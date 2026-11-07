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
- The shared Kotlin (`crates/rovsti_android/kotlin`) is a Gradle source directory added in
  `app/build.gradle.kts`, not a symlink, so Windows checkouts build too.
- Permissions live in both `gpuiforge.json` and `app/src/main/AndroidManifest.xml`; keep them equal.
- The eject bakes absolute paths into `app/build.gradle.kts`. After moving the repo run
  `scripts/fix-eject-paths.sh`.
- `min_sdk` 34 and `target_sdk` 36 come from `variables` in `gpuiforge.json`.

## Signed release

```bash
bash scripts/build-release.sh
```

(uses the `signing` object in `gpuiforge.json`; `scripts/build-apks.sh` builds all four apps).

### Release key

All four apps are signed with one key: `~/ownCloud/Personal/rovsti/rovsti-release.jks`,
alias `rovsti`, certificate `CN=Rovsti throwaway, O=Rovsti, C=TR`, SHA-256
`df5123f5d10c3608ad257e43e927c7abb39a8198233a90a7bb818985e68d8fc5`. It was made on the
Windows PC on 2026-11-03 and the apps on the phone carry it, so APKs from any machine must
use it or Android refuses the update as a package conflict. `gpuiforge.json` points at it,
and `scripts/signing.sh` (sourced by the build scripts) loads `ANDROID_STORE_PASSWORD` /
`ANDROID_KEY_PASSWORD` from `signing.env` beside it, never printing them.

Override the key with `ROVSTI_SIGNING_DIR` (folder with `rovsti-release.jks` + `signing.env`),
or `ROVSTI_SIGNING_ENV` / `ROVSTI_KEYSTORE` / `ROVSTI_KEY_ALIAS`; the scripts then build from a
temporary `.gpuiforge-signing.json` copy. The older Linux key (2026-10-27,
SHA-256 `01bd06dd...89c1bb4`) stays in `~/.config/rovsti` as
`ROVSTI_SIGNING_DIR=$HOME/.config/rovsti`, but it cannot update the installed apps.
`versionCode` is fixed at 1 in each `app/build.gradle.kts`; raise it on every machine
together, since Android refuses a lower versionCode than the installed one.

Check the key of an installed app: `adb pull "$(adb shell pm path dev.rovsti.phone | head -1 | cut -d: -f2)" installed.apk`,
then `apksigner verify --print-certs installed.apk` (the same works on any APK).

## Performance builds

`scripts/perf-build.sh` builds a perf-realistic APK (release profile, `perf` feature, debug key) for
measuring on the phone only; `scripts/perf-run.sh` runs the scripted scenarios and collects the
numbers. See `docs/perf/README.md`. Never hand that APK over: testing builds are the signed release
from `scripts/build-release.sh`.
