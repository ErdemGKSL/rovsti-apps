# util-contacts: everyday utilities for Rovsti Contacts (round 5)

baseSha: 4e256b536dd85ed61572685c60d0ddfca54b062f

## Audit (what exists at baseSha)

Rounds 1-4 (`util-contacts.md`, `-r2.md`, `-r3.md`, `-r4.md`) are fully landed: C1-C32, C34, C36 are
done (last commits 694f348 "Docs, backlog and review fixes (C26-C36)" and 4e256b5 "utilities review
fixes") and ticked in `docs/roadmap.md` ("Utilities, round 4"). Clean tree, no stash, about 6 GB free
(run `rm -rf target/debug/incremental` before building when `df` shows under 6 GB). The separate UX pass
(`ux-contacts-r5.md`) returned done.

Facts the R5 steps build on (checked at baseSha, do not re-create):
- vCard: `rovsti_core::vcard::{parse, write, export_name, Parsed}` read 2.1/3.0/4.0 and write 3.0
  (`push_line` folds at 75 bytes). `PHOTO` is skipped on read (test at vcard.rs ~l. 666) and never written.
  `ContactDraft` (contact_edit.rs l. 164) has no photo field and is the JSON sent to Kotlin `save` /
  `import`: do NOT add photo bytes to it.
- Single-card vCard writers: `detail.rs` l. ~391 (share `.vcf`), ~413 (copy vCard), ~451 (save to file);
  `qr_sheet.rs` l. 76 (QR payload: must stay photo-free, QR is size-limited).
- Single-card intake: `transfer.rs::route_scan` / `trash.rs::scanned_route` (scan, paste, shared file)
  open the editor prefilled; many cards go to `plan_cards` (import preview, Kotlin `import`).
  The editor already holds `pending_photo: Option<(String /*path*/, CropView)>` for a new contact and
  applies it after the first save (`editor.rs::save_editor` -> `apply_photo_with`). `CropView: Default`.
- Photos: `backend::full_photo(uri) -> Option<(RgbaImage, PathBuf)>` (background only); the app's
  `image` dep has `jpeg` + `webp` features (`image::codecs::jpeg::JpegEncoder::new_with_quality`).
  `rovsti_core` has no base64 dep; the workspace has `base64 = "0.22"` (used by `rovsti_wa_proto`).
- Files: `documents::export_contacts(name, text)` (mime `text/x-vcard`), `documents::export_calls(name,
  csv)` (mime `text/csv`, answers `DocumentOp::ExportCalls`, which Contacts does not handle yet),
  `documents::import_contacts()` (Kotlin `importContactsIntent`, mimes x-vcard/vcard/directory/plain/
  octet-stream; used only by Contacts). `contact_intake::{from_file, from_text, is_vcard, Intake}`.
- Editor save: `editor.rs::save_editor` has `editor.initial` (the draft before editing) and ends in
  pill "Saved."; `NoticeAction` (main.rs l. 118) has `UndoDelete`, `UndoMerge`, kept by `keeps_undo`.
- Search: `search::{fold, ContactIndex::search_loose}` over names/emails/numbers only. `backend::pull_full`
  gives every `ContactDraft` (org, nickname, title, note, addresses, websites, emails).
- Call log: Contacts already asks for the call-log permission (C21, `history.rs`, `providers::call_log`,
  `history::DENIED`). `call_stats::{from_sources, StatCall, top_people, PersonStat}` exist (Phone uses them).
- Country of a number: `calling_code::{country_line(raw, home_cc), foreign_country}` exist and are used by
  Phone only; Contacts stores `home_cc` (R4 C32, `number_format::infer_home_cc`).
- Prefs: `ContactsPrefs` (`contacts_prefs.rs`, `MAX_FILE_BYTES` 64 KiB, `favorites_order` lookup keys).
  `WireContact { id, display_name, numbers: Vec<WireNumber>, starred, photo_uri, lookup_key, ... }`.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| C1-C10 | Photo viewer, save/share photo, change/remove, share as text/vcf/QR, dates, addresses, vCard fields, duplicates+merge, upcoming birthdays, scrubber | done R1 |
| C11-C18 | Select mode, export one, crop/rotate, camera, recently added, copy contact / vCard, WhatsApp per number, websites/nickname/title | done R2 |
| C19-C25 | Scan to add, Trash, card call history, birthday reminders, default number/email, Clean up, group utilities | done R3 |
| C26-C32, C34, C36 | Favorites order, smarter search, paste to add, summary, quick actions, open shared .vcf, copy number as, ringtone/voicemail, Home screen shortcut | done R4 |
| C40 | Undo last edit: after Save, "Saved · Number added" pill with Undo restores the previous version | done R5 |
| C33 | vCard PHOTO: single-card share / save / copy carries the photo (JPEG, size-capped); a single-card import with a photo prefills the editor photo | done R5 |
| C41 | Search in details: company, job title, nickname, note, address, email, website ("In details" section with the matched snippet) | done R5 |
| C39 | CSV export (Google CSV column set) and CSV import (Google / Outlook headers) next to the .vcf rows | done R5 |
| C42 | Frequently contacted on Favorites (local call log, last 30 days, not starred) | done R5 |
| C38 | My card: mark a contact "This is me"; pinned "My card" row at the top of Contacts with Share / QR | done R5 |
| C43 | Emergency contacts (up to 5): list in Settings, one-tap call, "Share emergency info" text | done R5 |
| C44 | Country of a foreign number on the card ("Germany") from `calling_code::country_line` | done R5 |
| C45 | Recently edited (`CONTACT_LAST_UPDATED_TIMESTAMP`) next to Recently added | backlog R6 |
| C46 | Phonetic name and Relationship fields (card, editor, vCard `X-PHONETIC-*`, `RELATED`) | backlog R6 |
| C47 | Share several selected contacts as plain text (select mode) | backlog R6 |
| C48 | Multi-card vCard import keeps photos (Kotlin import writes the photo per card) | backlog R6 |
| C35 | Block / unblock a number from the card (hand-off to Rovsti Phone, which holds the dialer role) | deferred (Phone change) |
| C37 | Last SMS with this contact on the card | dropped (READ_SMS belongs to Messages) |

## Rules for every step

All checks green after each step: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
One focused commit per step, prefix `Rovsti contacts: `. Do not change Phone / Messages behaviour (shared
`rovsti_core` / `rovsti_android` changes keep them compiling: fix struct literals only). Read
`.claude/skills/liquid-glass-gpui/SKILL.md` before S5-S7; use only GPUI APIs already used in this repo or
grepped in `~/.cargo/git/checkouts/gpui-fusion-*/d6c8412/crates/gpui`. Every action ends in a pill
(`self.notice`); destructive ones (Remove my card, removing an emergency contact) go through
`confirm.rs::Confirm`; Undo of an edit is itself the undo and needs no confirm. Blocking work (pull_full, call
log, JPEG encode, file reads) runs on the background executor.

## Steps (round 5)

### S1. Pure: vCard PHOTO and CSV (C33, C39)
- `crates/rovsti_core/Cargo.toml`: add `base64.workspace = true`.
- `crates/rovsti_core/src/vcard.rs` (update the module doc):
  `pub const MAX_PHOTO_BYTES: usize = 256 * 1024;`
  `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum PhotoFormat { Jpeg, Png }` with
  `pub fn mime(self) -> &'static str` and `pub fn sniff(bytes: &[u8]) -> Option<Self>` (FF D8 FF / PNG magic).
  `#[derive(Clone, Debug, PartialEq, Eq)] pub struct CardPhoto { pub format: PhotoFormat, pub bytes: Vec<u8> }`.
  `pub fn photos(text: &str) -> Vec<Option<CardPhoto>>`: one entry per card in `parse(text).contacts`
  order (skipped cards are not counted; implement inside the same pass, e.g. `Builder` gains
  `photo: Option<CardPhoto>` and a private `parse_full` returns both, `parse` keeps its signature).
  Accepts 2.1 `PHOTO;ENCODING=BASE64;JPEG:`, 3.0 `PHOTO;ENCODING=b;TYPE=JPEG:`, 4.0
  `PHOTO:data:image/jpeg;base64,...`; whitespace inside base64 ignored; `VALUE=uri` http(s) photos
  ignored (no network); decoded size over `MAX_PHOTO_BYTES`, bad base64 or unknown magic -> `None`;
  format taken from the magic bytes, not the TYPE param.
  `pub fn write_one(draft: &ContactDraft, photo: Option<&CardPhoto>) -> String`: same as `write(&[draft])`
  plus `PHOTO;ENCODING=b;TYPE=JPEG|PNG:<base64>` before `END:VCARD`, folded by `push_line`.
  `write` stays photo-free (bulk export size).
- New `crates/rovsti_core/src/contact_csv.rs` (+ `pub mod`):
  `pub fn write(drafts: &[ContactDraft]) -> String`: UTF-8 BOM, CRLF, RFC 4180 quoting; Google columns
  `First Name, Middle Name, Last Name, Nickname, Organization Name, Organization Title, Birthday, Notes,`
  then `Phone N - Label, Phone N - Value`, `E-mail N - Label, E-mail N - Value`,
  `Address N - Label, Address N - Formatted`, `Website N - Value` for N = 1..=max used (cap 10 each);
  labels "Mobile", "Home", "Work", custom label text, `* ` prefix for the primary (Google's marker);
  birthday as `contact_dates` Android form.
  `pub fn parse(text: &str) -> vcard::Parsed`: header row mapped case-insensitively; Google (`First Name`
  / `Given Name`, `Last Name` / `Family Name`, `Name`, numbered Phone/E-mail/Address/Website columns, values
  split on ` ::: `) and Outlook (`First Name`, `Last Name`, `Company`, `Job Title`, `E-mail Address`,
  `E-mail 2 Address`, `Mobile Phone`, `Home Phone`, `Business Phone`, `Notes`, `Birthday`); quoted fields with
  commas, `""` and newlines; BOM; rows that fail `ContactDraft::validate` counted in `skipped`;
  at most 5000 rows.
  `pub fn looks_like_csv(mime: Option<&str>, name: &str, head: &[u8]) -> bool` (`text/csv`,
  `text/comma-separated-values`, `.csv`, or a first line with `First Name` / `Given Name` and a comma).
  `pub fn file_name(count: usize, today: (i32, u8, u8)) -> String` (`rovsti-contacts-<n>-YYYY-MM-DD.csv`).
- `contact_intake.rs`: `from_file` tries `contact_csv` when `looks_like_csv` (before the vCard check
  fails), giving `Intake::Cards`; `notice` unchanged.
- Tests: v2.1 / v3 / v4 photo decode; folded photo lines; oversize -> None; uri photo -> None; PNG magic
  with TYPE=JPEG -> Png; `photos` aligned with skipped cards; `write_one` round trip (parse + photos give
  the same draft and bytes); `write` still has no PHOTO; CSV round trip of a full draft (Turkish text,
  commas, quotes, newline in note, two numbers with primary); Google export from the docs header; Outlook
  header; ` ::: ` multi values; BOM; skipped rows; `looks_like_csv`; intake routes a CSV to Cards.
- Acceptance: `cargo test -p rovsti_core vcard contact_csv contact_intake` green; no app change.

### S2. Pure: undo last edit, search in details, frequently contacted (C40, C41, C42)
- `crates/rovsti_core/src/contact_edit.rs`:
  `pub fn undo_target(before: &ContactDraft, after: &ContactDraft) -> Option<ContactDraft>`: `None` when
  `before.id` is `None` (a new contact) or both are equal after `clean()`; else `before` cleaned, with
  `id = after.id`. `pub fn change_summary(before: &ContactDraft, after: &ContactDraft) -> Option<String>`:
  one change -> "Name changed" / "Number added" / "Number removed" / "Email changed" / "Note changed" /
  "Birthday added" ...; 2+ -> "3 changes"; none -> `None`. Pill text is `saved_notice(summary)` ->
  "Saved · Number added" or "Saved.".
- New `crates/rovsti_core/src/contact_find.rs`:
  `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum DeepField { Company, JobTitle, Nickname, Note,
  Address, Email, Website }` with `pub fn label(self) -> &'static str`;
  `#[derive(Clone, Debug, PartialEq, Eq)] pub struct DeepHit { pub id: i64, pub field: DeepField, pub
  snippet: String }`; `pub struct DeepIndex` with `pub fn new(drafts: &[ContactDraft]) -> Self` (folded
  texts via `search::fold`, drafts without an id skipped) and `pub fn search(&self, query: &str, skip:
  &[i64], limit: usize) -> Vec<DeepHit>` (query folded, at least 2 chars, digit-only queries return
  nothing (numbers are covered by `search_loose`); first matching field per contact in the enum order;
  `skip` = ids already shown by name; snippet = up to 48 chars around the hit on char boundaries with
  `…`, newlines as spaces).
- New `crates/rovsti_core/src/contact_frequent.rs`:
  `pub const WINDOW_MS: i64 = 30 * 86_400_000; pub const MAX_SHOWN: usize = 6;`
  `#[derive(Clone, Debug, PartialEq, Eq)] pub struct Frequent { pub contact_id: i64, pub calls: u32,
  pub last_ms: i64 }`; `pub fn rank(calls: &[StatCall], contacts: &[WireContact], now_ms: i64, limit:
  usize) -> Vec<Frequent>`: calls in the window matched to a contact through its `numbers` with
  `PhoneNumber::matches` (fallback `import_plan::number_key` equality), missed calls count half, calls in
  the last 7 days count double (score in integer halves), starred contacts and contacts with 1 call left out,
  sorted by score desc then `last_ms` desc then id; `pub fn subtitle(f: &Frequent) -> String`
  ("5 calls in the last 30 days", "1 call ..." singular never shown since 1 is excluded, keep the form).
- Tests: undo for new / unchanged / changed; summaries for each single change and several; deep search
  per field, Turkish folding ("işçi" finds "IŞÇI"), skip list, limit, snippet boundaries with emoji,
  digits -> empty, one-char -> empty; frequent ranks by score, ties by recency, window edge, starred out,
  national vs international numbers matched to the same contact, unknown numbers ignored.
- Acceptance: `cargo test -p rovsti_core contact_edit contact_find contact_frequent` green.

### S3. Pure: My card and emergency contacts (C38, C43)
- `crates/rovsti_core/src/contacts_prefs.rs`: `#[serde(default)] pub my_card: Option<String>` (lookup
  key), `#[serde(default)] pub emergency: Vec<String>` (lookup keys), `pub const MAX_EMERGENCY: usize = 5;`,
  `pub fn emergency_keys(&self) -> Vec<String>` (dedup, drop empty, cap). Old JSON (with and without
  `favorites_order`) still parses; fix `Default` / `from_style` literals.
- New `crates/rovsti_core/src/contact_me.rs`:
  `pub fn resolve<'a>(key: Option<&str>, all: &'a [WireContact]) -> Option<&'a WireContact>` (by
  `lookup_key`); `pub fn my_card_row(found: Option<&WireContact>) -> (String, String)` (("My card",
  "Ayşe Yılmaz") or ("Set up my card", "Choose the contact that is you"));
  `pub fn my_card_notice(set: bool) -> &'static str` ("Set as my card" / "My card removed");
  `#[derive(Debug, PartialEq, Eq, thiserror::Error)] pub enum EmergencyError { #[error("Up to 5
  emergency contacts")] Full, #[error("This contact has no number")] NoNumber, #[error("This contact has
  no key yet")] NoKey }`;
  `pub fn toggle_emergency(keys: &[String], contact: &WireContact) -> Result<(Vec<String>, bool /*added*/),
  EmergencyError>`; `pub fn emergency_list<'a>(keys: &[String], all: &'a [WireContact]) -> Vec<&'a
  WireContact>` (in key order, missing ones dropped);
  `pub fn emergency_text(owner: Option<&str>, entries: &[(&str, &str)]) -> String`
  ("Emergency contacts for Ayşe Yılmaz:\n1. Mehmet Yılmaz: +90 532 ...\n..."; "Emergency contacts:" without
  an owner); `pub fn emergency_notice(added: bool) -> &'static str`.
- Tests: prefs default and old JSON, cap and dedup; resolve by key and missing key; row texts; toggle add /
  remove / full / no number / no key; list order with a deleted contact; share text with and without owner.
- Acceptance: `cargo test -p rovsti_core` green; workspace compiles.

### S4. Backend wrappers and Android glue (C33, C39, C41, C42)
- `crates/rovsti_android/kotlin/dev/rovsti/bridge/Documents.kt`: `importContactsIntent` mime list gains
  `text/csv`, `text/comma-separated-values` (only Contacts uses it). No manifest change (Contacts does not
  claim every CSV).
- `apps/contacts/src/backend.rs`:
  `pub(crate) fn card_photo(uri: &str) -> Option<CardPhoto>`: `full_photo(uri)`, resize so the long side is
  at most 512 px, JPEG quality 85 via `image::codecs::jpeg::JpegEncoder::new_with_quality`; retry at 384 px /
  quality 70 when over `vcard::MAX_PHOTO_BYTES`; `None` on failure (desktop: the sample portrait).
  `pub(crate) fn photo_file(photo: &CardPhoto) -> Result<PathBuf, String>`: writes `import-photo-<n>.jpg|png`
  into the existing `temp_dir()` (for `pending_photo`).
  `pub(crate) fn csv_text(hidden: &[i64]) -> Result<(String, usize), String>` (`pull_full`, drop hidden,
  `contact_csv::write`); `pub(crate) fn start_export_csv(name: &str, csv: &str) -> Result<(), String>`
  (`documents::export_calls`, answer `DocumentOp::ExportCalls`).
  `plan_import` path: the imported text goes through `contact_intake` so a picked CSV reaches the preview
  (check `transfer.rs::plan_import`; route by `contact_csv::looks_like_csv(None, "", text.as_bytes())`).
  `pub(crate) fn deep_index() -> Result<DeepIndex, String>` (`pull_full` + `DeepIndex::new`).
  `pub(crate) fn frequent(contacts: &[WireContact], now_ms: i64) -> Result<Vec<Frequent>, String>`
  (`providers::call_log(PULL_LIMIT)` -> `call_stats::from_sources(&entries, &[])` -> `contact_frequent::rank`);
  without the permission returns `Err(history::DENIED)`-style words, desktop answers from `sample`
  (add a small sample call list if `sample.rs` has none).
- Intake with a photo: `Intake::Single` / file route for exactly one card keeps `vcard::photos(text)[0]`;
  add `pub(crate) fn single_photo(text: &str) -> Option<CardPhoto>` used by S5.
- Tests (desktop): `card_photo` on the sample gives a JPEG under the cap; `photo_file` writes readable bytes;
  `csv_text` header + sample row count; `deep_index` finds a sample company; `frequent` desktop result shape.
- Acceptance: all checks green incl. the three aarch64 checks.

### S5. UI: photo in single-card vCards, CSV export / import (C33, C39)
- `detail.rs` share `.vcf`, Copy vCard and Save to file: on the background executor build
  `vcard::write_one(&draft, photo.as_ref())` with `photo = contact.photo_uri.and_then(backend::card_photo)`;
  the QR payload (`qr_sheet.rs`) stays `vcard::write` (no photo). Copy vCard with a photo shows
  "vCard copied (with photo)".
- Single-card intake (scan, paste, shared or picked `.vcf` with one card): when `single_photo` gives a photo,
  `photo_file` it and set `editor.pending_photo = Some((path, CropView::default()))` so the editor shows it
  and "Remove photo" works; pill "Photo added from the card" once the editor opens.
- Settings "Contacts file" section: "Export to a CSV file" row (`LucideFileSpreadsheet` if present in
  `gpui_icons`, else `LucideFileText`; grep first) under "Export to a .vcf file": `csv_text` ->
  `start_export_csv(contact_csv::file_name(..))`; `on_document_result` handles `DocumentOp::ExportCalls`
  like `ExportContacts` ("412 contacts saved"/"Can't save the file."). Nothing to export -> existing
  empty-book pill. "Import from a .vcf file" row renamed "Import from a file" with subtitle ".vcf or .csv".
  Select mode "Export" sheet and the group page "Save to file" gain a "CSV" choice next to ".vcf" when
  they already offer a format list; otherwise leave them (do not add a sheet just for this).
- Tests: `export_rows()` (or the existing settings row helper) lists both exports; `document_op_notice`
  helper maps ExportCalls the same as ExportContacts; intake helper picks `pending_photo` only for one card.
- Acceptance: all checks green.

### S6. UI: Undo last edit, search in details, Frequently contacted (C40, C41, C42)
- Undo: `save_editor` keeps `contact_edit::undo_target(&editor.initial, &draft)`; on success the pill is
  `saved_notice(change_summary(..))` with a new `NoticeAction::UndoEdit` button ("Undo") kept by `keeps_undo`
  while `self.edit_undo: Option<ContactDraft>` is set (cleared by navigation / next action like
  `merge_undo`). Tap -> `backend::save(&before)` on the background executor -> reload -> pill "Edit undone"
  (failure: "Can't undo: <words>"). The photo is not part of the undo (say so in the doc comment).
- Search in details: when the query has 2+ chars, `rebuild_rows` asks a cached `deep: Option<DeepIndex>`
  (built once per book reload on the background executor via `backend::deep_index`, dropped on reload)
  and appends a section header "In details" with rows "Ayşe Yılmaz" / "Company · Acme Ltd" (snippet) after
  the name results, skipping ids already listed; tap opens the card. The No-results "Create contact" offer
  shows only when both lists are empty. `picker.rs` is unchanged.
- Frequently contacted: Favorites tab, under the favorites list, section "Frequently contacted" with up to
  `MAX_SHOWN` rows (avatar, name, `subtitle`), tap opens the card, long press opens the R4 quick sheet,
  trailing star button adds to Favorites (pill). Loaded on the background executor on Favorites open /
  book reload only when `permissions::has(Group::CallLog)` is already true (never asks from here); hidden
  when empty or without permission. A "Clear" is not needed (data is the system call log).
- Tests: `keeps_undo` with `UndoEdit`; `deep_rows(hits, contacts) -> Vec<RowSpec>` mapping; frequent
  section hidden when empty; existing tests updated.
- Acceptance: all checks green.

### S7. UI: My card, Emergency contacts, country on the card (C38, C43, C44)
- My card: Contacts tab top row (above "Recently added", only when the search is empty) from
  `contact_me::my_card_row`; set -> tap opens the card with a "My card" badge under the name; unset -> tap
  opens the existing contact picker (`picker.rs`, single choice) to choose "This is me". Card more menu:
  "Set as my card" / "Remove my card" (remove with `Confirm`); saved in `ContactsPrefs.my_card`
  (`save_prefs`), pill `my_card_notice`. On My card the share menu entries "Share", "Show QR code" are the
  existing ones (no new share code).
- Emergency contacts: card more menu "Add to emergency contacts" / "Remove from emergency contacts"
  (`toggle_emergency`, errors as pills); Settings section "Emergency" row "Emergency contacts" (count
  subtitle) opening a small page (pattern of `recent.rs`): rows with a Call glass button (existing call
  path), "Add" (picker, contacts with a number), swipe-free remove via each row's trailing minus button
  with `Confirm`, and "Share emergency info" -> `emergency_text(my card name, entries)` through the existing
  text share path; pill on every action. Saved in `ContactsPrefs.emergency`.
- Country: on the card, each number row's subtitle gains `calling_code::country_line(number, home_cc)`
  when it returns `Some` (foreign numbers only), e.g. "Mobile · Germany".
- Tests: `my_card_menu_entry(is_me) -> &'static str`; emergency page rows mapping and the add filter
  (no-number contacts excluded); number subtitle with and without a country.
- Acceptance: all checks green.

### S8. Docs, backlog and review fixes (C33, C38-C44)
- `docs/roadmap.md` Rovsti Contacts: "Utilities, round 5 (C33, C38-C44, done without a device)" with one line
  per utility, and a device-check line for vCard photos, CSV in Google Contacts, Frequently contacted.
  `docs/architecture.md`: vCard PHOTO only for single cards, CSV via the `text/csv` save picker, My card and
  emergency keys in `contacts-prefs.json`, frequent list read-only from the call log.
- Update this file's backlog statuses to `done R5` for what landed; keep C45-C48 as `backlog R6`.
- Self review of the R5 diff (`git diff 4e256b5..HEAD -- apps/contacts crates/rovsti_core
  crates/rovsti_android`): no blocking work on the UI thread, every action has a pill, Confirm on remove
  My card / emergency remove, QR payload photo-free, no Phone/Messages behaviour change; fix in the same commit.
- Acceptance: all checks green.
