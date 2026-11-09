# Round 12 plan: E5 R1 = Phone app module, data layer, keypad, Recents, sheets, Favorites/Contacts tabs, Incoming, In-call (E5.1-E5.7a)

Planned 2026-11-09 at base `c34b155` (branch `ui-overhaul`). Epic: **E5 Phone**, round R1. E1-E4 are done (E4 R2
re-verified in code below). E5's hard dependencies (E1 done; E2.9, E2.10 `ContactIndex`, E2.14; E3.2, E3.3, E3.5,
E3.8a/b, E3.9, E3.10, E3.11) are all `done`.

Owner decisions still in force: Rovsti is GPL-3.0-or-later (E1.23, `542e271`; re-checked: `LICENSE`, root
`Cargo.toml` `license = "GPL-3.0-or-later"`, `CLAUDE.md` GPL line, `android/THIRD_PARTY_NOTICES.md` with Convx,
Kyant0 backdrop / AndroidLiquidGlass, compose-hig, glisse, reorderable). The glass bug is closed by the Convx backdrop
port (E1.24-E1.26, `GlassRealPathTest`). **Where a Convx file fits, port it close to verbatim** with the original
header plus `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`; glisse-derived
code keeps the glisse MIT header. In this round that applies to the call aura (Convx
`app/src/main/kotlin/com/convx/music/ui/component/ArtworkMeshBackground.kt` + `AnimatedChromeMesh.kt`, lifecycle- and
`MotionDurationScale`-aware) and the in-call expand/collapse (Convx `ui/player/PlayerSheetMotion*` numbers, see
`app/src/test/kotlin/com/convx/music/ui/player/PlayerSheetMotionTest.kt`). The GPUI app (`apps/phone/src/*.rs`,
45 604 lines) is a **feature inventory only**, never a design reference.

**Scope change:** E5.8 (WhatsApp calls) moves from R1 to R2. R1 already carries eight big stories (E5.1-E5.7a) and
E5.8 needs both call screens finished; R2 is now E5.7b, E5.8, E5.9-E5.14 (8 stories). The In-call and Incoming screens
of this round take a `CallSource` parameter from day one (`Cellular` only wired) so E5.8 only adds the WhatsApp source.

## State found (verified in code, not trusted)

- `git status` clean, HEAD `c34b155` ("Polish and close E4 (E4.16)"); `ce32f09` ("Fix start-up crash: native calls
  wait for Rust.init") landed after the round-11 plan and stays. `stash@{0}` is the deprecated GPUI layout WIP: never pop.
- **E4 R2 done**, checked file by file: every test class the round-11 evidence names exists under
  `android/contacts/src/test/kotlin/dev/rovsti/contacts/**` (`ContactShareTest`, `QrSheetTest`, `IntakeViewModelTest`,
  `IntakeScreenTest`, `ScanQrTest`, `CsvImportViewModelTest`, `CsvMappingTest`, `DupesViewModelTest` (named
  `Duplicates...` in the evidence; the class is `DupesViewModelTest`), `CleanupViewModelTest`, `DupesScreenTest`,
  `CleanupScreenTest`, `PhotoViewerActionsTest`, `CallOptionsViewModelTest`, `CallOptionsSectionTest`, `MeViewModelTest`,
  `EmergencyViewModelTest`, `RecentListsViewModelTest`, `ContactsSettingsViewModelTest`, `SettingsScreenTest`);
  `QrCodeTest` in `:design`; 164 goldens in `android/contacts/screenshots/` incl. every `e4.9-*`..`e4.15-*` name;
  `IntakePlaceholderPage` is gone; `docs/kotlin-ui/screenshots/contacts.png` exists. E4 stays `done`.
- **Screenshots of the previous round, opened and judged:**
  - `docs/kotlin-ui/screenshots/contacts.png` (contact sheet, 84 light goldens): consistent chrome, one avatar per row,
    menus float, last rows clear the bar. Correct.
  - `android/contacts/screenshots/e4.15-settings-light.png`: groups with section gap (F13 fixed), values trailing,
    footer wraps. Correct. **F15 (check, not a known bug):** at the leading end of the floating tab bar a large blue
    blob (about 45 dp wide, the full cap height) is the lens refraction of the blue "Import from a File" tile scrolled
    under the bar; the same happens in `e4.14-my-card-dark.png` (teal/orange from avatars under the Contacts tab). It
    stays inside the capsule, so it is the real backdrop path, but it is much stronger than the thin edge band of iOS 26.
    Step 1 compares our tab-bar lens parameters (`ios/component/IosTabBar.kt`, `chrome/floatingtabbar/*`) with
    Convx's (`app/src/main/kotlin/com/convx/music/ui/ios/component/IosTabBar.kt` / its `lens(...)` call: refraction
    height and amount) and restores Convx's values if ours differ; if they are equal, nothing changes and the finding is
    closed with that note.
  - `e4.14-my-card-dark.png`: My card row, Emergency/Recent card, rail, WA glyph once per row, list continues under the
    bar. Correct.
- What E5 starts from:
  - **Platform (E3.3/E3.5)**: `android/platform/.../telephony/`: `CallRegistry : CallsApi` (answer, reject,
    disconnect, hold, mute, route, routeEndpoint, dtmf, swap/merge/split, answerReplacing/Holding/EndingHeld,
    rejectWithMessage, silenceRinger, selectAccount, `snapshot()`, `multiCallFacts()`), `InCallHost` seam (the service
    implements it; `:platform` names no app class), `CallNotifications` (CallStyle first), `CallSurface`,
    `IncomingCall`/`AnswerOnce`, `IncomingTiming` seam, `AudioPath`, `ProximityController`, `KeypadTone`,
    `SimRepository` (+ `FakeSimRepository` in testFixtures), `DialIntents`, `RejectPolicy`, `MultiCall`, `VoicePlayer`.
    `FakeCalls` lives in `platform/src/test` (not testFixtures): Step 2 moves it to `testFixtures` so `:phone` tests can
    use it. `calllog/`: `CallLogRepository` (+ fake), `BlocklistRepository` (+ fake), `MissedCalls`. `notify/`:
    `CallReminders`, `Reminders`. `:wa`: `WaCalls` (place/accept/reject/end/mute/speaker, audio pump).
  - **Stays in the app** (`docs/kotlin-ui/platform-port.md:73-90`): `RovstiPhoneApp`, `MainActivity`,
    `RovstiInCallService`, `CallActionReceiver`, `VoicemailReceiver`, `IncomingPerf` (implements `IncomingTiming`),
    `NativeIncoming` (fallback curtain) from `apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/*.kt`.
  - **Rust ops**: `PhoneOps.kt` (`core-bridge/.../ops/PhoneOps.kt`, 76 ops): `recents.*` (group, build, refreshDelta,
    mergeExtras, missedExtras, history, historyText, sectionStarts, callLogQuery, frequent, callCounts, idsForNumbers,
    csv, prunePlan), notes (set/remove/restore/rows/forCalls/callDetails/export), stats (overview/person/bucket),
    speed dial (onHold/assign/unassign/freeDigits/digitFor/badges), block (check/parse/parseMany/parseFile/lines/
    callLine), `dial.*` (normalisePaste, fromTelUri, validate, fieldMenu, extractNumbers, pastePlan, redialTarget,
    tonePlan, tonesText, tonePreview, pasteTonesLabel, isEmergency), `sim.build`/`sim.dot`, `setup.*` (steps,
    checklist, xiaomi, reopened), `audio.rows`/`audio.diff`, `scene.of`/`diff`/`texts`, prefs/notes load/save,
    `replyChoices`, `keypadTones`, WA tracker ops, `backup*`, `displayName`. `SearchOps` (ContactIndex, T9 through
    `query` with the keypad digits; verify the T9 mode exists in `crates/rovsti_ffi/src/ops/search.rs`, else add
    `search.t9` over `rovsti_core::t9` with fixtures, Rule 2).
  - **Migration fixtures**: `crates/rovsti_app_core/tests/fixtures/dirs/phone/{prefs.json,call-notes.json}` and
    `dirs/wa/whatsapp-link.json`. There is **no `wa-calls.json` fixture** (file name `HISTORY_FILE` in
    `crates/rovsti_app_core/src/phone/wa_call.rs:23`): Step 1 adds one from that module's own test data. Phone keeps no
    `sim-choices.json` (that file is the Messages legacy SIM file, `crates/rovsti_app_core/src/messages/prefs.rs:22`;
    Phone decides "ask every time" from the system default, `apps/phone/src/sim_ui.rs:191-215`): E5.1 AC2 is corrected
    in epics.md.
  - **Manifest**: `apps/phone/platforms/android/app/src/main/AndroidManifest.xml` (+ `src/firebase/` with
    `.RovstiMessagingService`); expected permission set `android/platform/src/test/resources/manifest/phone.permissions.txt`.
    `<queries>` for EMERGENCY_ASSISTANCE, HEARING_DEVICES_SETTINGS, SHOW_CALL_SETTINGS; `.MainActivity` with MAIN,
    two DIAL filters, VIEW `tel:`; `activity-alias .WaHandoff` (`dev.rovsti.action.WA_CALL`, HANDOFF permission);
    `.RovstiInCallService` (permission, `IN_CALL_SERVICE_UI` meta-data and filter exactly as in the GPUI file); `.CallActionReceiver`,
    `.VoicemailReceiver` (`SHOW_VOICEMAIL_NOTIFICATION`), `dev.rovsti.bridge.CallReminderReceiver`,
    `dev.rovsti.bridge.RovstiShareProvider`. GPUI `versionName` `0.1.0`.
  - **`:design` gap**: no dial field. `GlassTextField` (`text/GlassTextField.kt:88`) always raises the IME. The keypad
    needs a `BasicTextField(TextFieldState)` with caret, selection, the host text toolbar (`text/TextSelection.kt`) and
    **no soft keyboard** (Step 3 adds `DialField`).
  - Templates to copy structure from: `android/contacts` (container, routes, `ContactsManifestTest`,
    `ContactsMigrationTest`, ViewModel/Screen/ShotTest split, `contactSheet` task from `ScreenshotsConventionPlugin`).
- **Disk: 4.2 GB free, below the 6 GB limit.** Biggest: `target` 5.6 GB, `~/.gradle/caches` 7.3 GB,
  `android/core-bridge/build` 1.3 GB, `android/contacts/build` 687 MB, `android/catalog/build` 672 MB. Step 1 starts
  with the deep clean (Rule 1) before anything else.

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

### Step 1. Disk, F15 check, `:phone` module, manifest parity, components, container, tabs, setup sheet, diagnostics, migration (E5.1)

- Disk clean to >= 8 GB (Rule 1, 7 GB level). F15 check as written in State found (lens values vs Convx; re-record and
  open `e4.15-settings-*`, `e4.14-my-card-*` only if values change).
- `android/phone`: plugins `rovsti.android.app`, `rovsti.compose`, `rovsti.screenshots`, `kotlin.serialization`;
  namespace/`applicationId` `dev.rovsti.phone`, `versionCode = 100 + ROVSTI_BUILD_NUMBER`, `versionName "0.1.0"`;
  product flavors `plain` and `firebase` (only `:phone` has them so far; the FCM dependency and
  `src/firebase/AndroidManifest.xml` in the `firebase` source set, as the GPUI Gradle file did); depends on `:shell` (+ `:wa`); tests on
  `:testing` and testFixtures of `:platform`, `:wa`, `:core-bridge`, `:shell`. `include(":phone")`. App icon from
  `apps/phone/assets/app.png`. Release signing already in the convention plugin.
- Manifest rebuilt from the GPUI one (State found), drop `dev.gpui.android.*` and the vulkan `uses-feature`.
  App classes (FQCN kept, ARCH 7.2), ported from `apps/phone/platforms/android/app/src/main/kotlin/dev/rovsti/phone/`
  onto `:platform`: `RovstiPhoneApp : Application, HasContainer<PhoneContainer>`; `MainActivity : RovstiShellActivity`
  (`setShowWhenLocked`/`setTurnScreenOn` only while `CallRegistry` reports a live call, cleared on idle);
  `RovstiInCallService : InCallService, InCallHost` (forwards to `CallRegistry.onServiceBound/onCallAdded/...`);
  `CallActionReceiver`, `VoicemailReceiver` (calls `VoicePlayer`/notify), `IncomingPerf : IncomingTiming`,
  `NativeIncoming` (kept as the error view); `RovstiMessagingService` in `src/firebase` (calls `WaPush`).
- `PhoneContainer : ShellContainer`: lazily `calls: CallsApi = CallRegistry`, `callLog`, `blocklist`, `contacts`,
  `sims: SimRepository`, `ops: PhoneOps`, `search: SearchOps`, `qr: QrOps`, `wa: WaRepository`, `waCalls: WaCalls`,
  `clipboard`, `specialAccess`, `store: PhoneStore` (Step 2), `commonSettings` (appearance/haptics from `PrefsV2`
  settings if the fields exist, else `null` as E4 R1 did).
- Navigation: `@Serializable sealed interface PhoneRoute`: tab roots `Favorites`, `Recents`, `Contacts`, `Keypad`
  (titles/icons from `apps/phone/src/main.rs:90-96`: star / clock / person.2 / circle.grid.3x3 in `RovstiIcons`),
  `Settings` (pushed from a toolbar gear on each root, as the GPUI app), `Diagnostics` (hidden: 7 taps on the Settings
  version row, as `apps/phone/src/devtools.rs`), `Incoming(callId)`, `InCall`, `WhatsAppSettings`, common settings
  routes. `chromeFor`: roots Visible + large title (Keypad: no large title, tab bar visible); Incoming/InCall Hidden
  and full-screen (no tab bar, no nav chrome). `routeFor(intent)`: DIAL / VIEW `tel:` -> Keypad prefilled via
  `dial.fromTelUri`; `CallNotifications` content intents -> Incoming/InCall; `WA_CALL` handoff -> Keypad (WA place
  is E5.8, R2: show the number with the WhatsApp chip disabled and a toast "WhatsApp calls arrive in a later update").
- Setup sheet (`apps/phone/src/setup_ui.rs`): `SetupSheet` (`SheetEntry` medium/large) listing `setup.steps` +
  `setup.checklist(facts)` rows (default dialer role -> `RoleManager.createRequestRoleIntent(ROLE_DIALER)`, contacts,
  call log, notifications, full-screen intent, battery; each row's next action from `PermissionState`); shown on first
  launch while the role is missing and re-checked on every `ON_RESUME` (`setup.reopened`). Notice line under the Recents
  title ("Rovsti is not your phone app", tap opens the sheet).
- Diagnostics page: `ScreenPage` with `CallsApi.eventDump()`, `blockGuardHits`, `silenceCallbacks`, route latency,
  `IncomingPerf` timeline, Rust core version; Copy all (toast).
- Tests: `PhoneManifestTest` (merged manifest vs `phone.permissions.txt` and the GPUI component/filter set, both
  flavors), `PhoneRoutesTest` (DIAL, VIEW tel with `#`/`,`, notification intents, one-shot extras), `PhoneMigrationTest`
  (fixture dir `dirs/phone` + new `wa-calls.json` + `dirs/wa/whatsapp-link.json` copied into `noBackupFilesDir`:
  prefs load keeps quick replies and speed dial 2, notes load, WA history load), `InCallServiceTest` (Robolectric:
  bound service hands an `InCallHost` to `CallRegistry`), `SetupSheetViewModelTest` (rows from the checklist fixture,
  role row action, resume re-check).
- Screenshots: `e5.1-setup-sheet`, `e5.1-diagnostics`, `e5.1-notice-line`, `e5.1-tabs` (light/dark). Open each.

### Step 2. Data layer: `PhoneStore`, `RecentsRepository`, people index, SIMs, block (no new screen)

- Move `FakeCalls` from `platform/src/test` to `platform/src/testFixtures` (same package).
- `dev.rovsti.phone.data`: `PhoneStore` (one `StateFlow<PrefsV2>` + notes; `prefsLoad/Save`, `notesLoad/Save` on IO,
  writes serialised by a `Mutex`, debounced 300 ms); `RecentsRepository` (call-log observer + `WaCalls` history ->
  `recents.build`/`refreshDelta`/`mergeExtras`/`missedExtras` -> `StateFlow<RecentsModel>`; Rust decides grouping,
  sections and labels; refresh coalesced with `RebuildCoalescer`); `PeopleIndex` (book pull -> `SearchOps.indexNew`,
  handle kept, `query` for search and T9, `displayName`, `idsForNumbers`); `SimState` (`SimRepository` ->
  `sim.build`/`sim.dot`, exposes `ask`, default, dot colours); `BlockRepo` (`BlocklistRepository` + `block.check`/
  `blockLines`, Undo snapshots); `SpeedDial` (`speedDialBadges/digitFor/onHold/assign/unassign` over `PhoneStore`).
- Effects helper `CallPlacer`: validate (`dial.validate`, `dial.isEmergency`) -> SIM choice needed? (Rule:
  `apps/phone/src/sim_ui.rs:191-215`) -> `CallsApi`/`DialIntents.place(number, account)`; emits
  `NeedsSim(number, choices)` or `Placed`.
- Tests: `PhoneStoreTest` (round-trip, debounce, concurrent writes), `RecentsRepositoryTest` (fixture call log + WA
  history -> rows from the `recents.build` fixture; delta refresh keeps ids), `PeopleIndexTest`, `SimStateTest`,
  `CallPlacerTest` (emergency never asks for a SIM; two SIMs + ask -> NeedsSim; default SIM -> placed with its handle),
  `SpeedDialTest`.

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
