# Building on Windows

`docs/phone-build.md` and the scripts in `scripts/` assume Linux (`~/Android/Sdk`,
`/usr/lib/jvm`, bash). This is what a Windows machine needs, and the traps found on the
first release build (2026-11-03).

## Tools

- Rust through `rustup` (the toolchain file adds the Android targets).
- **Visual Studio Build Tools with the C++ workload.** Git for Windows puts a GNU
  `link.exe` first on `PATH`; without MSVC's linker every build script fails with
  `link: extra operand`. Run cargo from a `vcvars64.bat` environment.
- JDK 21, Android SDK platform **36.1**, build-tools 36.0.0, NDK 29, `gpuiforge`
  (`cargo install --path` from the gpuiforge checkout).

## Traps

- **Paths with spaces break Gradle's JDK lookup.** Point `JAVA_HOME` at a path without
  one (a junction such as `C:\Users\<you>\jdk21`). In `cmd`, write
  `set "JAVA_HOME=..."`: `set X=val && ...` keeps the space before `&&` in the value.
- **`os error 33` from `gpuiforge build-android-native` under Gradle.** The inner
  gpuiforge opens Gradle's own `.gradle/<version>/checksums/checksums.lock`, which Gradle
  holds. Move Gradle's project cache with `--project-cache-dir <dir outside the repo>`.
  (Found with Process Monitor: filter Result is SHARING VIOLATION.)
- **Git symlinks.** `core.symlinks=false` checks a symlink out as a small text file, and
  gpuiforge's source snapshot refuses symlinks and junctions. No host uses one now: all four
  add `crates/rovsti_android/kotlin` as a Gradle source directory in `app/build.gradle.kts`.
  (Phone used to link `kotlin/dev/rovsti/bridge`, which needed a temporary copy to build.)
- `gpuiforge.json` keeps an absolute keystore path (`/home/erdem/...` for Phone). For a
  throwaway build, create a keystore with `keytool`, and pass the signing environment to
  Gradle directly (`GPUIFORGE_SIGNING_ENABLED=1`, `GPUIFORGE_SIGNING_KEYSTORE`,
  `GPUIFORGE_SIGNING_KEY_ALIAS`, `GPUIFORGE_SIGNING_STORE_PASSWORD`,
  `GPUIFORGE_SIGNING_KEY_PASSWORD`, `-PgpuiSigning=configured`) instead of editing the file.
- A first release build compiles the whole GPUI stack for Android: about 10 minutes per
  ABI. Run it in the background.

## Release build without `gpuiforge build`

```bash
cd apps/contacts/platforms/android        # or apps/phone/platforms/android
./gradlew.bat --no-daemon --project-cache-dir <dir> \
  -PgpuiSigning=configured -PgpuiAbis=arm64-v8a \
  -PgpuiForgeExecutable=<path to gpuiforge.exe> :app:assembleRelease
```

Check the result with `apksigner verify --print-certs` and `aapt dump badging`.
