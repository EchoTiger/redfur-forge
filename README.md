# Redfur Forge (`com.fissal.forge`) — v2.0.0

Native Android Kotlin + Jetpack Compose sovereign media utility wrapping **yt-dlp**, **FFmpeg / FFprobe**, **aria2c**, and **LibRaw** (`AndroidLibRaw`) with dual-engine **Arc-Relay** intake and **Molten Forge** local transformation.

## What's New in v2.0

### Dual-Engine Molten Forge & Arc-Relay Identity
- **Visual Domain Coding**: Cool **Arc-Relay Cyan / Secunda Silver** cues distinguish network intake, site connections, and delivery from **Molten Ember / Masser Crimson** local CPU/GPU forging stages.
- **Fiery Forge Fissal Iconography & Contextual Help**: Custom adaptive Forge Fissal clockwork icon & in-app emblem, tap-to-read `(?)` `ForgeInfoTooltip` sheets paired with live host hardware advisories, and the animated Canvas **Nixie-Fuse Workshop Stamp** crediting Echo & Fissal in Settings.

### Sample Frame Lab & Fullscreen OLED Blink Comparator
- **Authentic Multi-Config Sample Lab**: Scrub to any clip timestamp (or roll `Random` with automatic dark-fade dodge), toggle the untouched **ORIG** reference frame, add/edit/remove candidate configs, and benchmark real encoded micro-segments (`SVT-AV1`, `HEVC`, `H.264`, `VP9`, `GIF`, `JPEG`, `WebP`, `PNG`).
- **Perceptual "Best Config" Advisor**: Evaluates rendered candidates using edge-weighted BT.709 SSIM + Pareto-knee scoring (`PerceptualAdvisor`) to spotlight the sharpest size-to-quality sweet spot.
- **0ms Synced Blink Comparator**: Edge-to-edge OLED black canvas with horizontal swipe disabled so pan & pinch-zoom never misfire, instantaneous single-tap Left/Right edge switching, normalized `(X, Y, Zoom)` preservation across matching and differing aspect ratios (`ViewportSyncMath`), and long-press actions (`👍` / `👎` Vote, Save Emoji Preset, Use in Studio, Remove).
- **Room v3 Emoji Format Presets**: Save favorite encoding setups with custom emojis and bind automatic per-media-kind (`Video`, `Image`, `Camera RAW`, `Audio`) defaults for Studio & Sample Lab.

### Universal Live Filesize Estimator & Inline Studio Preview
- **Real-Time Target Budgets**: Instant byte & % deltas powered by `FilesizeEstimator` and live sample calibration ratios (`SampleCalibrationRatio`) across download cards, batch Studio items, and compression panels.
- **Inline Before/After Preview**: Single-tap flip between compressed sample frames and source frames inside Studio without leaving the editor.

### Encrypted Site Auth Vault & Searchable Connections
- **Android Keystore Session Vault**: Captures authenticated site cookies + matched User-Agent inside `AES/GCM/NoPadding` Keystore-encrypted storage (`SiteAuthVault`), with SAF `cookies.txt` import, domain search dropdown with brand icons, live `Check` / `Re-authenticate` / `Remove`, ephemeral `0600` `files/auth_tmp/` materialization cleared in `finally` blocks & on cold start, and contextual login prompts with auto-retry.

### Smart Chapter Track Chopper, Multi-Category SponsorBlock & Dead-Air Skip
- **Single-Video Chapter Splitting**: Parses structured `chapters` metadata and timestamps in descriptions (`YtDlpJsonParser`) into selectable tracklists, downloads long source streams once, and slices tracks with bulk or per-track **Audio-Only vs Video+Audio** toggles.
- **Granular SponsorBlock & Synchronized Dead-Air Removal**: Multi-category SponsorBlock pills (`Sponsor`, `Self-Promo`, `Interaction`, `Intro`, `Outro`, `Preview`, `Music Offtopic`, `Filler`) alongside frame-accurate FFmpeg `silencedetect` / `silenceremove` pause skipping.

### Host-Device Resilience & Signed GitHub Releases Updater
- **Hardware & Memory Guardrails**: `DeviceCapabilityAdvisor` profiles host RAM, CPU cores, heap class, `GL_MAX_TEXTURE_SIZE`, and scratch storage to enforce subsampling (`inSampleSize` + pixel caps on 50MP–108MP RAWs and 4K/8K media), thread/concurrency caps, low-disk preflight checks, and credential/token redaction across logs and Room diagnostics.
- **SHA-256 Verified Self-Updater**: Checks public GitHub Releases on `EchoTiger/redfur-forge`, selects the matching device ABI APK (`arm64-v8a` / `universal`), verifies its SHA-256 checksum against the release notes, and installs seamlessly via Android `PackageInstaller`.

## Build, Verification, Signing & ABI Splits
```bash
./gradlew testDebugUnitTest assembleRelease --no-daemon --stacktrace
```
- **ABI Split APKs**: Release & debug builds generate per-architecture slim APKs (`arm64-v8a`, `armeabi-v7a`, `x86_64`) alongside a combined `universal` APK (`app/build/outputs/apk/release/`).
- **Release Signing**: Add a gitignored `keystore.properties` at the repository root (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`) to sign `release` builds with your release keystore. When omitted, `assembleRelease` falls back to the debug signing config.
- **GitHub Actions & Releases**: `.github/workflows/android-ci.yml` runs the full JUnit regression suite and builds both `arm64-v8a` and `universal` APKs on pushes to `main` and version tags (`v*`), publishing `redfur-forge-<tag>-arm64-v8a.apk` and `redfur-forge-<tag>-universal.apk` with SHA-256 checksums.

```bash
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```
