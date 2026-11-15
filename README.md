# Redfur Forge (`com.fissal.forge`)

Native Android Kotlin + Jetpack Compose media utility wrapping **yt-dlp**, **FFmpeg / FFprobe**, **aria2c**, and **LibRaw** (`AndroidLibRaw`).

## Core Capabilities
- **Batch URL Downloader**: Extracts & cleans tracking parameters (`utm_*`, `si`, `igsh`, `fbclid`) from multi-line links, guards accidental full-playlist pulls (`--no-playlist` toggle), accelerates downloads via `aria2c`, and optionally chains post-download compression/transcoding.
- **Android Share Menu Intercept (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`)**: Auto-detects shared URLs, photos, Camera RAW files, videos, and audio clips, prepping combinable **Convert**, **Compress**, **Trim**, and **Crop** controls.
- **Canonical Aspect-Snap Crop**: Automatically snaps default crop bounds to the closest canonical **16:9** or **4:3** resolution (**9:16** / **3:4** on portrait media) with strictly even-pixel coordinates safe for `yuv420p` encoders.
- **Exact Target Filesize Reducer**: Computes container-overhead-aware video/audio bitrate budgets + smart resolution downscaling (and binary-search quality encoding for images/RAWs) with automatic corrective retry passes.
- **Zero-Directory Clipboard Copy**: Exposes processed media via a MIME-resolving `ForgeFileProvider` (`ClipData.newUri`) so files can be pasted directly into Discord, Messages, Telegram, or Email—either standalone (**Copy Only**, zero gallery clutter) or alongside **MediaStore** export (**Save & Copy**).
- **Crash-Proof Huge RAW & Video Pipeline**: Runs a dual-lane orchestrator (`Network` lane + RAM-weighted `Compute` lane) backed by `Room` persistence and `ForgeForegroundService`, using `LibRaw` half-size fast previews, `BitmapRegionDecoder` subsampling, and `ARGB_8888` mutable buffers.

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
