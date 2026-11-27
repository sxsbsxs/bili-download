# Feature status

This file is a status sheet, not a marketing checklist.

| Area | Current state | Notes |
|---|---|---|
| Bilibili links | Implemented | b23.tv, BV, av, ep, ss |
| Video metadata | Implemented | CID, page list, title, cover, uploader |
| Available qualities | Implemented | Read from Bilibili playurl response |
| DASH video + audio | Implemented | Separate streams downloaded then remuxed |
| Audio-only | Implemented with limits | AAC DASH is packaged as M4A |
| Segmented HTTP | Implemented | Range scheduler, ETag/Last-Modified checkpoints, bounded retry |
| QR authorization | Implemented | Bilibili web QR login; SESSDATA stored with Android Keystore |
| Background tasks | Implemented | WorkManager foreground worker and persisted task input |
| MP4 validation | Implemented | C++ box/sample-table checks before publication |
| Danmaku export | Implemented, needs live-device verification | Segmented protobuf is exported as Bilibili XML; opt-in in settings |
| Subtitle export | Implemented, needs live-device verification | BCC subtitle JSON is exported as SRT; opt-in in settings |
| Multiple DURL/FLV segments | Not implemented | Multi-segment legacy streams are rejected instead of concatenated unsafely |
| Native MP4 muxer | In progress | Android MediaExtractor/MediaMuxer still performs sample extraction/remuxing |
| Persistent relational task database | Planned | Task inputs currently use SharedPreferences plus WorkManager state |
| Live CDN/device QA | Not verified by CI | CI cannot prove Bilibili account access, CDN behavior, or playback on every phone |

## Release gate

A feature is not considered verified just because it compiles. Before marking danmaku/subtitle export as fully verified, test on-device with:
- a public video with multiple danmaku segments;
- a video with at least one official or creator-provided BCC subtitle;
- an account-authorized video if Bilibili requires login;
- playback/opening of the resulting XML and SRT files.
