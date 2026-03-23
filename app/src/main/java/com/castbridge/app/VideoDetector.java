package com.castbridge.app;

import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

class VideoDetector {

    private static final String TAG = "__cbx";

    interface Callback {
        void onVideoDetected(int totalCount, String label);
        void onDrmDetected(String errorMessage);
        void onDetectionsCleared();
        void onStatusUpdate(String message);
    }

    private final Callback callback;
    private final CastDiagnostics diag;

    private final Set<String> detectedVideoUrls = new LinkedHashSet<>();
    private final Map<String, Map<String, String>> videoHeaders = new LinkedHashMap<>();
    private final Map<String, String> m3u8Cache = new LinkedHashMap<>();
    private String lastDetectedVideoUrl;

    private final JsBridgeInterface jsBridgeInterface = new JsBridgeInterface();

    // Known embed/video player domains where we inject fetch/XHR hooks
    private static final Set<String> EMBED_DOMAINS = new HashSet<>();
    static {
        EMBED_DOMAINS.add("vimeos.net"); EMBED_DOMAINS.add("vimeos.zip");
        EMBED_DOMAINS.add("streamwish.com"); EMBED_DOMAINS.add("streamwish.to");
        EMBED_DOMAINS.add("filemoon.sx"); EMBED_DOMAINS.add("filemoon.to"); EMBED_DOMAINS.add("filemoon.in");
        EMBED_DOMAINS.add("doodstream.com"); EMBED_DOMAINS.add("dood.to");
        EMBED_DOMAINS.add("mixdrop.co"); EMBED_DOMAINS.add("mixdrop.to");
        EMBED_DOMAINS.add("upstream.to"); EMBED_DOMAINS.add("vidoza.net");
        EMBED_DOMAINS.add("streamtape.com"); EMBED_DOMAINS.add("mp4upload.com");
        EMBED_DOMAINS.add("wishembed.pro"); EMBED_DOMAINS.add("hlswish.com");
        EMBED_DOMAINS.add("embedwish.com"); EMBED_DOMAINS.add("streamhub.to");
        EMBED_DOMAINS.add("vidhide.com"); EMBED_DOMAINS.add("vidhidepro.com");
        EMBED_DOMAINS.add("playhydrax.com");
        EMBED_DOMAINS.add("goodstream.one"); EMBED_DOMAINS.add("dianaavoidthey.com");
        EMBED_DOMAINS.add("rabbitstream.net"); EMBED_DOMAINS.add("megacloud.tv");
        EMBED_DOMAINS.add("dokicloud.one"); EMBED_DOMAINS.add("rapid-cloud.co");
    }

    // JavaScript hook that captures fetch/XHR responses for m3u8/mpd playlists.
    // Injected into embed page HTML so it runs INSIDE the iframe context.
    // __cbx JS interface is available in all frames via addJavascriptInterface.
    static final String JS_HOOK_SCRIPT =
        "<script>(function(){" +
        // Anti-detection: override navigator.userAgentData to hide WebView
        "try{Object.defineProperty(navigator,'userAgentData',{value:{" +
        "brands:[{brand:'Chromium',version:'120'},{brand:'Google Chrome',version:'120'},{brand:'Not:A-Brand',version:'99'}]," +
        "mobile:true,platform:'Android'," +
        "getHighEntropyValues:function(){return Promise.resolve({brands:this.brands,mobile:true,platform:'Android',platformVersion:'13',architecture:'arm64',model:'Pixel 7',bitness:'64',wow64:false});}" +
        "},configurable:false});}catch(e){}" +
        "var CB=typeof __cbx!=='undefined'?__cbx:null;" +
        "if(CB)CB.hookActive(window.location.href);" +
        // DRM error detection: listen for video errors including DRM failures
        "document.addEventListener('error',function(e){" +
        "  if(e.target&&e.target.tagName==='VIDEO'&&e.target.error){" +
        "    var err=e.target.error;" +
        "    try{if(CB)CB.onPlayerError(err.code,err.message||'');}catch(x){}" +
        "  }" +
        "},true);" +
        // Also catch JW Player / common player error events
        "window.addEventListener('message',function(e){" +
        "  try{" +
        "    var d=typeof e.data==='string'?JSON.parse(e.data):e.data;" +
        "    if(d&&d.event==='error'&&d.code){" +
        "      if(CB)CB.onPlayerError(d.code,d.message||JSON.stringify(d));" +
        "    }" +
        "  }catch(x){}" +
        "});" +
        // Helper: resolve relative URLs to absolute
        "function _abs(u){try{return new URL(u,window.location.href).href;}catch(e){return u;}}" +
        // Hook fetch() - use response.url for absolute URL
        "var oF=window.fetch;" +
        "window.fetch=function(){" +
        "  return oF.apply(this,arguments).then(function(r){" +
        "    var au=r.url||_abs(String(arguments&&arguments[0]));" +
        "    if(au&&(au.indexOf('.m3u8')!==-1||au.indexOf('.mpd')!==-1)){" +
        "      r.clone().text().then(function(t){" +
        "        try{if(CB)CB.cacheM3u8(au,t);}catch(e){}" +
        "      });" +
        "    }" +
        "    return r;" +
        "  });" +
        "};" +
        // Hook XMLHttpRequest - resolve URL to absolute
        "var oO=XMLHttpRequest.prototype.open;" +
        "XMLHttpRequest.prototype.open=function(m,u){this._cbu=_abs(u);return oO.apply(this,arguments);};" +
        "var oS=XMLHttpRequest.prototype.send;" +
        "XMLHttpRequest.prototype.send=function(){" +
        "  var x=this;this.addEventListener('load',function(){" +
        "    if(x._cbu&&(x._cbu.indexOf('.m3u8')!==-1||x._cbu.indexOf('.mpd')!==-1)){" +
        "      try{if(CB)CB.cacheM3u8(x._cbu,x.responseText);}catch(e){}" +
        "    }" +
        "  });return oS.apply(this,arguments);" +
        "};" +
        // Hook <video> src - resolve and fetch content
        "var vDesc=Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype,'src');" +
        "if(vDesc&&vDesc.set){" +
        "  var oSet=vDesc.set;" +
        "  Object.defineProperty(HTMLMediaElement.prototype,'src',{" +
        "    set:function(v){" +
        "      var av=_abs(v);" +
        "      if(av&&(av.indexOf('.m3u8')!==-1||av.indexOf('.mpd')!==-1)){" +
        "        try{" +
        "          fetch(av).then(function(r){return r.text();}).then(function(t){" +
        "            try{if(CB)CB.cacheM3u8(av,t);}catch(e){}" +
        "          }).catch(function(){});" +
        "        }catch(e){}" +
        "      }" +
        "      return oSet.call(this,v);" +
        "    }," +
        "    get:vDesc.get," +
        "    configurable:true" +
        "  });" +
        "}" +
        // Hook setAttribute - resolve URL
        "var oSA=Element.prototype.setAttribute;" +
        "Element.prototype.setAttribute=function(n,v){" +
        "  if((n==='src')&&v&&(v.indexOf('.m3u8')!==-1||v.indexOf('.mpd')!==-1)){" +
        "    var av=_abs(v);" +
        "    try{" +
        "      fetch(av).then(function(r){return r.text();}).then(function(t){" +
        "        try{if(CB)CB.cacheM3u8(av,t);}catch(e){}" +
        "      }).catch(function(){});" +
        "    }catch(e){}" +
        "  }" +
        "  return oSA.call(this,n,v);" +
        "};" +
        // Watch for dynamically created video elements
        "new MutationObserver(function(muts){" +
        "  muts.forEach(function(m){" +
        "    m.addedNodes.forEach(function(n){" +
        "      if(n.tagName==='VIDEO'||n.tagName==='SOURCE'){" +
        "        var s=n.src||n.getAttribute('src');" +
        "        if(s&&(s.indexOf('.m3u8')!==-1||s.indexOf('.mpd')!==-1)){" +
        "          try{" +
        "            fetch(s).then(function(r){return r.text();}).then(function(t){" +
        "              try{if(CB)CB.cacheM3u8(s,t);}catch(e){}" +
        "            }).catch(function(){});" +
        "          }catch(e){}" +
        "        }" +
        "      }" +
        "      if(n.querySelectorAll){" +
        "        n.querySelectorAll('video,source').forEach(function(v){" +
        "          var s=v.src||v.getAttribute('src');" +
        "          if(s&&(s.indexOf('.m3u8')!==-1||s.indexOf('.mpd')!==-1)){" +
        "            try{" +
        "              fetch(s).then(function(r){return r.text();}).then(function(t){" +
        "                try{if(CB)CB.cacheM3u8(s,t);}catch(e){}" +
        "              }).catch(function(){});" +
        "            }catch(e){}" +
        "          }" +
        "        });" +
        "      }" +
        "    });" +
        "  });" +
        "}).observe(document.documentElement||document.body||document,{childList:true,subtree:true});" +
        "})();</script>";

    VideoDetector(Callback callback, CastDiagnostics diag) {
        this.callback = callback;
        this.diag = diag;
    }

    JsBridgeInterface getJsBridgeInterface() {
        return jsBridgeInterface;
    }

    Map<String, String> getM3u8Cache() {
        return m3u8Cache;
    }

    Map<String, String> getVideoHeaders(String url) {
        synchronized (detectedVideoUrls) {
            Map<String, String> headers = videoHeaders.get(url);
            return headers != null ? new LinkedHashMap<>(headers) : null;
        }
    }

    List<String> getDetectedUrls() {
        List<String> urls = new ArrayList<>();
        synchronized (m3u8Cache) {
            urls.addAll(m3u8Cache.keySet());
        }
        synchronized (detectedVideoUrls) {
            for (String u : detectedVideoUrls) {
                if (!urls.contains(u)) urls.add(u);
            }
        }
        return urls;
    }

    boolean hasDrmDetected() {
        return diag.drmDetected;
    }

    void clearAll() {
        synchronized (detectedVideoUrls) {
            detectedVideoUrls.clear();
            videoHeaders.clear();
        }
        synchronized (m3u8Cache) {
            m3u8Cache.clear();
        }
        diag.reset();
        lastDetectedVideoUrl = null;
    }

    boolean isEmbedDomain(String baseDomain) {
        return EMBED_DOMAINS.contains(baseDomain);
    }

    // --- Video Detection ---

    void onVideoUrlDetected(String url, Map<String, String> headers) {
        if (url == null || url.isEmpty()) return;
        if (url.length() > 4096) return;
        if (!isSafeHttpUrl(url)) return;
        if (AdBlocker.isAdVideo(url)) return;
        if (!isCastableVideoUrl(url)) return;

        synchronized (detectedVideoUrls) {
            if (detectedVideoUrls.contains(url)) return;
            detectedVideoUrls.add(url);
            lastDetectedVideoUrl = url;
            if (headers != null && !headers.isEmpty()) {
                videoHeaders.put(url, new LinkedHashMap<>(headers));
            }
        }

        int count;
        synchronized (detectedVideoUrls) {
            count = detectedVideoUrls.size();
        }
        callback.onVideoDetected(count, null);
    }

    static boolean isSafeHttpUrl(String url) {
        if (url == null) return false;
        String lower = url.trim().toLowerCase();
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    static boolean isCastableVideoUrl(String url) {
        String lower = url.toLowerCase();
        String path = lower.contains("?") ? lower.substring(0, lower.indexOf("?")) : lower;

        if (path.endsWith(".m3u8") || lower.contains(".m3u8?")) return true;
        if (path.endsWith(".mpd") || lower.contains(".mpd?")) return true;
        if (path.endsWith(".mp4") || lower.contains(".mp4?")) return true;
        if (path.endsWith(".webm") || lower.contains(".webm?")) return true;

        if (path.endsWith(".ts") || lower.contains(".ts?")) return false;
        if (path.endsWith(".mkv") || path.endsWith(".flv") ||
            path.endsWith(".avi") || path.endsWith(".mov")) return false;

        if (lower.contains("videoplayback")) return true;
        if (lower.contains("mime=video")) return true;

        return false;
    }

    boolean isMasterPlaylist(String url) {
        synchronized (m3u8Cache) {
            String content = m3u8Cache.get(url);
            return content != null && content.contains("#EXT-X-STREAM-INF");
        }
    }

    String getVideoLabel(String url) {
        String lower = url.toLowerCase();

        String format;
        if (lower.contains(".m3u8")) format = "HLS";
        else if (lower.contains(".mpd")) format = "DASH";
        else if (lower.contains(".mp4")) format = "MP4";
        else if (lower.contains(".webm")) format = "WebM";
        else format = "Video";

        String cached;
        synchronized (m3u8Cache) {
            cached = m3u8Cache.get(url);
        }
        if (cached != null && cached.contains("#EXT-X-STREAM-INF")) {
            format += " Master";
        }

        StringBuilder label = new StringBuilder(format);

        // Extract max resolution from master playlist
        if (cached != null) {
            String res = extractMaxResolution(cached);
            if (res != null) {
                label.append("-").append(res);
            }
        }

        if (cached != null) {
            label.append(" [").append(formatSize(cached.length())).append("]");
        }

        return label.toString();
    }

    private static final Pattern RESOLUTION_PATTERN = Pattern.compile(
            "RESOLUTION=(\\d+)x(\\d+)");

    /**
     * Extracts the highest resolution from an HLS master playlist.
     * Returns a label like "1080p" or "720p", or null if no resolution found.
     */
    private String extractMaxResolution(String playlistContent) {
        Matcher matcher = RESOLUTION_PATTERN.matcher(playlistContent);
        int maxHeight = 0;
        while (matcher.find()) {
            try {
                int height = Integer.parseInt(matcher.group(2));
                if (height > maxHeight) maxHeight = height;
            } catch (NumberFormatException ignored) {}
        }
        return maxHeight > 0 ? maxHeight + "p" : null;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // --- Embed Page Detection & Injection ---

    boolean isEmbedPageRequest(String url, WebResourceRequest request) {
        String host = AdBlocker.extractHost(url);
        if (host == null) return false;

        String baseDomain = AdBlocker.getBaseDomain(host);
        boolean isDomainMatch = EMBED_DOMAINS.contains(baseDomain) || EMBED_DOMAINS.contains(host);

        if (!isDomainMatch) {
            String lower = url.toLowerCase();
            isDomainMatch = lower.contains("/embed/") || lower.contains("/e/") ||
                           lower.contains("/play/") || lower.contains("/player/");
        }

        if (!isDomainMatch) return false;

        Map<String, String> headers = request.getRequestHeaders();
        if (headers != null) {
            String accept = headers.get("Accept");
            if (accept == null) accept = headers.get("accept");
            if (accept != null && accept.contains("text/html")) return true;
        }

        String lower = url.toLowerCase();
        String path = lower.contains("?") ? lower.substring(0, lower.indexOf("?")) : lower;
        return !path.endsWith(".js") && !path.endsWith(".css") && !path.endsWith(".png") &&
               !path.endsWith(".jpg") && !path.endsWith(".gif") && !path.endsWith(".svg") &&
               !path.endsWith(".woff") && !path.endsWith(".woff2") && !path.endsWith(".ico");
    }

    WebResourceResponse tryInjectHookIntoHtml(String url, WebResourceRequest request) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setInstanceFollowRedirects(true);

            Map<String, String> headers = request.getRequestHeaders();
            if (headers != null) {
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    String key = h.getKey();
                    if (key.equalsIgnoreCase("Host") || key.equalsIgnoreCase("Connection")) continue;
                    conn.setRequestProperty(key, h.getValue());
                }
            }

            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
            }

            int code = conn.getResponseCode();
            diag.htmlFetchStatus = code;
            diag.interceptedDomain = AdBlocker.extractHost(url);

            Log.d(TAG, "Embed HTML fetch: " + code + " for " + AdBlocker.extractHost(url));

            if (code != 200) {
                diag.htmlIntercepted = false;
                return null;
            }

            String contentType = conn.getContentType();
            if (contentType != null && !contentType.contains("html")) {
                return null;
            }

            InputStream in = conn.getInputStream();
            StringBuilder sb = new StringBuilder();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            String html = sb.toString();

            List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
            if (setCookies != null) {
                for (String cookie : setCookies) {
                    CookieManager.getInstance().setCookie(url, cookie);
                }
            }

            String injectedHtml;
            int headIndex = html.indexOf("<head");
            if (headIndex >= 0) {
                int closeTag = html.indexOf(">", headIndex);
                if (closeTag >= 0) {
                    injectedHtml = html.substring(0, closeTag + 1) +
                                   JS_HOOK_SCRIPT +
                                   html.substring(closeTag + 1);
                } else {
                    injectedHtml = JS_HOOK_SCRIPT + html;
                }
            } else {
                injectedHtml = JS_HOOK_SCRIPT + html;
            }

            diag.htmlIntercepted = true;
            diag.scriptInjected = true;
            Log.d(TAG, "Hook injected into embed HTML from " + diag.interceptedDomain);

            byte[] bytes = injectedHtml.getBytes("UTF-8");
            return new WebResourceResponse("text/html", "UTF-8",
                    new ByteArrayInputStream(bytes));

        } catch (Exception e) {
            diag.htmlIntercepted = false;
            diag.lastError = "HTML injection: " + e.getMessage();
            Log.e(TAG, "HTML injection failed for " + url + ": " + e.getMessage());
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // --- JavaScript Interface ---

    class JsBridgeInterface {
        @JavascriptInterface
        public void cacheM3u8(String url, String content) {
            if (content == null || !content.contains("#EXTM3U")) return;
            synchronized (m3u8Cache) {
                m3u8Cache.put(url, content);
            }
            diag.jsHookCaptured = true;
            diag.capturedContentLength = content.length();
            diag.capturedUrl = url;
            Log.d(TAG, "JS hook captured m3u8! URL: " +
                    (url.length() > 80 ? url.substring(0, 80) + "..." : url) +
                    " Length: " + content.length());

            synchronized (detectedVideoUrls) {
                if (!detectedVideoUrls.contains(url)) {
                    detectedVideoUrls.add(url);
                    lastDetectedVideoUrl = url;
                }
            }
            int count;
            synchronized (detectedVideoUrls) {
                count = detectedVideoUrls.size();
            }
            callback.onVideoDetected(count, null);
        }

        @JavascriptInterface
        public void hookActive(String frameUrl) {
            if (diag.hookFrameUrl != null && !frameUrl.equals(diag.hookFrameUrl)) {
                Log.d(TAG, "New iframe detected, clearing previous detections");
                synchronized (detectedVideoUrls) {
                    detectedVideoUrls.clear();
                    videoHeaders.clear();
                }
                synchronized (m3u8Cache) {
                    m3u8Cache.clear();
                }
                diag.reset();
                callback.onDetectionsCleared();
            }
            diag.jsHookActive = true;
            diag.hookFrameUrl = frameUrl;
            Log.d(TAG, "JS hook active in frame: " + frameUrl);
        }

        @JavascriptInterface
        public void onPlayerError(int code, String message) {
            Log.d(TAG, "Player error: code=" + code + " msg=" + message);
            String lower = (message != null) ? message.toLowerCase() : "";
            boolean isDrm = lower.contains("drm") || lower.contains("protected") ||
                    lower.contains("key session") || lower.contains("232403") ||
                    lower.contains("encrypted") || lower.contains("license") ||
                    code == 3;
            if (isDrm) {
                diag.drmDetected = true;
                diag.drmErrorMessage = message;
                callback.onDrmDetected(message);
            }
        }
    }
}
