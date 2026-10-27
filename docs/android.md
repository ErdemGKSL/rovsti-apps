# Building for Android

## One-time setup

1. Rust targets: `rust-toolchain.toml` installs `aarch64-linux-android` and
   `x86_64-linux-android` on first use.
2. Android SDK. It is already at `~/Android/Sdk`. Install **platform 36.1+**,
   build tools and **NDK r29+**:

   ```bash
   ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;36.0.0" "ndk;29.0.14206865" "platform-tools"
   ```

   (Use the NDK 29.x version that `sdkmanager --list` shows.)
3. Environment, in your shell profile:

   ```bash
   export ANDROID_HOME=$HOME/Android/Sdk
   export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/<29.x version>
   export PATH=$ANDROID_HOME/platform-tools:$PATH
   ```

4. JDK 17 or newer (the system has 25).
5. GPUiForge, the packager for gpui-fusion apps:

   ```bash
   git clone https://github.com/cradiy/gpuiforge ~/src/gpuiforge && cargo install --path ~/src/gpuiforge
   ```

`scripts/setup-android.sh` runs steps 1, 2 and 5.

## Build and run

```bash
cd apps/phone && gpuiforge run android
```

This prompts for an `adb` device. `gpuiforge build android` produces
`target/gpuiforge/android/app/build/outputs/apk/debug/app-debug.apk` with both ABIs.
GPU logs: `adb logcat -s GPUI`.

## Adding the Rovsti Kotlin pieces (eject once per app)

GPUiForge generates the Kotlin host. The dialer and SMS roles need extra
components, so each app is ejected once:

```bash
cd apps/phone && gpuiforge platform eject android
```

The project now lives in `apps/phone/platforms/android` and is owned by us. Then:

1. Copy `crates/rovsti_android/kotlin/dev/rovsti/bridge/RovstiBridge.kt` into the
   app module, and add `proguard-rovsti.pro` to its R8 rules.
2. Add an `Application` subclass that calls
   `RovstiBridge.install(this, "rovsti_phone")`, and register it in the manifest.
3. Add the role components listed in [architecture.md](architecture.md#what-each-app-needs-from-android).

Later `gpuiforge` builds keep these user-owned files.

## Checks without a device

```bash
cargo check -p rovsti_android --target aarch64-linux-android
```

Crates with C code (SQLite, ring) need the NDK linker, so a full Android
`cargo check` of `rovsti_messages` needs `ANDROID_NDK_HOME`.
