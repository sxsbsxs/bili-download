# BVD / BiliMiao reference scan

Scan date: 2026-11-28
Target: https://github.com/sxsbsxs/bili-download
References: https://github.com/KafuuNeko/BiliDownload (BVD) and https://github.com/10miaomiao/bilimiao2 (BiliMiao)

## 1. Scan method and boundaries

Complete recursive Git trees were retrieved for all three repositories. None of the tree responses was truncated.

| Repository | Tree entries | Build shape |
|---|---:|---|
| BVD | 787 | One Android app module plus tooling |
| BiliMiao | 1,896 | Kotlin Multiplatform app, shared Compose UI, networking, downloader and dedicated danmaku engine modules |
| BiliDl | 191 | Android app plus presentation-only bilivdl-ui module |

The review opened the source files for the requested feature areas: account and QR login, account card, home/feed/search, video detail and multipart selection, stream-selection dialog, download scheduling/retry/resume, player shell and gestures, danmaku parsing/rendering/settings, comments/replies, and download/profile settings.

This is a complete repository-structure scan and source-level audit of those relevant feature areas. It is not a claim that every line of all 2,683 upstream files was read individually. Generated protocol bindings, unrelated platforms, widgets, benchmarks and unrelated feature pages were not exhaustively line-reviewed.

## 2. BVD: account and downloader reference

### Project structure

BVD is a native Android application. Its source mixes Jetpack Compose screens with ViewBinding/DataBinding activities and XML layouts. Its principal integration points are under app/src/main/java/cc/kafuu/bilidownload; the app also contains a native QR encoder and reusable download, network, manager and model packages.

### Account / QR flow

Inspected source:
- common/manager/AccountManager.kt
- common/network/service/BiliPassportService.kt
- common/network/repository/BiliAccountRepository.kt
- feature/compose/viewmodel/qrlolgin/QrLoginViewModel.kt
- feature/compose/viewmodel/qrlolgin/QrLoginUiState.kt
- feature/compose/layout/QrLoginLayout.kt
- app/src/main/res/views/include/layout/include_account_card.xml

Observed behavior:
- BVD uses Bilibili web QR endpoints: generate a QR/key, poll the key, then collect returned cookies.
- Its QR screen has explicit requesting, request-failed, waiting-scan, waiting-confirmation, completed and expired states. Failed/expired QR codes can be retried.
- AccountManager validates a candidate session by requesting account data before accepting it. Profile details are then loaded and stored account state is updated.
- Logout is a server-side operation. The local session is cleared only after logout succeeds.
- The account card has explicit default nickname/signature states and uses BVD's own ic_2233 artwork as its avatar placeholder. It does not use Android's built-in robot icon.

Important distinction: BVD's web-QR flow and BiliMiao's TV-QR/OAuth flow are different protocols. They should not be mixed casually.

### Download selection and batch workflow

Inspected source:
- common/download/BatchDownloadResolver.kt
- common/download/BatchDownloadUseCase.kt
- common/download/DownloadSourceSelector.kt
- common/download/DownloadRetryPolicy.kt
- common/download/ResourceDownloader.kt
- common/download/DownloadCheckpoint.kt
- common/download/DownloadTaskRecoveryUseCase.kt
- common/download/DownloadTaskPostProcessor.kt
- feature/viewbinding/view/activity/VideoDetailsActivity.kt
- feature/viewbinding/viewmodel/activity/VideoDetailsViewModel.kt
- feature/viewbinding/view/dialog/BiliPartDialog.kt
- feature/compose/layout/SettingsLayout.kt

Observed behavior:
- Batch resolution first freezes and deduplicates the target part list. A user can choose preferred parts or all parts.
- The first playable part establishes the preferred video/audio stream pair. Later parts try exact stream identity first.
- When a later part lacks the exact stream, BVD has three explicit policies: automatic compatible fallback, ask the user again, or skip that part.
- The stream picker is a two-list dialog: video and audio tracks are selected independently. Video-only and audio-only choices are valid combinations.
- Batch progress reports processed, added and skipped counts. Cancellation stops creating further tasks without cancelling tasks that were already created.
- CDN source probing uses a bounded Range sample (64 KiB) and a time limit. Valid samples are throughput-ranked; unselected URLs remain available as fallbacks.
- Resource downloads validate Range responses, Content-Range, validators and total length before appending to a partial file. Checkpoints record source identity and strong ETag data.
- Retry handling is bounded, has jitter and understands Retry-After. Recovery distinguishes download, synthesis and publish phases instead of blindly restarting the whole task.
- The batch quality mismatch mode is a user-visible setting, not an implicit hidden fallback.

### BVD visual/UI patterns

- Account card explicitly shows default account state and uses a project-owned avatar placeholder.
- Video part selection supports long-press multi-select, select-all, downloaded-part exclusion, batch progress and cancellation.
- The stream dialog separates video and audio choices and updates its confirmation text for video+audio, video-only and audio-only selections.
- Settings is grouped into interactive cards, including batch mismatch mode and source selection.

## 3. BiliMiao: UI, player, interaction and danmaku reference

### Project structure

BiliMiao is broader than a single Android UI module:
- bilimiao-comm: API clients, data models, stores, account state and player delegation.
- bilimiao-compose: shared Compose pages/components with Android and desktop actual implementations.
- bilimiao-download and the Compose download manager: download models, task manager and download UI.
- danmaku-engine: independent multiplatform danmaku parser/model/filter/renderer/task engine.
- bilimiao-grpc, bilimiao-cover, bilimiao-appwidget, app, desktop app and benchmark modules.

### Home and detail hierarchy

Inspected source:
- bilimiao-compose/pages/home/HomePage.kt
- bilimiao-compose/pages/home/content/HomeRecommendContent.kt
- bilimiao-compose/pages/home/content/HomePopularContent.kt
- bilimiao-compose/components/video/VideoItemBox.kt
- bilimiao-compose/pages/video/VideoDetailPage.kt
- bilimiao-compose/pages/video/content/VideoDetailContent.kt
- bilimiao-compose/pages/video/components/VideoInfoBox.kt
- bilimiao-compose/pages/video/content/VideoReplyContent.kt
- bilimiao-compose/pages/community/content/ReplyListContent.kt

Observed behavior:
- Home has multiple content destinations/tabs (recommendation, popular, dynamic and other configured tabs), preserves page state, and supports pagination and refresh.
- Video cards have a defined 140x85 dp cover proportion, restrained 5 dp rounding, duration overlay, play/danmaku/UP metadata, title hierarchy and progress indicator. Placeholder and error artwork are project-owned image resources.
- Video detail is a stateful Compose page with a player anchor, shared transitions, lazy content and a horizontal pager for detail/comment destinations.
- Video information is componentized: title selection, collapsible animated description, parsed clickable links, part list, uploader, tags, statistics, season and recommendation components are separate.
- Comments use a reusable reply-list component with refresh, pagination, sort menu, like, delete, user navigation and a dedicated reply-edit dialog. The editor supports Bilibili emotes.

### Player architecture

Inspected source:
- bilimiao-compose/components/player/VideoPlayer.kt
- bilimiao-compose/components/player/BiliVideoScaffold.kt
- bilimiao-compose/components/player/videoplayer/VideoScaffold.kt
- bilimiao-compose/components/player/videoplayer/PlayerControllerState.kt
- bilimiao-compose/components/player/videoplayer/gesture/PlayerGestureHost.kt
- bilimiao-compose/components/player/videoplayer/progress/PlayerControllerBar.kt
- bilimiao-compose/components/layout/PlayerAnchorBox.kt

Observed behavior:
- Video surface is separate from the controller. Android uses an ExoPlayer/Mediamp-backed surface, while Compose owns the controls and gestures.
- VideoScaffold composes floating messages, top/right/bottom controls, left-side buttons, gesture area, subtitle layer, danmaku layer, video surface, right sheet and status/preview overlays.
- Controller visibility is stateful and supports always-on requests, inline progress slider and progress-bar requests.
- Gesture layer covers tap/double tap, pause/resume, controller visibility, horizontal seeking, swipe brightness/volume, long-press fast-skip, fullscreen and keyboard shortcuts.
- Progress UI has separate quality, speed, subtitle, aspect ratio, fullscreen and options controls.
- PlayerAnchorBox measures a detail-page location and allows the player to occupy that location instead of being trapped in a modal-sized surface.

### Danmaku engine and display controls

Inspected source:
- danmaku-engine/parser/BiliDanmakuParser.kt
- danmaku-engine/model/SpecialDanmaku.kt
- danmaku-engine/context/DanmakuContext.kt
- danmaku-engine/filter/DanmakuFilters.kt
- danmaku-engine/renderer/DanmakuRenderer.kt
- danmaku-engine/renderer/DanmakusRetainer.kt
- danmaku-engine/task/DanmakuEngine.kt
- danmaku-engine/task/DrawTask.kt
- bilimiao-compose/components/player/DanmakuOverlay.kt
- bilimiao-compose/pages/player/SendDanmakuPage.kt
- bilimiao-compose/pages/setting/content/DanmakuDisplaySettingContent.kt

Observed behavior:
- The parser understands Bilibili XML modes 1, 4, 5, 6 and 7, plus mode-7 JSON animation data.
- Special danmaku supports alpha changes, delayed movement, easing, X/Y/Z rotation and path-segment trajectories.
- The engine separates models, parser, renderer, retention/collision behavior, drawing and background/cache tasks.
- Filters include type, quantity, elapsed-time, color, user ID/hash, guest, duplicate merging, max lines and overlapping.
- Display preferences include overall enable, rolling/top/bottom/advanced visibility, max lines by type, font scale, opacity and speed. Settings can be independently enabled for default, small, fullscreen and picture-in-picture modes.
- Sending supports rolling/top/bottom position, 14 colors, default/small text sizes and the player's exact current playback timestamp. The sent item is also inserted into the live player engine.

### BiliMiao APIs and session behavior

Inspected source:
- bilimiao-comm/apis/AuthApi.kt
- bilimiao-comm/apis/PlayerAPI.kt
- bilimiao-comm/apis/VideoAPI.kt
- bilimiao-comm/apis/CommentApi.kt
- bilimiao-comm/network/MiaoHttp.kt
- bilimiao-compose/pages/auth/QrCodeLoginPage.kt

Observed behavior:
- BiliMiao has web/account/OAuth/refresh, TV QR auth, password, SMS and email auth paths.
- Its TV QR flow has a login session ID and auth code, with separate states for waiting, scanned, confirmed, expired and failed. It is not the same endpoint protocol as BVD web QR.
- MiaoHttp centralizes Bilibili headers, cookies, buvid and request-specific headers, with cancellation tied to the network call.
- PlayerAPI uses video-specific Referer/UA when requesting a video play URL and asks for DASH-related format capabilities.
- VideoAPI and CommentApi expose the actual like/coin/favorite/triple and comment/reply operations used by the UI.

## 4. Direct comparison with the current BiliDl branch

| Area | Current BiliDl state | Reference gap |
|---|---|---|
| Unlogged account avatar | Android sym_def_app_icon is used in XML and Glide placeholder/error | Replace with an app-owned neutral avatar asset; BVD also uses its own placeholder |
| QR authorization | BVD-style web QR flow; encrypted full cookie header in Android Keystore | No BiliMiao TV-QR/OAuth/password/SMS route; do not mix protocols without a deliberate choice |
| Account card | Nickname, status, avatar URL, signature, VIP status | Placeholder is wrong; profile history/favorite destinations are not equivalent to BiliMiao |
| Batch P selection | Multi-select, select-all, progress, cancellation and separate WorkManager tasks | Missing BVD's user-configurable AUTO_FALLBACK / ASK / SKIP mismatch policies and replayable request/decision state |
| Stream selection | Video/audio are chosen separately; exact stream identity is persisted with a task | Compatible fallback policy needs an explicit setting and regression coverage |
| CDN and resume | Custom bounded probe, Range scheduler, checkpoints, validators and fallback | BVD has deeper response validation, Retry-After/jitter and phase-aware recovery patterns worth checking against current transport |
| Home | Search + feed and video cards | BiliMiao has broader multi-tab discovery, saved tab state and a more refined card metadata system |
| Video detail | Detail/comments tabs, parts, tags, description and interaction APIs | Missing BiliMiao's full player anchor/shared-transition and component hierarchy |
| Player | Media3 PlayerView with quality/speed buttons and a partial overlay | Not equivalent to BiliMiao's decomposed Compose controller, gesture host, adaptive bars, subtitles and player-anchor architecture |
| Danmaku | Custom Canvas renderer; common modes and partial advanced JSON motion | Missing dedicated engine path model, Y rotation/no-stroke, collision/retainer, cache, filters and per-display-mode preferences |
| Comments | Main/reply list, sort, likes and sending | Missing emote editor, delete/user navigation and some reusable reply-list behavior |
| Download manager UI | WorkManager tasks, queue, persistence, retry/resume, task selection | BVD phase-aware recovery and BiliMiao download detail/history UI are deeper references |

## 5. Implementation priorities from the scan

1. Fix the account placeholder: use a repository-owned avatar vector for XML source, Glide null fallback, loading placeholder and error placeholder. Never fall back to an Android robot.
2. Complete BVD batch mismatch policy: add AUTO_FALLBACK / ASK / SKIP as a persisted setting; hold the first selected audio/video pair stable; ask again only when policy requires it; report skipped parts and cancellation accurately.
3. Separate player concerns before adding more controls: keep Media3 as the Android rendering backend but introduce explicit controller state, gesture state and overlay/controller slots modelled on BiliMiao's boundaries. Do not claim a wholesale Compose source port.
4. Replace the partial danmaku renderer with a real engine integration/port: start with parser/model/retainer/filter/render loop, then add special-path and full display settings; do not keep extending one View with unrelated branches.
5. Bring detail/comments UI closer through component hierarchy: prioritize player anchor, detail information components and reusable reply/editor state rather than adding more labels to a long layout.
6. Refine home cards and settings using measured component patterns: use project-owned placeholders/icons, consistent card geometry, typography and actual persisted setting state.

## 6. Licensing / attribution

Both upstream repositories declare GNU GPL-3.0. This repository's bilivdl-ui/LICENSE also declares GPL-3.0. Any direct source port must retain applicable notices, mark modifications and preserve corresponding license obligations. This audit records design and architecture findings; it does not copy upstream source code.