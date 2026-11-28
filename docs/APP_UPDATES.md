# In-app updates

BiliDl checks the latest stable release from:

`https://api.github.com/repos/sxsbsxs/bili-download/releases/latest`

The app updater:

- Ignores GitHub drafts and prereleases (the `latest` endpoint returns a published stable release).
- Selects a published `.apk` asset and ignores assets named as debug, unsigned or unaligned builds.
- Compares the release tag with the installed app version before offering an update.
- Downloads the asset into the app's private cache, verifies its expected byte count, and opens Android's package installer through a restricted FileProvider URI.
- Requests Android's per-app "Install unknown apps" permission only when the installer requires it. The user still confirms the installation in Android.

## Publishing requirements

A usable update requires a public GitHub Release with a signed APK attached as a release asset.

The APK must:
- Use the same application ID (`com.example.bilidl`).
- Have a higher Android `versionCode` than the installed build.
- Be signed with the same signing certificate as the installed application.
- Use a semantic version tag such as `v1.2.1` so the in-app version comparison is reliable.
- Be a real installable APK, not an Actions ZIP, debug artifact, unsigned APK, or split APK requiring additional files.

Android itself verifies the signing certificate and version code when installation begins. An APK with a different signer cannot update the existing installation.

The repository currently has no published GitHub Release. Until the first correctly signed release is published, the in-app check reports that no formal package is available rather than offering a debug artifact.
