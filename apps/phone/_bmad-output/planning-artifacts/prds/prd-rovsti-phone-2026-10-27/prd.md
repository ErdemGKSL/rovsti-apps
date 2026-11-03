---
title: "PRD: Rovsti Phone"
status: final
created: 2026-10-27
updated: 2026-10-28
revisions:
  - 2026-10-27 MVP (FR-1 to FR-40, NFR-1 to NFR-13)
  - 2026-10-28 v0.2 additions after the first device test (FR-41 to FR-73, NFR-14 to NFR-18); see section 12
---

# PRD: Rovsti Phone

## 0. Document Purpose

This PRD defines the MVP (v0.1, FR-1 to FR-40) and the v0.2 additions (FR-41 to FR-73, section 4.11) of Rovsti Phone (`apps/phone`), the Android default dialer in the Rovsti monorepo. Its readers are the owner and the downstream BMAD steps (UX, architecture, epics and stories, and one automated implementation run). It builds on the product brief at `../../briefs/brief-rovsti-phone-2026-10-27/` (`brief.md`, `addendum.md`) and does not repeat the brief's market framing.

How the document is organized: §3 Glossary fixes the vocabulary. §4 groups features, each with globally numbered FRs (FR-1 to FR-40 for the MVP, FR-41 to FR-73 for v0.2) and testable consequences. §5 holds cross-cutting NFRs (NFR-1 to NFR-13 for the MVP, NFR-14 to NFR-18 for v0.2). §6.1 sets P0/P1/P2 build priority. Inferences are tagged `[ASSUMPTION]` inline and indexed in §11. Technical "how" (Android components, bridge options, crate placement, toolchain) is kept in `addendum.md` for architecture.

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
- ~~Dual-SIM power users who switch SIMs per call.~~ Now served from v0.2 (FR-50, FR-51).
- ~~Anyone who needs conference calls~~ Now served from v0.2 (FR-44 to FR-48). Still non-users: anyone who needs call recording or caller-ID / spam lookup services (see section 7).

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
- **Xiaomi Setting** — one of four OEM settings that cannot be read back: Autostart, Battery saver "No restrictions", "Show on lock screen / pop-up while in background", and Background pop-ups. Shown only when the device is Xiaomi.
- **Call** — one Telecom call session, outgoing or incoming, from creation to disconnection.
- **Call State** — one of `Dialing`, `Ringing`, `Active`, `Holding`, `Disconnected`, plus the Mute and Audio Route flags. (Android's transient "select phone account" state is resolved automatically per FR-11 and is never shown.)
- **Audio Route** — where call audio flows. MVP: earpiece or speaker, with headsets used automatically. From v0.2 (FR-41): any Audio Endpoint Android lists.
- **Audio Endpoint** — one selectable route offered by Android for the Call: earpiece, speaker, wired headset or a named Bluetooth device (hearing aids appear as Bluetooth). Identified by Android's endpoint identifier, not by type.
- **Conference** — a Call that merges two or more remote parties, with child participants where Android exposes them.
- **Quick Reply** — a short text the owner can send when declining a Call (FR-56, FR-57).
- **Blocked Number** — a number in Android's blocked-numbers store, writable because the app is the default phone app.
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

The owner can see the Setup Checklist with these Setup Items in order: Default Phone App; Phone and call log permissions; Contacts permission; Notifications permission; Full-screen notifications (Android 14+); and, on Xiaomi only, Autostart, Battery saver "No restrictions", Show on lock screen / pop-up, Background pop-ups. Realizes UJ-1.

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
- Delete removes one character; holding delete erases right to left, one character at a time and faster the longer it is held, until the finger lifts or the field is empty (owner request 2026-11-03).

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
- If Android asks the dialer to choose a SIM (for example a dual-SIM phone set to "Ask every time"), the app selects the default outgoing account, or else the first suggested account, so the Call never stays stuck before `Dialing` `[ASSUMPTION]`. A real SIM picker stays out of scope for the MVP; FR-50 supersedes this on dual-SIM phones.
- If `CALL_PHONE` is missing, the app requests it and places the call after it is granted.

#### FR-12: Match the entered number to a Contact

While a number is being entered, the Keypad shows the matching Contact's name when one exists. Realizes UJ-6.

**Consequences (testable):**
- Typing a number stored as "+905551112233" in a Contact, written as "05551112233", shows that Contact's name (using `PhoneNumber::matches`).

**Out of Scope:**
- T9 name search on the keypad (deferred in the MVP; delivered by FR-65 in v0.2).

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
- When a wired or Bluetooth headset is connected, the control shows the headset route and the owner can still choose speaker. A full route picker is out of scope for the MVP; FR-41 and FR-42 supersede this.

#### FR-16: In-Call Keypad and DTMF

The owner can open the In-Call Keypad and send DTMF Tones. Realizes UJ-6.

**Consequences (testable):**
- Each press sends exactly one DTMF Tone for that key, and the pressed digits appear above the keys.
- Closing the In-Call Keypad returns to the main controls without affecting the Call.

#### FR-17: Hold

The owner can put the Call on hold and resume it. Realizes UJ-2.

**Consequences (testable):**
- Hold is shown only when Android reports the Call supports hold. Its state follows the Call State. (v0.2 refinement, NFR-14: the control keeps its slot and is disabled instead of hidden when hold is unsupported.)

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
- Answer ends the first Call and then answers the Waiting Call, so there is only ever one live Call to manage `[ASSUMPTION]`. This keeps the brief's "answer/decline only" scope. **Superseded by FR-48 (v0.2).**

**Out of Scope:**
- Answer-and-hold, swap, merge and conference controls (MVP only; delivered by FR-44 to FR-48 in v0.2).

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

### 4.11 Post-MVP additions (v0.2, from the 2026-10-28 gap analysis)

**Description:** The owner tested v0.1 and found it lacks features a modern dialer has. `../../gap-analysis-2026-10-28.md` compares Google, Samsung, Xiaomi and Apple dialers with the Android Telecom API and sorts the gaps into Epics 14 to 21. This section turns the in-scope gaps into FRs. All of it stays local (NFR-1), needs only the default-dialer role the app already holds, and follows NFR-14 to NFR-18. API facts and effort estimates are in the gap analysis; `[?]` marks facts that must be proven on the owner's phone. Realizes UJ-7 to UJ-10.

**New journeys (captured from the owner's test feedback):**

- **UJ-7. Erdem moves a call to his Bluetooth earbuds.** Mid-call he taps the Audio control. A glass list shows Phone, Speaker, "Erdem's Buds" and "Car kit" by name, with the current one checked. He taps "Erdem's Buds" and the list closes. He switches the buds off; the list and the control update to Phone without him touching it.
- **UJ-8. Erdem runs a three-way call.** On a call with Ayşe he taps Add call, picks Özgür from Favorites, and Ayşe is held. He taps Merge. The screen shows a conference with both names. He ends Özgür from the list and the call continues with Ayşe.
- **UJ-9. Erdem declines a call with a message.** In a meeting a call comes in. He swipes the Incoming Call Screen up, taps "Can't talk now. I'll call you back." and the caller is rejected with that text. Later he blocks a spam number from Recents in two taps.
- **UJ-10. Erdem sets the phone up his way.** In Settings he picks his work SIM as the default, edits the quick replies, assigns speed dial 2 to his sister, reorders Favorites and exports the app's local data to a file.

#### Audio routes and devices (Epic 14)

#### FR-41: Audio route list

The owner can see every audio route Android offers for the Call and choose one. Realizes UJ-7.

**Consequences (testable):**
- The list shows one row per available endpoint: earpiece, speaker, wired headset, and each Bluetooth device by the name Android reports. Rows have a type icon and a check on the current route.
- Choosing a row routes the Call to exactly that endpoint (by its identifier, not by type), so two paired Bluetooth devices can be told apart.
- The list updates while open and while closed when an endpoint appears or disappears (headset plugged, earbuds switched off) within 500 ms (NFR-3). If the current endpoint disappears, the control shows the route Android falls back to.
- A hearing aid appears as a Bluetooth endpoint with its device name; no special type exists in Android's endpoint API `[?: confirm the name shown for the owner's hearing aid, if any]`.

#### FR-42: Audio control behaviour

The Audio control adapts to the available routes. Realizes UJ-7. Supersedes the "full route picker is out of scope" limit in FR-15.

**Consequences (testable):**
- With only earpiece and speaker available, one tap toggles between them as in FR-15.
- With any other endpoint available, one tap opens the FR-41 list. Long-press always opens it.
- The control shows an icon for the current route and a short label (Phone, Speaker, device name, ellipsised) in a fixed-size slot, so the In-Call layout does not move (NFR-14).

#### FR-43: Bluetooth and hearing-aid shortcuts

The route list offers shortcuts to the system pages that manage devices. Realizes UJ-7.

**Consequences (testable):**
- The list footer has "Bluetooth settings" and "Hearing aids" (the system accessibility page). Both fall back to the main Settings page when the specific screen is missing.
- If device names are unavailable because `BLUETOOTH_CONNECT` is not granted, the list asks for it once and shows type names until granted.
- Settings shows whether the handset reports hearing-aid compatibility (informational).

#### Multi-call and conference (Epic 15)

#### FR-44: Add call

The owner can place a second call while one is active. Realizes UJ-8.

**Consequences (testable):**
- The In-Call Screen has an Add call control in a fixed slot. Tapping it opens the Keypad, Favorites and Contacts over the Call without ending it.
- Placing the second call uses the one call path (FR-11) and puts the first on hold, as the network does.
- The control is disabled, not hidden, when Android reports the Call cannot take another call (the "can add call" state).

#### FR-45: Two calls and swap

While two Calls exist, the owner can see both and switch between them. Realizes UJ-8.

**Consequences (testable):**
- The screen shows the active Call and the held Call with names and each timer. Tapping the held Call, or the Swap control, makes it active and holds the other.
- Swap uses the conference swap action when Android reports it, otherwise hold/unhold.
- Hold and Swap share one fixed slot (NFR-14).

#### FR-46: Merge

The owner can merge two Calls into a conference. Realizes UJ-8.

**Consequences (testable):**
- The Merge control is enabled only when Android reports the merge capability on a Call and shares the slot rule above. A carrier that does not support three-way calling leaves it disabled with the reason shown on long-press `[?: carrier support]`.
- After merging, the screen shows the conference state (FR-47).

#### FR-47: Conference participants

The owner can see and manage conference participants where Android allows it. Realizes UJ-8.

**Consequences (testable):**
- The conference view lists participants (children of the conference Call) with names, and a conference timer.
- Each row offers End (disconnect that participant) and Split (take them out as a private Call) only when Android reports the matching capability for the conference; otherwise the rows are read-only.
- If the carrier hides participants, the view shows "Conference" with a count if known, and the End control for the whole conference stays available `[?: IMS conference children on HyperOS]`.

#### FR-48: Waiting Call options

A Waiting Call can be handled in more ways than FR-20 allowed. Realizes UJ-3, UJ-9. Supersedes FR-20's "Answer ends the first Call".

**Consequences (testable):**
- The Waiting Call banner offers Answer and hold, End and answer, Decline and Decline with message (FR-56).
- Answer and hold answers the new Call and holds the current one; the two-call view (FR-45) follows.
- Decline leaves the first Call `Active`, as before.

#### Settings, SIM and local data (Epic 16)

#### FR-49: Settings screen

The owner can open a Settings screen. Realizes UJ-10.

**Consequences (testable):**
- A header button next to Setup opens one glass page. There is no fifth tab and the Tab Bar does not change.
- Sections: Calls, Sounds and haptics, Display, SIM, Quick replies, Blocked numbers, Data, System shortcuts. A section appears only when it has something to show (for example SIM with fewer than two SIMs shows the read-only default only).
- Every setting is stored locally (NFR-17), takes effect without restart, and has a default that reproduces v0.1 behaviour.
- System shortcuts open: sound/ringtone settings, call forwarding and waiting (the system call settings page), accessibility (TTY/RTT), Default apps. Each falls back to the app's system details page if the intent is missing.

#### FR-50: SIM choice

On a phone with two or more call-capable SIMs, the owner can choose the SIM for a call. Realizes UJ-10. Supersedes FR-11's automatic SIM choice on dual-SIM phones.

**Consequences (testable):**
- When Android asks the dialer to choose (system default is "ask every time"), a glass chooser lists the accounts by label; the Call proceeds after the choice and never stays stuck before `Dialing`.
- Long-pressing the Call button, a Recents row's call action or a Contact Sheet number opens the chooser even when a default exists.
- Settings shows the current default and a button to the system page that changes it. With one SIM none of this is visible and v0.1 behaviour is unchanged.

#### FR-51: SIM label

With two or more SIMs, the owner can see which SIM a Call used. Realizes UJ-10.

**Consequences (testable):**
- The In-Call Screen and each Recents row show a small SIM chip (label and slot colour) in a fixed slot. A log entry whose account no longer exists shows no chip.

#### Blocking (Epic 17)

#### FR-53: Block and unblock

The owner can block a number and unblock it. Realizes UJ-9.

**Consequences (testable):**
- Block is offered on a Recents row menu, the number sheet, the Contact Sheet (per number), Call details (FR-69) and the Incoming Call Screen. It writes to Android's blocked-numbers store and shows a confirmation with Undo for 5 seconds.
- A blocked number's calls are rejected by the system before ringing and appear with the blocked type.
- If the platform says blocking is unavailable for the current user, the actions are disabled with an explanation.

#### FR-54: Blocked numbers list

The owner can manage blocked numbers. Realizes UJ-9.

**Consequences (testable):**
- Settings lists blocked numbers (virtualised), each with an Unblock action, and an Add number field using the dial-string rules of FR-9.
- Numbers blocked in another app on the same store appear here and vice versa `[?: HyperOS's own blocklist may be separate]`.

#### FR-55: Blocked calls

Blocked calls are handled quietly and can be reviewed. Realizes UJ-9. Refines FR-26's "blocked hidden".

**Consequences (testable):**
- Blocked entries stay out of Recents and the Missed Call Badge.
- The blocked list has a "Blocked calls" view showing them with time and, where available, the block reason.

#### Incoming call controls (Epic 18)

#### FR-56: Reject with message

The owner can decline a ringing Call and send the caller a text. Realizes UJ-9.

**Consequences (testable):**
- The Incoming Call Screen, the Waiting Call banner and the Incoming Call Notification offer "Message". It lists the quick replies (FR-57); choosing one rejects the Call with that text.
- Which component sends the SMS is proven first on the owner's phone `[?]`. If Android does not send it, the app opens the default messaging app with the text prefilled and still rejects the Call. The result is never silent: the screen says "Sent" or "Opened Messages".
- The app requests no SMS permission unless the proof shows it is required and the owner accepts it (decision recorded in the story).

#### FR-57: Quick replies

The owner can edit the quick replies. Realizes UJ-10.

**Consequences (testable):**
- Settings holds up to 6 replies, default three, editable, reorderable, resettable. Stored locally.
- An empty list hides "Message" instead of showing an empty sheet.

#### FR-58: Silence the ringer

The owner can silence a ringing Call without rejecting it. Realizes UJ-3.

**Consequences (testable):**
- The Incoming Call Screen has a Silence control in a fixed slot. After silencing, the Call stays answerable and rings out as missed if not answered.
- If Settings "Flip to silence" is on, placing the phone face down while it rings silences it. The sensor runs only while a Call is ringing.
- The control is hidden only if the platform refuses the action `[?: permission needed for silencing as a sideloaded dialer]`; the volume key (FR-21) always works.

#### FR-59: Answer and decline gestures

The owner can answer and decline by gesture as well as by button. Realizes UJ-3, UJ-9.

**Consequences (testable):**
- A slide gesture answers, a slide the other way declines, and a slide up opens the reply sheet (FR-56). The thumb tracks the finger with spring release and haptic ticks; a short drag springs back without acting.
- The Answer and Decline buttons remain and keep their accessible labels (NFR-10).
- Touch response is within one frame (NFR-15).

#### Contacts management and quick actions (Epic 19)

#### FR-60: Create and edit contacts

The owner can create a contact from a number or from scratch, and edit one, with the system contacts editor. Realizes UJ-5. Refines FR-32's hand-off.

**Consequences (testable):**
- "Add to contacts" in Recents, the number sheet, Call details and the Keypad opens the system editor with the number prefilled, offering create-new or add-to-existing.
- On return the app refreshes its lists within 2 seconds (as FR-29).

#### FR-61: Delete a contact

The owner can delete a contact. Realizes UJ-5.

**Consequences (testable):**
- The Contact Sheet has Delete with a confirmation naming the contact. Deleting removes it from the provider and from Contacts and Favorites immediately.

#### FR-62: In-app contact editor (stretch)

The owner can edit name and numbers inside the app. Realizes UJ-5.

**Consequences (testable):**
- Applies to contacts with one writable raw contact; others use FR-60's system editor.
- Saved changes appear in the system Contacts app. A write failure leaves the contact unchanged and says why.
- This FR may be cut without affecting FR-60 and FR-61 (P3).

#### FR-63: Contact quick actions

The Contact Sheet and the number sheet carry quick actions. Realizes UJ-5, UJ-9.

**Consequences (testable):**
- Actions: Message (opens the default messaging app to the number), Copy number, Share, Block (FR-53), Call details (FR-69), Favorite (star), Ringtone for this contact (system picker, stored on the contact `[?: needs WRITE_CONTACTS grant]`).
- Long-press on any displayed number copies it with a haptic tick and a "Copied" notice.

#### FR-64: Favorites order and frequent

The owner can reorder Favorites and see frequently called people. Realizes UJ-10.

**Consequences (testable):**
- Dragging a tile reorders it with spring displacement of neighbours and persists the order locally keyed by contact; new stars append at the end; unstarred ones drop out.
- An optional "Frequent" row below Favorites lists the most called non-starred contacts from the call log (pure `rovsti_core` function with tests). A setting hides it.

#### Keypad intelligence (Epic 20)

#### FR-65: T9 search on the Keypad

While digits are entered, the Keypad lists matching contacts by name as well as by number. Realizes UJ-6. Supersedes the "T9 deferred" note in FR-12.

**Consequences (testable):**
- Typing 2-9 matches names by their T9 letters at the start of the name or of any word ("7 9 2" finds "Ayşe Yılmaz" through diacritic folding as in FR-31). Number matches (FR-12) rank alongside.
- Results update on every keypress within one frame and never block the UI (NFR-18). Tapping a result fills the number; its call action calls it.
- The results area has a fixed height so the keys do not move (NFR-14).

#### FR-66: Speed dial

The owner can assign contacts to digit keys. Realizes UJ-10.

**Consequences (testable):**
- Digits 2 to 9 are assignable from Settings and from a Contact Sheet number. Long-pressing an assigned key on an empty Keypad calls that number via FR-11, with the key popping and a haptic.
- Digit 1 is reserved for voicemail (FR-67). Assignments are stored locally and included in the backup (FR-73).

#### FR-67: Voicemail shortcut

The owner can reach voicemail quickly. Realizes UJ-10.

**Consequences (testable):**
- Long-press `1` on an empty Keypad calls the voicemail number of the default (or chosen, FR-50) SIM. If none is configured, the app says so and opens the system call settings.
- Recents shows the voicemail type icon for voicemail entries. A new-voicemail badge appears only if the device exposes an unread signal `[?]`; otherwise no badge, never a guessed one.

#### FR-68: Emergency numbers

Emergency numbers are recognised and never delayed. Realizes UJ-6. Extends FR-10.

**Consequences (testable):**
- When the entered number is an emergency number for the current country, the Call button is labelled "Emergency call" and uses no entry animation delay, confirmation, SIM chooser or blocked/setup check.
- The In-Call Screen disables Add call, Merge and Block for an emergency Call.
- Settings offers an Emergency information shortcut if the system page resolves `[?: intent on HyperOS]`.

#### Call details and history tools (Epic 21)

#### FR-52: Call quality indicators

The owner can see whether a Call is HD or Wi-Fi. Realizes UJ-2.

**Consequences (testable):**
- The In-Call Screen shows HD and Wi-Fi calling chips in a fixed slot when Android reports the properties; no layout change when absent.
- Call details and Recents rows show HD, Wi-Fi and VoLTE from the log features of past calls where recorded.

#### FR-69: Call details

The owner can open a details screen for a number. Realizes UJ-4, UJ-9.

**Consequences (testable):**
- Opened from a Recents row's info control (replacing the number sheet when no Contact matches) and from the Contact Sheet. Shows name or number, a virtualised history of that number (type, date and time, duration, SIM chip, quality chips) and totals (calls, talk time).
- Actions: Call, Message, Copy, Share, Block, Add to contacts, Delete history (FR-70).
- The list loads in under 1 s for 500 entries (NFR-4) and refreshes after a call ends.

#### FR-70: Delete call history

The owner can delete call log entries. Realizes UJ-4.

**Consequences (testable):**
- Delete one entry (swipe), a grouped row, all entries of a number (details screen) or all history (Settings, double confirmation).
- Deleted entries disappear from Recents and the Missed Call Badge immediately. Needs the call-log write permission already declared.

#### Settings content (Epic 16, continued)

#### FR-71: Sounds, haptics and call behaviour

The owner can tune how the app feels. Realizes UJ-10.

**Consequences (testable):**
- Settings has: keypad tones (on/off, follows the system flag by default), haptics (off, light, normal), vibrate when a call connects, vibrate when it ends, proximity screen-off (on by default, FR in §4.4), flip to silence (FR-58), and a shortcut to the system ringtone and sound settings (Telecom plays the ringtone).
- Theme stays "follows system" (FR-37).

#### FR-72: Display

The owner can choose contact name order. Realizes UJ-10.

**Consequences (testable):**
- Settings has Sort by (first name or last name) and Name format (first last or last, first), applying to Contacts, Favorites, Recents and the In-Call Screen, taking effect immediately.

#### FR-73: Backup and restore of local data

The owner can export and import the app's own data. Realizes UJ-10.

**Consequences (testable):**
- Export writes one JSON file (settings, Favorites order, speed dial, quick replies, Xiaomi confirmations excluded) to a location the owner picks with the system file picker. Import restores it after a preview of what changes.
- No permission is needed beyond the picker; nothing leaves the phone except where the owner saves the file.
- Contacts, call log and blocked numbers are not exported (their providers are the source of truth).


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

- **NFR-14 Layout stability (v0.2).** The main layout never shifts. In-Call controls (Mute, Keypad, Audio, Add call, Hold/Swap, Merge) occupy fixed slots and are disabled, never removed, when unsupported. SIM chips, quality chips, the Audio label, the Silence control and the T9 results area have reserved space. Sheets and lists overlay or scroll; they do not push the content they open from.
- **NFR-15 Touch feedback (v0.2).** Every new control shows press feedback within one frame of the touch and gives a haptic tick where the existing equivalent does (honouring the haptics setting, FR-71). Gestures track the finger directly and release with the kit's interruptible springs.
- **NFR-16 Fail-soft platform use (v0.2).** Every new Android API call is gated by a capability or availability check and degrades visibly: disabled control with a reason, or a system-page fallback. No new feature may crash, block a Call, or leave the In-Call Screen without End when the platform, carrier or HyperOS refuses. Facts marked `[?]` in section 4.11 are proven on the owner's phone in the story that first needs them; the result is written into that story.
- **NFR-17 Local-only data (v0.2).** New persistent data (settings, Favorites order, speed dial, quick replies) lives in the app's no-backup directory or in Android's own providers. No `INTERNET` permission and no network code are added. New permissions must be justified in the story; SMS permission only if the Epic 18 spike shows it is needed and the owner accepts it. Export (FR-73) goes only where the owner saves it.
- **NFR-18 Performance of new surfaces (v0.2).** Audio route changes, conference updates and T9 results apply within one frame of the data arriving; T9 search over 2,000 contacts completes within 8 ms off the UI thread; Blocked numbers, Call details, conference and route lists are virtualised and load within 1 s for 500 rows (NFR-4). Performance comes from code optimisation, never from cutting visual effects (AD-21).

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

### 6.2 Out of Scope for MVP (history)

These were out of the MVP. Each item shows where it went on 2026-10-28.

- Dual-SIM picker and per-SIM badges. -> **v0.2, FR-50, FR-51.**
- Blocklist (`BlockedNumberContract`). -> **v0.2, FR-53 to FR-55.**
- Swap, merge and conference calls. -> **v0.2, FR-44 to FR-48.**
- T9 search, speed dial, long-press `1` for voicemail. -> **v0.2, FR-65 to FR-67.**
- Bluetooth / audio route picker beyond earpiece/speaker. -> **v0.2, FR-41 to FR-43.**
- Contact creation and editing (hand-off), -> **v0.2, FR-60 to FR-62**; call notes -> **backlog, see 6.4**; call recording -> **removed, see section 7.**
- Deleting Call Log Entries. -> **v0.2, FR-70.**
- Play Store release, localization (English only). -> still out. Release signing was delivered in Epic 13.
- Shared people model with Rovsti Messages. -> still out.

### 6.3 In Scope for v0.2

FR-41 to FR-73 and NFR-14 to NFR-18, delivered as Epics 14 to 21 (`../../epics.md` is extended by the next epics step). Build order and cut line, from the gap analysis:

- **P1:** Epic 14 audio routes (FR-41 to FR-43), Epic 15 multi-call and conference (FR-44 to FR-48), Epic 16 Settings, SIM and local data (FR-49 to FR-51, FR-71 to FR-73), Epic 17 blocking (FR-53 to FR-55), Epic 18 incoming controls (FR-56 to FR-59).
- **P2:** Epic 19 contacts management (FR-60, FR-61, FR-63, FR-64), Epic 20 keypad intelligence (FR-65 to FR-68), Epic 21 call details (FR-52, FR-69, FR-70).
- **P3 (cut first):** FR-62 in-app contact editor, the Frequent row in FR-64, flip to silence in FR-58 if the platform refuses silencing, vibrate-on-connect/end in FR-71.

Every epic ends with a signed release APK for the owner's phone test.

### 6.4 Backlog (not scheduled)

- Call and contact notes (needs its own store; conflicts with "providers are the source of truth").
- Silencing unknown or private callers through a call-screening role (spike only; sideload behaviour unverified).
- RTT/TTY beyond the Settings shortcut (FR-49).

## 7. Non-Goals

- Rovsti Phone is not a caller-ID, spam lookup or cloud-backup service and will never call a network service.
- It does not keep its own contacts database. Android's providers stay the source of truth.
- It is not a general Android dialer for other OEMs in v1. It may work elsewhere, but only Xiaomi is tested.
- It does not replace the system Contacts app.
- It does not record calls. Android gives third-party apps no supported way to capture call audio, workarounds are restricted for sideloaded apps and carry legal risk, so this is removed from the roadmap, not deferred.
- It does not offer visual voicemail, call screening by AI, Hold for Me, or video calls (they need network services, carrier clients or Google/Apple services; see the gap analysis).

## 8. Success Metrics

These are personal-project metrics, measured by the owner on their phone.

**Primary**
- **SM-1:** For 7 consecutive days, every call the owner makes or receives goes through Rovsti Phone, with no fallback to the stock dialer. Validates FR-11, FR-13 to FR-24.
- **SM-2:** Zero missed calls caused by the app (a call that rang on the network but never showed the Incoming Call Screen or Notification) during those 7 days. Validates FR-21 to FR-23, FR-5 to FR-8.

**Secondary**
- **SM-3:** The automated run delivers an APK that installs and passes FR-1, FR-2, FR-11 and FR-24 in the owner's first manual test session. Validates FR-39, FR-40.
- **SM-4:** Workspace checks (NFR-8) pass at the end of the run.
- **SM-5 (v0.2):** In the owner's first test of Epics 14 and 15, he moves a call between at least two named Bluetooth devices and the earpiece (FR-41), and completes a three-way call with merge and end-one-participant (FR-44 to FR-47), without falling back to the stock dialer. Carrier limits are reported, not counted as failures.
- **SM-6 (v0.2):** Reject-with-message (FR-56) and block (FR-53) each work on the first test or the story records the platform reason and the fallback used.

**Counter-metrics (do not optimize)**
- **SM-C1:** Visual polish at the cost of ring latency. A slower but prettier incoming screen is a regression. Counterbalances SM-1.
- **SM-C2:** Feature count. Adding deferred items to the MVP run risks not finishing it. Counterbalances SM-3. For v0.2, feature count must not cost layout stability (NFR-14) or frame pacing (NFR-5, NFR-18).

## 9. Risks

- **Toolchain not installed.** On 2026-10-27, `adb` and `gpuiforge` are not on PATH and `~/Android/Sdk` has no `ndk/`. `scripts/setup-android.sh` must succeed before FR-39. This is the main delivery risk.
- **GPUI cold start on incoming calls.** It may miss NFR-2. Mitigation: the native fallback allowed in §4.5.
- **MIUI role dialog hidden.** Mitigated by FR-2's Settings path.
- **Older Mali GPUs.** They may need GLES. Check with `adb logcat -s GPUI`.
- **No automated device test.** Final acceptance is the owner's manual test. Partial mitigation: the `x86_64` ABI is built, so an Android emulator can exercise the role grant and incoming / answer / decline / missed flows (`adb emu gsm call <number>`) before sideloading, if the run has an emulator image available.
- **MIUI background pop-ups.** They may be off by default for sideloaded apps, which silently breaks FR-23. Covered by the Background pop-ups Xiaomi Setting; verify on device.
- **Carrier-dependent call waiting.** FR-20's behavior depends on the network supporting call waiting.
- **Carrier-dependent conference (v0.2).** Merge, split and participant lists (FR-46, FR-47) depend on three-way calling and IMS conference support. Capability bits decide at run time; acceptance needs two real calls on the owner's SIM.
- **Reject-with-message sender (v0.2).** Which component sends the SMS, and whether Android 15 restricted settings or Xiaomi's Send-SMS app-op block it, is unproven. Mitigation: Story 18.1 spike and the Messages hand-off fallback (FR-56).
- **HyperOS blocklist divergence (v0.2).** Xiaomi's own filter may not share the system blocked-numbers store (FR-54). Verify on the device.
- **Control-grid growth (v0.2).** More In-Call controls can crowd the screen or shift the layout. Mitigation: NFR-14 fixed slots, designed in Story 15.2 before any control is added.

## 10. Open Questions

- **Q1 (Architecture).** In-call and incoming surface: reuse the single GPUI activity (brought to front with `showWhenLocked` / `turnScreenOn`), or a separate activity? Does the incoming screen need the native Kotlin fallback to meet NFR-2?
- **Q2 (Architecture).** Kotlin → Rust event direction for Call State, audio state and caller handle: JSON strings over the existing string-only seam, or a native callback registered in `nativeInit`? The same choice applies to list queries (call log, contacts) that return more than a bool.
- **Q3 (Architecture).** Where the Call State lives when the GPUI view is not running (Kotlin `InCallService` as the owner, Rust view as a renderer), and how the view resyncs on attach.
- **Q4 (Product, non-blocking).** Missed-call notification: MVP relies on Telecom's own notification (no `ACTION_SHOW_MISSED_CALLS_NOTIFICATION` handler). If the owner's device shows none, a Rovsti notification that opens Recents is the first post-MVP addition. Owner: the owner; revisit after first device test.
- **Q5 (Product, non-blocking).** Should Xiaomi confirmations reset on every app update (FR-8) or only when the owner asks? Default: reset on version change. Revisit after a week of use.
- **Q6 (Product, v0.2, non-blocking).** Does the owner use two SIMs? If not, FR-50 and FR-51 drop to P3; they are invisible on one SIM by design. Owner: the owner.
- **Q7 (Architecture, v0.2).** Wire model for endpoints, multi-call and conference: extend the call snapshot (`rovsti_core::wire`) with endpoint lists and per-call capability bits, keeping golden fixtures (AD-7, AD-14). Owner: architecture, before Story 14.1.
- **Q8 (Product/Spike, v0.2).** Reject-with-message sender (FR-56), ringer-silence permission (FR-58), HyperOS blocklist sharing (FR-54), voicemail badge signal (FR-67), emergency-info intent (FR-68), contact ringtone write (FR-63). Each is proven on the device in the first story that needs it. Owner: the owner (device check).
- **Q9 (Product, v0.2, non-blocking).** In-app contact editor (FR-62) or keep the system editor? Default: system editor (FR-60); editor is a P3 stretch.

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
- §6.2 (MVP only): one active SIM, or the system default SIM is acceptable. Superseded for dual-SIM by FR-50.
- v0.2: the owner has allowed restricted settings for the dialer role (Android 15+ sideload rule), so the role stays held; other v0.2 features need no extra privileged permission.
- FR-41: hearing aids appear as Bluetooth endpoints with their device name `[?]`.
- FR-46, FR-47: the owner's carrier supports three-way calls and exposes conference children `[?]`.
- FR-56: the reject-with-message SMS path works or falls back to the Messages hand-off `[?]`.
- FR-72: name order applies app-wide, not only to the contact lists.
- NFR-18: T9 8 ms budget for 2,000 contacts.

## 12. Revision History

- **2026-10-27 (MVP).** FR-1 to FR-40, NFR-1 to NFR-13, deferred list in section 6.2.
- **2026-10-28 (v0.2).** Added FR-41 to FR-73 (section 4.11), NFR-14 to NFR-18, UJ-7 to UJ-10, SM-5, SM-6, glossary terms, new risks and open questions Q6 to Q9, sections 6.3 and 6.4. Deferred items moved out of 6.2 (history kept). Changed wording, no renumbering: FR-11, FR-12, FR-15, FR-17 and FR-20 each carry a pointer to the FR that supersedes or refines them; FR-26's "blocked hidden" is refined by FR-55. Call recording, visual voicemail, call screening AI, Hold for Me and video calls were removed from the roadmap (section 7). Source: `../../gap-analysis-2026-10-28.md`.
