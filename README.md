# Redfur Forge (`com.fissal.forge`)

Native Android Kotlin + Jetpack Compose media utility wrapping **yt-dlp**, **FFmpeg / FFprobe**, **aria2c**, and **LibRaw** (`AndroidLibRaw`).

## Core Capabilities (`v1.1.0`)
- **Resolved Link Chips**: Pasted links automatically resolve via bounded `yt-dlp --flat-playlist --dump-single-json` lookups into expandable cards with thumbnails, detected media type (`Audio`, `Video`, `Playlist`, `Image`), host, duration, per-link format/codec controls, and post-download target filesize budgets.
- **Playlist Song Visualizer & Queue Removal**: Expanded playlist chips visualize every individual track with duration/uploader metadata and checkboxes (`Select All` / `Clear`); queued playlists fan out into per-song tracked jobs with aggregate playlist progress, `Cancel Playlist`, `Retry Failed`, and 1-tap `Remove Playlist`.
- **Perceptual SVT-AV1 + Opus Target-Filesize Engine**: Automatically computes packet/container-aware bitrates, Lanczos scaling, adaptive FPS stepping, and gentle temporal denoising (`hqdn3d`) using `libsvtav1` + `libopus` (or 2-pass `libx265` / `libx264`) so even extreme targets like a **30-minute video under 25 MB** fit cleanly with minimal quality loss.
- **Bulk Site & File-Type Propagation**: Apply settings from any link chip or batch Studio item across **All `<Audio/Video/...>`**, **This Site (`<host>`)**, or **All Links**, while keeping individual overrides intact.
- **Workflow Pipeline Dashboard**: Visualizes the 3-stage pipeline (`Resolve` -> `Fetch` -> `Forge`) with live network/compute gauges and active playlist monitoring alongside the local/RAW studio, canonical 16:9 / 4:3 crop snapping, and zero-directory clipboard export.
- **Automatic GitHub Release Self-Updater**: Checks public GitHub Releases on `EchoTiger/redfur-forge` at launch (or manually in Settings), picks the matching device ABI APK (`arm64-v8a` / `universal`), verifies its SHA-256 checksum against release notes, and hands off seamlessly to Android `PackageInstaller`.

## Build, Release Signing & ABI Splits
```bash
./gradlew testDebugUnitTest assembleRelease --no-daemon --stacktrace
```
- **ABI Split APKs**: Release & debug builds generate per-architecture slim APKs (`arm64-v8a` ~60 MB for modern Pixels/phones, `armeabi-v7a`, `x86_64`) alongside a combined `universal` APK (`app/build/outputs/apk/release/`).
- **Optional Release Signing**: Add a gitignored `keystore.properties` at the repository root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) to sign `release` builds with your own release keystore. When omitted (such as fresh clones or GitHub Actions CI), `assembleRelease` falls back cleanly to the debug signing config.
- **GitHub Actions & Releases**: `.github/workflows/android-ci.yml` runs unit tests and builds both `arm64-v8a` and `universal` release APKs on pushes to `main` and version tags (`v*`). Tagged releases attach `redfur-forge-<tag>-arm64-v8a.apk` and `redfur-forge-<tag>-universal.apk`.

```bash
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```
