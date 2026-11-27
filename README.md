# BiliDl

BiliDl is an Android app for saving Bilibili videos that your account or the public video page is allowed to access.

## Implemented

- Accepts Bilibili video links, b23.tv share links, BV IDs, av IDs, and bangumi ep / ss links.
- Resolves video metadata, all multipart page CIDs, cover art, and the quality options returned by Bilibili for each page.
- Uses Bilibili's WBI-signed playurl endpoint to request DASH streams and reports only qualities actually returned by the API.
- Downloads video and audio streams with BiliDl's own HTTP Range scheduler: parallel chunks, per-chunk checkpoints, validator-aware resume, bounded retry, and sequential fallback.
- Muxes DASH video/audio into MP4, interleaving samples by presentation timestamp without re-encoding. Audio-only downloads are packaged as M4A.
- Supports Bilibili web QR authorization and optional SESSDATA entry. The credential is validated against Bilibili and encrypted locally with an Android Keystore AES-GCM key.
- Handles multipart video selection and batch task creation. WorkManager foreground notifications report the current stage and transfer progress.
- Saves finished media in Download/BiliDl through MediaStore without requesting broad storage access.
- Includes an original C++/JNI core for concurrent random-access writes, durable file sync, and structural MP4 inspection. The inspector checks box boundaries, track/sample tables, timing-table counts, chunk mappings, and verifies that each chunk points into an actual mdat payload.
- HTTPS/TLS networking uses Android's platform HTTP stack. This project does not bundle yt-dlp, FFmpeg, or aria2.

## Build

Open the repository in Android Studio with JDK 17 and Gradle 8.13 support, or run:

    gradle --no-daemon :app:assembleDebug
    gradle --no-daemon :app:testDebugUnitTest

GitHub Actions also runs a host-side C++ MP4-inspector test suite before building the Android debug APK.

## Known limits

- If Bilibili only provides multiple legacy DURL / FLV segments, this version reports that format as unsupported rather than joining unrelated files and risking corruption.
- DASH remuxing still uses the Android media stack to extract codec samples and write the MP4 container. Codec availability and behavior can vary by Android device; the native inspector verifies container structure, not decoded picture/audio quality.
- Some videos require an authorized Bilibili account. The app does not bypass paid access, DRM, or Bilibili's access controls.
- Bilibili may change its API, WBI signing requirements, or CDN access rules. HTTP errors are surfaced instead of bypassing platform restrictions.

Use the app only for content you are authorized to save, and follow Bilibili's terms and the creator's rights.
