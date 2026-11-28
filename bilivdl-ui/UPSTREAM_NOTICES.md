# BiliVdl UI source and licensing

This module ports the Android XML layouts, screen styling, and UI components from the user's repository:

- Source project: https://github.com/sxsbsxs/bilibilivdl
- Original UI package: \`com.sxsbsxs.bilivdl.ui\`
- Source paths: \`app/src/main/java/com/sxsbsxs/bilivdl/ui/\` and \`app/src/main/res/\`
- Original upstream project license: GNU GPL v3.0

The UI is adapted to the host application's data and callback boundary. BiliDl keeps its own Bilibili repository, encrypted cookie store, Room task database, WorkManager scheduler, and download engine. The UI module does not bundle a second networking or downloading backend.

The original source project also incorporates bilimiao2 and BiliDownload upstream code. Their notices remain in the original source repository at \`sxsbsxs/bilibilivdl/THIRD_PARTY_NOTICES.md\`; this UI-only module does not import those backend modules.

Keep this notice and the accompanying GPL-3.0 license with redistributed copies of this module.
