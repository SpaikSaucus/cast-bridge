package com.castbridge.app;

import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lightweight ad blocker for the built-in browser.
 * Blocks known ad/tracker domains, common ad URL patterns,
 * and aggressive redirect chains used by streaming sites.
 */
public final class AdBlocker {

    private static final Set<String> BLOCKED_DOMAINS = new HashSet<>();
    private static final Pattern AD_URL_PATTERN;
    private static final WebResourceResponse EMPTY_RESPONSE;

    static {
        // Empty response returned for blocked requests
        EMPTY_RESPONSE = new WebResourceResponse(
                "text/plain", "UTF-8",
                new ByteArrayInputStream("".getBytes()));

        // --- Google Ads ---
        BLOCKED_DOMAINS.add("doubleclick.net");
        BLOCKED_DOMAINS.add("googlesyndication.com");
        BLOCKED_DOMAINS.add("googleadservices.com");
        BLOCKED_DOMAINS.add("googleads.g.doubleclick.net");
        BLOCKED_DOMAINS.add("pagead2.googlesyndication.com");
        BLOCKED_DOMAINS.add("adservice.google.com");
        BLOCKED_DOMAINS.add("afs.googlesyndication.com");

        // --- Major ad networks ---
        BLOCKED_DOMAINS.add("adnxs.com");
        BLOCKED_DOMAINS.add("adsrvr.com");
        BLOCKED_DOMAINS.add("advertising.com");
        BLOCKED_DOMAINS.add("rubiconproject.com");
        BLOCKED_DOMAINS.add("pubmatic.com");
        BLOCKED_DOMAINS.add("openx.net");
        BLOCKED_DOMAINS.add("casalemedia.com");
        BLOCKED_DOMAINS.add("criteo.com");
        BLOCKED_DOMAINS.add("criteo.net");
        BLOCKED_DOMAINS.add("taboola.com");
        BLOCKED_DOMAINS.add("outbrain.com");
        BLOCKED_DOMAINS.add("revcontent.com");
        BLOCKED_DOMAINS.add("mgid.com");
        BLOCKED_DOMAINS.add("adcolony.com");
        BLOCKED_DOMAINS.add("unity3d.com");
        BLOCKED_DOMAINS.add("applovin.com");
        BLOCKED_DOMAINS.add("mopub.com");
        BLOCKED_DOMAINS.add("inmobi.com");
        BLOCKED_DOMAINS.add("smaato.com");
        BLOCKED_DOMAINS.add("vungle.com");
        BLOCKED_DOMAINS.add("chartboost.com");
        BLOCKED_DOMAINS.add("ironsrc.com");
        BLOCKED_DOMAINS.add("is.com");

        // --- Popup/redirect networks (very common on streaming sites) ---
        BLOCKED_DOMAINS.add("popads.net");
        BLOCKED_DOMAINS.add("popcash.net");
        BLOCKED_DOMAINS.add("propellerads.com");
        BLOCKED_DOMAINS.add("propellerclick.com");
        BLOCKED_DOMAINS.add("popmyads.com");
        BLOCKED_DOMAINS.add("popunder.net");
        BLOCKED_DOMAINS.add("clickadu.com");
        BLOCKED_DOMAINS.add("clickadilla.com");
        BLOCKED_DOMAINS.add("juicyads.com");
        BLOCKED_DOMAINS.add("exoclick.com");
        BLOCKED_DOMAINS.add("exosrv.com");
        BLOCKED_DOMAINS.add("trafficjunky.com");
        BLOCKED_DOMAINS.add("trafficfactory.biz");
        BLOCKED_DOMAINS.add("hilltopads.net");
        BLOCKED_DOMAINS.add("admaven.com");
        BLOCKED_DOMAINS.add("adsterra.com");
        BLOCKED_DOMAINS.add("a-ads.com");
        BLOCKED_DOMAINS.add("bitmedianetwork.com");
        BLOCKED_DOMAINS.add("monetag.com");
        BLOCKED_DOMAINS.add("richads.com");
        BLOCKED_DOMAINS.add("rollerads.com");
        BLOCKED_DOMAINS.add("galaksion.com");
        BLOCKED_DOMAINS.add("clickaine.com");
        BLOCKED_DOMAINS.add("evadav.com");
        BLOCKED_DOMAINS.add("pushhouse.io");

        // --- Tracking / analytics (privacy) ---
        BLOCKED_DOMAINS.add("facebook.net");
        BLOCKED_DOMAINS.add("connect.facebook.net");
        BLOCKED_DOMAINS.add("pixel.facebook.com");
        BLOCKED_DOMAINS.add("analytics.tiktok.com");
        BLOCKED_DOMAINS.add("ads.twitter.com");
        BLOCKED_DOMAINS.add("ads-api.twitter.com");
        BLOCKED_DOMAINS.add("mixpanel.com");
        BLOCKED_DOMAINS.add("segment.com");
        BLOCKED_DOMAINS.add("segment.io");
        BLOCKED_DOMAINS.add("hotjar.com");
        BLOCKED_DOMAINS.add("fullstory.com");
        BLOCKED_DOMAINS.add("crazyegg.com");
        BLOCKED_DOMAINS.add("mouseflow.com");
        BLOCKED_DOMAINS.add("luckyorange.com");
        BLOCKED_DOMAINS.add("smartlook.com");
        BLOCKED_DOMAINS.add("newrelic.com");
        BLOCKED_DOMAINS.add("bugsnag.com");
        BLOCKED_DOMAINS.add("sentry.io");
        BLOCKED_DOMAINS.add("amplitude.com");
        BLOCKED_DOMAINS.add("branch.io");
        BLOCKED_DOMAINS.add("adjust.com");
        BLOCKED_DOMAINS.add("appsflyer.com");
        BLOCKED_DOMAINS.add("kochava.com");
        BLOCKED_DOMAINS.add("moat.com");
        BLOCKED_DOMAINS.add("doubleverify.com");
        BLOCKED_DOMAINS.add("adsafeprotected.com");

        // --- Malware / scam common on streaming sites ---
        BLOCKED_DOMAINS.add("pushnotificationtool.com");
        BLOCKED_DOMAINS.add("push-notifications.com");
        BLOCKED_DOMAINS.add("notifpush.com");
        BLOCKED_DOMAINS.add("pushwelcome.com");
        BLOCKED_DOMAINS.add("onesignal.com");
        BLOCKED_DOMAINS.add("pushcrew.com");
        BLOCKED_DOMAINS.add("subscribers.com");
        BLOCKED_DOMAINS.add("pushwoosh.com");
        BLOCKED_DOMAINS.add("cleverpush.com");
        BLOCKED_DOMAINS.add("rewarded-video.com");
        BLOCKED_DOMAINS.add("offerimage.com");
        BLOCKED_DOMAINS.add("lp.lonelycheatingwives.com");

        // --- Common streaming site ad domains ---
        BLOCKED_DOMAINS.add("streamhub.to");
        BLOCKED_DOMAINS.add("streamsb.net");
        BLOCKED_DOMAINS.add("betteradsopt.com");
        BLOCKED_DOMAINS.add("disqus.com");
        BLOCKED_DOMAINS.add("mc.yandex.ru");
        BLOCKED_DOMAINS.add("top.mail.ru");
        BLOCKED_DOMAINS.add("acscdn.com");
        BLOCKED_DOMAINS.add("ackcdn.com");
        BLOCKED_DOMAINS.add("2mdn.net");
        BLOCKED_DOMAINS.add("3lift.com");
        BLOCKED_DOMAINS.add("33across.com");
        BLOCKED_DOMAINS.add("amazon-adsystem.com");
        BLOCKED_DOMAINS.add("bidswitch.net");
        BLOCKED_DOMAINS.add("bing-ads.com");
        BLOCKED_DOMAINS.add("bluekai.com");
        BLOCKED_DOMAINS.add("demdex.net");
        BLOCKED_DOMAINS.add("everesttech.net");
        BLOCKED_DOMAINS.add("exelator.com");
        BLOCKED_DOMAINS.add("eyeota.net");
        BLOCKED_DOMAINS.add("intentiq.com");
        BLOCKED_DOMAINS.add("liadm.com");
        BLOCKED_DOMAINS.add("lijit.com");
        BLOCKED_DOMAINS.add("mathtag.com");
        BLOCKED_DOMAINS.add("mookie1.com");
        BLOCKED_DOMAINS.add("myvisualiq.net");
        BLOCKED_DOMAINS.add("narrativ.com");
        BLOCKED_DOMAINS.add("nativo.com");
        BLOCKED_DOMAINS.add("quantserve.com");
        BLOCKED_DOMAINS.add("rlcdn.com");
        BLOCKED_DOMAINS.add("scorecardresearch.com");
        BLOCKED_DOMAINS.add("sharethrough.com");
        BLOCKED_DOMAINS.add("simpli.fi");
        BLOCKED_DOMAINS.add("sitescout.com");
        BLOCKED_DOMAINS.add("spotxchange.com");
        BLOCKED_DOMAINS.add("tapad.com");
        BLOCKED_DOMAINS.add("tidaltv.com");
        BLOCKED_DOMAINS.add("tribalfusion.com");
        BLOCKED_DOMAINS.add("turn.com");
        BLOCKED_DOMAINS.add("undertone.com");
        BLOCKED_DOMAINS.add("yieldmo.com");

        // --- Compile regex for ad URL patterns ---
        AD_URL_PATTERN = Pattern.compile(
                "(/ads/|/ad/|/adserver|/adframe|/adview|" +
                "/banner[s]?/|/popup[s]?/|/popunder|" +
                "\\bad[sx]?[_.-]?\\d|" +
                "/click\\?|/redirect\\?.*(?:ad|click|track)|" +
                "smartadserver|adserving|adtag|adzone|" +
                "prebid|gpt\\.js|pubads|" +
                "pagead|showads|textads|videoads|" +
                "/sponsor|/promo[s]?/|" +
                "interstitial|overlay[_-]?ad|" +
                "floating[_-]?ad|sticky[_-]?ad|" +
                "\\.(gif|png|jpg)\\?.*(?:ad|banner|click))",
                Pattern.CASE_INSENSITIVE
        );
    }

    private AdBlocker() {}

    /**
     * Checks if a URL should be blocked (ad, tracker, or malicious).
     */
    public static boolean shouldBlock(String url) {
        if (url == null || url.isEmpty()) return false;

        // Only filter http/https - let other resources through
        String lower = url.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }

        // Check against blocked domains
        String host = extractHost(url);
        if (host != null && isBlockedDomain(host)) {
            return true;
        }

        // Check against ad URL patterns
        if (AD_URL_PATTERN.matcher(url).find()) {
            return true;
        }

        return false;
    }

    /**
     * Returns an empty response to use for blocked requests.
     */
    public static WebResourceResponse getEmptyResponse() {
        return new WebResourceResponse(
                "text/plain", "UTF-8",
                new ByteArrayInputStream("".getBytes()));
    }

    /**
     * Checks if a navigation URL looks like an ad redirect.
     * Used to block aggressive redirects to ad pages.
     */
    public static boolean isAdRedirect(String url) {
        if (url == null) return false;

        String host = extractHost(url);
        if (host != null && isBlockedDomain(host)) {
            return true;
        }

        // Common redirect patterns used by streaming sites
        String lower = url.toLowerCase();
        if (lower.contains("/go/") && lower.contains("?url=")) return true;
        if (lower.contains("/redirect") && lower.contains("click")) return true;
        if (lower.contains("/out/") || lower.contains("/away/")) return true;
        if (lower.contains("clicktrack") || lower.contains("clickserve")) return true;

        return false;
    }

    /**
     * Checks if a domain change during navigation is suspicious (likely an ad redirect).
     * Allows same-domain and known CDN/player domains.
     */
    public static boolean isSuspiciousRedirect(String fromUrl, String toUrl) {
        if (fromUrl == null || toUrl == null) return false;

        String fromHost = extractHost(fromUrl);
        String toHost = extractHost(toUrl);
        if (fromHost == null || toHost == null) return false;

        // Same domain is fine
        String fromBase = getBaseDomain(fromHost);
        String toBase = getBaseDomain(toHost);
        if (fromBase.equals(toBase)) return false;

        // If destination is a blocked domain, it's definitely suspicious
        if (isBlockedDomain(toHost)) return true;

        // Allow known video/CDN domains that streaming sites commonly redirect to
        if (isKnownVideoDomain(toHost)) return false;

        return false;
    }

    private static boolean isBlockedDomain(String host) {
        if (BLOCKED_DOMAINS.contains(host)) return true;

        // Check parent domains: e.g. "ads.example.doubleclick.net" → "doubleclick.net"
        int dot = host.indexOf('.');
        while (dot >= 0 && dot < host.length() - 1) {
            String parent = host.substring(dot + 1);
            if (BLOCKED_DOMAINS.contains(parent)) return true;
            dot = host.indexOf('.', dot + 1);
        }
        return false;
    }

    private static boolean isKnownVideoDomain(String host) {
        String base = getBaseDomain(host);
        // Common video hosting/CDN domains
        return base.equals("googlevideo.com") ||
                base.equals("akamaized.net") ||
                base.equals("cloudfront.net") ||
                base.equals("cdn77.org") ||
                base.equals("fastly.net") ||
                base.equals("edgecast.net") ||
                base.equals("limelight.com") ||
                base.equals("jwpcdn.com") ||
                base.equals("jwplayer.com") ||
                base.equals("jwplatform.com") ||
                base.equals("videojs.com") ||
                base.equals("plyr.io") ||
                base.equals("vimeo.com") ||
                base.equals("vimeocdn.com") ||
                base.equals("dailymotion.com") ||
                base.equals("dmcdn.net") ||
                base.equals("bitmovin.com") ||
                base.equals("brightcove.com") ||
                base.equals("cloudflare.com") ||
                base.equals("cdninstagram.com") ||
                base.equals("fbcdn.net") ||
                base.equals("amazonaws.com") ||
                base.equals("storage.googleapis.com") ||
                base.equals("blogspot.com") ||
                base.equals("imgur.com") ||
                host.contains("cdn") ||
                host.contains("stream") ||
                host.contains("video") ||
                host.contains("player") ||
                host.contains("embed");
    }

    /**
     * Extracts the host from a URL string without using URI parsing
     * (which can fail on malformed ad URLs).
     */
    static String extractHost(String url) {
        try {
            int start = url.indexOf("://");
            if (start < 0) return null;
            start += 3;
            int end = url.indexOf('/', start);
            if (end < 0) end = url.indexOf('?', start);
            if (end < 0) end = url.indexOf('#', start);
            if (end < 0) end = url.length();
            String host = url.substring(start, end).toLowerCase();
            // Remove port
            int colon = host.indexOf(':');
            if (colon >= 0) host = host.substring(0, colon);
            return host;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets the base domain (eTLD+1 approximation).
     * "sub.example.com" → "example.com"
     * "example.co.uk" → "co.uk" (simplified, acceptable for ad blocking)
     */
    static String getBaseDomain(String host) {
        if (host == null) return "";
        String[] parts = host.split("\\.");
        if (parts.length <= 2) return host;
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    // --- Video ad filtering ---

    private static final Pattern AD_VIDEO_KEYWORDS = Pattern.compile(
            "(betwinner|1xbet|1xslot|stake[._-]?com|melbet|mostbet|linebet|22bet|" +
            "parimatch|pin-up|pinup|betway|bet365|betfair|bwin|" +
            "casino|gambling|betting|slot[s]?[._-]|jackpot|poker|roulette|blackjack|" +
            "bigwin|big[_-]?win|mega[_-]?win|" +
            "bonus[._-]|promo[._-]|offer[._-]|deal[._-]|" +
            "signup|sign[_-]up|register|download[_-]app|install[_-]app|" +
            "preroll|pre[_-]roll|midroll|mid[_-]roll|" +
            "vast[._-]tag|vpaid|adtag|ad[_-]?tag|" +
            "sponsor|branded[_-]content|" +
            "click[._-]?track|impression|pixel\\.gif|beacon|" +
            "overlay[_-]?ad|interstitial|" +
            "video[_-]?ad[s]?[._/]|ad[s]?[._-]?video)",
            Pattern.CASE_INSENSITIVE
    );

    /** Known video ad serving domains */
    private static final Set<String> AD_VIDEO_DOMAINS = new HashSet<>();
    static {
        AD_VIDEO_DOMAINS.add("imasdk.googleapis.com");
        AD_VIDEO_DOMAINS.add("googleads.g.doubleclick.net");
        AD_VIDEO_DOMAINS.add("pubads.g.doubleclick.net");
        AD_VIDEO_DOMAINS.add("securepubads.g.doubleclick.net");
        AD_VIDEO_DOMAINS.add("ad.doubleclick.net");
        AD_VIDEO_DOMAINS.add("s0.2mdn.net");
        AD_VIDEO_DOMAINS.add("vid.springserve.com");
        AD_VIDEO_DOMAINS.add("ads.adaptv.advertising.com");
        AD_VIDEO_DOMAINS.add("rtr.innovid.com");
        AD_VIDEO_DOMAINS.add("vast.adsrvr.org");
        AD_VIDEO_DOMAINS.add("ads.celtra.com");
        AD_VIDEO_DOMAINS.add("cdn.spotxcdn.com");
        AD_VIDEO_DOMAINS.add("search.spotxchange.com");
        AD_VIDEO_DOMAINS.add("serve.popads.net");
        AD_VIDEO_DOMAINS.add("syndication.exoclick.com");
        AD_VIDEO_DOMAINS.add("juicyads.com");
        AD_VIDEO_DOMAINS.add("a.realsrv.com");
        AD_VIDEO_DOMAINS.add("tsyndicate.com");
        AD_VIDEO_DOMAINS.add("syndication.dynsrvtbg.com");
    }

    /**
     * Checks if a video URL is likely an ad video (pre-roll, mid-roll, sponsor, etc.)
     * rather than actual content. Used to filter detected video URLs.
     */
    public static boolean isAdVideo(String url) {
        if (url == null) return false;

        // Check if from a known video ad domain
        String host = extractHost(url);
        if (host != null) {
            if (AD_VIDEO_DOMAINS.contains(host)) return true;
            if (isBlockedDomain(host)) return true;
        }

        // Check URL path for ad-related keywords
        if (AD_VIDEO_KEYWORDS.matcher(url).find()) return true;

        return false;
    }

    /**
     * Scores a video URL to determine how likely it is to be real content.
     * Higher score = more likely to be the actual video.
     * Used to sort detected videos with the most likely content first.
     */
    public static int scoreVideoUrl(String url) {
        if (url == null) return 0;
        String lower = url.toLowerCase();
        int score = 0;

        // HLS and DASH streams are almost always the main content
        if (lower.contains(".m3u8")) score += 50;
        if (lower.contains(".mpd")) score += 50;

        // Longer paths with hash-like segments suggest CDN content delivery
        String path = url.contains("?") ? url.substring(0, url.indexOf("?")) : url;
        if (path.length() > 80) score += 10;

        // Known content CDN patterns
        if (lower.contains("/hls/") || lower.contains("/hls2/")) score += 30;
        if (lower.contains("/dash/")) score += 30;
        if (lower.contains("/playlist")) score += 20;
        if (lower.contains("/master")) score += 20;
        if (lower.contains("/index")) score += 15;
        if (lower.contains("/chunk") || lower.contains("/segment")) score += 5;

        // Content-like path patterns
        if (lower.contains("/embed/")) score += 10;
        if (lower.contains("/watch/")) score += 10;
        if (lower.contains("/video/")) score += 5;
        if (lower.contains("/stream/")) score += 10;

        // Penalize small segment files (likely HLS chunks, not the playlist)
        if (lower.matches(".*\\.ts(\\?.*)?$") && !lower.contains(".m3u8")) score -= 10;

        // Penalize ad-looking patterns
        if (AD_VIDEO_KEYWORDS.matcher(url).find()) score -= 100;

        return score;
    }
}
