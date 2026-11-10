# Kotlin UI toolchain

Headless Android toolchain for the Kotlin UI (no Android Studio, no device).

## Setup (per session, idempotent)

```
scripts/android-setup.sh        # installs SDK into /opt/android-sdk, adds Rust android targets
source scripts/android-env.sh   # ANDROID_HOME, ANDROID_SDK_ROOT, ANDROID_NDK_HOME, PATH, cargo linkers
```

Installed: cargo-ndk 4.1.2 (`cargo install cargo-ndk --locked`, done by `android-setup.sh`; drives `:core-bridge:cargoBuildAndroid`), cmdline-tools rev 16111833, platform-tools 37.0.1, `platforms;android-37.0`,
`build-tools;37.0.0`, NDK `29.0.14206865`. Licenses are accepted with `yes | sdkmanager --licenses`.
Override with env: `ANDROID_HOME`, `PLATFORM`, `BUILD_TOOLS`, `NDK_MAJOR`, `NDK_PACKAGE`.
compileSdk must be 37 because Compose BOM 2026.09 libraries require it.
`android/local.properties` (git-ignored) holds `sdk.dir`; recreate with `echo sdk.dir=$ANDROID_HOME > android/local.properties`.

## Gradle root: `android/`

- Gradle 9.9.0 via wrapper (`./gradlew`). The preinstalled /opt/gradle 8.14.3 cannot load AGP 9;
  generate wrappers from an empty directory, not from `android/`.
- Versions live in `android/gradle/libs.versions.toml`: AGP 9.5.1, Kotlin 2.4.21 (AGP 9 built-in
  Kotlin, plus the `kotlin.plugin.compose` compiler plugin), Compose BOM 2026.09.00, coroutines 1.11.0,
  Robolectric 4.17, Roborazzi 1.77.0, Spotless 8.11.0 with ktlint 1.8.0 (`android/.editorconfig`;
  `@Composable` functions may be PascalCase).
- Convention plugins live in the included build `android/build-logic` (`rovsti.android.library`,
  `rovsti.android.app`, `rovsti.compose`, `rovsti.screenshots`). SDK levels (compileSdk 37, minSdk 34, targetSdk 36)
  are the `RovstiSdk` object there. `-PcomposeReports` writes Compose compiler reports and metrics to
  `<module>/build/compose_reports` (run `./gradlew :design:compileReleaseKotlin --no-build-cache --rerun -PcomposeReports`; a build-cache hit writes no reports); stability overrides go in `android/compose_stability.conf` (`//` comments only).
  Roborazzi writes goldens to `<module>/screenshots/`.
- Modules so far: `:design` (library), `:testing` (test-only library with `ShotTest`, `FixedClock`),
  `:catalog` (demo app, `dev.rovsti.catalog`). Compose API checks are in [api-notes.md](api-notes.md).
- Modules are added in `settings.gradle.kts` (`include(":phone")`, ...; the layout is in [architecture.md](architecture.md) section 2). The toolchain was proven with a
  throwaway `:sample` module (Compose + Robolectric native graphics + Roborazzi screenshot, lint, assemble),
  then removed. Module recipe: apply `android.application`/`android.library`, `kotlin.compose`,
  `roborazzi`; `testOptions.unitTests.isIncludeAndroidResources = true`; tests use
  `@GraphicsMode(NATIVE)`, `@Config(sdk=[35], qualifiers="w411dp-h891dp-xxhdpi")`, and
  `captureRoboImage("build/outputs/roborazzi/<name>.png")`; open the PNG with the Read tool.
- Maven Central sometimes returns HTTP 429; retry (the check script does so) and keep `--max-workers=2`.

## Release signing (E4.1)

`rovsti.android.app` (`AndroidAppConventionPlugin`) signs the `release` build type when all three environment variables are
set: `ROVSTI_KEYSTORE` (path of the `.jks`), `ANDROID_STORE_PASSWORD` and `ANDROID_KEY_PASSWORD`; the key alias is `rovsti`
(the names the GPUI apps' `gpuiforge.json` `signing` block used). When one is missing, `assembleRelease` still succeeds and
produces an unsigned APK, which is what CI and the agent machine build. Passwords are never written to the repository.
Version: `versionCode = 100 + ROVSTI_BUILD_NUMBER` (env, default 0) in the app modules that ship.

## Check script

```
scripts/kotlin-ui-check.sh            # full gate
SKIP_GRADLE=1 scripts/kotlin-ui-check.sh   # Rust only
SKIP_RUST=1 scripts/kotlin-ui-check.sh     # Gradle only
RECORD=1 scripts/kotlin-ui-check.sh        # re-record screenshots instead of verifying
```

Order (fail fast, prints `FAILED: <step>` and the command): `cargo fmt --all --check`,
`cargo test --profile checks -p rovsti_core -p rovsti_app_core -p rovsti_geo -p rovsti_tiles -p rovsti_wa_proto -p rovsti_wa_client -p rovsti_android -p rovsti_ffi`,
`cargo check --release --target aarch64-linux-android -p rovsti_android` (and `-p rovsti_ffi`), then in `android/`:
`spotlessCheck lintRelease testReleaseUnitTest :phone:testPlainReleaseUnitTest :phone:testFirebaseReleaseUnitTest :core-bridge:testRustBridge assembleRelease verifyRoborazziRelease :phone:verifyRoborazziPlainRelease`,
then `scripts/check-catalog-apk.sh` (release APKs: arm64-v8a `librovsti_ffi.so` with the eight JNI exports) and the orphan-golden check
(only `spotlessCheck` while no Android module exists). GPUI crates are excluded.

**Release builds only.** The checks never build debug Rust or debug APKs: a debug `librovsti_ffi` is 90 MB per ABI and debug builds filled the disk,
which made agents delete `target/` and rebuild everything (40 to 70 minutes). Host Rust (tests, `cargoBuildHost`, which copies `target/checks/librovsti_ffi.so`)
uses `[profile.checks]` in the root `Cargo.toml` (inherits release, `lto = false`, `codegen-units = 16`, `incremental = false` because incremental caches cost gigabytes of the small disk; unchanged crates are still not rebuilt, and fat LTO is not paid per test binary).
Android Rust is only type-checked with `cargo check --release`; Gradle builds the real arm64-v8a release library (`cargoBuildAndroidRelease`).
Unit tests and Roborazzi run on the release unit-test variant (`testReleaseUnitTest`, `verifyRoborazziRelease`; R8 does not run for unit tests, so results and goldens equal the debug ones).

**Never `cargo clean`, never delete `target/` or run `rm -rf android/**/build`.** Incremental release builds are fast; a clean one is not. If disk is low,
delete only `target/checks` or the `build/` of one Android module, never all of them.
