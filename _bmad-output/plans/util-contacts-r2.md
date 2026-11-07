# util-contacts: everyday utilities for Rovsti Contacts (round 2)

baseSha: bc9e0c726730ca433397955ff2d43781d1efb767

## Audit (what exists at baseSha)

Round 1 (`util-contacts.md`, S1-S8) is fully landed (commits f68d778 .. bc9e0c7): C1-C10 are done.
No stash entries, clean tree.

Shipped in R1 and reusable now (do not re-create):
- `rovsti_core::{contact_dupes, contact_dates}`; `ContactDraft.{events, addresses}`; vCard BDAY,
  ANNIVERSARY/X-ANNIVERSARY, ADR; `vcard::export_name(drafts, today)`; `contacts_view::{scrubber_letters,
  show_scrubber, letter_at, first_row_of}`.
- App: `photo_viewer.rs` (zoom/pan/save/share/change/remove), `qr_sheet.rs`, `dupes.rs` (find + merge +
  `NoticeAction::UndoMerge`), `upcoming.rs` (Favorites section, Upcoming page, scrubber), card Share menu
  (`detail.rs::ShareChoice {Text, Vcf, Qr}`, `share_choices`), `FieldTarget {.., Address, Date}` with
  `FieldAction::Copy(..)`, field rows use `.on_long_press` and a trailing `field_more` button.
- Backend (`apps/contacts/src/backend.rs`): `pull_full`, `export_text(hidden)`, `start_export(text)`
  (always names the file `contacts.vcf`), `share_vcf`, `save_png`, `share_png`, `start_pick_photo`,
  `change_photo(id, path)` (uses `contact_photo::plan` = centred square), `delete`, `save`,
  `set_group_members(group_id, change)`, `add_contacts`.
- Android seams: `documents::export_contacts(name, text)` (any name), `documents::pick_contact_photo`
  (`document_result` op `contact_photo`), `contacts_edit::set_photo(id, path, plan_json)` (Kotlin
  `ContactPhotos` crops by `PhotoPlan`), `telecom::{copy_text, share_text, compose_sms, send_email_to}`,
  `media::*`, `RovstiShareProvider` (read-only, `openFile` refuses mode != "r") registered in the
  Contacts manifest.
- `rovsti_core::multi_select::{Selection, title, star_all, starred_notice, deleted_notice, cap_notice}`,
  `contact_edit::SelectionChange { add, remove }`, `pending_delete`.
- WhatsApp Chat / Call exist once per card (the contact's first WhatsApp JID), not per number row.

Not present yet: select mode in lists; export one contact to a file; copy whole contact / copy as vCard;
`WireContact` has no last-updated time (Kotlin `ContactsQuery.CONTACT_PROJECTION` lacks
`CONTACT_LAST_UPDATED_TIMESTAMP`), so no "Recently added"; websites / nickname / job title are neither
read (`ContactsEdit.EDITED_MIMES`) nor in `ContactDraft` nor in vCard; `PhotoPlan` is centred-only, no
rotation, no user crop; no camera capture anywhere in `crates/rovsti_android/kotlin`.

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| C1 | Photo viewer: full screen, pinch / double-tap zoom, pan, swipe down, tap hides chrome | done R1 |
| C2 | Save photo to gallery and Share photo from the viewer | done R1 |
| C3 | Change / Remove photo from the viewer (Remove confirms) | done R1 |
| C4 | Share contact as Text, .vcf, QR code (QR Save / Share) | done R1 |
| C5 | Birthdays and anniversaries: read, write, edit, card line with age | done R1 |
| C6 | Postal addresses: read, write, edit, open in maps, copy/share | done R1 |
| C7 | vCard BDAY, ANNIVERSARY, ADR import/export | done R1 |
| C8 | Find duplicates and Merge with confirm and Undo | done R1 |
| C9 | Upcoming birthdays (Favorites section + page, Wish SMS) | done R1 |
| C10 | Alphabet scrubber | done R1 |
| C11 | Select mode in the Contacts list: select all, export selection (.vcf file), share (.vcf), add to group, star/unstar, delete (confirm + Undo) | done R2 |
| C12 | Export one contact to a .vcf file ("save as") from the card Share menu | done R2 |
| C13 | Photo crop / rotate before setting (pan + zoom square crop, quarter turns) | done R2 |
| C14 | Set photo from the camera (`ACTION_IMAGE_CAPTURE` into the app cache) | done R2 |
| C15 | Recently added / edited list (`CONTACT_LAST_UPDATED_TIMESTAMP`) | done R2 |
| C16 | Copy whole contact (text) and Copy as vCard from the card Share menu | done R2 |
| C17 | WhatsApp chat chip per number row when that number is on WhatsApp | done R2 |
| C18 | Websites, nickname, job title: read, write, edit, vCard URL/NICKNAME/TITLE, tap opens browser | done R2 |
| C19 | Scan to add: decode a contact QR from a picked image (pure Rust decoder, licence-checked) | backlog R3 |
| C20 | Trash: deleted contacts kept locally as drafts for 30 days, Restore, Empty Trash (confirm) | backlog R3 |
| C21 | Contact history: last calls and SMS with this contact (local providers, read-only, permission) | backlog R3 |
| C22 | Birthday reminders: local notification on the morning of (opt-in) | backlog R3 |
| C23 | Default number / email: set / clear `IS_SUPER_PRIMARY` from the field sheet | backlog R3 |
| C24 | Cleanup filter chips: no number, no photo, no group | backlog R3 |
| C25 | Group utilities: Message group (SMS to all), Email group, export group as .vcf | backlog R3 |
| C26 | Favorites custom order (drag) using `contacts_view::reordered_keys` | backlog R4 |
| C27 | Fuzzy search: initials ("ay" -> "Ayse Yilmaz") and number fragments in any format | backlog R4 |
| C28 | Import from clipboard: paste a vCard or "Name +90..." text to create a contact | backlog R4 |
| C29 | Contact count and storage summary per account in Settings | backlog R4 |
| C30 | Quick actions on row long-press (Call, Message, Copy number, Share, Star, Select) | backlog R4 |
| C31 | Open a .vcf shared to the app (`ACTION_VIEW`/`SEND` text/x-vcard intent filter) -> import preview | backlog R4 |
| C32 | Copy number in international / national format choice (`number_format`) in the field sheet | backlog R4 |

## Steps (round 2)

Every step leaves all checks green: `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings
&& cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p
rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
If `df` shows under 6 GB free (7.6 GB at planning time), `rm -rf target/debug/incremental` first. One
focused commit per step, prefix `Rovsti contacts: `. Do not touch `apps/phone` or `apps/messages` source
(a shared `rovsti_core`/`rovsti_android` change must keep them compiling; fix struct literals only).
Read `.claude/skills/liquid-glass-gpui/SKILL.md` before S5-S8; use only GPUI APIs already used in
`apps/contacts` (`on_long_press`, `on_click`, `on_pinch`, `ListState`, `RenderImage`, `cx.open_url`).

### S1. Pure: selection, contact text, recent list (C11, C12, C15, C16)
- New `crates/rovsti_core/src/contact_select.rs` (`pub mod contact_select;` in `lib.rs`):
  - `pub const MAX_SELECTED: usize = 500;`
  - `pub fn export_ids(selected: &BTreeSet<i64>, hidden: Option<i64>) -> Vec<i64>` (ascending, drops the
    pending-delete id).
  - `pub fn pick_drafts(all: &[ContactDraft], ids: &[i64]) -> Vec<ContactDraft>` (order of `all`).
  - `pub fn add_to_group_change(group_members: &[i64], selected: &[i64]) -> SelectionChange` (add only
    those not yet members, `remove` empty) and `pub fn added_notice(n, group_title) -> String`
    ("Added 3 contacts to Family", "They are all in Family already").
  - `pub fn exported_notice(n) -> String` ("Saved 1 contact to the file", "Saved 4 contacts to the file")
    and `pub fn selection_file_name(n, today: (i32,u8,u8)) -> String` delegating to `vcard::export_name`
    semantics for n > 1.
  - `pub fn delete_confirm(n) -> (String, String)` (title "Delete 3 contacts?", body "They are removed
    from the phone and from their accounts.").
- `contact_edit.rs` (or a new `contact_text.rs`): `pub fn full_text(d: &ContactDraft) -> String`: name,
  org/title line, each number "Mobile: +90 ...", each email, each address (one line, ", " joined),
  events via `contact_dates::card_line`-like "Birthday: 12 May 1994", note last; empty sections skipped.
  `pub fn copied_contact_notice() -> &'static str` ("Contact copied"), `copied_vcard_notice()`
  ("vCard copied").
- `wire.rs` `WireContact`: `#[serde(default, skip_serializing_if = "Option::is_none")] pub updated_ms:
  Option<i64>` (`CONTACT_LAST_UPDATED_TIMESTAMP`). Fix every struct literal the compiler flags in the
  workspace with `updated_ms: None` (or `..Default::default()` where `Default` is derived).
- `contacts_view.rs`: `pub fn recent<'a>(contacts: &'a [WireContact], now_ms: i64, within_days: u16,
  max: usize) -> Vec<&'a WireContact>` (deduped by id, newest first, ties by id desc, `None` dropped) and
  `pub fn recent_when(updated_ms, now_ms, tz_offset_s) -> String` ("Today", "Yesterday", "3 days ago",
  "12 May").
- Tests: export_ids drops hidden and sorts; add_to_group_change skips members and empty selection;
  notices singular/plural; full_text field order, skips empties, includes address and birthday; recent
  window, ordering, dedup, missing timestamps; recent_when day boundaries with tz offset; old
  `WireContact` JSON without `updated_ms` parses.
- Acceptance: `cargo test -p rovsti_core contact_select contacts_view wire` green; no app behaviour change.

### S2. Pure: websites, nickname, job title (C18)
- `contact_edit.rs` `ContactDraft`: `#[serde(skip_serializing_if = "String::is_empty")] pub nickname:
  String`, `pub title: String` (job title), `#[serde(skip_serializing_if = "Vec::is_empty")] pub
  websites: Vec<String>`. `clean()` trims, clips (reuse the existing clip length), dedups websites by
  lowercase, caps at `MAX_FIELDS`. `display_name()` unchanged (nickname never replaces the name).
  `pub fn web_url(s: &str) -> Option<String>`: adds `https://` when no scheme, accepts only `http`/
  `https`, rejects whitespace inside and empty host.
- `contact_dupes::merge`: keeper's nickname/title unless empty, websites unioned.
- `vcard.rs`: parse `URL` (any TYPE), `NICKNAME` (first of a comma list), `TITLE`; write them in 3.0
  (`NICKNAME:`, `TITLE:`, `URL:` per website, escaped). Update the module doc.
- `contact_text::full_text` (S1) adds nickname ("Nickname: Annie"), title (with org: "Engineer, Acme"),
  websites.
- Tests: `web_url` cases (`example.com`, `HTTP://x.y`, `ftp://x`, `a b.com`, empty); vCard round trip of
  all three; `NICKNAME:Annie,Ann` takes "Annie"; old draft JSON parses; merge unions websites.
- Acceptance: `cargo test -p rovsti_core` green.

### S3. Pure: crop and rotate plan (C13, C14)
- `contact_photo.rs`: `PhotoPlan` gains `#[serde(default)] pub turns: u8` (quarter turns clockwise,
  0..=3, applied to the source before the crop; old JSON = 0). `plan(w, h)` keeps `turns: 0`.
  `pub fn rotated_size(w, h, turns) -> (u32, u32)`.
  `#[derive(Clone, Copy, Debug, PartialEq)] pub struct CropView { pub turns: u8, pub zoom: f32 /* >= 1 */,
  pub cx: f32, pub cy: f32 /* crop centre, 0..1 of the rotated image */ }` with `Default` (centred, zoom
  1, turns 0), `fn rotate_right(self) -> Self` (turns + 1 mod 4, recentre), `fn pan(self, dx_px, dy_px,
  view_side_px, w, h) -> Self`, `fn pinch(self, factor) -> Self` (zoom clamped 1..=`max_zoom(w,h,turns)`
  so the crop side never drops below `MIN_SIDE`), both clamping the centre so the square stays inside.
  `pub fn plan_for(w, h, view: CropView) -> Result<PhotoPlan, PhotoError>`: square side =
  `min(rw, rh) / zoom`, `x/y` from the clamped centre in rotated coordinates, `out = side.min(MAX_SIDE)`.
  `pub fn camera_file_name(now_ms: i64) -> String` ("camera-<ms>.jpg", safe for the share provider).
- Tests: default view equals `plan`; rotate swaps sizes; four rotations return to the start; pan clamps
  at every edge; pinch clamps at 1 and max; `plan_for` stays inside the image at every corner and zoom;
  TooSmall when max zoom would cross `MIN_SIDE`; old `PhotoPlan` JSON without `turns` parses.
- Acceptance: `cargo test -p rovsti_core contact_photo` green.

### S4. Android glue and backend wrappers (C12, C13, C14, C15, C18)
- Kotlin `ContactsQuery.kt`: add `Contacts.CONTACT_LAST_UPDATED_TIMESTAMP` to `CONTACT_PROJECTION` and
  carry it into `ProviderJson.PhoneRow` -> JSON key `updated_ms` (only when > 0). Check every reader of
  the projection by index before appending (append last).
- Kotlin `ContactsEdit.kt`: add `Website`, `Nickname` to `EDITED_MIMES`; `DraftBuilder.add` reads
  `Website.URL` into `websites`, `Nickname.NAME` into `nickname`, `Organization.TITLE` into `title`
  (Organization is already read for `org`: read `TITLE` from the same row); `insertData` writes them
  (`Organization.TITLE` on the org row, which must be written when either org or title is set); `toJson`
  emits them only when non-empty.
- Kotlin `ContactPhotos.kt`: apply `turns` with `android.graphics.Matrix.postRotate(90f * turns)` on the
  decoded bitmap before the crop (`PhotoPlan` coordinates are in rotated space). Also honour EXIF
  orientation only if it is already handled; do not add a new library.
- Camera: Kotlin `Documents.kt` `startPhotoCapture(activity)`: create `cache/share/camera-<ms>.jpg`,
  `Intent(MediaStore.ACTION_IMAGE_CAPTURE)` with `EXTRA_OUTPUT` = a `RovstiShareProvider` URI and
  `FLAG_GRANT_WRITE_URI_PERMISSION`; extend `RovstiShareProvider.openFile` to allow mode "w"/"wt"
  **only** for names matching `camera-<digits>.jpg` (pure helper `MediaShare.writable(name)`), keep "r"
  for the rest. On RESULT_OK reply `document_result` op `contact_photo` with `path`, `mime =
  "image/jpeg"` (same as the photo picker, so the existing hand-back runs); on cancel ok=false without
  error. No `CAMERA` permission in the manifest (declaring it would make the intent need it).
  `ActivityNotFoundException` -> `false`. Rust: `rovsti_android::documents::capture_photo() ->
  Result<bool>` (`bridge::call_bool0("capturePhoto")`, Unsupported on desktop) + the `RovstiBridge`
  static.
- `contacts_edit::set_photo` doc mentions `turns`. No signature change (plan is JSON).
- `apps/contacts/src/backend.rs`: `start_capture_photo()`, `change_photo_with(id, path, view:
  CropView) -> Result<(), String>` (reads size via `photo_size`, `plan_for`, `set_photo`; existing
  `change_photo` becomes `change_photo_with(.., CropView::default())`), `start_export_named(name, text)`
  (`documents::export_contacts`), `export_selection(ids, hidden) -> Result<(String, usize), String>`
  (`pull_full` + `pick_drafts` + `vcard::write`). Desktop sample: give one sample a nickname, title,
  website and `updated_ms` values (fixed) for two contacts.
- Tests: desktop wrappers return the Unsupported words; sample drafts still `finish()`; sample still has
  exactly one duplicate group; `export_selection` on desktop writes the chosen sample cards only.
- Acceptance: all checks green incl. the three aarch64 checks.

### S5. Card: export file, copy contact, WhatsApp per number, new fields (C12, C16, C17, C18)
- `detail.rs`: `ShareChoice` gains `SaveFile` ("Save to file (.vcf)", `backend::start_export_named(
  vcard::export_name(&[draft], today), vcard::write(&[draft]))`, reply handled by the existing
  `document_result` export path in `transfer.rs`: notice `exported_notice(1)`), `CopyText` ("Copy
  contact", `telecom::copy_text(full_text)`, pill `copied_contact_notice`) and `CopyVcard` ("Copy as
  vCard"). Update `share_choices` and its test (`SaveFile`, copies always offered; QR rule unchanged),
  and the id match at line ~301.
- Number rows: when the card's WhatsApp section is connected and a number maps to a JID (reuse the
  lookup behind `card_jid`, generalised to `jid_for_number(&str) -> Option<String>`, number-keyed, no
  per-frame work: compute once per card open / chats refresh into `card_wa_numbers: Vec<Option<String>>`),
  show a small "WhatsApp" chip that calls the existing `wa_handoff(false, jid, name, cx)`. Pure helper
  `wa_chip_index(numbers, jids) -> Vec<bool>` with a test (only numbers on WhatsApp, at most one chip per
  JID).
- New rows: nickname under the name (secondary text), title in the org line ("Engineer · Acme"),
  websites as info rows: tap -> `cx.open_url(web_url(..))` (pill "Can't open that link" when `None`),
  long-press -> field sheet Copy / Share. `FieldTarget::Website(String)` with actions
  `[OpenLink, Copy("Copy Link"), Share("Share Link")]`; extend the `actions` test.
- `editor.rs`: Nickname and Job title single fields next to Organisation; "Websites" section (add /
  remove rows like emails). Reuse `field.rs`; no new widgets.
- Acceptance: all checks green; desktop card shows the sample nickname/title/website and the 6 share
  entries.

### S6. Select mode in the Contacts list (C11)
- `main.rs`: `select: Option<Selection<i64>>` on `ContactsApp`. Enter by long-press on a contact row
  (`contact_row` gains `.on_long_press`, as in `detail.rs`) or a "Select" button in the Contacts header;
  in select mode a tap toggles (checkmark in place of the trailing mark), the tab bar is replaced by a
  glass action bar, the header shows `multi_select::title(n)` with Cancel and "Select all"
  (`select_all` over the visible rows, capped by `MAX_SELECTED` -> `cap_notice`). Back / Escape leaves
  select mode first (`go_back`/`can_go_back`). Leaving the Contacts tab clears it; `retain` on reload.
- New `apps/contacts/src/select.rs` holding the actions: Share (`export_selection` -> `backend::share_vcf(
  selection_file_name(..), text)`), Save to file (`start_export_named`), Add to group (small sheet listing
  `groups_of(groups, account)` per account of the selection; `add_to_group_change` ->
  `set_group_members`, pill `added_notice`; contacts from another account are skipped and counted in the
  pill), Star / Unstar (`star_all` decides; each `save` with `starred` toggled on the background
  executor, pill `starred_notice`), Delete -> `Confirm::DeleteMany { ids }` in `confirm.rs` (destructive
  button, `delete_confirm` texts) -> hide the rows and delete after the Undo window like `PendingDelete`
  (generalise it to `ids: Vec<i64>`; the single-contact path passes one id; `UNDO_NOTICE` replaced by
  `deleted_notice(n)`; extend `keeps_undo` tests). All work runs off the UI thread; failures show
  `android_words`.
- Tests: pure `select_bar_actions(n, has_groups) -> Vec<SelectAction>` (empty selection disables all
  but Cancel/Select all; Add to group hidden without groups); `Confirm::DeleteMany` texts;
  `PendingDelete` with several ids hides all of them in `export_ids`.
- Acceptance: all checks green.

### S7. Photo crop / rotate and camera (C13, C14)
- New `apps/contacts/src/photo_crop.rs`: after a pick or capture (`document_result` op
  `contact_photo` in `transfer.rs`, which today calls `change_photo` directly; editor flow in
  `editor.rs` likewise) open a full-screen crop page instead of saving: the picked image (load with
  `image` like `backend::contact_photo_at`, downscaled to <= 1440 px for display, background executor)
  rotated by `turns`, a square glass frame, the outside dimmed. Drag -> `CropView::pan`, `on_pinch` ->
  `CropView::pinch`, double-tap toggles zoom 1 / 2; bottom glass bar: Rotate (quarter turn), Reset,
  Cancel, Use photo. Use photo -> `backend::change_photo_with(id, path, view)` (or, for a new contact
  in the editor, keep `(path, view)` until Save) -> pill "Photo updated"; Cancel deletes nothing on the
  phone, pill none. Reuse the image-rect math from `photo_viewer.rs` (move `image_rect` to a shared
  `pub(crate)` fn rather than copying).
- Photo source menu everywhere a photo is chosen (editor photo button, viewer Edit menu): "Take photo"
  (`backend::start_capture_photo`, failure -> pill "No camera app found" / Unsupported words) and
  "Choose from gallery" (existing), plus "Remove photo" where it exists today.
- Tests: pure `frame_rect(view_side, w, h, view) -> (x, y, side)` maps `plan_for` to screen space and
  matches it at turns 0..3; `photo_source_choices(has_photo) -> Vec<PhotoSource>`.
- Acceptance: all checks green; on desktop the crop page opens with the sample photo through a debug-free
  path (desktop pick returns Unsupported, so expose the crop page from the viewer's Edit menu with the
  current full photo as "Crop current photo" too: useful on device as well).

### S8. Recently added page, docs, backlog (C15)
- Contacts tab header: a "Recently added" glass row (only when no search text and `recent` is not
  empty, `within_days = 30`, max 50) showing the count; it opens a page (same pattern as the Upcoming
  page in `upcoming.rs`) listing rows with `recent_when` as the subtitle; tap opens the card. Rows built
  once per pull, never per frame.
- Docs: `docs/roadmap.md` Rovsti Contacts section ticks C11-C18 ("done without a device"), adds the camera
  provider write rule to `docs/architecture.md` where `RovstiShareProvider` is described; update this
  file's backlog statuses to `done R2` for what landed.
- Tests: pure `show_recent(rows_query, recent_len) -> bool`.
- Acceptance: all checks green.
