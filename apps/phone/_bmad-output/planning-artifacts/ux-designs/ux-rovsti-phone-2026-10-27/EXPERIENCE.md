---
name: Rovsti Phone
status: final
created: 2026-10-27
updated: 2026-10-28
sources:
  - apps/phone/_bmad-output/planning-artifacts/prds/prd-rovsti-phone-2026-10-27/prd.md
  - apps/phone/_bmad-output/planning-artifacts/prds/prd-rovsti-phone-2026-10-27/addendum.md
  - apps/phone/_bmad-output/planning-artifacts/briefs/brief-rovsti-phone-2026-10-27/brief.md
  - apps/phone/_bmad-output/planning-artifacts/gap-analysis-2026-10-28.md
design: DESIGN.md
---

# Rovsti Phone: Experience Spine

> Behaviour, screens, states and flows for the dialer. Looks live in `DESIGN.md`; tokens are referenced as `{path.to.token}`. FR/NFR/UJ ids refer to the PRD. **Spines win on conflict with any mock.** The MVP sections describe v0.1 (FR-1 to FR-40). The **v0.2 sections** (headed "v0.2", FR-41 to FR-73, NFR-14 to NFR-18, UJ-7 to UJ-10, Epics 14 to 21) extend them and, where they conflict, win; the Supersession table lists every overridden MVP rule. Still out of scope: call recording, caller-ID lookup, visual voicemail, video, RTT beyond a shortcut, call notes. Open architecture questions Q1 to Q3 are flagged, not solved. Open UX questions UX-Q1 to UX-Q19 are listed at the end.

## Foundation

- **Form factor:** phone only, portrait only, one GPUI window, Android 14+ on a Xiaomi phone (HyperOS / MIUI). Desktop Linux is a preview target only (FR-38).
- **UI system:** `liquid_glass_kit` + `rovsti_ui` (see `DESIGN.md` ui-system). This spine specifies only behaviour on top of it.
- **Persona:** Erdem, developer and owner, one phone, one SIM, English UI. Everything is local; no accounts, no network (NFR-1).
- **Priorities, in order:** (1) never miss or fumble a call, (2) fluid and fast, (3) clean and uncluttered, (4) glass beauty. When 4 hurts 1 to 3, 4 loses (PRD SM-C1).
- **Performance is a requirement:** frame, glass and list budgets in `DESIGN.md` Performance Budget are acceptance criteria. The behaviour rules below (virtualised lists, off-thread queries, no layout springs on lists, degradation) exist to meet them.
- **Role modes:** *Default mode* (holds `ROLE_DIALER`) and *Preview mode* (does not, FR-4). Preview mode is the same app with a banner; lists work if permissions are granted; placing a call hands off to the system.
- **Architecture seams the UX depends on, flagged not solved:**
  - **Q1 (surface):** the Incoming Call Screen and In-Call Screen must be able to show over the lock screen, turn the screen on, and appear in under 1.5 s warm / 3 s cold (NFR-2). Layout, copy and controls below are surface-agnostic. If the native fallback is chosen for the Incoming Call Screen, keep this layout, labels, order and copy; only the glass becomes solid fills (see Incoming Call Screen, fallback skin).
  - **Q2 (event transport):** the UI needs call added / state changed / removed, audio state changed, call-log changed, contacts changed (Addendum). UX needs updates reflected within 500 ms (NFR-3) and does not care about the carrier.
  - **Q3 (call-state owner):** the UI must be able to render the full current Call State on attach (cold start, return from background, GPUI view restarting during a call, NFR-6). If the view dies, the ongoing-call notification is the guaranteed way to end the Call.

## Information Architecture

| Surface | Reached from | Purpose | FRs |
| --- | --- | --- | --- |
| Favorites tab | Tab Bar, first-run-never (default tab after first launch is Keypad) | Starred contacts, one-tap call | FR-34, 35 |
| Recents tab | Tab Bar; Missed Call Badge; missed-call notification (system) | Call history, call back | FR-26 to 29 |
| Contacts tab | Tab Bar | Browse and search people | FR-30, 31 |
| Keypad tab | Tab Bar; `ACTION_DIAL` / `tel:` intent (FR-3); first launch | Dial a number | FR-9 to 12 |
| Setup Checklist card | Top of every tab until complete; collapsed "Setup complete" row after | Role, permissions, Xiaomi settings | FR-5 to 8 |
| Preview banner | Under the title in Preview mode | Says the app is not default; "Fix" leads to the checklist | FR-4 |
| Return-to-call pill | Top of every tab while a Call exists and the user is outside the In-Call Screen | Go back to the Call | FR-19 |
| Contact Sheet | Contacts row; Favorites long-press; Recents info control (when matched) | Numbers, call, star | FR-32, 33 |
| Number sheet | Recents info control (no contact match) | Call, add to contacts | FR-28 |
| Number chooser | Favorites tile (several numbers, no default) | Pick which number | FR-35 |
| In-Call Screen | Outgoing or answered Call; ongoing-call notification; app launch during a Call | Control the Call | FR-13 to 20 |
| In-Call Keypad | In-Call Screen "Keypad" control | Send DTMF | FR-16 |
| Incoming Call Screen | Ringing Call over lock screen; tapping the Incoming Call Notification body | Answer or decline | FR-22, 24 |
| Incoming Call Notification | Ringing Call when unlocked and in use | Heads-up Answer / Decline | FR-23 |
| Ongoing-call notification | A Call exists | End; return to Call | FR-19 |
| System screens (hand-offs) | Role dialog, Default apps, permission dialogs, Xiaomi pages, full-screen intent page, Contacts app (edit, add) | Outside the app | FR-2, 6, 32 |

Navigation model (MVP; v0.2 replaces it, see "v0.2 Information architecture"): one Tab Bar (Favorites, Recents, Contacts, Keypad), no drawer, no settings screen in MVP, modals one level deep (a sheet over a tab; never a sheet over a sheet). The In-Call and Incoming screens are full-screen and replace the tab chrome; they are not tabs.

Default tab: Keypad on first launch (FR-40); afterwards the last selected tab (FR-36, P2). A `tel:` dial intent forces Keypad. During a Call, opening the app from the launcher shows the In-Call Screen (FR-19: "or the Return to call pill": decision below).

> **Decision (FR-19 assumption):** opening the app from the launcher during a Call lands on the **In-Call Screen** (not the tabs). Leaving the In-Call Screen is via the system Back gesture or the ongoing-call notification; the tabs then show the Return-to-call pill. This keeps "one call, one place".

→ No mocks were rendered in this headless run; the layouts below are spine-only. Request key-screen mocks via Update mode for Keypad, In-Call and Incoming first.

## Voice and Tone

Microcopy: short, plain, factual. Sentence case everywhere, no exclamation marks, no emoji, no jokes. Errors say what happened and what to do. The app never blames the user. Time and numbers follow the phone's locale formats; copy is English only.

| Do | Don't |
| --- | --- |
| "Calling…" | "Dialing, please wait!" |
| "Call ended" | "Oops, the call has ended!" |
| "Not a valid number" | "Invalid input" |
| "Rovsti Phone isn't your default phone app yet." | "ERROR: role not held" |
| "Allow Phone access so Rovsti Phone can place calls." | "Permission denied" |
| "Needs your confirmation. Rovsti can't check this setting." | "Unverifiable" |
| "Setup complete" | "You're all set!" |
| "No recent calls" | "Nothing to see here" |
| "Waiting: {name}" | "Another caller is trying to reach you" |

Labels: Answer, Decline, End, Mute, Speaker, Keypad, Hold, Resume, Call, Delete, Paste, Star, Unstar, Edit in Contacts, Add to contacts, Grant access, Set up, Confirm, Open settings, Fix.

Names: show the contact display name; otherwise the formatted number (`PhoneNumber` formatting); hidden number is "Unknown"; a number that cannot be parsed is shown as entered.

## Component Patterns

Behavioral. Visual specs live in `DESIGN.md` Components.

| Component | Use | Behavioral rules |
| --- | --- | --- |
| Tab Bar | All tab screens | Tap a tab: press feedback, puck springs, page changes. A second tap during the flight retargets (interruptible). Tapping the already-selected tab scrolls that list to the top (Keypad: no-op). Hidden (not dismissed) on In-Call and Incoming screens and while the system keyboard is up in Contacts search. Drag across the bar moves the puck under the finger and selects on release (puck drag); optional, not required for MVP. |
| Missed Call Badge | Recents tab icon | Count of missed entries newer than the last view (FR-27). Hidden at 0, "99+" above 99. Clears when Recents is viewed (see Recents). Announces as part of the Recents tab label. |
| Key | Keypad, In-Call Keypad | Fires on finger down, not on up (feels immediate); long-press `0` for `+` after 400 ms replaces the `0` just typed with `+`. A finger sliding off a key cancels nothing already entered. Haptic: light tick on each key (system haptic constant; no sound on the keypad: DTMF tone only in-call). |
| Number field | Keypad | See Keypad. Never wraps; font steps down at 11, 14, 18 characters (36, 32, 28, 24 px); max 32 characters; after 32, extra keys do nothing and the field shakes once. |
| Call button | Keypad, rows, sheets | Calls via the one call path (FR-11) with the raw dial string. Single press only; a second press within 800 ms is ignored (prevents double dialing). |
| Row (Recents / Contacts) | Lists | Tap whole row = primary action (Recents: call; Contacts: open Contact Sheet). Trailing info button is a separate target. Long-press: Recents: opens number/contact sheet (same as info); Contacts: none. Press: scale 0.97 plus `fill` tint. |
| Favorite tile | Favorites | Tap = call (default number; see FR-35 rules); long-press (450 ms) = Contact Sheet with a light haptic. |
| Search field | Contacts | Tapping opens the keyboard (text, no autocorrect, no suggestions). Results update on every keystroke (FR-31), off the UI thread when more than 200 contacts; no debounce visible to the user. The clear button empties the field and keeps focus. Back gesture: first clears focus, second leaves nothing (tab stays). |
| Sheet | Contact Sheet, number sheet, chooser | Opens at medium detent; drag grabber or body to large; drag down or tap the dim area to dismiss; Back gesture dismisses. Starring and calling happen without closing animations blocking them. Over a Call, no sheets open (Return-to-call pill is not tappable through an open sheet). |
| Setup item | Setup Checklist | Has state done / not done / unverifiable (Setup Item definition). Tapping a not-done item or its action runs its fix (FR-6). Done items are inert except in the Default Phone App item, which shows a "Open settings" link. Unverifiable Xiaomi items have two actions: "Open settings" (primary) and "Confirm" (marks done, FR-8). |
| In-call control | In-Call Screen | Toggles reflect the **reported** state, not the tapped state (FR-14, 15): on tap the control shows a pending look (press scale held 120 ms) and settles to the state Android reports within 500 ms (NFR-3). If the reported state does not change within 1 s, the control returns to its previous state and no error is shown. |
| End button | In-Call Screen | Always visible and reachable by the thumb on every In-Call state (including Keypad open, Hold). Single tap ends; no confirmation. |
| Answer / Decline | Incoming Call Screen, Waiting banner, notification | Single tap acts; no swipe-to-answer in MVP (a swipe is slower and breaks TalkBack). Decline never opens the app. |
| Banner | Preview mode | One per screen, tappable "Fix". |
| Return-to-call pill | Tabs during a Call | Tap returns to the In-Call Screen with a `morph` spring. Timer updates once a second. |

## State Patterns

Every list and action has an explicit state. Performance note for all rows: list data arrives off the UI thread; the UI never blocks (NFR-4).

| State | Surface | Treatment |
| --- | --- | --- |
| First launch | Keypad + Setup Checklist expanded | Checklist card at top with all items not done; keypad below it, scrollable under the title. |
| Loading (lists) | Recents, Contacts, Favorites | Skeleton rows or tiles (no spinner). Shown only if data takes over 150 ms; avoids a flash on fast loads. Under 1 s expected (NFR-4). |
| Empty Favorites | Favorites | Icon `LucideStar`, "No favorites yet", body "Star someone in Contacts to call them with one tap.", pill "Go to Contacts". |
| Empty Recents | Recents | Icon `LucideClock`, "No recent calls", body "Calls you make and receive show up here." In Preview mode the body adds "Make Rovsti Phone your default phone app to see everything." |
| Empty Contacts | Contacts | Icon `LucideUsers`, "No contacts with a phone number", body "Add people in the Contacts app." Pill "Open Contacts". |
| Search no results | Contacts | Centered "No matches" with body "No contact matches \"{query}\"." (query truncated to 24 characters). Search field stays. |
| Permission denied: call log | Recents | Triangle-alert icon, "Allow call log access", body "Rovsti Phone needs it to show your recent calls.", pill "Grant access" (FR-26). |
| Permission denied: contacts | Contacts, Favorites | Same pattern, "Allow contacts access", body "Rovsti Phone needs it to show your contacts and favorites." |
| Permission permanently denied | any | After two denials Android stops showing the dialog; the pill then reads "Open settings" and opens the app's system details page. The copy adds "Turn on Phone, Call logs and Contacts under Permissions." |
| Call permission missing | Any call action | The app requests `CALL_PHONE`, and places the call after it is granted (FR-11). If denied: status line / sheet text "Allow Phone access so Rovsti Phone can place calls." with "Grant access". The call is not placed. |
| Not default (Preview mode) | All tabs | Preview banner under the title. Calls hand off to the system (acceptable system in-call UI, FR-4). |
| Role dialog hidden by MIUI | Setup Checklist item | Item expands to show "Your phone didn't show the dialog. Open Settings, Apps, Manage apps, Default apps, Phone, then choose Rovsti Phone." with button "Open default apps" (FR-2). |
| Checklist complete | All tabs | The card collapses into the "Setup complete" row (FR-7); tap to expand. |
| Setup regression | Launch / resume | A previously done item reads not done (permission revoked, Autostart reset after an update, version change reset). The card re-expands showing only the open items first, with a one-line header "Something changed: {item name}". Does not block calling. |
| Dial: invalid | Keypad | Empty field or no digit, `*`, `#`: the number shakes, status line "Not a valid number". Nothing is called (FR-10). |
| Dial: service code / emergency | Keypad | Accepted as entered (FR-10). No special UI, no confirmation, no blocking by a missing Setup Item. |
| Dial: matched contact | Keypad | The status line under the field shows the contact's name (FR-12); a non-match shows nothing. |
| Dialing | In-Call | Name, "Calling…", End (and the Mute, Speaker, Keypad controls enabled; Hold disabled until Active). |
| Active | In-Call | Name, timer (`m:ss`, `h:mm:ss`), full controls. |
| Holding | In-Call | Status "On hold" in warning colour; Hold control shows Resume (on-state); Mute, Speaker, Keypad disabled (opacity 0.4); End enabled. |
| Hold unsupported | In-Call | Hold control disabled and visibly dim; no error text (FR-17). |
| Disconnected | In-Call | Status "Call ended" for about 1 s, controls dim, then the screen leaves (FR-18). A failed call (busy, unreachable, network) shows "Call failed" in the same slot with the reason line when Android gives one ("Number busy", "Out of service", "Number not reachable"); held 2 s for failures so it can be read. |
| Audio route change | In-Call | Headset connected: Speaker control becomes a route control showing `LucideHeadphones` or `LucideBluetooth`; tapping toggles speaker vs headset (FR-15). |
| Waiting Call | In-Call | Banner at top (FR-20). The Call underneath keeps its timer. Answering ends the first Call and answers the second; the banner copy states it: "Answer ends current call" as a footnote under the name. |
| App process killed mid-call | Any | The Call continues (Telecom owns it). Ongoing-call notification stays with End. On next app start the In-Call Screen re-attaches (Q3). |
| GPUI view failed | Any | Same as above; the incoming notification path still rings and the system answer/decline in the notification works. |
| Sample data (desktop) | Desktop preview | A small caption "Preview data" in tertiary-label under the title, only on desktop (FR-38). Android-only actions show "Not available on this platform". |
| Offline | n/a | There is no network concept in the app. |
| Reduced motion / reduced transparency | Everything | See Accessibility Floor and `DESIGN.md` Motion and Elevation. |

## Interaction Primitives

- **Tap** acts on finger-down visuals and finger-up semantics, except keys and in-call toggles, which act on finger-down for immediacy. **Long-press** is 450 ms with a light haptic, used in exactly three places: `0` on the keypad (`+`), delete (clear all), Favorites tile (Contact Sheet). Recents rows long-press equals the info control.
- **Scroll:** inertial, vertical only. Titles are large and collapse to a small title bar of 44 px as the list scrolls past 24 px (opacity and translate only, no layout). Search field in Contacts sticks.
- **Sheets:** drag with fling projection and rubber-banding (kit `gesture::project`, `rubber_band`); two detents (medium, large).
- **Haptics:** light tick on keypad keys, tab selection and toggles; medium on Answer, End, Decline, star. Respect system touch feedback.
- **System gestures:** Back dismisses sheet, then search focus, then leaves the In-Call Keypad, then does nothing on tabs (never exits the app on the root tab; the OS decides). Back on the In-Call Screen goes to the tabs (the Call continues, Return-to-call pill shows). Back on the Incoming Call Screen does nothing (prevents accidental decline); the volume key silences the ring (system).
- **Press feedback:** springs from `DESIGN.md` Motion; always concurrent with the action.
- **Paste:** the Keypad accepts system paste (long-press the number field, and the "Paste" pill when the clipboard has text and the field is empty). Pasted text is normalised: digits, `+`, `*`, `#`, `,`, `;` kept; spaces, dashes and parentheses dropped (FR-9).
- **Deep links:** `tel:` or `ACTION_DIAL` open Keypad with the number pre-filled, no call (FR-3). The number is shown in the field and may be edited.
- **Banned:** swipe-to-answer, swipe-to-delete rows (Recents delete is out of scope), pull-to-refresh (lists refresh live), carousels, badges for engagement (only the missed count), onboarding tours, rate-me prompts, sounds from the app (the system ringer rings), looping animations other than the Call aura and the incoming sonar rings, toasts.
- **Cost control:** no layout springs on lists; list items appear with a fade only; scroll never triggers an animation start. A sheet never animates the blur.

## Signature Moments (behaviour)

The delightful, distinctive moments are specified with spring parameters, durations and reduced-motion fallbacks in `DESIGN.md` Signature Moments (S1 to S16). This spine fixes **when they play and what they must never do**:

| Moment | Plays when | Must never |
| --- | --- | --- |
| S1 Drop key, S2 Liquid number field | Each key down / delete on Keypad and In-Call Keypad (S1 ripple and drop on the Keypad only) | Delay or batch the digit entry; the digit is in the field on the first frame, the drop is decoration |
| S3 Call blooms, S4 Shared-element avatar, S8 Answer morph | Call placed or answered | Block or delay the Call; if the next screen is not ready the Call is still placed and the pressed button waits |
| S5 Tab flow | Tab selected (tap or puck drag) | Run on the already-selected tab; run twice on a rapid second tap (it retargets) |
| S6 Call aura | In-Call Screen visible with the screen on | Imply audio level; loop at idle on a hidden screen; run while the proximity sensor has the screen off |
| S7 Incoming entrance | Incoming Call Screen appears | Show a blank frame; hold Answer or Decline from being tappable (both are tappable from the first frame they show, even mid-spring) |
| S9 Timer lands, S11 Call ended collapse | Call becomes Active / Disconnected | Extend the "Call ended" hold beyond 1 s (2 s for failures) |
| S10 Control tint flood | Mute, Speaker, Hold reported state changes | Show a state Android did not report |
| S12 Star burst | Star set on | Play on a failed write (the toggle reverts, no burst) |
| S13 Badge pop | Missed count increases | Play on first load or when the count merely re-renders |
| S14 Setup complete | The last Setup item turns done in this session | Replay on launch |
| S15 Theme melt, S16 Sheet rise | Theme change / sheet open | Run when reduced motion is on (fallbacks apply) |

**v0.2 moments (S17 to S36, specs in `DESIGN.md` v0.2 Signature Moments):**

| Moment | Plays when | Must never |
| --- | --- | --- |
| S17, S18 Route bloom, Device arrival | Popover opens or a route is chosen; an endpoint appears | Move a row under the finger; claim a route Android did not report; change the selected route by itself |
| S19, S20, S21, S22 Hold-away, Swap, Merge, Eject | Android reports the second call, swap, conference or participant change | Play on tap before the report (Merge); delay the End button; reorder the control grid |
| S23 Waiting card drop | A second call rings | Hold back any of its four choices from being tappable on the first frame |
| S24, S25 Gear turn, Toggle snap | Settings opened; a toggle or segment changes | Delay a setting taking effect |
| S26 SIM chip flight | A SIM is chosen | Place the call before a choice, or stall the call after it |
| S27, S28 Block sweep, Notice pill | Block confirmed; any notice | Show Undo for an action that cannot be undone; stack more than one pill |
| S29, S30, S31 Incoming slide, Silence hush, Reply sent | Drag, silence, reply | Delay Answer or Decline buttons; imply the caller was messaged before the platform accepted it (the card says Sent or Opened Messages) |
| S32, S33 Results morph, Speed-dial charge | First digit; held assigned key | Delay a digit; start a call on a non-empty field; run for an emergency number |
| S34 Reorder lift | Long-press then movement on a tile | Open the Contact Sheet after a drag; change the real order before drop |
| S35, S36 Details arrival, Hold to delete | Details opens; hold on the confirm pill | Delete on a tap without the full hold (or the TalkBack button) |

Every moment obeys the Performance Budget and the reduced-motion fallbacks. At most two signature moments run at once on a screen; on a collision the later one waits for the earlier one's first 120 ms, except S3/S8, which always run immediately.

## Accessibility Floor

Behavioral. Visual contrast lives in `DESIGN.md` Colors.

- **TalkBack (AccessKit via `gpui_android`):** every interactive element has `aria_label` and a role; icon-only controls are labelled by action, not by icon. Required labels and states:
  - Tab Bar tabs: `Role::Tab`, labels "Favorites", "Recents", "Contacts", "Keypad"; the selected tab announces "selected"; Recents appends ", {n} missed calls" when the badge shows.
  - Keys: "1", "2 A B C" (digit then letters), "star", "pound", "0 plus" (hint "Long press for plus"). Call: "Call {number or name}"; Delete: "Delete" (hint "Long press to clear"); the number field announces the whole number on change with a 300 ms settle, digit by digit as typed.
  - Rows: "{Name}, missed call, 2 calls, yesterday 3:14 pm, mobile. Double tap to call." Info button: "Details for {name or number}".
  - Favorites tile: "{Name}. Double tap to call. Long press for details."
  - In-call: "Mute, off" / "Mute, on" (toggle state); "Speaker, off"; "Hold" / "Resume"; "Keypad"; "End call"; the call status line is a live region (polite): "Calling {name}", "Connected", "Call ended". The timer is not announced every second; it announces on request (focus) and at the first connect.
  - Incoming: "Incoming call from {name or number}" announces assertively when the screen appears; "Answer", "Decline" are the first two focus stops, Answer first.
  - Setup items: "{title}, done" / "not done" / "needs confirmation". Action: "Set up {title}".
  - Sheets: announce the sheet title on open; focus moves to its first control; Back returns focus to the originating element.
- **Focus order:** reading order, top to bottom, left to right. Tab screens: title, banners, list, then Tab Bar last. In-Call: name, status, control grid (row-major), End. Incoming: caller, Answer, Decline.
- **Touch targets:** at least 44 x 44 (NFR-10); no two adjacent targets under 8 px apart except keys (24 px).
- **Font scale up to 1.3x without clipping the in-call controls (NFR-10):** rows grow, titles wrap to two lines on tab screens only, the number field steps down in size, control captions move to two lines, and the control grid scrolls vertically on the In-Call Screen when the height does not fit (End stays pinned at the bottom).
- **Reduced motion** (system animation scale 0 or `prefers_reduced_motion`): springs snap; the puck jumps; sheets use a 120 ms fade; no shake (status text instead); aura and sonar rings static (see DESIGN.md Signature Moments); the page entrance is a fade only.
- **Reduced transparency / no backdrop support:** glass falls back to the opaque material; all text keeps contrast (DESIGN.md Elevation).
- **Dark and light:** both pass the contrast pairs listed in `DESIGN.md` Colors; state is never colour alone (missed has an icon and the word "missed" in its label; on-state has a ring; not-done has an open circle versus a check).
- **Haptics-only cues are never the only cue.** Ringing alerts through the system ringer, not the app; silent mode still shows the Incoming Call Screen or Notification (FR-21).
- **Time and numbers** are read in the user's locale by TalkBack from the label strings, which are built with the full spelled-out relative day ("yesterday", "Monday").

## Screens

Coordinates are in dp on the 402 x 874 reference; all vertical stacks are flex and obey `DESIGN.md` Layout & Spacing.

### Keypad tab (FR-9 to FR-12, UJ-1, UJ-6)

Layout, top to bottom:
1. Status bar inset, then `large-title` "Keypad" (collapses on scroll only when the Setup Checklist card makes the page scroll).
2. Setup Checklist card or the collapsed row (when not complete), or the Preview banner (Preview mode and checklist complete). One of these only (precedence in DESIGN.md Performance Budget).
3. Optional "Paste" pill (only when the field is empty and the clipboard has text).
4. Number field (`dial-number`, centered, 52 px high). Below it the 18 px status line: matched contact name, validation message, or call result.
5. Key grid 4 x 3: rows `1 2 3`, `4 5 6`, `7 8 9`, `* 0 #`, 78 px keys, 24 px gaps.
6. Bottom row: empty slot (78), Call (78), Delete (78; hidden when the field is empty).
7. Bottom: 12 px gap, then the Tab Bar over the bottom inset. On a short screen (under 700 dp) the title is hidden when the Setup card is collapsed; the key size stays 78; the page scrolls rather than shrinking keys.

Behaviour:
- Digit key: appends, pops the newest digit, shows Delete, updates the contact match (P2: FR-12) on each change (match runs off-thread; the status line updates when ready, never blocks typing).
- Delete tap removes one character (spring on the digit row); long-press 500 ms clears the field with a fade of the digits.
- `0` long-press: replaces the typed `0` by `+` once.
- Call with a valid dial string: places the call (FR-11): the key press is acknowledged immediately, the status line shows "Calling…" for the time until the In-Call Screen appears (usually under 300 ms), and the field is cleared when the Call is added.
- Call with an empty field: the shake and "Not a valid number". An *empty* Call press when there is a last-dialed number: not in MVP (redial is out of scope).
- Invalid/empty: no call; nothing else changes.
- Dial intent: field is pre-filled, cursor at the end, no call.
- Call without permission: see State Patterns.

### Recents tab (FR-26 to FR-29, UJ-2, UJ-4)

Layout: `large-title` "Recents"; checklist/banner/pill (one precedence stack); virtualised list of Recents Rows (58 px each) under a sticky-less list (no section headers in MVP; relative time carries the grouping); Tab Bar floats above with the Missed Call Badge on its icon.

Row content: avatar 40, name or number (headline), count "(n)" when grouped, subtitle (direction icon + label), trailing time (`footnote`) and info button. Time format: today "3:14 pm", yesterday "Yesterday", within 6 days the weekday, older "12/10/26" (phone locale). Missed rows: title and count in `{colors.danger}`; declined rows show `LucidePhoneOff` and "Declined".

Behaviour:
- Tapping the row calls that number (FR-28) through the call path; the row shows a press then the In-Call Screen takes over. The destination number is the row's raw number; an unknown or private number (empty) row is not callable: the row is inert (no press feedback beyond a dim) and TalkBack says "Unknown number, cannot call back".
- Info: Contact Sheet when the number matches a Contact; otherwise the number sheet with "Call" and "Add to contacts" (FR-28; the latter opens the system Contacts app insert screen).
- Viewing Recents (the tab becomes selected and the first rows have been on screen for 1 s) sets "last viewed" time, clears the badge and asks Telecom to cancel the missed-call notification (FR-25, FR-27). Rows that were new stay red until the next view cycle ends (they do not change colour while you are looking at them).
- Live refresh: when a Call ends while Recents is open, the new row appears at the top within 2 s (FR-29): it fades in at the top, rows below do not slide (no layout spring); if the user has scrolled down, the list does not jump, and a small glass pill "New call" is not shown (out of scope); the list simply updates below the fold.
- Grouping is by consecutive entries with the same normalized number and type (FR-26); a count appears only when above 1.
- Empty, loading, permission states per State Patterns.
- Scrolling 500 rows stays within the frame budget (Performance Budget).

### Contacts tab (FR-30 to FR-33, UJ-5)

Layout: `large-title` "Contacts"; checklist/banner/pill precedence; sticky search field under the title; the alphabetical list with sticky section letters (28 px); an optional alphabet index rail is not in MVP.

Behaviour:
- Rows: contacts with at least one number (FR-30), provider display-name order, sections by first letter; names starting with a non-letter go under "#".
- Tapping a row opens the Contact Sheet (medium detent). There is no one-tap call from this list (that is Favorites).
- Search filters as you type (FR-31), diacritic- and case-insensitive for names, formatting-insensitive for numbers; result rows show the matched number as the subtitle when the match was a number. Sections collapse to a flat list while searching.
- Contact Sheet: avatar, name, star toggle, numbers grouped (label, formatted number, call button per row; the whole row is also tappable to call, FR-32), "Edit in Contacts" (opens the system Contacts app via `ACTION_EDIT`; returning to the app refreshes the contact).
- Star toggle (FR-33): immediate visual toggle with a medium haptic; writes `STARRED`; if the write fails the toggle returns and the sheet shows an inline error "Couldn't update favorite" for 3 s (needs `WRITE_CONTACTS`). Favorites updates immediately when next shown.
- A contact with several numbers: all numbers listed; the default (super-primary) number shows a small "Default" caption.

### Favorites tab (FR-34, FR-35, UJ-2)

Layout: `large-title` "Favorites"; stack precedence as above; a grid of Favorite tiles (2 columns), scrolling; empty state if none.

Behaviour:
- Order: alphabetical by display name (the provider's order); no manual reorder in MVP.
- Tap tile: calls the contact's default number; if the contact has one number, calls it; if several and no default, opens the Number chooser (FR-35).
- Long-press: Contact Sheet.
- A contact starred elsewhere appears next time the tab is opened (FR-34); no live refresh is required while the tab is open.
- Photos: monogram first, photo fades in when decoded (P2). No grid reflow animation on star or unstar.

### Setup Checklist (FR-5 to FR-8, UJ-1)

A sheet-glass card at the top of every tab (it scrolls with the page, it is not pinned). Items in this order; each row has title, one-line reason, state icon and an action:

| # | Item | Reason line | State source | Action |
| --- | --- | --- | --- | --- |
| 1 | Default phone app | "So Rovsti Phone can answer and place every call." | Role held (readable) | "Set up": role dialog (FR-2); if it cannot show, expand the Settings-path help and "Open default apps" |
| 2 | Phone and call log access | "To place calls and show your recent calls." | Permissions (readable) | "Set up": runtime request for the group; permanently denied: "Open settings" |
| 3 | Contacts access | "To show names, search and favorites." | Permission (readable) | Same |
| 4 | Notifications | "To show incoming calls when you're using your phone." | Permission (readable) | Same |
| 5 | Full-screen notifications (Android 14+) | "So incoming calls can open over the lock screen." | `canUseFullScreenIntent` (readable) | "Set up": opens the system full-screen-intent page |
| 6 | Autostart (Xiaomi) | "Xiaomi may stop Rovsti Phone in the background without this." | Unverifiable until confirmed | "Open settings" then "Confirm" |
| 7 | Battery saver: No restrictions (Xiaomi) | "Keeps Rovsti Phone alive when the screen is off." | Unverifiable; the AOSP half is readable | "Open settings": the AOSP dialog first, then the Xiaomi page; then "Confirm" |
| 8 | Show on lock screen (Xiaomi) | "Lets the incoming call screen appear over the lock screen." | Unverifiable until confirmed | "Open settings", "Confirm" |
| 9 | Background pop-ups (Xiaomi) | "Lets the call screen open while you use another app." | Unverifiable until confirmed | "Open settings", "Confirm" |

Behaviour:
- Items 6 to 9 appear only on Xiaomi (detected). On other devices the card has 5 items.
- Opening a Xiaomi page: the app opens the deep link; on a missing component it opens the app details page and shows an inline note "This phone's menu is different. Look for Autostart, Battery saver, Other permissions and Notifications." (FR-6).
- "Confirm" is enabled only after the user has opened the settings page for that item at least once in this session; a hint "Open settings first, then confirm here" shows on the disabled button. Confirmation persists, resets when the app version changes (FR-8, Q5).
- Re-evaluation: on launch and every resume (FR-7). Returning from a system page re-checks readable items and updates the icons with a quiet cross-fade (no layout motion).
- Progress: "{done} of {total} done" counts confirmed Xiaomi items as done.
- Completion: all done and confirmed: the card collapses into "Setup complete" (morph spring) once, not repeatedly; it stays collapsed across launches until an item regresses.
- The checklist never blocks any other function. Emergency calls and every other action work while it is open.
- Items 2 to 4: after the role is granted, items pre-grant and flip to done without a prompt (FR-5); the UI does not assume a dialog will appear.

### In-Call Screen (FR-13 to FR-20, UJ-2, UJ-3, UJ-6)

Full-screen, no Tab Bar, over the same emerald wash and the living Call aura (S6). Layout, top to bottom:
1. Top inset; if a Waiting Call exists, its banner under the inset (76 px).
2. 64 px gap; avatar 96 (photo or monogram; `LucideUser` for unknown).
3. Name `incall-name` (one line); 4 px; status `incall-status`: "Calling…" / the timer / "On hold" / "Call ended" / "Call failed".
4. Optional secondary line `footnote`: the formatted number when the name is a contact, and the route text when the route is a headset ("Bluetooth", "Headphones").
5. Flexible space.
6. Control grid 3 x 2: Mute, Keypad, Speaker / (empty), Hold, (empty). The empty slots stay empty (MVP has no add-call, FaceTime, contacts); Hold is centered in the second row.
7. 32 px; End button 220 x 64 centered.
8. Bottom inset + 24.

Behaviour:
- **Mute (FR-14):** toggles; shows the on-state per reported state. A headset-button mute change reflects here within 500 ms.
- **Speaker (FR-15):** toggles earpiece and speaker. With a headset connected the control's icon is the headset route; tapping selects speaker; tapping again returns to the headset. Reflects the reported route.
- **Keypad (FR-16):** swaps the control grid for the In-Call Keypad (below); the End button stays. A "Hide" text button (accent) closes it.
- **Hold (FR-17):** present only when the Call supports hold (`can_hold`), else disabled and dim. On Hold, the control becomes "Resume" (play icon, on-state), other controls dim as in State Patterns.
- **End (FR-18):** disconnects; the status becomes "Call ended" for about 1 s (2 s for a failed call), then the screen leaves with an `appear` fade: to the previous tab (Recents shows the new row, FR-29), or to the lock screen if the phone was locked and the screen was turned on for the call (the screen turns off per system timeout; the app does not force sleep). A remote hang-up behaves the same.
- **Back / swipe home:** leaves to the tabs with the Return-to-call pill; the Call continues.
- **Proximity:** the screen turns off at the ear with the earpiece route and turns on when removed (system proximity wake lock); the screen stays on with speaker or headset route (§4.4, assumption).
- **Timer:** starts when the Call becomes Active; updates once a second; survives background and return (FR-13).
- **Waiting Call (FR-20):** banner shows the waiting caller with Decline and Answer. Decline rejects it; the main Call is untouched. Answer ends the main Call, then answers the waiting one; the banner has the footnote "Answer ends current call" (spine copy above). If the waiting caller hangs up first, the banner slides away. While a Waiting Call exists, a ringing sound plays from the system (call waiting tone).
- **Dialing state errors:** if Android reports a failed outgoing call, show "Call failed" and the reason line, and leave as above.
- **Dual-SIM "select account":** resolved automatically per FR-11; the user never sees a picker, and the status is "Calling…" throughout.
- **Rendering of text:** the name truncates; numbers truncate in the middle; TalkBack reads the full string.

#### In-Call Keypad (FR-16, UJ-6)

Replaces the control grid. Layout: the pressed-digits line (`dial-number` at 28 px, up to 24 characters, shows the last 24, left-ellipsis) above the key grid of 4 x 3 keys (72 px keys, 16 px gaps) in the same positions as the main keypad, then the "Hide" button, then End. Each key press sends exactly one DTMF tone (start on down, stop on up) and appends the digit to the line (FR-16). No Call button; no Delete. Long-press `0` does not produce `+` here.

### Incoming Call Screen (FR-21 to FR-24, UJ-3)

Shown over the lock screen when the phone is locked or the screen is off, turning the screen on (FR-22); also when the user taps the Incoming Call Notification body (FR-23). Not used when the phone is unlocked and in use (a heads-up notification is used instead).

Layout (full screen, no Tab Bar, emerald wash plus the contact's aura, no glass in this screen: see Performance Budget):
1. Top inset, 80 px gap.
2. Label "Incoming call" (`footnote`, secondary-label, with a `LucideLock` 12 prefix when over the lock screen).
3. Avatar 120 (photo or monogram, contact hue pair) with the living aura behind it and sonar rings while ringing (S6, S7).
4. Name `incall-name`, centered; below it the formatted number (`subheadline`, secondary-label) when the caller is a contact; "Unknown" for hidden numbers.
5. Flexible space.
6. Decline (76, danger) left and Answer (76, answer) right, 96 px apart, captions under each. Both circles are solid, with the glyph at 30 px.
7. Bottom inset + 48.

Behaviour:
- Ringtone, vibration and ringer-mode behaviour are the system's (FR-21). The screen appears and works in silent mode and under Do Not Disturb as far as Android allows the full-screen surface.
- Answer (FR-24): the Call moves to Active and the In-Call Screen takes over using the `morph` transition; no unlock is needed. Decline: rejects the Call and dismisses; the lock screen is shown again.
- Volume key silences the ring (system); the screen stays until answered or declined or the remote ends it.
- Remote hang-up or missed: the screen shows "Missed call from {name}" for 1.2 s (only if the phone stays on the screen), then dismisses; the Missed Call Badge increments (FR-25).
- Back and home gestures do nothing while ringing (home moves the app to the background only after the call ends; Android may show the notification instead).
- Cold start: if the GPUI surface is not ready within 1.5 s, the system's Incoming Call Notification (heads-up with Answer / Decline) is already visible and works. The user must never see a blank white screen: the first frame shown is the wash and the caller name, glass is optional.
- **Fallback skin (if Q1 selects the native screen):** same layout, name, number, labels, order, copy and button positions; solid `{colors.background}` with a flat emerald gradient, solid circular buttons, system font. After Answer, the In-Call Screen is the GPUI one and the transition is a plain cross-fade.

### Incoming Call Notification (FR-23)

Used when the phone is unlocked and in use. A high-priority call-style heads-up notification: caller name (or number), "Incoming call", Answer and Decline actions. Answer answers the Call and opens the In-Call Screen. Decline rejects it without opening the app. Tapping the body opens the full Incoming Call Screen. If Background pop-ups are not allowed on Xiaomi, this silently fails: that is why the Setup item 9 exists.

### Ongoing-call notification (FR-19)

While a Call exists: persistent call-style notification with the caller, the timer (chronometer) and an End action. Tapping opens the In-Call Screen. It is the guaranteed fallback if the GPUI view fails (NFR-6).

### Preview mode (FR-4)

Tabs behave normally (lists need permissions). The preview banner shows under the title: "Not the default phone app" and "Fix". Making a call places it through the system (the system in-call UI may show); a one-line status after the call attempt reads "Placed through the system phone app". "Fix" scrolls to the Setup Checklist's first item.

### Return-to-call pill

A glass capsule under the status bar on every tab while a Call exists and the In-Call Screen is not showing: "Return to call · 2:41" (live). It is above the checklist/banner stack; tapping goes to the In-Call Screen. It hides when the Call ends.

## v0.2 Screens and surfaces (Epics 14 to 21)

Everything here extends the MVP screens above and **supersedes** them where the Supersession table says so. Conventions that hold for every new surface:

- **Touch model (matches the built app).** Android delivers the raw touch when the finger lands and only synthesises the click after it lifts. So every new control paints its **press feedback on the raw finger-down** (scale `press-scale` 0.97, or 0.94 for tiles and circles, plus the lens glow, within one frame, NFR-15) and **runs its action on finger-up** (the synthesised click), after the tap guard has confirmed the finger did not drag or scroll. A finger that slides off a control releases it without acting. The exceptions, which act on finger-down by design, stay the existing ones: keypad digit keys and in-call toggles that need immediacy. Destructive and irreversible actions (Block, Delete, Decline-with-message, End and answer) always act on finger-up only.
- **Haptics.** A light tick on press for tiles, rows with actions, toggles and segments; a medium haptic when a call-affecting action commits (Swap, Merge, Answer variants, Block, Delete). All follow the Haptics setting (FR-71); "Off" silences the app's own ticks and never the system ringer.
- **Layout stability.** New content arrives in overlays (popover, sheet, card, Notice pill) or in reserved slots (chip row, Audio caption, T9 slot, Silence slot, Held-card slot). Nothing that was on screen moves when a new surface opens (NFR-14).
- **Reduced motion and reduced transparency** apply as in the Accessibility Floor; each surface below lists only what is specific.
- **TalkBack.** Every control has a label, a role and (for toggles) a state; sheets announce their title and move focus in; Back returns focus to the opener. Labels per surface are in the Accessibility Floor addendum.
- **Strings are English** and follow Voice and Tone; the new strings are listed with each surface.

### Supersession table

| MVP rule | Now |
| --- | --- |
| "No settings screen in MVP" (Navigation model) | Settings page behind the gear (FR-49); no fifth tab |
| Control grid slots Mute / Keypad / Speaker, Hold centred | Fixed 3 x 2: Mute, Keypad, Audio / Add call, Hold or Swap, Merge (FR-42, 44 to 46) |
| Speaker toggles earpiece and speaker, headset swaps (FR-15) | Audio control: one tap toggles when only Phone and Speaker exist, otherwise opens the route list (FR-42) |
| Waiting Call: Answer ends the current call (FR-20) | Waiting card with four choices (FR-48) |
| Banned: swipe-to-answer | Slide gestures on the incoming card in addition to buttons (FR-59); buttons stay |
| Dual-SIM picker never shown (FR-11) | Chooser when Android asks or on long-press of Call (FR-50) |
| Recents row long-press equals the info control | Long-press opens the row menu; the info control opens Call details (FR-69) |
| Number sheet from Recents info (no contact) | Replaced by Call details; the number sheet remains only as the target of Keypad "Add to contacts" and as the bottom of a T9 miss (not used) |
| No toasts | One Notice pill, only for reversible actions, confirmations and short results |
| Favorites order alphabetical, no reorder | Manual order with long-press drag (FR-64) |
| Banned: swipe-to-delete rows | Still banned. FR-70's "swipe" is replaced by the row menu and Call details, see UX-Q9 |

### Audio route picker (Epic 14, FR-41 to FR-43, UJ-7)

**Where.** The Audio control (row 1, column 3 of the In-Call grid). Also reachable by long-press on the control at any time (FR-42).

**Control behaviour (spec).**
- *Icon and caption.* The icon is the current route's icon; the caption is its name (Phone, Speaker, or the Bluetooth device name, ellipsis, one line, 96 px wide slot).
- *Only Phone and Speaker available (no other endpoint):* a tap toggles Phone and Speaker on finger-up (S10 tint flood for Speaker on), long-press opens the list (it shows Phone, Speaker, the empty-devices hint row and the footer shortcuts).
- *Any other endpoint available:* a tap opens the list; the control carries a 6 px accent dot when a device appeared since the list was last opened (S18, 3 s).
- *Disabled:* while Holding the control is dimmed as the other controls are, and a tap does nothing.

**Surface.** Route popover (`{components.route-popover}`), 288 px wide, anchored above the Audio control and aligned to the right inset; opens upward, over the avatar zone, with the page behind dimmed to 0.7. The End button and the grid below the popover stay in place and tappable (tap outside the popover on a control both dismisses the popover and fires nothing; the first tap only dismisses, to avoid accidental mutes).

**Rows, top to bottom (bottom-anchored, newest device at the top so existing rows never move).**
1. Bluetooth and hearing-aid devices, one row per endpoint, by the **name Android reports**, `LucideBluetooth` or `LucideEar` (best effort: shown only if the name says "hearing" or "aid"), subtitle "Connected".
2. Wired headset when present, `LucideHeadphones`.
3. Speaker, `LucideVolume2`.
4. Phone, `LucideSmartphone`. The earpiece row is always present; if Android does not list it (for example a headset replaced it) it is dimmed with the subtitle "Unavailable with this device" and cannot be chosen (disabled, not hidden).
5. Footer: "Bluetooth settings", "Hearing aids" (both open system pages, FR-43; falling back to Settings).

**Selection.** The current route has the accent check. Finger-up on a row sends "route to this endpoint" by identifier. The tapped row shows the pending look (check circle outlined in accent) and the popover closes immediately with S17; the Audio control shows the pending look until Android reports the new route (within 500 ms). If the report does not change within 1 s the control returns to the previous route and the Notice pill says "Couldn't switch to {name}" (2 s). A tap on the current row just closes the popover.

**Live updates (FR-41).** The list is a live snapshot, coalesced to one update per frame. While open: a new device fades in at the top (S18); a lost device dims in place with "Disconnected" and is removed on the next open; if the current route is lost the check moves to the fallback Android reports and the control follows. While closed the control icon and caption update and, for an arriving device, the accent dot appears. No row ever moves under the finger.

**States.**

| State | Treatment |
| --- | --- |
| Loading | Never shown: the first snapshot arrives with the call; until then the control is the plain Speaker toggle |
| No devices | Rows Phone and Speaker, plus a disabled hint row "No Bluetooth device connected" with `LucideBluetooth` in tertiary-label |
| Bluetooth permission missing (`BLUETOOTH_CONNECT`) | Device rows read "Bluetooth device 1" etc.; a top row "Show device names" (accent) asks once per session, then opens the app's permission page after two denials; selection works regardless |
| Wired headset | Row 2 appears; if it is the current route the Audio icon is `LucideHeadphones` |
| Hearing aid | A Bluetooth row; no special treatment unless the name matches, then `LucideEar` and subtitle "Hearing aid" |
| Error | "Couldn't switch to {name}" Notice pill; route unchanged |
| Hold | Control dimmed, popover cannot open; if it was open when Hold began it closes |
| Single route during a call that is not yet connected (Dialing) | Enabled; routing before connect is allowed |
| Emergency call | Enabled, never blocked |
| Reduced motion / transparency | 120 ms fade; opaque material |

**Press and release.** Press on the control: scale 0.97 + lens glow. Rows: press = `fill` tint + scale 0.97 on the raw touch; act on finger-up; dragging more than the tap slop (10 px) or scrolling cancels. Footer rows act on finger-up, close the popover first.

**TalkBack.** Control: "Audio, {route name}. Double tap to choose audio output" (or "Audio, Speaker off. Double tap for speaker" in the two-route case). Popover: announces "Audio output, {n} options"; rows: "{name}, selected" / "{name}. Double tap to switch"; disabled rows are focusable and read "{name}, unavailable". The check change announces "Now using {name}" (polite).

**Signature moment.** S17 Route bloom and handoff (cost M), S18 Device arrival (cost C).

### Multi-call and conference (Epic 15, FR-44 to FR-48, UJ-8)

**In-Call grid.** Fixed slots as the Supersession table lists. Each control's enablement comes from the reported call capabilities (NFR-16):

| Slot | One call | Two calls | Conference | Held |
| --- | --- | --- | --- | --- |
| Mute | on | on | on | dim |
| Keypad | on | on (acts on the active call) | on | dim |
| Audio | on | on | on | dim |
| Add call | on if the call can take another; else dim with reason on long-press | dim (a third call is not offered; reason "Two calls are already in progress") | dim | dim |
| Hold / Swap | Hold (if supported) | **Swap** (icon `LucideArrowUpDown`) | Hold the conference (if supported) | shows Resume |
| Merge | dim ("Merge needs two calls") | on if the capability exists, else dim with reason | dim (shows "Merged" state after merging) | dim |

**Disabled reasons.** Long-press on a dimmed control (450 ms, light haptic) shows its reason in a Notice pill (2 s): for example "Your carrier doesn't allow merging calls". TalkBack reads the reason as the control's hint.

**Add call (FR-44).**
- Tap Add call (finger-up). The **Add call sheet** rises (S16 pattern) at the large detent over the In-Call Screen; the call keeps running and its timer is visible in the Return-to-call-style pill at the top of the sheet ("Ayşe · 2:41", 44 px). The sheet holds a three-segment control: Keypad, Favorites, Contacts (remembered for the session; first use: Favorites).
- *Keypad segment:* the standard 4 x 3 key grid at 72 px keys, number field above, Call button below, same validation and paste rules. Not the In-Call DTMF keypad.
- *Favorites segment:* the tiles (2 columns, no long-press sheet, no reorder); tap places the call.
- *Contacts segment:* search field and list; tap opens the number choice inline (the Number chooser content replaces the list row), then places the call.
- Placing the call: the sheet dismisses on `sheet_dismiss` while S19 Hold-away runs; the first call is held by the network. If placing fails (busy, blocked, no signal) the sheet's pill reads "Call failed: {reason}" and the first call stays active. Back dismisses the sheet; nothing is placed.
- *States:* permission denied (contacts) per State Patterns; empty favorites or contacts per State Patterns; Add call dim states as above; emergency calls cannot be added to (Add call dim with "Not available during an emergency call").

**Two-call view (FR-45).** The in-call zone shows the active block (avatar 72, name 28 px, timer, chip row) and the Held card below it. Timers: both tick once per second (a single repaint region each). Tapping the Held card or Swap (finger-up) swaps with S20 and one medium haptic. A new incoming call shows the Waiting card (below). If the held party hangs up, the Held card fades out (120 ms) and the zone returns to the one-call layout with no travel; if the active party hangs up, the held call stays held and the screen shows it as the Held card with a prominent "Resume" label until the owner taps (Android does not auto-resume): the active block shows "Call ended" for 1 s and then the held party takes the active block, status "On hold" in warning colour.

**Merge and conference (FR-46, FR-47).**
- Merge (finger-up, medium haptic). The grid keeps its place; the Merge control shows the pending look until Android reports the conference. S21 plays only on the report. If it does not report within 2 s, the control returns and a Notice pill says "Couldn't merge calls".
- Conference view: avatar stack (up to 3 overlapped, then "+n"), "Conference" `title3`, one timer (the conference's), and the participant panel. Rows list the children by name; the number is the subtitle. Per-row **Split** and **End** appear enabled only when the conference reports `can split` / `can disconnect` for that participant; otherwise the row is read-only (the icons are not drawn, the row keeps its height).
- Split (finger-up): the participant becomes the active private call, the rest of the conference is held (Android behaviour), S22. End (finger-up): disconnects that participant (no confirmation, ends one party only); S22 fade.
- Carrier hides participants: the panel shows one row "Conference" with the count if known ("3 people") and no per-row actions; the End button for the whole conference stays (`[?]` IMS children on HyperOS).
- When the conference drops to one participant, Android demotes it to a normal call: the zone returns to the one-call layout.
- *Hold on a conference* holds all participants; the avatar stack takes the Holding ring colour.

**Waiting Call card (FR-48).** Replaces the 76 px banner. It falls over the top of the screen (S23) as a flat sheet-glass card 184 px high and **overlays** the zone (the zone dims to 0.85, it does not shrink). Choices (2 x 2 grid of 44 px pills; each acts on finger-up, the first frame it is visible):
- **Hold and answer** (primary, answer colour): the new call is answered, the current call is held; the two-call view follows (S19).
- **End and answer:** the current call ends first, then the new one is answered (what FR-20 did); the footnote states "Ends {name}" under the pill. Medium haptic.
- **Decline:** rejects the new call; the first call is untouched; the card leaves upward.
- **Message:** opens the reply sheet over the call (the call continues); choosing a reply rejects the waiting call with that text (FR-56).
The card never auto-dismisses; it leaves when the caller hangs up (card rises away, a pill says "Missed call from {name}" for 2 s) or a choice is made. When the screen is a conference, only Hold and answer / End and answer / Decline / Message are shown, as Android allows. With only one choice supported by the platform the others are dimmed, not hidden.

**Press feedback.** All pills: press scale 0.97 + tint within one frame. The Held card: whole-card press scale 0.98 (it is wide), release on `release`.

**TalkBack.** "Add call", "Swap calls, {name} on hold", "Merge calls", "Held call: {name}, on hold 1:12. Double tap to swap", conference rows "{name}. Actions: Split, End" (custom actions), Waiting card: "Waiting call from {name}" announced assertively, then focus lands on "Hold and answer".

**Reduced motion.** Cross-fades replace S19 to S23; no pulses.

### Settings (Epic 16, FR-49, FR-71, FR-72, FR-73, UJ-10)

**Entry.** The gear at the trailing edge of every tab's large title (44 px, reserved slot; not on the In-Call or Incoming screens). Tap on finger-up pushes Settings with S24. The Tab Bar fades out while any Settings page is open. Back is the Back button (44 px, top-left), the system Back gesture, or an edge swipe handled by the system. Depth: Settings is one level; sub-pages (Quick replies, Blocked numbers, Speed dial, Backup preview) are a second level; a sheet may open from either; never a page over a sub-page.

**Layout (402 x 874).** Top inset, Back button row (44), `large-title` "Settings", then sections as grouped lists, scrolling. A section appears only when it has something to show (FR-49).

| Section | Rows (title: control) | Shown when |
| --- | --- | --- |
| Calls | Proximity screen-off: toggle (default on); Flip to silence: toggle (default off; a footnote "Face down while ringing"); Vibrate when call connects: toggle; Vibrate when call ends: toggle | always (flip row dim with footnote "Not supported on this phone" if no sensor) |
| Sounds and haptics | Keypad tones: toggle (default = system); Haptics: segmented Off / Light / Normal; Ringtone and sounds: row with `LucideExternalLink` opens the system sound settings | always |
| Display | Sort by: segmented First name / Last name; Name format: segmented First Last / Last, First; a live preview line "Ayşe Yılmaz" updates as the choice changes; Theme: read-only row "Follows system" | always |
| SIM | Default for calls: row with value ("Ask every time" / "SIM 1"), tap opens the system page for changing the default account | two or more call-capable SIMs only; on one SIM the whole section is absent (see UX-Q10) |
| Quick replies | Row "Quick replies" with count, chevron to the editor | always |
| Blocked numbers | Row "Blocked numbers" with count, chevron to the list | always (disabled with explanation if the platform refuses) |
| Speed dial | Row "Speed dial" with count of assigned digits, chevron to the editor | always |
| Favorites | Show Frequent: toggle (default on) | always |
| Data | Export favorites and settings (`LucideUpload`); Import (`LucideDownload`); Delete all call history (danger text) | always |
| System shortcuts | Call forwarding and waiting; Accessibility (TTY and RTT); Default apps; Emergency information (only if the intent resolves); Hearing-aid compatibility (read-only value "Supported" / "Not reported") | per availability |

**Behaviour.**
- Toggles and segments apply immediately (no restart, no Save), act on finger-up of the whole row, and persist locally (NFR-17). The setting that failed to persist reverts with the pill "Couldn't save setting".
- System shortcuts open the system page; if the intent is missing the row opens the app's system details page and the Notice pill says "Opened app settings instead".
- **Quick replies editor (FR-57).** Rows are text fields (max 100 characters, counter appears at 80) with a trailing `LucideGripVertical` handle; drag the handle to reorder (list reflows by transform on `select`, up to 6 rows); a row is cleared with a trailing "Clear" button when focused; "Add reply" row (disabled at 6, with "Up to 6 replies"); "Reset to defaults" (danger text) restores the three defaults after a confirm sheet. The editor autosaves; an empty list hides Message everywhere (FR-57) and the header shows a footnote "With no replies, Message is hidden".
- **Speed dial editor.** A list of digits 2 to 9 (rows 58): the digit in a 36 px key glyph, the assigned contact (name + number) or "Not assigned", trailing "Assign" or "Change". "Assign" opens the Contacts picker sheet (search field + list; number choice inline). A reserved first row `1` reads "Voicemail" and is read-only (FR-66, FR-67). Assigning a digit already used moves it with a confirmation line in the pill "5 moved from Mehmet".
- **Backup (FR-73).** Export opens the system "create file" picker with the name `rovsti-phone-backup-{yyyy-mm-dd}.json`; success pill "Saved to {file name}". Import opens the system "open file" picker; a valid file opens the **Import preview sheet** (large detent): "This will replace: settings (12), favorites order (5), speed dial (3), quick replies (4)." with "Import" (accent) and "Cancel". An invalid or foreign file shows an inline red row "This isn't a Rovsti Phone backup." Import is a replace and cannot be undone; the sheet says so in one line. Contacts, call log and blocked numbers are not included (footnote on the Data section).
- **Delete all call history (FR-70).** Row opens a confirm sheet (medium) "Delete all call history?" with body "This removes every entry from this phone's call log. Missed-call count goes to 0." and the **Hold-to-confirm pill** (S36). Cancel is a text button above. With TalkBack on, the pill is replaced by a normal "Delete everything" button. Success pill "Call history deleted"; Recents refreshes at once. No Undo (the provider cannot restore).

**States.**

| State | Treatment |
| --- | --- |
| Loading | Values appear as soon as the local store loads (under one frame); no skeleton |
| Permission | Delete history and Blocked numbers rows show an inline `LucideTriangleAlert` row "Allow call log access" (pill "Grant access") when the permission is missing |
| Single SIM | SIM section absent |
| Error | Per-row inline text in danger colour below the group |
| Desktop preview | System rows say "Not available on this platform" in tertiary-label and are inert |
| Reduced motion | Page push is a 120 ms fade; toggles jump |

**Press feedback.** Every row presses to 0.97 scale + `fill` tint on the raw touch; toggles use S25; segmented segments press the indicator. Action on finger-up.

**TalkBack.** Toggles: "{title}, on/off. Double tap to toggle" with state; segmented: "{title}: {selected}, {n} options"; rows with chevron: "{title}, {value}. Double tap to open"; the Back button: "Back"; section labels are headings; the sheet confirm "Hold to delete" announces the alternative action.

**Signature moment.** S24 Gear turn and page push, S25 Toggle snap, S36 Hold to delete.

### Dual SIM: picker and badges (Epic 16, FR-50, FR-51)

**Visible only with two or more call-capable SIMs.** On a single SIM nothing here appears: no chooser, no chip, no setting, no layout change (reserved slots stay empty).

- **When Android asks (system default is "Ask every time").** The SIM chooser sheet rises over the Keypad (or the origin tab) at the medium detent the moment Call is tapped (S3 does not play yet). Rows are the accounts; finger-up on a row places the call with that account and plays S26. Cancel by dragging down, tapping outside or Back: the call is **not** placed and the number stays in the field. The call is never stuck: if the chooser stays open with no choice the app does nothing, and no call exists until a choice is made.
- **Long-press on a Call button** (Keypad, Recents row call action, Contact Sheet number's call circle, Number chooser): opens the same chooser even when a default exists; the default row carries the "Default" caption. Light haptic at 450 ms.
- **SIM chips.** In-Call chip row (SIM chip first) and each Recents row (trailing end of the subtitle line, 36 px reserved; empty for entries whose account no longer exists, or when the list has fewer than two SIMs). The chip text is "SIM 1"/"SIM 2" or the carrier label; the dot is the account colour. TalkBack: "Call on SIM 1, {carrier}".
- **Press feedback.** Rows press to 0.97 on the raw touch; act on finger-up; chips are not interactive.
- **States.** Error from the platform (account unavailable): the sheet row dims with "Unavailable"; no accounts: the chooser never opens. Emergency numbers never show the chooser (FR-68).
- **Signature moment.** S26 SIM chip flight (cost C).

### Blocking (Epic 17, FR-53 to FR-55, UJ-9)

**Where Block appears.** The Recents row menu, Call details action strip, Contact Sheet and number sheet action strip (per number when a contact has several: the tile opens the Number chooser first), the Incoming Call Screen after a call ends (post-call row, below), and the Blocked numbers list's Add field.

**Recents row menu.** Long-press (450 ms, light haptic) on a Recents row opens a **context menu** (glass sheet-class, 14 radius, 220 px wide, rows 48) anchored beside the row with the page behind dimmed 0.85: Call details, Copy number, Message, Block number (or Unblock), Delete entry (danger). Anchored so it never covers the pressed row. Tapping a row finger-up acts; tapping outside dismisses.

**Block flow.** Block (finger-up) runs S27 and writes at once (no confirmation dialog: reversible). The Notice pill reads "Blocked {number or name}" with **Undo** for 5 s (10 s while TalkBack is on). Undo unblocks and restores the Recents row. The row disappears from Recents (blocked numbers stay out, FR-55). From the Incoming Call Screen after a call ended the pill is shown over the tabs. If the platform refuses blocking (`canCurrentUserBlockNumbers` false) every Block action is **disabled, not hidden**, with the subtitle "Blocking isn't available for this user".

**Blocked numbers list** (Settings > Blocked numbers; second-level page). Large title "Blocked numbers"; segmented control "Numbers | Blocked calls"; Numbers view: add field at the top then a virtualised list.
- *Add:* the field uses dial-string rules (FR-9); invalid input gives the inline footnote "Not a valid number". "Block" (accent) acts on finger-up, the number appears at the top with a fade, the pill says "Blocked {number}".
- *Row:* `LucideBan` tile, name if the number matches a Contact (`headline`) else the formatted number, subtitle the number or "Blocked number"; trailing "Unblock" glass pill (finger-up, no confirmation, pill "Unblocked {number}" with "Undo" 5 s).
- *Blocked calls view:* rows of blocked incoming calls: number or name, time (`footnote`), and the reason if the log has one (subtitle "Blocked by you" / "Blocked by the system"; absent if unknown). No call action on these rows; tapping opens Call details for that number.
- *Empty:* icon `LucideBan` in tertiary-label, "No blocked numbers" with body "Block spam from Recents or by typing a number above."; for Blocked calls "No blocked calls". *Permission denied:* the call-log pattern. *HyperOS separate list* `[?]`: a footnote at the bottom "Xiaomi's own block list may be separate" (shown only if the story proves it).
- *Incoming-blocked indication.* A blocked caller never rings, so there is no screen. The evidence is (a) the Blocked calls view, (b) a `Blocked` chip in the Call details header of a blocked number, (c) a `LucideBan` glyph (14, secondary-label) after the number in the Contact Sheet number row for blocked numbers. No badge and no notification (FR-55: blocked entries stay out of the Missed Call Badge).

**Press feedback.** Rows 0.97 scale + `fill` tint on touch; "Unblock" pill 0.94; all act on finger-up. **TalkBack:** "Block {number}. Double tap to block"; Undo pill: assertive "Blocked {number}. Undo", focus on Undo; rows "{number}. Blocked. Actions: Unblock".

**States.** Loading: skeleton rows if over 150 ms; error: inline "Couldn't read blocked numbers" with pill "Try again"; platform refuses: banner row at the top "Blocking isn't available for this user" and the Add field disabled; reduced motion: no S27 sweep.

**Signature moment.** S27 Block sweep, S28 Notice pill.

### Incoming call controls (Epic 18, FR-56 to FR-59, UJ-3, UJ-9)

**Layout, top to bottom (402 x 874; no glass at rest, 0 refractive surfaces).** Top inset, 80 gap; label; avatar 120 with aura and rings; name; number; flexible space; *secondary row* (Message left, Silence right, 56 px flat circles, 24 apart, captions); 24 gap; Decline and Answer (76, 96 apart, captions); bottom inset + 48. The Message and Silence slots are reserved even when one is hidden (the other keeps its position).

**Gestures (FR-59), on the card (avatar, name, number).** Direct drag with a 0.9 gain: right to answer, left to decline, up for the reply sheet. Commit distance 96 px (or a fling above 600 px/s in that direction). Haptic tick at 50%, medium at commit (S29). A short drag springs back with no action. The buttons keep working as taps on finger-up; the gesture and the buttons never conflict because the buttons do not move. On first use (first 3 incoming calls, a local counter) a `footnote` hint under the number reads "Drag the card to answer or decline". Reduced motion: no follow effect, but a drag past 96 px commits all the same.

**Message (FR-56).** Tap Message, or drag up, or the heads-up notification's Message action. The **reply sheet** (medium detent) lists the quick replies; finger-up on a row rejects the call with that text (S31). The result is never silent: the card label becomes "Sent" or "Opened Messages" for 1.2 s. If the SMS path needs a permission (the spike), the sheet shows a top row "Allow sending texts" (accent) until granted; until then a reply opens Messages with the text prefilled and the call is still rejected. "Write your own…" rejects the call and opens Messages with an empty body. The sheet is not draggable to dismiss while ringing without declining: dismiss returns to the ringing card (the call keeps ringing). When there are no replies the Message control is hidden-but-reserved (a blank slot) and the Waiting card pill is dimmed with "Add replies in Settings".

**Silence (FR-58).** Tap Silence (finger-up): the ringer stops via the platform path; the control becomes "Silenced" (accent tint, disabled); the Call remains answerable, rings out as missed if untouched. Flip to silence (Settings): on face-down while ringing, same result plus a medium haptic; the caption reads "Silenced by flip"; a lift does not un-silence. If the platform refuses silencing the control is hidden-but-reserved and the volume key remains (FR-21).

**No Block while ringing.** Block is deliberately not offered on the ringing screen, so a mistap cannot confuse Decline and Block. The path is Recents (row menu) or Call details. A caller who is blocked later never rings at all.

**Heads-up notification.** Actions: Answer, Decline, Message (three, Android's limit for call style). Message opens the Incoming Call Screen with the reply sheet already open.

**Press feedback.** Answer, Decline (existing). Message, Silence: scale 0.94 + lens glow on the raw touch; act on finger-up. Reply rows 0.97 + tint.

**TalkBack.** TalkBack users do not need the gestures; focus order is Answer, Decline, Message, Silence. Labels: "Message. Double tap to decline with a text", "Silence ringer", "Silenced". Reply rows: "{text}. Double tap to decline and send". The sheet announces "Decline with message, {n} replies".

**States.** Loading: none. No replies: Message hidden-but-reserved. Silenced: described. Permission (SMS): row in the sheet. Platform refuses silence: hidden-but-reserved. Reduced motion: S29 follow effect off; S30, S31 fades.

**Signature moment.** S29 Incoming slide, S30 Silence hush, S31 Reply sent.

### Contacts management and quick actions (Epic 19, FR-60 to FR-64)

**Create and edit (FR-60).** Decision (PRD Q9 default): **system editor hand-off**; no in-app editor in v0.2 unless FR-62 is built (P3, spec below). "Add to contacts" appears on: Recents row menu, Call details (below the strip), the number sheet, and the Keypad (a text button "Add to contacts" in the status line slot, shown when the field holds a valid number with no match). Finger-up launches the system insert/edit screen with the number prefilled (the user chooses create new or add to existing there). On return the app refreshes within 2 s; the originating row or sheet cross-fades to the new name; if nothing changed, nothing happens. If the intent is missing: Notice pill "No contacts app found".

**Contact Sheet, v0.2.**
- Top: avatar 80, name `title2`, star toggle (existing).
- **Action strip** (five 64 px tiles): **Message**, **Copy**, **Share**, **Block**, **Details**. Message opens the default messaging app addressed to the contact's default number (number chooser when several, no default); Copy copies the default number (chooser when several) and shows "Copied"; Share sends the contact's number as text through the system share sheet; Block runs the Block flow; Details pushes Call details for the chosen number. Tiles press 0.94 on touch, act on finger-up, stagger in with S16.
- Numbers group (existing) plus: long-press a number (450 ms) copies it with a light haptic and the "Copied" pill (FR-63). The call circle and the whole row still call.
- **Speed dial** row: "Speed dial: 5" or "Speed dial: Not assigned", tap opens the digit picker (2 to 9) inline (a segmented row of eight 36 px key glyphs).
- **Ringtone** row: "Ringtone: Default", tap opens the system ringtone picker; stored on the contact. If `WRITE_CONTACTS` is missing the row is dimmed with "Allow contacts editing" and a "Grant access" pill. If the story proves this is infeasible `[?]` the row is absent (flagged).
- "Edit in Contacts" (existing) and, last, **Delete contact** (FR-61), danger text.

**Delete (FR-61).** Tap "Delete contact" (finger-up): the row morphs in place (`morph`, 58 to 112 px, sheet content scrolls if needed) into a confirm: "Delete {name}?" body "This removes the contact from this phone and all apps." with Cancel (glass) and Delete (danger). Delete (finger-up, medium haptic): the provider delete runs, the sheet dismisses, the contact leaves Contacts and Favorites immediately (rows fade, list re-lays instantly), the pill says "Deleted {name}" (3 s, **no Undo**: restoring a contact is not guaranteed). Error: inline "Couldn't delete {name}" in the confirm row; the contact stays. Permission: dimmed with "Allow contacts editing".

**Number sheet, v0.2.** Used for an unsaved number from the Keypad or a Details shortcut: number `title2`, the action strip (Message, Copy, Share, Block, Details), then the two pills "Call" and "Add to contacts".

**In-app editor (FR-62, P3 stretch, spec only if built).** Sheet at the large detent, "Edit contact": name field, number rows (label segmented Mobile / Home / Work / Other, number field, remove button), "Add number", Save (accent) and Cancel. Only for contacts with one writable raw contact; others use the system editor without saying why beyond "Edit in Contacts". Save failures show the provider's reason inline; the contact is unchanged.

**Favorites reorder and Frequent (FR-64).**
- *Reorder.* Long-press (450 ms) on a tile lifts it (S34, medium haptic). If the finger moves more than 12 px it enters reorder (tile follows the finger; neighbours slide by transform), and the Contact Sheet does **not** open. Lifting the finger commits the order locally (keyed by contact); if the finger never moved, the Contact Sheet opens on finger-up as before. Dragging near the top or bottom edge auto-scrolls. TalkBack gets custom actions "Move earlier" and "Move later" on each tile (no drag needed) and announces the new position. Newly starred contacts append at the end; unstarred ones leave.
- *Frequent.* Below the grid, a `footnote` section label "Frequent" and one row of up to four flat avatar columns (avatar 56, name `footnote`, no glass): the most-called non-starred contacts in the last 90 days `[ASSUMPTION: window]`. Tap opens the Contact Sheet (no accidental call); long-press does nothing extra. Hidden when fewer than 2 qualify, or when the setting is off. No reflow when data changes while the tab is open (refreshes on the next open).
- *States:* empty Favorites per State Patterns (Frequent can still show below the empty state if present: then the empty state takes the grid slot and Frequent follows).

**Press, haptics, TalkBack.** Tiles in the action strip 0.94 on touch; labels "Message {name}", "Copy number", "Share contact", "Block {name}", "Call details". Delete: "Delete contact, double tap" then "Delete {name}? Delete / Cancel". **Reduced motion:** morph becomes a cross-fade; reorder tiles jump.

### Keypad intelligence (Epic 20, FR-65 to FR-68, UJ-6, UJ-10)

**T9 results (FR-65).** The T9 strip occupies the **large-title slot** of the Keypad (S32), so the Keypad layout does not change: the number field, status line, keys and Call row keep their positions. When the field is empty the title "Keypad" shows; with a first digit, the best match appears in the title slot (cross-fade with a 12 px slide) when at least one contact matches by number or by T9 letters; with no match the title stays. If the Setup card is above (it sits under the title), nothing moves either. On short screens (under 700 dp) where the title is hidden, the strip uses the same 56 px that the hidden title would have occupied (reserved only while the field is non-empty).
- *Best match row:* avatar, name with the matching letters in weight 700, matched number (the number that matched, or the default), trailing 36 px call circle. Tapping the row (finger-up) **fills the number field** with that contact's number; the call circle calls immediately. The status line under the field keeps its job (the contact name when the number matches exactly, FR-12).
- *More matches:* a "+n" capsule opens the **Results sheet** (medium detent, up to 50 rows, virtualised). Rows tap to fill; the trailing call circle calls.
- *Ranking:* exact number match first, then T9 name prefix, then word-start, then number substring; ties by call frequency, then name. (Ranking is in `rovsti_core`; the UX only requires that ranking is stable while typing.)
- *Latency:* results update within one frame of the digit (NFR-18); a stale result is dropped; a digit is never delayed.
- *States:* contacts permission denied: no strip, the status line does not change, and a one-time `footnote` row under the field "Allow Contacts to see matches" with pill "Grant access" (first use only per session); no matches: nothing shown; index building (first launch with 2,000+ contacts): no strip, no spinner; the feature has no setting (always on).
- *Cut line:* if a T9 miss for the first digit would show the matched number rather than a name, the row shows the number only (no avatar).

**Speed dial (FR-66).** Assigned digits 2 to 9 show a 14 px avatar dot on the key (top-right). On an **empty** field, holding an assigned key charges its ring over 450 ms (S33); at completion the digit is removed and the call is placed through the one call path (SIM chooser applies if Android asks). Early release leaves the digit typed. On a non-empty field the long-press does nothing special (a number being typed is not interrupted). Pressing a key that has no assignment for 450 ms shows the status-line text "No speed dial for {digit}. Set it in Settings." for 2 s (light haptic), with the words "Settings" tappable. Assignments are made in Settings > Speed dial and from the Contact Sheet.

**Voicemail (FR-67).** Long-press `1` on an empty field calls voicemail of the default SIM (the chooser if dual-SIM asks); the key shows `LucideVoicemail` in place of an empty caption. If no voicemail number is configured: the status line reads "No voicemail number set" with a tappable "Open call settings" for 3 s. Recents shows the voicemail type icon (`LucideVoicemail`) on entries of voicemail type. **No unread badge** is drawn unless the story proves a local signal exists (open question UX-Q12); if proven, the badge is an 8 px accent dot on the `1` key (never a number).

**Emergency (FR-68).** When the entered string is an emergency number for the current country, the Call button gets the caption "Emergency call" below it, the status line reads "Emergency call" in danger colour, and the S3 bloom becomes an instant swap (no ring, no 300 ms hold). The SIM chooser, blocked/setup checks, speed-dial hold and T9 strip are bypassed for it. On the In-Call Screen of an emergency Call, Add call and Merge are dimmed and Block is not offered (reason on long-press "Not available during an emergency call"). Emergency information in Settings only if the intent resolves.

**Press feedback.** Keys as before. The T9 row and "+n" capsule: 0.97 on touch, act on finger-up. The call circle in the strip: 0.94 on touch, calls on finger-up (calls on finger-up because it is a call-causing, mistake-prone control; keys remain on finger-down).

**TalkBack.** Strip: "Best match: {name}, {number}. Double tap to fill the number. Call button: Call {name}". "+n": "{n} more matches". Speed-dial keys: "5, speed dial {name}. Long press to call" (hint). Voicemail key: "1, voicemail. Long press to call voicemail". Emergency: "Emergency call".

**Reduced motion.** S32 becomes a cross-fade, S33 a static ring at completion. **Signature moment.** S32 Results morph, S33 Speed-dial charge.

### Call details (Epic 21, FR-52, FR-69, FR-70, UJ-4, UJ-9)

**Entry.** Info control on a Recents row (replaces the number sheet when no Contact matches), "Details" tile in the Contact Sheet or number sheet strip, Blocked calls rows, row menu "Call details". A pushed full page (no Tab Bar), S35, Back button and system Back leave it; the sheet that opened it dismisses first.

**Layout (402 x 874, scrolling).**
1. Top inset; Back button row (44).
2. Header: avatar 96, name `title2` (or the formatted number), number `subheadline` secondary-label when named, chip row (Blocked, Contact).
3. Action strip, five tiles: **Call** (answer-colour icon, long-press = SIM chooser), **Message**, **Copy**, **Share**, **Block** (or **Unblock**).
4. Group: **Add to contacts** (or **Edit in Contacts** when matched), **Delete history**: opens a confirm sheet "Delete calls with {name}?" with Cancel and Delete (danger); success pill "Deleted {n} calls".
5. Totals card: "{n} calls", "{h} h {m} min talk time", "Last call {relative}".
6. History: sticky date headers and rows (see Components). Missed rows in danger colour. Each row is inert except for the whole-row **Call** (finger-up) which calls the number; long-press the row for its menu (Delete entry, Copy number).
7. Bottom inset + 24.

**Behaviour.** The history loads off-thread, virtualised; rows fade in. Refreshes after a call ends while open (new row at the top, fade only, no scroll jump). Durations: `m:ss` or `h:mm:ss`; Missed and Declined have no duration. Quality chips (FR-52) show HD, Wi-Fi and VoLTE where the log recorded them; absent chips leave nothing. SIM chips only with two SIMs. **Delete entry** (row menu, FR-70): removes one entry immediately, fade then instant re-layout, pill "Deleted" with "Undo" for 5 s (re-inserts it with the same fields; if the provider refuses, the pill says "Couldn't restore"). Deleting all entries of the number returns to the previous screen with the pill.

**States.**

| State | Treatment |
| --- | --- |
| Loading | Header immediately; skeleton history rows after 150 ms |
| Empty history (after delete) | Icon `LucideClock`, "No calls with this number" |
| Permission (call log) | The call-log denied pattern in the history area; header and actions still work |
| Blocked number | Chip "Blocked" (danger outline) and the strip shows Unblock |
| Unknown / private number | Header "Unknown"; Call and Block dimmed ("Unknown number, cannot call back") |
| Error | Inline "Couldn't load history" with "Try again" |
| Reduced motion | S35 becomes a fade |

**Press feedback / TalkBack.** Tiles 0.94 on touch; rows 0.97 + tint; all on finger-up. Rows: "{type}, {day} {time}, {duration}, {SIM}, {HD}. Double tap to call". The page title is a heading.

**Live quality chips on the In-Call Screen (FR-52).** HD and Wi-Fi chips in the reserved chip row appear when Android reports the properties and vanish when it stops; they fade 120 ms (no layout change).

### Updated flows in existing tabs

- **Recents (FR-51, FR-53, FR-67, FR-69, FR-70).** Row: SIM chip at the end of the subtitle line (dual SIM only), voicemail icon for voicemail entries, long-press opens the row menu, info opens Call details; blocked numbers do not appear; delete is by menu (no swipe).
- **Contacts (FR-60 to FR-63).** Contact Sheet per above; an in-title "New contact" is **not** added (a plus button would crowd the title row: the title row's only trailing control is the gear); creating from scratch is "Add to contacts" with an empty number from the Keypad, or the system Contacts app. Flag UX-Q11.
- **Favorites (FR-64).** Reorder and Frequent per above.
- **Keypad (FR-65 to FR-68).** Strip, speed dial, voicemail, emergency per above; the title-row gear is the only trailing control.
- **Tab titles.** The trailing 44 px slot of each large title carries the gear; titles shrink by 56 px of available width only.

### Notice pill (all v0.2 surfaces)

The single transient-message surface (visual spec in DESIGN.md v0.2 components, S28).
- **Position.** Overlay, 12 px above the Tab Bar (or 12 px above the bottom inset on pages and sheets without it); it never pushes content and never covers the Tab Bar. Over the In-Call Screen it sits 12 px above the End button.
- **Lifetime.** Info 2 s, Undo 5 s (10 s while TalkBack is running). One at a time; a new notice replaces the old in place (the old one's Undo is committed, not run). Pausing: while a finger is on the pill the timer is held.
- **Undo** acts on finger-up, rewinds the hairline, performs the inverse action, and shows the result for 1 s.
- **Uses (complete list):** Blocked / Unblocked, Deleted entry, Deleted contact, Deleted all history, Copied, Sent / Opened Messages, Saved to {file}, Couldn't switch to {device}, Couldn't merge calls, Couldn't save setting, disabled-control reasons, Opened app settings instead.
- **Accessibility.** A polite live region; the Undo button is a normal focus stop. It never carries information that is not also available elsewhere (Blocked shows in the blocked list, Sent shows on the card).
- **Reduced motion.** Fade only, no hairline.

## v0.2 State Patterns (additions)

| State | Surface | Treatment |
| --- | --- | --- |
| Capability unsupported | Any In-Call control | Dimmed to 0.4, place kept; long-press shows the reason in a Notice pill; TalkBack hint carries the same text |
| Platform refuses | Block, Silence, Merge, Ringtone | Control disabled (or hidden-but-reserved for Silence and Message on the Incoming screen) with an explanation; never an error dialog |
| Pending (awaiting reported state) | Audio route, Merge, Mute, Speaker, Hold | Press look held for up to 1 s; reverts if unreported; no spinner |
| No devices | Route popover | Phone and Speaker plus a disabled "No Bluetooth device connected" row |
| Single SIM | Chooser, chips, Settings SIM | Absent (reserved slots empty) |
| Dual SIM, account missing | Recents row | No chip |
| Permission: Bluetooth | Route list | Names generic, "Show device names" row |
| Permission: contacts editing | Delete contact, ringtone | Dimmed with "Allow contacts editing" and "Grant access" |
| Permission: SMS (if the spike requires it) | Reply sheet | "Allow sending texts" row |
| Empty quick replies | Incoming, Waiting card | Message hidden-but-reserved / dimmed with "Add replies in Settings" |
| No voicemail number | Keypad `1` | Status line "No voicemail number set" + "Open call settings" |
| Speed dial unassigned | Keypad | Status line "No speed dial for {digit}. Set it in Settings." |
| Emergency Call | Keypad, In-Call | Instant path, caption "Emergency call", Add call / Merge / Block dimmed |
| Blocked number | Call details, Contact Sheet | Chip and `LucideBan` glyph; strip shows Unblock |
| Blocking unavailable | Block actions, Blocked list | Disabled with "Blocking isn't available for this user" |
| Backup invalid file | Import | Inline "This isn't a Rovsti Phone backup." |
| Backup success | Export / Import | "Saved to {file}" / sheet then "Imported" pill |
| Desktop preview | Android-only actions | "Not available on this platform" in tertiary-label |
| Degradation | Any | S18 dot, S21 pulse, S24 rotation, S29 wash, S34 displacement, S36 sweep stop first |

## v0.2 Interaction Primitives (amendments)

- **Long-press** (450 ms, light haptic) now appears in these places and no others: `0` (plus), `1` (voicemail), assigned digits 2 to 9 (speed dial, empty field only), Delete (clear), the Call button (SIM chooser, dual SIM only), Favorites tile (Contact Sheet, or reorder if the finger moves), Recents row (row menu), a number in a sheet (copy), a dimmed In-Call control (reason), the Audio control (route list), a Details history row (row menu).
- **Drag:** Incoming card (S29), Favorites tile reorder (S34), Quick reply handle, sheets (existing). Only these.
- **Act on finger-up**, press feedback on raw finger-down, for every control added in v0.2. Keys and in-call toggles keep acting on finger-down.
- **Tap guard.** A tap is cancelled if the finger moves more than 10 px or the list scrolled (the built `TapGuard`); a cancelled tap releases the control without acting.
- **Back order (extended):** Notice pill is ignored, then popover / context menu, then sheet, then search focus, then In-Call Keypad, then the top page (Settings sub-page, Settings, Call details), then tabs do nothing. On the In-Call Screen with the route popover or Add call sheet open, Back closes the overlay first. On the Incoming screen with the reply sheet open, Back closes the sheet and keeps ringing.
- **Banned (updated):** swipe-to-delete rows (still), pull-to-refresh, carousels, engagement badges, app-played sounds, looping animations other than the aura and sonar rings, toasts other than the Notice pill. Swipe-to-answer is *replaced* by the drag gestures of FR-59, which are additive to the buttons.

## v0.2 Accessibility Floor (additions)

- Every new control: label, role, state; touch targets at least 44 x 44 (route rows 52, tiles 64, chips non-interactive); at least 8 px between adjacent targets (the action strip is 12).
- **Font scale 1.3x:** the Audio caption stays one line with ellipsis (the full name is in the TalkBack label and in the popover); route rows and Settings rows grow in height and wrap titles to two lines; the action strip tiles keep 64 px and their labels wrap to two lines inside the tile (tile height grows to 76 only at scale 1.3 and above); the Waiting card pills stay two columns and labels wrap to two lines; the in-call zone scrolls.
- **TalkBack custom actions** replace every gesture and drag: Incoming card (Answer, Decline, Message are the buttons), tile reorder (Move earlier, Move later), quick reply reorder (Move up, Move down), conference rows (Split, End), Recents rows (Call details, Block, Delete, Copy via the actions menu), speed-dial keys (Call speed dial).
- **Never colour alone:** SIM chips carry text; Block state carries a glyph and word; selected route carries a check; the Held card carries text and the pause ring.
- **Announcements:** route changes (polite), conference joins and leaves (polite, "Özgür left the conference"), Waiting call (assertive), merge result (polite), silenced (polite), notices (polite).
- **Focus:** a popover or sheet takes focus on open and returns it to the opener on close; the Waiting card takes focus on the first pill.
- **Time limits:** the Undo window extends to 10 s with TalkBack; hold-to-delete is replaced by a button; the speed-dial hold has no TalkBack equivalent need (custom action).
- **Reduced motion / transparency:** per surface above; every v0.2 moment has the stated fallback.

## v0.2 Voice and Tone (strings)

Labels added: Audio, Add call, Swap, Merge, Split, Hold and answer, End and answer, Message, Silence, Silenced, Block, Unblock, Undo, Copy, Share, Details, Delete, Export, Import, Reset, Add reply, Assign, Change.

| Context | Copy |
| --- | --- |
| Route list header (TalkBack only) | "Audio output" |
| No devices | "No Bluetooth device connected" |
| Names hidden | "Show device names" |
| Switch failed | "Couldn't switch to {name}" |
| Merge failed | "Couldn't merge calls" |
| Merge disabled reason | "Your carrier doesn't allow merging calls" / "Merge needs two calls" |
| Add call disabled reason | "Two calls are already in progress" / "Not available during an emergency call" |
| Held card | "On hold · {m:ss}" |
| Conference | "Conference", "{n} people" |
| Waiting card | "Waiting: {name}"; footnote under End and answer: "Ends {name}" |
| Missed while waiting | "Missed call from {name}" |
| Blocked | "Blocked {number}" + Undo; "Unblocked {number}" |
| Blocking unavailable | "Blocking isn't available for this user" |
| Empty blocked | "No blocked numbers" / "Block spam from Recents or by typing a number above." / "No blocked calls" |
| Reply sheet | "Decline with message"; default replies: "Can't talk now. I'll call you back.", "I'm in a meeting. Call you later.", "Can you text me instead?" |
| Reply result | "Sent" / "Opened Messages" |
| Silenced | "Silenced" / "Silenced by flip" |
| Gesture hint | "Drag the card to answer or decline" |
| SIM chooser | "Call with", "Default", "Change default SIM" |
| Copied | "Copied" |
| Contact delete | "Delete {name}?" / "This removes the contact from this phone and all apps." / "Deleted {name}" |
| History delete | "Delete all call history?" / "This removes every entry from this phone's call log. Missed-call count goes to 0." / "Hold to delete everything" / "Call history deleted" |
| Backup | "Saved to {file}" / "This will replace: settings, favorites order, speed dial, quick replies." / "This isn't a Rovsti Phone backup." |
| Speed dial | "No speed dial for {digit}. Set it in Settings." / "{digit} moved from {name}" |
| Voicemail | "No voicemail number set" / "Open call settings" |
| T9 | "Allow Contacts to see matches" |
| Emergency | "Emergency call" |
| Frequent | "Frequent" |
| Settings footnotes | "Face down while ringing" / "With no replies, Message is hidden" / "Up to 6 replies" / "Not supported on this phone" |

## v0.2 Information architecture (additions)

| Surface | Reached from | Purpose | FRs |
| --- | --- | --- | --- |
| Settings page | Gear in every tab title | All settings, SIM, replies, blocked, speed dial, data, shortcuts | FR-49, 71, 72, 73 |
| Quick replies editor | Settings > Quick replies | Edit the six replies | FR-57 |
| Blocked numbers page | Settings > Blocked numbers; Details "Blocked" chip | Manage blocked numbers and review blocked calls | FR-54, 55 |
| Speed dial editor | Settings > Speed dial; Contact Sheet speed dial row | Assign digits 2 to 9 | FR-66 |
| Import preview sheet | Settings > Import | Review what a backup replaces | FR-73 |
| Route popover | In-Call Audio control | Pick an audio endpoint | FR-41 to 43 |
| Add call sheet | In-Call Add call | Keypad, Favorites, Contacts over the call | FR-44 |
| Held card, Conference panel | In-Call Screen (zone) | Two calls, conference | FR-45 to 47 |
| Waiting card | A second Call rings during a Call | Hold and answer, End and answer, Decline, Message | FR-48 |
| Reply sheet | Incoming screen Message / drag up / Waiting card Message / heads-up action | Reject with a text | FR-56 |
| Silence control | Incoming screen | Silence the ringer | FR-58 |
| SIM chooser | Call tap when Android asks; Call long-press | Choose the SIM | FR-50 |
| Row menu | Recents row long-press | Call details, Copy, Message, Block, Delete | FR-53, 63, 70 |
| Call details page | Recents info; Contact Sheet / number sheet Details; Blocked calls rows | Per-number history and actions | FR-52, 69, 70 |
| Delete history confirm sheet | Call details; Settings | Confirm deletion | FR-70 |
| T9 strip, Results sheet | Keypad | Name matches while typing | FR-65 |
| Notice pill | Overlay on any screen | Short results, Undo | FR-53 and others |
| System hand-offs (added) | Contacts insert / edit, messaging, ringtone picker, share sheet, file picker, Bluetooth and accessibility settings, call settings, default SIM page | Outside the app | FR-43, 49, 60, 63, 73 |

**Navigation model (replaces the MVP paragraph).** One Tab Bar (Favorites, Recents, Contacts, Keypad). The gear in each tab title opens Settings, a pushed full-height page with at most one sub-page deeper; Call details is a pushed page from a tab. Overlays (popover, context menu, Notice pill) sit above any screen. Sheets are one level deep and are never opened over another sheet or over a page that was itself reached from a sheet (a sheet's page action dismisses the sheet first). The In-Call and Incoming screens stay full-screen with no Tab Bar; the Route popover, Add call sheet, Waiting card and Reply sheet are the only things that open over them.

## Key Flows

### Flow 1: First launch and setup (Erdem, newly sideloaded, UJ-1)

1. He opens Rovsti Phone for the first time; it opens on the Keypad tab with the Setup Checklist expanded: 0 of 9 done on his HyperOS phone.
2. He taps "Set up" on Default phone app; the system dialog appears; he accepts. The item flips to done, and Phone and Contacts permission items flip to done without prompts (FR-5).
3. He taps "Set up" on Notifications, grants it. He opens Full-screen notifications from its action, enables it, returns; it is done after the resume re-check.
4. Autostart: "Open settings"; the Xiaomi page opens; he turns the switch on; he comes back; "Confirm" is now enabled; he taps it.
5. He does the same for Battery saver (AOSP dialog first, then the Xiaomi page), Show on lock screen, and Background pop-ups.
6. **Climax:** the ninth item turns done and the card morphs into a small "Setup complete" row; the keypad moves up with a spring.
7. Resolution: Rovsti Phone is his dialer.

Failure: if MIUI does not show the role dialog, the item expands to show the Settings path and an "Open default apps" button. If a Xiaomi page does not exist, the inline note explains where to look and he still taps Confirm after checking. After a HyperOS update revokes Autostart, the next launch re-opens the card with only that item (header "Something changed: Autostart").

### Flow 2: Call his sister from Favorites (Erdem, at his desk, UJ-2)

1. He opens the app (it remembers Favorites as the last tab).
2. He taps his sister's tile. It presses in with a springy release; the call is placed.
3. The In-Call Screen appears: her name, "Calling…", the End button.
4. She answers; the status becomes the timer 0:01 and counting.
5. He taps Speaker; the control shows its on-state; later he taps Mute, then End.
6. **Climax:** "Call ended" for about a second, then the screen fades out and Favorites is back under his thumb.
7. Resolution: he opens Recents; her outgoing call is at the top, within 2 s of ending.

Failure: if the Call fails ("Number not reachable"), the status says so for 2 s; he is back on Favorites.

### Flow 3: A call comes in while the phone is locked (Erdem, in a taxi, UJ-3)

1. The phone is in his pocket; it rings via the system ringer and the screen turns on over the lock screen, showing the Incoming Call Screen: "Incoming call", a contact's name, Decline and Answer.
2. He takes it out and taps Answer without unlocking.
3. **Climax:** the Answer circle morphs into the In-Call Screen and the timer starts; the call has been connected one tap after the ring.
4. He talks; the proximity sensor blanks the screen at his ear.
5. The other party hangs up; "Call ended" shows briefly and the lock screen returns.
6. Resolution: the Call is in Recents.

Variants: unlocked and using another app: a heads-up notification with Answer/Decline appears and the call is answered from it (FR-23). Silent mode: the screen or heads-up still appears with no sound. Cold start: the notification is visible first; the full screen follows within 3 s.

### Flow 4: Call back a missed number (Erdem, morning, UJ-4)

1. He sees the Recents tab icon with a red "2".
2. He taps Recents; the page fades in; one red row "Mehmet (2)" with the missed icon and "yesterday" at the top.
3. He taps the row.
4. **Climax:** the call is placed and the In-Call Screen shows "Calling…".
5. When the call ends, he lands on Recents; the badge is gone, because he has viewed the tab.

### Flow 5: Look someone up and star them (Erdem, UJ-5)

1. He taps Contacts and the search field; types "ays".
2. The list filters on each keystroke: "Ayşe" stays.
3. He taps her row; the Contact Sheet rises to the medium detent: her numbers with labels, a star toggle, call buttons.
4. He taps the star; it fills with the accent colour and a medium haptic.
5. **Climax:** he taps her mobile number; the call is placed and the In-Call Screen shows.
6. Resolution: Ayşe now appears in Favorites, and the Contacts app shows her starred.

### Flow 6: Dial a support line with the in-call keypad (Erdem, at home, UJ-6)

1. He opens Keypad (it is the default tab on this device after he opens a link, or the intent `tel:` opens it directly with the number filled).
2. He pastes "+90 (555) 111-22-33" through the Paste pill; the field shows "+905551112233".
3. He taps Call; the In-Call Screen appears.
4. The IVR answers; he taps Keypad (the in-call keypad opens) and presses `1`, then `#`; each key press sends one tone and the digits show above the keys.
5. **Climax:** the IVR reacts to the tones.
6. He taps Hide, then End.

### Flow 7: Call waiting (Erdem, on a call when another comes in, UJ-3 variation; v0.1, superseded by the Waiting card and Flow 9)

1. He is on a call; a Waiting Call banner slides down: "Waiting: Ayşe", Decline, Answer.
2. He taps Decline; the banner leaves; his call continues.
3. Alternatively he taps Answer: his first call ends and Ayşe is connected (the banner's footnote said so).

## v0.2 Key Flows

### Flow 8: Move a call to the earbuds (Erdem, walking, UJ-7)

1. He is on a call with Ayşe on the phone speaker. The Audio control reads "Speaker" with the speaker icon. His earbuds connect; a 6 px dot appears on the control.
2. He taps Audio. The popover blooms upward from the control: "Erdem's Buds" (new, with a halo ring), "Car kit", Speaker (checked), Phone, then the shortcuts.
3. He taps "Erdem's Buds". The check flies to the control, the popover closes, the icon and caption change to the Bluetooth glyph and "Erdem's Buds" once Android confirms (under 500 ms).
4. **Climax:** he switches the buds off; without touching anything the control returns to Phone and its caption follows.
5. Resolution: the call never dropped; the layout never moved.

Failure: if Android does not report the switch within 1 s the control returns and the pill says "Couldn't switch to Erdem's Buds".

### Flow 9: A three-way call (Erdem, at his desk, UJ-8)

1. On a call with Ayşe he taps Add call. The Add call sheet rises over the call; his pill at the top says "Ayşe · 2:41".
2. He picks Favorites and taps Özgür. The sheet drops away as Ayşe's avatar shrinks into the Held card and Özgür's avatar drops in with a bounce ("Calling…").
3. Özgür answers. He taps Swap once, checks Ayşe, taps the Held card to swap back.
4. He taps Merge. The two avatars slide together into a stack, the colours mix in the aura, and the participant list rises.
5. **Climax:** he taps End on Özgür's row; the row slides away and the call continues with Ayşe, one-call layout again.
6. He taps End.

Variants: carrier refuses Merge: the control stays dim, long-press shows the reason. A third caller rings: the Waiting card drops with the four choices.

### Flow 10: Decline with a message, then block a spammer (Erdem, in a meeting, UJ-9)

1. The phone rings over the lock screen. He drags the card up; the reply sheet follows his finger.
2. He taps "Can't talk now. I'll call you back." **Climax:** the bubble flies to the avatar, the card reads "Sent", the call is rejected and the screen leaves.
3. Later an unknown number rings; he taps Silence; the rings fade and the screen stays answerable. It rings out as missed.
4. In Recents he long-presses that row, taps Block number. The avatar gets the slash sweep, the row fades out, a pill says "Blocked +90 555 123 45 67  Undo". Two taps, as promised.

### Flow 11: Make the phone his own (Erdem, evening, UJ-10)

1. He taps the gear on Favorites. Settings pushes in from the right.
2. SIM: "Default for calls" opens the system page; he picks his work SIM and returns; the row reads "SIM 2".
3. Quick replies: he edits the second text, drags a third to the top.
4. Speed dial: assigns 2 to his sister.
5. Back on Favorites he long-presses a tile, drags it to the first place and lifts.
6. In Settings he taps Export; the file picker saves `rovsti-phone-backup-2026-10-28.json`. **Climax:** the pill says "Saved to rovsti-phone-backup-2026-10-28.json".
7. Resolution: every choice survives a restart; nothing left the phone.

## Inspiration & Anti-patterns

- **Lifted from iOS 26 Phone:** one big Call button, glass key grid, floating tab bar, sheet detents, spring everywhere, the red missed row with a count.
- **Lifted from stock Android dialers:** the system ringer owns sound and haptics, so it respects Do Not Disturb and per-contact ringtones.
- **Rejected: swipe-to-answer** (slow, fails with TalkBack and wet hands).
- **Rejected: engagement chrome:** streaks, badges beyond the missed count, rate-me prompts, "suggested" contacts, ads.
- **Rejected: always-on shaders and animated glass on scrolling rows.** It breaks the frame budget and clutters the screen. Glass belongs to fixed chrome and sheets only.
- **Rejected for v0.1, accepted for v0.2: a settings screen.** v0.2 adds one behind the gear (no fifth tab); the theme still follows the system and there is no theme setting.
- **Rejected: call recording, spam lookup, visual voicemail** (no public API or needs the network).
- **Rejected for the In-Call grid: hiding or moving unsupported controls.** They dim in place.

## Responsive & Platform

- **Portrait only.** Orientation is locked on every screen, including the Incoming Call Screen and In-Call Screen.
- **Screen sizes:** 360 to 440 dp wide. Keys stay 78 px; below 380 dp the key gap becomes 16 px; Favorites uses 2 columns up to 439 dp, 3 from 440. Below 700 dp tall, the Keypad drops its large title (when the checklist is collapsed) and scrolls.
- **Display cutouts and system bars:** status and navigation insets honoured; the Tab Bar sits above the bottom inset.
- **Gesture navigation:** Back is the edge swipe; the app never captures edge gestures except sheet drags (vertical only).
- **Xiaomi specifics:** setup items 6 to 9 only on Xiaomi; deep links may fall back (FR-6); MIUI may hide the role dialog (FR-2); Background pop-ups may be off for sideloaded apps (Risk in PRD §9).
- **Desktop preview (FR-38):** a 402 x 874 window; sample data; Android-only actions explain themselves.
- **Display refresh:** budgets follow the panel (60/90/120 Hz). On 120 Hz the same springs run with the real `dt`; no frame pacing assumptions.
- **Battery:** no timers while idle; the call timer is the only per-second tick, and only during a Call.

## Performance Requirements (acceptance, see DESIGN.md Performance Budget)

Measured on the owner's phone with a release-like build:

| Id | Requirement | Scenario |
| --- | --- | --- |
| P-1 | p95 frame time within one display frame; at most 1% dropped frames; no frame over 2x budget | Tab switch ping-pong 20 times; scroll Recents (500 rows) top to bottom and back; Keypad typing at 6 keys/s; sheet open, drag, dismiss |
| P-2 | At most 4 refractive surfaces on screen at any moment (table in DESIGN.md); rows, tiles and keys at rest are never refractive | Inspect every screen state |
| P-3 | Lists show first rows within 1 s of tab open for 500 items (NFR-4); scroll position never jumps on data refresh | Open Recents / Contacts with 500 items; trigger a call-log change while scrolled |
| P-4 | Press feedback begins on the first frame after the touch (no more than one frame of latency) and never delays the action | Keys, Call, Answer, Decline, End |
| P-5 | Call controls take effect within 300 ms and reflect state within 500 ms (NFR-3) | Mute, Speaker, Hold, DTMF, End |
| P-6 | Incoming Call Screen or Notification under 1.5 s warm and 3 s cold (NFR-2); the screen never shows a blank frame | Cold start, warm, locked, unlocked |
| P-7 | Zero animation frames requested at idle; no timers other than the call timer | Leave the app idle 30 s on every tab |
| P-8 | When the frame budget is blown twice in a row, the screen degrades to flat glass automatically and recovers on the next screen | Force a slow frame in a debug build |
| P-9 | The UI thread never blocks on a provider query | Strict-mode check during list loads |
| P-10 | Layout stays uncluttered: at most one banner/card above a list; at most one primary action per screen state | Inspect every screen state |

## v0.2 Acceptance (P-11 and on, same style as P-1 to P-10)

Measured on the owner's phone with the release build.

| Id | Requirement | Scenario |
| --- | --- | --- |
| P-11 | Route popover opens within one frame of the touch ending, rows update within one frame of an endpoint snapshot, no row moves under a finger; the Audio caption never changes the grid | Connect and disconnect Bluetooth 5 times while the popover is open and closed; open the popover 20 times |
| P-12 | Press feedback within one frame and the action on finger-up for Add call, Swap, Merge, Hold and answer, End and answer, Decline, Message, Silence, tiles, rows, toggles | Slow-motion screen recording of each control; slide off a control and verify no action |
| P-13 | In-Call grid and End never change position across: one call, two calls, conference, Waiting card, popover, Add call sheet | Overlay screenshots of the 6 states aligned pixel for pixel on the grid and End |
| P-14 | At most 4 refractive surfaces per v0.2 screen (table in DESIGN.md); scaled surfaces use the flat look until settled | Inspect each screen state; frame-time log during popover open |
| P-15 | Conference of up to 6 participants, blocked list of 500, call history of 500, T9 over 2,000 contacts stay within the frame budget; lists load in under 1 s; T9 result within 8 ms off-thread and within one frame of the digit on screen | Scroll tests and typing at 6 keys/s |
| P-16 | Incoming drag follows the finger with no extra frame of latency; commit at 96 px or by fling; a short drag springs back without any action; buttons work unchanged | 30 drags in each direction; TalkBack run through custom actions |
| P-17 | Each v0.2 setting persists, applies without restart, and reproduces v0.1 behaviour at its default; Export then Import round-trips | Toggle everything, restart, export, wipe, import |
| P-18 | Single-SIM phone shows no SIM UI and no layout difference from v0.1; dual-SIM shows chooser, chips and Settings row | Test on one and two SIMs |
| P-19 | Block then Undo restores the row; Block unavailable is disabled with a reason; blocked numbers never ring and are absent from Recents and the badge | Block a test number, call it from another phone, check |
| P-20 | Reduced motion and reduced transparency: every v0.2 surface works with the stated fallback; font scale 1.3 clips nothing; TalkBack reaches every action without a gesture | Run each surface with the three settings on |
| P-21 | Emergency number entry: no animation delay, no chooser, no setup or block check; Add call, Merge and Block dim in the Call | Dial the local emergency test number on a device without a SIM-less call (emulator or carrier test number) |
| P-22 | Idle cost still zero frames and no timers except the call timer; the Notice pill, speed-dial ring and hold-to-delete run only while visible or held; the flip sensor runs only while ringing | Idle 30 s on Settings and Call details; profile a ringing call |
| P-23 | Destructive actions never fire on finger-down and always name what they remove | Review every delete, block and End and answer path |

## Open Questions

- **Q1 (architecture):** the in-call and incoming surface (single GPUI activity vs native fallback) decides whether the Incoming Call Screen is glass. This spine is surface-agnostic.
- **Q2 (architecture):** the event transport. The UI needs 500 ms state reflection and an initial full resync.
- **Q3 (architecture):** call-state ownership when the GPUI view is not running. The UI needs a full-state snapshot on attach.
- **UX-Q1 (design):** accent colour is `{colors.accent}` (emerald-teal from the icon); the kit's default accent is iOS blue. The kit needs a per-app accent override; confirm the owner likes the teal on selected tabs. Assumption.
- **UX-Q2 (design):** the tab-bar puck drag, the Recents "New call" pill and the alphabet rail are not in MVP; confirm.
- **UX-Q3 (product):** "Confirm" gating on opening the Xiaomi page first (this spine) vs allowing Confirm at any time. Assumption: gated, to avoid false "done".
- **UX-Q4 (product):** Recents rows for unknown or private numbers are inert; confirm.

## v0.2 Open Questions

- **UX-Q5 (product).** Audio rows: the popover is bottom-anchored with the newest device at the top, so the order is devices, wired, Speaker, Phone (reverse of the usual Phone first). Alternative: top-anchored popover that grows downward over the control grid. Chosen: bottom-anchored to protect the "no row moves under the finger" rule. Confirm.
- **UX-Q6 (product).** Add call opens a sheet with Keypad / Favorites / Contacts (FR-44). Alternative is a full-screen picker. Chosen: sheet, so the call timer stays visible. Confirm.
- **UX-Q7 (product).** After merging, End on the conference button: ends everyone; End on a participant row: ends only them. The big End button's meaning when a conference exists is "End conference". Confirm the wording "End" vs "End conference" on the big button.
- **UX-Q8 (design).** Dragging the incoming card is the gesture target (not the buttons) so the buttons never move. PRD FR-59 says "slide gesture"; confirm the card is the right object and that a left-drag decline is not too easy to trigger with a wet thumb (commit distance 96 px).
- **UX-Q9 (conflict).** FR-70 says "delete one entry (swipe)", but the MVP spine bans swipe-to-delete (scroll conflict, TalkBack). Chosen: row menu and Details. Confirm or amend the PRD.
- **UX-Q10 (conflict).** FR-49 says the SIM section "shows the read-only default only" with fewer than two SIMs, FR-50 says none of it is visible on one SIM. Chosen: absent entirely on one SIM.
- **UX-Q11 (product).** No "New contact" button is added to the Contacts title (the gear takes the trailing slot). Creating from scratch goes through the Keypad's "Add to contacts" or the system Contacts app. If the owner wants a plus button, the title row needs two trailing buttons (88 px).
- **UX-Q12 (platform).** Voicemail new-message badge: shown only if a local signal exists; unknown on this carrier (PRD FR-67 `[?]`).
- **UX-Q13 (platform).** Which component sends the reject-with-message SMS, and whether an SMS permission is needed (FR-56 `[?]`, Story 18.1). The reply sheet's permission row is conditional on the answer.
- **UX-Q14 (platform).** Contact ringtone write needs `WRITE_CONTACTS` and may not work for a sideloaded dialer (FR-63 `[?]`); the row disappears if it does not.
- **UX-Q15 (product).** Frequent window (90 days assumed) and whether tapping a Frequent avatar should call (chosen: opens the Contact Sheet).
- **UX-Q16 (product).** The Earpiece row when Android omits it (headset connected): shown dimmed as "Unavailable with this device" rather than removed (chosen), vs removed.
- **UX-Q17 (design).** Accent halo and dot on new devices (S18) are the only accent use outside the existing list in DESIGN.md Colors; confirm.
- **UX-Q18 (product).** Delete contact has no Undo (a deleted provider contact cannot be restored reliably). Confirm the confirmation-only policy.
- **UX-Q19 (platform).** Hearing-aid hint ("Hearing aid" subtitle and `LucideEar`) depends on the device name; behaviour on the owner's hearing aid, if any, to be tested (FR-41 `[?]`).

