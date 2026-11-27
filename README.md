# BiliDl

BiliDl is an Android app for saving Bilibili videos that your account or the public video page is allowed to access.

## What works in this branch

- Accepts Bilibili video links, b23.tv share links, BV IDs, av IDs, and bangumi ep / ss links.
- Resolves video metadata, multipart page CID, cover, and the quality options returned by Bilibili for that video.
- Uses Bilibili's WBI-signed playurl endpoint to request DASH streams.
- Downloads video and audio streams in a persistent WorkManager task and muxes them into MP4 with Android MediaExtractor / MediaMuxer.
- Saves completed media in Download/BiliDl through MediaStore; no broad storage permission is requested.
- Supports audio-only mode when Bilibili returns a separate audio stream.
- Optionally accepts the user's SESSDATA. It is validated with Bilibili and encrypted at rest with an Android Keystore AES-GCM key.
- Respects the Wi-Fi-only preference and shows real task progress / failure details. WorkManager jobs survive Activity recreation and process restarts.

## Build

Open this repository in Android Studio with JDK 17 and Gradle 8.13 support, or run:

```bash
gradle --no-daemon :app:assembleDebug
gradle --no-daemon :app:testDebugUnitTest
```

The GitHub Actions workflow builds the debug APK and uploads it as the bili-download-debug artifact.

## Current limits

- QR-code login has not been connected yet. Cookie authorization is optional; public videos can be parsed without signing in.
- Cancel stops the current transfer. Retrying starts the transfer again from the beginning; HTTP range resume is not implemented yet.
- If Bilibili only provides multiple legacy DURL / FLV segments, this version reports that format as unsupported rather than writing a potentially broken file.
- MP4 muxing depends on the audio/video codecs exposed by Bilibili and supported by the device's Android media stack. Unsupported codecs produce a visible failed task.
- Bilibili may change its API, WBI signing requirements, or CDN access rules. HTTP 412 / 403 / 352 responses are surfaced as errors rather than bypassed.
- This app does not remove DRM, bypass paid access, or grant access to videos your account cannot play.

Use the app only for content you are authorized to save, and follow Bilibili's terms and the creator's rights.