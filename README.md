# BiliDl

BiliDl is a Bilibili-focused Android downloader. The project uses its own Bilibili parsing and transfer logic; it does not bundle yt-dlp, FFmpeg, or aria2.

## Implemented

- Parse Bilibili b23.tv, BV, av, ep, and ss links.
- Resolve public video metadata and all multipart page CIDs without requiring login. Cover URLs are normalized to HTTPS and fetched with a Bilibili image Referer; blocked/missing images show a bundled placeholder rather than a blank area.
- Show the available qualities returned by Bilibili. When anonymous playurl quality discovery is denied, keep the metadata result usable and defer quality negotiation until the download worker runs; sign-in remains optional.
- Use Bilibili's WBI-signed playurl endpoint to request DASH streams and show only qualities actually returned by the API. Anonymous playurl requests include the observed `try_look=1` preview parameter; whether Bilibili grants 720P/1080P still depends on the video and the live API response. If DASH has no usable source, the repository retries the legacy direct format.
- Download video and audio streams with BiliDl's own HTTP Range scheduler: parallel chunks, per-chunk checkpoints, validator-aware resume, bounded retry, backup CDN fallback and sequential fallback. Requests carry the exact video-page Referer; after all CDN URLs return HTTP 403, BiliDl refreshes playurl once and retries with fresh URLs. Legacy multi-part DURL FLV streams are downloaded per segment and merged at the FLV tag/timestamp level.
- Mux DASH video/audio into MP4, interleaving samples by presentation timestamp without re-encoding. AAC audio-only downloads are packaged as M4A.
- Include an original adaptive launcher icon (down arrow + open frame) and bundled cover placeholder.
- Support optional Bilibili web QR authorization and optional SESSDATA entry. Credentials are validated against Bilibili and encrypted locally with Android Keystore AES-GCM. Login is not required for parsing public metadata or queueing a download; it only changes what Bilibili permits the account to access.
- Show a direct download-all-parts action for multipart videos. Paste up to 50 Bilibili links at once to create independent tasks; one failed link does not block the rest.
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

## Live API verification

To verify anonymous preview quality against a specific public video without sending a Cookie:

    python3 scripts/probe-anonymous-playurl.py --bvid BV1xxxxxxxxx --page 1 --require-1080p

The probe resolves the selected part CID, obtains the current WBI key, signs a `try_look=1` / `qn=80` DASH request, and reports the actual video track IDs returned by Bilibili. It does not download or publish the video. The same check is available as the manually triggered **Bilibili anonymous playurl smoke test** GitHub Actions workflow. For a fuller Android-side run, trigger **Live Bilibili Android pipeline smoke test** with a public BV number; it downloads the selected DASH stream on an Android emulator, remuxes and inspects the MP4, then exercises the live danmaku and subtitle endpoints and the production XML/SRT exporters.

## Build

Open the repository in Android Studio with JDK 17 and Gradle 8.13 support, or run:

    gradle --no-daemon :app:assembleDebug
    gradle --no-daemon :app:testDebugUnitTest

The Android workflow also builds the native C++ inspector tests, verifies the packaged arm64 library, and uploads the debug APK.

## Current limits

- Multi-segment legacy DURL is supported for FLV: segment order, alternate CDN URLs, FLV tag sizes, timestamps, and duplicate metadata are handled during merge. Multi-segment non-FLV direct streams remain rejected until their container boundaries are identified.
- DASH remuxing still uses Android MediaExtractor/MediaMuxer. Device codec availability and behavior may vary.
- Danmaku/subtitle export is implemented but still needs live Bilibili CDN and on-device validation across representative public and account-authorized videos.
- The native MP4 inspector validates the container and sample-table references; it does not decode frames or prove audio/video perceptual quality.
- Room schema v1 stores durable task, media-resource, and execution-event records; WorkManager remains the execution scheduler. Legacy SharedPreferences task specifications are retained only as an upgrade-migration source.
- The app does not bypass DRM, paid access, or Bilibili's access controls.

Use BiliDl only for content you are authorized to save, and follow Bilibili's terms and the creator's rights.
