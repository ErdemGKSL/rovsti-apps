# util-contacts: everyday utilities for Rovsti Contacts (round 6)

baseSha: 2595a7856ce9d37484134c37b9e776b55e80e914

## Audit (what exists at baseSha)

Rounds 1-5 (`util-contacts.md`, `-r2.md` .. `-r5.md`) are fully landed: C1-C34, C36, C38-C44 are done
(last commit 2595a78 "Docs, backlog and review fixes (C33, C38-C44)", ticked in `docs/roadmap.md`
"Utilities, round 5"). Clean tree, no stash. Disk: about 2.7 GB free at baseSha, so run
`rm -rf target/debug/incremental` before the first build of every step (more if `df` stays under 6 GB:
`cargo clean -p rovsti_contacts` style per-package cleans are fine).

Facts the R6 steps build on (checked at baseSha, do not re-create):
- "Recently added" (`apps/contacts/src/recent.rs`, `contacts_view::recent`) is really "changed in the last
  30 days": it sorts on `WireContact::updated_ms` (`CONTACT_LAST_UPDATED_TIMESTAMP`). Android has no
  creation time, so "added" needs a local mark (S1).
- `ContactsPrefs` (`contacts_prefs.rs`, `MAX_FILE_BYTES` 64 KiB, `#[serde(default)]` fields, `Default` /
  `from_style` literals) holds `favorites_order`, `my_card`, `emergency` (lookup keys).
- `ContactDraft` (contact_edit.rs l. 164): given, family, org, nickname, title, note, numbers, emails,
  addresses (`DraftField`), websites, events (`DraftEvent`), groups, starred, account. No phonetic name,
  no relations. Kotlin `ContactsEdit.kt`: `update` deletes `EDITED_MIMES` rows then `insertData`;
  `create`/`appendNew` return the raw-contact op index; reads in the `add(c, base)` / `toJson` block
  (~l. 189-240); `importJson` (l. 506) returns only `{added}` (`ImportPayload { added: u32 }`).
- vCard: `vcard::{parse, write, write_one, photos, CardPhoto, MAX_PHOTO_BYTES}`; CSV:
  `contact_csv::{write, parse}`; search: `contact_find::{DeepField, DeepIndex}`.
- Merge: `contact_dupes::merge(keeper, others) -> ContactDraft`; number identity:
  `import_plan::number_key`. Undo of an edit: `NoticeAction::UndoEdit` + `self.edit_undo`
  (`contact_edit::undo_target`, `change_summary`, `saved_notice`).
- Select mode: `SelectAction::Share` (select.rs `share_selection`) always shares a `.vcf`;
  `contact_select::full_text(&ContactDraft)` exists (copy contact as text).
- No-results offer: main.rs ~l. 1777 `contact_edit::draft_from_query(query)` -> "Create Contact" pill.
- Import: `transfer.rs::plan_import` (one card with photo -> editor; else `import_plan::plan` ->
  `Confirm::Import { cards, duplicates, unreadable }` -> Kotlin `import`). Photos on many cards are dropped.
  `backend::photo_file(&CardPhoto)`, `backend::change_photo_with(id, path, CropView::default())`.
- Dates: `contact_dates::{age_on, next_occurrence, upcoming, EventDate}`; Upcoming page `upcoming.rs`.
- Files: `rovsti_android::documents::export_file(name, mime, path)` (stream copy; answers op
  `export_messages` = `DocumentOp::ExportMessages`, which Contacts does not handle yet);
  `transfer.rs::document_op_notice`. Text share: `rovsti_android::telecom::share_text`.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| C1-C10 | Photo viewer, save/share photo, change/remove, share as text/vcf/QR, dates, addresses, vCard fields, duplicates+merge, upcoming birthdays, scrubber | done R1 |
| C11-C18 | Select mode, export one, crop/rotate, camera, recently added, copy contact / vCard, WhatsApp per number, websites/nickname/title | done R2 |
| C19-C25 | Scan to add, Trash, card call history, birthday reminders, default number/email, Clean up, group utilities | done R3 |
| C26-C32, C34, C36 | Favorites order, smarter search, paste to add, summary, quick actions, open shared .vcf, copy number as, ringtone/voicemail, Home screen shortcut | done R4 |
| C33, C38-C44 | vCard PHOTO, CSV, undo last edit, search in details, frequently contacted, My card, emergency contacts, country on the card | done R5 |
| C49 | Add to an existing contact: a number / email from a no-results search, paste or scan goes into a picked contact (Undo) | done R6 |
| C45 | Recently added vs Recently edited: segmented "Added / Edited" page from a local first-seen mark | done R6 |
| C51 | Recently viewed: last 8 opened cards above the list while the search is empty; Clear (confirm) | done R6 |
| C47 | Share selected contacts as plain text (select mode Share: ".vcf file" / "Text") | done R6 |
| C46 | Phonetic name and Relationship fields (card, editor, vCard, CSV, search); a relation's name opens that contact | done R6 |
| C48 | Multi-card import keeps photos (Kotlin import returns ids, photos set afterwards) | done R6 |
| C50 | Birthdays and anniversaries to a calendar file (`.ics`, yearly all-day events) from Upcoming | done R6 |
| C52 | Age / years together on the card's date rows ("12 May 1990 · 36 years") if not already shown | backlog R7 (check first) |
| C53 | Copy every number / email of the selection (select mode "Copy") | backlog R7 |
| C54 | Contacts without a number / without a name filters in Clean up | backlog R7 (check `contact_cleanup` first) |
| C35 | Block / unblock a number from the card (hand-off to Rovsti Phone, which holds the dialer role) | deferred (Phone change) |
| C37 | Last SMS with this contact on the card | dropped (READ_SMS belongs to Messages) |

## Rules for every step

All checks green after each step: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
One focused commit per step, prefix `Rovsti contacts: `. Do not change Phone / Messages behaviour (shared
`rovsti_core` / `rovsti_android` changes keep them compiling: fix struct literals only; new `ContactDraft`
fields are `#[serde(skip_serializing_if = ...)]` + default so old JSON parses). Read
`.claude/skills/liquid-glass-gpui/SKILL.md` before S6-S7; use only GPUI APIs already used in this repo or
grepped in `~/.cargo/git/checkouts/gpui-fusion-*/d6c8412/crates/gpui`. Every action ends in a pill
(`self.notice`); destructive ones (Clear recently viewed) go through `confirm.rs::Confirm`; Add to existing
is undoable (`NoticeAction::UndoEdit`) and needs no confirm. Blocking work (pull_full, file writes, photo
encode, Kotlin calls) runs on the background executor.

## Steps (round 6)

### S1. Pure: recently added / edited and recently viewed (C45, C51)
- New `crates/rovsti_core/src/contact_recent.rs` (+ `pub mod` in lib.rs, module doc):
  `pub const ADDED_KEEP: usize = 50; pub const VIEWED_KEEP: usize = 8; pub const WINDOW_DAYS: u16 = 30;`
  `#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)] pub struct AddedMark { pub id:
  i64, pub ms: i64 }`.
  `pub fn note_pull(known_max_id: Option<i64>, marks: &[AddedMark], ids: &[i64], now_ms: i64) ->
  (i64, Vec<AddedMark>)`: first pull (`None`) -> (max id, no marks); later pulls append ids above the
  known max with `ms = now_ms`, drop marks older than `WINDOW_DAYS` and ids no longer in `ids`, keep the
  newest `ADDED_KEEP`, return the new max (`known_max_id` never decreases; empty book keeps it).
  `pub fn note_created(marks: &[AddedMark], id: i64, now_ms: i64) -> Vec<AddedMark>` (in-app create; dedup).
  `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum RecentTab { Added, Edited }` with `label()`.
  `pub fn added<'a>(contacts: &'a [WireContact], marks: &[AddedMark]) -> Vec<(&'a WireContact, i64)>`
  (newest mark first); `pub fn edited<'a>(contacts, marks, now_ms, within_days, max) -> Vec<&'a
  WireContact>` = `contacts_view::recent` minus ids in `marks` whose `updated_ms` is within 60 s of the
  mark (edited after adding still counts as edited).
  `pub fn entry_title(added: usize, edited: usize) -> (&'static str, String)` -> ("Recently added",
  "3") / ("Recently edited", "5") / ("Recent", "3 added · 5 edited"); `pub fn show_entry(query, added,
  edited) -> bool`.
  `pub fn note_viewed(keys: &[String], key: &str) -> Vec<String>` (move to front, dedup, cap
  `VIEWED_KEEP`, empty key ignored); `pub fn viewed<'a>(keys: &[String], all: &'a [WireContact]) ->
  Vec<&'a WireContact>` (key order, missing dropped, skips `hidden` ids passed as `&[i64]`);
  `pub fn forget_viewed(keys, key) -> Vec<String>`; `pub const CLEAR_VIEWED: (&str, &str) =
  ("Clear recently viewed?", "The contacts stay; only this list is cleared.")`.
- `contacts_prefs.rs`: `#[serde(default)] pub known_max_id: Option<i64>`, `#[serde(default)] pub added:
  Vec<AddedMark>`, `#[serde(default)] pub viewed: Vec<String>`; fix `Default` / `from_style`.
- Tests: first pull seeds, later pull marks new ids, deleted ids drop, window expiry, cap, max never goes
  down, `note_created` dedup; added/edited split incl. edited-after-added; entry titles; viewed
  move-to-front, cap, missing keys, hidden ids; prefs old JSON (r5 shape) still parses.
- Acceptance: `cargo test -p rovsti_core contact_recent contacts_prefs` green; workspace compiles.

### S2. Pure: add to existing contact, share selection as text (C49, C47)
- `contact_edit.rs`:
  `#[derive(Clone, Debug, PartialEq, Eq, Default)] pub struct Addition { pub numbers: Vec<String>, pub
  emails: Vec<String> }` with `pub fn from_query(query: &str) -> Option<Self>` (reuse
  `draft_from_query`: only its numbers / emails; a pure name gives `None`) and `pub fn from_draft(d:
  &ContactDraft) -> Option<Self>` (scan / paste of a card: its numbers and emails).
  `#[derive(Debug, PartialEq, Eq, thiserror::Error)] pub enum AddError { #[error("{0} already has this")]
  AlreadyThere(String), #[error("Nothing to add")] Empty }`.
  `pub fn add_to_existing(target: &ContactDraft, add: &Addition) -> Result<(ContactDraft, usize),
  AddError>`: appends numbers not equal by `import_plan::number_key` (kind Mobile, no primary change) and
  emails not equal case-insensitively (kind Home); returns the new draft and how many were added.
  `pub fn added_notice(name: &str, add: &Addition, n: usize) -> String` ("Number added to Ayşe Yılmaz",
  "2 details added to ...").
- `contact_select.rs`: `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum ShareFormat { Vcf, Text }`
  with `label()` (".vcf file" / "Text") and `pub const SHARE_FORMATS: [ShareFormat; 2]`;
  `pub fn share_text(drafts: &[ContactDraft]) -> String` (each `full_text`, joined by a blank line, at
  most `MAX_TEXT_CONTACTS = 100`, then "… and 12 more"); `pub fn shared_text_notice(n) -> String`.
- Tests: from_query number / email / name; add new number, same number in another format ->
  AlreadyThere, email case, mixed, empty; notices singular/plural with Turkish names; share_text order,
  blank-line join, cap line.
- Acceptance: `cargo test -p rovsti_core contact_edit contact_select` green.

### S3. Pure: phonetic name, relations, calendar file, import photos (C46, C50, C48)
- `contact_edit.rs` `ContactDraft` gains `#[serde(skip_serializing_if = "String::is_empty")] pub
  phonetic_given: String`, `pub phonetic_family: String`, and `#[serde(skip_serializing_if =
  "Vec::is_empty")] pub relations: Vec<DraftRelation>`;
  `#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)] #[serde(rename_all =
  "snake_case")] pub enum RelationKind { Spouse, Partner, Child, Mother, Father, Parent, Brother, Sister,
  Relative, Friend, Manager, Assistant, #[default] Custom }` with `title()`;
  `pub struct DraftRelation { pub name: String, pub kind: RelationKind, pub label: String }` (+ `title()`:
  custom label or kind title). `clean()` trims and drops empty relations; `display_name` unchanged.
  Fix every `ContactDraft { .. }` literal in the workspace (grep).
  `pub fn relation_match<'a>(name: &str, all: &'a [WireContact]) -> Option<&'a WireContact>` (unique
  `search::fold` equality on display name; ambiguous -> None).
- `vcard.rs`: read `X-PHONETIC-FIRST-NAME`, `X-PHONETIC-LAST-NAME` (also 4.0 `SORT-AS` is NOT used),
  `RELATED;TYPE=<kind>[;VALUE=text]:<name>` (uri values skipped) and Apple `itemN.X-ABRELATEDNAMES` with
  its `itemN.X-ABLabel` (`_$!<Spouse>!$_` form -> kind); write the X-PHONETIC lines and
  `RELATED;TYPE=<kind>;VALUE=text:<name>` (a custom relation writes its label as `TYPE="<label>"`, read
  back as Custom with that label). `contact_csv.rs`: Google
  `Phonetic First Name`, `Phonetic Last Name`, `Relation N - Label`, `Relation N - Value` both ways.
  `contact_find.rs`: `DeepField::PhoneticName` and `DeepField::Relation` (after Nickname / before Note).
- New `crates/rovsti_core/src/contact_ics.rs`: `pub struct IcsEvent { pub uid: String, pub summary:
  String, pub md: (u8, u8), pub year: Option<i32> }`; `pub fn events_of(drafts: &[ContactDraft]) ->
  Vec<IcsEvent>` (birthdays "Ayşe Yılmaz's birthday", anniversaries "... anniversary", other kinds by
  title; uid `rovsti-<id>-<kind>-<mmdd>@contacts.local`; dates via `contact_dates` parse, unparsable
  skipped); `pub fn write(events: &[IcsEvent], stamp_ms: i64) -> String` (RFC 5545: CRLF, 75-octet
  folding on char boundaries, TEXT escaping of `,;\` and newlines, `VERSION:2.0`, `PRODID:-//Rovsti//
  Contacts//EN`, all-day `DTSTART;VALUE=DATE:` (year from the card, else 1972 a leap year so 29 Feb
  works), `RRULE:FREQ=YEARLY` (29 Feb: `RRULE:FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1`),
  `TRANSP:TRANSPARENT`, `DTSTAMP` from `stamp_ms`); `pub fn file_name(n, today) -> String`
  (`rovsti-birthdays-<n>-YYYY-MM-DD.ics`); `pub fn saved_notice(n) -> String`.
- Import photos: `contact_edit::ImportPayload` gains `#[serde(default)] pub ids: Vec<i64>` (aligned with
  the sent drafts, 0 for a failed one; old `{added}` JSON still parses). `import_plan.rs`: `pub fn
  plan_with_photos(existing, cards: Vec<ContactDraft>, photos: Vec<Option<CardPhoto>>) -> (Plan,
  Vec<Option<CardPhoto>>)` (photos follow the kept cards; shorter photo list padded with None) and `pub
  const MAX_IMPORT_PHOTOS: usize = 200; pub fn photo_targets<'a>(ids: &[i64], photos: &'a
  [Option<CardPhoto>]) -> Vec<(i64, &'a CardPhoto)>` (skip id 0, cap).
- Tests: draft JSON round trip with and without the new fields (old JSON parses); vCard 3/4 phonetic +
  RELATED round trip, Apple X-ABRELATEDNAMES with label, uri RELATED skipped; CSV relation columns; deep
  search finds a phonetic name and a relation; relation_match unique / ambiguous; ICS golden text for a
  birthday with year, without year, 29 Feb, escaping, folding of a long Turkish summary; plan_with_photos
  alignment with duplicates removed; photo_targets skips 0 and caps; ImportPayload old JSON.
- Acceptance: `cargo test -p rovsti_core` green; workspace compiles (Phone / Messages literals fixed).

### S4. Android glue and backend wrappers (C46, C48, C50, C45)
- `crates/rovsti_android/kotlin/dev/rovsti/bridge/ContactsEdit.kt`:
  read `StructuredName.PHONETIC_GIVEN_NAME` / `PHONETIC_FAMILY_NAME` into `phonetic_given` /
  `phonetic_family` and `Relation` rows (`NAME`, `TYPE`, `LABEL`) into `relations` (`kindOfRelation`
  / `typeOfRelation` pairs like `kindOfEvent`) in the detail / full JSON; `insertData` writes the phonetic
  values on the StructuredName insert and one Relation row each; add `Relation.CONTENT_ITEM_TYPE` to
  `EDITED_MIMES`. `importJson`: record each draft's raw-contact op index from `appendNew`, map
  `results[i].uri` -> raw id -> `contactIdOfRaw`, answer `{added, ids}` (0 when unknown); keep chunking.
- `apps/contacts/src/backend.rs`:
  `pub(crate) fn import_with_photos(cards: &[ContactDraft], photos: &[Option<CardPhoto>]) -> Result<(u32,
  usize /*photos set*/), String>`: existing import call, then for each `photo_targets` entry `photo_file`
  + `change_photo_with(id, &path, CropView::default())`, failures counted not fatal, temp files removed.
  `cards_of` / plan path keeps `vcard::photos(text)` (CSV: all None).
  `pub(crate) fn export_ics(name: &str, text: &str) -> Result<(), String>`: write `<temp_dir>/<name>` and
  `documents::export_file(name, "text/calendar", path)`.
  `pub(crate) fn add_to_existing(id: i64, add: &Addition) -> Result<(ContactDraft /*before*/, String
  /*notice*/), String>` (detail pull -> `contact_edit::add_to_existing` -> `save`).
- `prefs.rs` / book reload: call `contact_recent::note_pull` after each successful pull and
  `note_created` after an in-app create; save prefs only when they changed (background).
- Tests (desktop): `import_with_photos` on desktop returns Unsupported-style words without panicking;
  `export_ics` desktop error words; sample-based `add_to_existing` pure path via a helper.
- Acceptance: all checks green incl. the three aarch64 checks.

### S5. UI: Add to existing, share selection as text, Recently viewed (C49, C47, C51)
- No-results offer (main.rs ~l. 1777): when `Addition::from_query` is `Some`, show a second glass pill
  "Add to a Contact" next to "Create Contact"; it opens the existing single-choice `picker.rs` (title "Add
  to which contact?"); on pick run `backend::add_to_existing` in the background; success -> reload, pill
  `added_notice` with `NoticeAction::UndoEdit` (set `edit_undo = Some(before)`); `AlreadyThere` /
  failure -> pill with the words. Same option in the paste-to-add / scan route when the intake is a lone
  number or email (`intake.rs`: an "Add to a Contact" choice beside opening the editor, only where that
  route already shows a choice; do not add a new sheet otherwise).
- Select mode Share: a small sheet (pattern of the existing group sheet in `select.rs`) with
  `SHARE_FORMATS`; Vcf keeps today's path; Text -> `pull_full` + `pick_drafts` + `share_text` ->
  `telecom::share_text` on the background executor; pill `shared_text_notice` / error words; leaves
  select mode like the vcf path.
- Recently viewed: `open_detail` (or the card open path) calls `note_viewed` with the lookup key and saves
  prefs (background, only on change). Contacts tab, search empty: a "Recently viewed" section (up to 8
  compact rows with avatar + name, below My card, above Recently added) with a "Clear" text button ->
  `Confirm::ClearViewed` (`CLEAR_VIEWED`) -> pill "Recently viewed cleared". Hidden when empty.
  Trash / delete calls `forget_viewed`.
- Tests: pill text helpers; `share_sheet_rows()` lists both formats; viewed section hidden when empty or
  while searching; `Confirm::ClearViewed` texts; `keeps_undo` for the add-to-existing notice.
- Acceptance: all checks green.

### S6. UI: Recently added / edited page, phonetic name and relations (C45, C46)
- `recent.rs`: entry row uses `contact_recent::entry_title`; the page gets a glass segmented control
  (existing component in `rovsti_ui::components`, grep `segmented`) "Added" / "Edited", rows from
  `added` / `edited` with `recent_when`; empty state per tab ("No contacts added in the last 30 days"); the
  chosen tab is kept for the session.
- Editor (`editor.rs`): "Phonetic name" fields (given, family) under the name, shown when non-empty or
  after "Add field ▸ Phonetic name"; a "Relationship" list like websites (name + kind chooser using
  `RelationKind::title`, custom label). Card (`detail.rs`): "Phonetic name" row (long-press copy like
  other fields) and one row per relation "Spouse · Mehmet Yılmaz"; tap opens the matched contact
  (`relation_match`) or, if none, the search with that name; copy on long press with the existing pill.
- Tests: tab rows mapping and empty texts; relation row subtitle and tap target; editor field list
  includes phonetic / relations only when set.
- Acceptance: all checks green.

### S7. UI: calendar file from Upcoming, import keeps photos (C50, C48)
- Upcoming page (`upcoming.rs`) header: glass icon button (`LucideCalendarPlus` if in `gpui_icons`, else
  `LucideCalendar`; grep first) "Save to calendar file": background `pull_full` (drop hidden) ->
  `contact_ics::events_of` -> none -> pill "No birthdays or anniversaries to save."; else `write` ->
  `backend::export_ics(file_name(..))` -> pill "Choose where to save…"; `on_document_result` handles
  `DocumentOp::ExportMessages` as the calendar answer ("12 dates saved" / "Can't save the file.") via
  `document_op_notice`. The birthday row in Settings "Birthday reminders" section gets the same action as a
  row "Export birthdays (.ics)".
- Import (`transfer.rs::plan_import` / the `Confirm::Import` accept path): carry the aligned photos in
  `Confirm::Import { cards, photos, .. }` and call `import_with_photos`; the result pill becomes
  "12 contacts added · 5 photos" (photos part only when > 0); the confirm summary line notes "with 5 photos"
  when any.
- Tests: `document_op_notice` for ExportMessages; import summary with / without photos; the Upcoming
  export helper with no dates.
- Acceptance: all checks green.

### S8. Docs, backlog and review fixes (C45-C51)
- `docs/roadmap.md` Rovsti Contacts: "Utilities, round 6 (C45-C51, done without a device)" with one line
  per utility, plus a device-check line (import photos, .ics in Google Calendar, Relation rows written).
  `docs/architecture.md`: first-seen marks and viewed keys in `contacts-prefs.json`, Relation /
  phonetic in the edited mimes, import answers ids, `.ics` via `export_file`.
- Update this file's backlog statuses to `done R6`; keep C52-C54 as `backlog R7`.
- Self review of the R6 diff (`git diff 2595a78..HEAD -- apps/contacts crates/rovsti_core
  crates/rovsti_android`): no blocking work on the UI thread, every action has a pill, Confirm on Clear
  recently viewed, Undo on Add to existing, prefs stay under `MAX_FILE_BYTES`, no Phone/Messages
  behaviour change; fix in the same commit.
- Acceptance: all checks green.
