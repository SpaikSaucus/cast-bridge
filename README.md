[![en](https://img.shields.io/badge/lang-en-red.svg):ballot_box_with_check:](#) [![es](https://img.shields.io/badge/lang-es-yellow.svg):black_large_square:](https://github.com/SpaikSaucus/cast-bridge/blob/main/README.es.md)

# CastBridge

Android app to cast videos from web pages to Chromecast with a stable connection, using the same protocol as YouTube.

Browsers use screen mirroring to cast, which causes frequent disconnections and drains battery. CastBridge instead detects the stream URL and sends it directly to the Chromecast via Cast SDK.

## Compatibility

Works with servers that serve video without DRM: HLS (.m3u8), DASH (.mpd), MP4, WebM.

Does not work with DRM-protected content (Netflix, Disney+, Amazon Prime) or YouTube (use its official app). If you see an error like "protected content" or "error 232403" during playback, that server uses DRM — try another server available on the page.

## Build

Requirement: [Docker Desktop](https://www.docker.com/products/docker-desktop/) installed and running.

```bash
cp .env.example .env        # create config file
./build-apk.sh              # Linux/macOS
```

On Windows: double-click `build-apk.bat`.

The APK is generated at `app-output/CastBridge.apk`. The first build takes 10-20 minutes (downloads JDK, Android SDK, Gradle). Subsequent builds take 1-3 minutes.

> The `.env` file contains the passwords for signing the APK. You can change the default values by editing the file. It is not uploaded to the repository (listed in `.gitignore`).

## Install

**Via USB** (recommended):
1. Enable USB Debugging on your phone: Settings > About phone > tap "Build number" 7 times > Developer options > USB Debugging
2. Connect the phone and run: `adb install app-output/CastBridge.apk`

**Without USB**: copy the APK to your phone and install it manually (requires enabling unknown sources).

## Usage

1. Open CastBridge and select the Chromecast from the top-right icon.
2. Paste a URL in the address bar (or share from any browser).
3. Navigate to the video and press play. If there are multiple servers, pick one.
4. When **"Video detected!"** appears, tap **SEND TO CAST**. If the page has multiple streams, the app shows a list to choose from — it's normal to try more than one until you find the one that plays.
5. Control playback with the Play/Pause and Stop buttons.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Docker build fails | Make sure Docker Desktop is running |
| Exit code 137 | Allocate at least 4 GB of RAM in Docker Desktop > Settings > Resources |
| Cast icon not visible | Phone and Chromecast must be on the same Wi-Fi network |
| "Video detected!" doesn't appear | Try another video server on the page |
| DRM / protected content error | That server uses encryption, switch to another server |

## Structure

```
Dockerfile                       # Containerized build environment
build-apk.sh / build-apk.bat    # Build scripts
app/src/main/java/.../
  MainActivity.java              # UI and WebView browser
  VideoDetector.java             # Video detection and JS injection into iframes
  CastManager.java               # Cast session and playback controls
  LocalStreamProxy.java          # HTTP proxy that relays video to Chromecast
  AdBlocker.java                 # Ad and tracker blocking (130+ domains)
  UrlUtils.java                  # URL utilities
  CastOptionsProvider.java       # Cast SDK configuration
  CastDiagnostics.java           # Diagnostic data
```
