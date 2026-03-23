package com.castbridge.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UrlUtils {

    private static final int MAX_INPUT_LENGTH = 8192;

    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s<>\"']{1,2048}");

    private static final Pattern VIDEO_EXTENSION_PATTERN = Pattern.compile(
            "\\.(mp4|m3u8|webm|mkv|avi|mov|mpd|flv)(\\?|#|$)",
            Pattern.CASE_INSENSITIVE);

    // .ts matched separately to avoid false positives with TypeScript files
    private static final Pattern TS_VIDEO_PATTERN = Pattern.compile(
            "\\.ts(\\?|#|$)",
            Pattern.CASE_INSENSITIVE);

    private UrlUtils() {}

    /**
     * Extracts the first URL from shared text. Browsers share text in various formats:
     * - Just the URL
     * - "Page Title - https://..."
     * - "Check this out: https://... more text"
     */
    public static String extractUrl(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }

        // Limit input length to prevent ReDoS
        String input = text.length() > MAX_INPUT_LENGTH
                ? text.substring(0, MAX_INPUT_LENGTH) : text;
        String trimmed = input.trim();

        // If the entire text is a URL, return it directly
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            int spaceIndex = trimmed.indexOf(' ');
            if (spaceIndex == -1) {
                return cleanUrl(trimmed);
            }
        }

        // Otherwise, find the first URL in the text
        Matcher matcher = URL_PATTERN.matcher(input);
        if (matcher.find()) {
            return cleanUrl(matcher.group());
        }

        return null;
    }

    /**
     * Checks if a URL points directly to a video file based on its extension.
     * Only matches extensions at the end of the path (before query/fragment).
     */
    public static boolean isDirectVideoUrl(String url) {
        if (url == null) return false;

        // Extract path portion for extension check
        String path = getPathFromUrl(url);
        if (VIDEO_EXTENSION_PATTERN.matcher(path + "?").find()) return true;

        // Check .ts separately: only match if the URL context suggests video
        // (avoids matching TypeScript .ts files)
        if (TS_VIDEO_PATTERN.matcher(path + "?").find()) {
            String lower = url.toLowerCase();
            return lower.contains("segment") || lower.contains("chunk")
                    || lower.contains("video") || lower.contains("stream")
                    || lower.contains("media") || lower.contains("hls");
        }

        return false;
    }

    /**
     * Infers the MIME content type from a URL's file extension.
     * Matches only the path portion to avoid false positives from query params.
     */
    public static String inferContentType(String url) {
        if (url == null) return "video/mp4";

        String path = getPathFromUrl(url).toLowerCase();

        if (path.endsWith(".m3u8")) return "application/x-mpegurl";
        if (path.endsWith(".mpd")) return "application/dash+xml";
        if (path.endsWith(".webm")) return "video/webm";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        if (path.endsWith(".ogg")) return "audio/ogg";
        if (path.endsWith(".mkv")) return "video/x-matroska";
        if (path.endsWith(".flv")) return "video/x-flv";
        if (path.endsWith(".ts")) return "video/mp2t";
        if (path.endsWith(".mov")) return "video/quicktime";

        return "video/mp4";
    }

    /**
     * Checks if a URL looks like it could contain a video resource.
     * Used to filter intercepted network requests in the WebView.
     */
    public static boolean looksLikeVideoResource(String url) {
        if (url == null) return false;

        // Only consider http/https URLs
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;

        // Direct video file extensions
        if (isDirectVideoUrl(url)) return true;

        // Common video streaming patterns (more specific to reduce false positives)
        if (lower.contains("videoplayback")) return true;
        if (lower.contains("mime=video")) return true;
        if (lower.contains("content-type=video")) return true;
        if (lower.contains("/hls/") || lower.contains("/dash/")) return true;

        return false;
    }

    /**
     * Extracts the path portion of a URL (everything before ? or #).
     */
    private static String getPathFromUrl(String url) {
        int queryIndex = url.indexOf('?');
        int fragmentIndex = url.indexOf('#');
        int endIndex = url.length();

        if (queryIndex >= 0) endIndex = Math.min(endIndex, queryIndex);
        if (fragmentIndex >= 0) endIndex = Math.min(endIndex, fragmentIndex);

        return url.substring(0, endIndex);
    }

    private static String cleanUrl(String url) {
        // Remove trailing punctuation that might have been captured from surrounding text
        return url.replaceAll("[.,;:!?)]+$", "");
    }
}
