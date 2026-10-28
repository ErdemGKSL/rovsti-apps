# Rovsti Phone: owner acceptance checklist

For the person testing on the Xiaomi phone. Do the steps in order. Mark each row Pass or Fail
and write what you saw next to every Fail. You need a second phone to call yours.

APK: `~/Downloads/rovsti-phone-release.apk` (signed by `CN=ErdemGKSL`, built with
`bash scripts/build-release.sh`).

| # | Step | Expected | Pass / Fail | Notes |
|---|---|---|---|---|
| **A. Install** | | | | |
| 1 | Copy the APK to the phone and open it (or `adb install -r rovsti-phone-release.apk`). Allow installs from this source if asked. | Installs as "Rovsti Phone". | | |
| 2 | Open the app. | Keypad/Recents opens with the glass tab bar. Preview or "Fix" banner is allowed to show until step 5. | | |
| **B. Restricted settings and default app** | | | | |
| 3 | Settings, Apps, Rovsti Phone, three dots (top right), Allow restricted settings. If the entry is missing, do step 4 once first, then come back. | The entry appears and is accepted. | | |
| 4 | In the app open Setup (shield button), tap "Default phone app". | The system asks to make Rovsti Phone the default phone app. | | |
| 5 | Accept. Return to the app. | The row shows Done. Phone and call log, Contacts, Notifications and Full-screen notifications: tap each row and allow. Each ends as Done. | | |
| **C. Xiaomi rows** | | | | |
| 6 | Autostart: tap "Open settings", switch Rovsti Phone on, come back. | Done (read from the system), or tap "Confirm" if it stays Unverifiable. | | |
| 7 | Battery saver: open settings, choose "No restrictions". Confirm. | Done or confirmed. | | |
| 8 | Show on lock screen: open settings, allow, confirm. | Done or confirmed. | | |
| 9 | Background pop-ups: open settings, allow, confirm. | Done or confirmed. | | |
| 10 | Close and reopen the app. | Confirmed rows stay Done; when all rows are Done the Setup button stops asking for attention. | | |
| **D. Outgoing call** | | | | |
| 11 | Keypad: type a number. Keys react the moment the finger touches. | Digits appear without the layout jumping. | | |
| 12 | Tap Call. | "Calling..." then the In-Call screen. The other phone rings. | | |
| 13 | Answer on the other phone. Talk. | Timer runs. Speaker toggles the audio route. Mute silences you (the other side hears nothing). | | |
| 14 | Open the in-call keypad and press a digit. | A tone plays to the other side. | | |
| 15 | Press the Home button during the call. | Notification with timer and End. The "Return to call" pill shows in the app. | | |
| 16 | Tap End. | Call ends, "Call ended" shows briefly, app returns to the tabs. | | |
| **E. Incoming call** | | | | |
| 17 | Phone unlocked, another app in front. Call your phone. | Heads-up notification with Decline (left) and Answer (right). | | |
| 18 | Tap Answer. | In-Call screen opens, audio works. End the call. | | |
| 19 | Call again, tap Decline. | The call is rejected, the app does not open. | | |
| 20 | Lock the phone, screen off. Call it. | Screen turns on, the Incoming Call screen shows over the lock screen with Answer and Decline. | | |
| 21 | Lift the finger on Answer. | Call is answered only after you lift. Proximity turns the screen off at your ear. | | |
| 22 | End the call and lock the phone again. | The lock screen is normal again (no call screen left over). | | |
| 23 | Call and do not answer, let it go missed. | A missed call shows in Recents and in the tab bar badge. | | |
| **F. Recents, Contacts, Favorites** | | | | |
| 24 | Recents: scroll the list. | Smooth, names (not numbers) for saved contacts, missed calls in red. | | |
| 25 | Tap a row's call area. | The call starts to that number. End it. | | |
| 26 | Tap the "i" area of a row. | The details sheet opens; drag it down to close. | | |
| 27 | Contacts: scroll, search a name, search part of a number. | Matches appear; photos show for contacts that have one. | | |
| 28 | Favorites: tap a tile. | The call starts. End it. | | |
| 29 | Tap a Recents row while the "Return to call" pill or a banner is visible. | Only the pill/banner reacts, the row behind does not. | | |
| **G. Hold and waiting call** | | | | |
| 30 | During a call press Hold (if the carrier supports it). | Status shows Holding, the other side is silent. Press again to resume. | | |
| 31 | During a call have a third person call you. | A waiting-call banner appears with Answer and Decline, without moving other content. | | |
| 32 | Answer the waiting call. | The first call is held, the new one is active. Swap back works. | | |
| 33 | Tap End on the ongoing notification while a second call is ringing. | The active call ends, the waiting call keeps ringing. | | |
| **H. Performance and logs** | | | | |
| 34 | Follow `docs/perf/README.md` ("Measuring on the phone"). That needs the separate perf APK from `scripts/perf-build.sh`; it replaces the release APK, so reinstall the release one afterwards. | Table filled in. Idle shows `renders=0`. | | |
| 35 | Logs: `adb logcat -s Rovsti RovstiPerf`. Tag `Rovsti` shows errors and state changes, `RovstiPerf` shows `report ...` lines and call ids. | No phone numbers or contact names appear in either tag. | | |

## Reading the logs

* `adb logcat -s Rovsti` : app messages. Look for `ongoing-call notification failed` (foreground
  service refused) and `GPUI stopped; calls are unaffected` (UI died, the call is fine).
* `adb logcat -s RovstiPerf` : one `report scenario=... build_ms p50/p95/p99 ...` line per
  scenario. Targets are in `docs/perf/README.md`.
* Share a bug with: `adb logcat -d -s Rovsti RovstiPerf GPUI AndroidRuntime > rovsti.log`.

Result: ____ of 35 passed. Tester: ____  Date: ____  Phone / HyperOS version: ____

## Owner release 3: Settings, SIM, backup (Epic 16)

Install over the previous release (no uninstall). Single-SIM phones skip the SIM rows (S1 to S5):
nothing SIM-related may be visible on them, and that is itself a check.

| # | Step | Expected | Pass / Fail | Notes |
|---|---|---|---|---|
| **UJ-10 Settings** | | | | |
| S1 | On any tab tap the gear left of the shield. | The gear turns while held, Settings slides in from the right, the tab bar fades out. Back, the system Back and the edge swipe return. | | |
| S2 | Toggle "Proximity screen-off" off, place a call, hold the phone to your ear. | The screen stays on. Turn it back on: the screen blanks at the ear. | | |
| S3 | Turn on "Vibrate when call connects" and "Vibrate when call ends", place a call. | A short vibration when the call is answered, and when it ends. (If it does not come with the screen off, note it in `docs/spikes/s14-system.md`.) | | |
| S4 | Set Haptics to Off, then Light, then Normal and press keys and toggles. | Off: no app ticks. Light: key and toggle ticks only. Normal: all. | | |
| S5 | Keypad tones on, press keys; then off. | A dial-pad tone with each key when on, none when off. | | |
| S6 | Display: set Name format to "Last, First" and Sort by to "Last name". Visit Contacts, Favorites, Recents, call someone saved. | Names read "Last, First" everywhere including the In-Call screen, and Contacts sort by family name. Back to defaults restores the old look. | | |
| S7 | Ringtone and sounds. | The system sound settings open. | | |
| S8 | System shortcuts: Call forwarding and waiting, Accessibility, Default apps. | Each opens its system page (or App info with "Opened app settings instead"). "Hearing-aid compatibility" shows Supported or Not reported and does nothing. | | |
| **UJ-10 Backup** | | | | |
| S9 | Settings, Data, Export favorites and settings. | The system file picker; after saving: "Saved to rovsti-phone-backup-{date}.json". | | |
| S10 | Change a setting, then Import and pick that file. | The Import preview sheet lists what it replaces and says it cannot be undone. Import: the setting is back. Cancel leaves everything as is. | | |
| S11 | Import any text file that is not a backup. | Red row "This isn't a Rovsti Phone backup." and nothing changes. | | |
| **Dual SIM (skip on one SIM)** | | | | |
| S12 | Phone default SIM = "Ask every time". Type a number and tap Call. | "Call with" rises with both SIMs; the number stays in the field. Pick one: the call starts on it and the chip flies to the In-Call screen. | | |
| S13 | Dismiss the chooser by dragging it down. | Nothing is placed, the number stays typed. | | |
| S14 | Choose a default SIM in the phone settings, then hold the Call button for half a second. | The chooser opens with the default row captioned "Default". | | |
| S15 | Open Recents. | Each row shows its SIM at the end of the subtitle line; a row whose SIM is unknown shows nothing. | | |
| S16 | Settings, SIM, "Default for calls". | The system page for the default SIM opens. | | |
| **Diagnostics (long-press the Settings title for 0.8 s)** | | | | |
| S17 | Run SIM (after one call on each SIM), Backup (two pickers) and TalkBack/system (5 s countdown at the ear). | Copy the report and fill the PENDING tables in `docs/spikes/s5-sim.md`, `s13-saf.md` and `s14-system.md`. | | |
