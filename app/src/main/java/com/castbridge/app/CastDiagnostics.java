package com.castbridge.app;

class CastDiagnostics {
    boolean htmlIntercepted = false;
    String interceptedDomain = null;
    int htmlFetchStatus = 0;
    boolean scriptInjected = false;
    boolean jsHookActive = false;
    String hookFrameUrl = null;
    boolean jsHookCaptured = false;
    int capturedContentLength = 0;
    String capturedUrl = null;
    int m3u8CacheSize = 0;
    String proxyServeMode = "none";
    String lastError = null;
    boolean drmDetected = false;
    String drmErrorMessage = null;

    void reset() {
        htmlIntercepted = false;
        interceptedDomain = null;
        htmlFetchStatus = 0;
        scriptInjected = false;
        jsHookActive = false;
        hookFrameUrl = null;
        jsHookCaptured = false;
        capturedContentLength = 0;
        capturedUrl = null;
        m3u8CacheSize = 0;
        proxyServeMode = "none";
        lastError = null;
        drmDetected = false;
        drmErrorMessage = null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("[1] HTML intercept: ").append(htmlIntercepted ? "YES" : "NO");
        if (interceptedDomain != null) sb.append(" (").append(interceptedDomain).append(")");
        if (htmlFetchStatus > 0) sb.append(" status=").append(htmlFetchStatus);
        sb.append("\n");

        sb.append("[2] Script injected: ").append(scriptInjected ? "YES" : "NO").append("\n");

        sb.append("[3] JS hook active: ").append(jsHookActive ? "YES" : "NO");
        if (hookFrameUrl != null) {
            String frame = hookFrameUrl.length() > 60 ?
                    hookFrameUrl.substring(0, 60) + "..." : hookFrameUrl;
            sb.append(" in ").append(frame);
        }
        sb.append("\n");

        sb.append("[4] m3u8 captured via JS: ").append(jsHookCaptured ? "YES" : "NO");
        if (capturedContentLength > 0) sb.append(" (").append(capturedContentLength).append(" bytes)");
        sb.append("\n");

        sb.append("[5] m3u8 cache size: ").append(m3u8CacheSize).append("\n");
        sb.append("[6] Proxy serve mode: ").append(proxyServeMode).append("\n");

        if (drmDetected) {
            sb.append("[!] DRM DETECTED");
            if (drmErrorMessage != null) sb.append(": ").append(drmErrorMessage);
            sb.append("\n");
        }
        if (lastError != null) sb.append("[!] Error: ").append(lastError).append("\n");

        return sb.toString();
    }
}
