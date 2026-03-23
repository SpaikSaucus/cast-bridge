package com.castbridge.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import android.view.Menu;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;

import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements VideoDetector.Callback, CastManager.Callback {

    private static final String TAG = "__cbx";
    static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private EditText urlEditText;
    private TextView statusText;
    private ProgressBar progressBar;
    private WebView browserWebView;
    private LinearLayout videoDetectedBanner;
    private TextView videoDetectedText;
    private LinearLayout controlsLayout;
    private ImageButton playPauseButton;
    private ImageButton stopButton;
    private ImageButton clearUrlButton;

    private final CastDiagnostics diag = new CastDiagnostics();
    private VideoDetector videoDetector;
    private CastManager castManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initViews();

        videoDetector = new VideoDetector(this, diag);
        castManager = new CastManager(this, this, diag);

        int playServicesResult = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this);
        if (playServicesResult != ConnectionResult.SUCCESS) {
            statusText.setText(R.string.status_no_play_services);
            return;
        }

        try {
            castManager.initCastSdk();
        } catch (Exception e) {
            statusText.setText("Error initializing Cast SDK: " + e.getMessage());
            return;
        }

        requestNotificationPermission();
        setupBrowserWebView();
        setupListeners();
        handleIntent(getIntent());
    }

    private void initViews() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        urlEditText = findViewById(R.id.urlEditText);
        statusText = findViewById(R.id.statusText);
        progressBar = findViewById(R.id.progressBar);
        browserWebView = findViewById(R.id.browserWebView);
        videoDetectedBanner = findViewById(R.id.videoDetectedBanner);
        videoDetectedText = findViewById(R.id.videoDetectedText);
        controlsLayout = findViewById(R.id.controlsLayout);
        playPauseButton = findViewById(R.id.playPauseButton);
        stopButton = findViewById(R.id.stopButton);
        clearUrlButton = findViewById(R.id.clearUrlButton);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                   != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    // --- WebView Setup ---

    @SuppressLint("SetJavaScriptEnabled")
    private void setupBrowserWebView() {
        WebSettings settings = browserWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setUserAgentString(USER_AGENT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);

        // Remove X-Requested-With header that reveals the app package name
        try {
            Class<?> wvc = Class.forName("androidx.webkit.WebViewCompat");
            Class<?> wvf = Class.forName("androidx.webkit.WebViewFeature");
            java.lang.reflect.Field field = wvf.getDeclaredField("REQUESTED_WITH_HEADER_CONTROL");
            String featureName = (String) field.get(null);
            java.lang.reflect.Method isSupported = wvf.getMethod("isFeatureSupported", String.class);
            if ((Boolean) isSupported.invoke(null, featureName)) {
                java.lang.reflect.Method setMode = wvc.getMethod(
                        "setRequestedWithHeaderMode", WebView.class, int.class);
                setMode.invoke(null, browserWebView, 2);
                Log.d(TAG, "X-Requested-With header removed");
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not remove X-Requested-With (not critical): " + e.getMessage());
        }

        browserWebView.addJavascriptInterface(videoDetector.getJsBridgeInterface(), "__cbx");

        browserWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                if (AdBlocker.shouldBlock(url)) {
                    return AdBlocker.getEmptyResponse();
                }

                if (videoDetector.isEmbedPageRequest(url, request)) {
                    WebResourceResponse injected = videoDetector.tryInjectHookIntoHtml(url, request);
                    if (injected != null) return injected;
                }

                if (UrlUtils.looksLikeVideoResource(url)) {
                    videoDetector.onVideoUrlDetected(url, request.getRequestHeaders());
                }

                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                if (url != null && !url.equals("about:blank")) {
                    urlEditText.setText(url);
                }
                injectAdBlockCss();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return true;
                }

                if (AdBlocker.isAdRedirect(url)) {
                    return true;
                }
                if (AdBlocker.shouldBlock(url)) {
                    return true;
                }

                String currentUrl = view.getUrl();
                if (currentUrl != null) {
                    String currentBase = AdBlocker.getBaseDomain(AdBlocker.extractHost(currentUrl));
                    String targetBase = AdBlocker.getBaseDomain(AdBlocker.extractHost(url));
                    if (currentBase != null && targetBase != null && !currentBase.equals(targetBase)) {
                        if (!videoDetector.isEmbedDomain(targetBase)) {
                            Log.d(TAG, "Blocked cross-domain navigation: " + targetBase);
                            return true;
                        }
                    }
                }

                return false;
            }
        });

        browserWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    progressBar.setVisibility(View.VISIBLE);
                } else {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, android.os.Message resultMsg) {
                return false;
            }
        });

        statusText.setText(R.string.status_browser_hint);
    }

    // --- Ad Block CSS Injection ---

    private void injectAdBlockCss() {
        if (browserWebView == null) return;

        String css =
                "[id*='ad-'], [id*='ads-'], [id*='advert'], " +
                "[class*='ad-container'], [class*='ad-wrapper'], [class*='ad-overlay'], " +
                "[class*='ads-container'], [class*='advert'], " +
                "[class*='popup'], [class*='pop-up'], [class*='popunder'], " +
                "[class*='overlay-ad'], [class*='modal-ad'], " +
                "[class*='interstitial'], [class*='lightbox-ad'], " +
                "[class*='floating-ad'], [class*='sticky-ad'], [class*='fixed-ad'], " +
                "[class*='vast-'], [class*='vpaid-'], " +
                "[id*='overlay'], [class*='overlay']:not(video):not([class*='player']):not([class*='control']), " +
                "iframe[src*='ads'], iframe[src*='doubleclick'], iframe[src*='googlesyndication'], " +
                "iframe[src*='popads'], iframe[src*='popunder'], " +
                "[class*='social-share'], [class*='share-buttons'] " +
                "{ display: none !important; visibility: hidden !important; " +
                "  height: 0 !important; width: 0 !important; " +
                "  overflow: hidden !important; position: absolute !important; " +
                "  pointer-events: none !important; }";

        String js = "(function() {" +
                "  var style = document.createElement('style');" +
                "  style.type = 'text/css';" +
                "  style.textContent = " + escapeJsString(css) + ";" +
                "  document.head.appendChild(style);" +
                "  window.open = function() { return null; };" +
                "  if (window.PopAds) window.PopAds = null;" +
                "  if (window.popns) window.popns = null;" +
                "  if (window.pop_config) window.pop_config = null;" +
                "})();";

        browserWebView.evaluateJavascript(js, null);
    }

    private static String escapeJsString(String s) {
        return "'" + s.replace("\\", "\\\\")
                     .replace("'", "\\'")
                     .replace("\n", "\\n")
                     .replace("\r", "\\r") + "'";
    }

    // --- Listeners ---

    private void setupListeners() {
        findViewById(R.id.goButton).setOnClickListener(v -> onGoButtonClicked());
        findViewById(R.id.sendToCastButton).setOnClickListener(v -> onSendToCastClicked());
        playPauseButton.setOnClickListener(v -> castManager.onPlayPauseClicked());
        stopButton.setOnClickListener(v -> castManager.onStopClicked());

        clearUrlButton.setOnClickListener(v -> {
            urlEditText.setText("");
            browserWebView.stopLoading();
            browserWebView.loadUrl("about:blank");
            videoDetectedBanner.setVisibility(View.GONE);
            videoDetector.clearAll();
            statusText.setText(R.string.status_browser_hint);
        });

        urlEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                clearUrlButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
        });

        urlEditText.setOnEditorActionListener((v, actionId, event) -> {
            onGoButtonClicked();
            return true;
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        CastButtonFactory.setUpMediaRouteButton(
                getApplicationContext(), menu, R.id.media_route_menu_item);
        return true;
    }

    // --- Lifecycle ---

    @Override
    protected void onResume() {
        super.onResume();
        castManager.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        castManager.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public void onBackPressed() {
        if (browserWebView.canGoBack()) {
            browserWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        castManager.onDestroy();
        if (browserWebView != null) {
            browserWebView.stopLoading();
            browserWebView.destroy();
        }
    }

    // --- Intent Handling ---

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();

        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(type)) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            String url = UrlUtils.extractUrl(sharedText);
            if (url != null && VideoDetector.isSafeHttpUrl(url)) {
                navigateTo(url);
            } else {
                statusText.setText("Could not find a valid URL in shared text.");
            }
        } else if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            String url = intent.getData().toString();
            if (VideoDetector.isSafeHttpUrl(url)) {
                navigateTo(url);
            }
        }
    }

    // --- Navigation ---

    private void onGoButtonClicked() {
        String url = urlEditText.getText().toString().trim();
        if (url.isEmpty()) {
            statusText.setText(R.string.status_enter_url);
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
            urlEditText.setText(url);
        }
        if (!VideoDetector.isSafeHttpUrl(url)) {
            statusText.setText("Invalid URL.");
            return;
        }
        navigateTo(url);
    }

    private void navigateTo(String url) {
        videoDetector.clearAll();
        videoDetectedBanner.setVisibility(View.GONE);

        urlEditText.setText(url);
        progressBar.setVisibility(View.VISIBLE);
        statusText.setText(R.string.status_browser_hint);
        browserWebView.loadUrl(url);

        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlEditText.getWindowToken(), 0);
        }
    }

    // --- Cast (bridges VideoDetector and CastManager) ---

    private void onSendToCastClicked() {
        if (videoDetector.hasDrmDetected()) {
            showErrorDialog(
                    "This server uses DRM encryption. Try selecting a different server.",
                    castManager.buildFullDiagnostics(null, null, null));
            return;
        }

        List<String> urls = videoDetector.getDetectedUrls();
        if (urls.isEmpty()) return;

        // Sort: master playlist first, then by score
        Collections.sort(urls, (a, b) -> {
            boolean aMaster = videoDetector.isMasterPlaylist(a);
            boolean bMaster = videoDetector.isMasterPlaylist(b);
            if (aMaster && !bMaster) return -1;
            if (!aMaster && bMaster) return 1;
            return Integer.compare(AdBlocker.scoreVideoUrl(b), AdBlocker.scoreVideoUrl(a));
        });

        if (urls.size() == 1) {
            castVideo(urls.get(0));
            return;
        }

        String[] items = new String[urls.size()];
        for (int i = 0; i < urls.size(); i++) {
            items[i] = videoDetector.getVideoLabel(urls.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.select_video)
                .setItems(items, (dialog, which) -> castVideo(urls.get(which)))
                .show();
    }

    private void castVideo(String videoUrl) {
        statusText.setText("Starting proxy...");
        progressBar.setVisibility(View.VISIBLE);
        castManager.castVideoUrl(videoUrl, videoDetector.getM3u8Cache(),
                videoDetector.getVideoHeaders(videoUrl));
    }

    // --- VideoDetector.Callback ---

    @Override
    public void onVideoDetected(int totalCount, String label) {
        runOnUiThread(() -> {
            if (totalCount == 1) {
                videoDetectedText.setText(R.string.video_detected);
            } else {
                videoDetectedText.setText(String.format(
                        getString(R.string.videos_detected_count), totalCount));
            }
            videoDetectedBanner.setVisibility(View.VISIBLE);
            statusText.setText(R.string.video_detected);
        });
    }

    @Override
    public void onDrmDetected(String errorMessage) {
        runOnUiThread(() -> {
            videoDetectedBanner.setVisibility(View.GONE);
            statusText.setText("DRM detected. This server encrypts video. Try another server.");
        });
    }

    @Override
    public void onDetectionsCleared() {
        runOnUiThread(() -> videoDetectedBanner.setVisibility(View.GONE));
    }

    @Override
    public void onStatusUpdate(String message) {
        runOnUiThread(() -> statusText.setText(message));
    }

    // --- CastManager.Callback ---

    @Override
    public void onCastSessionConnected(String deviceName, boolean isResumed) {
        runOnUiThread(() -> {
            statusText.setText(String.format(getString(R.string.status_connected), deviceName));
            showControls(isResumed);
        });
    }

    @Override
    public void onCastSessionDisconnected() {
        runOnUiThread(() -> {
            showControls(false);
            statusText.setText(R.string.status_disconnected);
        });
    }

    @Override
    public void onCastSessionFailed(int errorCode) {
        runOnUiThread(() -> {
            showControls(false);
            showErrorDialog(
                    "Could not connect to Chromecast. Make sure it's on the same Wi-Fi.",
                    "Connection failed with error code: " + errorCode);
        });
    }

    @Override
    public void onMediaStateChanged(int playerState, int idleReason) {
        runOnUiThread(() -> {
            switch (playerState) {
                case MediaStatus.PLAYER_STATE_PLAYING:
                    playPauseButton.setImageResource(android.R.drawable.ic_media_pause);
                    showControls(true);
                    break;
                case MediaStatus.PLAYER_STATE_PAUSED:
                case MediaStatus.PLAYER_STATE_BUFFERING:
                    playPauseButton.setImageResource(android.R.drawable.ic_media_play);
                    showControls(true);
                    break;
                case MediaStatus.PLAYER_STATE_IDLE:
                    if (idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                        statusText.setText("Playback finished.");
                    }
                    showControls(false);
                    break;
            }
        });
    }

    @Override
    public void onCastStarted(String videoUrl) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            statusText.setText(String.format(
                    getString(R.string.status_casting),
                    videoUrl.length() > 60 ? videoUrl.substring(0, 60) + "..." : videoUrl));
            showControls(true);
        });
    }

    @Override
    public void onCastFailed(String simpleMessage, String diagnostics) {
        runOnUiThread(() -> {
            progressBar.setVisibility(View.GONE);
            if (simpleMessage == null) {
                // No session yet, prompt user to select device
                statusText.setText(R.string.status_select_device);
            } else {
                showErrorDialog(simpleMessage, diagnostics);
            }
        });
    }

    @Override
    public void onCastRetryingWithProxy() {
        runOnUiThread(() -> statusText.setText(R.string.status_retrying_proxy));
    }

    @Override
    public void onPendingCastReady() {
        // Not used - CastManager handles pending cast internally
    }

    // --- UI Helpers ---

    private void showControls(boolean show) {
        controlsLayout.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showErrorDialog(String simpleMessage, String fullDiagnostics) {
        statusText.setText(simpleMessage);

        new AlertDialog.Builder(this)
                .setTitle("CastBridge")
                .setMessage(simpleMessage)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copy details", (dialog, which) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        android.content.ClipData clip =
                                android.content.ClipData.newPlainText("CastBridge Error", fullDiagnostics);
                        clipboard.setPrimaryClip(clip);
                        statusText.setText("Diagnostics copied to clipboard.");
                    }
                })
                .show();
    }
}
