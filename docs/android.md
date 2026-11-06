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

## Messages host

`apps/messages/platforms/android` is ejected like Contacts: `gpuiforge.json` has
`management: manual`, `project-dir: platforms/android`, activity
`dev.rovsti.messages.MainActivity`, `min_sdk` 34 and `target_sdk` 36. The shared Kotlin bridge is a
Gradle source directory (`crates/rovsti_android/kotlin`, added in `app/build.gradle.kts`), not a
symlink and not a copy. Permissions: the SMS/MMS set, `READ_CONTACTS`, `READ_PHONE_STATE`,
`POST_NOTIFICATIONS`, `ACCESS_NETWORK_STATE`, `INTERNET` (gateway), `SCHEDULE_EXACT_ALARM` and
`RECEIVE_BOOT_COMPLETED`. There is no foreground service and no battery-optimization request.

**Role components** (all four are needed for Android to offer `ROLE_SMS`), declared in
`app/src/main/AndroidManifest.xml`:

| Component | Intent | Does |
| --- | --- | --- |
| `SmsDeliverReceiver` (`BROADCAST_SMS`) | `SMS_DELIVER` | writes `Telephony.Sms.Inbox`, `sms_classify`, notifies unless spam, emits `sms_received` and `sms_changed` |
| `MmsWapPushReceiver` (`BROADCAST_WAP_PUSH`) | `WAP_PUSH_DELIVER`, `application/vnd.wap.mms-message` | `mms_notification`, `MmsCodec.download`; the result goes to `MmsInbox` (`mms_retrieved`, `Telephony.Mms`, notify) |
| `HeadlessSmsSendService` (`SEND_RESPOND_VIA_MESSAGE`) | `RESPOND_VIA_MESSAGE`, `sms`/`smsto`/`mms`/`mmsto` | sends the quick reply through `SmsSend` |
| `MainActivity` | `SENDTO` (the same four schemes), `SEND` (`text/plain`, `image/*`) | `SENDTO`/`SEND` become the one-shot `open_thread` `{tag: "new"\|"share", to[], body?, stream_uri?, mime?}`; a notification tap is `open_thread` `{tag: "sms:<id>"\|"wa:<jid>"}` |

The rest: `ReplyReceiver` (inline reply, copy code, mark read), `ScheduledSmsReceiver` (alarm target),
`BootReceiver` (re-arms stored alarms after boot or an app update), `SmsStatusReceiver`,
`MmsResultReceiver` and `MmsFileProvider` (MMS PDU files), none exported. `MainActivity` posts
`open_thread` through `RovstiBridge.postOneShot`, so it reaches Rust after its snapshot even on a cold
start, and forwards `onActivityResult` to `RovstiBridge.handleActivityResult` (backup file pickers).
`RovstiMessagesApp.onCreate` installs the bridge, sets `Headless.libName = "rovsti_messages"` and
registers `MmsInbox` as the MMS download handler, so a cold receiver has both before `onReceive`.
The app icon is a vector (`ic_stat_message`); `MessageNotifications` uses `applicationInfo.icon` as the
small icon, so a host without an icon cannot post.

**Headless call and fallback.** Receivers call `Headless.call(kind, json)` on their own worker thread
(`goAsync`). It loads the library once (`System.loadLibrary`) and calls
`RovstiBridge.nativeHeadless`. Kinds and JSON are documented at the top of
`apps/messages/src/headless.rs`. Any failure (no library, no symbol, panic, blank answer) is `null`
and every caller has a plain path: SMS is stored and notified without a passcode chip or spam check,
an MMS push is ignored, an inline reply on a WhatsApp notification is dropped. Spam rules are read
from `<data dir>/messages-prefs.json` (`spam` key); a missing or corrupt file means defaults.

**Exact-alarm fallback.** Scheduled SMS use one `AlarmManager` alarm per item. `ScheduledSms.arm`
uses `setExactAndAllowWhileIdle` when `canScheduleExactAlarms()` is true. If the user denied
"Alarms & reminders" (or the call throws `SecurityException`) it uses `setAndAllowWhileIdle`, so the
SMS still goes out, possibly a few minutes late, and the UI says so. The payload is kept in
SharedPreferences and sent by `SmsSend` from the receiver, so no native library is needed;
`BootReceiver` re-arms after a reboot.

**FCM flavor.** Same as Phone: put `google-services.json` in `apps/messages/platforms/android/app/`.
Only then does `app/build.gradle.kts` apply the Google services plugin, add `firebase-messaging`, the
source directory `src/firebase/kotlin` and the manifest `src/firebase/AndroidManifest.xml`.
`RovstiMessagingService` calls `WaPush.onMessage(message.data)`; for `kind=message` while the UI is
not attached it calls `nativeHeadless("wa_digest", {dir, chat})` and renders the answer with
`MessageNotifications` (AD-MSG5). With the UI attached the app posts from the event stream.

Pure Kotlin (`ComposeRequest`, the Rust answers) has JVM tests in
`app/src/test/kotlin/dev/rovsti/messages`. Build: `gpuiforge build android` in `apps/messages`.
Device checks are in [messages-acceptance.md](messages-acceptance.md).

## Stops host

`apps/stops/platforms/android` is ejected like Contacts and Messages: `gpuiforge.json` has
`management: manual`, `project-dir: platforms/android`, activity `dev.rovsti.stops.MainActivity`,
`min_sdk` 34 and `target_sdk` 36. The shared Kotlin bridge is a Gradle source directory
(`crates/rovsti_android/kotlin`), not a copy. Permissions: `ACCESS_FINE_LOCATION`,
`ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`,
`USE_FULL_SCREEN_INTENT`, `VIBRATE`, `WAKE_LOCK`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`,
`USE_EXACT_ALARM` (Stops is an alarm app; no `SCHEDULE_EXACT_ALARM`), `RECEIVE_BOOT_COMPLETED`,
`ACCESS_NETWORK_STATE` and `INTERNET` (gateway, arriving-soon message only).

`ACCESS_BACKGROUND_LOCATION` is there because of the Android 11+ while-in-use rule: a foreground
service of type `location` started while the app is in the background (after a process death, a
`START_FLAG_REDELIVERY` restart or the watchdog) only gets location when the app holds it. Stops has
`minSdk` 34, so the rule always applies. It is recommended, not required (AD-SA7 in
[architecture.md](architecture.md)): the Trip tab asks for it after foreground location. When Android
still refuses a background restart, `TripService` posts a notification on the channel `trip_problem`
("Trip paused", `IMPORTANCE_HIGH`) and Stops resumes with `backend::resume_trip` (AD-SA8).

Stops also uses the shared `telecom::share_text` (Saved > a place > Share); without a share sheet it copies the text to the clipboard.

| Component | Does |
| --- | --- |
| `TripService` (`foregroundServiceType="location"`) | location updates; each fix goes to `Headless.call("trip_fix", ...)`, the answer sets the next interval, the ongoing notification and the ring |
| `TripWatchdogReceiver` | exact alarm that asks for a fix when none arrived (Doze) |
| `AlarmActivity` | plain Views over the lock screen: Dismiss and Snooze; Back snoozes |
| `TripAlarmReceiver` | snooze fire and the notification's Dismiss/Snooze actions, through `Headless.call("trip_alarm", ...)` |
| `BootReceiver` | ends a trip left running before a reboot (`trip_end`, `cancelled`) |
| `MainActivity` | `VIEW` for `geo:` and the maps hosts (`maps.google.com`, `www.google.com/maps`, `www.openstreetmap.org`, `maps.apple.com`), `SEND text/plain`; both reach Rust through `Application::on_open_urls` and `on_receive_share` |

**Idle location.** To centre the map before a trip, `StopsLocation` answers two bridge calls:
`lastLocation` (sync, the last known fix or nothing) and `locateOnce` (asks for one fresh fix and
returns false without permission or a provider). The fix arrives as a `stops_location` event
(`rovsti_android::location`, parsed by `parse_location_fix`). There is no background tracking and
no fix is stored.

`RovstiStopsApp.onCreate` installs the bridge and sets `Headless.libName = "rovsti_stops"`. Kinds and
JSON of the headless call are documented at the top of `apps/stops/src/headless.rs`.

**Settings that reach Kotlin.** Sound and vibration are read by `TripAlarm.ring` from
`stops-prefs.json` in the data directory of the last trip (both on when the file is missing or
unreadable, so a test ring before the first trip uses the defaults). The snooze length is applied in
Rust (`trip_alarm`, `snooze_min`). The Settings tab also offers the battery exemption
(`xiaomi::request_ignore_battery_optimizations`), the Xiaomi autostart page when `xiaomi::detect()`
finds MIUI or HyperOS, and the exact-alarm status.

**Alarm channel, audio and full screen.** `TripAlarm` posts on the channel `trip_alarm_v2`
(`IMPORTANCE_HIGH`, alarm sound with `USAGE_ALARM` attributes, `setBypassDnd(true)`; the old
`trip_alarm` channel is deleted), with `CATEGORY_ALARM`, so Do Not Disturb treats it as an alarm. On
Android 14+ `NotificationManager.canUseFullScreenIntent()` is checked at ring time; when denied the
heads-up notification and sound still run and Kotlin emits `{"state":"error","error":"full_screen"}`
on `trip_status`. The Stops UI row "Let the alarm wake the screen" reads
`rovsti_android::permissions::can_use_full_screen_intent` (desktop `Unsupported`), opens
`open_full_screen_intent_settings` and refreshes when the window is activated again.
`AlarmActivity` uses `setShowWhenLocked` and `setTurnScreenOn`. `AlarmSound` requests
`AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE` with alarm attributes (abandoned on stop), raises
`STREAM_ALARM` to at least 80 % of max (or keeps a louder user level) with a ramp of about 4 s and
restores the saved level afterwards, then walks the sound fallback chain (AD-SA4). The ring stops
after 10 minutes and re-rings after the snooze length (AD-SA5). The policy comes from
`rovsti_geo::alarm::AlarmPlan`.

**Doze.** Snooze uses `setAlarmClock`, which Doze lets through; the watchdog is an exact alarm. If
`canScheduleExactAlarms()` is false they fall back to `setExactAndAllowWhileIdle`, which may be late.
HyperOS can still delay location for a foreground service; the battery and autostart rows are the
mitigation. None of this has run on a phone: the open item is in [roadmap.md](roadmap.md#rovsti-stops).

**Trigger and `FixOut`.** The service is trigger-agnostic: it reads `state`, `ring`, `next_fix_ms`,
`high_accuracy`, `retry_ms` (watchdog window), `title` and `text`. `distance_m` is now the distance to the line A-B. New fields,
all optional so older answers still parse: `side` (`"left"`, `"right"` or absent when on the line),
`inside` (bool, in the circle) and `circle_m` (metres to the circle, 0 inside). A `trip.json` of the
old single-destination shape is moved to `trip.legacy.json` and the service is answered
`{"state":"ended"}`.

Desktop: `ROVSTI_STOPS_SIMULATE=1 cargo run -p rovsti_stops` feeds synthetic fixes through the same
headless handler; the simulator starts 2 km left of the midpoint and walks across the diameter to
the right side, so the alarm rings once. Build on Android: `gpuiforge build android` in `apps/stops`.

## WhatsApp push and call audio

WhatsApp lives on the gateway ([architecture.md](architecture.md#whatsapp-gateway)).
The Kotlin side adds three small pieces to the bridge:

- `WaPush`: `RovstiBridge.waPushToken()` reads the FCM token **by reflection** on
  `com.google.firebase.messaging.FirebaseMessaging`, so a host without Firebase still
  compiles and gets `""` (the app then registers nothing and the gateway sends it no
  push). `WaPush.onMessage(data)` turns an FCM data message into the Rust event
  `wa_push` (JSON of the data map).
- `WaCallAudio`: `AudioRecord` (`VOICE_COMMUNICATION`) and `AudioTrack`
  (`USAGE_VOICE_COMMUNICATION`), 16 kHz mono PCM16, 960-sample frames passed as
  base64 through `waAudioStart`, `waAudioStop`, `waMicFrame` and `waPlayFrame`.
  `waAudioStart` sets `AudioManager.MODE_IN_COMMUNICATION` and returns `false`
  without `RECORD_AUDIO`. Phone declares that permission in `gpuiforge.json`. The
  speaker toggle is `rovsti_android::wa::call_audio_speaker`.
- `WaIncoming`: the incoming WhatsApp call notification, `CallStyle.forIncomingCall`
  on channel `wa_calls` with a full-screen intent and Answer and Decline actions.
  `WaIncomingNotice.of(data)` (pure, JVM-tested) reads an FCM `kind=call` message and
  picks the title: `name`, else the number in `from`, else "WhatsApp call".
  `WaPush.onMessage` shows it before it emits `wa_push`, so a cold process rings.
  Rust can show or cancel it through `rovsti_android::wa::show_incoming` and
  `cancel_incoming`.
- `wa_call_action`: each notification button opens the launch activity with the extras
  `wa_call_id` and `wa_call_action` (`answer` or `decline`). The host's `MainActivity`
  calls `RovstiBridge.deliverWaCallAction(intent)`, which queues the event
  `wa_call_action` (`{"call_id", "action"}`); Phone answers or declines once per call
  id. Decline sends the task back (`moveTaskToBack`) so the UI does not open.
- `call_end`: `WaPush.onMessage` handles an FCM `kind=call_end` message before it emits
  `wa_push`: it cancels the ringing `WaIncoming` notification for `call_id`, and with
  `missed=1` posts "Missed WhatsApp call" on channel `wa_missed` (importance default,
  title from the same name, number or "WhatsApp call" rule; tapping it opens the launch
  activity). `WaMissedNotice.of(data)` and `WaIncoming.missedNotificationId` are pure and
  JVM-tested; the missed notification id differs from the ringing one for the same call.
  The `wa_push` event then makes Phone fetch `GET /v1/calls`, which puts the call into
  Recents.

Copy `WaPush.kt`, `WaIncoming.kt` and `WaCallAudio.kt` next to `RovstiBridge.kt`. Push itself is
optional and is a separate source set in the Phone host. Put `google-services.json` in
`apps/phone/platforms/android/app/`. Only when that file exists, `app/build.gradle.kts`
applies the `com.google.gms.google-services` plugin, adds `firebase-messaging`, adds
the source directory `src/firebase/kotlin` and merges `src/firebase/AndroidManifest.xml`.
Without the file the build is unchanged and no Firebase code is compiled.

The source set holds `RovstiMessagingService`, a `FirebaseMessagingService` that only
calls `WaPush.onMessage(message.data)`:

- `kind=call`: `WaIncoming` posts the full-screen notification; Answer opens the
  activity and `wa_call_action` reaches Rust, which answers through the gateway.
- `kind=call_end`: the ringing notification goes away; a missed call posts the
  `wa_missed` notification.
- `kind=message`: the `wa_push` event; no message text is in the push, the app fetches it from
  the gateway. Phone stops there. Messages also builds the notification with
  `nativeHeadless("wa_digest")` while its UI is not attached (see [Messages host](#messages-host)).

The token is registered once per launch and link; a rotated token is picked up on the
next launch.

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
