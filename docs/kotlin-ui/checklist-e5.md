# E5 R1 checklist record (Phone, round-12 step 8)

The pass of `.claude/skills/convx-motion-compose/references/checklist.md` sections A-H over the Phone screens built in
R1 (`android/phone`): setup sheet, diagnostics, settings (E5.1), keypad (E5.2), Recents (E5.3), the details sheets (E5.4),
Incoming (E5.6) and the single-call In-Call screen (E5.7a). Each item is `pass`, `fixed` or `deviation: <reason>`.
Goldens are in `android/phone/screenshots/` (contact sheet: `docs/kotlin-ui/screenshots/phone.png`, opened).
Favorites and Contacts tabs (E5.5) are NOT part of this pass: that step was stashed (see epics.md recovery note).

## A. Licence and provenance
- pass: no GPUI, no Material. The call aura is the Convx port in `:design` (`media/CallAura.kt`, header kept).
- pass: `CallSheetMotion` (mini pill windows) is Rovsti code that uses Convx's published numbers only (0.2, 0.3, 0.1-0.6).

## B. Layout
- pass: In-Call is one column (collapse button, caller block that scrolls at large fonts, 3 x 2 grid or tone keypad, End);
  controls never scroll. `e5.7-incall-fs2` shows no clipping at 2.0.
- fixed (this step): the post-call pill covered the End button (`e5.7-post-call-block-pill`); the bottom pad is now 80 dp,
  re-recorded and opened: End clears the pill.
- pass: one avatar, no source pill; the SIM chip only with two SIMs (`e5.7-incall-sim-chip`).
- pass: no tab bar on Incoming / In-Call (`TabBarMode.Hidden`), no content under a floating bar.

## C. Overlays
- pass: the audio route list is a `PopoverEntry` grown out of the Audio button (`e5.7-route-picker-popover`); the timer
  menu is an `IosContextMenuEntry`; the note is a sheet; pills are the design system's undo pill.
- pass: opening any of them does not change the page layout (they are siblings in the overlay host).

## D. Motion
- pass: avatar shares bounds with Incoming (`rovstiSharedBounds`, 500 ms `Motion.sharedArtwork`).
- pass: the aura runs only while live and not held, stops under reduce motion and while a transition runs
  (`InCallScreenTest`, `IncomingScreenTest`).
- pass: the timer is a `produceState` read in its own text composable: a tick recomposes the text only
  (`aTickRecomposesTheTimerTextAndNothingElse`).
- pass: the mini pill is driven by a spring progress; `CallSheetMotion` fixes the alpha windows and input ownership.
- deviation: the avatar does not fly from the Keypad call button (the Keypad has no avatar to fly from); the call page
  opens with the normal push.
- deviation: expanding the pill is a normal push, not a record-and-replay morph of two layers.

## E. Input
- pass: tone keys send DTMF on down and stop on up; the digit line copies on touch-and-hold; the note field is the shared
  `GlassTextField` with `imeOverlapPadding` (owner fix c), never `imePadding`.

## F. Accessibility
- pass: every control has a label; active controls carry a state description; Add call says it is not available yet;
  the screen is a polite live region.

## G. Platform
- pass: no Rust call before `Rust.init` (the model reaches the core only through `PhoneOps` in coroutines).
- pass: lock-screen flags are dynamic and held only while a call is live (`LockScreenGate`, tested).
- pass: Bluetooth rows are hidden without `BLUETOOTH_CONNECT` (`RoutePickerTest`). R8: no class is reached by name.
- deviation: U51 (remember tones) is kept in memory for the process, not on disk (prefs schema is owned by Rust).
- deviation: Phone has no `values-tr` yet (none of the R1 screens do); tracked for E5.14.

## H. Tests
- `InCallViewModelTest`, `RoutePickerTest`, `InCallScreenTest`, `E57ShotTest` (light and dark goldens, all opened).

# E5 R2 part 1 checklist record (Phone, round-13 step 8)

Screens touched this round: Favorites / Contacts tabs (E5.5), Incoming and the waiting card (E5.6), In-Call and the multi-call
faces (E5.7b), WhatsApp calls (E5.8), the tab bar (F21). Goldens opened: `e5.5-*`, `e5.6-*`, `e5.7-*` (incl.
`e5.7-incall-hold-two-calls`, `e5.7-incall-conference`, `e5.7-add-call-sheet`, `-favorites`, `e5.7-conference-panel`), `e5.8-*`; the
contact sheet `docs/kotlin-ui/screenshots/phone.png` was regenerated and opened.

## A. Licence and provenance
- pass: new files are original (Rovsti GPL-3.0-or-later); nothing new was ported this step. `SegmentedControl`, `IosSheet`
  and the keys (`DigitKey`, `CallKey`, `DeleteKey`) are the existing design-system pieces.

## B. Layout
- pass: the Held card sits in the collapse button's row; with or without it the name, controls and End never move
  (`theHeldCardDoesNotMoveAnythingBelowIt`, frame by frame; `aSwapSettlesWithTheOtherCallOnStageAndNothingJumps`).
- pass: two calls = Mute, Keypad, Audio / Add call (dimmed), Swap, Merge; conference = avatars side by side (never stacked),
  name, timer and a "n people" pill; `e5.7-incall-hold-two-calls-fs2` shows no clipping at 2.0.
- deviation: with two calls Merge takes the Note slot (Note returns when one call remains).

## C. Overlays
- pass: Add call is a large `SheetEntry` (keypad / Favorites / Contacts tabs) over the call, the call stays; the conference
  panel is a sheet (Split / End per row, End all); End-all is also a context menu on a long press of End.

## D. Motion
- pass: S19 hold-away 0.86/380 (`RovstiMotion.holdAway`), S20 swap 0.8/420 (`swapCross`), S21 merge 0.75/500 (`mergePull`); all
  are layer transforms (no layout), snap under reduce motion. S22 = split/drop rows leave through the sheet's list motion.
- deviation: the swap moves the identity block by a graphics layer; the avatars are not shared-bounds elements of two layouts.

## E. Input
- pass: Add call keypad uses the Keypad's keys (0 held = +, delete repeats); no text field is added, so no IME rule applies.

## F. Accessibility
- pass: the Held card reads "name, on hold" and "Swap calls"; dimmed controls keep taking taps and say the core's sentence.

## G. Platform
- pass: `scene.of` goes through `PhoneOps.sceneOf` (already in `rovsti_ffi`; "zone" ops of the plan are `scene.*`); the model
  never calls Rust before `Rust.init`. Add call places on the live call's SIM. No class is reached by name (no new keep rules).

## H. Tests
- `MultiCallViewModelTest` (fake and REAL core), `AddCallSheetTest`, `InCallScreenTest` multi-call cases, `E57bShotTest`.

# E5.14 Polish record (Phone, round-14 step 8)

## U23 audit: every Phone utility, its pill and its haptic

Rule (added this step, `PillFeedbackTest`): a pill that reports something DONE (copied, saved, cleared, reminded, added,
removed, an Undo pill) is paired with `HapticEvent.Confirm` in the screen's effect collector; a failure notice is paired with
`HapticEvent.Reject` by the view model (`*Effect.Reject` before the `Say`); long presses that open a menu play `LongPress`
(design system); drags that cross a line play `Tick` / `ThresholdTick`. Fixed in this step: success pills had no haptic in
Recents, Keypad, the Person and Call details sheets, Favorites and the three Undo pills (`RecentsScreen`, `KeypadScreen`,
`PersonSheet`, `CallDetailsSheet`, `FavoritesScreen`). Status `in HEAD` = verified in `android/phone/src/main`;
No row is MISSING any more (see "E5 closed" below).

| U | Utility | Pill (string key) | Haptic | Status |
|---|---|---|---|---|
| U1 | Photo viewer | none (full screen) | drag-dismiss spring | in HEAD (`details/photo`, `photo_open`) |
| U2 | Save / share photo | `photo_saved`, `photo_cant_save`, `photo_cant_share` | Confirm / Reject | in HEAD |
| U3 | Recents search, filter chips | empty state text | Selection (chips) | in HEAD (`recents_filter_*`) |
| U4 | Copy as / Share as | `recents_notice_copied`, `details_notice_copied` | Confirm (fixed) | in HEAD (`recents_form_*`) |
| U5 | Export call log CSV | "Call history exported" | Confirm | in HEAD (round 15, E5.9) |
| U6 | Pause / wait on `*` `#` | tone hint line (`keypad_tone_hint`) | LongPress | in HEAD (`KeypadAction.Hold`) |
| U7 | Call statistics | none | Tick (bar tap) | in HEAD (`stats/`, `details_stats`) |
| U8 | Per-call note | `details_note_saved`, `details_note_deleted` + Undo | Confirm (fixed) | in HEAD |
| U9 | Share contact as QR | `details_notice_qr_failed` on failure | Reject | in HEAD (`details_sheet_qr`) |
| U10 | Set / replace / remove photo | `photo_updated`, `photo_remove_*` | Confirm / Reject | in HEAD (E5.13) |
| U11 | Call details sheet | `details_copied`, `details_call_deleted` | Confirm (fixed) | in HEAD |
| U12 | Select mode, multi-delete | `recents_deleted_many` + Undo | Confirm (fixed) | in HEAD |
| U13 | Remind later | `remind_set_at`, `recents_notice_reminder_failed` | Confirm (fixed) / Reject | in HEAD (E5.12) |
| U14 | Keypad field menu | `keypad_notice_copied`, `keypad_notice_cleared` + Undo | LongPress, Confirm (fixed) | in HEAD |
| U15 | Day section capsule | none | none (scroll) | in HEAD (`section_starts`) |
| U16 | Export a person's calls | `details_notice_no_calls_to_export` | Reject | in HEAD (`details_menu_export`) |
| U17 | In-call note, copy number | `incall_notice_note_saved`, `incall_notice_number_copied` | Confirm (`InCallEffect.Confirm`) | in HEAD |
| U18 | Share person stats | share sheet | none | in HEAD (`details_menu_share_stats`) |
| U19 | CSV `note` column | covered by U5 | n/a | in HEAD (round 15, E5.9) |
| U20 | Quick reply on missed | `recents_reply_title` sheet | Confirm (message app opens) | in HEAD |
| U21 | Notes search in Recents | list filter | none | in HEAD |
| U22 | Speed dial share / clear | "Speed dial cleared" + Undo | Confirm | in HEAD (round 15, E5.9) |
| U23 | This audit | | | done |
| U24 | Delete all history + Undo | Undo pill | Confirm | in HEAD (round 15, E5.9b `DataViewModel.deleteAll`, 5 s window before the provider delete); per-person delete too |
| U25 | Copy number | `recents_notice_copied` | Confirm (fixed) | in HEAD (`recents_menu_copy_number`) |
| U26 | Share call details | share sheet | none | in HEAD |
| U27 | Blocked: share, unblock all | "Unblocked n" + Undo | Confirm | in HEAD (round 15, E5.9) |
| U28 | Stats CSV export | "Call statistics exported" | Confirm | in HEAD (`stats_export`) |
| U29 | Add to contacts from keypad | `keypad_add_contact` | none (navigation) | in HEAD |
| U30 | Notes page | "Note deleted" + Undo | Confirm | in HEAD (round 15 step 3, E5.11: swipe delete or menu, "Note deleted" + Undo; `NotesViewModel.delete/undo`, `NotesScreenTest`) |
| U31 | Redial | status line | none | in HEAD (`KeypadViewModel`) |
| U32 | Smart paste chooser | `keypad_paste_title` sheet | none | in HEAD |
| U33 | Notes share all | share sheet | none | in HEAD (round 15 step 3, `NotesEffect.ShareText`; `NotesViewModelTest`) |
| U34 | Bulk block from paste | "Blocked n" + Undo | Confirm | in HEAD (round 15, E5.9) |
| U35 | Notes chip | empty text | Selection | in HEAD (`recents_filter_notes`) |
| U36 | Copy / share all numbers | `details_notice_copied_all` | Confirm (fixed) | in HEAD |
| U37 | Post-call "Add note" pill | `postcall_add_note` | none | in HEAD |
| U38 | Blocked export / import | "Blocked numbers exported" | Confirm | in HEAD (round 15, E5.9) |
| U39 | Mark missed seen | none | none | in HEAD (`RecentsViewModel`) |
| U40 | Share stats summary | share sheet | none | in HEAD |
| U41 | Export pills name the file | per-export pill | Confirm | in HEAD (round 15, E5.9b `DataEffect.Export` names the file; U28 partly done) |
| U42 | Notes export | "Call notes exported" | Confirm | in HEAD (round 15 step 3, `NotesEffect.SaveCsv` through `DetailsHost.saveCsv`, file `rovsti-call-notes-<date>.csv`) |
| U43 | Delete older calls | "n calls deleted" + Undo | Confirm | in HEAD (round 15, E5.9) |
| U44 | Remind on a missed call | `details_remind` pill | Confirm (fixed) | in HEAD |
| U45 | Select: block / share | `recents_blocked_many` + Undo | Confirm (fixed) | in HEAD |
| U46 | Tap a stats bar | bucket sheet | Tick | in HEAD |
| U47 | Long-press Call: SIM / WhatsApp | menu | LongPress | in HEAD |
| U48 | Country of unknown number | row subtitle | none | in HEAD |
| U49 | Paste tones + Stop | `incall_notice_tones_sent` | Confirm | in HEAD |
| U50 | Copy tones | `incall_notice_tones_copied` | Confirm | in HEAD |
| U51 | Remember tones (memory only) | `incall_tones_send` | none | in HEAD (deviation: in memory) |
| U52 | Stats records | none | none | in HEAD (`stats_records`) |
| U53 | Field menu Share / E.164 | `keypad_notice_copied_e164` | Confirm (fixed) | in HEAD |
| U54 | Export contacts .vcf | "Contacts exported" | Confirm | in HEAD (round 15, E5.9) |
| U55 | Block by prefix | "Blocked +90 850*" + Undo | Confirm | in HEAD (round 15, E5.9) |
| U56 | Copy country | dropped (U48, U4) | n/a | dropped |

Moments added this step: the **theme melt** (`:design` `theme/ThemeMelt.kt`, used by `RovstiShellActivity`: the new theme
applies at once, a sheet of the old page colour fades over 360 ms in one draw-phase rectangle; none under reduce motion;
`ThemeMeltTest`) and the **setup-complete moment** (`phone/setup/SetupCompleteMoment.kt`: the last checklist row turning
done while the app is open gives Confirm and the "You're all set" pill; silent when already complete at launch;
`PillFeedbackTest`).

## E5 closed (round 15 step 4): recovery evidence

E5.9 Settings (root, Blocked numbers, Quick replies, Speed dial, SIM, Data, Backup) and E5.11 Notes were recovered into HEAD in
round 15 steps 1-3 (commits 3ad1c3b, 5857821, 8e9b12b) from `stash@{2}`, `stash@{1}` and `stash@{0}`. Step 4 closes the
three open findings: F31 (a refusal from the core plays `HapticEvent.Reject` through `playRefusalHaptic` in Recents,
PersonSheet, Keypad and Favorites; `PillFeedbackTest`), F35 (the `:design` `ListRow` puts the trailing action under the
text at font scale >= 1.5 or when the text column would get under half of the row; `ListRowStackingTest`) and F36
(`:design` `text/LtrIsolate.kt`; `RovstiText`/`IosText` give plain text an LTR paragraph in an RTL layout, rows isolate numbers
with `rtlSafe()`; `LtrIsolateTest`). Evidence: `e5.14-settings-fs2`, `e5.14-settings-rtl`, `e5.14-stats-rtl`, `e5.9-*`, `e5.11-*`.

## Checklist A-H for the Settings and Notes pages (E5.9, E5.11)

- A licence: pass, original GPL-3.0-or-later code, nothing ported.
- B layout: pass, `e5.9-*` and `e5.11-*` show nothing under the floating bar; every pushed page has the glass back button.
- C overlays: pass, menus, confirmations and pills are overlay entries; Undo pills for delete/unblock.
- D motion: pass, shared row press, no animated resize.
- E input: pass, the quick-reply and note fields use `imeOverlapPadding`.
- F accessibility: pass, rows are one merged node with custom actions; trailing action reachable at 2x font.
- G platform: pass, no Rust before `Rust.init`; no new class reached by name.
- H tests: `BlockedViewModelTest`, `BackupViewModelTest`, `NotesViewModelTest`, `NotesScreenTest`, `PillFeedbackTest`,
  `ListRowStackingTest`, `LtrIsolateTest`, `SettingsEndPaddingTest`.

## Checklist A-H for the R2 part 2 screens present in HEAD (Statistics, setup flows, reminders, photo viewer)

- A licence: pass, new files are original GPL-3.0-or-later; nothing ported this step.
- B layout: pass, `e5.10-*`, `e5.13-*` show nothing under the floating bar at rest and at the end of the list. Fixed in
  step 4 (F35): at 2x font the trailing action ("Set as default") sits under the title and subtitle, which get the full width.
- C overlays: pass, bucket sheet, remind menu, photo edit menu are overlay entries; no page change.
- D motion: pass, bars on a staggered spring (none under reduce motion), theme melt is a draw-only fade.
- E input: pass, no new text field.
- F accessibility: pass, bars carry `stats_bar_desc`; setup rows carry state.
- G platform: pass, no Rust before `Rust.init`; no new class reached by name (no keep rule); Phone has `values-tr`.
- H tests: `StatsViewModelTest`, `StatsScreenTest`, `E510ShotTest`, `E514ShotTest`, `KeypadRecompositionTest`,
  `RecentsScrollRecompositionTest`, `PillFeedbackTest`, `ThemeMeltTest`, `TabBarCellsTest` (LargeTitleScaffold case).
- RTL: `e5.14-settings-rtl`, `e5.14-stats-rtl` mirror correctly (back chevron and title at the right, row chevrons at the left,
  tab order reversed). Numbers and durations keep their glyph order ("+49 30 123456", "1 h 52 min"): F36 fixed in step 4.
