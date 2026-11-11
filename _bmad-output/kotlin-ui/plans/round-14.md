# Round 14 plan: E5 Phone R2 part 2 (in-call fixes F22-F26, Settings, Statistics, Notes, Reminders and setup, Photos, Polish)

Planned 2026-11-11 at base `50c6204` (branch `ui-overhaul`). Epic: **E5 Phone**, the first epic not done (E1-E4 done).
Round 13 (`plans/round-13.md`) delivered E5.5, E5.6/E5.7a owner follow-ups, E5.8 and E5.7b. This round closes E5 with
E5.9-E5.14, after first fixing the open screenshot and owner items F22-F24 plus two new ones found in this round's review
(F25, F26). The cross-app stories **X.1** (WhatsApp photo fallback avatar) and **X.2** (Takumi share cards) are round 15,
before E6.

Owner decisions in force: Rovsti is GPL-3.0-or-later; Convx code is ported close to verbatim with its original header plus
`Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`; glisse-derived code keeps the
glisse MIT header; `android/THIRD_PARTY_NOTICES.md` lists any new source. Owner rules (b) wait for `Rust.init` before any
`RustNative` call, (c) `imeOverlapPadding` only (never `imePadding` / `windowInsetsPadding(WindowInsets.ime)`), (d) R8
keep rules for anything reached by name, checked with `dexdump` on the release APK. Definition of done item 6 (Turkish
in `values-tr`, launcher name/icon, notification icons, never crash on platform calls) applies to every new string and
notification of this round.

## State found at `50c6204` (verified in code, not trusted)

- Working tree clean. Stashes: `stash@{0}` "WIP on ui-overhaul: cade24b" (step-4 WIP of round 13: `RovstiGlass.kt`,
  `GlassEffect.kt`, `InCallScreen.kt`, `IncomingScreen.kt`, `GlassRealPathTest.kt`, `InCallScreenTest.kt`, ...;
  superseded by `c0ffc3c`, whose `GlassRealPathTest` has the `CallControlButton` case at line 212), `stash@{1}` "blocked:
  kotlin-ui r12 S6" (E5.5, recovered in round-13 step 1), `stash@{2}` deprecated GPUI WIP (never pop). Branch
  `wip-r12-s6` is gone.
- **Round 13 verified in code:** `design/.../list/Reorder.kt`, `phone/src/main/res/values-tr/strings.xml`,
  `design/.../controls/PlainIconAction.kt`, `SlideToAnswer.kt`, `design/.../media/CallBackdrop.kt`,
  `docs/kotlin-ui/wa-call-audio.md`, `crates/rovsti_wa_client/tests/call_audio.rs`; tests `FavoritesViewModelTest`
  (phone), `PhoneStringsTest`, `TabBarCellsTest`, `PlainIconActionTest`, `CallBackdropTest`, `SlideToAnswerTest`,
  `WaCallAudioTest`, `WaCallFlowTest`, `MultiCallViewModelTest`, `AddCallSheetTest`, `GlassRealPathTest`; goldens
  `e5.5-*`, `e5.6-*` (incl. `-photo`, `locked-slide-*`), `e5.7-*` (incl. `hold-two-calls`, `conference`,
  `add-call-sheet`, `incall-photo`), `e5.8-*` (152 PNGs in `android/phone/screenshots/`). E5.1-E5.8 stay `done`.
- **Goldens opened this round:**
  - `e5.6-incoming-dark`: correct (formatted `+90 555 111 22 33`, Light name, plain secondary actions, solid discs).
  - `e5.7-incall-active-light`, `e5.7-incall-hold-two-calls-dark`, `e5.8-wa-incall-dimmed-controls-light`:
    **F22 still open**: the number under the name is raw (`+905559990000`, `+905551112233`). Product code formats
    (`InCallViewModel` takes `NumberDisplay`), but the in-call rigs pass `NumberDisplay.AsIs`
    (`phone/src/test/.../incoming/IncomingRig.kt:218` default) and `InCallScreenTest.kt:154` even asserts the raw text.
    The goldens must show the real path (`hostDisplay(...)`, `IncomingRig.kt:254`).
  - `e5.7-incall-keypad-dark`: **F23 still open**: "Mert Kaya" is clipped at the descenders by the "4821" digits line
    (reads "Mert Kava").
  - **F25 (new):** the in-call name is `RovstiTheme.type.largeTitle` bold (`InCallScreen.kt:370`), while Incoming uses
    the iOS 26 Light 36 sp name: the answer morph jumps weight. iOS 26 keeps the thin name in call.
  - **F26 (new):** the in-call "Audio" control shows a phone handset on earpiece (`audioIcon(ui.route)`,
    `InCallScreen.kt:508`); iOS shows a speaker glyph (Bluetooth glyph when BT is the route). The handset reads as "call".
  - **F24 (owner, epics E5.5):** search focus/IME is not handled when a result opens a sheet (no code path for it in
    `phone/.../contacts/` or `recents/`).
- **E5.9-E5.14 inventory (GPUI app, feature list only):** `apps/phone/src/settings_ui.rs`, `blocked_ui.rs`,
  `prune_ui.rs`, `backup_ui.rs`, `replies_ui.rs`, `speed_dial_ui.rs`, `sim_ui.rs`, `stats_ui.rs`, `notes_ui.rs`,
  `remind_ui.rs`, `setup_ui.rs`, `photo_viewer.rs`, `photo_edit.rs`, `photos.rs`. Core ops already exported
  (`rovsti_ffi`): `blocklist.{lines,parse_input,parse_many,parse_file,check,call_line}`, `recents.blocked_history`,
  `call_log.{csv,prune_plan,counts,frequent,query}`, `call_notes.{rows,export,remove,restore,set,details}`,
  `call_stats.{overview,bucket,person}`, `speed_dial.*`, `setup.{checklist,steps,xiaomi,reopened}`,
  `phone.{backup_export,backup_import,prefs_load,prefs_save,reply_choices}`. Existing Kotlin: `PhoneRoute` has
  `Settings`, `Diagnostics`, `WhatsAppSettings`, `Appearance`, `Haptics`, `About`, `Licences`; `data/BlockRepo.kt`,
  `data/SpeedDial.kt`, `data/PhoneStore.kt`; `:design` `media/PhotoViewer.kt` (E1.20).
- Disk: 6.5 GB free at planning time: **Rule 1 at the 7 GB level is required before the first build.**

## Rules for this round

1. **Disk first, every step.** `df -h .` before any build. Below 8 GB: `rm -rf android/*/build` and
   `target/*/incremental`. Below 7 GB: also `cargo clean -p` the deprecated GPUI crates (`rovsti_phone rovsti_messages
   rovsti_stops rovsti_contacts rovsti_ui rovsti_wa_ui liquid_glass_kit`), `rm -rf ~/.gradle/caches/build-cache-1` and
   Gradle versions under `~/.gradle/caches/` other than the wrapper's. Never build below 6 GB. Never `cargo clean` the
   whole workspace or `rm -rf target` (release-only checks, `docs/kotlin-ui/toolchain.md`).
2. **Domain logic in Rust**: parsing, plans, CSV, stats buckets, notes search live in the core ops listed above; a missing
   decision becomes a `rovsti_ffi` op with fixtures, `OPS.md` and `scripts/check-ffi-exports.sh`.
3. **Screens** (ARCH 5.2): ViewModel, one `@Immutable UiState`, `onAction(sealed)`, `stateIn(WhileSubscribed(5_000))`,
   effects through a `Channel`; composables never map/sort/filter; only `:design`/`:shell` components (inset-grouped
   `IosList` rows, `FrostedTopBar` with the `LocalPageBack` glass back button on every pushed page, full tab bar on
   pushed pages, toasts/undo pills from E1.15, `IosSheet` detents, `IosContextMenu`/`ButtonMenuEntry` menus).
4. **Text fields** (paste many, prefix range, quick reply editor, notes search): `:design` text fields with
   `imeOverlapPadding` only (rule c); caret, selection and the copy/paste menu must work.
5. **Motion** (SKILL refs): list insert/remove `motion.md` spring 0.85/400 with `animateItem`; undo pill `overlays.md`
   toast; sheet `overlays.md` 5; chart bars grow with a staggered spring 0.8/300 (30 ms stagger, reduce motion -> no
   animation through `MotionGate`); no layout pushes from presses (press = `press/GlassPress.kt`).
6. **Tests:** JVM ViewModel tests with the real core (`HostRust`) or `FakeRustCore`; gestures with
   `mainClock.autoAdvance = false`; `ShotTest` light + dark (+ `-fs2` / `-rtl` / `-tr` where named) into
   `android/phone/screenshots/`. **Every new or changed PNG is opened with Read and judged**; what was seen goes in the
   commit message and the epics Evidence line. Goldens must use the product path (real `NumberDisplay`, real core ops):
   no "AsIs" stand-ins for anything the owner sees.
7. **Strings:** every new string in `values/strings.xml` and `values-tr/strings.xml`; `PhoneStringsTest` and lint
   `MissingTranslation` pass.
8. **Check per step:** `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN`. Rust changes also run `cargo test -p
   rovsti_core -p rovsti_app_core -p rovsti_ffi` and `bash scripts/check-ffi-exports.sh`. Full `CLAUDE.md` gate in Step 8.
9. **Commits:** one per step, `Kotlin UI (E5 Phone, round 14 (E5 R2 part 2)): <step title>`, ending with the session
   attribution lines. Never push to `main`.

## Steps

### Step 1. In-call and search fixes: F22, F23, F24, F25, F26

- Disk Rule 1 (7 GB level).
- **F22:** `IncomingRig.kt` default `display` becomes the real `hostDisplay(...)` for every shot rig (`E56ShotTest`,
  `E57ShotTest`, `E58ShotTest`, `MultiCallRig`); `NumberDisplay.AsIs` only in unit tests that say why.
  `InCallScreenTest.kt:154` asserts `+90 555 999 00 00`. Grep the in-call path (`InCallViewModel` name/subtitle, held
  card, conference rows, Add-call sheet rows, post-call pill `blockMessage`, `WaitingCallCard`) for any `numberRaw`
  that reaches text and route it through `NumberDisplay`. `InCallViewModelTest` case for the held card and the
  conference row.
- **F23:** when the in-call keypad is open the header switches to the compact style: name `title2` Light 22 sp on one line
  (ellipsis), the digits line in its own row under it with `Spacing.s8` gap, both measured so nothing overlaps (no fixed
  heights; `Column` with `Arrangement.spacedBy`). Spring the switch with `Modifier.animateContentSize(spring(0.85, 400))`.
  Test `InCallScreenTest`: keypad open -> the name node's bounds and the digits node's bounds do not intersect, the name
  text is not clipped (`assertIsDisplayed` + `TextLayoutResult.hasVisualOverflow == false` via a semantics probe).
- **F25:** in-call name uses the same style as Incoming (`largeTitle` with `FontWeight.Light`, 36 sp, two lines then
  shrink-to-fit): extract `CallNameText` into `phone/.../incoming/CallHeader.kt` (shared by Incoming, In-call, WA) so
  the answer morph keeps the weight.
- **F26:** `audioIcon(route)`: earpiece and speaker -> `RovstiIcons.Speaker` (wave glyph, add to `:design` if missing),
  Bluetooth -> `RovstiIcons.Bluetooth`, wired headset -> `RovstiIcons.Headphones`; active (speaker on) -> solid white
  disc as now. Unit test of the mapping.
- **F24:** new `:design` `text/SearchFocusHandoff.kt`: `rememberSearchFocusHandoff(focusRequester)` with
  `onResultOpened()` (clears focus, `SoftwareKeyboardController.hide()`) and `onSheetDismissed()` (requests focus, caret
  to the end of the `TextFieldState`, `show()`); used by Phone Contacts tab search, Recents search and the Contacts app
  list search (`contacts/.../list/`). Test `SearchFocusHandoffTest` (Compose: type, tap result -> field unfocused and
  IME hidden (Robolectric `ShadowInputMethodManager`), dismiss the sheet -> focused, IME shown, selection at end).
- Screenshots re-recorded and OPENED: every `e5.7-*` and `e5.8-*` in-call golden, `e5.6-waiting-call-card*` (formatted
  numbers), `e5.7-incall-keypad` (no clipping, compact header), `e5.7-incall-active`, `e5.7-incall-muted-speaker`
  (speaker glyph, Light name), new `e5.5-contacts-search-sheet-open` (keyboard hidden behind the sheet).
- Check. epics: F22-F26 Evidence under E5.7a / E5.5.

### Step 2. E5.9a Settings root and Blocked numbers

- `PhoneRoute.Settings` becomes the full iOS settings page (inset-grouped sections, `IosList`): **Calls** (Blocked
  Numbers, Quick Replies, Speed Dial, SIM Choices when dual-SIM), **Data** (Call History, Backup and Restore),
  **Statistics**, **Notes**, **Haptics**, **Appearance**, **WhatsApp**, **About** / **Licences**, hidden Diagnostics
  (7 taps on the version row, as now). New routes `Blocked`, `QuickReplies`, `SpeedDialPage`, `SimChoices`, `Data`,
  `Backup`, `Stats`, `Notes` in `PhoneRoute` + `PhoneRoutesTest` cases (all pushed: back button, full bar).
- `phone/.../settings/blocked/BlockedViewModel` + `BlockedScreen` over `BlockRepo` and the core
  `blocklist.lines` / `parse_input` / `parse_many` / `parse_file` / `check` and `recents.blocked_history`:
  list (formatted numbers, prefix ranges shown as "+90 850 *"), swipe and menu Unblock with Undo pill, **U34** paste many
  (sheet with a multi-line field; parse preview "12 numbers, 1 invalid" from the core), **U55** add prefix range,
  **U27** share list / unblock all + Undo, **U38** export / import file (SAF through `:platform` files, `parse_file`),
  **Blocked calls** section from `recents.blocked_history` (tap opens the number sheet).
- Tests: `BlockedViewModelTest` (real core: paste-many preview, prefix add, unblock + undo restores, import file
  fixture, unblock all + undo), `BlockedScreenTest` (swipe unblock with `autoAdvance=false`; paste field caret/paste;
  IME uses `imeOverlapPadding`), `PhoneRoutesTest` additions, `PushedPagesBackTest` additions for the new routes.
- Screenshots OPENED: `e5.9-settings`, `e5.9-settings-fs2`, `e5.9-settings-tr`, `e5.9-blocked-list`,
  `e5.9-block-paste-many` (keyboard open: field above IME, preview line visible) (light + dark): back button top-left,
  full four-tab bar, 16 dp gutters, no wrapped labels in Turkish.
- Check. epics: E5.9 -> in-progress.

### Step 3. E5.9b Quick replies, Speed dial, SIM choices, Data, Backup

- `settings/replies/`: list of quick replies (`phone.reply_choices`, prefs save), add / edit in an `IosSheet` with a
  text field, reorder with `:design` `list/Reorder.kt`, delete + Undo, restore defaults.
- `settings/speeddial/`: digits 2-9 (1 = voicemail row) over `speed_dial.*` (`assign`, `unassign`, `free_digits`,
  `badges`): assign from contacts picker (reuse `FavoritePicker`), **U22** share list, clear one / clear all + Undo.
- `settings/sim/`: remembered SIM per contact (`sim-choices` rules from `SimState`), forget one / all + Undo; hidden on
  single-SIM.
- `settings/data/`: **U5** export call history CSV with **U19** note column (`call_log.csv` + `call_notes.export`),
  **U41** export names, **U43** delete older than (30/90/365 days, `call_log.prune_plan` preview count, confirm sheet),
  **U24** delete all + Undo (5 s window before the provider delete), **U54** export contacts `.vcf`. Share sheet or SAF
  save through `:platform`.
- `settings/backup/`: prefs backup export / import (`phone.backup_export` / `backup_import`) with a preview of what the
  file holds before applying.
- Tests: `QuickRepliesViewModelTest`, `SpeedDialViewModelTest`, `SimChoicesViewModelTest`, `DataViewModelTest` (CSV
  header and a note column, prune plan count, delete-all undo cancels the provider call), `BackupViewModelTest`
  (round-trip with the real core), `QuickRepliesScreenTest` (reorder drag `autoAdvance=false`).
- Screenshots OPENED: `e5.9-quick-replies`, `e5.9-quick-reply-edit-sheet`, `e5.9-speed-dial`, `e5.9-sim-choices`,
  `e5.9-data`, `e5.9-data-delete-older`, `e5.9-backup-preview` (light + dark).
- Check. epics: E5.9 -> `done` with Evidence.

### Step 4. E5.10 Statistics

- `phone/.../stats/StatsViewModel` + `StatsScreen` over `call_stats.overview` / `bucket` / `person`: segmented control
  (This week / Last 7 days / Last 8 weeks, `:design` segmented from E1.17), count cells (calls, incoming, outgoing,
  missed, talk time), bar chart (Canvas, bars from the core's fractions, labels from the core; grow with the staggered
  spring of Rule 5), **U46** tap a bar -> bucket sheet listing that day's calls (tap opens the number sheet),
  top people (avatar, count, talk time; tap -> person sheet), **U52** records (longest call, busiest day),
  **U28** CSV export, **U40** share summary text. Empty state for no calls. TalkBack: each bar has a content
  description "Tuesday, 6 calls".
- Tests: `StatsViewModelTest` (real core with a call-log fixture: ranges, bucket ids, records), `StatsScreenTest`
  (tap a bar opens its bucket sheet; reduce motion -> bars at final height in the first frame).
- Screenshots OPENED: `e5.10-stats-week`, `e5.10-stats-8-weeks`, `e5.10-stats-day-tapped`, `e5.10-records`,
  `e5.10-stats-empty` (light + dark), `e5.10-stats-week-fs2`.
- Check. epics: E5.10 -> `done`.

### Step 5. E5.11 Notes page

- `phone/.../notes/NotesViewModel` + `NotesScreen` over `call_notes.rows` / `remove` / `restore` / `export`: list of
  notes (person, date via `DeviceLocaleTime`, first lines), search field (the core filters; `SearchFocusHandoff` from
  Step 1 when a row opens its call-details sheet), tap opens the call (E5.4 call details), swipe / menu delete + Undo
  (`restore`), **U33** share all, **U42** export CSV. Empty and no-results states.
- Tests: `NotesViewModelTest` (real core: search, delete + undo, export header), `NotesScreenTest` (swipe delete with
  `autoAdvance=false`, undo pill restores).
- Screenshots OPENED: `e5.11-notes`, `e5.11-notes-search`, `e5.11-notes-empty` (light + dark).
- Check. epics: E5.11 -> `done`.

### Step 6. E5.12 Reminders, missed-call notifications, setup flows

- **U13 call reminders:** "Remind me" choices (in 15 min, 1 h, this evening, tomorrow morning, pick time) from
  `rovsti_core::reminder`; `CallReminderReceiver` (manifest component, already declared) posts the reminder notification
  (kind icon, accent, contact photo large icon) with Call / Message actions; reminders survive reboot (boot receiver
  re-arms from the store); exact-alarm permission missing -> inexact with a settings row. Replace the round-12 action
  sheet with the `ButtonMenuEntry` menu (`e5.12-remind-later-menu`).
- **Missed-call notification** actions: Call back, Message, Remind me (through `CallActionReceiver`), grouped digest
  when more than one, cleared when Recents is opened (U39 marks seen).
- **Setup flows** (`setup.checklist`, `setup.steps`, `setup.xiaomi`, `setup.reopened`): default dialer role request,
  Xiaomi/HyperOS autostart and background rows (open the OEM settings via E3.8b `OemSettings`), battery optimisation,
  full-screen intent revoked row (API 34 `canUseFullScreenIntent`), notifications permission; the setup sheet and a
  "Setup" section in Settings show the same rows; returning from a system page refreshes.
- R8: every receiver/service reached by name already in the manifest; verify with `dexdump` that
  `CallReminderReceiver` and `CallActionReceiver` survive in the release APK.
- Tests: `CallReminderTest` (Robolectric `ShadowAlarmManager`: scheduled time, reboot re-arm, inexact fallback),
  `MissedCallNotificationTest` (actions, digest, small icon), `SetupFlowViewModelTest` (Xiaomi rows only on Xiaomi,
  full-screen intent row when revoked), `PhoneManifestTest` unchanged and green.
- Screenshots OPENED: `e5.12-remind-later-menu`, `e5.12-xiaomi-setup-rows`, `e5.12-full-screen-intent-revoked-row`,
  `e5.12-settings-setup-section` (light + dark), `e5.12-setup-tr`.
- Check. epics: E5.12 -> `done`.

### Step 7. E5.13 Photo viewer and photo edit

- **U1** tap the avatar on the person sheet / call details -> `:design` `media/PhotoViewer.kt` (E1.20) with a shared
  bounds transition from the avatar (`motion.md` shared element, spring 0.85/380), pinch / double-tap zoom, drag down to
  dismiss with velocity hand-off. **U2** Save (MediaStore through `:platform`) / Share. **U10** set / replace / remove
  the contact photo (Photo Picker, camera capture, crop/rotate from the Contacts editor's crop component, provider write
  via `ContactsRepository`), menu from the viewer's toolbar (`e5.13-photo-edit-menu`); without a write grant, hand off
  to the Contacts editor. (The WhatsApp fallback photo of X.1 is round 15; leave a `PhotoSource` enum so the viewer can
  show it read-only.)
- Tests: `PhotoViewerFlowTest` (open from sheet, drag-dismiss with `autoAdvance=false` returns to the avatar bounds),
  `PersonPhotoViewModelTest` (set/replace/remove call the repository; no grant -> hand-off).
- Screenshots OPENED: `e5.13-viewer`, `e5.13-photo-edit-menu`, `e5.13-viewer-zoomed` (light + dark).
- Check. epics: E5.13 -> `done`.

### Step 8. E5.14 Polish, close E5, full gate, APKs

- **U23 audit:** a table in `docs/kotlin-ui/checklist-e5.md` listing every Phone utility (U1-U56) with its pill text
  and haptic (`HapticEvent`), each verified in code; add the missing ones. Theme melt (system theme change cross-fade)
  and setup-complete moment (`motion.md`).
- **Recomposition tests:** `KeypadRecompositionTest` (typing 10 digits recomposes only the field and T9 list),
  `RecentsScrollRecompositionTest` (scrolling recomposes no row whose data did not change) with the E1.21 harness;
  baseline profile rules for `:phone` if the harness supports it.
- Checklist A-H for every Phone screen in `checklist-e5.md`; RTL and 2x font goldens for Settings and Stats if missing.
- Residual of F21: `LargeTitleScaffold.titleHeightPx` (state written on size, read in draw) gets the same plain holder
  as `ScreenChrome.titleBlockHeight`; `TabBarCellsTest` gets a `LargeTitleScaffold` case.
- Stash hygiene: compare `stash@{0}` (round-13 step-4 WIP) with HEAD; if nothing in it is missing from HEAD, list it in
  the summary as safe to drop (do not drop `stash@{2}`).
- Regenerate `docs/kotlin-ui/screenshots/phone.png` (`:phone:contactSheet`) and open it.
- Full gate: `cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace`, `cargo check -p
  rovsti_android --target aarch64-linux-android`, `bash scripts/kotlin-ui-check.sh`. Release APKs of `:phone` (plain and
  firebase) and `android/catalog`; `dexdump` keep-rule checks; state the paths.
- epics: E5.14 -> `done`, E5 header "DONE (R2)"; next planner = round 15: X.1 then X.2, then E6.

## Verification list for the next planner

Files: `phone/.../incoming/CallHeader.kt`, `design/.../text/SearchFocusHandoff.kt`, `phone/.../settings/{blocked,replies,
speeddial,sim,data,backup}/`, `phone/.../stats/`, `phone/.../notes/`, reminder/missed-call notification code,
`docs/kotlin-ui/checklist-e5.md` U23 table. Tests: `SearchFocusHandoffTest`, `InCallScreenTest` (formatted number,
keypad header no overlap), `BlockedViewModelTest`, `BlockedScreenTest`, `QuickRepliesViewModelTest`,
`SpeedDialViewModelTest`, `DataViewModelTest`, `BackupViewModelTest`, `StatsViewModelTest`, `StatsScreenTest`,
`NotesViewModelTest`, `NotesScreenTest`, `CallReminderTest`, `MissedCallNotificationTest`, `SetupFlowViewModelTest`,
`PhotoViewerFlowTest`, `PersonPhotoViewModelTest`, `KeypadRecompositionTest`, `RecentsScrollRecompositionTest`.
Goldens: re-recorded `e5.7-*` / `e5.8-*` (open `e5.7-incall-active` and `e5.7-incall-keypad`: formatted number, Light
name, speaker glyph, no clipping), `e5.9-*`, `e5.10-*`, `e5.11-*`, `e5.12-*`, `e5.13-*`, `docs/kotlin-ui/screenshots/phone.png`.
