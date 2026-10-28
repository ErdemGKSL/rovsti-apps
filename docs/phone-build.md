# Building Rovsti Phone

Environment (owner machine):

```bash
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/30.0.16248370   # 29.x also works
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
```

## Debug APK

```bash
cd apps/phone && gpuiforge build android      # arm64-v8a only (the owner tests on a real phone)
scripts/check-apk.sh                           # JNI symbols and manifest permissions
```

Artifact: `apps/phone/platforms/android/app/build/outputs/apk/debug/app-debug.apk`.
The PRD's `target/gpuiforge/android/...` path only applies while the host is
managed. The host is ejected, so `gpuiforge info android` prints the real path.

Install: `adb install -r <apk>`, or `gpuiforge run android --device <serial>`.

## The ejected host

`apps/phone/platforms/android` is owned by us (`gpuiforge platform eject android`).

- Namespace is `dev.rovsti.phone`; phone Kotlin is in `app/src/main/kotlin/dev/rovsti/phone`.
  `gpuiforge.json` sets `activity` to `dev.rovsti.phone.MainActivity`.
- `app/src/main/kotlin/dev/rovsti/bridge` is a symlink to
  `crates/rovsti_android/kotlin/dev/rovsti/bridge` (shared Kotlin).
- Permissions live in both `gpuiforge.json` and `app/src/main/AndroidManifest.xml`; keep them equal.
- The eject bakes absolute paths into `app/build.gradle.kts`. After moving the repo run
  `scripts/fix-eject-paths.sh`.
- `min_sdk` 34 and `target_sdk` 36 come from `variables` in `gpuiforge.json`.

## Signed release

```bash
bash scripts/build-release.sh
```

(uses the `signing` object in `gpuiforge.json`; passwords come from `~/.config/rovsti/signing.env`).

## Performance builds

`scripts/perf-build.sh` builds a perf-realistic APK (release profile, `perf` feature, debug key) for
measuring on the phone only; `scripts/perf-run.sh` runs the scripted scenarios and collects the
numbers. See `docs/perf/README.md`. Never hand that APK over: testing builds are the signed release
from `scripts/build-release.sh`.
