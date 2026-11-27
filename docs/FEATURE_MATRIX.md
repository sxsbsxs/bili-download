# Feature status

This file is a status sheet, not a marketing checklist.

| Area | Current state | Notes |
|---|---|---|
| Bilibili links | Implemented | b23.tv, BV, av, ep, ss; paste up to 50 links for batch import with per-link error isolation |
| Multipart batch download | Implemented | A visible action queues all P parts; each part is inspected independently |
| Video metadata | Implemented | Public view/season metadata is shown even if anonymous playurl quality discovery is denied; CID, page list, title and uploader are independent of login |
| Cover art | Implemented with fallback | Protocol-relative/http cover URLs are upgraded to HTTPS, downloaded with image headers and sampled decoding; missing/blocked covers use a bundled placeholder instead of a blank panel |
| Available qualities | Implemented with anonymous fallback | Read only from Bilibili playurl data; an empty anonymous quality list no longer blocks parsing or queue creation, and the worker resolves fresh playurl data at download time. Login only affects Bilibili's account-specific entitlements |
| Unauthenticated public use | Implemented | Parse public metadata, view the result, select automatic quality and queue a download without signing in; login is optional and is never required just to open the app or create a task |
| Application icon | Implemented | Adaptive and round launcher icons use the original down-arrow/open-frame mark, with a monochrome layer and an in-app cover placeholder |
| DASH video + audio | Implemented | Separate streams downloaded then remuxed |
| Audio-only | Implemented with limits | AAC DASH is packaged as M4A |
| Segmented HTTP | Implemented | Range scheduler, ETag/Last-Modified checkpoints, bounded retry; sends the exact Bilibili video-page Referer, tries backup CDN URLs and on HTTP 403 refreshes playurl once. Local HTTP tests cover interruption retry, valid resume, validator change, ignored Range, backup CDN and Referer forwarding |
| QR authorization | Implemented | Bilibili web QR login; SESSDATA stored with Android Keystore |
| Background tasks | Implemented | WorkManager foreground worker; Room stores task specs and lifecycle snapshots |
| Presentation state | In progress | Main, Downloads, Login and Settings ViewModels own parse, history, authorization and preference state; View-based screen construction remains hosted by MainActivity |
| Settings persistence | Implemented | Quality, concurrency, Wi-Fi, audio, danmaku, subtitle and notification preferences are read/written through SettingsViewModel |
| Persistent task history | Implemented | Room task/resource/event tables; legacy SharedPreferences specifications are migrated |
| Task search and observation | Implemented | Search title/uploader/BV and observe task/resource/event Flow |
| Resource history | Implemented | Main media and sidecar content URIs are stored per task |
| MP4 validation | Implemented | C++ box/sample-table checks before publication |
| Danmaku export | Implemented, needs live-device verification | Segmented protobuf is exported as Bilibili XML; opt-in in settings |
| Subtitle export | Implemented, needs live-device verification | BCC subtitle JSON is exported as SRT; opt-in in settings |
| Multiple DURL/FLV segments | Implemented for FLV | Downloads each ordered DURL segment, tries per-segment backup CDN URLs, validates FLV tag boundaries/PreviousTagSize, rebases timestamps using the DURL duration, and removes repeated onMetaData tags; non-FLV multi-part direct streams remain unsupported |
| Native MP4 muxer | In progress | Android MediaExtractor/MediaMuxer still performs sample extraction/remuxing |
| Room task database | Implemented, schema v1 | Room is the durable task/resource/event index; WorkManager remains the execution scheduler |
| Room integration | Automated JVM tests | Robolectric-backed in-memory SQLite tests cover persistence, search, progress, upsert safety, and foreign-key cascade |
| Batch task management | Implemented | Select multiple tasks to pause, retry, or delete; batch retry updates Room before WorkManager enqueue |
| Saved resource browser | Implemented | Completed task rows open saved MP4, danmaku XML, and subtitle SRT resources |
| Task execution history | Implemented | View timestamped status changes and failure/retry messages per task |
| Anonymous playurl live probe | Implemented, manual | `scripts/probe-anonymous-playurl.py` resolves a public BV/part and checks whether the live anonymous WBI DASH response contains quality id 80; the manual workflow requires a real BV input |
| Android live pipeline smoke | Implemented, manual | Android instrumentation test downloads a public DASH stream on API 35, remuxes it with device MediaExtractor/MediaMuxer, validates MP4 via JNI, and calls live danmaku/subtitle APIs plus XML/SRT exporters |
| Live CDN/device QA | Not run automatically | Offline CI validates FLV tag merging and local HTTP behavior; manual workflows exercise a supplied public video on the live Bilibili API and Android emulator. Physical OEM-device compatibility still needs a run on real phones |

## Release gate

A feature is not considered verified just because it compiles. Before marking danmaku/subtitle export as fully verified, test on-device with:
- a public video with multiple danmaku segments;
- a video with at least one official or creator-provided BCC subtitle;
- an account-authorized video if Bilibili requires login;
- playback/opening of the resulting XML and SRT files.
