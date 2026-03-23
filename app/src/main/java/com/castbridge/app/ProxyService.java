package com.castbridge.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManager;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

/**
 * Foreground service that keeps the {@link LocalStreamProxy} alive when the
 * screen is off. Acquires a WifiLock and a partial WakeLock so Android does
 * not suspend the proxy's network or CPU threads during Doze.
 *
 * <p>The service registers its own Cast session and media listeners so it can
 * stop itself when playback ends, even if the Activity is paused.</p>
 */
public class ProxyService extends Service {

    private static final String TAG = "ProxyService";
    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "castbridge_proxy";

    static final String ACTION_STOP = "com.castbridge.app.action.STOP_PROXY";

    private static volatile ProxyService instance;
    private static volatile LocalStreamProxy pendingProxy;

    private LocalStreamProxy proxy;
    private WifiManager.WifiLock wifiLock;
    private PowerManager.WakeLock wakeLock;

    private SessionManager sessionManager;
    private boolean listenersRegistered;

    // --- Public static API for CastManager ---

    /**
     * Starts the service and hands it the already-running proxy.
     * The proxy must be started before calling this method.
     */
    static void startWithProxy(Context context, LocalStreamProxy proxy) {
        pendingProxy = proxy;
        Intent intent = new Intent(context, ProxyService.class);
        ContextCompat.startForegroundService(context, intent);
    }

    /**
     * Requests the service to stop, releasing all resources.
     */
    static void stopProxy(Context context) {
        if (instance == null) return;
        Intent intent = new Intent(context, ProxyService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    /**
     * Returns the running proxy instance, or null if the service is not active.
     */
    static LocalStreamProxy getProxy() {
        ProxyService svc = instance;
        return svc != null ? svc.proxy : null;
    }

    // --- Service lifecycle ---

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            shutdown();
            return START_NOT_STICKY;
        }

        // Consume the pending proxy set by startWithProxy()
        LocalStreamProxy incoming = pendingProxy;
        pendingProxy = null;

        if (incoming == null) {
            Log.w(TAG, "Started without a proxy — stopping");
            shutdown();
            return START_NOT_STICKY;
        }

        // Replace any previous proxy
        if (proxy != null) {
            proxy.stop();
        }
        proxy = incoming;

        acquireLocks();

        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        registerCastListeners();

        Log.d(TAG, "Foreground service started — proxy on port " + proxy.getPort());
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterCastListeners();
        releaseLocks();
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // --- Cast session / media listeners (self-cleanup) ---

    private final SessionManagerListener<CastSession> serviceSessionListener =
            new SessionManagerListener<CastSession>() {
                @Override public void onSessionStarting(CastSession session) {}
                @Override public void onSessionStarted(CastSession session, String sessionId) {}
                @Override public void onSessionStartFailed(CastSession session, int error) {}
                @Override public void onSessionResuming(CastSession session, String sessionId) {}
                @Override public void onSessionResumed(CastSession session, boolean wasSuspended) {}
                @Override public void onSessionSuspended(CastSession session, int reason) {}
                @Override public void onSessionEnding(CastSession session) {}

                @Override
                public void onSessionEnded(CastSession session, int error) {
                    Log.d(TAG, "Cast session ended — stopping service");
                    shutdown();
                }

                @Override
                public void onSessionResumeFailed(CastSession session, int error) {
                    Log.d(TAG, "Cast session resume failed — stopping service");
                    shutdown();
                }
            };

    private final RemoteMediaClient.Callback serviceMediaCallback =
            new RemoteMediaClient.Callback() {
                @Override
                public void onStatusUpdated() {
                    if (sessionManager == null) return;
                    CastSession session = sessionManager.getCurrentCastSession();
                    if (session == null) return;
                    RemoteMediaClient client = session.getRemoteMediaClient();
                    if (client == null) return;
                    MediaStatus status = client.getMediaStatus();
                    if (status == null) return;

                    int playerState = status.getPlayerState();
                    if (playerState != MediaStatus.PLAYER_STATE_IDLE) return;

                    int idleReason = status.getIdleReason();
                    if (idleReason == MediaStatus.IDLE_REASON_FINISHED
                            || idleReason == MediaStatus.IDLE_REASON_CANCELED) {
                        Log.d(TAG, "Media idle (reason=" + idleReason + ") — stopping service");
                        shutdown();
                    }
                    // Note: IDLE_REASON_ERROR is NOT handled here.
                    // CastManager handles error-based fallback logic;
                    // it will call stopProxy() if appropriate.
                }
            };

    private void registerCastListeners() {
        try {
            CastContext castContext = CastContext.getSharedInstance(this);
            sessionManager = castContext.getSessionManager();
            sessionManager.addSessionManagerListener(serviceSessionListener, CastSession.class);

            CastSession session = sessionManager.getCurrentCastSession();
            if (session != null && session.getRemoteMediaClient() != null) {
                session.getRemoteMediaClient().registerCallback(serviceMediaCallback);
            }
            listenersRegistered = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to register Cast listeners: " + e.getMessage());
        }
    }

    private void unregisterCastListeners() {
        if (!listenersRegistered) return;
        try {
            if (sessionManager != null) {
                sessionManager.removeSessionManagerListener(
                        serviceSessionListener, CastSession.class);
                CastSession session = sessionManager.getCurrentCastSession();
                if (session != null && session.getRemoteMediaClient() != null) {
                    session.getRemoteMediaClient().unregisterCallback(serviceMediaCallback);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to unregister Cast listeners: " + e.getMessage());
        }
        listenersRegistered = false;
    }

    // --- Locks ---

    @SuppressWarnings("deprecation") // WIFI_MODE_FULL_HIGH_PERF still works on API 34
    private void acquireLocks() {
        WifiManager wm = (WifiManager) getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, "CastBridge::ProxyWifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        }

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "CastBridge::ProxyCpu");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private void releaseLocks() {
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    // --- Notification ---

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows while CastBridge is relaying video to Chromecast");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPending = PendingIntent.getActivity(
                this, 0, tapIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, ProxyService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_casting_title))
                .setContentText(getString(R.string.notif_casting_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(contentPending)
                .addAction(android.R.drawable.ic_media_pause,
                        getString(R.string.notif_action_stop), stopPending)
                .build();
    }

    // --- Shutdown ---

    private void shutdown() {
        unregisterCastListeners();
        if (proxy != null) {
            proxy.stop();
            proxy = null;
        }
        releaseLocks();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }
}
