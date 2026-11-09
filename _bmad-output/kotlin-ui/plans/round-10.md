# Round 10 plan: E4 R1 = Contacts app module, list, card, editor, Trash, favorites, groups, select (E4.1-E4.8)

Planned 2026-11-09 at base `ed92dda` (branch `ui-overhaul`). Epic: **E4 Contacts**, round R1. E1, E2 and E3 are done;
E4's hard dependencies (E1 done; E2.7, E2.10; E3.2, E3.6, E3.8a/b, E3.10, E3.11) are all `done`.

Owner request for this workflow: "make our project GPL, port the code with very high similarity". The relicensing was
delivered in R3 (E1.23, `542e271`) and re-checked at this base: `LICENSE` is GPL-3.0, `Cargo.toml` has
`license = "GPL-3.0-or-later"`, `CLAUDE.md` has the GPL/attribution line, `android/THIRD_PARTY_NOTICES.md` lists Convx,
Kyant0 backdrop / AndroidLiquidGlass, compose-hig and glisse. The glass bug is closed by the Convx backdrop port
(E1.24-E1.26, `GlassRealPathTest`). The rule for this round: **where a Convx file fits, port it close to verbatim**
with the Convx header plus `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`.
In E4 R1 that applies to the favorites reorder (Convx `ui/screens/playlist/LocalPlaylistScreen.kt:536-600` with
`sh.calvin.reorderable` 3.0.0, Apache-2.0, as Convx `gradle/libs.versions.toml:28,80` pins it) and to anything else a
step names. The GPUI app (`apps/contacts/src/*.rs`, 20 584 lines) is a **feature inventory only**, never a design
reference.

## State found (verified in code, not trusted)

- `git status` clean, HEAD `ed92dda` (E3 close). `stash@{0}` is the deprecated GPUI layout WIP: never pop it.
- **E3 R2 done**, checked file by file: `android/settings.gradle.kts` includes `:wa` and `:shell`; every test class the
  round-09 verification list names exists (`PermissionStateTest`, `PermissionControllerTest`, `HandoffPermissionsTest`,
  `WaRepositoryTest`, `WaCallsTest`, `WaAvatarsTest`, `WhatsAppSettingsViewModelTest`, `WhatsAppSettingsScreenTest`,
  `PermissionGateTest`, `RefreshRateTest`, `NoticesParserTest`, `RovstiShareProviderTest`, `NoMediaPermissionTest`,
  `E311SettingsShotTest`, `SettingsPagesTest`, `LicenceAssetsTest`, `CommonSettingsTest`, `RouteForPatternTest`,
  `ShellContainerTest`); `crates/rovsti_app_core/src/wa_link/settings.rs` + `settings_view.rs` exist and
  `crates/rovsti_wa_ui/src/model.rs:4` re-exports them; 44 `wa.settings_apply.*` fixtures; 15 goldens in
  `android/wa/screenshots/`, 15 in `android/shell/screenshots/`; `catalog-debug.apk` built. E3 stays `done`.
- **Screenshots of the previous round, opened and judged:**
  - `android/wa/screenshots/e3.10-wa-connected-dark.png`: status row with green dot, three action rows, destructive
    Delete in its own card, footer wraps. Correct.
  - `android/shell/screenshots/e3.11-permission-rationale-light.png`: medium sheet with grabber, icon, title, body,
    Continue / Not now; page behind pushed back and dimmed. Correct.
  - `android/shell/screenshots/e3.11-permission-denied-row-dark.png`: **finding F11**: the cards sit at ~32 dp from the
    screen edge and the section headers at ~40 dp while the large title is at 16 dp; every other page (e.g.
    `e3.10-wa-connected`) has 16 dp cards. Cause: the test host `PermissionTestSupport.kt:59-60` pads the column by
    `Spacing.gutter` and `InsetGroupedSection` adds its own gutter (double gutter). The same host draws the rationale
    shot (cards ~48 dp in). The component is fine; the demo page is wrong, and E4 must not copy it. Fixed in Step 1.
- What E4 starts from:
  - Manifest: `apps/contacts/platforms/android/app/src/main/AndroidManifest.xml` (Application `.RovstiContactsApp`,
    `.MainActivity` singleTop with MAIN/LAUNCHER, SEND and VIEW(content) for `text/x-vcard`, `text/vcard`,
    `text/directory`; `dev.rovsti.bridge.BirthdayReminderReceiver`; `dev.rovsti.bridge.RovstiShareProvider`
    `${applicationId}.rovstishare`; `<queries>` for `dev.rovsti.action.WA_CALL`/`WA_CHAT`; HANDOFF uses-permission and
    `<permission ... signature>`; `allowBackup=false`, `supportsRtl=true`). Expected permission set:
    `android/platform/src/test/resources/manifest/contacts.permissions.txt`. The receiver and provider classes already
    exist in `:platform` (`notify/NotifyReceivers.kt:44`, `files/RovstiShareProvider.kt:18`).
  - `AndroidAppConventionPlugin.kt` sets only common config and `targetSdk`: **no release signing yet** (E4.1 AC1).
  - Data: `ContactsRepository` (`platform/contacts/ContactsRepository.kt:31-102`: `all`, `withNumbers`, `favorites`,
    `full`, `detail`, `save`, `delete`, `setStarred`, `groups`, `saveGroup`, `deleteGroup`, `setMembers`, `accounts`,
    `photoSize`, `setPhoto`, `removePhoto`, `options`, `setRingtone`, `setVoicemail`, `import`) with
    `FakeContactsRepository` in testFixtures; `CallLogRepository` + fake; `Shortcuts`; `ContactCallOptions`.
  - Rust ops: `ContactsOps` (`core-bridge/.../ops/ContactsOps.kt:142-339`, 66 ops incl. `view`, `scrubber`, `quick`,
    `card`, `fieldMenu`, `dates`, `age`, `eventLine`, `mapsUrl`, `webUrl`, `relationTap`, `draftValidate`,
    `draftDiff`, `draftEdit`, `draftFromQuery`, `draftAdd`, `history`, `frequent`, `recentViewed`, `recentLists`,
    `favoritesMove`, `selectBar`, `selectExport`, `groups*`, `trash*`, `photoPlan`, `photoCrop`, `photoAccepts`,
    `prefsLoad`/`prefsSave`, `deepIndexNew`/`deepFind`); `SearchOps` (ContactIndex: `indexNew`, `query`,
    `queryLoose`, `fold`); `QrOps`.
  - **Gap:** the WhatsApp decisions of AD-CT6 live in `crates/rovsti_app_core/src/contacts/whatsapp.rs`
    (`marked_contacts` :206, `WaPanel::{target, lookup_request, jid, jid_for_number, status}` :51-98,
    `wa_chip_index` :159, `status_line` :173, `profile_lines` :237) and only `lookup_jids` is reachable over FFI
    (`crates/rovsti_ffi/src/ops/wa/pure.rs:9`). Step 2 exposes the rest.
  - Migration fixtures: `crates/rovsti_app_core/tests/fixtures/dirs/contacts/{contacts-prefs,contacts-trash}.json`
    and `.../dirs/wa/whatsapp-link.json`.
  - `ContactsPrefs` (`core-bridge/.../model/contacts/StoreModels.kt:100-115`) has **no appearance and no haptics**
    field, so the Contacts `CommonSettings` answers `null` for both (rows hidden), as `shell-usage.md` allows. Adding
    them is E4.15 (R2), not this round.
  - `:design` has everything the screens need: `ScreenLazyPage`/`ScreenPage`/`LargeTitleScaffold`, `SearchField`,
    `GlassTextField`, `AlphabetScrollBar` (Convx port), `ListRow`, `InsetGroupedSection`, `IosList`, `Avatar`,
    `SourceGlyph`, `SwipeRow`/`IosSwipeActions`, `ContextMenuEntry`/`PreviewCardEntry`, `ButtonMenuEntry`/`IosMenu`,
    `Popover`, `SheetEntry`, `Alert`/`ActionSheet`, `Toast`/undo pill, `PhotoViewer`, `RovstiImage`, `ChipRow`,
    `EmptyState`, `rovstiSharedBounds`.
- Disk: **7.4 GB free** (limit 6 GB). Clean before Step 1 (Rule 1).

## Rules for this round

1. **Disk first, every step.** `df -h .` before any build. Below 8 GB: `rm -rf target/debug/incremental
   target/aarch64-linux-android/debug/incremental android/*/build`. Below 7 GB: also `cargo clean -p` for the deprecated
   GPUI crates (`rovsti_phone rovsti_messages rovsti_stops rovsti_contacts rovsti_ui rovsti_wa_ui liquid_glass_kit`)
   and delete Gradle versions under `~/.gradle/caches/` other than the wrapper's. Never below 6 GB. `:contacts` adds an
   APK build dir: clean before Steps 1, 4 and 8.
2. **Domain logic in Rust.** Every decision the GPUI app made through `rovsti_core`/`rovsti_app_core` goes through the
   matching op (sections, sorting, search, card rows, dates, quick actions, validation, diff, trash rules, select bar,
   favorites move, group titles). Kotlin only orders effects. If a needed decision has no op, add it to `rovsti_ffi`
   with fixtures (as Step 2 does) instead of rewriting it in Kotlin.
3. **Screens** (ARCH 5.2): ViewModel with one `@Immutable UiState` (`ImmutableList` fields), `onAction(sealed)`,
   `stateIn(WhileSubscribed(5_000))`, one-shot `Effect`s through a `Channel` (navigation, toasts, undo, intents).
   Composables never map/sort/filter. Use only `:design`/`:shell` components (list in "State found"). Every list gets
   `LocalContentInsets` bottom padding (ends above the tab bar), `key` + `contentType` on every item.
4. **Owner complaints are acceptance criteria** (ARCH 1, `references/navigation-layout.md` 6): one avatar per row
   (40 dp), WhatsApp shown once per row as the 14 dp `SourceGlyph` after the name (never a pill), chip rows are one
   non-wrapping `LazyRow`, 16 dp gutter everywhere, every long-press opens a host context menu that floats above the
   content and never pushes it, text fields are `GlassTextField` (caret, selection, paste menu).
5. **Motion** (SKILL refs): push/pop and predictive back from `RovstiNavHost` (`shell-usage.md` 7); shared avatar list
   row -> card with `Modifier.rovstiSharedBounds("avatar-$id")` and `Motion.sharedArtwork()` 500 ms (`motion.md` 8);
   context menus `overlays.md` 3 (lift `menuLift` 0.80/420, panel `menuGrowX/Y`, return `previewReturn` 0.75/500);
   button menus `overlays.md` 4.3 (press-slide-release); sheets `overlays.md` 5 (`sheetSettle` 0.86/380, dismiss rule
   +12 % H or velocity); swipe rows `motion.md` 5.3 (76 dp per action, `swipeSettle` 1/500); alphabet rail
   `scrollToItem` (not animated) while scrubbing with a `selection` haptic per letter (`navigation-layout.md` 6, 8);
   undo pill / toast `overlays.md` 6; reduce motion honoured through `MotionGate`.
6. **Tests:** pure functions and ViewModels on the JVM with `FakeRustCore` + fixture answers and the `:platform` fakes;
   gestures in Compose tests with `mainClock.autoAdvance = false`; screenshots with `ShotTest` from `:testing`, light
   and dark (plus `-fs2`/`-rtl` where named), goldens in `android/contacts/screenshots/`. **Every new PNG is opened
   with Read and judged**; what was seen goes in the commit message and the epics Evidence line. Look for overlaps,
   clipped text, last row above the tab bar, 16 dp gutters, menus floating (not inline), one glyph per row.
7. **Check per step:** `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN`; Rust steps also run
   `cargo test -p rovsti_app_core -p rovsti_ffi` and `bash scripts/check-ffi-exports.sh`. Full `CLAUDE.md` gate in
   Step 8. Every new screen gets `references/checklist.md` B-H in its commit message.
8. **Commits:** one per step, `Kotlin UI (E4 R1): <step title>`, ending with the session attribution lines. Never push
   to `main`.

## Steps

### Step 1. `:contacts` app module, manifest parity, release signing, container, tabs, routes, migration (E4.1) + F11

- Disk clean. `android/contacts` with `rovsti.android.app`, `rovsti.compose`, `rovsti.screenshots`,
  `kotlin.serialization`; namespace and `applicationId` `dev.rovsti.contacts`, `versionCode = 100` (+ build number from
  env `ROVSTI_BUILD_NUMBER`, default 0), `versionName` from the GPUI `Cargo.toml`. Depends on `:shell` (api `:design`,
  `:platform`, `:wa`, `:core-bridge`); test deps `:testing` and testFixtures of `:platform`, `:wa`, `:core-bridge`,
  `:shell`. `include(":contacts")`.
- **Release signing from env** in `AndroidAppConventionPlugin.kt`: when `ROVSTI_KEYSTORE`, `ANDROID_STORE_PASSWORD`
  and `ANDROID_KEY_PASSWORD` are set, a `release` signing config (alias `rovsti`, as `apps/*/gpuiforge.json`
  `signing`) is used by the release build type; otherwise release stays unsigned and the build still succeeds (CI and
  agent machine have no keystore). Document it in `docs/kotlin-ui/toolchain.md`.
- Manifest rebuilt from the GPUI one (State found): same components, filters, `<queries>`, HANDOFF declaration,
  `allowBackup=false`, `supportsRtl`, `enableOnBackInvokedCallback`, `windowSoftInputMode=adjustResize`, launchMode
  singleTop; drop `dev.gpui.android.*` and the vulkan `uses-feature`; theme = a `Theme.Rovsti` NoActionBar style in
  `:shell` if none exists. App icon: convert `apps/contacts/assets/app.png` to `mipmap` (adaptive foreground).
- Classes (FQCN kept, ARCH 7.2): `dev.rovsti.contacts.RovstiContactsApp : Application, HasContainer<ContactsContainer>`
  and `dev.rovsti.contacts.MainActivity : RovstiShellActivity`. `ContactsContainer : ShellContainer` adds lazily
  `contacts: ContactsRepository`, `callLog`, `ops: ContactsOps`, `search: SearchOps`, `qr: QrOps`, `shortcuts`,
  `store: ContactsStore` (Step 2) and `commonSettings: CommonSettings` (both `null`, see State found).
- Navigation: `@Serializable sealed interface ContactsRoute` with `ContactsList`, `Favorites`, `Groups`, `Settings`
  (tab roots), `ContactCard(id: Long)`, `ContactEditor(id: Long?, prefill: DraftSeed?)`, `Trash`, `GroupPage(id)`,
  `UpcomingBirthdays`, `WhatsAppSettings`, and the `CommonSettingsPage` routes. Tabs `Tab("contacts")`, `favorites`,
  `groups`, `settings` with icons person.2 / star / folder / gear (`RovstiIcons`), titles from `apps/contacts/src/
  main.rs:89` `TABS`. `chromeFor`: tab roots Visible + large title; card/editor/trash Hidden; settings sub-pages Folded.
  `routeFor(extras)`: `BirthdayReminders.EXTRA_CONTACT_ID` and the shortcut extra (`Shortcuts` in `:platform`) ->
  `contacts` tab + `ContactCard(id)` (AD-CT8; the extra is removed after use, one-shot, as `RovstiBridge.kt:571-575`
  did); SEND/VIEW with a vCard -> `contacts` tab + an `Intake(uri)` route (the page itself is E4.10, R2; in R1 it is a
  placeholder `EmptyState` "Import arrives in a later update" so the filter is not dead).
- Tab roots in this step are empty shells: `ContactsList` with the large title + `PermissionGate(Contacts)` (nothing is
  asked at launch: the gate shows `PermissionRow` until the user taps Allow), `Settings` page = `SettingsPage` with a
  "WhatsApp" row (E3.10 screen through `:wa`) and `CommonSettingsGroup`.
- **F11 fix:** `shell/src/test/.../permissions/PermissionTestSupport.kt` hosts its rows in `ScreenPage` (or drops the
  outer `.padding(horizontal = Spacing.gutter)`), so sections use the single 16 dp gutter; re-record
  `e3.11-permission-denied-row-*` and `e3.11-permission-rationale-*`, open them, cards at 16 dp, headers aligned with
  the card text.
- Tests:
  - `ContactsManifestTest` (Robolectric-free JVM test, ARCH 7.2): parses the merged manifest
    (`build/intermediates/merged_manifests/debug/**/AndroidManifest.xml`, wired as a test input from
    `processDebugMainManifest`) and asserts the permission/declaration set equals `contacts.permissions.txt` (copy or
    reference the `:platform` resource), the component FQCNs, the three MIME filters for SEND and VIEW, `exported`
    flags, the provider authority, `<queries>`, `allowBackup=false`, `adjustResize`. Put the parser in `:testing`
    (`MergedManifest`) so E5-E7 reuse it.
  - `ContactsRoutesTest`: `routeFor` for the birthday extra, the shortcut extra, a vCard VIEW, nothing; `chromeFor`
    for every route.
  - `ContactsMigrationTest` (Robolectric): copies `dirs/contacts/*` and `dirs/wa/whatsapp-link.json` (copied into
    `android/contacts/src/test/resources/migration/`) into `noBackupFilesDir`, builds the container with the real
    `RustCore` (`:core-bridge:testRustBridge` host library, as `WireFixturesTest` does; skip with a clear assumption if
    the host lib is absent) and asserts `ContactsStore.prefs` equals the fixture values, the trash has the fixture's
    entries, and `WaRepository.linkState` is not `None`.
  - `ContactsContainerTest`: lazy (no Rust init before first use).
- Screenshot: `e4.1-tabs-empty-book` (light/dark): Contacts tab, permission granted, empty book -> `EmptyState`
  "No Contacts" / "Tap + to add someone, or import a .vcf file in Settings." (`main.rs:1810-1814`), the floating tab
  bar with four tabs, nothing under the bar. Open it.
- `platform-port.md`: rows `apps/contacts/.../MainActivity.kt` and `RovstiContactsApp.kt` become `ported (E4.1)`.
- Acceptance: check green; `android/contacts/build/outputs/apk/debug/contacts-debug.apk` exists and carries
  `librovsti_ffi.so` (extend `scripts/check-catalog-apk.sh` to take the contacts APK too).

### Step 2. Data layer: `ContactsStore`, `BookRepository`, search index, WhatsApp marks (Rust ops `contacts.wa_*`)

- Rust (`crates/rovsti_ffi/src/ops/contacts/`, new `wa.rs`), stateless ops over `rovsti_app_core::contacts::whatsapp`:
  - `contacts.wa_marks {contacts, chat_users} -> {marks: {id: jid}}` (`marked_contacts`);
  - `contacts.wa_card {id, numbers, target?, lookup?: {state, users}, presence?, now_ms} -> {lookup_request, jid,
    chips: [bool], status_line?, number_jids: [jid?]}` (`WaPanel` + `wa_chip_index` + `status_line` + `number_jid`);
  - `contacts.wa_profile_lines {profile, contact_name} -> {lines: [{label, value}]}`.
  Fixtures per case (no chats, one match, non-international number ignored, lookup checking/done/failed, presence
  online/last seen), registered in the op registry and `OPS.md`; Kotlin `ContactsWaOps` facade + `@Serializable
  @Immutable` models in `model/contacts/WaModels.kt` + `compose_stability.conf` entries; `WireFixturesTest` decodes
  them. Rust unit tests reuse `whatsapp.rs`'s existing cases.
- Kotlin, package `dev.rovsti.contacts.data`:
  - `ContactsStore`: `prefs: StateFlow<ContactsPrefs>` from `prefsLoad`, `update { }` -> `prefsSave` (serialised
    writes, `Mutex`), plus `favoritesOrder`, `viewed`, `myCard`, `emergency` views; `trash()` / `trashPush` /
    `trashRestore` / `trashDelete` / `trashConfirm` wrappers.
  - `BookRepository`: `book: StateFlow<Book>` (`Loading`, `NoPermission`, `Ready(contacts: ImmutableList<WireContact>,
    drafts)`), driven by `ContactsRepository.all()` + permission state; keeps a `ContactIndex` (`SearchOps.indexNew`/
    `indexReplace` on every provider change, conflated) and a `DeepIndexHandle` (`deepIndexNew`, closed on replace).
  - `WaMarks`: collects `WaRepository.chats()` and the book, calls `contacts.wa_marks`, exposes
    `marks: StateFlow<ImmutableMap<Long, String>>`; empty when not linked (Contacts never sends the address book,
    AD-CT6).
- Tests: `ContactsStoreTest` (prefs round trip through fixtures, concurrent updates serialised), `BookRepositoryTest`
  (provider change -> one `indexReplace`; permission revoked -> `NoPermission`; deep handle closed on replace),
  `WaMarksTest` (not linked -> empty; chats + book -> marks; refetch on resync), and the fixture decode test.
- Acceptance: check green + `cargo test -p rovsti_app_core -p rovsti_ffi` + `check-ffi-exports.sh`.

### Step 3. Contacts list and search (E4.2)

- `ContactsListViewModel(book, store, ops, search, waMarks, clock)`: `UiState(rows, deepRows, scrubberLetters,
  showScrubber, query, queryEcho, emptyKind, createDraft, addition, viewed, myCard, recentEntry, selectMode=null)`.
  Rows from `contacts.view` (name format, sort, favorites order, `width_dp`); search uses `SearchOps.query` (fuzzy
  initials, number fragments, C27) and `deepFind` for the "In details" section (C41, title `finds::DEEP_TITLE`);
  recently viewed via `recentViewed` (C51, "Clear" with a confirm `Alert`); no results -> `EmptyState` "No Results" /
  "No contact matches “q”." + "Create Contact" (`draftFromQuery`) + "Add to a Contact" (`draftAdd`; picker is E4.10,
  so R1 opens the editor of the chosen contact via a simple picker sheet) exactly as `main.rs:1815-1846`. Query typing
  debounced 0 ms but conflated (latest wins) on `Dispatchers.Default`.
- `ContactsListScreen`: `ScreenLazyPage` with large title "Contacts", trailing toolbar "Select" capsule + "+" glass
  circle button (`main.rs:1786-1803`); `SearchField` under the title that docks into the inline bar on collapse
  (`navigation-layout.md` 7, 10); `LazyColumn` with letter `SectionHeader`s (sticky) and `ListRow`s: 40 dp `Avatar`
  (photo via `RovstiImage` slot size, else monogram + hue), name (bold family part per name format), one secondary
  line (`subtitle`), star glyph if starred, WhatsApp 14 dp `SourceGlyph` after the name when `marks` has the id. Row tap
  -> `ContactCard(id)` (records `recentViewed` "open"). `AlphabetScrollBar` on the trailing edge when `showScrubber`,
  letters from the op, `scrollToItem` while scrubbing, haptic per letter, bubble with the letter; hidden while
  searching. Rows end above the tab bar (`LocalContentInsets`).
- Row long-press: `ContextMenuEntry` (simple variant, lifted row copy) with actions from `contacts.quick` (`call`,
  `message`, `whatsapp`, `copy_number`, `share`, `star`/`unstar`, `add_to_home`) plus "Select" (enters select mode,
  E4.8). Call/Message/WhatsApp go through `ShareIntents`/`DialIntents`/`Handoff` (AD-CT5); Copy number ->
  `ClipboardRepository` + toast "Copied"; Share -> `card.share_text` through `ShareIntents.text`.
- Tests: `ContactsListViewModelTest` (sections, search initials "al" -> Ada Lovelace, number fragment, deep rows title,
  no results -> create draft present, recently viewed clear), `ContactsListScreenTest` (`autoAdvance = false`: rail drag
  over "M" calls `scrollToItem` to the M header without animation; long-press opens the menu and row 1 bounds are
  unchanged; WA glyph appears exactly once per marked row), recomposition test: typing one letter recomposes the field
  and the list once.
- Screenshots: `e4.2-list`, `e4.2-list-end-above-bar` (scrolled to the end: last row fully above the bar),
  `e4.2-list-fs2`, `e4.2-list-rtl`, `e4.2-search-typing` (keyboard, "In details" section), `e4.2-search-no-results`,
  `e4.2-rail-scrubbing`, `e4.2-row-context-menu`, `e4.2-recently-viewed`. Use a 40-contact sample book (port names
  from `apps/contacts/src/sample.rs`) with 3 photos, 5 starred, 4 WA-marked. Open every PNG.

### Step 4. Contact card (E4.3)

- Disk clean. `ContactCardViewModel(id, repo, ops, waOps, wa, callLog, store, permissions, clock)`: `UiState(header,
  actions, sections, recent, wa)` built from `ContactsRepository.detail(id)` + `contacts.card` (call target, share
  text), `dates`/`age`/`eventLine` (C5/C52 with age), `mapsUrl` (C6), `webUrl` (C18), `relationTap` (C46), account
  label (`accountLabel`, C44 country from the number), phonetic and nickname/title rows, default number/email
  markers (C23). Recent calls and SMS (C21/C37) from `contacts.history` behind `PermissionGate(CallLog)` - a
  `PermissionRow` in the section until granted. WhatsApp (AD-CT5/CT6): on open, `contacts.wa_card` gives the lookup
  request; `WaGateway.lookupUsers` once; then chips per number (C17), presence subscribe for the JID and the
  `status_line`; profile lines (`wa_profile_lines`).
- `ContactCardScreen`: hero header (88 dp avatar with `rovstiSharedBounds("avatar-$id")`, tap opens `PhotoViewer`
  when a photo exists - the full viewer menu is E4.12), name, org/title line, presence line; one row of action
  capsules Call, Message, WhatsApp (only when a JID), Email (only with an email) - equal width, glass, never wrapping
  (at fs2 labels drop to icons); then `InsetGroupedSection`s per field kind (numbers with label + WA chip, emails,
  addresses, dates with age, websites, relations, nickname, note), "Recent" section, "Open in Android Contacts" row.
  Top bar: back + "Edit" + star toggle (glass circle buttons, `FrostedTopBar`).
- Field long-press: `ContextMenuEntry` with Copy (C32 formats: as written / +E.164 / national - from `fieldMenu` and
  `numberKey`), Share, Email (email rows), Set/Clear default (`fieldMenu` `clears`), Open in maps (address), Open
  (website), Copy note. Nothing moves underneath.
- Tests: `ContactCardViewModelTest` (dates with age, default number, history hidden without permission, WA lookup once
  per open, chip only on the matching number), `ContactCardScreenTest` (field long-press opens the menu over the row,
  card scroll position unchanged; Call capsule invokes the dial intent), shared-element test: list row avatar -> card
  avatar bounds animate for 500 ms (`mainClock` steps).
- Screenshots: `e4.3-card-full`, `e4.3-card-minimal` (name + one number), `e4.3-card-whatsapp-online`,
  `e4.3-field-menu-open`, `e4.3-card-recent-calls`, `e4.3-card-fs2`. Open each: capsules equal width and not wrapped,
  one WA chip per WA number only, menu floats.

### Step 5. Editor: fields, labels, account, groups, validation, discard, undo (E4.4 AC1, AC3, AC4)

- `ContactEditorViewModel(id?, seed?, repo, ops, store)`: holds a `ContactDraft` in `SavedStateHandle` (JSON);
  `draftEdit` for every change (add/remove rows, labels, default), `draftValidate` sentences inline under the field
  (`rovsti_core::contact_edit`), `draftDiff` decides dirty and the Save enablement, save -> `ContactsRepository.save`
  (account from `prefs.save_to` or the chooser, AD-CT3; number-less contacts allowed, AD-CT2), then `recentNoteCreated`
  and an "Undo" pill that restores the previous draft (C40, 5 s like `UNDO_LIFE`, `main.rs:112`).
- `ContactEditorScreen`: page-sheet presentation (`IosPageSheet` via `SheetEntry` large detent) with Cancel / title /
  Done; fields are `GlassTextField` with keyboard types (phone `KeyboardType.Phone`, email, URI, `ImeAction.Next`
  through the list); sections Name (prefix/first/middle/last/suffix, phonetic), Company (org, title), Phone, Email,
  Address (multi-line), Dates (date field via `dateParse`), Websites, Relations, Nickname, Note, Groups (chooser
  sheet from `groups()`), Account (chooser `ButtonMenuEntry`, labels from `accountLabel`). Each repeatable row has a
  leading red minus and a trailing label button that opens a `Popover` with the standard labels + Custom (AC1). "add
  phone" etc. rows insert with `appear` spring. Focused field is kept 16 dp above the IME (`REVEAL_MARGIN`,
  `main.rs:115`) through `bringIntoViewRequester` + `imePadding`.
- Back/Cancel when dirty -> confirm `ActionSheet` "Discard Changes" (destructive) / "Keep Editing" (AC3); swipe-down of
  the sheet is blocked while dirty (rubber band) and shows the same sheet.
- Tests: `ContactEditorViewModelTest` (fixtures `draft_validate.{no_name,bad_email,bad_date}`, `draft_diff.same` ->
  not dirty, save -> repo saw the draft with the account, undo restores), `ContactEditorScreenTest` (label popover
  anchors to the label button and does not move the field; text selection + paste in a phone field; discard sheet on
  dirty back; IME keeps the focused field visible).
- Screenshots: `e4.4-editor-new`, `e4.4-editor-filled`, `e4.4-editor-keyboard-open`, `e4.4-editor-label-popover`,
  `e4.4-discard-confirm`, `e4.4-validation-error`. Open each.

### Step 6. Editor photo (E4.4 AC2) and Delete, Undo, Trash (E4.5)

- Photo: the avatar in the editor opens an `IosMenu` Choose Photo (`PickPhoto` contract, no media permission), Take
  Photo (`CapturePhoto` via `RovstiShareProvider`, C14), Use WhatsApp Photo (only with a JID; `WaGateway.picture` ->
  file, the `fetch_wa_photo` path rule `wa_photo_path`), Remove Photo (destructive; `removePhoto`). After Choose/Take,
  `ContactPhotoCropScreen` (C13): full-screen dark page, the image under a circular mask, pinch/pan with `ZoomMath`
  (E1.20), rotate 90 deg button, Cancel / Choose; geometry from `photoPlan` / `photoAccepts` / `photoCrop` (Rust crops
  and writes the file); then `setPhoto`. Gestures are `Animatable`s, not state.
- Delete (E4.5): from the card's top-bar menu and the editor's bottom "Delete Contact" row -> confirm `Alert`
  (`trashConfirm` text) -> `trashPush` (snapshot to `contacts-trash.json`) -> `repo.delete` -> pop to the list with an
  undo pill "Deleted Ada Lovelace - Undo" (`UNDO_LIFE` 5 s; Undo -> `trashRestore` + `repo.save`, `keeps_undo` rule of
  `main.rs:121-129`). Trash page (C20) under Settings: rows with "deleted N days ago", swipe or long-press Restore /
  Delete Now, toolbar "Empty Trash" with confirm, 30-day pruning by `trashList`; `EmptyState` "Trash Is Empty".
- Tests: `PhotoCropTest` (pure: pan bounds, rotate, plan round trip with fixtures), `PhotoMenuTest` (WA item only with a
  JID; Remove only with a photo), `TrashViewModelTest` (delete -> pill -> undo restores with the same fields; expired
  entries hidden; empty with confirm), Compose test that the undo pill does not cover the last list row (pill sits above
  the tab bar inset).
- Screenshots: `e4.4-photo-menu`, `e4.4-crop`, `e4.5-delete-confirm`, `e4.5-undo-pill`, `e4.5-trash-list`,
  `e4.5-trash-empty`. Open each.

### Step 7. Favorites, frequent, upcoming birthdays (E4.6) and Groups (E4.7)

- Favorites tab: `FavoritesViewModel` - order from `favoritesOrder` (`contacts.view` `favorites`), Frequently contacted
  from `contacts.frequent` (behind the call-log gate; a star adds to favorites, `main.rs:1546`), Upcoming birthdays
  section (`dates`/upcoming, C9) with a "See All" row to `UpcomingBirthdays` page: rows with date and age, "Wish" ->
  SMS intent with the text, "Add to Calendar" -> `contacts.ics` + `ExportDocument("text/calendar")` (C50).
  Header actions from `quick::favorites_header_actions` semantics (`main.rs:1905-1932`): "+" opens a contact picker
  sheet, "Edit"/"Done". **Edit mode reorder**: port Convx's reorderable list (`LocalPlaylistScreen.kt:536-600`:
  `rememberReorderableLazyListState`, `ReorderableItem`, drag handle, haptic on start/move) close to verbatim with its
  header; add `sh.calvin.reorderable:reorderable:3.0.0` to `libs.versions.toml` and its Apache-2.0 entry to
  `THIRD_PARTY_NOTICES.md`; the dropped order goes through `favoritesMove` (drag mode `from`/`to`) and is saved to
  prefs; "Move earlier/later" in the row context menu use button mode (`index`, `up`). Lifted row scale 1.03 + shadow,
  neighbours settle with `select` spring.
- Groups tab: `GroupsViewModel` from `ContactsRepository.groups()` + `groupsLabels`/`groupsTitle`/`groupsSummary`;
  list of groups with counts; "+" -> name alert with `GlassTextField`; `GroupPage(id)`: members list, toolbar "..."
  `ButtonMenuEntry` with Rename, Add Members (multi-select picker sheet with search, `setMembers`), Message Group
  (`groupsRecipients` -> SMS intent, C25), Email Group, Export Group (`vcardWrite` + `ExportDocument`), Delete Group
  (confirm). Member row swipe -> Remove.
- Tests: `FavoritesViewModelTest` (order from prefs, drag move saved, move earlier/later, frequent hidden without
  permission), `UpcomingTest` (ics request has the right event), Compose reorder test (`autoAdvance = false`: drag row 1
  below row 3 -> order changes once, haptic recorded), `GroupsViewModelTest` (create/rename/delete, members change,
  recipients).
- Screenshots: `e4.6-favorites`, `e4.6-favorites-reorder-drag`, `e4.6-upcoming-page`, `e4.7-groups`,
  `e4.7-group-page`, `e4.7-member-picker`, `e4.7-group-menu-open`. Open each.

### Step 8. Select mode (E4.8), close E4 R1

- Select mode on the Contacts tab (C11): entered from "Select" or a row's context menu; rows show a leading check
  circle (spring `select`), the large title becomes "N Selected" from `selectBar`, toolbar Cancel / Select All; a
  bottom glass toolbar (above the tab bar, which folds) with Share (C47 as text / .vcf via `selectExport`), Add to
  Group (group chooser sheet), Star, Delete (confirm, then one undo pill for all, hidden ids via `view.hidden`), and a
  "..." `IosMenu` with Export .vcf, Copy Numbers, Copy Emails (C53). Back exits select mode first (BackHandler order).
- Tests: `SelectModeViewModelTest` (select all, bar text, export text, multi-delete undo restores all), Compose test
  (back leaves select mode, not the tab).
- Screenshots: `e4.8-select-mode`, `e4.8-select-actions-menu`. Open both.
- Close: disk clean; full `CLAUDE.md` gate (`cargo fmt --all && cargo clippy --workspace --all-targets && cargo test
  --workspace`, `cargo check -p rovsti_android --target aarch64-linux-android`) then `bash scripts/kotlin-ui-check.sh`;
  name `android/contacts/build/outputs/apk/debug/contacts-debug.apk` (and the catalog APK) in the commit. Run
  `references/checklist.md` A-H over all Contacts screens and record it in `docs/kotlin-ui/checklist-e4.md`.
- epics.md: E4.1-E4.8 `done` with one Evidence line each (commit, tests, PNGs opened and what was seen); point the
  next planner at **E4 R2** (E4.9-E4.16).

## Verification summary for the next planner

- `android/settings.gradle.kts` includes `:contacts`; `contacts-debug.apk` builds; `ContactsManifestTest` passes
  against `contacts.permissions.txt`; `MergedManifest` helper is in `:testing`.
- Rust ops `contacts.wa_marks`, `contacts.wa_card`, `contacts.wa_profile_lines` with fixtures.
- Test classes: `ContactsRoutesTest`, `ContactsMigrationTest`, `ContactsStoreTest`, `BookRepositoryTest`,
  `WaMarksTest`, `ContactsListViewModelTest`, `ContactsListScreenTest`, `ContactCardViewModelTest`,
  `ContactCardScreenTest`, `ContactEditorViewModelTest`, `ContactEditorScreenTest`, `PhotoCropTest`,
  `TrashViewModelTest`, `FavoritesViewModelTest`, `GroupsViewModelTest`, `SelectModeViewModelTest`.
- Goldens in `android/contacts/screenshots/`: `e4.1-*` .. `e4.8-*` as listed, all opened; `e3.11-permission-*`
  re-recorded with a single 16 dp gutter (F11).
- Favorites reorder file carries the Convx header and `THIRD_PARTY_NOTICES.md` lists `sh.calvin.reorderable`.
