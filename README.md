# BiliDl

BiliDl is a Bilibili-focused Android downloader. The project uses its own Bilibili parsing and transfer logic; it does not bundle yt-dlp, FFmpeg, or aria2.

## Implemented

- Parse Bilibili b23.tv, BV, av, ep, and ss links.
- Resolve video metadata, all multipart page CIDs, cover art, and the quality options returned by Bilibili for each page.
- Use Bilibili's WBI-signed playurl endpoint to request DASH streams and show only qualities actually returned by the API. Anonymous playurl requests include the observed `try_look=1` preview parameter; whether Bilibili grants 720P/1080P still depends on the video and the live API response.
- Download video and audio streams with BiliDl's own HTTP Range scheduler: parallel chunks, per-chunk checkpoints, validator-aware resume, bounded retry, and sequential fallback.
- Mux DASH video/audio into MP4, interleaving samples by presentation timestamp without re-encoding. AAC audio-only downloads are packaged as M4A.
- Support Bilibili web QR authorization and optional SESSDATA entry. Credentials are validated against Bilibili and encrypted locally with Android Keystore AES-GCM.
- Handle multipart video selection, batch task creation, WorkManager background tasks, foreground progress notifications, and task restoration.
- Optionally export segmented Bilibili danmaku as XML and BCC subtitles as SRT. The two options are controlled separately in Settings and are saved with each task.
- Save finished media and sidecar files in Download/BiliDl through MediaStore without requesting broad storage access. Check free space before muxing and publication, then release validated DASH source tracks before copying the final MP4 to reduce peak temporary storage.
- Include an original C++/JNI core for positional writes, durable file sync, and structural MP4 inspection. The inspector checks box boundaries, track/sample tables, timing-table counts, chunk mappings, and verifies that each media chunk points into an actual mdat payload.

## Repository layout

- app: Android application, platform integration, download worker, and Bilibili API.
- app/src/main/java/com/example/bilidl/common/content: danmaku/subtitle models, protobuf parsing, and export.
- app/src/main/cpp: native transfer and MP4 inspection core.
- docs: architecture and feature status.
- metadata: store/listing descriptions.
- scripts: repeatable repository checks.
- tools/formatting: contributor formatting conventions.
- .github/workflows: Android CI, source checks, and tagged debug test builds.

The application remains a single Gradle app module for now; package boundaries are being established before any independent module split.

## Build

Open the repository in Android Studio with JDK 17 and Gradle 8.13 support, or run:

    gradle --no-daemon :app:assembleDebug
    gradle --no-daemon :app:testDebugUnitTest

The Android workflow also builds the native C++ inspector tests, verifies the packaged arm64 library, and uploads the debug APK.

## Current limits

- Multi-segment legacy DURL/FLV streams are reported as unsupported rather than concatenated unsafely.
- DASH remuxing still uses Android MediaExtractor/MediaMuxer. Device codec availability and behavior may vary.
- Danmaku/subtitle export is implemented but still needs live Bilibili CDN and on-device validation across representative public and account-authorized videos.
- The native MP4 inspector validates the container and sample-table references; it does not decode frames or prove audio/video perceptual quality.
- Room schema v1 stores durable task, media-resource, and execution-event records; WorkManager remains the execution scheduler. Legacy SharedPreferences task specifications are retained only as an upgrade-migration source.
- The app does not bypass DRM, paid access, or Bilibili's access controls.

Use BiliDl only for content you are authorized to save, and follow Bilibili's terms and the creator's rights.
