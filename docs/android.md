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

This prompts for an `adb` device. `gpuiforge build android` produces `apps/phone/platforms/android/app/build/outputs/apk/debug/app-debug.apk` (arm64-v8a only).
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
   `RovstiBridge.install(this)` (no `System.loadLibrary`; `MainActivity.onCreate` calls `RovstiBridge.attachNative()`), and register it in the manifest.
3. Add the role components listed in [architecture.md](architecture.md#what-each-app-needs-from-android).

Later `gpuiforge` builds keep these user-owned files.

## WhatsApp push and call audio

WhatsApp lives on the gateway ([architecture.md](architecture.md#whatsapp-gateway)).
The Kotlin side adds two small pieces to the bridge:

- `WaPush`: `RovstiBridge.waPushToken()` reads the FCM token **by reflection** on
  `com.google.firebase.messaging.FirebaseMessaging`, so a host without Firebase still
  compiles and gets `""` (the app then registers nothing and the gateway sends it no
  push). `WaPush.onMessage(data)` turns an FCM data message into the Rust event
  `wa_push` (JSON of the data map).
- `WaCallAudio`: `AudioRecord` (`VOICE_COMMUNICATION`) and `AudioTrack`
  (`USAGE_VOICE_COMMUNICATION`), 16 kHz mono PCM16, 960-sample frames passed as
  base64 through `waAudioStart`, `waAudioStop`, `waMicFrame` and `waPlayFrame`.
  `waAudioStart` sets `AudioManager.MODE_IN_COMMUNICATION` and returns `false`
  without `RECORD_AUDIO`. Phone declares that permission in `gpuiforge.json`.

Copy `WaPush.kt` and `WaCallAudio.kt` next to `RovstiBridge.kt`. Push itself is
optional. To enable it, add `google-services.json` and the Firebase Messaging
dependency to the ejected app, then a service such as:

```kotlin
class RovstiMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data["kind"] == "call") showIncomingCall(data) // CallStyle.forIncomingCall
        WaPush.onMessage(data)                             // wakes Rust: event "wa_push"
    }
}
```

and register it in the manifest (`com.google.firebase.MESSAGING_EVENT`). For
`kind=call`, `showIncomingCall` posts a `Notification.CallStyle.forIncomingCall`
notification with a full-screen intent, as `CallRegistry` does for telephony calls;
Answer opens the activity and Phone's call card does the rest. For `kind=message`
the service may just post a plain notification. No message text is in the push, so
the app fetches it from the gateway. The token is registered once per launch and
link; a rotated token is picked up on the next launch.

## Checks without a device

```bash
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android
```

Crates with C code (SQLite, ring) need the NDK linker, so a full Android
`cargo check` of `rovsti_messages` needs `ANDROID_NDK_HOME`.

## Contact photos (Phone, story 10.5)

The bridge still moves only strings and ints. `RovstiBridge.contactPhoto(uri, size)`
(`ContactPhotos.kt`) centre-crops the `PHOTO_THUMBNAIL_URI` image to a square of at most
`size` px, writes a PNG into `cacheDir/contact-photos/` and returns its path (`""` for none).
Rust (`backend::contact_photo`, background executor only) reads and decodes the file; the
`photos` module keeps 64 decoded `RenderImage`s in an LRU keyed by contact id. Avatars never
decode while painting. A photo edited in Contacts shows after the cached file is older than
10 minutes and the LRU entry was evicted or the app restarted.
