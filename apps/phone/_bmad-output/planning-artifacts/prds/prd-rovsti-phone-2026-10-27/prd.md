---
title: "PRD: Rovsti Phone"
status: final
created: 2026-10-27
updated: 2026-10-27
---

# PRD: Rovsti Phone

## 0. Document Purpose

This PRD defines the MVP of Rovsti Phone (`apps/phone`), the Android default dialer in the Rovsti monorepo. Its readers are the owner and the downstream BMAD steps (UX, architecture, epics and stories, and one automated implementation run). It builds on the product brief at `../../briefs/brief-rovsti-phone-2026-10-27/` (`brief.md`, `addendum.md`) and does not repeat the brief's market framing.

How the document is organized: §3 Glossary fixes the vocabulary. §4 groups features, each with globally numbered FRs (FR-1 to FR-40) and testable consequences. §5 holds cross-cutting NFRs (NFR-1 to NFR-13). §6.1 sets P0/P1/P2 build priority. Inferences are tagged `[ASSUMPTION]` inline and indexed in §11. Technical "how" (Android components, bridge options, crate placement, toolchain) is kept in `addendum.md` for architecture.

## 1. Vision

Rovsti Phone replaces the stock phone app on the owner's Xiaomi phone. It places and answers calls, shows the in-call screen, and holds the keypad, call history, contacts and favorites, all in an iOS-style liquid-glass interface with spring motion. It runs only on the phone. The code is open. There is no network code path, no account, no analytics and no sync. Contacts and call history stay in Android's own providers.

A dialer that misses calls is worse than no dialer. So reliability on Xiaomi (MIUI / HyperOS), whose power management silently breaks third-party dialers, is a core feature and not a settings footnote. The app leads the owner through the OEM setup steps and re-checks them on every launch.

The MVP is narrow on purpose: a debug APK that the owner sideloads, sets as the default phone app, and uses for every real call for a week. It is also the first app to exercise the whole shared stack (Android role, Kotlin system service, JNI bridge, content providers, a GPUI screen over the lock screen), so Rovsti Messages and Rovsti Stops can reuse what it proves.

## 2. Target User

### 2.1 Jobs To Be Done

- **Functional:** make and receive every phone call, find a person and call them in two taps or fewer, and see who called while I was away.
- **Emotional:** trust that the phone will ring. Enjoy a dialer that looks and moves the way I want.
- **Contextual:** on a Xiaomi phone, where the OS may kill or block a third-party dialer without telling me.
- **Builder:** prove the Rovsti Android stack (role, `InCallService`, bridge, providers, lock-screen UI) on real hardware.

### 2.2 Non-Users (v1)

- Users who install from the Play Store, or who need signed releases or localized UI.
- Dual-SIM power users who switch SIMs per call.
- Anyone who needs conference calls, call recording, or caller-ID / spam lookup services.

### 2.3 Key User Journeys

The single persona is **Erdem**, the developer and owner, with one Xiaomi phone on HyperOS / MIUI and Android 14+ `[ASSUMPTION]`.

- **UJ-1. Erdem installs Rovsti Phone and makes it his dialer.**
  - **Entry state:** Debug APK has just been sideloaded with `adb install`. First launch.
  - **Path:** The app opens on the Keypad tab with the Setup Checklist on top. He taps "Set as default phone app" and accepts the system dialog. If MIUI hides that dialog, the item explains the Settings path and opens Default apps. He grants phone, call log, contacts and notification permissions. On Xiaomi, he opens Autostart, Battery saver and the lock-screen pop-up page from the checklist and enables each one.
  - **Climax:** Every checklist item shows done and the checklist collapses.
  - **Resolution:** Rovsti Phone is the default phone app. The stock dialer is no longer used for calls.
  - **Edge case:** After a HyperOS update revokes Autostart, the next launch shows the checklist again with only that item open.

- **UJ-2. Erdem calls his sister from Favorites.**
  - **Entry state:** Phone unlocked, app in the background.
  - **Path:** Opens the app, taps the Favorites tab, taps her tile.
  - **Climax:** The In-Call Screen appears with her name, "Calling…", then the Call Timer once she answers. He turns on speaker, later mutes, then taps End.
  - **Resolution:** He is back on Favorites. Recents shows the outgoing call at the top.

- **UJ-3. A call comes in while the phone is locked in his pocket.**
  - **Entry state:** Screen off, phone locked, Setup Checklist complete.
  - **Path:** The phone rings and the screen turns on with the Incoming Call Screen over the lock screen: caller name (or number), Answer and Decline.
  - **Climax:** He taps Answer. The In-Call Screen takes over and the Call Timer starts, still without unlocking.
  - **Resolution:** When the call ends, the screen returns to the lock screen.
  - **Edge case:** If he is using another app with the phone unlocked, a heads-up notification with Answer / Decline appears instead of a full-screen takeover.

- **UJ-4. Erdem calls back a missed number.**
  - **Entry state:** The Recents tab shows a Missed Call Badge with "2".
  - **Path:** Opens Recents. The two missed calls from the same number show as one red row with "(2)". He taps the row.
  - **Climax:** The call is placed and the In-Call Screen shows.
  - **Resolution:** The badge clears once Recents has been viewed.

- **UJ-5. Erdem looks someone up and stars them.**
  - **Path:** Contacts tab → types "ays" into search → "Ayşe" matches → opens her Contact Sheet → taps the star → taps her mobile number to call.
  - **Resolution:** Ayşe now appears in Favorites, and in the system Contacts app as starred.

- **UJ-6. Erdem dials a number and uses the in-call keypad.**
  - **Path:** Keypad tab → types or pastes a support line number → Call → during the call opens the In-Call Keypad and presses "1", then "#".
  - **Climax:** The remote IVR reacts to the DTMF Tones.

## 3. Glossary

- **Default Phone App** — the app holding Android's `ROLE_DIALER`. Only the Default Phone App gets the Telecom in-call binding.
- **Setup Checklist** — the in-app list of Setup Items that must be satisfied for calls to work reliably.
- **Setup Item** — one checkable requirement: Default Phone App role, a runtime permission group, the full-screen notification permission, or a Xiaomi Setting. Each item is *done*, *not done*, or *unverifiable*. Only Xiaomi Settings can be *unverifiable*: they cannot be read back and stay *unverifiable* until the owner confirms them, after which they are *done*.
- **Xiaomi Setting** — one of four OEM settings that cannot be read back: Autostart, Battery saver "No restrictions", "Show on lock screen / pop-up while in background", and Floating notifications. Shown only when the device is Xiaomi.
- **Call** — one Telecom call session, outgoing or incoming, from creation to disconnection.
- **Call State** — one of `Dialing`, `Ringing`, `Active`, `Holding`, `Disconnected`, plus the Mute and Audio Route flags. (Android's transient "select phone account" state is resolved automatically per FR-11 and is never shown.)
- **Audio Route** — earpiece or speaker in MVP. Wired and Bluetooth headsets are used automatically by Android.
- **In-Call Screen** — the GPUI screen shown while a Call is `Dialing`, `Active` or `Holding`. It shows one Call at a time, plus a Waiting Call banner when one exists.
- **Incoming Call Screen** — the full-screen answer/decline UI for a `Ringing` Call, shown over the lock screen.
- **Incoming Call Notification** — the heads-up notification with Answer and Decline, shown when the phone is unlocked and in use.
- **Waiting Call** — a second `Ringing` Call that arrives while another Call is `Active`.
- **Call Timer** — elapsed time since the Call became `Active`, as `m:ss` or `h:mm:ss`.
- **In-Call Keypad** — the keypad opened on the In-Call Screen to send DTMF Tones.
- **DTMF Tone** — a touch tone sent to the remote party for a digit, `*` or `#`.
- **Recents** — the tab showing Call Log Entries grouped into Recents Rows.
- **Call Log Entry** — one row of Android's `CallLog.Calls` provider. Its type is outgoing, incoming, missed, rejected (declined) or blocked. Blocked entries are not shown in MVP.
- **Recents Row** — one or more consecutive Call Log Entries with the same normalized number and the same type, shown as one row with a count.
- **Missed Call Badge** — the count on the Recents tab icon of missed Call Log Entries not yet seen in Rovsti Phone.
- **Contact** — one aggregated person from Android's `ContactsContract`, with a display name, zero or more numbers and a Starred flag.
- **Contact Sheet** — the glass sheet with a Contact's name, numbers, call actions and the star toggle.
- **Starred** — Android's `STARRED` flag on a Contact. It is the only source of truth for Favorites.
- **Favorites** — the tab showing every Starred Contact that has at least one number.
- **Tab Bar** — the floating, icon-only Lucide glass tab bar with Favorites, Recents, Contacts, Keypad.

## 4. Features

### 4.1 Default Phone App and Android host

**Description:** The app ships an ejected Android host with the components Android requires before it grants `ROLE_DIALER`, plus the bridge and an `Application` subclass. Without the role, the app still opens in a read-only preview: lists work if permissions are granted, and placing a call hands off to the system. Realizes UJ-1.

**Functional Requirements:**

#### FR-1: Role eligibility

The installed APK is eligible for `ROLE_DIALER`. Realizes UJ-1.

**Consequences (testable):**
- The system "Default phone app" picker on the device lists Rovsti Phone.
- The manifest declares an `InCallService` with the in-call UI metadata and bind permission, and an activity that handles `ACTION_DIAL` both with and without a `tel:` URI.

#### FR-2: Request the role

The owner can request the Default Phone App role from the Setup Checklist. Realizes UJ-1.

**Consequences (testable):**
- Tapping the item opens the system role dialog. Accepting it marks the item done without restarting the app.
- If the dialog cannot be shown (request returns false), the item shows the Settings path (Settings → Apps → Manage apps → Default apps → Phone) and a button that opens the system default-apps screen.

#### FR-3: Handle dial intents

Other apps and links can open Rovsti Phone with a number pre-filled. Realizes UJ-6.

**Consequences (testable):**
- `ACTION_DIAL` with `tel:+905551112233` opens the Keypad tab with that number entered and does not place the call.
- `ACTION_DIAL` without data opens the Keypad tab empty.

#### FR-4: Preview mode without the role

When the role is not held, the app runs in preview mode. Realizes UJ-1.

**Consequences (testable):**
- A visible banner states that Rovsti Phone is not the default phone app.
- Placing a call still works through the system path (current `telecom::place_call` behavior). The system in-call UI is acceptable in this mode.

### 4.2 Setup Checklist

**Description:** One glass card at the top of the app lists every Setup Item. It is re-evaluated on every launch and every resume, because HyperOS can revoke Autostart after an update. Xiaomi Settings cannot be read back, so they are marked done when the owner confirms them, and are reset when the app version changes `[ASSUMPTION]`. Realizes UJ-1.

**Functional Requirements:**

#### FR-5: Checklist contents

The owner can see the Setup Checklist with these Setup Items in order: Default Phone App; Phone and call log permissions; Contacts permission; Notifications permission; Full-screen notifications (Android 14+); and, on Xiaomi only, Autostart, Battery saver "No restrictions", Show on lock screen / pop-up, Floating notifications. Realizes UJ-1.

**Consequences (testable):**
- On a non-Xiaomi device (or desktop), the four Xiaomi Settings are absent.
- Accepting the Default Phone App role usually pre-grants the phone, call log and contacts permissions, so those items may turn done right after FR-2 without a prompt. Tests must not assume a prompt appears.
- The full-screen notification item is read back (Android's "can use full-screen intent" check) and, when not done, opens the system page that grants it.
- Each item shows its state (done, not done, unverifiable) and a one-line reason why it matters.

#### FR-6: Act on a Setup Item

The owner can act on each not-done Setup Item from the checklist. Realizes UJ-1.

**Consequences (testable):**
- Permission items trigger the Android runtime permission request for their group.
- Xiaomi Settings open their deep link, falling back to the app's system details page when the component is missing.
- Battery saver also offers the AOSP "ignore battery optimizations" dialog. Its result can be read back and marks the AOSP part done.

#### FR-7: Re-check on launch and resume

The app re-evaluates every readable Setup Item on launch and on resume. Realizes UJ-1.

**Consequences (testable):**
- Revoking a permission in system settings and returning to the app shows that item as not done.
- When every item is done (confirmed for Xiaomi Settings), the checklist collapses into a small "Setup complete" row that can be expanded again.

#### FR-8: Confirm Xiaomi Settings

The owner can mark a Xiaomi Setting as done after visiting it. Realizes UJ-1.

**Consequences (testable):**
- The confirmation persists across restarts in the app's local data directory.
- After the installed app version changes, Xiaomi confirmations are cleared and the items show as not done again `[ASSUMPTION]`.

### 4.3 Keypad and outgoing calls

**Description:** The Keypad tab mostly exists today: spring-animated keys with digit pop, a delete control, an invalid-number shake, paste, and a Call button. The MVP keeps this and routes every outgoing call (from Keypad, Recents, Contacts, Favorites) through one call path. Realizes UJ-2, UJ-4, UJ-6.

**Functional Requirements:**

#### FR-9: Enter a number

The owner can enter a dial string with the keypad (`0-9`, `*`, `#`, long-press `0` for `+`) or paste it. Realizes UJ-6.

**Consequences (testable):**
- Pasting "+90 (555) 111-22-33" yields a number that `PhoneNumber::parse` accepts.
- Pasted pause and wait characters (`,` and `;`) are kept.
- Delete removes one character; long-press delete clears the field.

#### FR-10: Validate before calling

The app rejects only dial strings that cannot be dialed at all. Realizes UJ-6.

**Consequences (testable):**
- Tapping Call with an empty field, or with a string that has no digit, `*` or `#`, plays the shake animation and places no call.
- Service codes such as `*#06#` or a balance code like `*123#` are not rejected; they are passed to Telecom as entered. `PhoneNumber::parse` is used for contact matching and display, not as a gate on these codes.
- Emergency numbers (for example 112) are never blocked by app validation or by a missing Setup Item; they are handed to Telecom, which routes them.

#### FR-11: Place a call

The owner can place a call from Keypad, a Recents Row, a Contact Sheet number, or a Favorites tile. Realizes UJ-2, UJ-4, UJ-5, UJ-6.

**Consequences (testable):**
- With the role held, the Call goes through Telecom on the system default SIM and the In-Call Screen appears.
- If Android asks the dialer to choose a SIM (for example a dual-SIM phone set to "Ask every time"), the app selects the default outgoing account, or else the first suggested account, so the Call never stays stuck before `Dialing` `[ASSUMPTION]`. A real SIM picker stays out of scope.
- If `CALL_PHONE` is missing, the app requests it and places the call after it is granted.

#### FR-12: Match the entered number to a Contact

While a number is being entered, the Keypad shows the matching Contact's name when one exists. Realizes UJ-6.

**Consequences (testable):**
- Typing a number stored as "+905551112233" in a Contact, written as "05551112233", shows that Contact's name (using `PhoneNumber::matches`).

**Out of Scope:**
- T9 name search on the keypad (deferred).

### 4.4 In-Call Screen

**Description:** While a Call is `Dialing`, `Active` or `Holding`, the In-Call Screen shows the caller and the controls. Call State comes from Android's `InCallService` and is pushed to the GPUI view. The screen uses glass round controls with spring press feedback. Proximity turns the display off when the phone is at the ear `[ASSUMPTION]`. Realizes UJ-2, UJ-3, UJ-6.

**Functional Requirements:**

#### FR-13: Show the active Call

The In-Call Screen shows the remote party and Call State. Realizes UJ-2, UJ-3.

**Consequences (testable):**
- Shows the Contact name when the number matches a Contact, otherwise the formatted number. A hidden number shows "Unknown".
- Shows "Calling…" in `Dialing`, the Call Timer in `Active`, and "On hold" in `Holding`.
- The Call Timer updates at least once per second and survives the app moving to background and back.

#### FR-14: Mute

The owner can toggle Mute. Realizes UJ-2.

**Consequences (testable):**
- The Mute control reflects the actual mute state reported by Android, including changes made elsewhere (for example a headset button).

#### FR-15: Speaker

The owner can switch the Audio Route between earpiece and speaker. Realizes UJ-2.

**Consequences (testable):**
- The Speaker control reflects the actual route reported by Android.
- When a wired or Bluetooth headset is connected, the control shows the headset route and the owner can still choose speaker. A full route picker is out of scope.

#### FR-16: In-Call Keypad and DTMF

The owner can open the In-Call Keypad and send DTMF Tones. Realizes UJ-6.

**Consequences (testable):**
- Each press sends exactly one DTMF Tone for that key, and the pressed digits appear above the keys.
- Closing the In-Call Keypad returns to the main controls without affecting the Call.

#### FR-17: Hold

The owner can put the Call on hold and resume it. Realizes UJ-2.

**Consequences (testable):**
- Hold is shown only when Android reports the Call supports hold. Its state follows the Call State.

#### FR-18: End

The owner can end the Call. Realizes UJ-2, UJ-3.

**Consequences (testable):**
- Tapping End disconnects the Call. The screen shows "Call ended" for about one second, then returns to the previous tab (or the lock screen if the phone was locked).
- A Call ended by the remote party behaves the same way.

#### FR-19: Return to the Call

The owner can leave the In-Call Screen during a Call and return to it. Realizes UJ-2.

**Consequences (testable):**
- An ongoing-call notification (with End) stays visible while a Call exists. Tapping it opens the In-Call Screen.
- Opening Rovsti Phone during a Call shows the In-Call Screen, or a glass "Return to call" pill on top of the tabs `[ASSUMPTION]`.

#### FR-20: Waiting Call

While one Call is `Active`, the owner can answer or decline a Waiting Call. Realizes UJ-3.

**Consequences (testable):**
- The In-Call Screen shows the Waiting Call's caller with Answer and Decline.
- Decline rejects the Waiting Call and leaves the first Call `Active`.
- Answer ends the first Call and then answers the Waiting Call, so there is only ever one live Call to manage `[ASSUMPTION]`. This keeps the brief's "answer/decline only" scope.

**Out of Scope:**
- Answer-and-hold, swap, merge and conference controls.

### 4.5 Incoming calls

**Description:** An incoming Call must ring and be answerable whether the phone is locked, unlocked and idle, or in use, including after a cold process start. When the phone is locked or the screen is off, the Incoming Call Screen appears full-screen over the lock screen and turns the screen on. When the phone is unlocked and in use, an Incoming Call Notification appears instead. Realizes UJ-3.

**Product decision (deviation from the brief, recorded):** the brief puts the incoming UI in GPUI. This PRD keeps that as the target, but ringing reliably comes first and glass second. If the GPUI surface cannot appear within the NFR-2 budget, a minimal native answer/decline screen is an acceptable fallback, as long as the In-Call Screen after answering is the GPUI one. The architecture step decides which surface is used (see §10 Q1).

**Product decision:** Android's Telecom plays the ringtone and vibration, not the app. This respects Do Not Disturb, per-contact ringtones and silent mode for free, and removes a subsystem from the run.

**Functional Requirements:**

#### FR-21: Ring

An incoming Call rings and vibrates through the system ringer according to the phone's ringer mode, Do Not Disturb and the Contact's ringtone. Realizes UJ-3.

**Consequences (testable):**
- In silent mode there is no sound or vibration, but the Incoming Call Screen or Notification still appears.
- Ringing stops on answer, decline, remote hang-up, or a volume key press (system silence behavior).

#### FR-22: Incoming Call Screen over the lock screen

When the phone is locked or the screen is off, the Incoming Call Screen appears over the lock screen and turns the screen on. Realizes UJ-3.

**Consequences (testable):**
- It shows the caller (Contact name or number) and Answer and Decline controls, and works without unlocking.
- It appears when the app process was not running before the call (cold start).

#### FR-23: Incoming Call Notification when unlocked

When the phone is unlocked and in use, an Incoming Call Notification with Answer and Decline appears. Realizes UJ-3.

**Consequences (testable):**
- Answer from the notification answers the Call and opens the In-Call Screen.
- Decline from the notification rejects the Call without opening the app.
- Tapping the notification body opens the full Incoming Call Screen.

#### FR-24: Answer and decline

The owner can answer or decline from the Incoming Call Screen. Realizes UJ-3.

**Consequences (testable):**
- Answer moves the Call to `Active` and shows the In-Call Screen.
- Decline rejects the Call and dismisses the screen. On the lock screen, the lock screen is shown again.

#### FR-25: Missed calls

A Call that rings out without being answered is recorded as missed and is visible to the owner. Realizes UJ-4.

**Consequences (testable):**
- The Missed Call Badge increases by one.
- MVP does not post its own missed-call notification and does not declare a handler for Android's "show missed calls notification" broadcast; the system Telecom notification is used where the device shows one (see §10 Q4).
- Viewing Recents asks Telecom to cancel the missed-call notification.

### 4.6 Recents

**Description:** Recents reads `CallLog.Calls` and shows Recents Rows, newest first: name or number, a direction icon (outgoing, incoming, missed), count when grouped, and relative time. Missed rows are red. Realizes UJ-2, UJ-4.

**Functional Requirements:**

#### FR-26: Show grouped Recents

The owner can see Call Log Entries grouped into Recents Rows. Realizes UJ-4.

**Consequences (testable):**
- Consecutive entries with the same normalized number and the same type collapse into one row with a count "(n)". Grouping is a pure `rovsti_core` function with unit tests.
- At most the 500 most recent Call Log Entries are loaded `[ASSUMPTION]` (the brief said "default call-log window"; the cap bounds load time for NFR-4).
- Missed rows are red; rejected rows show a declined icon; blocked entries are hidden.
- With `READ_CALL_LOG` missing, the tab shows an empty state with a "Grant access" button.

#### FR-27: Missed Call Badge

The Recents tab icon shows the Missed Call Badge. Realizes UJ-4.

**Consequences (testable):**
- The badge counts missed entries newer than the last time Recents was viewed. Viewing Recents clears it, and Android's "new" flag on those entries is cleared too `[ASSUMPTION]`.

#### FR-28: Call back

The owner can tap a Recents Row to call that number. Realizes UJ-4.

**Consequences (testable):**
- Tapping the row places a call to that number (FR-11). Tapping the row's info control opens the Contact Sheet if the number matches a Contact, otherwise a number sheet with Call and "Add to contacts" (handed off to the system contacts app).

#### FR-29: Live refresh

Recents updates when the call log changes while the tab is open. Realizes UJ-2.

**Consequences (testable):**
- After a Call ends, its entry appears in Recents within 2 seconds without manual refresh.

### 4.7 Contacts

**Description:** Contacts reads `ContactsContract` and shows Contacts with at least one phone number, alphabetically with section letters, and a glass search field. Contacts are read-only apart from the Starred flag. Realizes UJ-5.

**Functional Requirements:**

#### FR-30: List Contacts

The owner can browse Contacts alphabetically. Realizes UJ-5.

**Consequences (testable):**
- Only Contacts with at least one phone number are listed. Sorting follows the provider's display name order, with section headers per first letter.
- With `READ_CONTACTS` missing, the tab shows an empty state with a "Grant access" button.

#### FR-31: Search Contacts

The owner can search by name or number. Realizes UJ-5.

**Consequences (testable):**
- Name matching ignores case and diacritics: "ays" matches "Ayşe", "ozgur" matches "Özgür".
- Number matching ignores formatting: "5551112" matches "+90 555 111 22 33".
- Search is a pure `rovsti_core` function with unit tests. Results update on every keystroke.

#### FR-32: Contact Sheet

The owner can open a Contact Sheet showing the name, every number with its label, a call action per number, and the star toggle. Realizes UJ-5.

**Consequences (testable):**
- Tapping a number places a call to it (FR-11).
- An "Edit in Contacts" action hands off to the system contacts app (`ACTION_EDIT`).

#### FR-33: Star and unstar

The owner can star or unstar a Contact from its Contact Sheet. Realizes UJ-5.

**Consequences (testable):**
- The change is written to the Contact's `STARRED` flag, is visible in the system Contacts app, and appears in Favorites immediately.
- This is the only write to the contacts provider in MVP.

### 4.8 Favorites

**Description:** Favorites shows every Starred Contact with at least one number as a grid of glass tiles with initials (or the contact photo `[ASSUMPTION]`) and name. Realizes UJ-2.

**Functional Requirements:**

#### FR-34: Show Favorites

The owner can see Starred Contacts as a grid. Realizes UJ-2.

**Consequences (testable):**
- The set equals the Contacts with `STARRED = 1` and a number. A Contact starred in another app appears the next time the tab is opened.
- With no Starred Contacts, an empty state explains how to star someone from Contacts.

#### FR-35: Call a Favorite

The owner can call a Favorite with one tap. Realizes UJ-2.

**Consequences (testable):**
- Tapping a tile calls the Contact's default number (super-primary). With no default, a Contact with one number is called directly, and one with several numbers opens a small chooser `[ASSUMPTION]`.
- Long-press opens the Contact Sheet.

### 4.9 App shell and visual design

**Description:** One GPUI window with the floating icon-only Lucide Tab Bar (Favorites, Recents, Contacts, Keypad), following `.claude/skills/liquid-glass-gpui`: refractive glass surfaces, interruptible springs, and a theme that follows the system. The app icon is `apps/phone/assets/app.png`.

**Functional Requirements:**

#### FR-36: Tab navigation

The owner can switch between the four tabs with the Tab Bar. Realizes UJ-2, UJ-4, UJ-5.

**Consequences (testable):**
- The puck moves with a spring and can be interrupted by a second tap mid-animation.
- The app remembers the last tab across launches. First launch opens Keypad.

#### FR-37: Theme

The app follows the system light/dark theme. Realizes UJ-1.

**Consequences (testable):**
- Switching the system theme while the app is open updates every screen, including the In-Call Screen, without restart.

#### FR-38: Desktop preview

Every screen can be previewed on desktop Linux with `cargo run -p rovsti_phone`. Realizes the builder job.

**Consequences (testable):**
- Android-only actions show "not available on this platform" instead of panicking.
- Recents, Contacts, Favorites and the In-Call Screen can be shown with sample data on desktop `[ASSUMPTION]`.

### 4.10 Build and delivery

**Description:** The deliverable is a debug APK built with GPUiForge and sideloaded with `adb`.

**Functional Requirements:**

#### FR-39: Build the debug APK

The project produces a debug APK with `gpuiforge build android` from `apps/phone`.

**Consequences (testable):**
- The APK exists at `target/gpuiforge/android/app/build/outputs/apk/debug/app-debug.apk` and contains `arm64-v8a`.
- The app id is `dev.rovsti.phone` and the launcher icon comes from `apps/phone/assets/app.png`.

#### FR-40: Install and launch

The APK installs with `adb install` on the owner's phone and launches to the Keypad tab with the Setup Checklist.

**Consequences (testable):**
- `adb logcat -s GPUI` shows the renderer initializing (Vulkan or the GLES fallback) with no crash on launch.

## 5. Cross-Cutting NFRs

- **NFR-1 Privacy / locality.** The app has no network code path. The manifest does not request `INTERNET` `[ASSUMPTION: GPUiForge's generated host does not add it by default; if it does, it is removed in the ejected host]`. No analytics, crash upload, or sync. The only on-device data the app writes itself is preferences and Xiaomi confirmations in its no-backup data directory.
- **NFR-2 Incoming call latency.** From Telecom reporting a `Ringing` Call to the Incoming Call Screen or Notification being visible: under 1.5 s with the process warm, under 3 s from a cold start, on the owner's phone `[ASSUMPTION]`. The ringtone starts within 1 s in both cases.
- **NFR-3 Call control latency.** Mute, speaker, hold, DTMF and End take effect within 300 ms of the tap, and the control state reflects Android's reported state within 500 ms.
- **NFR-4 List load time.** Recents, Contacts and Favorites show data within 1 s of opening the tab, for about 500 Contacts and 500 Call Log Entries. Provider queries never block the UI thread.
- **NFR-5 Smoothness.** Scrolling lists and spring animations hold 60 fps on the owner's phone in normal use.
- **NFR-6 Reliability.** No crash during a Call. If the GPUI view fails while a Call exists, the Call itself continues (Telecom owns it) and can still be ended from the ongoing-call notification.
- **NFR-7 Platform rules.** Android logic lives in Kotlin behind `rovsti_android`. Every `rovsti_android` function compiles on desktop and returns `Error::Unsupported` there. GPUI and JNI code stay thin.
- **NFR-8 Testability.** Call-log grouping, Contact search, number matching and the Call State model live in `rovsti_core` with unit tests. Workspace checks pass: `cargo fmt --all`, `cargo clippy --workspace --all-targets`, `cargo test --workspace`, `cargo check -p rovsti_android --target aarch64-linux-android`.
- **NFR-9 Device support.** Android 14+ on the owner's Xiaomi phone (HyperOS 1/2 or MIUI 14) `[ASSUMPTION]`. Renders through Vulkan with the GLES fallback.
- **NFR-10 Accessibility.** Every control has an accessible label (exposed through gpui_android's AccessKit) and a touch target of at least 44 × 44 pt. Text respects the system font scale up to 1.3× without clipping the in-call controls `[ASSUMPTION]`.
- **NFR-11 Single-run buildability.** The MVP scope is sized so one automated implementation run can deliver it, in the priority order of §6.1. Anything that would expand it goes to §6.2.
- **NFR-12 Glass fidelity.** Glass surfaces (Tab Bar, Setup Checklist card, Contact Sheet, in-call controls) use `liquid_glass_kit`'s backdrop refraction material on Android, not a flat blurred rectangle, falling back only where the kit itself falls back. Motion uses the kit's interruptible springs.
- **NFR-13 Shared foundation.** New Android plumbing (bridge events, permission checks, Setup Checklist, Xiaomi Settings) goes in `rovsti_android` / `rovsti_ui` in a form that Rovsti Messages and Stops can reuse, not inside `apps/phone`. Phone-specific call logic stays in `rovsti_core` and `apps/phone`.

## 6. MVP Scope

### 6.1 In Scope

- Ejected Android host with `RovstiBridge`, an `Application` subclass, `InCallService`, `ACTION_DIAL` / `tel:` activity, and full-screen incoming-call support (FR-1 to FR-4).
- Setup Checklist including Xiaomi Settings, re-checked on launch and resume (FR-5 to FR-8).
- Keypad with outgoing calls from every tab (FR-9 to FR-12).
- In-Call Screen: caller, timer, mute, speaker, In-Call Keypad, hold, end, Waiting Call answer/decline (FR-13 to FR-20).
- Incoming calls: ring, lock-screen Incoming Call Screen, Incoming Call Notification, missed calls (FR-21 to FR-25).
- Recents, Contacts (read-only plus star), Favorites (FR-26 to FR-35).
- Glass app shell, system theme, desktop preview (FR-36 to FR-38).
- Debug APK built and sideloaded (FR-39, FR-40).

**Priority and cut line.** All of the above is MVP. If the run cannot finish everything, it builds in this order and cuts from the bottom:

- **P0 (a dialer that never misses a call):** FR-1 to FR-4, FR-9 to FR-11, FR-13 to FR-16, FR-18, FR-19, FR-21 to FR-26, FR-28, FR-39, FR-40.
- **P1 (daily-driver completeness):** FR-5 to FR-8, FR-17, FR-20, FR-30 to FR-35, FR-36, FR-37.
- **P2 (polish):** FR-12, FR-27, FR-29, FR-38 sample data, contact photos on Favorites, remembering the last tab.

### 6.2 Out of Scope for MVP

- Dual-SIM picker and per-SIM badges. MVP uses the system default SIM `[ASSUMPTION: one active SIM or the default is acceptable]`.
- Blocklist (`BlockedNumberContract`).
- Swap, merge and conference calls.
- T9 search, speed dial, long-press `1` for voicemail.
- Bluetooth / audio route picker beyond earpiece/speaker.
- Contact creation and editing (handed off to the system contacts app), call notes, call recording.
- Deleting Call Log Entries. `[NOTE FOR PM]` Cheap once the role works; left out to keep the run small.
- Play Store release, release signing, localization (English only).
- Shared people model with Rovsti Messages.

## 7. Non-Goals

- Rovsti Phone is not a caller-ID, spam lookup or cloud-backup service and will never call a network service.
- It does not keep its own contacts database. Android's providers stay the source of truth.
- It is not a general Android dialer for other OEMs in v1. It may work elsewhere, but only Xiaomi is tested.
- It does not replace the system Contacts app.

## 8. Success Metrics

These are personal-project metrics, measured by the owner on their phone.

**Primary**
- **SM-1:** For 7 consecutive days, every call the owner makes or receives goes through Rovsti Phone, with no fallback to the stock dialer. Validates FR-11, FR-13 to FR-24.
- **SM-2:** Zero missed calls caused by the app (a call that rang on the network but never showed the Incoming Call Screen or Notification) during those 7 days. Validates FR-21 to FR-23, FR-5 to FR-8.

**Secondary**
- **SM-3:** The automated run delivers an APK that installs and passes FR-1, FR-2, FR-11 and FR-24 in the owner's first manual test session. Validates FR-39, FR-40.
- **SM-4:** Workspace checks (NFR-8) pass at the end of the run.

**Counter-metrics (do not optimize)**
- **SM-C1:** Visual polish at the cost of ring latency. A slower but prettier incoming screen is a regression. Counterbalances SM-1.
- **SM-C2:** Feature count. Adding deferred items to the MVP run risks not finishing it. Counterbalances SM-3.

## 9. Risks

- **Toolchain not installed.** On 2026-10-27, `adb` and `gpuiforge` are not on PATH and `~/Android/Sdk` has no `ndk/`. `scripts/setup-android.sh` must succeed before FR-39. This is the main delivery risk.
- **GPUI cold start on incoming calls.** It may miss NFR-2. Mitigation: the native fallback allowed in §4.5.
- **MIUI role dialog hidden.** Mitigated by FR-2's Settings path.
- **Older Mali GPUs.** They may need GLES. Check with `adb logcat -s GPUI`.
- **No automated device test.** Final acceptance is the owner's manual test. Partial mitigation: the `x86_64` ABI is built, so an Android emulator can exercise the role grant and incoming / answer / decline / missed flows (`adb emu gsm call <number>`) before sideloading, if the run has an emulator image available.
- **MIUI floating notifications.** They may be off by default for sideloaded apps, which silently breaks FR-23. Covered by the Floating notifications Xiaomi Setting; verify on device.
- **Carrier-dependent call waiting.** FR-20's behavior depends on the network supporting call waiting.

## 10. Open Questions

- **Q1 (Architecture).** In-call and incoming surface: reuse the single GPUI activity (brought to front with `showWhenLocked` / `turnScreenOn`), or a separate activity? Does the incoming screen need the native Kotlin fallback to meet NFR-2?
- **Q2 (Architecture).** Kotlin → Rust event direction for Call State, audio state and caller handle: JSON strings over the existing string-only seam, or a native callback registered in `nativeInit`? The same choice applies to list queries (call log, contacts) that return more than a bool.
- **Q3 (Architecture).** Where the Call State lives when the GPUI view is not running (Kotlin `InCallService` as the owner, Rust view as a renderer), and how the view resyncs on attach.
- **Q4 (Product, non-blocking).** Missed-call notification: MVP relies on Telecom's own notification (no `ACTION_SHOW_MISSED_CALLS_NOTIFICATION` handler). If the owner's device shows none, a Rovsti notification that opens Recents is the first post-MVP addition. Owner: the owner; revisit after first device test.
- **Q5 (Product, non-blocking).** Should Xiaomi confirmations reset on every app update (FR-8) or only when the owner asks? Default: reset on version change. Revisit after a week of use.

## 11. Assumptions Index

- §2.3 / NFR-9: device runs Android 14+ with HyperOS 1/2 or MIUI 14.
- §4.2 / FR-8: Xiaomi confirmations reset when the app version changes.
- FR-11: when Android asks for a SIM, the default or first suggested account is chosen automatically.
- §4.4: proximity sensor turns the screen off during calls at the ear.
- FR-19: during a Call, opening the app shows the In-Call Screen or a "Return to call" pill.
- FR-20: answering a Waiting Call ends the first Call.
- FR-26: Recents loads at most 500 Call Log Entries.
- FR-27: viewing Recents clears Android's "new" flag on missed entries.
- FR-34 / §4.8: Favorites tiles may show contact photos when available.
- FR-35: a Contact with several numbers and no default shows a chooser.
- FR-38: desktop preview uses sample data.
- NFR-1: the generated host does not add `INTERNET`, or the ejected host removes it.
- NFR-2: latency budgets of 1.5 s warm / 3 s cold.
- NFR-10: font scale up to 1.3×.
- §6.2: one active SIM, or the system default SIM is acceptable.
