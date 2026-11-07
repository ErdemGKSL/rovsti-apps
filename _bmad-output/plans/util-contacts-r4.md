# util-contacts: everyday utilities for Rovsti Contacts (round 4)

baseSha: 1ebe35bc21e71579d53d05e7562a51defd9c20f9

## Audit (what exists at baseSha)

Rounds 1-3 (`util-contacts.md`, `-r2.md`, `-r3.md`) are fully landed: C1-C25 are done (last commit
1ebe35b "Clean up page, group utilities, docs and backlog (C24, C25)") and ticked in `docs/roadmap.md`
("Utilities, round 3"). Clean tree, no stash, about 6 GB free (run `rm -rf target/debug/incremental`
before building when `df` shows under 6 GB).

Facts the R4 steps build on (checked at baseSha, do not re-create):
- Search: `rovsti_core::search::ContactIndex::search` is substring-only on the folded name/email.
  Digit queries already ignore formatting and match `05..`/`0090..` against `+90..` (`Query::Digits`,
  `after_country_code`). No initials or any-order word matching. Phone also uses `search()`, so its
  behaviour must stay the same. Contacts calls it in `main.rs::rebuild_rows` (l. ~720) and `picker.rs:130`.
- Favorites: Contacts builds them with `contacts_view::ordered(contact_card::favorites(index.all()),
  style)` (`main.rs::rebuild`, l. ~683), sorted by name only. The pure pieces for a custom order exist
  (`contacts_view::order_favorites(&[WireContact], &[String])`, `reordered_keys`, keys = `lookup_key`);
  Phone keeps its order in `settings::favorites_order`. `ContactsPrefs` has no order field
  (`MAX_FILE_BYTES = 4 KiB`, too small for lookup keys).
- Long press: on Contacts tab rows it enters select mode (`select.rs::long_press_contact`). On Favorites,
  Group and Recent rows it does nothing (`main.rs::contact_row` only handles `key == "contact"`).
- Clipboard read: GPUI has `cx.read_from_clipboard()` (`ClipboardItem::text()`), implemented by
  `gpui_android` and already used in `apps/phone/src/keypad.rs`, `apps/stops/src/picker.rs`.
  Contacts never reads the clipboard. `contact_scan::parse_payload` (vCard, MECARD, tel:, mailto:,
  bare number/email) exists; plain "Name +90 532 ..." text gives `Scanned::Nothing`.
- Incoming files: GPUI `Application::on_receive_share(|Result<ReceivedShare>, &mut App|)`
  (`ReceivedShare { text, mime_type, files: Vec<SelectedFile> }`, `SelectedFile::read_limited(max)`)
  and `Application::on_open_urls`. Stops uses both in `apps/stops/src/main.rs::main`. Contacts uses
  neither; its manifest (`apps/contacts/platforms/android/app/src/main/AndroidManifest.xml`, management
  "manual") has only the MAIN/LAUNCHER filter. `MainActivity` extends `GpuiActivity` (forwards intents).
- Number forms: `number_format::{NumberForm, available(raw, home_cc), infer_home_cc}` exist (Phone uses
  them). The Contacts field sheet (`detail.rs` `FieldAction::Copy("Copy Number")`) copies the raw text only.
- Ringtone / voicemail / home-screen shortcut: nothing in Kotlin or Rust (`CUSTOM_RINGTONE`,
  `SEND_TO_VOICEMAIL`, `ShortcutManager` are not referenced). `RovstiBridge.deliverOpenContact(intent)`
  reads `BirthdayReminders.EXTRA_CONTACT_ID` and posts the one-shot `open_contact {id}`, already handled
  by `main.rs`; a pinned shortcut can reuse it.
- Blocking from Contacts (C35): `BlockedNumberContract` needs the default dialer/SMS role, which Contacts
  never holds, so `blocklist::block` would fail. Deferred (needs a Phone hand-off; Phone must not change
  in this pass). C37 (last SMS on the card) needs READ_SMS in Contacts, which belongs to Messages:
  dropped.
- `ContactDraft` has `account` (pull_full), `Account { key, name, kind }`, `account_label(key)`.
  `WireContact` has no account, so per-account counts come from `backend::pull_full()`.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| C1-C10 | Photo viewer, save/share photo, change/remove, share as text/vcf/QR, dates, addresses, vCard fields, duplicates+merge, upcoming birthdays, scrubber | done R1 |
| C11-C18 | Select mode, export one, crop/rotate, camera, recently added, copy contact / vCard, WhatsApp per number, websites/nickname/title | done R2 |
| C19-C25 | Scan to add, Trash, card call history, birthday reminders, default number/email, Clean up, group utilities | done R3 |
| C27 | Smarter search: initials ("ay" -> Ayse Yilmaz), words in any order ("yil ay"), tiered results | done R4 |
| C30 | Quick actions on long press in Favorites / Group / Recent (Call, Message, WhatsApp, Copy number, Share, Star, Add to Home screen) | done R4 |
| C26 | Favorites custom order (Edit -> Move up / Move down, saved locally) | done R4 |
| C28 | Paste to add: clipboard vCard / MECARD / "Name +90 ..." text -> editor or import preview | done R4 |
| C31 | Open a .vcf shared to or opened with Contacts (SEND / VIEW text/x-vcard) -> import preview | done R4 |
| C32 | Copy number as: international (E.164), national, display, tel: link (field sheet "Copy as...") | done R4 |
| C34 | Per-contact ringtone (system picker) and "Send calls to voicemail" (confirm) | done R4 |
| C36 | Add contact to Home screen (pinned shortcut opens the card) | done R4 |
| C29 | Contacts summary in Settings: total, per account, with number / email / birthday / photo | done R4 |
| C33 | vCard PHOTO import/export (base64 JPEG, size-capped) for single-card share / save and single-card import | backlog R5 |
| C38 | My card: the phone owner's profile card (ContactsContract.Profile) with Share / QR | backlog R5 |
| C39 | Export as CSV (Google / Outlook column set) next to "Export to a .vcf file" | backlog R5 |
| C40 | Undo last edit: after Save, "Undo" pill restores the previous draft | backlog R5 |
| C41 | Search in more fields (company, nickname, notes, address) via a pulled full index | backlog R5 |
| C42 | Frequently contacted row on Favorites (from the local call log, permission already in C21) | backlog R5 |
| C43 | Emergency / ICE contacts list and lock-screen-friendly share | backlog R5 |
| C35 | Block / unblock a number from the card (hand-off to Rovsti Phone, which holds the dialer role) | deferred (Phone change) |
| C37 | Last SMS with this contact on the card | dropped (READ_SMS belongs to Messages) |

## Rules for every step

All checks green after each step: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
One focused commit per step, prefix `Rovsti contacts: `. Do not change Phone / Messages behaviour (shared
`rovsti_core` / `rovsti_android` changes keep them compiling: fix struct literals only). Read
`.claude/skills/liquid-glass-gpui/SKILL.md` before S5-S7; use only GPUI APIs already used in this repo or
grepped in `~/.cargo/git/checkouts/gpui-fusion-*/d6c8412/crates/gpui` (`on_long_press`, `on_click`,
`ListState`, `Sheet`, `read_from_clipboard`, `on_receive_share`, `on_open_urls`, `SelectedFile::read_limited`).
Every action ends in a pill (`self.notice`); destructive ones go through `confirm.rs::Confirm`.

## Steps (round 4)

### S1. Pure: smarter search, quick actions, contacts summary (C27, C30, C29)
- `crates/rovsti_core/src/search.rs`: `Entry` gains `words: Vec<String>` (folded name words, split on
  whitespace and `-`/`.`/`,`, from `display_name` plus `display_name_alt` when it differs, deduped),
  `initials: String` and `initials_alt: Option<String>` (first char of each folded word, in order).
  New `pub fn search_loose(&self, query: &str) -> Vec<&WireContact>`:
  `Empty` -> `all()`; `Digits` -> same as `search`; `Text(q)` -> tier 0 = today's substring hits;
  tier 1 = every whitespace-separated query word is a prefix of a distinct entry word (any order);
  tier 2 = `q` has no whitespace, 2..=5 chars, all alphabetic, and `initials` or `initials_alt` starts
  with it. Result = tier 0, then 1, then 2, each in provider order, no duplicates. `search()` unchanged.
- New `crates/rovsti_core/src/contact_quick.rs` (+ `pub mod` in `lib.rs`):
  `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum QuickAction { Call, Message, WhatsApp,
  CopyNumber, Share, Star, Unstar, AddToHome }`; `pub fn actions(has_number: bool, on_whatsapp: bool,
  starred: bool) -> Vec<QuickAction>` (number-only entries omitted without a number; AddToHome always
  last); `pub fn label(self) -> &'static str` ("Call", "Message", "WhatsApp", "Copy number", "Share
  contact", "Add to Favorites", "Remove from Favorites", "Add to Home screen").
- New `crates/rovsti_core/src/contact_stats.rs`: `#[derive(Debug, Default, PartialEq, Eq)] pub struct
  Summary { pub total: usize, pub with_number: usize, pub with_email: usize, pub with_birthday: usize,
  pub starred: usize, pub accounts: Vec<(String /* key */, usize)> }`;
  `pub fn summarize(drafts: &[ContactDraft]) -> Summary` (accounts sorted by count desc then key, "" =
  on this phone; birthday = an `events` entry of the birthday kind, use the real `DraftEvent` kind
  name); `pub fn total_text(n) -> String` ("1 contact" / "412 contacts"); `pub fn rows(s: &Summary)
  -> Vec<(String, String)>` (label via `contact_edit::account_label`, count text).
- Tests: initials hit "ay" -> "Ayse Yilmaz", Turkish folding ("is" -> "Işık Sarı"), alt-name initials,
  any-order words ("yil ay"), tiers ordered and deduped, single letter does not use initials, digits
  unchanged, `search()` results unchanged on the existing fixtures; quick actions with/without number,
  WhatsApp, star state; summary counts, account sort, texts singular/plural.
- Acceptance: `cargo test -p rovsti_core search contact_quick contact_stats` green; no app change.

### S2. Pure: paste to add, shared-file intake, copy-as forms (C28, C31, C32)
- `crates/rovsti_core/src/contact_scan.rs`: `pub fn parse_pasted(text: &str) -> Scanned`: clip to 8 KiB;
  `parse_payload` first; when that gives `Nothing` (or a bare `Number`/`Email` with more text around),
  try a private `parse_name_line(text) -> Option<ContactDraft>`: tokens on lines; phone runs accepted by
  `import_plan::number_key` (7+ digits, `+`, spaces, `-`, `()` allowed) become numbers (max 5),
  `x@y.z` tokens emails (max 5), the remaining letters (labels like "Tel:", "Phone", "Mobile",
  "E-mail" stripped, punctuation trimmed) the name, split into `given`/`family` on the last space;
  needs a number or email. `pub fn paste_notice(s: &Scanned) -> &'static str` ("Nothing to add on the
  clipboard" for `Nothing`).
- New `crates/rovsti_core/src/contact_intake.rs`: `pub const MAX_SHARED_BYTES: usize = 4 * 1024 * 1024;`
  `pub fn is_vcard(mime: Option<&str>, name: &str, head: &[u8]) -> bool` (mime `text/vcard`,
  `text/x-vcard`, `text/directory`, or name ends `.vcf`/`.vcard` case-insensitive, or the head after a
  UTF-8 BOM and whitespace starts with `BEGIN:VCARD` case-insensitive);
  `pub fn decode(bytes: &[u8]) -> Option<String>` (strip BOM, UTF-8 only, `None` otherwise);
  `#[derive(Debug, PartialEq)] pub enum Intake { Cards(Vec<ContactDraft>), Single(Scanned),
  NotContacts, TooBig, Unreadable }`; `pub fn from_file(mime, name, bytes) -> Intake` (size check,
  `is_vcard`, `decode`, `vcard::parse`, cards with at least a name/number/email) and
  `pub fn from_text(text) -> Intake` (`parse_pasted`); `pub fn notice(i: &Intake) -> Option<&'static
  str>` ("This file has no contacts", "This file is too big to open", "Can't read this file").
- `crates/rovsti_core/src/number_format.rs`: `pub fn copy_forms(raw: &str, home_cc: Option<&str>) ->
  Vec<(NumberForm, String)>` = `available` without `NumberForm::VCard`, and `pub fn copied_notice(form:
  NumberForm) -> &'static str` ("Number copied", "International number copied", ...; use the real
  variants of `NumberForm`).
- Tests: "Ayse Yilmaz +90 532 111 22 33" -> one card with given/family/number; "Tel: 0532..." with label
  stripped; two numbers + email; plain sentence -> Nothing; vCard text still -> Cards; `is_vcard` by
  mime / name / head with BOM; non-UTF-8 -> Unreadable; oversize -> TooBig; vCard with no fields ->
  NotContacts; copy_forms drops VCard and duplicates.
- Acceptance: `cargo test -p rovsti_core contact_scan contact_intake number_format` green.

### S3. Pure: favorites order, ringtone / voicemail options, shortcut spec (C26, C34, C36)
- `contacts_prefs.rs`: `#[serde(default)] pub favorites_order: Vec<String>` (lookup keys);
  `pub const MAX_ORDER: usize = 300;` and `pub fn order(&self) -> Vec<String>` (dedup, cap, drop
  empty); raise `MAX_FILE_BYTES` to 64 KiB; fix `Default`/`from_style` literals. Old JSON parses.
- `contacts_view.rs`: `pub fn order_favorite_refs<'a>(favorites: Vec<&'a WireContact>, order: &[String])
  -> Vec<&'a WireContact>` (the logic of `order_favorites`, which now calls it: Phone unchanged);
  `pub fn move_favorite(shown: &[&WireContact], index: usize, up: bool) -> Option<Vec<String>>` (None at
  the edges or without a key; built on `reordered_keys`).
- New `crates/rovsti_core/src/contact_options.rs`:
  `#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)] #[serde(default)] pub struct
  ContactOptions { pub ringtone_uri: Option<String>, pub ringtone_title: Option<String>, pub
  to_voicemail: bool }` (the Kotlin reply); `pub fn parse(json: &str) -> Result<ContactOptions,
  serde_json::Error>`; `pub fn ringtone_text(o: &ContactOptions) -> String` ("Default", "Silent" when the
  uri is `Some("")`, else the title or "Custom"); `pub fn voicemail_confirm(name: &str) -> (String,
  String)` ("Send Ayse's calls to voicemail?", "Their calls won't ring this phone."); notices
  `ringtone_notice(&ContactOptions)`, `voicemail_notice(on)`.
  `#[derive(Clone, Debug, PartialEq, Eq, Serialize)] pub struct PinShortcut { pub id: String, pub
  short_label: String, pub long_label: String, pub contact_id: i64, pub icon_path: Option<String> }` and
  `pub fn shortcut(contact_id: i64, lookup_key: Option<&str>, name: &str, icon_path: Option<&str>) ->
  PinShortcut` (id `contact-<lookup or id>`, short label = first word clipped to 10 chars, long label =
  name clipped to 25 chars, char-boundary safe, "Contact" when blank); `pub fn pin_notice(ok: bool) ->
  &'static str` ("Added to Home screen" / "Your launcher can't add shortcuts").
- Tests: prefs default, old JSON, order dedup/cap; move up/down/edges; `order_favorites` unchanged on
  its tests; options parse with missing fields, ringtone texts, shortcut ids/labels (Turkish, emoji, long).
- Acceptance: `cargo test -p rovsti_core` green; workspace compiles.

### S4. Android glue and backend wrappers (C31, C34, C36, C29)
- New `crates/rovsti_android/kotlin/dev/rovsti/bridge/ContactOptions.kt`: `read(ctx, id): String` JSON
  `{ringtone_uri, ringtone_title, to_voicemail}` from `Contacts.CUSTOM_RINGTONE` /
  `Contacts.SEND_TO_VOICEMAIL` (title via `RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx)` in a
  try, null on failure); `setRingtone(ctx, id, uri: String?)` and `setVoicemail(ctx, id, on)` via
  `contentResolver.update(ContentUris.withAppendedId(Contacts.CONTENT_URI, id), values, null, null)`;
  `pickRingtone(activity, current: String?)`: `RingtoneManager.ACTION_RINGTONE_PICKER`, TYPE_RINGTONE,
  show default + silent, existing uri, a new request code next to the ones in `Documents.kt`, answered
  in the existing `handleActivityResult` dispatch as event `ringtone_picked {uri: String?, cancelled:
  bool}` (picked `DEFAULT_RINGTONE_URI` -> null = default; null picked uri -> "" = silent).
  `RovstiBridge` `@JvmStatic` wrappers. Rust `rovsti_android::contacts_edit::{contact_options(id:
  i64) -> Result<String>, set_ringtone(id: i64, uri: Option<&str>) -> Result<bool>,
  set_send_to_voicemail(id: i64, on: bool) -> Result<bool>, pick_ringtone(current: Option<&str>) ->
  Result<bool>}` (Unsupported on desktop, desktop tests).
- New `crates/rovsti_android/kotlin/dev/rovsti/bridge/Shortcuts.kt` + `crates/rovsti_android/src/
  shortcuts.rs` (`pub mod shortcuts;`): `can_pin() -> Result<bool>` (`ShortcutManager
  .isRequestPinShortcutSupported`), `pin_contact(spec_json: &str) -> Result<bool>`: intent =
  `packageManager.getLaunchIntentForPackage(packageName)` with `BirthdayReminders.EXTRA_CONTACT_ID` =
  contact id and `FLAG_ACTIVITY_CLEAR_TOP`; icon = `Icon.createWithAdaptiveBitmap` of the decoded
  `icon_path` (centre-cropped, padded to the adaptive safe zone) else `Icon.createWithResource(ctx,
  applicationInfo.icon)`. `deliverOpenContact` already turns the extra into `open_contact`.
- Shared / opened vCards: Contacts manifest `MainActivity` gains filters `SEND` (DEFAULT) with mime types
  `text/x-vcard`, `text/vcard`, `text/directory`, and `VIEW` (DEFAULT, BROWSABLE not needed) with
  scheme `content` and the same mime types; mirror them in `gpuiforge.json` `"share-mime-types"`.
  VIEW arrives through `on_open_urls` as a `content://` string: add `RovstiBridge.readSharedUri(uri,
  maxBytes): String` (path of a copy in `cacheDir/shared/` or "" on failure / too big) and Rust
  `documents::copy_shared(uri: &str, max_bytes: usize) -> Result<String>`.
- `apps/contacts/src/backend.rs`: `options_of(id) -> Result<ContactOptions, String>`,
  `set_ringtone`, `set_voicemail`, `pick_ringtone`, `pin_shortcut(&PinShortcut) -> Result<bool,
  String>`, `can_pin() -> bool`, `shared_file(uri) -> Result<Intake, String>` (copy + read +
  `contact_intake::from_file`), `summary() -> Result<Summary, String>` (`pull_full` + `summarize`).
- Tests (desktop): wrappers return the Unsupported words; `shared_file` on a desktop temp `.vcf` path
  (a desktop branch that reads a plain path) returns Cards.
- Acceptance: all checks green incl. the three aarch64 checks.

### S5. UI: smarter search, quick actions, favorites order (C27, C30, C26)
- `main.rs::rebuild_rows` and `picker.rs` use `search_loose`. The no-results "Create contact" offer
  (UX r4) stays.
- Quick actions: `contact_row` handles long press for `"favorite"` and `"group-contact"` keys (and the
  Recent page rows in `recent.rs`) by opening a new `quick_sheet: Sheet` (new `apps/contacts/src/
  quick.rs`, pattern of the share sheet in `detail.rs`) listing `contact_quick::actions` for that row;
  Call/Message use the card's existing default-number paths (`contacts_view::call_target`, ask which
  number when several), WhatsApp the existing hand-off, Copy number `telecom::copy_text` + pill, Share
  opens the existing share menu for that contact, Star/Unstar `toggle_star`, Add to Home screen the S7
  path (hidden until S7 lands, or shows the Unsupported pill). Contacts tab long press keeps select mode.
- Favorites order: Favorites header gains "Edit" (only with 2+ favorites) -> reorder mode: each row
  shows Move up / Move down glass buttons (`LucideChevronUp/Down`, no drag), "Done" leaves; each move
  calls `move_favorite`, stores `favorites_order` in `ContactsPrefs` (`save_prefs`), rebuilds with
  `order_favorite_refs`, pill "Order saved" once per edit session. `prefs_snapshot` carries the order.
- Tests: pure helper `quick_target(row) -> (bool has_number, bool on_whatsapp, bool starred)`;
  `favorites_header_actions(n, editing)`; existing tests updated.
- Acceptance: all checks green.

### S6. UI: paste to add, shared vCards, copy as (C28, C31, C32)
- Paste: Settings "Contacts file" section row "Paste contact" (`LucideClipboardPaste` if it exists in
  `gpui_icons`, else `LucideClipboard`; grep first) and, when the search box is empty and the
  clipboard holds a contact, nothing automatic (privacy): only on tap. Tap -> `cx.read_from_clipboard()
  .and_then(|i| i.text())` -> `contact_intake::from_text` -> the same routing as `transfer.rs::route_scan`
  (one card -> editor prefilled, many -> import preview via `plan_cards`, else `paste_notice` pill).
- Shared files: `main()` registers `app.on_receive_share` and `app.on_open_urls` before `run`, sending
  into an `mpsc::unbounded` like Stops; `ContactsApp::new` takes the receiver and drains it (also when
  the book is still loading: hold `pending_intake`). Share: `files[0].read_limited(MAX_SHARED_BYTES +
  1)` on the foreground task, then `from_file`; `text` only -> `from_text`. URL (`content://`) ->
  `backend::shared_file` on the background executor. Route as above; notices from
  `contact_intake::notice`. Several files: the first vCard; pill when others are skipped.
- Copy as: the number field sheet adds "Copy as..." (`FieldAction::CopyAs`) when `copy_forms` gives 2+
  forms; it opens a second list in the same sheet with each form's label and text; tap copies and shows
  `copied_notice`. `home_cc` = `number_format::infer_home_cc` over the book's numbers, computed once per
  rebuild (store on the app).
- Tests: `field::actions` includes CopyAs only for numbers with 2+ forms; `intake_route(&Intake) ->
  ScanRoute`-style mapping reusing S6 of R3 (`scanned_route`).
- Acceptance: all checks green.

### S7. UI: ringtone and voicemail, Home screen shortcut, contacts summary (C34, C36, C29)
- Card (`detail.rs`): a "Calls" section under the numbers (contact has a number): "Ringtone" row with
  `ringtone_text` -> `pick_ringtone`; `ringtone_picked` event (in `transfer.rs::on_event` or a new
  handler in `detail.rs`) -> `set_ringtone` on the background executor -> reload options -> pill
  `ringtone_notice`; "Send calls to voicemail" toggle row: turning on asks `voicemail_confirm`, off is
  direct; pill `voicemail_notice`. Options load once per card open with the draft (background), and
  are refreshed on return from the picker.
- Home shortcut: card share/more menu entry "Add to Home screen" (shown when `can_pin()`), quick sheet
  entry from S5: builds `contact_options::shortcut(id, lookup_key, name, photo_path)` (photo path from
  the cached full photo / thumbnail when present) -> `pin_shortcut` -> `pin_notice`.
- Summary: Settings top section "Contacts" with a row "412 contacts" (`total_text`, from the loaded
  book) that opens a small page (pattern of `recent.rs`) loading `backend::summary()` in the background
  (spinner row while loading): rows from `contact_stats::rows` plus the with-number / email / birthday /
  favorites counts; tap an account row filters nothing (read-only) in this round.
- Tests: `voicemail_toggle(current, wanted) -> Option<Confirm>` (confirm only when turning on);
  `ringtone_picked` JSON parse helper; summary page rows mapping.
- Acceptance: all checks green.

### S8. Docs, backlog and review fixes (C26-C36)
- `docs/roadmap.md` Rovsti Contacts: "Utilities, round 4 (C26-C32, C34, C36, done without a device)"
  with one line per utility; `docs/architecture.md`: Contacts receives shared / opened vCards
  (`on_receive_share`, `on_open_urls`, manifest filters), writes `CUSTOM_RINGTONE`/`SEND_TO_VOICEMAIL`,
  pins shortcuts that reuse `open_contact`, keeps the favorites order in `contacts-prefs.json`.
- Update this file's backlog statuses to `done R4` for what landed; leave C33, C38-C43 as `backlog R5`.
- Run `/code-review`-style self review of the R4 diff (`git diff 1ebe35b..HEAD -- apps/contacts
  crates/rovsti_core crates/rovsti_android`): no blocking work on the UI thread, every action has a pill,
  the voicemail confirm, no Phone/Messages behaviour change; fix what it finds in the same commit.
- Acceptance: all checks green.
