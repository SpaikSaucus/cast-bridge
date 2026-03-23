package com.castbridge.app;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class AdBlockerTest {

    // --- shouldBlock ---

    @Test
    public void shouldBlock_null_returnsFalse() {
        assertFalse(AdBlocker.shouldBlock(null));
    }

    @Test
    public void shouldBlock_empty_returnsFalse() {
        assertFalse(AdBlocker.shouldBlock(""));
    }

    @Test
    public void shouldBlock_nonHttp_returnsFalse() {
        assertFalse(AdBlocker.shouldBlock("ftp://example.com"));
    }

    @Test
    public void shouldBlock_blockedDomain_returnsTrue() {
        assertTrue(AdBlocker.shouldBlock("https://doubleclick.net/ad"));
    }

    @Test
    public void shouldBlock_subdomain_ofBlockedDomain_returnsTrue() {
        assertTrue(AdBlocker.shouldBlock("https://ads.example.doubleclick.net/ad"));
    }

    @Test
    public void shouldBlock_parentDomainMatching_returnsTrue() {
        assertTrue(AdBlocker.shouldBlock("https://sub.googlesyndication.com/pagead"));
    }

    @Test
    public void shouldBlock_cleanDomain_returnsFalse() {
        assertFalse(AdBlocker.shouldBlock("https://example.com/page"));
    }

    @Test
    public void shouldBlock_adUrlPattern_returnsTrue() {
        assertTrue(AdBlocker.shouldBlock("https://example.com/ads/banner.js"));
    }

    @Test
    public void shouldBlock_adServerPattern_returnsTrue() {
        assertTrue(AdBlocker.shouldBlock("https://example.com/adserver/deliver"));
    }

    @Test
    public void shouldBlock_popupNetwork_returnsTrue() {
        assertTrue(AdBlocker.shouldBlock("https://popads.net/script.js"));
    }

    @Test
    public void shouldBlock_trackingDomain_returnsTrue() {
        assertTrue(AdBlocker.shouldBlock("https://mixpanel.com/track"));
    }

    @Test
    public void shouldBlock_videoCdn_returnsFalse() {
        assertFalse(AdBlocker.shouldBlock("https://cdn.example.com/video.mp4"));
    }

    // --- extractHost ---

    @Test
    public void extractHost_standardUrl_returnsHost() {
        assertEquals("example.com", AdBlocker.extractHost("https://example.com/path"));
    }

    @Test
    public void extractHost_urlWithPort_removesPort() {
        assertEquals("example.com",
                AdBlocker.extractHost("https://example.com:8080/path"));
    }

    @Test
    public void extractHost_noProtocol_returnsNull() {
        assertNull(AdBlocker.extractHost("example.com/path"));
    }

    @Test
    public void extractHost_urlWithQuery_returnsHost() {
        assertEquals("example.com",
                AdBlocker.extractHost("https://example.com?key=value"));
    }

    @Test
    public void extractHost_urlWithFragment_returnsHost() {
        assertEquals("example.com",
                AdBlocker.extractHost("https://example.com#section"));
    }

    @Test
    public void extractHost_subdomain_returnsFullHost() {
        assertEquals("sub.example.com",
                AdBlocker.extractHost("https://sub.example.com/path"));
    }

    @Test
    public void extractHost_ipAddress_returnsIp() {
        assertEquals("192.168.1.1",
                AdBlocker.extractHost("http://192.168.1.1/path"));
    }

    @Test
    public void extractHost_mixedCase_returnsLowercase() {
        assertEquals("example.com",
                AdBlocker.extractHost("https://EXAMPLE.COM/path"));
    }

    // --- getBaseDomain ---

    @Test
    public void getBaseDomain_null_returnsEmpty() {
        assertEquals("", AdBlocker.getBaseDomain(null));
    }

    @Test
    public void getBaseDomain_twoLabels_returnsSame() {
        assertEquals("example.com", AdBlocker.getBaseDomain("example.com"));
    }

    @Test
    public void getBaseDomain_threeLabels_returnsLastTwo() {
        assertEquals("example.com", AdBlocker.getBaseDomain("sub.example.com"));
    }

    @Test
    public void getBaseDomain_fourLabels_returnsLastTwo() {
        assertEquals("example.com",
                AdBlocker.getBaseDomain("deep.sub.example.com"));
    }

    @Test
    public void getBaseDomain_singleLabel_returnsSame() {
        assertEquals("localhost", AdBlocker.getBaseDomain("localhost"));
    }

    // --- isAdRedirect ---

    @Test
    public void isAdRedirect_null_returnsFalse() {
        assertFalse(AdBlocker.isAdRedirect(null));
    }

    @Test
    public void isAdRedirect_blockedDomain_returnsTrue() {
        assertTrue(AdBlocker.isAdRedirect("https://doubleclick.net/redirect"));
    }

    @Test
    public void isAdRedirect_goPattern_returnsTrue() {
        assertTrue(AdBlocker.isAdRedirect("https://example.com/go/?url=http://ad.com"));
    }

    @Test
    public void isAdRedirect_redirectClickPattern_returnsTrue() {
        assertTrue(AdBlocker.isAdRedirect("https://example.com/redirect?click=1"));
    }

    @Test
    public void isAdRedirect_outPattern_returnsTrue() {
        assertTrue(AdBlocker.isAdRedirect("https://example.com/out/link"));
    }

    @Test
    public void isAdRedirect_awayPattern_returnsTrue() {
        assertTrue(AdBlocker.isAdRedirect("https://example.com/away/url"));
    }

    @Test
    public void isAdRedirect_clickTrack_returnsTrue() {
        assertTrue(AdBlocker.isAdRedirect("https://example.com/clicktrack?id=1"));
    }

    @Test
    public void isAdRedirect_normalUrl_returnsFalse() {
        assertFalse(AdBlocker.isAdRedirect("https://example.com/page"));
    }

    // --- isSuspiciousRedirect ---

    @Test
    public void isSuspiciousRedirect_sameDomain_returnsFalse() {
        assertFalse(AdBlocker.isSuspiciousRedirect(
                "https://example.com/page1", "https://example.com/page2"));
    }

    @Test
    public void isSuspiciousRedirect_toBlockedDomain_returnsTrue() {
        assertTrue(AdBlocker.isSuspiciousRedirect(
                "https://example.com/page", "https://doubleclick.net/ad"));
    }

    @Test
    public void isSuspiciousRedirect_toVideoCdn_returnsFalse() {
        assertFalse(AdBlocker.isSuspiciousRedirect(
                "https://example.com/page", "https://cdn.cloudfront.net/video.mp4"));
    }

    @Test
    public void isSuspiciousRedirect_nullFrom_returnsFalse() {
        assertFalse(AdBlocker.isSuspiciousRedirect(null, "https://example.com"));
    }

    @Test
    public void isSuspiciousRedirect_nullTo_returnsFalse() {
        assertFalse(AdBlocker.isSuspiciousRedirect("https://example.com", null));
    }

    // --- isAdVideo ---

    @Test
    public void isAdVideo_null_returnsFalse() {
        assertFalse(AdBlocker.isAdVideo(null));
    }

    @Test
    public void isAdVideo_adDomain_returnsTrue() {
        assertTrue(AdBlocker.isAdVideo("https://imasdk.googleapis.com/js/video.mp4"));
    }

    @Test
    public void isAdVideo_blockedDomain_returnsTrue() {
        assertTrue(AdBlocker.isAdVideo("https://doubleclick.net/video.mp4"));
    }

    @Test
    public void isAdVideo_bettingKeyword_returnsTrue() {
        assertTrue(AdBlocker.isAdVideo("https://cdn.example.com/betwinner_promo.mp4"));
    }

    @Test
    public void isAdVideo_casinoKeyword_returnsTrue() {
        assertTrue(AdBlocker.isAdVideo("https://cdn.example.com/casino_ad.mp4"));
    }

    @Test
    public void isAdVideo_prerollKeyword_returnsTrue() {
        assertTrue(AdBlocker.isAdVideo("https://cdn.example.com/preroll_30s.mp4"));
    }

    @Test
    public void isAdVideo_vastTag_returnsTrue() {
        assertTrue(AdBlocker.isAdVideo("https://cdn.example.com/vast-tag/ad.xml"));
    }

    @Test
    public void isAdVideo_normalVideo_returnsFalse() {
        assertFalse(AdBlocker.isAdVideo("https://cdn.example.com/movie_720p.mp4"));
    }

    @Test
    public void isAdVideo_hlsStream_returnsFalse() {
        assertFalse(AdBlocker.isAdVideo("https://cdn.example.com/hls/master.m3u8"));
    }

    // --- scoreVideoUrl ---

    @Test
    public void scoreVideoUrl_null_returnsZero() {
        assertEquals(0, AdBlocker.scoreVideoUrl(null));
    }

    @Test
    public void scoreVideoUrl_m3u8_scoresHigh() {
        int score = AdBlocker.scoreVideoUrl("https://cdn.example.com/stream.m3u8");
        assertTrue("HLS should score >= 50, was " + score, score >= 50);
    }

    @Test
    public void scoreVideoUrl_mpd_scoresHigh() {
        int score = AdBlocker.scoreVideoUrl("https://cdn.example.com/manifest.mpd");
        assertTrue("DASH should score >= 50, was " + score, score >= 50);
    }

    @Test
    public void scoreVideoUrl_hlsPath_bonusScore() {
        int withHls = AdBlocker.scoreVideoUrl("https://cdn.example.com/hls/master.m3u8");
        int withoutHls = AdBlocker.scoreVideoUrl("https://cdn.example.com/master.m3u8");
        assertTrue("HLS path should add bonus", withHls > withoutHls);
    }

    @Test
    public void scoreVideoUrl_adKeyword_penalized() {
        int score = AdBlocker.scoreVideoUrl("https://cdn.example.com/preroll_ad.mp4");
        assertTrue("Ad keyword should penalize score, was " + score, score < 0);
    }

    @Test
    public void scoreVideoUrl_longPath_bonus() {
        StringBuilder longPath = new StringBuilder("https://cdn.example.com/");
        for (int i = 0; i < 80; i++) longPath.append("x");
        longPath.append(".mp4");
        int score = AdBlocker.scoreVideoUrl(longPath.toString());
        assertTrue("Long path should add 10, was " + score, score >= 10);
    }

    @Test
    public void scoreVideoUrl_masterPlaylist_highScore() {
        int score = AdBlocker.scoreVideoUrl(
                "https://cdn.example.com/hls/master.m3u8?token=abc");
        // m3u8(+50) + /hls/(+30) + /master(+20) = 100+
        assertTrue("Master HLS should score very high, was " + score, score >= 100);
    }

    @Test
    public void scoreVideoUrl_tsSegment_lowScore() {
        int score = AdBlocker.scoreVideoUrl("https://cdn.example.com/seg001.ts");
        assertTrue("TS segment should score low, was " + score, score <= 0);
    }
}
