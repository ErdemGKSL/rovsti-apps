# Round 12 plan (revision 2): E5 R1 = F16 pushed-page chrome fix, data layer, keypad, Recents, sheets, Favorites/Contacts tabs, Incoming, In-call (E5.1-E5.7a)

Revised 2026-11-09 at base `36760c7` (branch `ui-overhaul`). The first revision of this file (commit `bfe9f6b`, base
`c34b155`) planned E5 R1 in 8 steps; its Step 1 landed as `36760c7` (module, manifest, components, container, routes,
setup sheet, diagnostics, migration, F15 check). This revision re-plans the rest after a re-check of the tree and the
goldens. It puts the owner fix (a) first as **Step 1 (F16)**, keeps the uncommitted data-layer work as **Step 2**, and
leaves Steps 3-8 as they were (copied unchanged below). Epic: **E5 Phone**, round R1. E1-E4 are done; E5.8 stays moved
to R2 (scope change of revision 1).

Owner decisions still in force: Rovsti is GPL-3.0-or-later (E1.23, `542e271`: `LICENSE`, root `Cargo.toml`, `CLAUDE.md`
GPL line, `android/THIRD_PARTY_NOTICES.md`). The glass bug is closed by the Convx backdrop port (E1.24-E1.26,
`GlassRealPathTest`). Where a Convx file fits, port it close to verbatim with the original header plus `Ported from
Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`; glisse-derived code keeps the glisse MIT
header. The GPUI app (`apps/phone/src/*.rs`) is a feature inventory only.

## State found at `36760c7` (verified in code, not trusted)

- `git log`: `36760c7` = revision-1 Step 1 (E5.1). `stash@{0}` is the deprecated GPUI layout WIP: never pop.
- **Uncommitted work (interrupted revision-1 Step 2), inspected and kept:**
  - `R  platform/src/test/.../telephony/FakeCalls.kt -> platform/src/testFixtures/...` (staged rename, as planned) and
    `platform/build.gradle.kts` `testFixturesImplementation(libs.robolectric)` (FakeCalls needs it).
  - `phone/src/main/kotlin/dev/rovsti/phone/data/`: `PhoneStore` (187 lines: `load`, `updatePrefs`, `updateNotes`,
    `flush`, debounced `schedule`, `Mutex` `disk`, `prefsLoad/Save` + `notesLoad/Save` on IO), `RecentsRepository`
    (`recentsGroup` -> `mergeExtras` -> `recentsBuild` -> `refreshDelta` -> `missedExtras`, `extraCalls(WaCalls)`),
    `PeopleIndex` (`SearchOps` handle, `t9`, `matchNumber`, `displayName`, `idsForNumbers`, `rankByCalls`), `SimState`
    (`simBuild`/`simDot`), `BlockRepo` (`blockCheck`/`blockLines`, `BlockUndo`), `SpeedDial`
    (`speedDialOnHold`/`Assign`), `CallPlacer` + `CallLauncher`/`AndroidCallLauncher`, `DeviceLocaleTime`.
  - `phone/src/test/kotlin/dev/rovsti/phone/data/`: `PhoneStoreTest`, `RecentsRepositoryTest`, `PeopleIndexTest`,
    `SimStateTest`, `CallPlacerTest`, `SpeedDialTest`, `BlockRepoTest`, `DataRig`.
  - `PhoneContainer.kt` wires them lazily (`store`, `recents`, `people`, `simState`, `block`, `speedDial`, `placer`,
    `clock` seam). No TODO/FIXME. Whether it compiles and its tests pass is **not known** (not run by this planner):
    Step 2 runs them first.
  - Owner rule (b) holds: `PeopleIndex` uses `RustNative::release` only on a handle that `SearchOps.indexNew` returned
    (so after `Rust.init`), the same pattern as `contacts/.../BookRepository.kt:69`; no other `RustNative` call in
    `:phone`. Rule (c): no `imePadding`/`windowInsetsPadding(WindowInsets.ime)` in `:phone`/`:contacts`/`:design`
    (`ContactEditorScreen.kt:1001` reads `WindowInsets.ime` only to trigger `bringIntoView`; padding is
    `imeOverlapPadding`). Rule (d): `phone/proguard-rules.pro` relies on `:core-bridge` consumer rules for JNI; no class
    of `:phone` is reached by name besides manifest components (kept by AAPT).
- **Screenshots opened and judged:**
  - `android/contacts/screenshots/e4.15-name-format-light.png` and `e4.15-accounts-summary-dark.png`: **owner fix (a)
    confirmed open**: no back button at the top-left, the tab bar folded into a lone gear circle bottom-left.
  - `android/phone/screenshots/e5.1-settings-light.png` and `e5.1-diagnostics-dark.png`: **the same bug in Phone**:
    pushed Settings/Diagnostics show no back button and a lone clock circle bottom-left.
  - `e5.1-tabs-light.png`: four-tab bar, Keypad selected with the puck, gear capsule top-right, placeholder centred.
    Correct.
- **F16 root cause (two parts):**
  1. `chromeFor` gives every pushed settings page `TabBarMode.Folded`
     (`contacts/.../ContactsRoute.kt:179-199`, `phone/.../PhoneRoute.kt:112-119`), and `RovstiNavHost.kt:161` draws
     Folded as the selected tab's circle. Convx keeps its full floating bar on pushed routes
     (`ref/Convx/app/src/main/kotlin/com/convx/music/AppRootContent.kt:1172-1180`: album/artist routes keep
     `AppFloatingNavBar`); folding is Convx's scroll-minimise / Stops-map behaviour
     (`references/navigation-layout.md:189-190`), never a pushed-page state.
  2. The back button depends on every caller passing `onBack` (`FrostedTopBar.kt:205`, `onBack: (() -> Unit)? = null`
     defaults through `ScreenPage`/`SettingsPage`/`ChoicePage`/`SummaryPage`/`PhoneSettingsPage`). The apps pass
     `pop` (`ContactsApp.kt:118-120`, `PhoneApp.kt:205`), but the screenshot hosts rebuild the entries without it
     (`contacts/src/test/.../settings/E415ShotTest.kt:51-68`, `phone/src/test/.../PhoneHost.kt:219-226`): the goldens
     show a different page from the app and hide missing-back bugs (the same "tests mask the real path" failure as the
     glass bug). Any future page that forgets `onBack` would ship without one.
- Disk: 12 GB free (above the 8 GB comfort level; Rule 1 still applies before builds).

## Rules for this round

1. **Disk first, every step.** `df -h .` before any build. Below 8 GB: `rm -rf target/debug/incremental
   target/aarch64-linux-android/debug/incremental android/*/build`. Below 7 GB: also `cargo clean -p` for the deprecated
   GPUI crates (`rovsti_phone rovsti_messages rovsti_stops rovsti_contacts rovsti_ui rovsti_wa_ui liquid_glass_kit`),
   `rm -rf ~/.gradle/caches/build-cache-1` and Gradle versions under `~/.gradle/caches/` other than the wrapper's
   (9.9.0). Never build below 6 GB. Step 1 runs the 7 GB level now (4.2 GB free). `:phone` adds an APK build dir:
   clean `android/contacts/build android/catalog/build` when they are not needed for the step.
2. **Domain logic in Rust.** Every decision the GPUI app made through `rovsti_core`/`rovsti_app_core` goes through
   the matching op (State found). Kotlin only orders effects. A missing decision becomes a new `rovsti_ffi` op with
   fixtures and `scripts/check-ffi-exports.sh`, never a Kotlin rewrite.
3. **Screens** (ARCH 5.2): ViewModel with one `@Immutable UiState` (`ImmutableList` fields), `onAction(sealed)`,
   `stateIn(WhileSubscribed(5_000))`, one-shot `Effect`s through a `Channel`. Composables never map/sort/filter. Only
   `:design`/`:shell` components (`ScreenLazyPage`, `LargeTitleScaffold`, `SearchField`, `GlassTextField`, `ListRow`,
   `InsetGroupedSection`, `Avatar`, `SourceGlyph`, `SwipeRow`/`IosSwipeActions`, `ContextMenuEntry`/`PreviewCardEntry`,
   `ButtonMenuEntry`/`IosMenu`, `Popover`, `SheetEntry`/`IosSheet`, `Alert`/`ActionSheet`, `Toast`/undo pill,
   `ChipRow`, `EmptyState`, `SegmentedControl`, `IosToggle`, `GlassCircleButton`, `SelectCheck`, `rovstiSharedBounds`).
   Every list gets `LocalContentInsets` bottom padding, `key` + `contentType` on every item.
4. **Owner complaints are acceptance criteria** (ARCH 1): one 40 dp avatar per row; WhatsApp shown once per row as the
   14 dp `SourceGlyph` (never a pill); filter chips are ONE non-wrapping `LazyRow` with counts as secondary labels; 16 dp
   gutter; the last row ends above the floating tab bar; every long-press opens a host context menu that floats and
   never pushes content; the number field has caret, selection and the paste menu; nothing flows into content on tap.
5. **Motion** (SKILL refs): push/pop and predictive back via `RovstiNavHost` (`shell-usage.md`); context menus
   `overlays.md` 3 (`menuLift` 0.80/420, `previewReturn` 0.75/500); button menus `overlays.md` 4.3 press-slide-release;
   sheets `overlays.md` 5 (`sheetSettle` 0.86/380, dismiss +12 % H or velocity, page pushback 5.3); swipe rows
   `motion.md` 5.3 (76 dp/action); keypad digits `motion.md` 7 glass press (glisse port in `press/GlassPress.kt`) with a
   `keyboardTap` haptic and the tone, never a layout change; T9 list insert/remove with the `appear` spring;
   undo pill/toast `overlays.md` 6; ambient aura ARCH 10 rule 5 (visible + resumed + animator scale > 0 + reduce motion
   off, <= 30 fps); reduce motion via `MotionGate`.
6. **Tests:** JVM ViewModel tests with `FakeRustCore` + fixture answers and `:platform` fakes (`FakeCalls`,
   `FakeCallLogRepository`, `FakeBlocklistRepository`, `FakeSimRepository`, `FakeContactsRepository`,
   `FakeClipboardRepository`, `FakeSpecialAccess`); gestures in Compose tests with `mainClock.autoAdvance = false`;
   `ShotTest` light + dark (+ `-fs2`/`-rtl` where named) into `android/phone/screenshots/`. **Every new PNG is opened
   with Read and judged** (overlaps, clipped text, last row above the bar, 16 dp gutters, menus floating, one glyph per
   row); what was seen goes in the commit message and the epics Evidence line.
7. **Check per step:** `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN`; Rust changes also run
   `cargo test -p rovsti_app_core -p rovsti_ffi` and `bash scripts/check-ffi-exports.sh`. Full `CLAUDE.md` gate in Step 8.
   `references/checklist.md` B-H for every new screen in its commit message (and `docs/kotlin-ui/checklist-e5.md` in Step 8).
8. **Commits:** one per step, `Kotlin UI (E5 Phone, round R1 (E5.1-E5.7a)): <step title>`, ending with the session
   attribution lines. Never push to `main`.


## Steps

### Step 1. F16: every pushed page gets the glass back button and keeps the full tab bar (owner fix (a); Contacts + Phone)

- `:design` `transition/RovstiNavHost.kt` + `TabNavigator.kt`: new `@Immutable data class PageBack(val onBack: () ->
  Unit, val onLongClick: () -> Unit)` and `val LocalPageBack = compositionLocalOf<PageBack?> { null }` in
  `transition/PageBack.kt`. The host provides it around every entry whose `StackKey.depth > 0` (`onBack =
  navigator.pop()`, `onLongClick = navigator.select(navigator.currentTab)`, the same pair the apps build today) and
  `null` around tab roots.
- `:design` `chrome/FrostedTopBar.kt` (and through it `ScreenChromeBox`, `ScreenPage`, `ScreenLazyPage`,
  `LargeTitleScaffold`): `val back = onBack ?: LocalPageBack.current?.onBack` (same for the long press) before the
  `if (onBack != null) BackButton(...)` branch at line 205. An explicit `onBack` still wins (sheets, editor Cancel).
  Pages that must have no back (full-screen Incoming/InCall, editor with its own Cancel/Done) opt out with a new
  `showBack: Boolean = true` parameter on `ScreenPage`/`ScreenLazyPage`/`ScreenChromeBox`; grep every caller of
  `ScreenChromeBox`/`FrostedTopBar` and decide per page (cards and pages in `TabBarMode.Hidden` that already draw their
  own back keep it once: no double button).
- `ChromeSpec.kt` KDoc: `Folded` is for a tab ROOT only (Stops map, Contacts select mode); a pushed page keeps
  `Visible` (Convx `AppRootContent.kt:1172-1180`) or `Hidden` (full-screen pages). `contacts/.../ContactsRoute.kt`
  lines 179-199 and `phone/.../PhoneRoute.kt` lines 112-119: every `TabBarMode.Folded` -> `TabBarMode.Visible` (keep
  the large titles). Contacts select mode (`chromeFor(route, selecting)`) keeps Folded: it is a root.
  `RovstiNavHost`: in debug builds `check(spec.tabBar != Folded || depth == 0)` with a message naming the route.
- Pushed pages leave room for the full bar: they already read `LocalContentInsets.bottom`; verify the last row of
  `e4.15-settings-end` / `e5.1-diagnostics` ends above the bar after the change (Visible bar is taller than the circle).
- Remove the now-redundant `onBack = pop, onBackLongClick = popToRoot` plumbing only where it is pure duplication
  (leave the parameters; they stay the override).
- Tests:
  - `:design` `PageBackTest` (Compose): root entry has no `back-button`; a pushed `ScreenPage` without `onBack` shows
    it; clicking pops; long press selects the tab root; explicit `onBack` wins; `showBack = false` hides it.
  - `ContactsRoutesTest` / `PhoneRoutesTest`: new invariant "no route other than a tab root maps to Folded" over every
    route object (sealed subclasses, sample args for data classes), plus the select-mode case.
  - `PushedPagesBackTest` in `:contacts` and in `:phone` (Robolectric, real `RovstiShell` through the existing
    `HostedSettings`/`HostedPhone` hosts): for every pushed settings route (Contacts: WhatsAppSettings, NameFormat,
    SortBy, SaveTo, Summary, ReminderTime, Appearance, Haptics, About, Licences, LicenceDetail; Phone: Settings,
    Diagnostics, WhatsAppSettings, Appearance, Haptics, About, Licences, LicenceDetail) assert one `back-button` node and
    a `tab-bar` showing all four tab labels (not folded). This test makes the hosts unable to mask the app again.
  - `catalog` `ChromeSpecTest` updated for the KDoc rule if it asserts Folded on a pushed page.
- Screenshots re-recorded and OPENED (light + dark): every `android/contacts/screenshots/e4.15-*` (name-format,
  accounts-summary incl. fs2, whatsapp, settings, settings-end) and any other contacts golden of a page that was Folded
  (`e4.1-settings*` if it shows a pushed page, appearance/haptics/about/licences if recorded); Phone `e5.1-settings`,
  `e5.1-diagnostics`. The re-recorded goldens must show: glass back circle
  top-left at the 16 dp gutter, large title under it, full four-tab bar with the selected tab's puck at the bottom,
  last row above the bar. Also `e4.15-name-format-rtl` (back chevron mirrored, at the right). Regenerate
  `docs/kotlin-ui/screenshots/contacts.png` (`:contacts:contactSheet`) and open it.
- Check: `bash scripts/kotlin-ui-check.sh` -> `ALL GREEN`. Commit `Kotlin UI (E5 Phone, round R1 (E5.1-E5.7a)): F16
  pushed pages get the back button and the full tab bar (owner fix a)`; epics.md: F16 Evidence under E5 and a note on
  E4.15 ("F16 fixed in round-12 rev 2 step 1").

### Step 2. Data layer: finish and verify the uncommitted work (no new screen)

- Start from the tree as found (State found): keep every file; do not rewrite what works. Compile first:
  `cd android && ./gradlew :platform:compileDebugUnitTestKotlin :phone:testPlainDebugUnitTest --tests 'dev.rovsti.phone.data.*'`;
  fix what fails. `:platform` tests that used `FakeCalls` from `src/test` must still pass from `testFixtures`
  (`./gradlew :platform:testDebugUnitTest`).
- Gaps against revision-1 Step 2 to close: `RecentsRepository` refresh coalesced with the existing `RebuildCoalescer`
  (verify it is used; `refreshNow` exists) and the `Main`-free build (all ops on `RustDispatcher`/IO); `PhoneStore`
  test for concurrent writes (two `updatePrefs` in parallel, one disk write after the 300 ms debounce, nothing lost)
  and `flush` on `ON_STOP` wired in `MainActivity` (process death keeps the last change); `PeopleIndex` releases the
  old handle when a new index replaces it and on READ_CONTACTS revoke (`drop`), tested; `CallPlacer` rules:
  emergency never asks for a SIM, two SIMs + no default -> `NeedsSim`, default SIM -> `Placed` with its
  `PhoneAccountHandle`, invalid -> `Invalid` with the core's sentence (all in `CallPlacerTest`). Every op result that
  is `Outcome.Err` is logged once and surfaced as state (no silent `?: return` that freezes the model): add an `error`
  field to `RecentsModel` and a test.
- Owner rule (b): add `PhoneContainerTest` case "constructing the container and touching `store`/`recents`/`people`
  before `Rust.init` completes issues no native call" (fake core that fails if called before init is signalled).
- Tests (must all exist and pass): `PhoneStoreTest`, `RecentsRepositoryTest`, `PeopleIndexTest`, `SimStateTest`,
  `CallPlacerTest`, `SpeedDialTest`, `BlockRepoTest`, `PhoneContainerTest` (extended).
- No screenshots (no UI). Check: `bash scripts/kotlin-ui-check.sh`. Commit `... : Data layer: PhoneStore,
  RecentsRepository, PeopleIndex, SimState, BlockRepo, SpeedDial, CallPlacer (E5.1 step 2)`; epics E5.1 -> `done` with
  an Evidence line naming the test classes.

### Step 3. Keypad (E5.2) and `DialField` in `:design`

- `:design` `text/DialField.kt`: `BasicTextField(TextFieldState)` with `InterceptPlatformTextInput` that never starts
  the IME (verify the API at our Compose BOM; fallback `LocalSoftwareKeyboardController` hide on focus), caret visible
  and placeable by tap, selection handles, long-press -> the host text toolbar (`TextSelection.kt`) with Copy/Paste/
  Select All; font scaled down to fit (`dial` sizes: 36 sp -> 24 sp in 2 sp steps), single line, centred, no border.
  `DialFieldTest` (no IME request after focus; paste inserts at caret; tap moves caret).
- Keypad screen (`keypad/`): layout from a pure `KeypadLayout.compute(viewport, fontScale)` (Kotlin, JVM-tested; 3x4
  keys of 78 dp circles on a normal phone, shrinking to fit a small screen with min 64 dp, call row below) — the
  number field on top, T9 suggestion list between field and keys, call button row (call circle, delete with
  hold-to-repeat and long-press clear). Digits are `GlassPress` circles with the letters line, `KeypadTone` + haptic;
  `0` long-press `+`, `*`/`#` long-press `,`/`;` (U6 pause/wait via `dial.tonePlan`); `1` shows the voicemail glyph and
  long-press calls voicemail or toasts "No voicemail number set"; long-press 2-9 = speed dial (`speedDialOnHold`:
  call, or "Assign Speed Dial" sheet).
- T9 (AC2): `PeopleIndex` T9 query on every digit change (debounced to the next frame, off Main), list of up to 3 rows
  (avatar, name with matched letters bold, number), tap fills and calls; "N more" opens a sheet.
- Field menu (U14/U53 via `dial.fieldMenu`): Copy, Paste, Clear (+ Undo pill), Share, Copy as +E.164 — a
  `ButtonMenuEntry` anchored to the field on long-press of the field area outside text selection. U32 smart paste
  (`dial.pastePlan`/`extractNumbers`): several numbers -> chooser `ActionSheet`. U29 "Add to Contacts" / "Create
  New Contact" row under the field when the number is unknown (handoff to `:contacts` editor via `Handoff`, or the
  system insert intent). U31 redial: empty field + call -> `dial.redialTarget` fills the field. U47 long-press Call ->
  `IosMenu`: per-SIM call rows (SIM dot colour), WhatsApp (disabled in R1, E5.8), Message (SMS intent). Dual-SIM
  chooser `SimChooserSheet` when `CallPlacer` emits `NeedsSim` (rows with the SIM dot, "Remember for this contact" not
  in GPUI: do not add).
- Tests: `KeypadLayoutTest` (sizes at 360x640, 412x915, fs2), `KeypadViewModelTest` (typing, tones text, paste plan
  chooser, redial, speed dial hold, voicemail missing toast, clear+undo), `KeypadScreenTest` (`autoAdvance=false`:
  press scale and release spring; delete hold repeats; long-press 0 gives `+`), recomposition count while typing (<= 2
  per key).
- Screenshots: `e5.2-keypad-empty`, `e5.2-keypad-typing-t9`, `e5.2-keypad-field-menu`, `e5.2-keypad-selection`,
  `e5.2-call-longpress-menu`, `e5.2-smart-paste-chooser`, `e5.2-sim-chooser-sheet`, `e5.2-keypad-fs2`,
  `e5.2-keypad-small-screen`. Open each (keys never overlap the tab bar; field text not clipped; menus float).

### Step 4. Recents (E5.3)

- `RecentsScreen` (`ScreenLazyPage`, large title "Recents", search in the title area): rows from `RecentsModel`:
  40 dp avatar, name (red when missed) + count "(3)", one line under it: type glyph + label + U48 country of an
  unknown number, trailing time + info button; WhatsApp calls show the 14 dp `SourceGlyph` after the name; dual-SIM
  phones show a small SIM dot chip (S26) next to the time. U15 date sections from `sectionStarts`. Missed badges on the
  tab item (`missedExtras`), U39 mark missed seen on open.
- Filter chips (U3 + U35): All, Missed, Incoming, Outgoing, WhatsApp, With notes in ONE `ChipRow` `LazyRow` under the
  title, counts as secondary labels; search (U21 notes search) through `callLogQuery`.
- Row context menu (long-press, `ContextMenuEntry`): Call back, Message, WhatsApp call (disabled R1), U20 quick reply
  (`replyChoices` -> `ActionSheet`), U13 Remind me later (submenu 15 min/1 h/tonight -> `CallReminders`), U25/U56 copy
  number / copy with country, U4 Copy/Share as (`historyText`), Add to contacts, Block (`block_ui` confirm ActionSheet,
  then undo pill S27), Delete with Undo (`CallLogWrite` + restore). Swipe left: Delete; swipe right: Call (76 dp).
- U12/U45 select mode (toolbar "Select"): `SelectCheck`, toolbar accessory Delete / Block / Share with one Undo.
- Tests: `RecentsViewModelTest` (filters, notes search, delete+undo restores, block confirm + undo, select-mode bulk
  delete one undo, remind later schedules), `RecentsScreenTest` (chips never wrap at fs2 and scroll horizontally; last
  row above the bar; long-press menu floats, list does not move), recomposition count while scrolling.
- Screenshots: `e5.3-recents`, `e5.3-recents-end-above-bar`, `e5.3-recents-missed-filter`, `e5.3-recents-chips-scrolled`,
  `e5.3-recents-row-menu`, `e5.3-recents-select-mode`, `e5.3-recents-search`, `e5.3-recents-whatsapp-badge`,
  `e5.3-recents-dual-sim-chip`, `e5.3-block-confirm`. Open each.

### Step 5. Contact, Number and Call details sheets (E5.4)

- `PersonSheet` (`SheetEntry` medium -> large, from a Recents info button / Favorites / Contacts row): header avatar +
  name + number label; quick actions as three equal capsules (Call, Message, Copy/Share/Block in "..." `IosMenu`);
  numbers (tap = call through `CallPlacer`, hold = call circle SIM chooser when a default exists); history lines
  (`history`), U7 per-person stats (`statsPerson`: calls, minutes, last), U36 copy all numbers, U9 QR (`qr.encode`,
  `QrCode` from `:design`), U16 export person CSV (`callLogCsv` -> `Documents.create`), U18 share stats, U26 share
  details, per-person quick reply (`person_ui`), Delete call history confirm (`history_ui`, undo). `NumberSheet` is the
  same sheet for an unknown number (Add to contacts row, U48 country line).
- `CallDetailsSheet` (U11, tap a history line): time, duration, type, SIM; Copy, Share, U8 note (`GlassTextField`
  multi-line inside the sheet, `noteSet`/`noteRemove`/`noteRestore` with Undo; the sheet rises with the IME using
  `imeOverlapPadding`), Delete this call (undo), U44 Remind me.
- Tests: `PersonSheetViewModelTest`, `CallDetailsViewModelTest` (note save/undo, delete undo), Compose: sheet drags
  between detents and dismisses past +12 %; note field shows caret and the paste menu.
- Screenshots: `e5.4-contact-sheet`, `e5.4-number-sheet`, `e5.4-call-details`, `e5.4-call-note-editing`,
  `e5.4-person-stats`, `e5.4-qr`. Open each.

### Step 6. Favorites and Contacts tabs (E5.5)

- Favorites: `prefs.favorites_order` + starred contacts; grid-free list rows (avatar, name, label) with a trailing info
  button; Edit mode with drag reorder reusing the Convx reorderable port of `:contacts`
  (`favorites/FavoritesReorder.kt`; move the generic part to `:design` if it is not app-specific, keeping its Convx
  header) plus "Move Earlier/Later" menu items for accessibility; "Frequent" section from `frequent`. Tap = call,
  info = PersonSheet. Empty state with "Add Favorite" (contact picker).
- Contacts tab: the `:contacts` list pattern (sections, alphabet rail `AlphabetScrollBar`, search) over `PeopleIndex`;
  tap opens the PersonSheet (not the full card); toolbar "+" hands off to the Contacts app editor.
- Tests: `FavoritesViewModelTest` (order persisted via `PhoneStore`, move earlier/later), `PhoneContactsViewModelTest`
  (search, sections), Compose reorder drag with `autoAdvance=false`.
- Screenshots: `e5.5-favorites`, `e5.5-favorites-reorder`, `e5.5-contacts-tab`, `e5.5-contacts-search`. Open each.

### Step 7. Incoming call (E5.6) and the call aura

- Aura: port Convx `ArtworkMeshBackground.kt` + `AnimatedChromeMesh.kt` to `:design` `media/CallAura.kt` (Convx header +
  "Ported from Convx ..." line), adapted: colours from the contact avatar (`rovsti_core::avatar` colour through
  `ContactsOps.avatar` or the existing `Avatar` palette) or the photo; runs only per ARCH 10 rule 5 (`MotionGate`,
  lifecycle RESUMED, <= 30 fps), static gradient otherwise.
- `IncomingScreen(source: CallSource)` from `CallRegistry.snapshot()` (`IncomingCall`): full screen, aura, avatar
  (`rovstiSharedBounds("call-avatar")` for the S3 flight into In-call), name/number/label, SIM chip, Answer / Decline
  circles (GlassCircleButton, green/red), secondary row: Message (decline with message -> `replyChoices` sheet ->
  `rejectWithMessage`; "Custom..." opens `rejectAndCompose`), Remind me (`CallReminders`), Silence on volume key
  (`silenceRinger`). Waiting-call card (`apps/phone/src/waiting_card.rs`) over In-call: "End & Answer"
  (`answerEndingHeld`/`answerReplacing`), "Hold & Answer" (`answerHolding`), Decline. Lock screen variant: same screen
  with `setShowWhenLocked`; the notification path is untouched (CallStyle first) and the first-frame report goes
  through `IncomingPerf` (`incomingFrame(callId)` on the first `withFrameNanos`). `NativeIncoming` shown if the Compose
  screen fails to compose (error boundary in `MainActivity`).
- Tests: `CallRegistryIncomingTest` (JVM, AC1: notification posted before any UI, answer from the notification action
  works with no activity), `IncomingViewModelTest` (answer once, decline, reply choices, waiting-call actions map to the
  right `CallsApi` call), `IncomingScreenTest` (first frame reported once; aura stops when reduce motion is on).
- Screenshots: `e5.6-incoming`, `e5.6-incoming-lockscreen-variant`, `e5.6-decline-with-message-sheet`,
  `e5.6-waiting-call-card`. Open each (the aura is drawn at a fixed phase in shots).

### Step 8. In-call single call (E5.7a), close the round, gate, APKs

- `InCallScreen(source: CallSource)`: aura, avatar (S3/S4 flight from Incoming or from the keypad call button,
  `Motion.sharedArtwork()` 500 ms), name, timer (`formatElapsed` once per second via `produceState`, read in a text
  lambda), SIM chip; 3x2 control grid of `GlassCircleButton`s: Mute, Keypad, Audio, Add call (disabled R1, E5.7b), Hold,
  Contacts/Note; End button. Audio route picker = `Popover` anchored to Audio (`audio.rows` from `AudioPath`
  endpoints; Bluetooth rows hidden when `BLUETOOTH_CONNECT` is denied, ARCH 7.5) ; in-call keypad (DTMF via
  `CallsApi.dtmf`, U49 paste tones + Stop with `pasteTonesLabel`, U50 copy digits, U51 remember tones), U17 note +
  copy number, U37 copy timer / share summary (menu on the timer). Proximity via `ProximityController` while live.
  Collapse to a mini pill over the app when the user navigates away (Convx player sheet motion numbers), tap expands.
  Call ended: summary line for 2 s then dismiss; post-call notice pill with Block / Undo (`notice.rs`).
- Close: motion audit and `references/checklist.md` A-H for every R1 screen in `docs/kotlin-ui/checklist-e5.md`;
  contact sheet `docs/kotlin-ui/screenshots/phone.png` (`:phone:contactSheet`, opened and judged). Full gate:
  `cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace`,
  `cargo check -p rovsti_android --target aarch64-linux-android`, `bash scripts/kotlin-ui-check.sh`. Build the
  plain debug APK of `:phone` (`android/phone/build/outputs/apk/plain/debug/phone-plain-debug.apk`) and rebuild
  `android/catalog/build/outputs/apk/debug/catalog-debug.apk`; state both paths. Update epics.md: E5.1-E5.7a `done`
  with Evidence lines; next planner = E5 R2.
- Tests: `InCallViewModelTest` (mute/hold/route/dtmf map to `CallsApi`; paste tones stop; ended -> summary -> pill),
  `RoutePickerTest` (no BT grant -> no BT rows, no crash), `InCallScreenTest` (timer recomposes only the text; the
  screen is shown over the lock screen only while live).
- Screenshots: `e5.7-incall-active`, `e5.7-incall-keypad`, `e5.7-route-picker-popover`, `e5.7-incall-sim-chip`,
  `e5.7-incall-ended-summary`, `e5.7-post-call-block-pill`. Open each.

## Verification list for the next planner

Test classes that must exist: `PhoneManifestTest`, `PhoneRoutesTest`, `PhoneMigrationTest`, `InCallServiceTest`,
`SetupSheetViewModelTest`, `PhoneStoreTest`, `RecentsRepositoryTest`, `PeopleIndexTest`, `SimStateTest`,
`CallPlacerTest`, `SpeedDialTest`, `DialFieldTest` (`:design`), `KeypadLayoutTest`, `KeypadViewModelTest`,
`KeypadScreenTest`, `RecentsViewModelTest`, `RecentsScreenTest`, `PersonSheetViewModelTest`, `CallDetailsViewModelTest`,
`FavoritesViewModelTest` (phone package), `PhoneContactsViewModelTest`, `CallRegistryIncomingTest`,
`IncomingViewModelTest`, `IncomingScreenTest`, `InCallViewModelTest`, `RoutePickerTest`, `InCallScreenTest`.
Goldens: every `e5.1-*`..`e5.7-*` name above in `android/phone/screenshots/`, light and dark. Files:
`android/phone/src/main/AndroidManifest.xml`, `design/.../text/DialField.kt`, `design/.../media/CallAura.kt` (Convx
header), `crates/rovsti_app_core/tests/fixtures/dirs/phone/wa-calls.json`, `docs/kotlin-ui/checklist-e5.md`,
`docs/kotlin-ui/screenshots/phone.png`.

Added by revision 2: `PageBackTest` (`:design`), `PushedPagesBackTest` (`:contacts` and `:phone`), the Folded invariant in
`ContactsRoutesTest`/`PhoneRoutesTest`, `BlockRepoTest`; re-recorded `e4.15-*` and `e5.1-settings`/`e5.1-diagnostics` goldens
show the back button and the full tab bar (open them).
