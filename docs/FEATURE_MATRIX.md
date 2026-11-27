# Feature status

This file is a status sheet, not a marketing checklist.

| Area | Current state | Notes |
|---|---|---|
| Bilibili links | Implemented | b23.tv, BV, av, ep, ss |
| Video metadata | Implemented | CID, page list, title, cover, uploader |
| Available qualities | Implemented | Read from Bilibili playurl response; anonymous WBI playurl requests include `try_look=1` before signing, but higher quality availability remains server-controlled |
| DASH video + audio | Implemented | Separate streams downloaded then remuxed |
| Audio-only | Implemented with limits | AAC DASH is packaged as M4A |
| Segmented HTTP | Implemented | Range scheduler, ETag/Last-Modified checkpoints, bounded retry; local HTTP integration tests cover interruption retry, valid resume, validator change, ignored Range, and backup CDN fallback |
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
| Multiple DURL/FLV segments | Not implemented | Multi-segment legacy streams are rejected instead of concatenated unsafely |
| Native MP4 muxer | In progress | Android MediaExtractor/MediaMuxer still performs sample extraction/remuxing |
| Room task database | Implemented, schema v1 | Room is the durable task/resource/event index; WorkManager remains the execution scheduler |
| Room integration | Automated JVM tests | Robolectric-backed in-memory SQLite tests cover persistence, search, progress, upsert safety, and foreign-key cascade |
| Batch task management | Implemented | Select multiple tasks to pause, retry, or delete; batch retry updates Room before WorkManager enqueue |
| Saved resource browser | Implemented | Completed task rows open saved MP4, danmaku XML, and subtitle SRT resources |
| Task execution history | Implemented | View timestamped status changes and failure/retry messages per task |
| Live CDN/device QA | Not verified by CI | Local HTTP server tests cover transport behavior; CI cannot prove Bilibili's current `try_look` entitlement, real CDN responses, danmaku/subtitle availability, or playback on every phone |

## Release gate

A feature is not considered verified just because it compiles. Before marking danmaku/subtitle export as fully verified, test on-device with:
- a public video with multiple danmaku segments;
- a video with at least one official or creator-provided BCC subtitle;
- an account-authorized video if Bilibili requires login;
- playback/opening of the resulting XML and SRT files.
