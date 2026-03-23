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

    /**
     * Tracks the current casting state so the media callback can distinguish
     * between a direct-cast error (which triggers proxy fallback) and a
     * proxy-cast error (which is reported to the user).
     */
    enum CastMode {
        IDLE,
        DIRECT_ATTEMPT,
        PROXY_ATTEMPT,
        PLAYING_DIRECT,
        PLAYING_PROXY
    }

    interface Callback {
        void onCastSessionConnected(String deviceName, boolean isResumed);
        void onCastSessionDisconnected();
        void onCastSessionFailed(int errorCode);
        void onMediaStateChanged(int playerState, int idleReason);
        void onCastStarted(String videoUrl);
        void onCastFailed(String simpleMessage, String diagnostics);
        void onCastRetryingWithProxy();
        void onPendingCastReady();
    }

    private final AppCompatActivity activity;
    private final Callback callback;
    private final CastDiagnostics diag;

    private CastContext castContext;
    private CastSession castSession;
    private SessionManager sessionManager;
    private RemoteMediaClient.Callback mediaCallback;

    private CastMode castMode = CastMode.IDLE;

    // Pending cast (waiting for a session to connect)
    private String pendingUrl;
    private Map<String, String> pendingCache;
    private Map<String, String> pendingHeaders;

    // Saved for proxy fallback after a failed direct attempt
    private String lastCastUrl;
    private String lastCastContentType;
    private Map<String, String> lastM3u8Cache;
    private Map<String, String> lastOriginalHeaders;

    CastManager(AppCompatActivity activity, Callback callback, CastDiagnostics diag) {
        this.activity = activity;
        this.callback = callback;
        this.diag = diag;

        mediaCallback = new RemoteMediaClient.Callback() {
            @Override
            public void onStatusUpdated() {
                handleMediaStatusUpdate();
            }
        };
    }

    void initCastSdk() {
        castContext = CastContext.getSharedInstance(activity);
        if (castContext != null) {
            sessionManager = castContext.getSessionManager();
        }
    }

    CastMode getCastMode() {
        return castMode;
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
        ProxyService.stopProxy(activity);
        castMode = CastMode.IDLE;
    }

    // --- Cast Video ---

    void castVideoUrl(String videoUrl, Map<String, String> m3u8Cache,
                      Map<String, String> originalHeaders) {
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

        // Save for potential proxy fallback
        lastM3u8Cache = m3u8Cache;
        lastOriginalHeaders = originalHeaders;

        // Update diagnostics
        synchronized (m3u8Cache) {
            diag.m3u8CacheSize = m3u8Cache.size();
        }

        attemptDirectCast(videoUrl);
    }

    // --- Direct Cast (no proxy) ---

    private void attemptDirectCast(String videoUrl) {
        castMode = CastMode.DIRECT_ATTEMPT;
        String contentType = UrlUtils.inferContentType(videoUrl);
        lastCastUrl = videoUrl;
        lastCastContentType = contentType;

        Log.d(TAG, "Attempting direct cast: " + videoUrl);

        MediaLoadRequestData loadRequest = buildLoadRequest(videoUrl, contentType);
        RemoteMediaClient client = getRemoteMediaClient();
        if (client == null) {
            callback.onCastFailed("No active Cast session.",
                    buildFullDiagnostics(videoUrl, null, contentType));
            castMode = CastMode.IDLE;
            return;
        }

        try {
            client.load(loadRequest).setResultCallback(result -> {
                if (!result.getStatus().isSuccess()
                        && castMode == CastMode.DIRECT_ATTEMPT) {
                    Log.d(TAG, "Direct cast load failed — falling back to proxy");
                    fallbackToProxy();
                }
                // On success, wait for media callback to confirm playback or report error
            });
        } catch (Exception e) {
            Log.e(TAG, "Direct cast exception: " + e.getMessage());
            fallbackToProxy();
        }
    }

    // --- Proxy Fallback ---

    private void fallbackToProxy() {
        callback.onCastRetryingWithProxy();
        startProxyAndCast(lastCastUrl, lastM3u8Cache, lastOriginalHeaders);
    }

    private void startProxyAndCast(String videoUrl, Map<String, String> m3u8Cache,
                                    Map<String, String> originalHeaders) {
        castMode = CastMode.PROXY_ATTEMPT;

        // Stop any previous proxy service
        ProxyService.stopProxy(activity);

        LocalStreamProxy streamProxy = new LocalStreamProxy();
        try {
            streamProxy.setM3u8Cache(m3u8Cache);
            streamProxy.start(originalHeaders);
            Log.d(TAG, "Proxy started on port " + streamProxy.getPort()
                    + " | m3u8 cached: " + m3u8Cache.size());
        } catch (Exception e) {
            castMode = CastMode.IDLE;
            callback.onCastFailed("Could not start local proxy. Check Wi-Fi connection.",
                    "Proxy start failed: " + e.getMessage() + "\n\n" + diag);
            return;
        }

        String proxyUrl = streamProxy.getProxyUrl(videoUrl);
        if (proxyUrl == null) {
            streamProxy.stop();
            castMode = CastMode.IDLE;
            callback.onCastFailed(
                    "No Wi-Fi connection detected. Check that your phone is on Wi-Fi.",
                    "Proxy URL failed: Local IP unavailable.\n\n" + diag);
            return;
        }

        // Hand the proxy to the foreground service so it survives screen-off
        ProxyService.startWithProxy(activity, streamProxy);

        diag.proxyServeMode = m3u8Cache.containsKey(videoUrl) ? "cache" : "cdn";

        String contentType = UrlUtils.inferContentType(videoUrl);
        lastCastUrl = videoUrl;
        lastCastContentType = contentType;

        MediaLoadRequestData loadRequest = buildLoadRequest(proxyUrl, contentType);
        RemoteMediaClient client = getRemoteMediaClient();
        if (client == null) {
            ProxyService.stopProxy(activity);
            castMode = CastMode.IDLE;
            callback.onCastFailed("No active Cast session.",
                    buildFullDiagnostics(videoUrl, proxyUrl, contentType));
            return;
        }

        try {
            client.load(loadRequest).setResultCallback(result -> {
                if (result.getStatus().isSuccess()) {
                    castMode = CastMode.PLAYING_PROXY;
                    callback.onCastStarted(videoUrl);
                } else {
                    ProxyService.stopProxy(activity);
                    castMode = CastMode.IDLE;
                    callback.onCastFailed(
                            "Cast failed. The Chromecast could not play this video.",
                            buildFullDiagnostics(videoUrl, proxyUrl, contentType));
                }
            });
        } catch (Exception e) {
            ProxyService.stopProxy(activity);
            castMode = CastMode.IDLE;
            callback.onCastFailed(
                    "An unexpected error occurred while casting.",
                    "Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                            + "\n\n" + buildFullDiagnostics(videoUrl, proxyUrl, contentType));
        }
    }

    // --- Media Status Handling ---

    private void handleMediaStatusUpdate() {
        RemoteMediaClient client = getRemoteMediaClient();
        if (client == null) return;
        MediaStatus status = client.getMediaStatus();
        if (status == null) return;

        int playerState = status.getPlayerState();
        int idleReason = (playerState == MediaStatus.PLAYER_STATE_IDLE)
                ? status.getIdleReason() : 0;

        if (playerState == MediaStatus.PLAYER_STATE_PLAYING) {
            if (castMode == CastMode.DIRECT_ATTEMPT) {
                castMode = CastMode.PLAYING_DIRECT;
                Log.d(TAG, "Direct cast playing successfully");
                callback.onCastStarted(lastCastUrl);
            } else if (castMode == CastMode.PROXY_ATTEMPT) {
                castMode = CastMode.PLAYING_PROXY;
                Log.d(TAG, "Proxy cast playing successfully");
                callback.onCastStarted(lastCastUrl);
            }
        }

        if (playerState == MediaStatus.PLAYER_STATE_IDLE
                && idleReason == MediaStatus.IDLE_REASON_ERROR) {
            if (castMode == CastMode.DIRECT_ATTEMPT) {
                Log.d(TAG, "Direct cast playback error — falling back to proxy");
                fallbackToProxy();
                return; // Don't propagate the error to the UI
            }
            if (castMode == CastMode.PROXY_ATTEMPT || castMode == CastMode.PLAYING_PROXY) {
                ProxyService.stopProxy(activity);
                castMode = CastMode.IDLE;
                callback.onCastFailed(
                        "Playback error. The Chromecast could not play this video format.",
                        buildFullDiagnostics(lastCastUrl, null, lastCastContentType));
            }
        }

        // Clean up proxy on non-error idle (finished / canceled)
        if (playerState == MediaStatus.PLAYER_STATE_IDLE
                && (idleReason == MediaStatus.IDLE_REASON_FINISHED
                    || idleReason == MediaStatus.IDLE_REASON_CANCELED)
                && castMode == CastMode.PLAYING_PROXY) {
            ProxyService.stopProxy(activity);
            castMode = CastMode.IDLE;
        }

        callback.onMediaStateChanged(playerState, idleReason);
    }

    // --- Media Controls ---

    void onPlayPauseClicked() {
        RemoteMediaClient client = getRemoteMediaClient();
        if (client == null) return;
        if (client.isPlaying()) client.pause();
        else client.play();
    }

    void onStopClicked() {
        RemoteMediaClient client = getRemoteMediaClient();
        if (client == null) return;
        client.stop();
        if (castMode == CastMode.PLAYING_PROXY || castMode == CastMode.PROXY_ATTEMPT) {
            ProxyService.stopProxy(activity);
        }
        castMode = CastMode.IDLE;
        callback.onMediaStateChanged(MediaStatus.PLAYER_STATE_IDLE,
                MediaStatus.IDLE_REASON_CANCELED);
    }

    private RemoteMediaClient getRemoteMediaClient() {
        if (castSession != null && castSession.isConnected()) {
            return castSession.getRemoteMediaClient();
        }
        return null;
    }

    private void registerMediaCallback() {
        RemoteMediaClient client = getRemoteMediaClient();
        if (client != null) client.registerCallback(mediaCallback);
    }

    private void unregisterMediaCallback() {
        RemoteMediaClient client = getRemoteMediaClient();
        if (client != null) client.unregisterCallback(mediaCallback);
    }

    // --- Helpers ---

    private MediaLoadRequestData buildLoadRequest(String url, String contentType) {
        MediaMetadata metadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);
        metadata.putString(MediaMetadata.KEY_TITLE, "__cbx");

        MediaInfo mediaInfo = new MediaInfo.Builder(url)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType(contentType)
                .setMetadata(metadata)
                .build();

        return new MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .build();
    }

    // --- Diagnostics ---

    String buildFullDiagnostics(String videoUrl, String proxyUrl, String contentType) {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Video Info ---\n");
        sb.append("URL: ").append(videoUrl != null ? videoUrl : "none").append("\n");
        if (proxyUrl != null) sb.append("Proxy URL: ").append(proxyUrl).append("\n");
        sb.append("Content-Type: ").append(contentType != null ? contentType : "none").append("\n");
        sb.append("Cast mode: ").append(castMode).append("\n");
        sb.append("Device: ").append(getDeviceInfo()).append("\n\n");

        sb.append("--- JS Hook Diagnostics ---\n");
        sb.append(diag).append("\n");

        sb.append("--- Proxy Diagnostics ---\n");
        LocalStreamProxy proxy = ProxyService.getProxy();
        if (proxy != null) {
            sb.append(proxy.getDiagnostics());
        } else {
            sb.append("Proxy: not started\n");
        }

        return sb.toString();
    }

    private String getDeviceInfo() {
        if (castSession != null) {
            try {
                return castSession.getCastDevice().getFriendlyName()
                        + " (" + castSession.getCastDevice().getModelName() + ")";
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
                    callback.onCastSessionConnected(
                            session.getCastDevice().getFriendlyName(), false);
                    activity.invalidateOptionsMenu();
                    if (pendingUrl != null) {
                        castVideoUrl(pendingUrl, pendingCache, pendingHeaders);
                    }
                }

                @Override
                public void onSessionResumed(CastSession session, boolean wasSuspended) {
                    castSession = session;
                    registerMediaCallback();
                    callback.onCastSessionConnected(
                            session.getCastDevice().getFriendlyName(), true);
                    activity.invalidateOptionsMenu();
                }

                @Override
                public void onSessionEnded(CastSession session, int error) {
                    unregisterMediaCallback();
                    castSession = null;
                    if (castMode == CastMode.PLAYING_PROXY
                            || castMode == CastMode.PROXY_ATTEMPT) {
                        ProxyService.stopProxy(activity);
                    }
                    castMode = CastMode.IDLE;
                    callback.onCastSessionDisconnected();
                    activity.invalidateOptionsMenu();
                }

                @Override
                public void onSessionStartFailed(CastSession session, int error) {
                    castSession = null;
                    castMode = CastMode.IDLE;
                    callback.onCastSessionFailed(error);
                }

                @Override public void onSessionStarting(CastSession session) {}
                @Override public void onSessionEnding(CastSession session) {}
                @Override public void onSessionResuming(CastSession session, String sessionId) {}

                @Override
                public void onSessionResumeFailed(CastSession session, int error) {
                    castSession = null;
                    if (castMode == CastMode.PLAYING_PROXY
                            || castMode == CastMode.PROXY_ATTEMPT) {
                        ProxyService.stopProxy(activity);
                    }
                    castMode = CastMode.IDLE;
                    callback.onCastSessionDisconnected();
                }

                @Override
                public void onSessionSuspended(CastSession session, int reason) {
                    unregisterMediaCallback();
                }
            };
}
