# Kotlin UI toolchain

Headless Android toolchain for the Kotlin UI (no Android Studio, no device).

## Setup (per session, idempotent)

```
scripts/android-setup.sh        # installs SDK into /opt/android-sdk, adds Rust android targets
source scripts/android-env.sh   # ANDROID_HOME, ANDROID_SDK_ROOT, ANDROID_NDK_HOME, PATH, cargo linkers
```

Installed: cmdline-tools rev 16111833, platform-tools 37.0.1, `platforms;android-37.0`,
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
- Modules are added in `settings.gradle.kts` (`include(":app-phone")`, ...). The toolchain was proven with a
  throwaway `:sample` module (Compose + Robolectric native graphics + Roborazzi screenshot, lint, assemble),
  then removed. Module recipe: apply `android.application`/`android.library`, `kotlin.compose`,
  `roborazzi`; `testOptions.unitTests.isIncludeAndroidResources = true`; tests use
  `@GraphicsMode(NATIVE)`, `@Config(sdk=[35], qualifiers="w411dp-h891dp-xxhdpi")`, and
  `captureRoboImage("build/outputs/roborazzi/<name>.png")`; open the PNG with the Read tool.
- Maven Central sometimes returns HTTP 429; retry (the check script does so) and keep `--max-workers=2`.

## Check script

```
scripts/kotlin-ui-check.sh            # full gate
SKIP_GRADLE=1 scripts/kotlin-ui-check.sh   # Rust only
SKIP_RUST=1 scripts/kotlin-ui-check.sh     # Gradle only
RECORD=1 scripts/kotlin-ui-check.sh        # re-record screenshots instead of verifying
```

Order (fail fast, prints `FAILED: <step>` and the command): `cargo fmt --all --check`,
`cargo test -p rovsti_core -p rovsti_geo -p rovsti_tiles -p rovsti_wa_proto -p rovsti_wa_client -p rovsti_android`,
`cargo build -p rovsti_android --target aarch64-linux-android`, then in `android/`:
`spotlessCheck lintDebug testDebugUnitTest assembleDebug verifyRoborazziDebug`
(only `spotlessCheck` while no Android module exists). GPUI crates are excluded.

Disk: keep 6 GB free; `rm -rf target android/**/build ~/.gradle/caches/build-cache-1` when needed.
