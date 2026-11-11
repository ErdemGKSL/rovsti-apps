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
