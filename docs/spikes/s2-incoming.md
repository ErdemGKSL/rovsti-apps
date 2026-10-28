# Spike S2: incoming call surface and ring latency (story 5.6)

Status: instrumented, **waiting for the owner's phone run**. No cold or warm
numbers exist yet, so the native curtain is **not** enabled
(`NativeIncoming.CURTAIN_ENABLED = false`). Fill the table below, then apply
the decision rule.

## What is measured

Logcat tag `RovstiPerf`, one line per ringing call, printed when the Incoming
Call screen draws its first GPUI frame:

```
incoming call=3 locked=true process=cold launch=cold notify_ms=9 activity_created_ms=412 activity_resumed_ms=455 first_frame_ms=871
```

- `call=` the Kotlin call id (names and numbers are never logged).
- `locked=` the keyguard was up when the call arrived.
- `process=cold|warm`: the app process started for this call (younger than 6 s at `onCallAdded`) or was already running.
- `launch=cold|warm`: `cold` = `MainActivity` (and its GPUI session) was created for this call; `warm` = it already existed and only came forward.
- `notify_ms` the incoming notification was posted, `activity_created_ms` / `activity_resumed_ms` the activity came up, `first_frame_ms` Rust drew the first frame of the Incoming Call screen. All are milliseconds since `RovstiInCallService.onCallAdded`.

Read it with:

```
adb logcat -c
adb logcat -s RovstiPerf
```

(Wireless debugging is fine; no USB cable needed while the phone rings.)

The pieces: `IncomingPerf` / `IncomingTimeline` (Kotlin, `apps/phone/platforms/android/.../IncomingPerf.kt`),
`CallRegistry.incomingFrame` (JNI target of `rovsti_android::calls::incoming_frame`), `PhoneApp::render_incoming` (reports once per call).

## Procedure

For each of the four cases make **5** incoming calls (another phone, or `adb emu gsm call` on an emulator) and take the `first_frame_ms` values:

| Case | How to get it | min | median | max | blank frame seen? |
| --- | --- | --- | --- | --- | --- |
| Warm, unlocked | Phone app opened earlier (`launch=warm`), screen on, another app in front | | | | |
| Warm, locked | Same, then lock the phone | | | | |
| Cold, unlocked | Swipe Rovsti Phone away from Recents, unlock, wait 10 s | | | | |
| Cold, locked | Swipe it away, lock, wait 10 s | | | | |

Targets (NFR-2): warm under **1.5 s**, cold under **3 s**. "Blank frame" means any moment
where the screen is white or black without the wash and the caller name.

Also record, for the notification path, how long after the phone starts ringing the heads-up
(unlocked) or the full-screen screen (locked) appears; the heads-up must always show and its
Answer / Decline must work even if the screen never does.

## Decision rule (AD-1)

- Cold-start **median over 3 s**, or **any blank frame**: turn the curtain on. Set
  `NativeIncoming.CURTAIN_ENABLED = true` in `NativeIncoming.kt`, rebuild with
  `bash scripts/build-release.sh`, and repeat the cold rows. The curtain is the native Kotlin
  Incoming screen (same layout, labels, order and copy, solid fills) put over the GPUI surface
  at launch while a call rings and removed on the first GPUI frame (`onIncomingFrame`), with an
  8 s safety removal. Its buttons act through `CallRegistry` and never touch Rust.
- Otherwise record "curtain not needed" here and leave the switch off.

## Related device checks (stories 5.1 to 5.7)

Record the outcome of each in `docs/phone-device-notes.md` (section "Incoming calls (Epic 5)").

## Result

(To fill in from the phone run.)
