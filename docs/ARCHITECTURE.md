# Architecture

BiliDl is a Bilibili-specific Android application. It does not embed a generic video-site downloader.

## Current source layout

- app/src/main/java/com/example/bilidl: Android entry points, Bilibili API access, download orchestration, WorkManager worker, and platform integration.
- app/src/main/java/com/example/bilidl/common/content: Bilibili-native content models, segmented-danmaku protobuf decoding, and XML/SRT exporters.
- app/src/main/cpp: the original JNI transfer and MP4 inspection core.
- app/src/test: Kotlin unit tests for link parsing, quality selection, transfer policy, protobuf parsing, and content export.
- app/src/test/cpp: host-side C++ tests for MP4 container inspection.
- .github/workflows: Android build, source-format checks, and tagged test-build publishing.
- docs: architecture and feature status.
- metadata: store/listing text maintained separately from implementation.
- scripts: repeatable repository checks.
- tools/formatting: formatting conventions used by contributors.

This is an intentional transition layout: the existing application remains one Gradle application module while domain boundaries are made explicit in packages. A Gradle multi-module split will only be introduced when a component has a stable API and can be built/tested independently; empty modules are not useful architecture.

## Download flow

1. BiliLinkParser normalizes b23.tv, BV, av, ep, and ss input.
2. BiliRepository resolves page metadata, WBI playurl data, available qualities, subtitle tracks, and danmaku segments.
3. BiliDownloadWorker owns the foreground task lifecycle and restores preferences from its persisted input.
4. SegmentedHttpDownloader schedules HTTP ranges, checkpoints validated chunks, and retries transient failures.
5. BiliDownloadEngine packages DASH audio/video, validates MP4 output, and publishes media via MediaStore.
6. Optional content export writes Bilibili danmaku as XML and BCC subtitles as SRT beside the main media.

## Native boundary

The C++ core owns positional writes, durable sync, and structural MP4 inspection. HTTPS/TLS remains on Android's platform HTTP stack. DASH sample extraction/remuxing currently uses Android MediaExtractor and MediaMuxer; native inspection checks container/sample-table consistency but does not decode the media or guarantee visual/audio quality.

## Development constraints

- Keep the downloader Bilibili-specific and implement protocol handling in this repository.
- Do not add yt-dlp, FFmpeg, or aria2 as hidden dependencies.
- Do not silently claim a format is supported unless the parser, merger, and regression tests cover it.
- Treat optional sidecar failures separately from the main video transfer; report warnings without deleting a successfully saved video.
- Keep secrets such as SESSDATA out of task input, logs, and repository files.
