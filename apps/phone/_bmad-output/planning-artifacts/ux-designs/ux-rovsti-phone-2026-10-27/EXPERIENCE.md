---
name: Rovsti Phone
status: final
created: 2026-10-27
updated: 2026-10-27
sources:
  - apps/phone/_bmad-output/planning-artifacts/prds/prd-rovsti-phone-2026-10-27/prd.md
  - apps/phone/_bmad-output/planning-artifacts/prds/prd-rovsti-phone-2026-10-27/addendum.md
  - apps/phone/_bmad-output/planning-artifacts/briefs/brief-rovsti-phone-2026-10-27/brief.md
design: DESIGN.md
---

# Rovsti Phone: Experience Spine

> Behaviour, screens, states and flows for the MVP dialer. Looks live in `DESIGN.md`; tokens are referenced as `{path.to.token}`. FR/NFR/UJ ids refer to the PRD. **Spines win on conflict with any mock.** Out-of-scope items (dual SIM, blocklist, swap/merge, T9, delete log, audio route picker, contact editing) are not designed here. Open architecture questions Q1 to Q3 are flagged, not solved: every screen below holds whichever surface the architecture step chooses.

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

Navigation model: one Tab Bar (Favorites, Recents, Contacts, Keypad), no drawer, no settings screen in MVP, modals one level deep (a sheet over a tab; never a sheet over a sheet). The In-Call and Incoming screens are full-screen and replace the tab chrome; they are not tabs.

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

Used when the phone is unlocked and in use. A high-priority call-style heads-up notification: caller name (or number), "Incoming call", Answer and Decline actions. Answer answers the Call and opens the In-Call Screen. Decline rejects it without opening the app. Tapping the body opens the full Incoming Call Screen. If Floating notifications are not allowed on Xiaomi, this silently fails: that is why the Setup item 9 exists.

### Ongoing-call notification (FR-19)

While a Call exists: persistent call-style notification with the caller, the timer (chronometer) and an End action. Tapping opens the In-Call Screen. It is the guaranteed fallback if the GPUI view fails (NFR-6).

### Preview mode (FR-4)

Tabs behave normally (lists need permissions). The preview banner shows under the title: "Not the default phone app" and "Fix". Making a call places it through the system (the system in-call UI may show); a one-line status after the call attempt reads "Placed through the system phone app". "Fix" scrolls to the Setup Checklist's first item.

### Return-to-call pill

A glass capsule under the status bar on every tab while a Call exists and the In-Call Screen is not showing: "Return to call · 2:41" (live). It is above the checklist/banner stack; tapping goes to the In-Call Screen. It hides when the Call ends.

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

### Flow 7: Call waiting (Erdem, on a call when another comes in, UJ-3 variation)

1. He is on a call; a Waiting Call banner slides down: "Waiting: Ayşe", Decline, Answer.
2. He taps Decline; the banner leaves; his call continues.
3. Alternatively he taps Answer: his first call ends and Ayşe is connected (the banner's footnote said so).

## Inspiration & Anti-patterns

- **Lifted from iOS 26 Phone:** one big Call button, glass key grid, floating tab bar, sheet detents, spring everywhere, the red missed row with a count.
- **Lifted from stock Android dialers:** the system ringer owns sound and haptics, so it respects Do Not Disturb and per-contact ringtones.
- **Rejected: swipe-to-answer** (slow, fails with TalkBack and wet hands).
- **Rejected: engagement chrome:** streaks, badges beyond the missed count, rate-me prompts, "suggested" contacts, ads.
- **Rejected: always-on shaders and animated glass on scrolling rows.** It breaks the frame budget and clutters the screen. Glass belongs to fixed chrome and sheets only.
- **Rejected: a settings screen in MVP.** The only settings are the Setup Checklist items; theme follows the system.

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

## Open Questions

- **Q1 (architecture):** the in-call and incoming surface (single GPUI activity vs native fallback) decides whether the Incoming Call Screen is glass. This spine is surface-agnostic.
- **Q2 (architecture):** the event transport. The UI needs 500 ms state reflection and an initial full resync.
- **Q3 (architecture):** call-state ownership when the GPUI view is not running. The UI needs a full-state snapshot on attach.
- **UX-Q1 (design):** accent colour is `{colors.accent}` (emerald-teal from the icon); the kit's default accent is iOS blue. The kit needs a per-app accent override; confirm the owner likes the teal on selected tabs. Assumption.
- **UX-Q2 (design):** the tab-bar puck drag, the Recents "New call" pill and the alphabet rail are not in MVP; confirm.
- **UX-Q3 (product):** "Confirm" gating on opening the Xiaomi page first (this spine) vs allowing Confirm at any time. Assumption: gated, to avoid false "done".
- **UX-Q4 (product):** Recents rows for unknown or private numbers are inert; confirm.
