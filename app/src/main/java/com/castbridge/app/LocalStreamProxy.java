package com.castbridge.app;

import android.util.Log;
import android.webkit.CookieManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local HTTP proxy server that runs on the phone.
 * The Chromecast connects to this proxy, and the phone fetches
 * the actual content from the CDN with the exact same headers
 * that the WebView used originally.
 *
 * This solves the common issue where streaming sites generate
 * session-bound URLs that only work with specific headers/cookies.
 */
public class LocalStreamProxy {

    private static final String TAG = "LocalStreamProxy";
    private static final int BUFFER_SIZE = 32768;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private int port;
    private boolean running;

    // ALL headers captured from the original WebView request
    private Map<String, String> originalHeaders;

    // Cached m3u8 content (to avoid re-fetching from CDN)
    private Map<String, String> m3u8Cache;

    // Diagnostics
    private int totalConnections = 0;
    private int successfulRequests = 0;
    private int failedRequests = 0;
    private String lastError = null;
    private String lastRequestedUrl = null;
    private int lastCdnResponseCode = 0;
    private String lastCdnResponseMessage = null;
    private int segmentsServed = 0;
    private int segmentsFailed = 0;
    private int firstSegmentStatus = 0;
    private boolean servedFromCache = false;

    /**
     * Starts the proxy server with the exact headers from the WebView request.
     */
    public void setM3u8Cache(Map<String, String> cache) {
        this.m3u8Cache = cache;
    }

    public void start(Map<String, String> originalHeaders) throws IOException {
        this.originalHeaders = originalHeaders != null ?
                new LinkedHashMap<>(originalHeaders) : new LinkedHashMap<>();
        this.executor = Executors.newCachedThreadPool();

        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), 0));
        port = serverSocket.getLocalPort();
        running = true;

        executor.execute(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.execute(() -> handleClient(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        Log.e(TAG, "Accept error: " + e.getMessage());
                    }
                }
            }
        });

        Log.d(TAG, "Proxy started on port " + port +
                " with " + this.originalHeaders.size() + " original headers");
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
        if (executor != null) executor.shutdownNow();
    }

    public boolean isRunning() {
        return running && serverSocket != null && !serverSocket.isClosed();
    }

    public int getPort() { return port; }

    public String getDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Proxy port: ").append(port).append("\n");
        sb.append("Running: ").append(isRunning()).append("\n");
        sb.append("Total connections: ").append(totalConnections).append("\n");
        sb.append("Successful: ").append(successfulRequests).append("\n");
        sb.append("Failed: ").append(failedRequests).append("\n");
        sb.append("Last CDN response: ").append(lastCdnResponseCode);
        if (lastCdnResponseMessage != null) sb.append(" (").append(lastCdnResponseMessage).append(")");
        sb.append("\n");
        sb.append("Last requested URL: ").append(lastRequestedUrl != null ?
                (lastRequestedUrl.length() > 100 ?
                    lastRequestedUrl.substring(0, 100) + "..." : lastRequestedUrl) : "none").append("\n");
        sb.append("Last error: ").append(lastError != null ? lastError : "none").append("\n");

        // Show captured headers for debugging
        sb.append("m3u8 served from cache: ").append(servedFromCache ? "YES" : "NO").append("\n");
        sb.append("m3u8 cache entries: ").append(m3u8Cache != null ? m3u8Cache.size() : 0).append("\n");
        sb.append("Segments served: ").append(segmentsServed).append("\n");
        sb.append("Segments failed: ").append(segmentsFailed).append("\n");
        sb.append("First segment status: ").append(firstSegmentStatus).append("\n");

        return sb.toString();
    }

    /**
     * Returns the proxy URL that should be sent to the Chromecast.
     */
    public String getProxyUrl(String originalUrl) {
        String localIp = getLocalIpAddress();
        if (localIp == null) return null;

        try {
            String encoded = URLEncoder.encode(originalUrl, "UTF-8");
            return "http://" + localIp + ":" + port + "/cast?url=" + encoded;
        } catch (Exception e) {
            return null;
        }
    }

    private void handleClient(Socket clientSocket) {
        totalConnections++;
        try {
            clientSocket.setSoTimeout(READ_TIMEOUT);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));
            String requestLine = reader.readLine();
            if (requestLine == null) {
                lastError = "Empty request (null request line)";
                failedRequests++;
                return;
            }

            Log.d(TAG, "Received request: " + requestLine);

            String targetUrl = extractTargetUrl(requestLine);
            if (targetUrl == null) {
                lastError = "Could not parse URL from request: " + requestLine;
                failedRequests++;
                sendError(clientSocket, 400, "Missing url parameter");
                return;
            }

            lastRequestedUrl = targetUrl;

            // Read and discard incoming headers from Chromecast
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // consume
            }

            proxyRequest(clientSocket, targetUrl);
            successfulRequests++;

        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            failedRequests++;
            Log.e(TAG, "Client handler error: " + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    private void proxyRequest(Socket clientSocket, String targetUrl) throws IOException {
        // Check if we have cached m3u8 content (avoids re-fetching from CDN)
        if (m3u8Cache != null && targetUrl.toLowerCase().contains(".m3u8")) {
            String cached = findCachedM3u8(targetUrl);
            if (cached != null) {
                servedFromCache = true;
                Log.d(TAG, "Serving m3u8 from cache for: " +
                        (targetUrl.length() > 80 ? targetUrl.substring(0, 80) + "..." : targetUrl));
                String rewritten = rewritePlaylist(cached, targetUrl);
                byte[] data = rewritten.getBytes("UTF-8");

                OutputStream out = clientSocket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\n").getBytes());
                out.write(("Content-Type: application/x-mpegurl\r\n").getBytes());
                out.write(("Content-Length: " + data.length + "\r\n").getBytes());
                out.write(("Access-Control-Allow-Origin: *\r\n").getBytes());
                out.write(("Connection: close\r\n").getBytes());
                out.write(("\r\n").getBytes());
                out.write(data);
                out.flush();
                lastCdnResponseCode = 200;
                return;
            }
        }

        HttpURLConnection connection = null;
        try {
            URL url = new URL(targetUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setInstanceFollowRedirects(true);

            // Replay ALL original headers from the WebView request
            for (Map.Entry<String, String> header : originalHeaders.entrySet()) {
                String key = header.getKey();
                // Skip headers that HttpURLConnection manages internally
                if (key.equalsIgnoreCase("Host") ||
                    key.equalsIgnoreCase("Connection") ||
                    key.equalsIgnoreCase("Content-Length") ||
                    key.equalsIgnoreCase("Transfer-Encoding")) {
                    continue;
                }
                connection.setRequestProperty(key, header.getValue());
            }

            // Ensure critical browser headers are set even if not in original
            setIfMissing(connection, "User-Agent",
                    "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            setIfMissing(connection, "Accept", "*/*");
            setIfMissing(connection, "Accept-Language", "en-US,en;q=0.9,es;q=0.8");
            setIfMissing(connection, "Sec-Fetch-Dest", "empty");
            setIfMissing(connection, "Sec-Fetch-Mode", "cors");
            setIfMissing(connection, "Sec-Fetch-Site", "cross-site");

            // Forward cookies from the WebView session for this domain
            String cookies = getCookiesForUrl(targetUrl);
            if (cookies != null && !cookies.isEmpty()) {
                connection.setRequestProperty("Cookie", cookies);
            }

            int responseCode = connection.getResponseCode();
            lastCdnResponseCode = responseCode;
            lastCdnResponseMessage = connection.getResponseMessage();
            String contentType = connection.getContentType();
            if (contentType == null) contentType = "application/octet-stream";

            Log.d(TAG, "CDN response: " + responseCode + " " + lastCdnResponseMessage +
                    " type=" + contentType);

            // Track segment delivery
            boolean isSegment = targetUrl.toLowerCase().contains(".ts") ||
                    (contentType != null && contentType.contains("mp2t"));
            if (isSegment) {
                if (firstSegmentStatus == 0) firstSegmentStatus = responseCode;
                if (responseCode >= 200 && responseCode < 400) {
                    segmentsServed++;
                } else {
                    segmentsFailed++;
                }
            }

            InputStream inputStream;
            if (responseCode >= 200 && responseCode < 400) {
                inputStream = connection.getInputStream();
            } else {
                lastError = "CDN returned HTTP " + responseCode + " " +
                        lastCdnResponseMessage + " for: " + targetUrl;
                inputStream = connection.getErrorStream();
                if (inputStream == null) {
                    sendError(clientSocket, responseCode,
                            "CDN returned " + responseCode + " " + lastCdnResponseMessage);
                    return;
                }
            }

            // Check if this is an m3u8 playlist that needs URL rewriting
            boolean isPlaylist = contentType.contains("mpegurl") ||
                    contentType.contains("m3u8") ||
                    targetUrl.toLowerCase().contains(".m3u8");

            if (isPlaylist) {
                String playlistContent = readFullStream(inputStream);
                String rewritten = rewritePlaylist(playlistContent, targetUrl);
                byte[] data = rewritten.getBytes("UTF-8");

                OutputStream out = clientSocket.getOutputStream();
                out.write(("HTTP/1.1 200 OK\r\n").getBytes());
                out.write(("Content-Type: application/x-mpegurl\r\n").getBytes());
                out.write(("Content-Length: " + data.length + "\r\n").getBytes());
                out.write(("Access-Control-Allow-Origin: *\r\n").getBytes());
                out.write(("Connection: close\r\n").getBytes());
                out.write(("\r\n").getBytes());
                out.write(data);
                out.flush();
            } else {
                long contentLength = connection.getContentLengthLong();

                OutputStream out = clientSocket.getOutputStream();
                out.write(("HTTP/1.1 " + responseCode + " OK\r\n").getBytes());
                out.write(("Content-Type: " + contentType + "\r\n").getBytes());
                if (contentLength >= 0) {
                    out.write(("Content-Length: " + contentLength + "\r\n").getBytes());
                }
                out.write(("Access-Control-Allow-Origin: *\r\n").getBytes());
                out.write(("Connection: close\r\n").getBytes());
                out.write(("\r\n").getBytes());

                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
                out.flush();
            }

        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /**
     * Rewrites URLs in an m3u8 playlist to go through the proxy.
     * Propagates query params (auth tokens) from the playlist URL to relative URLs.
     */
    private String rewritePlaylist(String content, String playlistUrl) {
        String baseUrl = stripQueryParams(playlistUrl);
        baseUrl = baseUrl.substring(0, baseUrl.lastIndexOf('/') + 1);

        // Extract query params from the playlist URL to propagate to relative URLs
        String queryParams = "";
        int qIdx = playlistUrl.indexOf('?');
        if (qIdx >= 0) {
            queryParams = playlistUrl.substring(qIdx);
        }

        String localIp = getLocalIpAddress();
        if (localIp == null) return content;

        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (trimmed.contains("URI=\"")) {
                    trimmed = rewriteUriAttribute(trimmed, baseUrl, localIp, queryParams);
                }
                result.append(trimmed).append("\n");
            } else {
                // This is a URL line (segment or sub-playlist)
                String segmentUrl;
                if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                    segmentUrl = trimmed;
                } else {
                    // Relative URL: make absolute and propagate auth query params
                    segmentUrl = baseUrl + trimmed;
                    if (!segmentUrl.contains("?") && !queryParams.isEmpty()) {
                        segmentUrl += queryParams;
                    }
                }

                try {
                    String encoded = URLEncoder.encode(segmentUrl, "UTF-8");
                    result.append("http://").append(localIp).append(":").append(port)
                            .append("/cast?url=").append(encoded).append("\n");
                } catch (Exception e) {
                    result.append(trimmed).append("\n");
                }
            }
        }

        return result.toString();
    }

    private String rewriteUriAttribute(String line, String baseUrl, String localIp, String queryParams) {
        int uriStart = line.indexOf("URI=\"") + 5;
        int uriEnd = line.indexOf("\"", uriStart);
        if (uriStart < 5 || uriEnd < 0) return line;

        String uri = line.substring(uriStart, uriEnd);
        String fullUrl;
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            fullUrl = uri;
        } else {
            fullUrl = baseUrl + uri;
            if (!fullUrl.contains("?") && !queryParams.isEmpty()) {
                fullUrl += queryParams;
            }
        }

        try {
            String encoded = URLEncoder.encode(fullUrl, "UTF-8");
            String proxyUri = "http://" + localIp + ":" + port + "/cast?url=" + encoded;
            return line.substring(0, uriStart) + proxyUri + line.substring(uriEnd);
        } catch (Exception e) {
            return line;
        }
    }

    private String extractTargetUrl(String requestLine) {
        if (requestLine == null || !requestLine.startsWith("GET ")) return null;

        int pathStart = 4;
        int pathEnd = requestLine.indexOf(' ', pathStart);
        if (pathEnd < 0) pathEnd = requestLine.length();

        String path = requestLine.substring(pathStart, pathEnd);
        int urlParamStart = path.indexOf("url=");
        if (urlParamStart < 0) return null;

        String encoded = path.substring(urlParamStart + 4);
        int ampersand = encoded.indexOf('&');
        if (ampersand > 0) encoded = encoded.substring(0, ampersand);

        try {
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    private String readFullStream(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private void sendError(Socket socket, int code, String message) {
        try {
            String body = "Error " + code + ": " + message;
            OutputStream out = socket.getOutputStream();
            out.write(("HTTP/1.1 " + code + " Error\r\n").getBytes());
            out.write(("Content-Type: text/plain\r\n").getBytes());
            out.write(("Content-Length: " + body.length() + "\r\n").getBytes());
            out.write(("Connection: close\r\n\r\n").getBytes());
            out.write(body.getBytes());
            out.flush();
        } catch (IOException ignored) {}
    }

    /**
     * Finds cached m3u8 content with fuzzy URL matching.
     * First tries exact match, then tries matching by path only (without query params).
     * This is needed because the master playlist references sub-playlists by path,
     * but the JS hook captures them with full query params.
     */
    private String findCachedM3u8(String url) {
        // Exact match first
        String exact = m3u8Cache.get(url);
        if (exact != null) return exact;

        // Fuzzy match: compare paths without query params
        String urlPath = stripQueryParams(url);
        for (Map.Entry<String, String> entry : m3u8Cache.entrySet()) {
            String cachedPath = stripQueryParams(entry.getKey());
            if (cachedPath.equals(urlPath)) {
                Log.d(TAG, "Cache fuzzy match: " + urlPath);
                return entry.getValue();
            }
        }

        // Even fuzzier: check if the URL path ends with a cached path's last segment
        String urlFile = urlPath.substring(urlPath.lastIndexOf('/') + 1);
        for (Map.Entry<String, String> entry : m3u8Cache.entrySet()) {
            String cachedPath = stripQueryParams(entry.getKey());
            if (cachedPath.endsWith("/" + urlFile) || cachedPath.endsWith(urlFile)) {
                Log.d(TAG, "Cache filename match: " + urlFile);
                return entry.getValue();
            }
        }

        return null;
    }

    private String stripQueryParams(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) : url;
    }

    private void setIfMissing(HttpURLConnection conn, String key, String value) {
        if (!originalHeaders.containsKey(key) &&
                !originalHeaders.containsKey(key.toLowerCase())) {
            conn.setRequestProperty(key, value);
        }
    }

    private String getCookiesForUrl(String url) {
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            return cookieManager.getCookie(url);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the phone's local IP address on the Wi-Fi network.
     */
    public static String getLocalIpAddress() {
        try {
            String fallback = null;
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr.isLoopbackAddress()) continue;
                    String hostAddress = addr.getHostAddress();
                    if (hostAddress == null || hostAddress.contains(":")) continue; // IPv4 only

                    // Prefer Wi-Fi interfaces
                    String name = ni.getName().toLowerCase();
                    if (name.contains("wlan") || name.contains("wifi")) return hostAddress;
                    if (fallback == null) fallback = hostAddress;
                }
            }
            return fallback;
        } catch (Exception e) {
            Log.e(TAG, "Could not get local IP: " + e.getMessage());
        }
        return null;
    }
}
