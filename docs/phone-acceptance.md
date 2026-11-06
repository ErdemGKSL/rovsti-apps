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
| 11a | Keypad: type 30 digits on a small (360x640) and a tall phone. | Number font shrinks to 22, then a leading ellipsis keeps the end visible; the number never wraps into the keys and the keys do not move. Layout comes from `rovsti_core::keypad_layout`. | | |
| 11b | Keypad: type a number with many matches and a valid unsaved number. | Matches and the Add to contacts / Message / WhatsApp chips keep a fixed height (extras collapse into "N more"); nothing overlaps the keys, call row or tab bar and the bottom inset is respected. | | |
| 11c | Keypad: check Portrait (key 56 and up), Compact (small height) and Landscape (rotate). | Keys stay 36..78; letters under digits show only at key 60 and above; in Landscape the grid sits right and the call row left, all inside the screen. | | |
| 11d | Keypad: rotate to Landscape and type a number. | The title is a single row beside the setup and gear buttons; nothing overlaps the number, matches or keys. | | |
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

## Owner release 5: Blocking and incoming controls (Epics 17 and 18)

Release 4 ("Blocking", 17.6) was not built on its own: owner release 5 carries both epics. Install
over the previous release (no uninstall). Rovsti Phone must be the default phone app. Use a second
phone to call this one; its number goes into the S6, S7 and S8 steps below.

| # | Step | Expected | Pass / Fail | Notes |
|---|---|---|---|---|
| **UJ-9 Blocking** | | | | |
| B1 | Recents: hold a row for half a second. | A light tick; a menu opens beside the row with Copy number, Message, Block number. The row stays visible, the page dims a little. Tap outside: it closes, nothing happens. | | |
| B2 | Hold a row of the second phone's number (call it once first), tap Block number. | The avatar greys, a ring with a slash settles over it, the row fades and the list closes up. The pill reads "Blocked {name}" with Undo. | | |
| B3 | Tap Undo within 5 s. | The row comes back. Block again. | | |
| B4 | Call this phone from the second phone. | It does not ring and shows no badge and no notification. Settings, Blocked numbers, Blocked calls: the call is listed "Blocked by you". (Note ring / no ring in `docs/spikes/s6-blocklist.md`.) | | |
| B5 | Settings, Blocked numbers. | Numbers shows the number with an Unblock pill; the footnote says Xiaomi's own list may be separate. Type a second number and tap Block: it fades in at the top and the pill says "Blocked {number}". Type "abc": "Not a valid number". Type 112: "Emergency numbers can't be blocked". | | |
| B6 | Unblock the second phone's number, then call again. | It rings. The pill after Unblock offers Undo. | | |
| B7 | Let a call from an unknown number ring out, or decline it. | After "Call ended" the tabs show a pill "Call from {number}" with Block. Tap it: blocked, with Undo. While a call rings there is no Block control. | | |
| B8 | Open a blocked contact's sheet (Contacts). | A small ban glyph follows the blocked number. | | |
| **UJ-3 / UJ-9 Incoming controls** | | | | |
| R1 | Call this phone. Look at the ringing screen. | Message (left) and Silence (right) circles above Decline and Answer; avatar, name and the two buttons are where they always were. The first three calls show "Drag the card to answer or decline". | | |
| R2 | Tap Message. | "Decline with message" rises with your three replies and "Write your own…". Drag it down or press Back: back to the ringing card, the call keeps ringing. | | |
| R3 | Tap Message, choose a reply. | The text flies to the avatar. The label says "Opened Messages" (HANDOFF is the shipped default) and the system Messages app opens with the text typed in: you send it yourself. Never "Sent" unless S7 passed. | | |
| R4 | Tap Message, "Write your own…". | The call is declined, Messages opens on the caller with an empty body. | | |
| R5 | Drag the card right 100 px and let go. Another call: drag left. Another: drag up. | Right answers (green wash, Answer bubble), left declines (red wash, card flies off), up opens the reply sheet. A short or slow drag springs back and does nothing. The buttons still work as taps. | | |
| R6 | Put a finger on the card, slide it down onto Answer and lift. | Nothing is answered. | | |
| R7 | Tap Silence while it rings. | The rings, breathing and glow ease to rest, the colours calm down, the control floods teal and reads "Silenced". The call is still answerable and rings out as missed if untouched. | | |
| R8 | Press a volume key while it rings. | Silenced (silenced by volume). | | |
| R9 | Settings, Flip to silence on. Call, turn the phone face down. | Silenced, "Silenced by flip", a medium tick. A phone already face down when the call starts does not silence. | | |
| R10 | Heads-up notification while the phone is on another app. | Decline, Answer and Message actions. Message raises the app with the reply sheet already open. | | |
| R11 | During a call have a second caller ring. Tap Message on the Waiting card. | The reply sheet opens over the call; choosing a reply declines only the waiting caller, the call underneath is untouched. With no replies the pill says "Add replies in Settings". | | |
| **Quick replies** | | | | |
| Q1 | Settings, Quick replies. | Three replies, each with a drag handle. Edit one: it saves by itself. At 80 characters a counter appears; 100 is the limit. | | |
| Q2 | Drag a handle up or down. | The row follows the finger, the others make room, the order is kept. With a row focused, Move up / Move down buttons appear (the TalkBack way). | | |
| Q3 | Add reply until 6; clear all rows. | "Up to 6 replies" at 6. With none: "With no replies, Message is hidden", and the Message control and the notification action disappear. | | |
| Q4 | Reset to defaults. | A confirm sheet; Reset restores the three defaults. | | |
| **Diagnostics (long-press the Settings title)** | | | | |
| D1 | Blocking (after B2 and B4), Reject text (call this phone within 40 s of Run), Silence (three rings within 75 s: tap Silence, volume key, flip), TalkBack/system (with TalkBack on, walk the flows). | Copy the report and fill the PENDING tables in `docs/spikes/s6-blocklist.md`, `s7-reject-text.md`, `s8-silence.md` and `s14-system.md`. | | |

## Quick actions, history, Favorites order (UX pass)

Install over the previous release. Needs a contact with one number, a contact with two numbers and
no default, an unsaved number in Recents, and a few calls (one missed) with the same person.

| # | Step | Expected | Pass / Fail | Notes |
|---|---|---|---|---|
| **Quick actions** | | | | |
| X1 | Open a contact with one number (Contacts or a Favorites tile). | Under the header a strip of four tiles: Message, Copy, Share, Block. | | |
| X2 | Tap Message, then Copy, then Share. | Message opens the SMS app on that number. Copy shows the pill "Number copied". Share opens the system share sheet with the number. | | |
| X3 | Tap Block. | The sheet closes and the pill "Blocked {name}" shows with Undo. Undo restores it; the tile reads Unblock while blocked. | | |
| X4 | Open a contact with two numbers and no default; tap Message. | The sheet asks "Message which number?"; tapping a number sends that one to the SMS app instead of calling. The same works for Copy, Share and Block. | | |
| X5 | Recents: tap "i" on an unsaved number. | The Number sheet shows the same strip, plus a WhatsApp pill under Call when WhatsApp is connected. | | |
| **Recent calls** | | | | |
| X6 | Tap "i" on a grouped row such as "Ayşe (3)". | "Recent calls" lists up to 10 lines: icon, type, day and time on the right, and the duration for answered calls. Missed lines are red and show no duration. | | |
| X7 | Open the Contact sheet of the same person. | The same "Recent calls" section. A contact with no calls shows no section. | | |
| X8 | Open the sheet of a person with a WhatsApp call. | The line reads "WhatsApp ..." with the WhatsApp badge. | | |
| **Favorites order and Frequent** | | | | |
| X9 | Open a contact from a Favorites tile, tap "Move earlier". | The tile moves one place up. "Move earlier" is dimmed on the first tile, "Move later" on the last. | | |
| X10 | Hold a tile, then drag it onto another slot and lift. | The order changes once. Close and reopen the app: the order is kept. Export then Import a backup: the order is kept. | | |
| X11 | Star a new contact. | It is added at the end of Favorites. | | |
| X12 | Call two or more non-starred people several times, open Favorites. | A "Frequent" row of up to four people shows below the grid. Tapping one opens the sheet and never places a call. | | |
| X13 | Settings, Display, turn "Show Frequent" off. | The Frequent row is gone at once. On brings it back. | | |
| **Keypad, Recents menu, call-back** | | | | |
| X14 | Keypad: type an unsaved valid number. | The status line offers "Add to contacts" and "Message". A saved number shows its name instead. | | |
| X15 | Tap "Add to contacts"; then clear and try "Message". | The Contacts app opens with the number filled in; Message opens the SMS app. | | |
| X16 | Recents: hold an unsaved row. | The menu has "Add to contacts" after Copy number; a saved row does not show it. | | |
| X17 | Miss a WhatsApp call, tap that row in Recents (WhatsApp connected). | A WhatsApp call starts, not a carrier call. With WhatsApp disconnected it calls by carrier. | | |
| **Undo pills** | | | | |
| X18 | Settings, Speed dial: remove an assigned digit. | The pill "Removed speed dial {d}" shows with Undo; Undo restores the same contact. | | |
| X19 | Settings, Quick replies: tap Clear on a filled reply. | The pill "Reply cleared" shows with Undo; Undo restores the text. Clearing an empty field shows no pill. | | |

## Delete call history and Contact sheet speed dial (UX pass, round 2)

Needs a few calls with the same person (one a WhatsApp call, WhatsApp connected) and a contact
that is not on a speed dial digit. The delete rows need the call log write permission (spike S12,
`docs/spikes/s12-calllog.md`).

| # | Step | Expected | Pass / Fail | Notes |
|---|---|---|---|---|
| D1 | Recents: hold a grouped row such as "Ayşe (3)", tap "Delete 3 calls". | The row disappears and the pill "Deleted 3 calls" shows with Undo. A single call reads "Delete from Recents". | | |
| D2 | Tap Undo on the pill. | The row comes back with the same calls, times and durations; the missed badge matches again. | | |
| D3 | Open the "i" sheet of a person with calls (Number or Contact sheet), tap "Delete call history". | A confirm sheet "Delete calls with {name}?" shows the count. Cancel changes nothing. | | |
| D4 | Confirm with Delete. | Every call with that person is gone from Recents and from the sheet; the pill "Deleted {n} calls" shows with Undo, and Undo restores them. | | |
| D5 | Settings, Data, "Delete all call history", then Delete. | Recents shows its empty state, the missed badge is 0, the pill reads "Call history deleted" (no Undo). | | |
| D6 | Delete a WhatsApp call row (D1 path), wait for or force a gateway sync, reopen Recents. | The deleted WhatsApp call does not come back. After D2's Undo it is back. | | |
| D7 | Take the call log permission away in system settings, repeat D1. | The pill offers "Allow call log access"; if the system refuses writes the pill reads "Not allowed on this phone" and nothing is removed or lost. | | |
| D8 | Contacts: open a contact with a number that has no speed dial. | Under the numbers a row "Add to speed dial". | | |
| D9 | Tap it, then pick 5. | The row reads "Speed dial 5" with Remove, the pill reads "Speed dial 5: {name}" with Undo, and Settings, Speed dial lists the person on 5. | | |
| D10 | Pick a digit another person holds. | The pill reads "Speed dial {d}: {name} (was {other})"; Undo gives the digit back to the other person. | | |
| D11 | On a contact with two numbers and no default, pick a digit. | The sheet asks "Speed dial which number?" and assigns the chosen one. | | |
| D12 | Tap Remove. | The pill "Removed speed dial {d}" shows with Undo; Undo restores it. Holding the digit on the empty Keypad calls the person while assigned. | | |

## Single-call delete, blocked calls, voicemail, search actions, Setup (UX pass, round 3)

Needs a person with three calls (grouped as "Ayşe (3)"), a blocked number with at least two
blocked calls, and for V2 a phone with no voicemail number set (or a SIM without voicemail).
Delete rows need the call log write permission (spike S12).

| # | Step | Expected | Pass / Fail | Notes |
|---|---|---|---|---|
| R1 | Recents: open the "i" sheet of "Ayşe (3)", tap Edit above "Recent calls". | A trash button appears on each call line; Edit reads Done. | | |
| R2 | Tap the trash on the middle call. | Pill "Deleted 1 call" with Undo; the sheet shows two lines and Recents shows "Ayşe (2)". The sheet stays open. | | |
| R3 | Tap Undo with the sheet still open. | The third call is back in the open sheet and in Recents. | | |
| R4 | End a new call while the sheet is open (or delete one on the Contact sheet). | The sheet list updates without closing and reopening it. | | |
| R5 | Settings, Blocked numbers, Blocked calls, tap a row. | The Number sheet lists "Blocked call" lines with time and no duration. | | |
| R6 | Edit, delete one blocked call. | Pill with Undo; the Blocked calls list updates; Undo restores it. | | |
| R7 | Tap "Delete call history", confirm. | Confirm text reads "Delete blocked calls from {who}?"; afterwards that number has no blocked calls and Undo restores them. | | |
| R8 | Keypad: look at the 1 key. | A small voicemail glyph sits under the 1; the screen reader says "1, hold for voicemail". | | |
| R9 | V2 (no voicemail number): hold 1. | The status reads "No voicemail number set" with "Open call settings"; no call is placed; the button opens the system call settings. Device check of S9 is PENDING. | | |
| R10 | Contacts search: type or paste an unsaved number such as 0555 111 22 33. | "No contact has {number}." with Call, Message and Add to contacts, each working. | | |
| R11 | Keypad: type a number that belongs to a contact, tap the name in the status line. | That contact's sheet opens. | | |
| R12 | Setup: on a Xiaomi row confirmed with "I did it", look at the buttons. | One button "Mark as not done"; tapping it makes the row not done again. Rows read from the system have no such button. | | |
| R13 | Revoke a permission in system settings, return to the app, open Setup. | The header reads "Something changed: {item}" until the item is fixed, then "Finish setup" or the done state. A plain app launch never shows it. | | |
| R14 | Keypad: type digits that match more contacts than the strip shows, tap "N more". | A "Matches" sheet lists every match with the typed letters highlighted. Tapping a row fills the field with its number and closes the sheet; the call circle places the call. | | |
| R15 | Recents: tap a WhatsApp row while no WhatsApp instance is linked. | The notice reads "WhatsApp is not connected" with "Connect"; tapping it opens Settings, WhatsApp. | | |
| R16 | Deny the Contacts permission, then type a digit on the Keypad. | The status line reads "Allow Contacts to see matches" with "Grant access"; it asks for the permission (or opens app details when permanently denied) and is not shown again that session. | | |
| R17 | Settings, Data: import a backup, then tap Undo in the pill. | The previous settings come back, the rows refresh and the pill reads "Import undone". | | |
| R18 | Settings, Data: tap "Reset settings", then Undo. | Calls, sounds and display return to defaults with a "Settings reset" pill; Undo restores them. The row is dimmed ("Already the defaults") when nothing differs. | | |
| R19 | Fresh install: place a WhatsApp call. Deny the microphone prompt; look at the pill; tap Allow. Repeat and deny with "Don't ask again", then tap Allow. | Android asks for the microphone on the first call. After a deny the pill reads "Allow the microphone so they can hear you" with Allow; allowing re-enables audio on the live call. When permanently denied, Allow opens App info. | | |
| R20 | Place a call through the system phone app, or a WhatsApp call while another is in progress. | The outcome ("Placed through the system phone app", "Another call is in progress") shows as a 2 s pill and goes; rows behind stay tappable. "Connect" and "Grant" notices keep their banner. | | |
| R21 | Settings, Speed dial: assign 5 to a contact; open the Keypad; use TalkBack on 5. | A coloured dot sits at the top-right of 5 only. TalkBack reads "5, speed dial {name}. Long press to call". | | |
| R22 | Keypad: hold a free digit such as 7, tap Set in the "No speed dial on 7" pill. | Contacts opens with "Choose a contact for 7"; tapping a number assigns 7 without picking the digit again. | | |

## WhatsApp calls

Needs the gateway running (`docs/whatsapp-gateway.md`), WhatsApp linked or connected under
Settings, WhatsApp, and a second WhatsApp account to call from and to. For the locked-phone
rows the gateway needs FCM configured and the APK must be built with `google-services.json`
in `apps/phone/platforms/android/app/` (`docs/android.md`). Allow the microphone when asked.

| # | Step | Expected | Pass / Fail | Notes |
|---|---|---|---|---|
| **Place** | | | | |
| W1 | Recents: open a row's menu, tap "WhatsApp call". | The In-Call screen shows the name with a "WhatsApp" caption and the status connecting, then the timer once answered. | | |
| W2 | Contacts: open a contact with a number. | A WhatsApp circle, outlined, sits beside Call for each number. | | |
| W3 | Tap that circle. | Same as W1. | | |
| W4 | Keypad: type a valid number. | A "WhatsApp" chip appears under the number; it is gone for an empty or invalid number. | | |
| W5 | Tap the chip. | Same as W1. | | |
| W6 | Settings, WhatsApp: disconnect (or use a build with no instance). | No WhatsApp circle on the contact sheet, no chip on the keypad. | | |
| **Controls** | | | | |
| W7 | During an answered WhatsApp call tap Mute. | The other side hears nothing; the button shows it is on. | | |
| W8 | Tap Speaker. | The audio moves to the loudspeaker and back. | | |
| W9 | Look at Keypad, Hold, Add, Merge and Swap. | Dimmed; Hold explains "Not available on WhatsApp calls". | | |
| W10 | Tap End. | The call ends on both sides; "Call ended" shows and the app returns to the tabs. | | |
| **Incoming, app open** | | | | |
| W11 | App open and unlocked. Call your WhatsApp from the other account. | The Incoming screen shows the caller with a "WhatsApp" caption, Answer and Decline. | | |
| W12 | Tap Answer. | In-Call screen, audio both ways. End it. | | |
| W13 | Call again and tap Decline. | The call is rejected; no notification stays behind. | | |
| **Incoming, locked, after FCM** | | | | |
| W14 | Close the app (swipe it away), lock the phone, screen off. Call your WhatsApp. | The push wakes the phone: the screen turns on with a WhatsApp call notification (caller name or number, Answer, Decline) over the lock screen. | | |
| W15 | Tap Answer on the locked-phone notification (app still killed from W14). | The app starts cold, the call connects without a second tap, audio works both ways. | | |
| W16 | Repeat W14 and tap Decline. | The call is rejected and the app does not open. | | |
| W17 | Repeat W14 (app killed) and let the caller hang up while the phone rings. | The ringing notification goes away and a "Missed WhatsApp call" notification (caller name or number) appears. | | |
| **Busy rules** | | | | |
| W18 | During a cellular call, make a WhatsApp call to yourself. | The cellular call keeps the screen. The WhatsApp offer shows as a waiting call with Decline only. | | |
| W19 | During a WhatsApp call, call the phone by cellular. | A cellular call rings on its own screen. The WhatsApp call is not dropped. | | |
| W20 | While a cellular call is ringing, make a WhatsApp call to the phone. | The WhatsApp call is declined at once; the cellular ring is not interrupted. | | |
| **Recents** | | | | |
| W21 | Open Recents after W1 to W20. | Each WhatsApp call is a row with a WhatsApp badge, grouped with the same person's cellular calls. | | |
| W22 | Miss a WhatsApp call, then open Recents. | The row is red and the tab bar badge counts it. | | |
| W23 | Close and reopen the app. | The WhatsApp rows are still there. | | |
| W24 | After W17, open Phone (tap the missed-call notification or the icon) and go to Recents. | The call from W17 is a red WhatsApp row and the tab bar badge counts it. | | |

## Utilities round 1: photo viewer, search, Copy as, CSV, pause and wait

Needs a contact with a photo, a contact without one, and a call log with missed, incoming,
outgoing and WhatsApp calls. Save needs Android 10 or newer (no storage permission).

| # | Step | Expected | Pass / Fail | Notes |
|---|---|---|---|---|
| **Photo viewer** | | | | |
| U1 | Open the sheet of a contact with a photo and tap the avatar. | The photo opens full screen on a dark backdrop. A contact without a photo does not open the viewer. | | |
| U2 | Pinch out, pinch in, then double-tap twice. | Pinch zooms around the fingers and stops at the limits; double-tap zooms in at the tap and the next one back to fit. | | |
| U3 | Zoom in and drag; fling and let go. | The photo pans and never leaves a gap at an edge; it settles with a short glide. | | |
| U4 | At fit size, drag down, then tap the close button. | Drag down dismisses the viewer (it springs back if short); the close button also closes it. | | |
| **Save and share** | | | | |
| U5 | In the viewer tap Save. | Pill "Saved to gallery"; the picture is in Gallery under Pictures/Rovsti. | | |
| U6 | Tap Share. | The system share sheet opens with the picture; sharing to another app shows the image. | | |
| **Search and filters** | | | | |
| U7 | Recents: type part of a name, then part of a number. | The list narrows to matching people and numbers; clearing the field restores the list. | | |
| U8 | Tap Missed, Incoming, Outgoing, WhatsApp, All in turn. | Each chip shows only that kind of call; with no match an empty state is shown. | | |
| **Copy as and Share as** | | | | |
| U9 | Open the Copy as chooser for a number (long-press a number action). | It lists display, International (E.164), national, tel: link and vCard; only forms that make sense for the number appear. | | |
| U10 | Choose International (E.164). | Pill "Copied International (E.164)"; pasting gives +country number with no spaces. | | |
| U11 | Choose Share as vCard. | The share sheet opens with a contact card that includes the name. | | |
| **CSV export** | | | | |
| U12 | Settings, Backup, Export call log (CSV), pick a place. | Pill confirms; the file opens as a table with time, number, name, type and duration. | | |
| U13 | Cancel the file picker. | No pill error and nothing is written. | | |
| **Pause and wait** | | | | |
| U14 | On the keypad type 5551234, then hold * for half a second. | The * becomes a comma, a haptic tick fires and the pill "Pause added" shows. | | |
| U15 | Type 1, hold #. | The # becomes a semicolon and the pill "Wait added" shows. | | |
| U16 | Hold * as the very first key. | It stays a *. | | |
| U17 | Dial 5551234,1 and place the call. | The call starts and, after the pause, the dialer sends 1 as a tone; a ; asks to confirm before sending. | | |

## Utilities round 2: statistics, notes, call details, QR and photo edit

Needs a call log with several calls (answered, missed, WhatsApp), a contact with a photo and one
without, and a second phone or app that can scan a QR code. Photo edit needs a Contacts provider
that accepts photo writes (the system Contacts app does).

| # | Step | Expected | Pass / Fail | Notes |
|---|---|---|---|---|
| **Call statistics (U7, U18)** | | | | |
| R2-U7a | Open the Contact sheet of someone you called. | A "Call statistics" block shows calls, talk time, longest call and a last-7-days bar chart. A person with no calls has no block. | | |
| R2-U7b | Open the Number sheet of an unsaved number. | The same block shows for that number only. | | |
| R2-U7c | Settings, Call statistics. | This week, last 7 days per day, last 8 weeks and top people by talk time are listed; missed calls add no talk time. | | |
| R2-U18 | In a sheet's statistics block tap Share. | The share sheet opens with a short text summary of that person's calls and talk time. | | |
| **Call details and notes (U8, U11, U19)** | | | | |
| R2-U11 | Tap a line in a sheet's "Recent calls". | The Call details sheet shows date, time, direction, duration, SIM and WhatsApp. Back returns to the sheet. | | |
| R2-U11b | Tap Copy details. | Pill that the details were copied; pasting gives the same lines. | | |
| R2-U8a | Tap Note, type, then Done. | Pill "Note saved"; the history line now has a note glyph. | | |
| R2-U8b | Delete the note. | Pill "Note deleted" with Undo; Undo brings the text back ("Note restored"). | | |
| R2-U11c | Tap Delete this call. | The call goes from the log with the usual Undo pill. | | |
| R2-U19 | Settings, Backup, Export call log (CSV), open the file. | There is a `note` column holding the text of each call's note (empty without one). | | |
| **In-call note (U17)** | | | | |
| R2-U17a | During a call tap Note, type, Done. | Pill "Note saved". After the call the note is on that call in the Call details sheet. | | |
| R2-U17b | Hold the caller's name or number for half a second. | Pill "Number copied". A private caller says there is no number to copy. | | |
| **QR code (U9)** | | | | |
| R2-U9a | Contact sheet, tap "Share as QR code". | The viewer opens with the contact's name as title and a QR on a white card. Pinch and double-tap do nothing; swipe down closes. | | |
| R2-U9b | Scan the QR with another phone. | It offers to add a contact with that name and the numbers. | | |
| R2-U9c | Tap Save, then Share. | Save: pill "Saved to Photos", file `rovsti-<name>-qr.png` under Pictures/Rovsti. Share: the share sheet opens with the image. | | |
| R2-U9d | Try a contact with very many numbers. | The QR opens and a pill says "N numbers left out to fit". | | |
| R2-U9e | Number sheet of an unsaved number, tap "Share as QR code". | A QR of that single number opens, titled with the number. | | |
| **Photo set, replace, remove (U10)** | | | | |
| R2-U10a | Open a contact's photo in the viewer and tap Edit. | A menu offers "Choose new photo" and "Remove photo" (red). | | |
| R2-U10b | Choose new photo and pick an image. | Pill "Photo updated"; the viewer closes and the sheet and lists show the new photo. Cancelling the picker changes nothing. | | |
| R2-U10c | Edit, Remove photo. | The sheet "Remove photo of <name>?" asks first; Cancel changes nothing. | | |
| R2-U10d | Confirm Remove. | Pill "Photo removed"; the viewer closes and the contact shows the monogram. | | |
| R2-U10e | Open the Contact sheet of someone without a photo and tap the avatar. | The menu offers only "Add photo". | | |
| R2-U10f | Pick something that is not an image. | Pill "That is not an image"; nothing changes. | | |
| R2-U10g | With the menu open press Back. | The menu closes first; a second Back closes the viewer. | | |

## Round 3 utilities (U12-U14, U16, U20-U22, U24)

| ID | Steps | Expected | Result | Notes |
|---|---|---|---|---|
| R3-U12 | Recents, Select, tick rows (or Select all), Delete. | Title shows "n selected"; a confirm sheet; pill "Deleted n" with Undo restoring all rows. Back leaves select mode. | | |
| R3-U13 | Row menu, Remind me, pick a time. | Pill with the time and Undo; a notification at that time; "Cancel reminder" in the menu removes it (Undo re-arms). | | |
| R3-U14a | Type a number and hold the number field for half a second. | A haptic and a glass popover above the field with Copy, Paste (only if the clipboard has text) and Clear. A tap outside closes it. | | |
| R3-U14b | Copy, then Paste into an empty field. | Pill "Copied"; Paste fills the field. | | |
| R3-U14c | Clear. | Field empties, pill "Cleared" with Undo; Undo brings the number back. | | |
| R3-U16 | Number or Contact sheet, Export calls. | The document picker saves a CSV; pill "Exported n calls" or "No calls to export". | | |
| R3-U20 | Missed call row menu, Send a quick reply, choose a text. | The messages app opens with the text. | | |
| R3-U21 | Recents search for a word from a note. | Calls whose note contains it are listed. | | |
| R3-U22a | Settings, Speed dial, tap Share (visible only with assignments). | The share sheet opens with "Speed dial" and one line per key. | | |
| R3-U22b | Tap Clear all. | Sheet "Clear speed dial?" with the count; Cancel changes nothing. | | |
| R3-U22c | Confirm Clear all. | Keys 2-9 are free, pill "Speed dial cleared" with Undo; Undo restores every entry. | | |
| R3-U24 | Recents, Delete all, confirm. | Pill with Undo; Undo restores the whole log. | | |

## Round 4 utilities (U15, U26-U28, U30-U34)

| ID | Steps | Expected | Result | Notes |
|---|---|---|---|---|
| R4-U15 | Recents with calls on several days, scroll down slowly. | A small capsule under the filter row shows Today, Yesterday, a weekday or a date, and is pushed up and swapped at each day change. Hidden at the top, in select mode and with an empty list. Touches pass through to the rows. | | |
| R4-U26 | Open a call's details sheet, tap Share. | The share sheet opens with "Call with <name>" and the details (and note). Copy still says "Details copied". | | |
| R4-U27a | Settings, Blocked numbers, tap Share (hidden when the list is empty). | The share sheet opens with "Blocked numbers" and one line per number. | | |
| R4-U27b | Tap Unblock all, then Cancel. | A sheet states the count; Cancel changes nothing. | | |
| R4-U27c | Unblock all, confirm. | The list empties, pill "Unblocked n numbers" with Undo; Undo blocks them all again with a pill. | | |
| R4-U28 | Settings, Call statistics, pick a range, Export CSV. | The document picker saves `rovsti-call-stats-<range>-<date>.csv`; pill "Exported n people", or "No calls in this range" for an empty range. | | |
| R4-U30a | Settings, Call notes. | All notes are listed newest first with the person, date and preview; the empty state reads "No call notes yet. Add one from a call's details." | | |
| R4-U30b | Search by a word, a name and digits. | The list filters; a tap opens that call's details sheet. | | |
| R4-U30c | Tap a row's delete glyph. | Pill "Note deleted" with Undo; Undo restores it. | | |
| R4-U31 | Empty keypad, tap Call with an earlier outgoing call. | The last dialed number fills the field with the status "Tap Call again to redial"; no call yet. A second tap calls. With no outgoing call it says "No recent calls to redial". | | |
| R4-U32 | Copy text with two numbers ("Office 0212 555 01 01 or mobile 0532 111 22 33"), long-press the field, Paste. | A "Which number?" sheet lists both; choosing one fills the field. A text with one number pastes straight in. | | |
| R4-U33 | On the Call notes page tap Share all (hidden when empty). | The share sheet opens with every note, newest first, separated by blank lines. | | |
| R4-U34 | Blocked numbers, paste several numbers into the add field, Block. | All are blocked; pill "Blocked n numbers" (with "already blocked" count if any) with Undo that unblocks them. Emergency numbers are skipped; an emergency-only paste is refused inline. | | |
| R4-fb | Run every utility above on a desktop preview. | Android-only ones show "Not available on this platform" rather than staying silent. | | |

## Round 5 utilities (U35-U43)

| ID | Steps | Expected | Result | Notes |
|---|---|---|---|---|
| R5-U35 | Recents, tap the Notes chip (sixth chip; check it fits at 360 dp). | Only calls with a note are listed; search still works inside it; with none the list says "No calls with notes". | | |
| R5-U36a | Open a contact with 2+ numbers, tap Copy, then "All numbers". | Pill "Copied 3 numbers" (count matches); the clipboard holds one "Label: number" line per number. | | |
| R5-U36b | Same, tap Share, then "All numbers". | Share sheet with the name and every number. The row is absent for a one-number contact and for other actions. | | |
| R5-U37a | End a connected outgoing call of 4:32. | One pill "Call with <name>, 4:32" with "Add note"; tapping it opens Call details with the note field editing. | | |
| R5-U37b | End a short incoming call, and a call that already has a note. | A short incoming call shows the Block offer only; a call with a note shows no pill. | | |
| R5-U38a | Settings, Blocked numbers, tap Export (hidden when empty). | The picker saves `rovsti-blocked-numbers-<date>.csv`; pill "Blocked numbers exported". | | |
| R5-U38b | Tap Unblock all, confirm. | List empty, pill with Undo. | | |
| R5-U38c | Tap Import and pick the exported file. | Every number is blocked again; pill "Blocked n numbers" with Undo; Undo unblocks them. Importing again says "No new numbers to block"; emergency numbers are skipped. | | |
| R5-U40 | Settings, Call statistics, Share. | Share sheet with "Call statistics, <range>", totals and up to 10 people. An empty range says "No calls in this range". | | |
| R5-U41 | Export call history, a person's calls, statistics, blocked numbers, notes; cancel one. | Each pill names what was saved: "Call history exported", "Call statistics exported", "Blocked numbers exported", "Call notes exported"; cancel says "Export cancelled". | | |
| R5-U42 | Settings, Call notes, Export (hidden when empty). | `rovsti-call-notes-<date>.csv` with columns date,time,name,number,note, newest first. | | |
| R5-U43a | Settings, Data, Delete older calls. | A chooser lists 30 days / 90 days / 1 year, each with the count; a row with none is dimmed. | | |
| R5-U43b | Pick a row, confirm. | Pill "n calls deleted" with Undo; Undo restores them. Cancel changes nothing. | | |
| R5-fb | Run U36-U43 on a desktop preview. | Android-only ones show "Not available on this platform" rather than staying silent. | | |

## In-call note and copy number

- On a connected or held call with a number, tap "Note" under the status line, type, press Done: the pill says "Note saved" and the capsule shows a check.
- A call that lasts longer than the 2 minute match window after ringing still gets its note in the call log, because the note is keyed by the time the call rang or was placed, which is the log's `DATE`.
- Hold the caller's name or number for about half a second: "Number copied". A private caller shows "No number to copy".

## Round 6 utilities (U44-U50)

| ID | Steps | Expected | Result | Notes |
|---|---|---|---|---|
| R6-U44 | Open Call details of a missed call, tap "Remind me", pick a time. Open it again. | A reminder is set with a pill; the button now reads "Cancel reminder"; tapping it cancels with an Undo pill. Hidden for private numbers. | | |
| R6-U45a | Recents, select three rows, tap Block, confirm. | Confirm says "Block 3 numbers?"; Yes leaves select mode and shows "Blocked 3 numbers" with Undo; Undo unblocks them. Cancel changes nothing. | | |
| R6-U45b | Select rows, tap Share. | Share sheet lists one "Name: number" line per number (private and repeated rows left out). Select mode stays on. | | |
| R6-U46 | Settings, Call statistics, tap a bar (a day, or a week on the 8-week range). | A sheet titled with the day or week lists that bucket's calls and a summary; tapping a call opens its Number sheet. Bars without calls do nothing. | | |
| R6-U48 | Receive or view a call from an unsaved foreign number (for example +49). | "Germany" shows under the number in Recents, the Number sheet, Call details and the Incoming screen. A home-country number and a saved contact show no country. | | |
| R6-U49 | Copy "PIN 4821#" in Messages, join a call, open the in-call keypad. Tap "Paste 4821#", then try again and tap Stop mid-way. | Tones play and the digit line fills; the pill says "Sent 5 tones". Stop halts the tones and shows "Stopped sending tones". The button is absent on WhatsApp calls and with no digits on the clipboard. | | |
| R6-U50 | Hold the digit line on the in-call keypad. | "Copied tones" and the clipboard holds the digits. Nothing happens when empty. | | |
| R6-fb | Run U44-U50 on a desktop preview. | Android-only actions show "Not available on this platform". | | |
