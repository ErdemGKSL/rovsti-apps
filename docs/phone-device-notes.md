# Phone device notes

Not recorded yet: no phone was attached (`adb devices` empty) during the Story 1.1 run.
When the phone is connected with USB debugging on, record here:

- serial: (`adb devices`)
- Android / HyperOS version: (`adb shell getprop ro.build.version.release`, `ro.mi.os.version.name`)
- GPU renderer from `adb logcat -s GPUI` (Vulkan or GLES): 

## Setup sheet and Xiaomi state reads (2026-10-27)

- The Setup checklist lives in a glass bottom sheet (`rovsti_ui::sheet`) opened
  from the shield button in the page header; it never moves the page.
- Xiaomi state: Autostart (op 10008), Show on lock screen (10020) and
  Background pop-ups (10021) are read through reflection on
  `AppOpsManager.checkOpNoThrow(int, int, String)` (`XiaomiOps.kt`), mode 0 =
  allowed, 1/2 = denied, anything else = unknown. The codes come from community
  sources only (a widely copied `XiaomiUtilities` list); Xiaomi does not
  document them and HyperOS is unverified. When a read is unknown the row
  falls back to the stored "I did it" confirmation (valid for one app version).
  For 10020/10021 a confirmation also wins over a "denied" reading; for
  Autostart the reading is final.
- Battery saver "No restrictions" cannot be read; `isIgnoringBatteryOptimizations`
  counts as done, otherwise the owner confirms.
- Android 15+ restricted settings block sideloaded apps from the dialer role:
  open App info, three-dot menu, Allow restricted settings (the item appears
  only after one blocked attempt).
- Desktop preview: `ROVSTI_PREVIEW_XIAOMI=1 ROVSTI_PREVIEW_SHEET=1 cargo run -p rovsti_phone`.

## Dial and talk (Epic 4) device checks

Install the signed release APK, make Rovsti Phone the default phone app, then:

- Dial intent (4.4): `adb shell am start -a android.intent.action.DIAL -d "tel:+905551112233"`
  opens the Keypad with the number typed and places no call. `-d "tel:*21%23"`
  gives `*21#`; `adb shell am start -a android.intent.action.DIAL` (no data)
  opens an empty Keypad. A `tel:` link in the browser behaves the same.
- Call (4.3): type a number, tap Call; the status reads "Calling..." until the
  In-Call screen appears. `*#06#` and `112` must dial unchanged.
- Failure containment (4.10), debug builds only (the receiver is not registered
  in release): during a call run
  `adb shell am broadcast -a dev.rovsti.phone.DEBUG_KILL_GPUI`. The call
  continues, the notification stays and End works. Record the result here.
- Ongoing call (4.9): the notification shows the caller, a running chronometer
  and End; tapping the body returns to the In-Call screen. If
  `startForeground(phoneCall)` is refused, logcat tag `Rovsti` has
  "ongoing-call notification failed".
- Desktop preview of the In-Call screen: `ROVSTI_PREVIEW_CALL=1 cargo run -p rovsti_phone`
  (placing a call there also walks a fake call through Dialing and Active).

## Incoming calls (Epic 5) device checks

Install the signed release APK and make Rovsti Phone the default phone app. Call the phone
from another phone (or `adb emu gsm call <number>` on an emulator). In every case the call
must be answerable and declinable; the notification is the safety net.

1. Unlocked, another app in front (5.1): a heads-up call notification appears with the name
   (or number), Decline on the left and Answer on the right. Decline rejects the call and the
   app does not open. Answer answers it and opens the In-Call screen. Tapping the body opens
   the Incoming Call screen.
2. Locked, screen off (5.2): the phone wakes and shows the Incoming Call screen over the lock
   screen (lock icon beside "Incoming call", avatar, name, Decline left, Answer right). No tabs.
   Back does nothing; the volume key silences the ring and the screen stays. If HyperOS shows
   only the heads-up, note it: Settings, Apps, Rovsti Phone, Other permissions, "Display
   pop-up windows while running in the background" and "Show on lock screen", and the Full-screen
   notifications permission.
3. Answer / Decline (5.3): Answer acts when the finger lifts and the In-Call screen takes over
   with no unlock. Decline rejects, the screen leaves and the lock screen returns. A notification
   Answer pressed after the caller hung up does nothing.
4. Ringer (5.4): normal mode, the system rings and vibrates (the app plays nothing, no double
   ring). Silent mode and Do Not Disturb: no sound, the screen or notification still appears.
5. Missed (5.5): let it ring out on the screen. It shows "Missed call from {name}" for about
   1.2 s and leaves (the lock screen returns when it arrived locked). The entry appears in the
   system call log; no notification of our own is posted.
6. Ring latency (5.6): see `docs/spikes/s2-incoming.md` (`adb logcat -s RovstiPerf`).
7. Proximity (5.7): on a call on the earpiece, hold the phone to your ear: the screen goes
   dark, and wakes when you move it away. Speaker or Bluetooth keeps the screen on. After the call
   `adb shell dumpsys power | grep -i -E "proximity|Rovsti"` must show no `Rovsti:proximity`
   wake lock. If the screen does not wake at the end of a call against the cheek or stays
   dark after moving away, record it: the release flag (`RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY`)
   is an unverified HyperOS choice.
- Desktop preview of the Incoming Call screen:
  `ROVSTI_PREVIEW_INCOMING=Ada cargo run -p rovsti_phone` (`=1` for an unknown number),
  plus `ROVSTI_PREVIEW_LOCKED=1` for the lock-screen look and `ROVSTI_PREVIEW_MISSED=1` to
  let the caller give up after 6 s.
