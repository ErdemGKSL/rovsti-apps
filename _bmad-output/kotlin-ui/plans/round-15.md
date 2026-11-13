# Round 15 plan: close E5 (recover E5.9 and E5.11 from the stashes, F31, F35-F38), then X.1 WhatsApp photo fallback

Planned 2026-11-13 at base `d89810c` (branch `ui-overhaul`). Epic: **E5 Phone**, the first epic not done (E1-E4 done). E5
is open only because E5.9 (Settings) and E5.11 (Notes) never reached HEAD: their round-14 work sits in three "blocked"
stashes. Steps 1-4 recover them, fix what this round's screenshot review found, and close E5. Steps 5-8 do **X.1** (owner
request, scheduled for round 15 in epics.md "X.", before E6). **X.2** (Takumi share cards) is round 16, then E6.

Owner decisions in force: Rovsti is GPL-3.0-or-later; Convx code is ported close to verbatim with its original header plus
`Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, <path>`; glisse-derived code keeps the
glisse MIT header; `android/THIRD_PARTY_NOTICES.md` lists every new source or library. Owner rules (b) no `RustNative`
call before `Rust.init` (a `ContentProvider.onCreate` runs **before** `Application.onCreate`: it must not touch Rust),
(c) `imeOverlapPadding` only, (d) R8 keep rules for anything reached by name, checked with `dexdump` on the release APK.
Definition of done item 6 (Turkish, launcher, notification icons, never crash on platform calls) applies throughout.

## State found at `d89810c` (verified in code, not trusted)

- Working tree clean. Commits after round 14 (`621e5c2`): F28-F34 owner fixes (`e3ea831`, `8b08b5f`, `0310ec0`,
  `47cb5b4`, `90f0288`, `b5507c4`, `0151edc`, `5d8583b`), WA call audio (`db0d587`), gateway deploy (`a70bf70`),
  swipe-row fixes (`653e329`, `d89810c`).
- **Round 14 verified:** Step 1 F22-F26 (`e9f9198`: `incoming/CallHeader.kt`, `design/.../text/SearchFocusHandoff.kt`),
  E5.10 (`c7a32cd`, `stats/`), E5.12 (`ac930ea`, `remind/`, `setup/`, `BootReceiver.kt`), E5.13 (`96ae55b`), E5.14
  (`621e5c2`: `ThemeMelt.kt`, `SetupCompleteMoment.kt`, `checklist-e5.md` U23 table). **Not in HEAD:** no
  `phone/.../settings/` and no `phone/.../notes/` package; `checklist-e5.md` lines 105-177 mark U5, U19, U22, U24, U27,
  U30, U33, U34, U38, U41-U43, U54, U55 as MISSING.
- **Stashes** (`git stash list`; untracked files are in each stash's third parent `stash@{n}^3`):
  - `stash@{0}` "blocked: kotlin-ui r14 r14-s5" (base `c7a32cd`): E5.11 Notes. Untracked: `notes/NotesScreen.kt`,
    `NotesViewModel.kt`, tests `E511ShotTest`, `NotesHarness`, `NotesRig`, `NotesSample`, `NotesScreenTest`,
    `NotesViewModelTest`, 10 `e5.11-*` goldens. Tracked: `PhoneApp/Container/Pages/Route.kt`, `Spacing.kt`,
    `details/DetailsPresenter.kt`, strings en/tr, `core-bridge .../HistoryModels.kt`, `crates/rovsti_ffi/src/ops/phone/log.rs`,
    shell `SettingsPage.kt`.
  - `stash@{1}` "blocked: kotlin-ui r14 r14-s3" (base `d3df23e`): E5.9b. Untracked: `settings/SettingsHost.kt`,
    `SettingsUi.kt`, `SettingsWiring.kt`, `settings/{backup,data,replies,sim,speeddial}/*`, tests `BackupViewModelTest`,
    `DataViewModelTest`, `E59ShotTest`, `QuickRepliesScreenTest`, `QuickRepliesViewModelTest`, `SettingsFixtures`,
    `SettingsRig`, `SettingsScreensTest`, `SimChoicesViewModelTest`, `SpeedDialViewModelTest`, `e5.9-speed-dial-*`.
    Tracked: manifest (+2), `MainActivity.kt`, `PhoneApp/Container/Pages/Route.kt`, `data/CallPlacer.kt`, strings (+107
    each), `PhoneHost`, `PhoneRoutesTest`, `PhoneScreenTest`, `PushedPagesBackTest`, shell `SettingsPage.kt`.
  - `stash@{2}` "blocked: kotlin-ui r14 r14-s2" (base `6cdc462`): E5.9a. Untracked: `settings/blocked/{BlockedEntry,
    BlockedScreen,BlockedStrings,BlockedViewModel}.kt`, tests `BlockedSample`, `BlockedScreenTest`, `BlockedViewModelTest`,
    `blocked/E59ShotTest`, `test/resources/blocked-import.csv`, `platform/.../calllog/BlockPrefixes.kt` +
    `BlockPrefixTest`, 18 `e5.9-*` goldens. Tracked: `RovstiIcons.kt` (+42), `PhoneApp/Container/Pages/Route.kt`,
    `Spacing.kt`, `data/BlockRepo.kt` (+75), strings (+70), `PhoneHost`, `PhoneRoutesTest`, `PhoneScreenTest`,
    `PushedPagesBackTest`, `platform/.../telephony/CallRegistry.kt`.
  - `stash@{3}` round-13 step-4 WIP (superseded by `c0ffc3c`), `stash@{4}` r12 S6 (recovered in round 13),
    `stash@{5}` deprecated GPUI WIP (never pop).
- **Dry-run merges** (`git merge-tree --write-tree --merge-base=stash@{n}^1 HEAD stash@{n}`): every stash conflicts
  textually with HEAD in `PhoneApp.kt`, `values/strings.xml`, `values-tr/strings.xml`, `e5.1-settings-*.png`, `epics.md`;
  plus `Spacing.kt`, `PhoneHost.kt`, `PhoneScreenTest.kt` (s2), `PhoneContainer.kt`, `data/CallPlacer.kt`,
  `PhoneScreenTest.kt` (s3). `PhonePages.kt`, `PhoneRoute.kt`, `PhoneRoutesTest.kt`, manifest auto-merge but s2 and s3
  each add routes and Settings rows independently (s3 was written without s2), so applying both will conflict between
  them. HEAD meanwhile grew a Setup section and a Statistics row on Settings (`PhoneSettingsEntries.kt`, E5.10/E5.12).
- **F31 still open** (verified): `RecentsScreen.kt:710-713` and `details/PersonSheet.kt:526-529` play `haptics.confirm()`
  on `*Effect.Core` (a refusal); `KeypadScreen.kt:150` and `FavoritesScreen.kt:516` play none.
- **X.1 groundwork in HEAD:** `wa/.../avatars/WaAvatars.kt` + `AvatarBook.kt` (per-app disk cache of WA pictures keyed by
  JID and picture id through the core's `wa.avatars_plan` LRU, `rovsti_ffi/src/ops/wa/pure.rs:210-239`; `wa.picture` in
  `rest.rs:350`); `platform/.../identity/{CallerIds,CallerIdentity,PhoneNumbers}.kt` (F28 resolver);
  `phone/.../data/{PeopleIndex,LiveNames}.kt` (F29); `incoming/CallPhotos.kt` (photo flow, null without own photo).
  Contacts declares a signature permission already (`dev.rovsti.permission.HANDOFF`, `contacts/AndroidManifest.xml:16-19`):
  the same pattern serves the people provider. No WorkManager dependency yet (`android/gradle/libs.versions.toml`).
- **Disk: 3.2 GB free** (`df -h .`). Big items: `~/.gradle/caches/9.9.0` 6.9 GB (current wrapper, keep),
  `target/checks` 2.7 GB, `~/.gradle/caches/modules-2` 885 MB, `android/{catalog,phone,core-bridge,contacts}/build`
  ~1.6 GB, `target/aarch64-linux-android` 471 MB, build-cache 354 MB.

## Screenshots opened in this review

- `android/phone/screenshots/e5.14-settings-fs2-light.png` (HEAD): **F35** at 2x font the Setup rows give the trailing
  action ("Set as default", "Allow access") its natural width and squeeze the title/subtitle into a one-word column
  ("Default / phone / app", "Shows / names / instead of / numbers"). iOS stacks the accessory under the text at
  accessibility sizes. Known deviation in `checklist-e5.md`; fix it now (Step 4).
- `android/phone/screenshots/e5.14-stats-rtl-dark.png` (HEAD): **F36** bidi breaks numbers and durations in an RTL
  layout: "h 52 min 1" for "1 h 52 min", "123456 30 49+" for "+49 30 123456", "calls · 1 h 05 min 9". Round 14 called it
  a harness artefact, but an Arabic/Hebrew/Persian system locale with our English fallback produces exactly this on a
  device. Phone numbers must be LTR-isolated (`TextDirection.Ltr` / `BidiFormatter.unicodeWrap` with FSI/PDI) and
  formatted-duration strings wrapped likewise (Step 4).
- `stash@{2}^3:.../e5.9-settings-light.png`: good (Setup, Calls, Data groups, chevrons, back button, full bar). It lacks
  HEAD's expanded Setup rows and E5.10 Statistics is present; the merge keeps HEAD's Setup section plus the stash's Calls
  and Data groups (Step 1).
- `stash@{2}^3:.../e5.9-blocked-list-dark.png`: good (formatted numbers, prefix rows "+90 850 *", sections). The last
  card (Share / Export) sits under the bar at rest, which is fine only if it scrolls fully clear (see F37).
- `stash@{2}^3:.../e5.9-block-paste-many-light.png`: good (multi-line field, caret handle, core preview line
  "3 to block, 1 emergency skipped, 1 already blocked", Cancel/Block). The golden has no simulated IME; Step 1 adds
  `e5.9-block-paste-many-ime` with the IME inset rig so `imeOverlapPadding` is proven.
- `stash@{1}^3:.../e5.9-speed-dial-light.png`: **F37** the "Clear all" card's bottom edge touches the floating tab bar
  with no gap: the list's end content padding does not include the bar height + gesture inset + `Spacing.s16`
  (owner complaint "floating tab bar covering the last row"). Fix in the shared settings page scaffold, not per page.
- `stash@{0}^3:.../e5.11-notes-light.png`: **F38** the third row shows the raw `+4930123456` (same class as F22): row
  titles without a name must go through `NumberDisplay` (`+49 30 123456`). Otherwise good (avatars, date · first line,
  share/export glass pill top-right, search field, ends clear of the bar).

## Rules for this round

1. **Disk first, every step.** `df -h .` before any build. Now (3.2 GB): `rm -rf target/checks android/*/build
   ~/.gradle/caches/build-cache-1 target/aarch64-linux-android/*/incremental`, `cargo clean -p` the deprecated GPUI crates
   (`rovsti_phone rovsti_messages rovsti_stops rovsti_contacts rovsti_ui rovsti_wa_ui liquid_glass_kit`) and any Gradle
   version dir under `~/.gradle/caches/` other than `9.9.0` (and the shared `modules-2`, `jars-*`, `transforms-*`, which
   stay). That frees about 5 GB; build only at 7 GB or more, never below 6 GB. Never `cargo clean` the workspace or
   `rm -rf target`; delete `target/checks` only (CLAUDE.md). Release builds only.
2. **Stash recovery is a merge, not a rewrite.** Apply with `git stash apply --index stash@{n}` (never `pop`), resolve each
   conflict by keeping HEAD's newer behaviour plus the stash's additions; do not regenerate the stash's code from
   scratch. Binary goldens in conflict: take neither, re-record. Run the step's tests before the full check. Drop a stash
   only after its commit is in HEAD and the check is green (Step 4 lists them); never touch `stash@{5}`.
3. **Domain logic in Rust**; screens follow ARCH 5.2 (ViewModel, one `@Immutable UiState`, `onAction`, effects through a
   `Channel`); only `:design`/`:shell` components (`IosList` rows, `FrostedTopBar` with `LocalPageBack`, full tab bar on
   pushed pages, toasts/undo pills, `IosSheet`, `ButtonMenuEntry` / `IosContextMenu`); presses never push layout.
4. **Text fields**: `:design` fields, `imeOverlapPadding` only (rule c); caret, selection, copy/paste menu work.
5. **Motion** (SKILL `references/motion.md`, `overlays.md`): list insert/remove spring 0.85/400 via `animateItem`; undo
   pill = toast spec; sheets 0.86/380 with release velocity; avatar crossfade (X.1) 220 ms `FastOutSlowIn` alpha only,
   no size change, none under reduce motion (`MotionGate`).
6. **Tests**: JVM ViewModel tests on the real core (`HostRust`) or `FakeRustCore`; gestures with
   `mainClock.autoAdvance = false`; `ShotTest` light + dark (+ `-fs2` / `-rtl` / `-tr` where named). **Every new or changed
   PNG is opened with Read and judged**, and what was seen goes in the commit message and the epics Evidence line. Goldens
   use the product path (real `NumberDisplay`, real core ops).
7. **Strings** in `values` + `values-tr`; `PhoneStringsTest` / Contacts strings test and lint `MissingTranslation` pass.
8. **Check per step**: `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN`. Rust changes also run
   `cargo test -p rovsti_core -p rovsti_app_core -p rovsti_ffi` and `bash scripts/check-ffi-exports.sh`; Step 4 and Step 8
   run the full CLAUDE.md gate.
9. **Commits**: one per step, `Kotlin UI (E5 Phone + X.1, round 15): <step title>`, with the session attribution lines.
   Never push to `main`.

## Steps

### Step 1. Recover E5.9a: Settings root and Blocked numbers (`stash@{2}`)

- Disk Rule 1 first.
- `git stash apply --index stash@{2}`. Resolve: `PhoneApp.kt` (keep HEAD's Stats/Setup/remind wiring, add the Blocked
  route entry), `Spacing.kt`, `PhoneHost.kt`, `PhoneScreenTest.kt`, strings en/tr (union, no duplicate names),
  `epics.md` (take HEAD). Settings root = HEAD's `PhoneSettingsEntries.kt` Setup section (expanded rows) + Statistics row
  + the stash's **Calls** (Blocked numbers with count, and placeholders that Step 2 fills) and **Data** groups; WhatsApp,
  Haptics, Appearance, About/Licences, hidden Diagnostics as now. Rows whose page is not yet in HEAD (Quick replies, Speed
  dial, SIM choices, Call history, Backup) are not shown until Step 2 (no dead rows).
- Re-check against current HEAD APIs: `CallRegistry.kt` change (8 lines) still needed after the F28 resolver; `BlockRepo`
  additions vs `recents` block flow (`block_ui`, S27) share one repository; `BlockPrefixes.kt` matches with
  `platform/.../identity/PhoneNumbers.kt` (one normaliser, not two: if `BlockPrefixes` has its own, route it through
  `PhoneNumbers`).
- **F37 (shared fix):** one `settingsPageContentPadding()` in `:design` chrome (or reuse `LargeTitleScaffold`'s
  `contentPadding` if it exists) = top title block + bottom `LocalTabBarHeight + navigationBars + Spacing.s16`; every
  settings page uses it. Test `SettingsEndPaddingTest`: scroll to end, last node's bottom <= tab bar top - 16 dp.
- Add `e5.9-block-paste-many-ime` (IME inset rig, the field and preview line above the IME).
- Tests: stash's `BlockedViewModelTest`, `BlockedScreenTest`, `BlockPrefixTest`, `PhoneRoutesTest`,
  `PushedPagesBackTest` additions pass; `SettingsEndPaddingTest`.
- Screenshots re-recorded and OPENED: `e5.1-settings-*`, `e5.9-settings{,-fs2,-tr}-*`, `e5.9-blocked-list{,-tr}-*`,
  `e5.9-blocked-empty-*`, `e5.9-blocked-no-role-*`, `e5.9-block-prefix-*`, `e5.9-block-paste-many{,-ime}-*`
  (light + dark): back button, full bar, last card clear of the bar at the end of the scroll.
- Check. Commit. epics: E5.9 -> in-progress.

### Step 2. Recover E5.9b: Quick replies, Speed dial, SIM choices, Data, Backup (`stash@{1}`)

- `git stash apply --index stash@{1}`. Resolve `PhoneApp.kt`, `PhoneContainer.kt`, `data/CallPlacer.kt` (keep HEAD's
  F28/F30/F34 behaviour: localized refusals, "no app" toast), strings, `PhoneScreenTest.kt`; merge the stash's
  `settings/SettingsWiring.kt` / `SettingsHost.kt` with Step 1's Settings root (one settings route table, not two).
  Review the manifest (+2 lines) and `MainActivity.kt` changes: any class reached by name -> keep rule + `dexdump`.
- Show the Step-1 hidden rows. SIM choices row only on dual-SIM (`SimState`).
- Data page: U5 CSV with U19 note column, U41 file-named pills, U43 delete older (prune plan preview + confirm), U24
  delete all + 5 s Undo before the provider delete, U54 `.vcf`. Backup: export/import with preview.
- Apply F37's shared padding to every page (speed dial "Clear all" must end 16 dp above the bar).
- Tests: `QuickRepliesViewModelTest`, `QuickRepliesScreenTest` (reorder drag, `autoAdvance=false`),
  `SpeedDialViewModelTest`, `SimChoicesViewModelTest`, `DataViewModelTest`, `BackupViewModelTest`, `SettingsScreensTest`.
- Screenshots OPENED: `e5.9-quick-replies`, `e5.9-quick-reply-edit-sheet`, `e5.9-speed-dial`, `e5.9-sim-choices`,
  `e5.9-data`, `e5.9-data-delete-older`, `e5.9-backup-preview` (light + dark), `e5.9-speed-dial-fs2`.
- Update `checklist-e5.md` U5, U19, U22, U24, U27, U34, U38, U41, U43, U54, U55 from MISSING to "in HEAD" (verified
  in code). Check. Commit. epics: E5.9 -> done with Evidence.

### Step 3. Recover E5.11 Notes (`stash@{0}`) and fix F38

- `git stash apply --index stash@{0}`. Resolve `PhoneApp.kt`, strings. The tracked Rust change
  (`crates/rovsti_ffi/src/ops/phone/log.rs`) and `core-bridge .../HistoryModels.kt`: keep, add or update its fixture,
  `OPS.md`, run `cargo test -p rovsti_ffi` and `bash scripts/check-ffi-exports.sh`. Shell `SettingsPage.kt` change:
  keep only if still needed after Steps 1-2 (the licence goldens `e3.11-*` changed in all three stashes: re-record and
  open them if the change stays).
- Notes row in Settings (and from call details "All notes"). `SearchFocusHandoff` when a row opens its call-details sheet.
- **F38:** row titles without a contact name go through `NumberDisplay` (and the F29 live-name lookup, so a renamed
  contact shows its current name). `NotesViewModelTest` case: unnamed row title is `+49 30 123456`.
- Tests: `NotesViewModelTest` (search, delete + undo, export header, F38), `NotesScreenTest` (swipe delete
  `autoAdvance=false`, undo restores).
- Screenshots re-recorded and OPENED: `e5.11-notes{,-fs2}`, `e5.11-notes-search`, `e5.11-notes-empty`,
  `e5.11-notes-no-match` (light + dark).
- `checklist-e5.md` U30, U33, U42 -> in HEAD. Check. Commit. epics: E5.11 -> done.

### Step 4. F31, F35, F36; close E5

- **F31:** `RecentsEffect.Core` and `PersonEffect.Core` play `haptics.reject()` (Keypad and Favorites Core too, for one
  rule everywhere); `PillFeedbackTest` gains the four cases (refusal -> Reject, never Confirm).
- **F35:** `:design` list row (`IosList` row with trailing action / value) switches to a stacked layout when
  `LocalDensity.fontScale >= 1.5` or the trailing content would leave the title less than 50 % of the row: title and
  subtitle full width, the trailing action under them, leading aligned with the text. Used by Phone Setup rows and every
  settings page automatically. Test `ListRowStackingTest` (fontScale 2: title width > 60 % of the row; fontScale 1: one
  line). Re-record `e5.14-settings-fs2-*`, `e5.12-*` fs2 goldens, Contacts `e4.15-*-fs2` if they use the row.
- **F36:** `:design` `text/LtrIsolate.kt`: `Modifier`/`AnnotatedString` helper that renders phone numbers and formatted
  durations with `TextDirection.Ltr` inside an RTL layout (FSI/PDI isolation for strings composed from resources). Apply
  in `NumberDisplay` output, stats durations (`stats/`), recents subtitles, notes rows, blocked rows. Test
  `LtrIsolateTest` (RTL: "+49 30 123456" glyph order kept). Re-record and open `e5.14-stats-rtl-*`,
  `e5.14-settings-rtl-*`.
- **Close E5:** `checklist-e5.md` has no MISSING row; "Not closed" section replaced by the recovery evidence; checklist
  A-H for Settings and Notes pages. Regenerate `docs/kotlin-ui/screenshots/phone.png` (`:phone:contactSheet`) and open it.
- Full gate: `cargo fmt --all && cargo clippy --workspace --all-targets && cargo test --workspace`,
  `cargo check --release -p rovsti_android --target aarch64-linux-android`, `bash scripts/kotlin-ui-check.sh`; release
  APKs of `:phone` (plain + firebase); `dexdump` any new keep rule. State the APK paths in the commit.
- Stash hygiene: after this commit, `stash@{0}`-`{2}` (round-14) and the round-13 `stash@{3}`/r12 `stash@{4}` are fully
  in HEAD: verify file by file (`git diff stash@{n}^3 HEAD -- <paths>`), list them in the summary as safe to drop and drop
  only those verified; never `stash@{5}`.
- epics: E5.9, E5.11, E5.14 -> done; E5 header "DONE (R2)"; X.1b F31 done.

### Step 5. X.1a People cache in the Rust core and the shared `:people` client module

- Rust `rovsti_app_core::people` (platform-free, unit-tested): a person-info store keyed by WhatsApp JID with the E.164
  number(s), LID, push/verified name, "on WhatsApp" flag, picture id, picture file name, fetched/updated times; ops
  `people.upsert` (a picture-id change marks the picture stale, a removed picture drops the file), `people.lookup`
  (by E.164 / JID / LID, offline), `people.plan` (which JIDs to refresh, which files to evict by an LRU byte cap, reusing
  the `wa.avatars_plan` rules rather than a second LRU), `people.snapshot` (compact rows for the provider). Exposed via
  `rovsti_ffi` with fixtures, `OPS.md`, `check-ffi-exports.sh`. Tests: insert, refresh on picture-id change, removal,
  eviction under the cap, offline read, LID -> number join.
- New Gradle module `android/people` (`:people`, Android library, depends on `:platform`, not on `:contacts`):
  `PeopleContract` (authority `dev.rovsti.contacts.people`, columns, `openFile` URIs per picture, signature permission
  `dev.rovsti.permission.READ_PEOPLE`), `PersonAvatar` sealed (`Own(uri)`, `WhatsApp(file, pictureId)`, `Initials`),
  `AvatarResolver` (own photo, else WA fallback, else initials), `PeopleIndex` in-memory index API with a `StateFlow`
  version for recomposition, `PeopleSource` interface (`ProviderSource` reading Contacts' provider, `GatewaySource`
  syncing into memory when Contacts is not installed, `PackageManager.resolveContentProvider` decides). No Rust in this
  module's static init.
- Gradle: add `:people` to `settings.gradle.kts`; add `androidx.work:work-runtime-ktx` (a release at least two weeks old)
  to `libs.versions.toml` for Step 6; licence line in `THIRD_PARTY_NOTICES.md` (Apache-2.0).
- Tests: `AvatarResolverTest` (own photo kept; no photo + WA picture -> WA; unlinked -> initials; unknown number on WA ->
  WA), `PeopleIndexTest` (change notification bumps the version; lookups by national/international forms via
  `PhoneNumbers`).
- Check. Commit. epics: X.1 -> in-progress.

### Step 6. X.1b Contacts owns the cache: provider, sync worker, settings row

- Contacts `people/PeopleStore` over the Step-5 ops (data in Contacts' Rust data dir, pictures in
  `filesDir/people/`), fed by `WaAvatars`/`wa.picture` downloads and gateway profile events (picture id / push name
  changes) only while WhatsApp is linked.
- `PeopleProvider` (exported, `readPermission = dev.rovsti.permission.READ_PEOPLE`, declared `signature` in Contacts and
  `uses-permission` in Phone, both apps like `HANDOFF`): `query` returns `people.snapshot` rows, `openFile` serves
  pictures read-only as `ParcelFileDescriptor`, `notifyChange` after every sync. `onCreate` does no Rust work; `query`
  awaits `Rust.init` with a short bound and returns an empty cursor if not ready (rule b).
- `PeopleSyncWorker` (WorkManager unique periodic work "people-sync", ~6 h, `CONNECTED` + battery-not-low,
  `ExistingPeriodicWorkPolicy.KEEP`): batches device contacts + recent callers to the gateway lookup, downloads only
  changed pictures, drops removed ones. Also triggered by live profile events and by Contacts coming to the foreground
  with data older than the period. Settings row "WhatsApp names and photos": last sync time/result and "Sync now"
  (en + tr).
- R8: provider and worker are reached by name -> keep rules (WorkManager's own consumer rules plus ours for
  `PeopleSyncWorker` constructor); verify with `dexdump` on the release APK. Manifest tests (`contacts.permissions.txt`,
  `PhoneManifestTest`) updated.
- Tests: `PeopleProviderTest` (Robolectric: rows, openFile, permission enforced, change notification, empty before
  `Rust.init`), `PeopleSyncWorkerTest` (`WorkManagerTestInitHelper`: unique, constraints, changed picture downloaded
  once, removed dropped, nothing when unlinked), `ContactsSettingsSyncRowTest`.
- Screenshots OPENED: `x1-people-sync-row` (light + dark, + `-tr`).
- Check. Commit.

### Step 7. X.1c Contacts surfaces use the resolver

- Every avatar in Contacts goes through `AvatarResolver`: list, search results, recently viewed, groups, favorites,
  card hero (and the share/birthday card input for X.2). The WA picture crossfades in (Rule 5) with no layout change; a
  cached picture renders on the first frame (index warmed in `RovstiContactsApp` after `Rust.init`). Never written into
  the Android contact; the editor's explicit "Use WhatsApp Photo" stays.
- Test `ContactsAvatarSurfacesTest` enumerating the surfaces (each composable takes the resolver output, none reads
  `photoUri` directly: a source scan test for `photoUri` outside the resolver), `FirstFrameAvatarTest`.
- Screenshots OPENED: `x1-wa-avatar-list`, `x1-wa-avatar-card`, `x1-wa-avatar-search` (light + dark).
- Check. Commit.

### Step 8. X.1d Phone surfaces, standalone fallback, close X.1

- Phone `PeopleIndex`/`LiveNames` (F29) and `CallPhotos.kt` use `:people`'s resolver: recents, favorites, contacts tab,
  keypad suggestions, speed dial page, statistics top people, person sheet, call details, notes rows, incoming and
  in-call (photo background), `WaitingCallCard`, and the ringing, missed-call, ongoing and reminder notifications (large
  icon via `:platform` NotificationIcon). Unknown numbers on WhatsApp get the picture too.
- Without Contacts installed: `GatewaySource` syncs into memory at process start and when WhatsApp gets linked; initials
  until it arrives; never blocks the ringing screen (the incoming path reads only what is in memory).
- Tests: `PhoneAvatarSurfacesTest` (enumerates every surface above), `NotificationLargeIconTest` (WA fallback as large
  icon, own photo wins), `StandaloneSourceTest` (no provider -> gateway sync into memory, no disk cache written),
  `IncomingFirstFrameTest` (cached picture on the first frame).
- Screenshots OPENED: `x1-wa-avatar-recents`, `x1-wa-avatar-incoming`, `x1-wa-avatar-person-sheet` (light + dark).
- Full gate as in Step 4; release APKs of `:contacts`, `:phone` (both flavors), `android/catalog`; `dexdump` of the
  provider/worker keep rules. epics: X.1 -> done with Evidence. Next planner: X.2 (round 16), then E6.

## Verification list for the next planner

Files: `phone/.../settings/{blocked,replies,speeddial,sim,data,backup}/`, `phone/.../notes/`,
`platform/.../calllog/BlockPrefixes.kt`, `design/.../text/LtrIsolate.kt`, the stacked list row, `android/people/`,
`contacts/.../people/{PeopleStore,PeopleProvider,PeopleSyncWorker}.kt`, `rovsti_app_core/src/people*`. Tests:
`BlockedViewModelTest`, `BlockedScreenTest`, `SettingsEndPaddingTest`, `QuickRepliesScreenTest`, `DataViewModelTest`,
`BackupViewModelTest`, `NotesViewModelTest`, `NotesScreenTest`, `PillFeedbackTest` (F31), `ListRowStackingTest`,
`LtrIsolateTest`, `AvatarResolverTest`, `PeopleProviderTest`, `PeopleSyncWorkerTest`, `ContactsAvatarSurfacesTest`,
`PhoneAvatarSurfacesTest`, `NotificationLargeIconTest`, `StandaloneSourceTest`. `checklist-e5.md` without MISSING rows.
`git stash list` shows only stashes not yet verified in HEAD.
