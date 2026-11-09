# Round 11 plan: E4 R2 = Contacts share, import, duplicates, photo viewer, call options, My card, settings, polish (E4.9-E4.16)

Planned 2026-11-09 at base `7bf317d` (branch `ui-overhaul`). Epic: **E4 Contacts**, round R2 (the last E4 round).
E1, E2 and E3 are done; E4 R1 (E4.1-E4.8) is done and was re-checked in code (below).

Owner request for this workflow: "make our project GPL, port the code with very high similarity". The relicensing was
delivered in R3 (E1.23, `542e271`) and re-checked at this base: `LICENSE` is the GPL-3.0 text, root `Cargo.toml` has
`license = "GPL-3.0-or-later"` and every crate uses `license.workspace = true`, `README.md:87-91` has the licence
section, `CLAUDE.md` has the GPL/attribution line, `android/THIRD_PARTY_NOTICES.md` lists Convx, Kyant0 backdrop /
AndroidLiquidGlass, compose-hig, glisse and (R10) `sh.calvin.reorderable`. The glass bug was closed by the Convx
backdrop port (E1.24-E1.26, `GlassRealPathTest`). Rule for this round: **where a Convx file fits, port it close to
verbatim** with the Convx header plus `Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0,
<path>`; glisse-derived code keeps the glisse MIT header. The GPUI app (`apps/contacts/src/*.rs`) is a **feature
inventory only**, never a design reference.

## State found (verified in code, not trusted)

- `git status` clean, HEAD `7bf317d` ("E4 R1 review fixes"). `stash@{0}` is the deprecated GPUI layout WIP: never pop it.
- **E4 R1 done**, checked file by file: `android/settings.gradle.kts:27` includes `:contacts`; every test class the
  round-10 verification list names exists under `android/contacts/src/test/kotlin/dev/rovsti/contacts/**`
  (`ContactsManifestTest`, `ContactsRoutesTest`, `ContactsMigrationTest`, `ContactsStoreTest`, `BookRepositoryTest`,
  `WaMarksTest`, `ContactsListViewModelTest`, `ContactsListScreenTest`, `ContactCardViewModelTest`,
  `ContactCardScreenTest`, `ContactEditorViewModelTest`, `ContactEditorScreenTest`, `PhotoCropTest`,
  `TrashViewModelTest`, `FavoritesViewModelTest`, `GroupsViewModelTest`, `SelectModeViewModelTest`,
  `SelectModeScreenTest`); `MergedManifest` is in `:testing`; Rust `crates/rovsti_ffi/src/ops/contacts/wa.rs` exists;
  80 goldens in `android/contacts/screenshots/` (`e4.1-*` .. `e4.8-*`, light and dark); reorderable in
  `gradle/libs.versions.toml:37` and `THIRD_PARTY_NOTICES.md:316`. E4.1-E4.8 stay `done`.
- **Rust ops for all of R2 already exist** (`ContactsOps.kt`, registered `contacts.*` ops): `vcardWrite`,
  `vcardParse`, `importPlan`, `importPhotos`, `intake`, `scan`, `csvExport`, `csvImport`, `dupes`, `mergePlan`,
  `cleanup`, `options`, `stats`, `pinShortcut`, `meState`, `meToggle`, `recentLists`, `recentNotePull`,
  `birthdayReminders`, `avatar`, `card` (share text), `numberKey`; `QrOps` (`qr.encode`, `qr.decode`). No new FFI op is
  expected; if one is, add it with fixtures (Rule 2).
- Platform seams exist: `ContactsRepository.options/setRingtone/setVoicemail/import` (`platform/contacts/
  ContactsRepository.kt:88-102`), `BlocklistRepository` (`platform/calllog/`), `MediaShare.saveToGallery/stageForShare`,
  `Documents` (SAF), `ClipboardRepository`, Photo Picker contract, `Shortcuts`, `BirthdayReminders` + receiver.
- What is still placeholder: `ContactsPages.kt:162` `IntakePlaceholderPage` (VIEW/SEND vCard target); the Settings tab
  (`ContactsSettings.kt:44-70`) has only WhatsApp, Trash and the common group; the card's photo viewer
  (`ContactCardScreen.kt:196`) is opened without the Save/Share/Change/Remove actions; `ContactsPrefs` (Rust
  `crates/rovsti_core/src/contacts_prefs.rs:32`) has no appearance/haptics fields, so the Contacts `CommonSettings`
  answers `null` for both rows.
- The QR drawing is `private fun QrCard` in `wa/src/main/kotlin/dev/rovsti/wa/settings/WhatsAppSettingsScreen.kt:254`;
  E4.9 needs it too, so it moves to `:design`.
- GPUI inventory for this round: `apps/contacts/src/qr_sheet.rs` (QR sheet, Save/Share image, dark-on-white card in
  dark mode), `transfer.rs` (vCard export/import through SAF + `document_result`), `intake.rs` (Paste contact, shared
  and opened .vcf), `dupes.rs` (duplicates page, review sheet with merged preview, confirmed merge with Undo),
  `cleanup.rs` (no number / no name / no photo / no group filters, select and delete into Trash), `photo_viewer.rs`
  (C1-C3), `call_options.rs` (Calls section: ringtone, send to voicemail; Home-screen shortcut; Settings "Contacts"
  summary C29), `me.rs` (My card C38, emergency C43 row, card menu entries and page), `recent.rs` (recently added C15 /
  edited C45 row and page), settings rows in `main.rs:1991-2160` (Name format, Sort by, New contacts -> Save to,
  Contacts file -> Import from a file / Export, Find duplicates, Clean up, Scan QR code, Paste contact, WhatsApp).
- Disk: **6.5 GB free** (limit 6 GB). Clean before Step 1 (Rule 1).

### Screenshots of the previous round, opened and judged

- `android/contacts/screenshots/e4.2-list-light.png`: sections, one 40 dp avatar per row, WA glyph once per row,
  rail on the trailing edge, list continues under the floating tab bar. Correct. Carry-over (known): row separators run
  to x~860 right next to the rail letters (E4.16).
- `e4.3-card-full-dark.png`: header, three equal capsules, Phone/Email/Dates/Address sections. **F14:** the work
  number reads `+49301234567` (unformatted) while the mobile reads `+90 532 111 22 33`; the card must show every
  number in the same display format (`numberKey`/`card` display string; check whether the sample stored it raw and the
  card skips formatting for non-default rows).
- `e4.2-row-context-menu-dark.png`: lifted row above, menu floats and nothing moved. **F12:** the icon column is
  inconsistent: "Copy number" and "Add to Home screen" have no glyph while the other rows do (iOS menus give every row
  of a group a glyph or none). Add `RovstiIcons.Copy` and `RovstiIcons.AddToHome` (or `square.and.arrow.down` style)
  to those items in the `contacts.quick` -> `MenuItem` mapping (`list/ContactsListScreen.kt`) and the card field menu.
- `e4.1-settings-light.png`: **F13:** the WhatsApp/Trash card and the About card touch (no gap between the two
  `IosSettingsGroup`s). `SettingsPage` (`:shell`) or `IosSettingsGroup` must put the inset-grouped section spacing
  (Convx `IosSettingsGroup` top padding / our `Spacing.sectionGap`) between consecutive groups. Fix in `:shell` once,
  re-record `e3.11-*` settings goldens and `e4.1-settings-*`.
- `e4.8-select-mode-light.png`: check circles, "3 selected", folded tab circle + toolbar accessory. Correct.

## Rules for this round

1. **Disk first, every step.** `df -h .` before any build. Below 8 GB: `rm -rf target/debug/incremental
   target/aarch64-linux-android/debug/incremental android/*/build`. Below 7 GB: also `cargo clean -p` for the deprecated
   GPUI crates (`rovsti_phone rovsti_messages rovsti_stops rovsti_contacts rovsti_ui rovsti_wa_ui liquid_glass_kit`)
   and delete Gradle versions under `~/.gradle/caches/` other than the wrapper's. Never below 6 GB. Start Step 1 with
   the 7 GB clean (we are at 6.5 GB).
2. **Domain logic in Rust.** Every decision goes through the existing `contacts.*` op (State found). Kotlin only orders
   effects. A missing decision becomes a new `rovsti_ffi` op with fixtures, never a Kotlin rewrite.
3. **Screens** (ARCH 5.2): ViewModel with one `@Immutable UiState` (`ImmutableList` fields), `onAction(sealed)`,
   `stateIn(WhileSubscribed(5_000))`, one-shot `Effect`s through a `Channel`. Composables never map/sort/filter. Only
   `:design`/`:shell` components. Every list gets `LocalContentInsets` bottom padding, `key` + `contentType`.
4. **Owner complaints are acceptance criteria** (ARCH 1, `references/navigation-layout.md` 6): one avatar per row,
   WhatsApp as the 14 dp `SourceGlyph` once per row, chip rows are one non-wrapping `LazyRow` (Clean up filters!),
   16 dp gutter, every long-press opens a host context menu floating above the content, text fields are
   `GlassTextField`, nothing pushes content on tap.
5. **Motion** (SKILL refs): pages push/pop through `RovstiNavHost` with predictive back (`shell-usage.md` 7); sheets
   `overlays.md` 5 (`sheetSettle` 0.86/380, dismiss +12 % H or velocity); context menus `overlays.md` 3; button menus
   `overlays.md` 4.3; photo viewer `motion.md` 8 (`Motion.sharedArtwork()` 500 ms, swipe-down dismiss); undo pill /
   toast `overlays.md` 6; list insert/remove `appear` spring; reduce motion via `MotionGate`.
6. **Tests:** JVM ViewModel tests with `FakeRustCore` fixture answers and `:platform` fakes; gestures in Compose tests
   with `mainClock.autoAdvance = false`; `ShotTest` light + dark (plus `-fs2`/`-rtl` where named) into
   `android/contacts/screenshots/`. **Every new PNG is opened with Read and judged**; what was seen goes in the commit
   message and the epics Evidence line.
7. **Check per step:** `bash scripts/kotlin-ui-check.sh` prints `ALL GREEN`; Rust changes also run
   `cargo test -p rovsti_app_core -p rovsti_ffi` and `bash scripts/check-ffi-exports.sh`. Full `CLAUDE.md` gate in
   Step 8. `references/checklist.md` B-H in each screen commit.
8. **Commits:** one per step, `Kotlin UI (E4 R2): <step title>`, ending with the session attribution lines. Never push
   to `main`.

## Steps

### Step 1. Screenshot fixes F12-F14, `QrCode` in `:design`, Share and shortcuts (E4.9)

- Disk clean (Rule 1, 7 GB level).
- F13 in `:shell` (`SettingsPage` / spacing between `IosSettingsGroup`s, 35 pt iOS section gap as Convx uses for
  inset-grouped lists); F12 glyphs on every menu item of the row and field menus; F14 one display format for every
  number on the card. Re-record the affected goldens (`e3.11-*settings*`, `e4.1-settings-*`, `e4.2-row-context-menu-*`,
  `e4.3-card-full-*`, `e4.3-field-menu-open-*`) and open them.
- Move `QrCard` from `:wa` to `:design` as `dev.rovsti.design.media.QrCode(matrix: QrMatrix?, modifier)` (dark modules
  on a white rounded card in both themes, quiet zone 4 modules, `drawWithCache` with one `Path`); `:wa` calls it.
  `QrCodeTest` (pixel at a dark module is black in dark theme too).
- E4.9 (C4, C12, C16, C36): card top-bar "..." `ButtonMenuEntry` gains **Share** submenu: "Share as Text"
  (`contacts.card` share text -> `ShareIntents.text`), "Share as File" (`vcardWrite` one contact ->
  `MediaShare.stageForShare("<Name>.vcf")` -> SEND with `text/x-vcard` via `RovstiShareProvider`: closes the R1
  carry-over), "Show QR Code" (`QrSheet`: `SheetEntry` medium detent, `qr.encode` of the vCard payload, name under the
  code, buttons "Save Image" -> `MediaShare.saveToGallery` + toast "Saved to Photos", "Share Image" -> staged PNG);
  **Export .vcf** (C12, `Documents.create("text/x-vcard")`); **Copy** submenu "Copy Contact" / "Copy as vCard" (C16,
  `ClipboardRepository`, toast "Copied"); **Add to Home Screen** (C36, `pinShortcut` -> `Shortcuts.pin`; also in the
  row context menu, already wired by R1 - verify it calls the same path). Bitmap for Save/Share: render `QrCode` into an
  `ImageBitmap` off-screen (`GraphicsLayer.toImageBitmap`) at 1024 px.
- Tests: `ContactShareTest` (VM effects: text share has the card text; file share stages `<name>.vcf` with the
  `vcardWrite` fixture body; export opens the create-document contract; pin uses `pinShortcut` answer),
  `QrSheetTest` (Compose: Save tapped -> `saveToGallery` called once, toast shown; sheet dismisses by drag).
- Screenshots: `e4.9-share-menu`, `e4.9-qr-sheet` (light/dark; the card stays white in dark). Open each.

### Step 2. Import paths 1: intake page, Paste contact, Import from a file, add to existing (E4.10 AC1, C7, C31, C28, C33, C48, C49)

- Replace `IntakePlaceholderPage` with `IntakeScreen` + `IntakeViewModel(source, ops, repo, book, store)`; sources:
  shared/opened `Uri` (VIEW/SEND, R1 route `Intake(uri)`), Settings "Import from a File" (`Documents.open` with the
  vCard MIME set), Settings / list "+" menu "Paste Contact" (C28: reads the clipboard only on tap, `contacts.intake`
  decides vCard vs plain text vs nothing -> toast "Nothing to paste").
- Flow: bytes -> `vcardParse` (C7 dates/ADR) -> `importPlan` against the book (dedup: new / same / update counts) ->
  preview sheet "Add these contacts?" (`SheetEntry` large detent: count line, list of cards with avatar from
  `importPhotos` (C33 PHOTO, C48 multi-card photos), each row tappable to a read-only card preview (C31)), buttons
  "Add N Contacts" / "Cancel". Single-card files also offer "Add to Existing Contact" (C49): `ContactPicker`
  (`people/ContactPicker.kt`) -> `draftAdd` merge -> `repo.save` -> undo pill restoring the previous draft.
  Import -> `repo.import(drafts)` on IO, then toast "Added 12 contacts" with Undo (deletes the new ids through Trash).
- Tests: `IntakeViewModelTest` (two-card fixture: counts, dedup skips the same contact; plain-text paste -> draft;
  empty clipboard -> toast; add-to-existing undo restores), `ContactsRoutesTest` additions (VIEW uri reaches the
  page), Compose `IntakeScreenTest` (Add button disabled while parsing; cancel pops).
- Screenshots: `e4.10-import-preview`, `e4.10-add-to-existing-picker`, `e4.10-paste-nothing-toast`. Open each.

### Step 3. Import paths 2: Scan QR from an image, CSV export/import (E4.10 C19, C39)

- "Scan QR Code" (Settings and the "+" menu, C19): Photo Picker (no media permission) -> decode the image on
  `Dispatchers.Default` to a luminance array -> `qr.decode` -> `contacts.scan` decides (vCard -> intake preview of
  Step 2, `tel:`/MECARD -> editor prefilled via `draftFromQuery`, anything else -> alert "No contact in this code").
  Result screen reuses the intake preview.
- CSV (C39): Settings "Contacts File" section rows "Export as CSV" (`csvExport` -> `Documents.create("text/csv")`) and
  "Import CSV" (`Documents.open` -> `csvImport` -> column-mapping sheet listing detected columns with a
  `ButtonMenuEntry` per column (First name, Last name, Phone, Email, Ignore) -> intake preview).
- Tests: `ScanQrTest` (fixture luminance of a generated QR -> vCard -> preview; non-contact -> alert),
  `CsvImportViewModelTest` (mapping changes re-run `csvImport`; export body equals fixture).
- Screenshots: `e4.10-scan-result`, `e4.10-csv-mapping`. Open each.

### Step 4. Duplicates, merge and Clean up (E4.11, C8, C24, C54)

- `DuplicatesScreen` (Settings "Find Duplicates"): `dupes` groups computed once per book pull on `Default`; rows show
  the stacked names (one avatar, the first; "and 2 more" line - no overlapping avatar stacks), "Merge All" toolbar
  button with confirm. Tap -> `MergeReviewSheet` (large detent): merged preview card from `mergePlan` (fields with the
  source chips as single-line text, not pills), "Merge" -> trash snapshots of the losers (`trashPush`) + save merged
  -> undo pill restoring all (one undo). `EmptyState` "No Duplicates".
- `CleanupScreen` (Settings "Clean Up", C24/C54): filter chips No Number / No Name / No Photo / No Group (and the extra
  C54 filters the op returns) as ONE non-wrapping `ChipRow` (`LazyRow`), counts from `cleanup`; list in select mode
  reusing `SelectController`; Delete -> confirm -> Trash + one undo pill (same path as E4.8).
- Tests: `DuplicatesViewModelTest` (merge -> one undo restores all three; Merge All confirm), `CleanupViewModelTest`
  (filter switch, delete goes through `trashPush`), Compose test: chip row scrolls horizontally, never wraps at fs2.
- Screenshots: `e4.11-duplicates`, `e4.11-merge-preview`, `e4.11-cleanup-chips`, `e4.11-cleanup-chips-fs2`. Open each.

### Step 5. Photo viewer on the card (E4.12, C1-C3) and ringtone, voicemail, block (E4.13, C34, C35)

- E4.12: the card avatar opens `showPhotoViewer` with the full-size photo (`ContactsRepository.photo` full size; the
  thumbnail first, shared 500 ms flight from the avatar rect via `anchorFor`) and `ViewerAction`s "Save" (C2,
  `saveToGallery`, toast), "Share" (staged JPEG), "Edit" `IosMenu` Choose Photo / Take Photo / Use WhatsApp Photo /
  Remove Photo (C3: reuses `photo/PhotoMenu.kt` and the crop screen of R1; Remove confirms). Viewer chrome hides on tap;
  swipe-down closes.
- E4.13: card "Calls" section (from `options` + `call_options.rs` texts): "Ringtone" row with the current name ->
  `RingtoneManager.ACTION_RINGTONE_PICKER` activity-result contract (default / silent / chosen) -> `setRingtone`;
  "Send Calls to Voicemail" `IosToggle` -> `setVoicemail` with confirm alert when turning on (texts from the op); "Block
  This Caller" / "Unblock" destructive row -> confirm -> `BlocklistRepository.add/remove` for every number of the
  contact (needs default dialer or SMS role: when `BlockedNumberContract.canCurrentUserBlockNumbers` is false the row
  explains "Set Rovsti Phone as the default phone app to block" and opens role settings).
- Tests: `PhotoViewerActionsTest` (Edit menu items by state, Save calls gallery once), `CallOptionsViewModelTest`
  (ringtone result saved, voicemail confirm, block all numbers, cannot-block sentence), Compose test: toggle animates
  with the switch spring and the section does not jump.
- Screenshots: `e4.12-viewer`, `e4.12-viewer-menu`, `e4.13-card-options`, `e4.13-voicemail-confirm`,
  `e4.13-block-confirm`. Open each.

### Step 6. My card, emergency, recently added and edited (E4.14, C38, C43, C15, C45)

- My card (C38): list header row "My Card" (avatar + name, or "Set My Card" when none) from `meState`; card "..." menu
  "This Is Me" / "Not Me" via `meToggle`; opening it shows the normal card with a "My Card" badge line.
- Emergency (C43): card menu "Add to Emergency Contacts" / "Remove"; Settings / list row "Emergency Contacts" page with
  the user's order (reuses the Convx reorderable port `favorites/FavoritesReorder.kt`), Call buttons per row, `EmptyState`.
- Recently added / edited (C15/C45): list row "Recently Added" (shown per `recentLists` rules) -> page with a
  `SegmentedControl` Added / Edited (Convx-ported segmented), "when" texts from the op (`recentNotePull` on book pull).
- Tests: `MeViewModelTest`, `EmergencyViewModelTest` (order saved via prefs, reorder), `RecentListsViewModelTest`
  (segment switch keeps scroll per segment).
- Screenshots: `e4.14-my-card`, `e4.14-emergency-list`, `e4.14-added-edited-tabs`. Open each.

### Step 7. Settings (E4.15, C22, C29) with appearance and haptics prefs

- Rust: add `appearance: Option<Appearance>`-style and `haptics` fields to `rovsti_core::contacts_prefs::ContactsPrefs`
  (`#[serde(default)]`, missing -> Auto/Normal; bump `SCHEMA_VERSION` only if the loader needs it), update
  `prefs_load`/`prefs_save` fixtures and the Kotlin `StoreModels.kt` model; the Contacts `CommonSettings` now answers
  real flows, so the Appearance and Haptics rows appear. `ContactsMigrationTest`: old fixture without the fields loads
  with the defaults.
- `ContactsSettingsPage` gets the full GPUI set, grouped: **Display** (Name Format, Sort By -> choice pages with
  checkmarks), **New Contacts** (Save To -> account chooser from `accounts()` + `accountLabel`), **Contacts** summary
  page (C29: counts per account from `stats`), **Birthdays** (C22 Reminders toggle + time row; POST_NOTIFICATIONS asked
  on first enable via `PermissionController`, `birthdayReminders` schedules), **Contacts File** (Import from a File,
  Export All as .vcf, Export as CSV, Import CSV), **Tools** (Find Duplicates, Clean Up, Scan QR Code, Paste Contact,
  Emergency Contacts, Trash), **WhatsApp**, then `CommonSettingsGroup`. Section gaps per F13.
- Tests: `ContactsSettingsViewModelTest` (name format change re-sorts the list through `view`; reminders toggle
  without permission asks first; export all body), `SettingsPagesTest` additions.
- Screenshots: `e4.15-settings`, `e4.15-settings-fs2`, `e4.15-accounts-summary`, `e4.15-whatsapp` (reuses E3.10),
  `e4.15-name-format`. Open each; no two cards touch.

### Step 8. Polish (E4.16), close E4

- Carry-overs: row separators stop 8 dp before the alphabet rail column (`ListRow` trailing inset when the rail is
  shown); custom accessibility actions on list rows mirroring the context menu (`semantics { customActions }`);
  recomposition tests for list scroll and search typing (`RecompositionCounter` from `:testing`); motion audit (springs
  retarget mid-flight, no layout shift on press, menus/sheets never push content) recorded in
  `docs/kotlin-ui/checklist-e4.md` A-H for every R2 screen; RTL and fs2 goldens for the new pages (`e4.15-settings-fs2`,
  `e4.11-cleanup-chips-fs2` exist; add `e4.14-emergency-list-rtl`).
- Contact sheet: `docs/kotlin-ui/screenshots/contacts.png` (montage of the main goldens, light row and dark row,
  built by the existing E1.22 contact-sheet script). Open it.
- Close: disk clean; full `CLAUDE.md` gate (`cargo fmt --all && cargo clippy --workspace --all-targets && cargo test
  --workspace`, `cargo check -p rovsti_android --target aarch64-linux-android`), then `bash scripts/kotlin-ui-check.sh`;
  build and name `android/contacts/build/outputs/apk/debug/contacts-debug.apk` and
  `android/catalog/build/outputs/apk/debug/catalog-debug.apk` in the commit. `platform-port.md` rows for the Contacts
  GPUI files become `ported (E4.x)`.
- epics.md: E4.9-E4.16 `done` with one Evidence line each; E4 header `- DONE (R2)`; point the next planner at **E5 R1**
  (Phone E5.1-E5.8).

## Verification summary for the next planner

- `IntakePlaceholderPage` is gone; `QrCode` lives in `:design` and `:wa` uses it.
- Test classes: `ContactShareTest`, `QrSheetTest`, `QrCodeTest`, `IntakeViewModelTest`, `IntakeScreenTest`,
  `ScanQrTest`, `CsvImportViewModelTest`, `DuplicatesViewModelTest`, `CleanupViewModelTest`, `PhotoViewerActionsTest`,
  `CallOptionsViewModelTest`, `MeViewModelTest`, `EmergencyViewModelTest`, `RecentListsViewModelTest`,
  `ContactsSettingsViewModelTest`.
- Goldens `e4.9-*` .. `e4.16-*` in `android/contacts/screenshots/`, all opened; F12/F13/F14 goldens re-recorded.
- Rust `ContactsPrefs` has appearance/haptics with defaults; Contacts Settings shows Appearance and Haptics rows.
- `contacts-debug.apk` and `catalog-debug.apk` built at the close.
