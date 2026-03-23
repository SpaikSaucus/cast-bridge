package com.castbridge.app;

import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaLoadRequestData;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import java.util.Map;

class CastManager {

    private static final String TAG = "__cbx";

    interface Callback {
        void onCastSessionConnected(String deviceName, boolean isResumed);
        void onCastSessionDisconnected();
        void onCastSessionFailed(int errorCode);
        void onMediaStateChanged(int playerState, int idleReason);
        void onCastStarted(String videoUrl);
        void onCastFailed(String simpleMessage, String diagnostics);
        void onPendingCastReady();
    }

    private final AppCompatActivity activity;
    private final Callback callback;
    private final CastDiagnostics diag;

    private CastContext castContext;
    private CastSession castSession;
    private SessionManager sessionManager;
    private RemoteMediaClient.Callback mediaCallback;
    private LocalStreamProxy streamProxy;

    private String pendingUrl;
    private Map<String, String> pendingCache;
    private Map<String, String> pendingHeaders;
    private String lastCastUrl;
    private String lastCastContentType;

    CastManager(AppCompatActivity activity, Callback callback, CastDiagnostics diag) {
        this.activity = activity;
        this.callback = callback;
        this.diag = diag;

        mediaCallback = new RemoteMediaClient.Callback() {
            @Override
            public void onStatusUpdated() {
                updateMediaControls();
            }
        };
    }

    void initCastSdk() {
        castContext = CastContext.getSharedInstance(activity);
        if (castContext != null) {
            sessionManager = castContext.getSessionManager();
        }
    }

    // --- Lifecycle ---

    void onResume() {
        if (sessionManager != null) {
            sessionManager.addSessionManagerListener(sessionListener, CastSession.class);
            CastSession current = sessionManager.getCurrentCastSession();
            if (current != null && current.isConnected()) {
                castSession = current;
                registerMediaCallback();
                callback.onCastSessionConnected(current.getCastDevice().getFriendlyName(), true);
            }
        }
    }

    void onPause() {
        if (sessionManager != null) {
            sessionManager.removeSessionManagerListener(sessionListener, CastSession.class);
        }
        unregisterMediaCallback();
    }

    void onDestroy() {
        if (streamProxy != null) {
            streamProxy.stop();
        }
    }

    // --- Cast Video ---

    void castVideoUrl(String videoUrl, Map<String, String> m3u8Cache, Map<String, String> originalHeaders) {
        if (castSession == null || !castSession.isConnected()) {
            pendingUrl = videoUrl;
            pendingCache = m3u8Cache;
            pendingHeaders = originalHeaders;
            callback.onCastFailed(null, null); // signals "select device" state
            return;
        }

        pendingUrl = null;
        pendingCache = null;
        pendingHeaders = null;

        // Update diagnostics
        boolean isCached;
        synchronized (m3u8Cache) {
            isCached = m3u8Cache.containsKey(videoUrl);
            diag.m3u8CacheSize = m3u8Cache.size();
        }
        diag.proxyServeMode = isCached ? "cache" : "cdn";

        startProxyAndCast(videoUrl, m3u8Cache, originalHeaders);
    }

    private void startProxyAndCast(String videoUrl, Map<String, String> m3u8Cache,
                                    Map<String, String> originalHeaders) {
        if (streamProxy != null) streamProxy.stop();
        streamProxy = new LocalStreamProxy();

        try {
            streamProxy.setM3u8Cache(m3u8Cache);
            streamProxy.start(originalHeaders);
            Log.d(TAG, "Proxy started on port " + streamProxy.getPort() +
                    " | m3u8 cached: " + m3u8Cache.size());
        } catch (Exception e) {
            callback.onCastFailed("Could not start local proxy. Check Wi-Fi connection.",
                    "Proxy start failed: " + e.getMessage() + "\n\n" + diag);
            return;
        }

        String proxyUrl = streamProxy.getProxyUrl(videoUrl);
        if (proxyUrl == null) {
            callback.onCastFailed("No Wi-Fi connection detected. Check that your phone is on Wi-Fi.",
                    "Proxy URL failed: Local IP unavailable.\n\n" + diag);
            return;
        }

        String contentType = UrlUtils.inferContentType(videoUrl);
        lastCastUrl = videoUrl;
        lastCastContentType = contentType;

        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        metadata.putString(MediaMetadata.KEY_TITLE, "__cbx");

        MediaInfo mediaInfo = new MediaInfo.Builder(proxyUrl)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(contentType)
                .setMetadata(metadata)
                .build();

        MediaLoadRequestData loadRequest = new MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build();

        RemoteMediaClient remoteMediaClient = castSession.getRemoteMediaClient();
        if (remoteMediaClient != null) {
            try {
                remoteMediaClient.load(loadRequest)
                        .setResultCallback(result -> {
                            if (result.getStatus().isSuccess()) {
                                callback.onCastStarted(videoUrl);
                            } else {
                                callback.onCastFailed(
                                        "Cast failed. The Chromecast could not play this video.",
                                        buildFullDiagnostics(videoUrl, proxyUrl, contentType));
                            }
                        });
            } catch (Exception e) {
                callback.onCastFailed(
                        "An unexpected error occurred while casting.",
                        "Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage() + "\n\n" +
                        buildFullDiagnostics(videoUrl, proxyUrl, contentType));
            }
        }
    }

    // --- Media Controls ---

    void onPlayPauseClicked() {
        RemoteMediaClient client = getRemoteMediaClient();
        if (client == null) return;
        if (client.isPlaying()) client.pause();
        else client.play();
        updateMediaControls();
    }

    void onStopClicked() {
        RemoteMediaClient client = getRemoteMediaClient();
        if (client == null) return;
        client.stop();
        callback.onMediaStateChanged(MediaStatus.PLAYER_STATE_IDLE, MediaStatus.IDLE_REASON_CANCELED);
    }

    private RemoteMediaClient getRemoteMediaClient() {
        if (castSession != null && castSession.isConnected()) {
            return castSession.getRemoteMediaClient();
        }
        return null;
    }

    private void updateMediaControls() {
        RemoteMediaClient client = getRemoteMediaClient();
        if (client == null) return;
        MediaStatus status = client.getMediaStatus();
        if (status == null) return;

        int playerState = status.getPlayerState();
        int idleReason = (playerState == MediaStatus.PLAYER_STATE_IDLE)
                ? status.getIdleReason() : 0;

        if (playerState == MediaStatus.PLAYER_STATE_IDLE &&
                idleReason == MediaStatus.IDLE_REASON_ERROR) {
            callback.onCastFailed(
                    "Playback error. The Chromecast could not play this video format.",
                    buildFullDiagnostics(lastCastUrl, null, lastCastContentType));
        }

        callback.onMediaStateChanged(playerState, idleReason);
    }

    private void registerMediaCallback() {
        RemoteMediaClient client = getRemoteMediaClient();
        if (client != null) client.registerCallback(mediaCallback);
    }

    private void unregisterMediaCallback() {
        RemoteMediaClient client = getRemoteMediaClient();
        if (client != null) client.unregisterCallback(mediaCallback);
    }

    // --- Diagnostics ---

    String buildFullDiagnostics(String videoUrl, String proxyUrl, String contentType) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Video Info ---\n");
        sb.append("URL: ").append(videoUrl != null ? videoUrl : "none").append("\n");
        if (proxyUrl != null) sb.append("Proxy URL: ").append(proxyUrl).append("\n");
        sb.append("Content-Type: ").append(contentType != null ? contentType : "none").append("\n");
        sb.append("Device: ").append(getDeviceInfo()).append("\n\n");

        sb.append("--- JS Hook Diagnostics ---\n");
        sb.append(diag).append("\n");

        sb.append("--- Proxy Diagnostics ---\n");
        if (streamProxy != null) {
            sb.append(streamProxy.getDiagnostics());
        } else {
            sb.append("Proxy: not started\n");
        }

        return sb.toString();
    }

    private String getDeviceInfo() {
        if (castSession != null) {
            try {
                return castSession.getCastDevice().getFriendlyName() +
                        " (" + castSession.getCastDevice().getModelName() + ")";
            } catch (Exception e) { /* ignore */ }
        }
        return "No active session";
    }

    // --- Session Listener ---

    private final SessionManagerListener<CastSession> sessionListener =
            new SessionManagerListener<CastSession>() {

                @Override
                public void onSessionStarted(CastSession session, String sessionId) {
                    castSession = session;
                    registerMediaCallback();
                    callback.onCastSessionConnected(session.getCastDevice().getFriendlyName(), false);
                    activity.invalidateOptionsMenu();
                    if (pendingUrl != null) {
                        castVideoUrl(pendingUrl, pendingCache, pendingHeaders);
                    }
                }

                @Override
                public void onSessionResumed(CastSession session, boolean wasSuspended) {
                    castSession = session;
                    registerMediaCallback();
                    callback.onCastSessionConnected(session.getCastDevice().getFriendlyName(), true);
                    activity.invalidateOptionsMenu();
                }

                @Override
                public void onSessionEnded(CastSession session, int error) {
                    unregisterMediaCallback();
                    castSession = null;
                    callback.onCastSessionDisconnected();
                    activity.invalidateOptionsMenu();
                }

                @Override
                public void onSessionStartFailed(CastSession session, int error) {
                    castSession = null;
                    callback.onCastSessionFailed(error);
                }

                @Override public void onSessionStarting(CastSession session) {}
                @Override public void onSessionEnding(CastSession session) {}
                @Override public void onSessionResuming(CastSession session, String sessionId) {}
                @Override public void onSessionResumeFailed(CastSession session, int error) {
                    castSession = null;
                    callback.onCastSessionDisconnected();
                }
                @Override public void onSessionSuspended(CastSession session, int reason) {
                    unregisterMediaCallback();
                }
            };
}
