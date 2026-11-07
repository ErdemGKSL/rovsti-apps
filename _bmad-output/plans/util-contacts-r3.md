# util-contacts: everyday utilities for Rovsti Contacts (round 3)

baseSha: 2baca187e0db5b88877f3761c4aa458ec7481d4c

## Audit (what exists at baseSha)

Rounds 1 and 2 (`util-contacts.md`, `util-contacts-r2.md`, S1-S8 each) are fully landed (commits
f68d778 .. 2baca18, last one "utilities review fixes"): C1-C18 are done and ticked in `docs/roadmap.md`.
No stash entries, clean tree, ~9.4 GB free.

Shipped and reusable now (do not re-create):
- Pure: `rovsti_core::{contact_dupes, contact_dates, contact_select, contact_photo (CropView, turns),
  vcard (BDAY/ANNIVERSARY/ADR/URL/NICKNAME/TITLE), contacts_view::{recent, recent_when, show_recent,
  scrubber_*}, multi_select, pending_delete}`; `recents::{history(entries, extras, numbers, limit),
  history_text(line, now_ms, locale)}` and `call_log_query::entries_for_numbers` (Phone's per-contact
  history, reusable as is); `import_plan::number_key`; `number_format::contact_qr` (QR payload).
- App: select mode (`select.rs`), `PendingDelete { ids, task }` + `commit_pending_delete` in
  `confirm.rs` (deletes for good after the Undo window), dupes page, upcoming page, recent page,
  photo viewer / crop / camera, QR sheet (`qr_sheet::qr_payload`), Settings rows "Export to a .vcf
  file", "Import from a .vcf file", "Find duplicates" (`main.rs::settings_tab`), `prefs.rs` (atomic
  JSON file in the app data dir, `rovsti_core::contacts_prefs::ContactsPrefs`).
- Android: `providers::call_log(limit)` (Kotlin `pullCallLog`), `permissions::{Group, has, request}`
  (`Group::Phone` = CALL_PHONE + READ_PHONE_STATE + READ/WRITE_CALL_LOG), `reminders::*` (call and
  message reminders: `ReminderStore<T>` generic in `CallReminders.kt`, receivers declared per host,
  launch-intent extras delivered through `postOneShot` mailbox like `deliverOpenThread`),
  `telecom::{compose_sms(number, body), send_email_to(address)}` (single recipient, `Uri.fromParts`),
  `documents::{pick_contact_photo, capture_photo}` (`document_result` op `contact_photo`).
- Contacts manifest: READ/WRITE_CONTACTS, INTERNET, HANDOFF only. No READ_CALL_LOG, no
  POST_NOTIFICATIONS, no receivers.
- Kotlin `ContactsQuery` already reads `Phone.IS_SUPER_PRIMARY` into `WireNumber.default`, but
  `DraftField` has no primary flag and `ContactsEdit.insertData` never writes `IS_PRIMARY`/
  `IS_SUPER_PRIMARY` (an edit/save clears the default today).

Not present yet: any QR decoder (`rqrr` 0.11.0 is on crates.io, licence `(MIT OR Apache-2.0) AND ISC`,
deps `g2p`, `lru 0.18`, `image` optional: use `default-features = false`); a local trash; per-card call
history; birthday notifications; default number/email; cleanup filters; group-wide SMS / e-mail.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| C1-C10 | Photo viewer, save/share photo, change/remove, share as text/vcf/QR, dates, addresses, vCard fields, duplicates+merge, upcoming birthdays, scrubber | done R1 |
| C11-C18 | Select mode, export one, crop/rotate, camera, recently added, copy contact / vCard, WhatsApp per number, websites/nickname/title | done R2 |
| C19 | Scan to add: decode a contact QR from a picked image (rqrr, pure Rust) -> editor prefilled / import | done R3 |
| C20 | Trash: deleted contacts kept locally as drafts for 30 days, Restore, Delete forever, Empty Trash (confirm) | done R3 |
| C21 | Contact history: last calls with this contact on the card (local call log, READ_CALL_LOG, read-only) | done R3 |
| C22 | Birthday reminders: opt-in morning notification, tap opens the card | done R3 |
| C23 | Default number / email: Set / Clear default from the field sheet, kept on save, vCard `PREF` | done R3 |
| C24 | Clean up page: chips No number, No name, No photo, Not in a group; select + delete (to Trash) | done R3 |
| C25 | Group utilities: Message group (one SMS to all), Email group, Share / save group as .vcf | done R3 |
| C26 | Favorites custom order (drag) using `contacts_view::reordered_keys` | backlog R4 |
| C27 | Fuzzy search: initials ("ay" -> "Ayse Yilmaz") and number fragments in any format | backlog R4 |
| C28 | Import from clipboard: paste a vCard or "Name +90..." text to create a contact (reuses C19 `parse_payload`) | backlog R4 |
| C29 | Contact count and storage summary per account in Settings | backlog R4 |
| C30 | Quick actions on row long-press (Call, Message, Copy number, Share, Star, Select) | backlog R4 |
| C31 | Open a .vcf shared to the app (`ACTION_VIEW`/`SEND` text/x-vcard intent filter) -> import preview | backlog R4 |
| C32 | Copy number in international / national format choice (`number_format`) in the field sheet | backlog R4 |
| C33 | vCard PHOTO import/export (base64 JPEG, size-capped) so shared cards keep their picture | backlog R4 |
| C34 | Per-contact ringtone (`CUSTOM_RINGTONE`, system ringtone picker) and "Send calls to voicemail" | backlog R4 |
| C35 | Block / unblock a number from the card (existing `blocklist` seam, confirm) | backlog R4 |
| C36 | Add contact shortcut to the home screen (pinned `ShortcutManager` shortcut opens the card) | backlog R4 |
| C37 | Contact history: last SMS with this contact (READ_SMS, read-only) next to calls | backlog R4 |

## Steps (round 3)

Every step leaves all checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
If `df` shows under 6 GB free, `rm -rf target/debug/incremental` first. One focused commit per step,
prefix `Rovsti contacts: `. Do not change `apps/phone` / `apps/messages` behaviour (a shared
`rovsti_core`/`rovsti_android` change must keep them compiling; fix struct literals only). Read
`.claude/skills/liquid-glass-gpui/SKILL.md` before S6-S8 and use only GPUI APIs already used in
`apps/contacts` (`on_long_press`, `on_click`, `ListState`, `RenderImage`, `cx.open_url`, glass rows
from `rovsti_ui`). Every action ends in a pill (`self.notice`), destructive ones go through
`confirm.rs::Confirm`.

### S1. Pure: local trash model (C20)
- New `crates/rovsti_core/src/contact_trash.rs` (`pub mod contact_trash;` in `lib.rs`):
  - `pub const SCHEMA_VERSION: u32 = 1; pub const RETENTION_DAYS: u32 = 30; pub const MAX_ENTRIES:
    usize = 500; pub const MAX_FILE_BYTES: usize = 8 * 1024 * 1024;`
  - `#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)] pub struct TrashEntry { pub key: u64,
    pub deleted_ms: i64, pub draft: ContactDraft }` and `#[derive(Default, ..)] pub struct Trash {
    #[serde(default)] pub schema_version: u32, #[serde(default)] pub entries: Vec<TrashEntry> }`.
  - `impl Trash`: `pub fn push(&mut self, drafts: Vec<ContactDraft>, now_ms: i64) -> Vec<u64>` (new keys,
    `max(existing)+1..`, newest first, oldest dropped past `MAX_ENTRIES`); `pub fn purge_expired(&mut
    self, now_ms: i64) -> usize`; `pub fn take(&mut self, keys: &[u64]) -> Vec<ContactDraft>` (removes
    them; returned drafts have `id = None`); `pub fn remove(&mut self, keys: &[u64]) -> usize`;
    `pub fn clear(&mut self) -> usize`; `pub fn sorted(&self) -> Vec<&TrashEntry>` (newest first).
  - `pub fn restore_drafts(drafts: Vec<ContactDraft>, groups: &[Group], accounts: &[Account],
    fallback_account: &str) -> Vec<ContactDraft>`: drops group ids that no longer exist or belong to
    another account, moves a draft whose account is gone to `fallback_account`.
  - `pub fn days_left(deleted_ms: i64, now_ms: i64) -> u32` and `pub fn left_text(..) -> String`
    ("30 days left", "1 day left", "Deleted today" when 0).
  - Texts: `moved_notice(n)` ("Moved to Trash" / "Moved 3 contacts to Trash"), `restored_notice(n)`
    ("Contact restored" / "Restored 3 contacts"), `empty_confirm(n) -> (String, String)` ("Empty
    Trash?", "3 contacts will be deleted for good. This can't be undone."), `forever_confirm(n)`.
- Tests: push keys unique and monotonic across reloads; cap drops the oldest; purge at exactly 30
  days; take clears ids and leaves others; restore_drafts drops stale groups and remaps accounts;
  JSON round trip; JSON without `schema_version` parses; texts singular/plural.
- Acceptance: `cargo test -p rovsti_core contact_trash` green; no app change.

### S2. Pure: scan-to-add decoder and payload parser (C19)
- Root `Cargo.toml` `[workspace.dependencies]`: `rqrr = { version = "0.11", default-features = false }`;
  `crates/rovsti_core/Cargo.toml` `[dependencies] rqrr.workspace = true` and `[dev-dependencies]
  qrcode = { workspace = true }` (workspace `qrcode` has `default-features = false, features = ["image"]`;
  if that pulls `image` into core dev-deps it is acceptable, or use `QrCode::to_colors()` + `width()` to
  draw the test bitmap by hand, preferred). Note the licence (`(MIT OR Apache-2.0) AND ISC`) in
  `docs/architecture.md` third-party list if one exists.
- New `crates/rovsti_core/src/contact_scan.rs`:
  - `pub const MAX_SIDE: u32 = 1600;` `pub fn decode_luma(width: u32, height: u32, luma: &[u8]) ->
    Vec<String>`: returns `vec![]` when `luma.len() != w*h` or a side is 0; uses
    `rqrr::PreparedImage::prepare_from_greyscale(w as usize, h as usize, |x, y| luma[y * w + x])`,
    `detect_grids()`, `decode()` each, keeps UTF-8 contents, dedups, order of detection. Callers
    downscale to `MAX_SIDE` first (doc comment says so).
  - `#[derive(Debug, PartialEq)] pub enum Scanned { Cards(Vec<ContactDraft>), Number(String),
    Email(String), Nothing }` and `pub fn parse_payload(text: &str) -> Scanned`: `BEGIN:VCARD` ->
    `vcard::parse` (cards with at least a name, number or email); `MECARD:` -> new private
    `parse_mecard` (`N:Last,First;TEL:..;EMAIL:..;ADR:..;NOTE:..;URL:..;BDAY:yyyymmdd;;`, backslash
    escapes `\;` `\:` `\,` `\\`); `tel:`/`TEL:` and a bare number accepted by `import_plan::number_key`
    -> `Number` (raw, trimmed, `tel:` stripped); `mailto:` or a single address with one `@` -> `Email`;
    anything else -> `Nothing`. Input clipped to 8 KiB.
  - `pub fn scan_notice(s: &Scanned) -> &'static str` ("No contact in this picture" for `Nothing`).
- Tests: QR round trip: encode `number_format::contact_qr(..).payload` and a 2-card vCard with
  `qrcode`, render 4 px modules + 4-module quiet zone into a luma buffer, `decode_luma` returns the
  payload; wrong buffer length -> empty; blank image -> empty; MECARD with escapes and BDAY; `tel:+90 532`
  -> Number; `mailto:a@b.c`; random URL -> Nothing; vCard without any field -> Nothing.
- Acceptance: `cargo test -p rovsti_core contact_scan` green; aarch64 checks still pass (rqrr is pure).

### S3. Pure: card history, cleanup filters, group recipients (C21, C24, C25)
- History: no new matcher; `recents::history(entries, &[], numbers, limit)` + `history_text` are the
  logic. Add `crates/rovsti_core/src/contact_history.rs` with only `pub const CARD_LIMIT: usize = 5;
  pub const PULL_LIMIT: usize = 500;` and `pub fn summary(lines: &[HistoryLine]) -> Option<String>`
  ("3 calls in the last 30 days"-style header: count + "last call 2 days ago" via
  `contacts_view::recent_when`), `pub fn permission_text() -> (&'static str, &'static str)` (title
  "Show calls with this contact?", body "Rovsti Contacts reads your call log on this phone only.").
- New `crates/rovsti_core/src/contact_cleanup.rs`:
  `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum Cleanup { NoNumber, NoName, NoPhoto, NoGroup }`
  with `pub const ALL: [Cleanup; 4]`, `pub fn label(self) -> &'static str` ("No number", "No name",
  "No photo", "Not in a group"); `pub fn matches(d: &ContactDraft, has_photo: bool, c: Cleanup) -> bool`
  (NoName = `display_name()` falls back to a number/email or is empty: reuse the existing fallback rule
  in `contact_edit`); `pub fn ids(drafts: &[ContactDraft], photo_ids: &HashSet<i64>, c: Cleanup) ->
  Vec<i64>` (drafts with `id`, order of `drafts`); `pub fn counts(..) -> [usize; 4]`;
  `pub fn empty_text(c) -> String` ("Every contact has a number").
- New `crates/rovsti_core/src/group_tools.rs`:
  `pub const MAX_SMS: usize = 30; pub const MAX_EMAIL: usize = 50;`
  `pub fn sms_recipients(members: &[ContactDraft]) -> (Vec<String>, usize /* without a number */)`:
  per member the default number (S4 `primary`, else first mobile, else first), deduped by
  `import_plan::number_key`, capped at `MAX_SMS`; `pub fn email_recipients(members) -> (Vec<String>,
  usize)` likewise; `pub fn sms_address(numbers: &[String]) -> String` (";"-joined, trimmed) and
  `pub fn email_address(addrs) -> String` (","-joined); `pub fn recipients_notice(kind, used,
  missing, capped: bool) -> Option<String>` ("2 people have no number", "Only the first 30 are
  added"); `pub fn group_file_name(title: &str, today) -> String` ("Family.vcf", unsafe chars -> `_`,
  empty -> `vcard::export_name`).
- Because `sms_recipients` uses `primary`, land S4's `DraftField.primary` first or write the fallback
  as "first mobile, else first" now and switch to `primary` in S4 (state which in the commit).
- Tests: summary empty/one/many; cleanup each filter, ids skip `id: None`, counts; recipients dedup
  by number key, cap flag, missing counts; address joins; file name sanitising.
- Acceptance: `cargo test -p rovsti_core contact_history contact_cleanup group_tools` green.

### S4. Pure: default number/email and birthday reminder plan (C22, C23)
- `contact_edit.rs` `DraftField`: `#[serde(default, skip_serializing_if = "std::ops::Not::not")] pub
  primary: bool`. `DraftField::new` sets false. `clean()` keeps at most one `primary` per list (the
  first). `pub fn set_primary(fields: &mut [DraftField], index: Option<usize>)` (None clears).
  `pub fn primary_of(fields: &[DraftField]) -> Option<usize>`. Fix every struct literal the compiler
  flags in the workspace.
- `vcard.rs`: parse `TYPE=pref` (3.0, also inside a comma list) and `PREF=1` (4.0) into `primary`;
  write `TYPE=...,pref` for a primary TEL/EMAIL. `contact_dupes::merge`: keeper's primary wins, a
  merged-in primary is dropped when the keeper already has one. `group_tools::sms_recipients` now uses
  `primary` (if S3 did the fallback).
- Field sheet actions: `pub fn default_action(is_primary: bool, count: usize) -> Option<(&'static str,
  bool)>` ("Set as default" / "Clear default", None when the list has one entry).
- New `crates/rovsti_core/src/birthday_reminder.rs`:
  `pub const DEFAULT_HOUR: u8 = 9; pub const HORIZON_DAYS: u16 = 60;`
  `#[derive(Clone, Debug, PartialEq, Serialize)] pub struct BirthdayAlarm { pub id: i64, pub contact_id:
  i64, pub at_ms: i64, pub title: String, pub body: String }`;
  `pub fn plan(upcoming: &[contact_dates::Upcoming], now_ms: i64, tz_offset_s: i32, hour: u8) ->
  Vec<BirthdayAlarm>`: one per upcoming birthday or anniversary within `HORIZON_DAYS` whose local
  `hour:00` is still in the future (today's after `hour` is skipped), `id` stable = hash of
  `(contact_id, kind, month, day, year)` folded to a positive i64 (no `std` RandomState: use a fixed
  FNV-1a), title "Ayse's birthday" / "Ayse and Can's anniversary"-style from `Upcoming` fields
  ("Ayse turns 31 today" when the year is known); `pub fn diff(armed: &[i64], wanted:
  &[BirthdayAlarm]) -> (Vec<i64> /* disarm */, Vec<&BirthdayAlarm> /* arm */)`.
  Use the real field names of `contact_dates::Upcoming` (read it first; do not invent).
- `contacts_prefs.rs` `ContactsPrefs`: `#[serde(default)] pub birthday_reminders: bool`,
  `#[serde(default = "default_hour")] pub reminder_hour: u8` (clamped 0..=23 on read). Old JSON
  parses; keep `MAX_FILE_BYTES` sufficient.
- Tests: one primary kept by `clean`; set/clear; vCard 3.0 and 4.0 pref round trip; merge rule;
  plan skips past hour today, includes tomorrow, respects horizon and tz offset, stable ids across
  calls, distinct ids for birthday vs anniversary; diff; prefs default and old JSON.
- Acceptance: `cargo test -p rovsti_core` green; workspace compiles.

### S5. Android glue and backend wrappers (C19-C25)
- Default flag: Kotlin `ContactsEdit.kt` `DraftBuilder.add` reads `Data.IS_SUPER_PRIMARY` (Phone and
  Email rows) into `"primary": true`; `insertData` writes `Data.IS_PRIMARY = 1` and
  `Data.IS_SUPER_PRIMARY = 1` for a primary field (the update path deletes and re-inserts rows, so
  this is what keeps the default on save).
- Call history: Contacts manifest gains `android.permission.READ_CALL_LOG` only. `permissions.rs`
  `Group::CallLog` (key `"call_log"`, not in `Group::ALL`) and the Kotlin map entry
  `"call_log" to listOf(READ_CALL_LOG)` in `RovstiBridge.kt`. History reuses `providers::call_log`.
- Group SMS / e-mail: `RovstiBridge.kt` `composeGroupSms(numbers: String, body: String)` building
  `Uri.parse("smsto:" + Uri.encode(numbers, ";"))` and `emailGroup(addresses: String)` using
  `ACTION_SENDTO mailto:` with `Intent.EXTRA_EMAIL` array; Rust `telecom::{compose_group_sms(numbers:
  &[String], body) -> Result<bool>, email_group(addresses: &[String]) -> Result<bool>}` (Unsupported on
  desktop, `false` when no app handles it).
- Scan image: `documents::pick_scan_image() -> Result<bool>` reusing the photo picker in
  `Documents.kt` with `document_result` op `scan_image` (same `path`/`mime` reply as `contact_photo`).
- Birthday reminders: new `crates/rovsti_android/kotlin/dev/rovsti/bridge/BirthdayReminders.kt` built
  on `ReminderStore<T>` (like `MessageReminders.kt`): item `{id, contact_id, at_ms, title, body}`,
  channel `"birthdays"` ("Birthdays"), `setAndAllowWhileIdle`, tap = launch intent with
  `EXTRA_CONTACT_ID`; `BirthdayReminderReceiver` (not exported). `RovstiBridge`: `armBirthday(json)`,
  `disarmBirthday(id)`, `armedBirthdays()`, `rearmBirthdays()`, and `deliverOpenContact(intent)`
  posting one-shot `open_contact {id}` through the mailbox; call it from Contacts `MainActivity.kt`
  `onCreate`/`onNewIntent` (as Messages calls `deliverOpenThread`). Contacts manifest gains
  `POST_NOTIFICATIONS` and the receiver. Rust `reminders::{arm_birthday, disarm_birthday,
  armed_birthdays, rearm_birthdays}`. Keep `proguard-rovsti.pro` rules in step if it lists bridge
  classes.
- `apps/contacts/src/backend.rs`: `trash_load(dir) / trash_save(dir, &Trash)` in a new
  `apps/contacts/src/trash_store.rs` (same atomic temp-file + rename as `prefs.rs`, file
  `contacts-trash.json`, size-guarded by `MAX_FILE_BYTES`, damaged file -> empty trash + log warn),
  `drafts_of(ids) -> Result<Vec<ContactDraft>, String>` (`pull_full` filtered by `pick_drafts`),
  `call_history(numbers) -> Result<Vec<HistoryLine>, String>` (`providers::call_log(PULL_LIMIT)` +
  `recents::history`), `set_default(draft, list, index)` = edit + `save`, `scan_image(path) ->
  Result<Scanned, String>` (decode with `image`, `to_luma8`, downscale to `contact_scan::MAX_SIDE`,
  `decode_luma`, first payload that parses to something), `group_sms`, `group_email`,
  `sync_birthdays(prefs, upcoming, now, tz) -> Result<(), String>` (`plan` + `diff` against
  `armed_birthdays`; disarm all when off).
- Tests (desktop): trash_store round trip, damaged / oversized file, missing file; `scan_image` on a
  PNG written by the test from a `qrcode` bitmap returns the cards; wrappers return the Unsupported
  words; sample still one duplicate group. Give one sample contact a primary number.
- Acceptance: all checks green incl. the three aarch64 checks.

### S6. UI: Trash and Scan to add (C20, C19)
- Delete to Trash: `confirm.rs::commit_pending_delete` first fetches the drafts (`drafts_of(ids)`,
  background), appends them with `Trash::push` and saves, then deletes; if the trash write fails the
  delete still happens only after a second confirm ("Can't keep a copy in Trash. Delete anyway?").
  Pills use `moved_notice`. Merge (`dupes.rs`) deletes are not trashed (Undo merge covers them).
  `purge_expired` runs on launch and when the Trash page opens.
- Trash page (new `apps/contacts/src/trash.rs`, pattern of `recent.rs`/`upcoming.rs`): Settings row
  "Trash" with the count under "Contacts file"; rows show name + `left_text`; tap -> small sheet
  Restore / Delete forever (confirm `forever_confirm`); header "Select" reuses the
  `multi_select::Selection` + action bar pattern from `select.rs` (Restore, Delete forever); "Empty
  Trash" (destructive confirm `empty_confirm`). Restore = `restore_drafts` + `add_contacts`, pill
  `restored_notice`, reload; on failure the entries go back into the trash.
- Scan to add: Settings row "Scan QR code" under "Contacts file" and an item in the Contacts header
  add menu if one exists (else only Settings): `pick_scan_image`; `document_result` op `scan_image` in
  `transfer.rs` -> `backend::scan_image` on the background executor -> `Cards(one)` opens the editor
  prefilled (new contact, not saved until Save), `Cards(many)` goes through the existing import
  preview/plan flow, `Number`/`Email` opens the editor with that one field, `Nothing` -> pill
  `scan_notice`. Busy flag like `transfer_busy`.
- Tests: pure `trash_actions(n_selected) -> Vec<TrashAction>`; `Confirm` texts for EmptyTrash /
  DeleteForever; `scanned_route(&Scanned) -> ScanRoute { Editor, Import, Notice }`.
- Acceptance: all checks green; desktop: delete a sample contact, it shows in Trash, Restore re-adds
  it via the desktop sample path (or shows the Unsupported pill on desktop, then the entry stays).

### S7. UI: card history, default number/email, birthday reminders (C21, C22, C23)
- Card (`detail.rs`): "Recent calls" section below the numbers when the contact has a number. Without
  `Group::CallLog` show one glass row "Show recent calls" -> `permission_text` confirm ->
  `permissions::request(Group::CallLog)`; with it, load `call_history` once per card open (background,
  cancel on close), show up to `CARD_LIMIT` lines with `history_text` (icon by direction), the
  `summary` header, tap a line calls that number (existing call path), "Show all" opens the Phone app
  history only if a handoff exists, else omitted. Denied -> pill "Call log permission is off" with
  Settings button (`open_app_details`).
- Default number/email: field sheet (`field.rs`) gains `FieldAction::SetDefault(bool)` from
  `default_action`; applying it edits the card's draft with `set_primary` and saves on the
  background executor, pill "Default number set" / "Default cleared"; the row shows a small
  "Default" tag. Editor keeps `primary` untouched when editing other fields (round-trips the flag).
  Extend the `actions` test.
- Birthday reminders: Settings section "Birthdays": toggle "Remind me on the day" and a "Time" row
  (choice rows 7:00 / 8:00 / 9:00 / 10:00 / 12:00 using `choice_row`); turning on requests
  `Group::Notifications` first (denied -> stays off, pill). Persist in `ContactsPrefs`; call
  `sync_birthdays` after each pull that changes `upcoming`, on toggle, and at launch (plus
  `rearm_birthdays`). Handle the `open_contact {id}` one-shot event in `main.rs` event loop: open the
  card (or `pending_open` if the book is not loaded yet).
- Tests: pure `history_rows(lines, now, locale) -> Vec<(String, String)>` mapping;
  `field::actions` includes Set/Clear default only with 2+ entries; `open_contact` JSON parse helper.
- Acceptance: all checks green.

### S8. UI: Clean up page and group utilities; docs and backlog (C24, C25)
- Clean up: Settings row "Clean up" next to "Find duplicates" opens a page (new
  `apps/contacts/src/cleanup.rs`, rows from `pull_full` + photo ids from the loaded `WireContact`s,
  built once per pull) with a segmented chip row of `Cleanup::ALL` labels + counts; the list uses the
  normal contact rows (tap opens the card); "Select all" + Delete reuses select mode actions and the
  Trash path (confirm via `delete_confirm`, pill `moved_notice`); empty state `empty_text`.
- Groups (`groups.rs` group page header menu): "Message group" (`group_tools::sms_recipients` ->
  `telecom::compose_group_sms`, pill `recipients_notice` when some are missing / capped, "No one in
  this group has a number" when empty), "Email group" (likewise), "Share as .vcf" (`share_vcf(
  group_file_name, vcard::write(members))`), "Save to file" (`start_export_named`). Work off the UI
  thread; failures show the Android words.
- Docs: `docs/roadmap.md` Rovsti Contacts section: "Utilities, round 3 (C19-C25, done without a
  device)" with one line per utility; `docs/architecture.md`: the local trash file, the rqrr
  dependency, Contacts' READ_CALL_LOG / POST_NOTIFICATIONS and the `open_contact` one-shot. Update
  this file's backlog statuses to `done R3` for what landed.
- Tests: pure `group_menu(n_members, has_numbers, has_emails) -> Vec<GroupAction>`;
  `cleanup_chip_text(label, count)`.
- Acceptance: all checks green.
