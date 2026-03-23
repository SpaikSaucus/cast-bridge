# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CastBridge is an Android app that casts web videos to Chromecast via Cast SDK (not screen mirroring). It detects stream URLs (HLS, DASH, MP4, WebM) from web pages and sends them directly to the Chromecast. DRM-protected content is not supported.

## Build & Test

Docker Desktop must be running for builds.

```bash
# Build APK (output: app-output/CastBridge.apk)
cp .env.example .env       # first time only — signing config
./build-apk.sh             # Linux/macOS
# On Windows: double-click build-apk.bat

# Run unit tests
./gradlew test

# Run a single test class
./gradlew test --tests "com.castbridge.app.UrlUtilsTest"

# Install on device
adb install app-output/CastBridge.apk
```

First build: 10-20 min (downloads SDK/JDK). Subsequent: 1-3 min.

## Architecture

100% Java Android app. Package: `com.castbridge.app`. Target SDK 34, min SDK 23, Java 17.

**Core flow:** `MainActivity` hosts a WebView browser. `VideoDetector` injects JavaScript into pages/iframes to hook `fetch()`, `XMLHttpRequest`, and `video.src` to detect video URLs. When a video is found, `CastManager` first attempts a direct cast (sending the URL straight to Chromecast). If the CDN rejects the request (session-bound URLs), it falls back to proxy mode where `LocalStreamProxy` relays the stream through the phone with the original WebView headers.

**Cast mode state machine (`CastManager.CastMode`):**
- `IDLE` → `DIRECT_ATTEMPT` → `PLAYING_DIRECT` (no proxy, no service)
- `IDLE` → `DIRECT_ATTEMPT` → error → `PROXY_ATTEMPT` → `PLAYING_PROXY` (service running)

**Key classes:**
- **VideoDetector** — JS injection + `JsBridgeInterface` (Java↔JS bridge). Hooks into 20+ known embed domains' iframes. Detects DRM failures.
- **CastManager** — Cast session lifecycle, direct cast + proxy fallback logic, `RemoteMediaClient` for playback control.
- **ProxyService** — Foreground service that keeps `LocalStreamProxy` alive when screen is off. Holds `WifiLock` + `WakeLock`. Has its own Cast session/media listeners for self-cleanup when playback ends.
- **LocalStreamProxy** — HTTP relay on random port. Caches m3u8 playlists. Forwards original request headers.
- **AdBlocker** — Blocks 130+ ad/tracker domains. Injects CSS to hide ad elements. Prevents popups/redirects.
- **UrlUtils** — URL parsing, video URL detection, content-type inference.
- **CastDiagnostics** — State tracking for debugging.
- **CastOptionsProvider** — Cast SDK config, referenced in AndroidManifest via metadata.

**Callback interfaces:** `VideoDetector.Callback` and `CastManager.Callback` connect components via observer pattern.

## ProGuard

Release builds have `minifyEnabled: true`. ProGuard rules preserve:
- `CastOptionsProvider` (manifest reference)
- `ProxyService` (manifest reference)
- `VideoDetector.JsBridgeInterface` methods (called from JS via `@JavascriptInterface`)
- Cast Framework classes

## Build Environment

Docker image: Eclipse Temurin JDK 17, Android SDK API 34, Gradle 8.5. Signing credentials via `.env` file (git-ignored) passed as BuildKit secrets.
