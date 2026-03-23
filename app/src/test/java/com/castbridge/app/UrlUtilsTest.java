package com.castbridge.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class UrlUtilsTest {

    // --- extractUrl ---

    @Test
    public void extractUrl_nullInput_returnsNull() {
        assertNull(UrlUtils.extractUrl(null));
    }

    @Test
    public void extractUrl_emptyString_returnsNull() {
        assertNull(UrlUtils.extractUrl(""));
    }

    @Test
    public void extractUrl_justUrl_returnsUrl() {
        assertEquals("https://example.com/video",
                UrlUtils.extractUrl("https://example.com/video"));
    }

    @Test
    public void extractUrl_urlWithSurroundingText_findsUrl() {
        assertEquals("https://example.com/path",
                UrlUtils.extractUrl("Check this: https://example.com/path more text"));
    }

    @Test
    public void extractUrl_urlAtEnd_findsUrl() {
        assertEquals("https://example.com",
                UrlUtils.extractUrl("Visit https://example.com"));
    }

    @Test
    public void extractUrl_urlWithQueryParams_preservesParams() {
        assertEquals("https://example.com/path?key=value&other=1",
                UrlUtils.extractUrl("https://example.com/path?key=value&other=1"));
    }

    @Test
    public void extractUrl_urlWithTrailingPunctuation_removesPunctuation() {
        assertEquals("https://example.com/path",
                UrlUtils.extractUrl("https://example.com/path."));
    }

    @Test
    public void extractUrl_httpUrl_works() {
        assertEquals("http://example.com",
                UrlUtils.extractUrl("http://example.com"));
    }

    @Test
    public void extractUrl_noUrl_returnsNull() {
        assertNull(UrlUtils.extractUrl("just some random text without urls"));
    }

    @Test
    public void extractUrl_multipleUrls_returnsFirst() {
        String result = UrlUtils.extractUrl("First https://a.com and https://b.com");
        assertEquals("https://a.com", result);
    }

    @Test
    public void extractUrl_urlWithPort_works() {
        assertEquals("https://example.com:8080/path",
                UrlUtils.extractUrl("https://example.com:8080/path"));
    }

    @Test
    public void extractUrl_veryLongInput_truncatesAndFinds() {
        StringBuilder sb = new StringBuilder();
        sb.append("https://example.com ");
        for (int i = 0; i < 10000; i++) sb.append("x");
        String result = UrlUtils.extractUrl(sb.toString());
        assertEquals("https://example.com", result);
    }

    @Test
    public void extractUrl_urlBeyondMaxLength_stillFindsInFirstPart() {
        StringBuilder sb = new StringBuilder("https://found.com ");
        while (sb.length() < 8192) sb.append("a");
        sb.append(" https://notfound.com");
        String result = UrlUtils.extractUrl(sb.toString());
        assertEquals("https://found.com", result);
    }

    // --- isDirectVideoUrl ---

    @Test
    public void isDirectVideoUrl_null_returnsFalse() {
        assertFalse(UrlUtils.isDirectVideoUrl(null));
    }

    @Test
    public void isDirectVideoUrl_mp4_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/video.mp4"));
    }

    @Test
    public void isDirectVideoUrl_m3u8_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/stream.m3u8"));
    }

    @Test
    public void isDirectVideoUrl_webm_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/clip.webm"));
    }

    @Test
    public void isDirectVideoUrl_mpd_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/manifest.mpd"));
    }

    @Test
    public void isDirectVideoUrl_mkv_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/movie.mkv"));
    }

    @Test
    public void isDirectVideoUrl_mp4WithQuery_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/video.mp4?token=abc"));
    }

    @Test
    public void isDirectVideoUrl_mp4WithFragment_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/video.mp4#time=10"));
    }

    @Test
    public void isDirectVideoUrl_nonVideoExtension_returnsFalse() {
        assertFalse(UrlUtils.isDirectVideoUrl("https://example.com/style.css"));
    }

    @Test
    public void isDirectVideoUrl_tsWithVideoContext_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/segment001.ts"));
    }

    @Test
    public void isDirectVideoUrl_tsWithHlsContext_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/hls/chunk.ts"));
    }

    @Test
    public void isDirectVideoUrl_tsWithoutContext_returnsFalse() {
        assertFalse(UrlUtils.isDirectVideoUrl("https://example.com/script.ts"));
    }

    @Test
    public void isDirectVideoUrl_flv_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/video.flv"));
    }

    @Test
    public void isDirectVideoUrl_mov_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/clip.mov"));
    }

    @Test
    public void isDirectVideoUrl_avi_returnsTrue() {
        assertTrue(UrlUtils.isDirectVideoUrl("https://cdn.example.com/movie.avi"));
    }

    // --- inferContentType ---

    @Test
    public void inferContentType_null_returnsDefault() {
        assertEquals("video/mp4", UrlUtils.inferContentType(null));
    }

    @Test
    public void inferContentType_m3u8_returnsHls() {
        assertEquals("application/x-mpegurl",
                UrlUtils.inferContentType("https://cdn.example.com/stream.m3u8"));
    }

    @Test
    public void inferContentType_mpd_returnsDash() {
        assertEquals("application/dash+xml",
                UrlUtils.inferContentType("https://cdn.example.com/manifest.mpd"));
    }

    @Test
    public void inferContentType_webm_returnsWebm() {
        assertEquals("video/webm",
                UrlUtils.inferContentType("https://cdn.example.com/clip.webm"));
    }

    @Test
    public void inferContentType_mp3_returnsAudio() {
        assertEquals("audio/mpeg",
                UrlUtils.inferContentType("https://cdn.example.com/song.mp3"));
    }

    @Test
    public void inferContentType_ogg_returnsOgg() {
        assertEquals("audio/ogg",
                UrlUtils.inferContentType("https://cdn.example.com/track.ogg"));
    }

    @Test
    public void inferContentType_ts_returnsMpegTs() {
        assertEquals("video/mp2t",
                UrlUtils.inferContentType("https://cdn.example.com/segment.ts"));
    }

    @Test
    public void inferContentType_mov_returnsQuicktime() {
        assertEquals("video/quicktime",
                UrlUtils.inferContentType("https://cdn.example.com/clip.mov"));
    }

    @Test
    public void inferContentType_mkv_returnsMatroska() {
        assertEquals("video/x-matroska",
                UrlUtils.inferContentType("https://cdn.example.com/movie.mkv"));
    }

    @Test
    public void inferContentType_flv_returnsFlv() {
        assertEquals("video/x-flv",
                UrlUtils.inferContentType("https://cdn.example.com/video.flv"));
    }

    @Test
    public void inferContentType_unknownExtension_returnsDefault() {
        assertEquals("video/mp4",
                UrlUtils.inferContentType("https://cdn.example.com/video.xyz"));
    }

    @Test
    public void inferContentType_m3u8WithQuery_ignoresQuery() {
        assertEquals("application/x-mpegurl",
                UrlUtils.inferContentType("https://cdn.example.com/stream.m3u8?token=abc"));
    }

    // --- looksLikeVideoResource ---

    @Test
    public void looksLikeVideoResource_null_returnsFalse() {
        assertFalse(UrlUtils.looksLikeVideoResource(null));
    }

    @Test
    public void looksLikeVideoResource_nonHttp_returnsFalse() {
        assertFalse(UrlUtils.looksLikeVideoResource("ftp://example.com/video.mp4"));
    }

    @Test
    public void looksLikeVideoResource_dataUri_returnsFalse() {
        assertFalse(UrlUtils.looksLikeVideoResource("data:video/mp4;base64,abc"));
    }

    @Test
    public void looksLikeVideoResource_directVideo_returnsTrue() {
        assertTrue(UrlUtils.looksLikeVideoResource("https://cdn.example.com/video.mp4"));
    }

    @Test
    public void looksLikeVideoResource_m3u8_returnsTrue() {
        assertTrue(UrlUtils.looksLikeVideoResource("https://cdn.example.com/stream.m3u8"));
    }

    @Test
    public void looksLikeVideoResource_videoplayback_returnsTrue() {
        assertTrue(UrlUtils.looksLikeVideoResource("https://rr.example.com/videoplayback?id=123"));
    }

    @Test
    public void looksLikeVideoResource_mimeVideo_returnsTrue() {
        assertTrue(UrlUtils.looksLikeVideoResource("https://example.com/stream?mime=video/mp4"));
    }

    @Test
    public void looksLikeVideoResource_contentTypeVideo_returnsTrue() {
        assertTrue(UrlUtils.looksLikeVideoResource(
                "https://example.com/get?content-type=video/mp4"));
    }

    @Test
    public void looksLikeVideoResource_hlsPath_returnsTrue() {
        assertTrue(UrlUtils.looksLikeVideoResource("https://cdn.example.com/hls/master.m3u8"));
    }

    @Test
    public void looksLikeVideoResource_dashPath_returnsTrue() {
        assertTrue(UrlUtils.looksLikeVideoResource("https://cdn.example.com/dash/manifest.mpd"));
    }

    @Test
    public void looksLikeVideoResource_regularPage_returnsFalse() {
        assertFalse(UrlUtils.looksLikeVideoResource("https://example.com/page.html"));
    }

    @Test
    public void looksLikeVideoResource_cssFile_returnsFalse() {
        assertFalse(UrlUtils.looksLikeVideoResource("https://example.com/style.css"));
    }

    @Test
    public void looksLikeVideoResource_jsFile_returnsFalse() {
        assertFalse(UrlUtils.looksLikeVideoResource("https://example.com/app.js"));
    }
}
