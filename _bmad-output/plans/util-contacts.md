# util-contacts: everyday utilities for Rovsti Contacts (round 1)

baseSha: c54dbd79008b930dc1e1996a6ba54c2fc5d27e26

## Audit (what already exists at baseSha)

Earlier plans `contacts.md`, `contacts-r2.md` and `ux-contacts.md` .. `ux-contacts-r5.md` are done.
No `util-contacts*.md` existed; no stash entries.

Already shipped, so not planned again:
- Contacts tab: search (`ContactIndex`), letter sections (`letter_header`), No Results with Create
  Contact prefilled, name format and sort order (first/last) persisted in `contacts-prefs.json`.
- Favorites tab with star toggle, + picker; Groups tab (create, rename, delete with confirm, members).
- Card (`apps/contacts/src/detail.rs`): Call, Message, Share (plain text via `telecom::share_text`),
  Email (email-only contacts), per-field sheet (long-press) with Copy / Share / Email / note copy, Edit,
  Open in Android Contacts, Delete with Undo pill (`PendingDelete`).
- Editor: names, org, numbers, emails, note, groups, Save to account, photo choose / remove / use the
  WhatsApp photo (confirm), discard confirm.
- vCard import (2.1/3.0/4.0, `rovsti_core::vcard::parse`, dedup by `import_plan`) and export of the
  whole book (`vcard::write`, vCard 3.0) from Settings.
- WhatsApp per contact (presence, chat/call handoff), shared WhatsApp settings.

Missing (backlog below): the 96 px card avatar cannot be opened (no viewer, no zoom, no save/share of
the photo), no export of one contact or a selection, no QR, no duplicate finder or merge, no
birthdays/anniversaries or postal addresses at all (`ContactDraft` has neither; Kotlin `ContactsEdit`
reads/writes only name, phone, email, org, note, group; `vcard` skips `BDAY`/`ADR`), no alphabet
scrubber, no select mode, no recently added list, no "open address in maps".

Reusable pieces already in the tree (do not re-create):
- `rovsti_core::zoom` (`fit`, `ZoomView::{pinch, pan, double_tap, settled, is_zoomed}`,
  `dismiss_progress`, `should_dismiss`). Phone's viewer `apps/phone/src/photo_viewer.rs` (1322 lines)
  and Messages' `media_viewer.rs` are app-local patterns to adapt (do not edit them).
- `rovsti_android::media::{save_image_to_gallery, share_image, share_file}` (Kotlin `MediaShare.kt`,
  `RovstiShareProvider`); Contacts already compiles `crates/rovsti_android/kotlin` (build.gradle line 81)
  but its manifest has **no** `RovstiShareProvider` entry (Phone's manifest lines 119-123 do).
- `rovsti_core::number_format::{contact_qr, qr_file_name}` and `rovsti_wa_ui::qr::qr_png(payload,
  min_px)` (Contacts already depends on `rovsti_wa_ui`).
- `rovsti_core::shared_card::vcf_file_name`, `rovsti_core::multi_select::Selection`,
  `rovsti_core::pending_delete`, `rovsti_core::search::fold`, `location_link::geo_url` (coordinates
  only; an address needs a `geo:0,0?q=` query), `cx.open_url` (used by Messages for `geo:` links).
- `rovsti_ui::haptics` for tick feedback.

GPUI facts at the pinned checkout (`~/.cargo/git/checkouts/gpui-fusion-*/d6c8412/crates/gpui`):
`Div::on_pinch` with `PinchEvent.delta` (relative step), mouse `click_count` for double-tap,
`ListState::scroll_to_reveal_item(ix)` (list.rs:669) and `ListState::scroll_to(ListOffset)`
(list.rs:652) for the scrubber. Use only these and APIs already used in `apps/contacts` or
`apps/phone/src/photo_viewer.rs`.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| C1 | Photo viewer: tap the card avatar, full screen, pinch / double-tap zoom, pan, swipe down to close, tap hides chrome | done R1 |
| C2 | Save photo to gallery and Share photo from the viewer | done R1 |
| C3 | Change / Remove photo from the viewer (Remove confirms, pill after) | done R1 |
| C4 | Share contact as: Text, vCard file (.vcf), QR code (QR sheet with Save / Share image) | done R1 |
| C5 | Birthdays and anniversaries: read, write, edit, show on the card with age ("turns 31 on 12 May") | done R1 |
| C6 | Postal addresses: read, write, edit, show; tap opens maps (`geo:0,0?q=`), long-press copy/share | done R1 |
| C7 | vCard 3/4 carries BDAY, ANNIVERSARY (and X-ANNIVERSARY), ADR on import and export | done R1 |
| C8 | Find duplicates (same name, shared number, shared email) and Merge with confirm and Undo | done R1 |
| C9 | Upcoming birthdays: section on Favorites (next 3) and a full page (next 365 days), "Wish" quick SMS | done R1 |
| C10 | Alphabet scrubber on the Contacts tab (drag letters, haptic tick, letter bubble) | done R1 |
| C11 | Select mode in the Contacts list: export selection (.vcf), share, add to group, star, delete (confirm + Undo) | backlog R2 |
| C12 | Export one contact as .vcf to a file (system "save as") from the card menu | backlog R2 |
| C13 | Photo crop / rotate before setting (user-moved square crop, quarter turns; extend `contact_photo::PhotoPlan`) | backlog R2 |
| C14 | Set photo from the camera (`ACTION_IMAGE_CAPTURE` into cache, Kotlin glue) | backlog R2 |
| C15 | Recently added / recently edited list (`CONTACT_LAST_UPDATED_TIMESTAMP` in `WireContact`) | backlog R2 |
| C16 | Copy whole contact (all fields as text) and Copy as vCard from the card menu | backlog R2 |
| C17 | One-tap WhatsApp message / call per number row (handoff exists; add row chips when the number is on WhatsApp) | backlog R2 |
| C18 | Websites and nicknames / job title fields (read, write, vCard URL/NICKNAME/TITLE), tap opens browser | backlog R2 |
| C19 | Scan to add: decode a contact QR from a picked image (pure Rust decoder, e.g. `rqrr`, checked for licence) and open the editor prefilled | backlog R3 |
| C20 | Trash: deleted contacts kept locally as drafts for 30 days with Restore and Empty Trash (confirm) | backlog R3 |
| C21 | Contact history: last calls and SMS with this contact (local call log / SMS providers, read-only, behind permission) | backlog R3 |
| C22 | Birthday reminders: local notification on the morning of (opt-in, `notify::show` / reminders glue) | backlog R3 |
| C23 | Default number: set / clear `IS_SUPER_PRIMARY` from the field sheet | backlog R3 |
| C24 | Contacts without a number / without a photo / without a group filters (cleanup chips) | backlog R3 |
| C25 | Group utilities: Message group (compose SMS to all), Email group, export group as .vcf | backlog R3 |
| C26 | Favorites custom order (drag) using `contacts_view::reordered_keys` | backlog R4 |
| C27 | Phonetic-free fuzzy search: match initials ("ay" -> "Ayse Yilmaz") and number fragments in any format | backlog R4 |
| C28 | Import from clipboard: paste a vCard or "Name +90..." text to create a contact | backlog R4 |
| C29 | Contact count and storage summary per account in Settings | backlog R4 |
| C30 | Quick actions on row long-press (Call, Message, Copy number, Share, Star) | backlog R4 |

## Steps (round 1)

Every step leaves all checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
If `df` shows under 6 GB free (it showed 6.1 GB at planning time), `rm -rf target/debug/incremental` first.
One focused commit per step, message prefix `Rovsti contacts: `. Do not touch `apps/phone` or `apps/messages`.

### S1. Pure duplicates and merge: `rovsti_core::contact_dupes` (C8)
- New `crates/rovsti_core/src/contact_dupes.rs`, `pub mod contact_dupes;` in `lib.rs`.
- `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum DupReason { SameNumber, SameEmail, SameName }`
  with `pub fn label(self) -> &'static str` ("Same number", "Same email", "Same name").
- `#[derive(Clone, Debug, PartialEq, Eq)] pub struct DupGroup { pub ids: Vec<i64>, pub reason: DupReason }`
  (ids sorted, the keeper first: the one with most numbers+emails, then starred, then lowest id).
- `pub fn find(drafts: &[ContactDraft]) -> Vec<DupGroup>`: union-find over draft ids; edges: a shared
  number key (last 10 digits, same rule as `import_plan::number_key`; move that helper to
  `pub(crate) fn number_key` and reuse it), a shared lowercased email, or the same `fold`ed
  `display_name()` when neither side has a number (a name match alone never joins two contacts that
  each have different numbers). Reason = strongest edge in the group (Number > Email > Name). Groups of
  one are dropped; drafts without `id` ignored; order by the keeper's folded name. Bound: O(n) hashing,
  no pairwise loop.
- `pub fn merge(keeper: &ContactDraft, others: &[ContactDraft]) -> ContactDraft`: keeper's id/account;
  given/family/org from the keeper unless empty (then the first non-empty other); numbers and emails
  unioned in order, deduped by number key / lowercased email (keep the first one's kind/label); notes
  joined with a blank line, duplicates skipped; groups: union of those of the keeper's account only
  (others' groups dropped when `account` differs); starred = any; `events` and `addresses` unioned too
  once S2 lands (S1 leaves a `// S2` free field list: write `merge` so new `Vec` fields are added in one
  place). Result passes `clean()`.
- `pub fn summary(groups: &[DupGroup]) -> String` ("No duplicates found", "1 possible duplicate",
  "4 possible duplicates") and `pub fn merged_notice(n: usize) -> String` ("Merged 3 contacts").
- Tests: same number in two formats groups; same email different case groups; same name without numbers
  groups; same name with different numbers does not; transitive chain A~B~C is one group; keeper choice;
  merge dedups numbers/emails, joins notes once, keeps keeper name, drops foreign-account groups, ORs
  starred; empty input; drafts without id.
- Acceptance: `cargo test -p rovsti_core contact_dupes` passes; no app change.

### S2. Pure dates and addresses: `ContactDraft` fields + `rovsti_core::contact_dates` (C5, C6, C9)
- `contact_edit.rs`: add to `ContactDraft` (serde default, `skip_serializing_if = "Vec::is_empty"` so old
  JSON and Kotlin answers still parse): `pub events: Vec<DraftEvent>`, `pub addresses: Vec<DraftField>`.
  `#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)] #[serde(default)]
  pub struct DraftEvent { pub date: String /* "YYYY-MM-DD" or "--MM-DD" */, pub kind: EventKind,
  pub label: Option<String> }`; `#[serde(rename_all = "snake_case")] pub enum EventKind { #[default]
  Birthday, Anniversary, Custom, #[serde(other)] Other }` with `title()` and `EVENT_KINDS`.
  `FieldKind::ADDRESS_KINDS = [Home, Work, Other]`. `clean()` trims/clips addresses (dedup by folded
  text) and events (drop unparsable dates, dedup by (kind, date)); `validate()` gains
  `DraftError::BadDate(usize)` ("Date {n} is not a real day."). Update `blank()`, `merge` in S1 and every
  struct literal the compiler flags (sample.rs, tests) with `..Default::default()` where needed.
- New `crates/rovsti_core/src/contact_dates.rs`: `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub struct
  Md { pub month: u8, pub day: u8 }`, `pub struct EventDate { pub year: Option<i32>, pub md: Md }`;
  `pub fn parse(s: &str) -> Option<EventDate>` (accepts `YYYY-MM-DD`, `YYYYMMDD`, `--MM-DD`, `--MMDD`,
  `YYYY-MM-DDThh:mm...` (time ignored); rejects 31 Apr, 29 Feb in a non-leap year with a year);
  `pub fn to_android(d: &EventDate) -> String` (`YYYY-MM-DD` / `--MM-DD`); `pub fn civil(days: i64) ->
  (i32, u8, u8)` and `pub fn days_from_civil(y, m, d) -> i64` (Howard Hinnant's algorithm, reuse the
  one in `call_log.rs` if it is already `pub(crate)`); `pub fn next_occurrence(md: Md, today: (i32,u8,u8))
  -> (i32, u8, u8)` (29 Feb falls on 28 Feb in non-leap years); `pub fn days_until(...) -> u16`;
  `pub fn age_on(year: Option<i32>, at_year: i32) -> Option<u16>`.
  `pub struct Upcoming { pub contact_id: i64, pub name: String, pub kind: EventKind, pub in_days: u16,
  pub date_text: String, pub turning: Option<u16> }`; `pub fn upcoming(drafts: &[ContactDraft],
  today_ms: i64, tz_offset_s: i32, within_days: u16) -> Vec<Upcoming>` (sorted by in_days then name).
  Texts: `pub fn when_text(in_days) -> String` ("Today", "Tomorrow", "In 5 days", "In 3 weeks"),
  `pub fn card_line(e: &DraftEvent, today) -> Option<String>` ("12 May 1994 · turns 31 in 5 days",
  "12 May" without a year), `pub fn wish_text(name, kind) -> String` ("Happy birthday, Anna!",
  "Happy anniversary, Anna!"). English month names, no locale lookup.
- Address helpers in the same module or `contact_edit`: `pub fn maps_url(address: &str) -> String`
  = `geo:0,0?q=` + percent-encoded single-line address (newlines to ", "; reuse the encoder in
  `location_link` if public, else a small RFC 3986 unreserved encoder with tests).
- Tests: every accepted/rejected date form; leap-day next occurrence; days_until across year end; age
  with and without year; `upcoming` ordering, window cut-off, tz offset moving "today"; wish text; maps
  url encodes `ç ş &` and newlines; old JSON without the new keys deserialises; `validate` BadDate.
- Acceptance: `cargo test -p rovsti_core` passes; app compiles unchanged in behaviour.

### S3. Pure vCard fields, export names and scrubber math (C7, C10, C4)
- `vcard.rs`: parse `BDAY` (3.0 `1994-05-12`, 4.0 `19940512`, `--0512`, with `VALUE=date`),
  `ANNIVERSARY` (4.0) and `X-ANNIVERSARY` (3.0), `ADR` (7 components `;`-separated, unescaped, joined as
  "street\ncity region postcode\ncountry" skipping empties; `TYPE=HOME/WORK`). Write in 3.0: `BDAY:`
  `YYYY-MM-DD` or `--MM-DD`, `X-ANNIVERSARY:`, `ADR;TYPE=HOME:;;street;city;;;country` (put the whole
  text in street with escaped newlines `\n` when it cannot be split; keep it simple and round-trip
  safe). Update the module doc (photos still skipped). Fix the test at line ~499 that asserts BDAY is
  skipped.
- `vcard.rs`: `pub fn export_name(drafts: &[ContactDraft], today: (i32,u8,u8)) -> String`: one contact
  -> `shared_card::vcf_file_name(display_name)`; several -> `rovsti-contacts-<n>-YYYY-MM-DD.vcf`.
- `contacts_view.rs`: `pub fn scrubber_letters(rows: impl IntoIterator<Item = char>) -> Vec<char>`
  (section letters in order, deduped; `#` last) and `pub fn letter_at(y: f32, height: f32, count: usize)
  -> Option<usize>` (clamped, `None` for count 0) and `pub fn first_row_of(letters_of_rows: &[char],
  letter: char) -> Option<usize>`.
- Tests: round-trip BDAY with/without year, X-ANNIVERSARY, ADR with comma/semicolon/newline escapes, a
  real iCloud 3.0 and a Google 4.0 sample card, unparsable BDAY is dropped not fatal; export names;
  scrubber mapping at top, bottom, out of range, empty.
- Acceptance: `cargo test -p rovsti_core vcard contacts_view` passes.

### S4. Android glue and backend wrappers (C2, C4, C5, C6)
- Kotlin `crates/rovsti_android/kotlin/dev/rovsti/bridge/ContactsEdit.kt`: add
  `ContactsContract.CommonDataKinds.Event` and `StructuredPostal` to `EDITED_MIMES` (so update replaces
  them; read before changing that `update` deletes only `EDITED_MIMES`). `DraftBuilder.add`: Event ->
  `events.put({date: START_DATE, kind: birthday|anniversary|other|custom, label})` (types 3/1/2/0);
  StructuredPostal -> `addresses.put(field(FORMATTED_ADDRESS, home|work|other, LABEL))`. `DATA_PROJECTION`
  already has DATA1..DATA3 (START_DATE = DATA1, TYPE = DATA2, LABEL = DATA3; FORMATTED_ADDRESS = DATA1).
  `insertData`: write events (`Event.START_DATE`, `Event.TYPE`, `Event.LABEL`) and addresses
  (`StructuredPostal.FORMATTED_ADDRESS`, `TYPE`, `LABEL`). `toJson` emits the arrays only when non-empty.
  `fullJson` therefore exports them too.
- `apps/contacts/platforms/android/app/src/main/AndroidManifest.xml`: register
  `dev.rovsti.bridge.RovstiShareProvider` exactly like Phone's manifest lines 119-123.
- `apps/contacts/src/backend.rs` thin wrappers, all returning `Result<_, String>` through the existing
  `done`/`android_words` helpers: `pub(crate) const FULL_PHOTO: u32 = 1080;`
  `pub(crate) fn full_photo(uri: &str) -> Option<(image::RgbaImage, PathBuf)>` (reuse `contact_photo(uri,
  FULL_PHOTO)` and write a PNG into `std::env::temp_dir()/rovsti-contacts/` so save/share have a path;
  desktop uses the sample photo path the same way), `save_to_gallery(path, name)`, `share_image(path,
  title)`, `share_vcf(name, text)` (write the text to the temp dir, `media::share_file(path,
  "text/x-vcard", name)`), `share_png(name, bytes)`, `pull_full() -> Result<Vec<ContactDraft>, String>`
  (wrap `contacts_edit::full()` like `export_text` does; reuse it there).
- `apps/contacts/src/sample.rs`: give two sample drafts a birthday (fixed dates, one without a year),
  one an anniversary, one a home address; add a duplicate pair (same number in two formats) so desktop
  shows S7. Tests only check that they parse, never which are "upcoming".
- Tests: desktop wrappers return the Unsupported words; sample drafts pass `finish()`; sample has one
  duplicate group by `contact_dupes::find`.
- Acceptance: all checks green, including the aarch64 checks.

### S5. Photo viewer (C1, C2, C3): `apps/contacts/src/photo_viewer.rs`
- Read `.claude/skills/liquid-glass-gpui/SKILL.md` first. Adapt (do not import) Phone's
  `photo_viewer.rs`: keep its `image_rect(view, natural, viewport)` pure fn with tests, its gesture flow
  (`on_pinch` -> `ZoomView::pinch`, drag -> pan when zoomed else dismiss progress with background fade,
  release -> `settled` with `rovsti_ui::spring::Spring`, `click_count == 2` -> `double_tap`, Escape and
  Back close). Field on `ContactsApp`: `viewer: Option<PhotoViewer>`; `go_back`/`can_go_back` close it
  first; draw it above the card and sheets in `render`.
- Open: tap on the card avatar in `detail.rs` (`photo_avatar(... 96.0 ...)`) when a photo is shown
  (contact photo or the WhatsApp stand-in). Show the cached thumbnail at once, swap in
  `backend::full_photo` from the background executor.
- Chrome: top glass bar Close + name; bottom glass bar Save, Share, Edit (only for a contact photo, not
  the WhatsApp stand-in). Save -> pill "Saved to Photos" / failure words; Share -> pill on failure.
  Edit -> small glass menu "Choose new photo" (`backend::start_pick_photo`, existing hand-back
  `apply_photo`) and "Remove photo" -> `Confirm::RemovePhoto { id, name }` in `confirm.rs` ("Remove
  Anna's photo?" destructive button) -> `clear_photo`, closes the viewer, pill "Photo removed".
  Tap with no photo on the avatar opens nothing (the editor already handles adding one).
- Tests (pure, in the module): `image_rect` at scale 1 is centred and inside the viewport; zoomed rect
  scales by `scale`; viewer opens only with an image; `Confirm::RemovePhoto` text.
- Acceptance: all checks green.

### S6. Card and editor: share menu, QR, dates, addresses (C4, C5, C6)
- `detail.rs`: the Share action button opens a small glass sheet (reuse the field-sheet pattern) with
  "Share as text" (current behaviour), "Share contact file (.vcf)" (`vcard::write(&[draft])`,
  `vcard::export_name`, `backend::share_vcf`) and "Show QR code". QR: new `apps/contacts/src/qr_sheet.rs`
  rendering the PNG from `rovsti_wa_ui::qr::qr_png(number_format::contact_qr(name, numbers, None)?
  .payload, 720)` as a `RenderImage` on a white rounded card (QR must stay dark-on-white in dark mode)
  with Save (`backend::save_to_gallery` of the PNG written via `share_png`'s temp path,
  `number_format::qr_file_name`) and Share buttons; `QrTooBig` shows pill "Too many details for one QR
  code". Pills: "Saved to Photos", failure words.
- Card rows (after emails, before note): each `events` entry as an info row (label = kind title, value
  = `contact_dates::card_line`), long-press -> field sheet Copy; birthday rows with a number get a
  "Wish" chip -> `telecom::compose_sms(number, wish_text)`. Each address: info row, tap ->
  `cx.open_url(&maps_url(addr))`, long-press -> field sheet with Copy / Share / Open in Maps. Extend
  `FieldTarget` with `Address(String)` and `Date(String)`.
- `editor.rs`: sections "Birthday & dates" (add row: kind cycles `EVENT_KINDS`, a text field taking
  `YYYY-MM-DD` or `MM-DD`, hint "e.g. 1994-05-12 or 05-12"; normalise through `contact_dates::parse` +
  `to_android` on save; `BadDate` error shown like `BadEmail`) and "Addresses" (multi-line text field,
  kind cycles `ADDRESS_KINDS`, remove button like numbers). No new GPUI widgets: reuse `field.rs`.
- Tests: `FieldTarget` actions per variant; a pure `share_choices(has_numbers) -> Vec<ShareChoice>`
  helper (QR only offered with a number or email); editor date normalisation helper.
- Acceptance: all checks green.

### S7. Find duplicates and merge (C8): `apps/contacts/src/dupes.rs`
- Settings tab gains a "Find duplicates" row (under export/import). It opens a page (same page pattern
  as the WhatsApp settings page in `whatsapp_page`) that loads `backend::pull_full()` on the background
  executor (spinner text "Looking for duplicates..."), runs `contact_dupes::find`, and lists groups:
  avatars + names + reason label + numbers line; empty state `summary`.
- Tap a group -> review sheet: the merged preview (`merge`), "Merge" (primary) and "Not now". Merge
  confirms through `Confirm::MergeContacts { keeper, others, name }` ("Merge 3 contacts into Anna? The
  other 2 are deleted from the phone."). On confirm: `backend::save(&merged)` then `backend::delete`
  each other id, on the background executor; keep `(original keeper draft, other drafts)` for Undo.
  Pill `merged_notice(n)` with Undo (new `NoticeAction::UndoMerge`, extend `keeps_undo` and its tests):
  Undo saves the original keeper draft and `backend::add_contacts(others with id = None)`, pill
  "Merge undone". Reload the book and re-run `find` after both.
- Tests: `keeps_undo` with `UndoMerge`; a pure `undo_cards(others) -> Vec<ContactDraft>` that clears
  ids and keeps everything else.
- Acceptance: all checks green; on desktop the sample duplicate pair shows one group (merge reports
  the Unsupported words since writes need a phone).

### S8. Upcoming birthdays, alphabet scrubber, docs (C9, C10)
- Favorites tab: an "Upcoming" glass section above the grid with the next 3 `contact_dates::upcoming`
  entries within 30 days (avatar, name, `when_text`, "turns 31"), a "See all" row opening an Upcoming
  page (365 days, grouped "This week", "This month", "Later"). Tap opens the card; a "Wish" chip
  composes the SMS (hidden without a number). Drafts come from `backend::pull_full()` loaded once after
  the book loads and on `reload` (background executor; never per frame); store `upcoming:
  Vec<Upcoming>` on `ContactsApp`.
- Contacts tab: a vertical letter column on the trailing edge (only when no search text and > 20 rows),
  letters from `scrubber_letters`; mouse down/move maps `letter_at` -> `first_row_of` ->
  `list.scroll_to_reveal_item(ix)`, a glass bubble shows the letter while dragging, `rovsti_ui::haptics`
  tick on each letter change (check its public fn first).
- Docs: `docs/roadmap.md` Rovsti Contacts section ticks C1-C10 ("done without a device"); update this
  file's backlog statuses to `done R1` for what landed.
- Tests: pure helper `favorites_upcoming(&[Upcoming], 3)`; scrubber visibility rule
  `show_scrubber(rows, query) -> bool`.
- Acceptance: all checks green.
