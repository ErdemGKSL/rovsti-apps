# UX completeness pass: Rovsti Contacts (round 1)

Base: `305eb8bcfc4477c59bb3e0938f3f3adab5cc5a92` (main, "Rovsti messages: UX gap review fixes").
No stash, clean tree.
Scope: `apps/contacts`, `crates/rovsti_core` (contact_card, contact_edit, contacts_view),
`crates/rovsti_android` (contacts_edit, telecom seams and Kotlin `ContactsEdit` / `RovstiBridge`).
No new product features and no backends. The app already intends these flows: groups with
members, Favorites, number-less contacts, export of the whole book, and a contact card that
acts on its own fields.

Earlier Contacts plans (`contacts.md`, `contacts-r2.md`) closed the roadmap items, and
`docs/ux-audit.md` (C1 to C20) fixed the quality issues. This pass audits from the user's side.

GPUI rules: use only APIs the workspace already uses. These are `cx.write_to_clipboard(ClipboardItem::new_string(..))`
(Phone `block_ui.rs:494`), `.on_long_press(..)` (Messages `conversation.rs:1927`, gpui
`elements/div.rs:388`), `rovsti_ui::sheet::Sheet` (Contacts `photo_sheet`), `ListState`/`list`
and `glass_icon_button`. The icons `LucideCopy`, `LucideEllipsis`, `LucideMail` and
`LucideUserPlus` are already used in the workspace.

## Audit: gaps found (from the point of view of a user holding the phone)

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| G1 | **High** | **You cannot add people to a group from the group.** You can create a group, but its page has no "Add contacts". The empty state says to open each contact's Edit screen, tick the group and save, one contact at a time. You cannot remove a member from the group page either. This is the same shape as the Stops pin bug: the model supports membership, but the group has no UI path to it. A new group also does not open after it is created, and nothing confirms it. | `groups.rs::group_page_item`, `save_group_edit` |
| G2 | **High** | **A starred contact without a number never shows in Favorites.** `contact_card::favorites` filters `!c.numbers.is_empty()`. Number-less contacts can be created and starred (the card's star works), but the Favorites tab drops them silently. | `rovsti_core/src/contact_card.rs:59` |
| G3 | **High** | **Export silently leaves out contacts without a number.** Kotlin `ContactsEdit.fullJson` keeps only `numbers.length() > 0`. The app lists, creates and imports email-only and org-only contacts, but "Export to a .vcf file" loses them. The "Saved N contacts" count hides the loss. | `ContactsEdit.kt:fullJson`, `backend.rs::export_text` doc |
| G4 | **High** | **The card's fields cannot be copied or used one by one.** Tapping a number calls it. You cannot copy a number, text a number that is not the default, or share one number. Email rows do nothing (no compose, no copy). The note cannot be copied. A contact with only an email has every action button dimmed. | `detail.rs::detail` |
| G5 | Medium | **Favorites has no "+".** The only way to add a favorite is to open each contact and tap the star. There is no way to pick several, and there is no feedback after a star change: the star icon's dot only shows small. | `main.rs::favorites_item`, `toggle_star` |
| G6 | Medium | **The card's "Groups · In: Family, Work" row is dead text.** Tapping a group name does not open that group. | `detail.rs` groups section |
| G7 | Medium | **"Save new contacts to" is remembered but never shown.** `ContactsPrefs.save_to` is persisted, but only the last new contact's "Save to" tap changes it. Settings has no row to see or change it. | `main.rs::settings_tab` |
| G8 | Low | **Stale empty-state texts.** "Contacts with a phone number appear here." is wrong since number-less contacts are listed. "Make a group, then add contacts to it from their Edit screen." and "Add contacts to this group from their Edit screen." point at the obscure path that G1 replaces. In the editor, an account with no groups shows no Groups section and no hint. | `main.rs::contacts_item`, `groups.rs`, `editor.rs` |
| G9 | Low | **Group rename gives no feedback** (no "Group renamed." pill). | `groups.rs::save_group_edit` |
| G10 | Info (deferred) | **Delete contact has a confirmation but no Undo.** Re-creating from `open_draft` would lose the photo, the synced identity and the id. This needs the `NoticePill` Undo migration and is not plannable as glue. Re-check in round 2. | `confirm.rs::delete_contact` |
| G11 | Info (not planned) | Several things are not in the model and would be new features: choosing the default number (it needs a new `IS_SUPER_PRIMARY` write path), searching by email or organisation (`WireContact` carries neither), sharing a vCard file instead of text, and quick actions on list rows. Already complete, with feedback: create, edit and delete contacts, photos, vCard import and export, WhatsApp chat, call, photo and settings, name format and sort, permission recovery, and the discard-changes confirm. | — |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
(if `df` shows under 6 GB free, `rm -rf target/debug/incremental` first).
Commit each step on its own with a focused message ("Rovsti Contacts: ...").

### U1. rovsti_core pure logic (+ tests) (done, `1c4220c`)

Changes in `crates/rovsti_core/src/contact_card.rs`:
- `favorites` keeps every starred contact (drop the `!c.numbers.is_empty()` filter) and its
  doc says "Starred contacts, in the order given." Phone does not call it (Phone uses
  `contacts_view::order_favorites`), so only Contacts changes.
- Update or add a test: a starred number-less contact is listed, and an unstarred one is not.

Additions to `crates/rovsti_core/src/contact_edit.rs`:
- `#[derive(Clone, Debug, Default, PartialEq, Eq)] pub struct SelectionChange { pub add: Vec<i64>, pub remove: Vec<i64> }`
- `pub fn selection_change(initial: &[i64], chosen: &BTreeSet<i64>) -> SelectionChange`:
  - `add` is the chosen ids that were not in `initial`, and `remove` is the ids in `initial`
    that are not chosen.
  - Both lists are ascending with no repeats, and duplicates in `initial` are ignored.
  - `SelectionChange::is_empty(&self) -> bool`.
- `#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)] pub struct MembersPayload { #[serde(default)] pub added: u32, #[serde(default)] pub removed: u32, #[serde(default)] pub skipped: u32 }`
  This is the answer of the new seam in U2. `skipped` counts contacts that have no raw contact
  in the group's account.
- `pub fn members_summary(title: &str, done: &MembersPayload) -> String`:
  - "Added 2 to Family." / "Removed 1 from Family." /
    "Added 1 to Family and removed 2."
  - When `skipped > 0`, it appends " 1 contact is in another account and can't join." (plural
    "3 contacts are ...").
  - When nothing changed, it reads "No changes."
  - Counts use "1 contact" / "N contacts" wording.

Tests:
- `selection_change`: add only, remove only, both, no change, and duplicates in `initial`.
- `members_summary`: every wording branch, including the plural and singular of `skipped`.
- `MembersPayload` decodes `{"added":1}` with the missing fields set to 0.

### U2. Android seams: group members, email compose, export of every contact (done, `12a27a8`)

Additions to `crates/rovsti_android/src/contacts_edit.rs`:
- `pub fn set_group_members(group_id: i64, change_json: &str) -> Result<String>` calls
  `bridge::call_string("setContactGroupMembers", &[Arg::Str(&group_id.to_string()), Arg::Str(change_json)])`.
  `change_json` is `SelectionChange`-shaped (`{"add":[..],"remove":[..]}`), and the answer is
  `{added, removed, skipped}` in the usual envelope.
- Add it to the `desktop_is_unsupported` test.

Kotlin `crates/rovsti_android/kotlin/dev/rovsti/bridge/ContactsEdit.kt`:
- `fun setGroupMembers(context, groupId: Long, json: String): String = guard { ... }`:
  - Read the group's account (query `Groups` by `_ID` for `ACCOUNT_TYPE`/`ACCOUNT_NAME` and
    use `accountKey`). An unknown group returns `not_found`.
  - **Add:** for each contact id, find a raw contact of that contact in the group's account
    (`RawContacts.CONTACT_ID = ? AND account matches`, `DELETED = 0`). With none, `skipped++`.
    When that raw contact already has the membership, skip it. Otherwise insert a
    `GroupMembership` row.
  - **Remove:** delete `Data` rows with `MIMETYPE = GroupMembership` and
    `GROUP_ROW_ID = groupId` for raw contacts of that contact id, then `removed++`.
  - One `applyBatch` call, then `ProviderJson.ok({added, removed, skipped})`.
- `fullJson` exports every contact that has any data. Drop the
  `if (b.numbers.length() > 0)` filter, keep the rest, and change the doc to "every contact".
  Rust `export_text` already drops nameless drafts with `validate()`.

Kotlin `RovstiBridge.kt`:
- `@JvmStatic fun setContactGroupMembers(groupId: String, json: String): String`, written in the
  same style as `saveContactGroup`.
- `@JvmStatic fun sendEmailTo(address: String): Boolean = start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", address, null)))`.

Addition to `crates/rovsti_android/src/telecom.rs`:
- `pub fn send_email_to(address: &str) -> Result<bool>` (`bridge::call_bool_str("sendEmailTo", address)`).
- Its desktop test asserts `Unsupported`.

Update the doc comments of `contacts_edit::full` and `apps/contacts/src/backend.rs::export_text`
to say "every contact". `start()` does not call `resolveActivity`, so the manifest needs no new
`<queries>`. Add a one-line note to `docs/android.md` for the new bridge methods.

### U3. Contact picker and "Add or remove contacts" on a group page (G1, G9) (done, `9d0f2f1`)

New `apps/contacts/src/picker.rs`, a full-page multi-select:
- **State:** `pub(crate) enum PickFor { Group(i64), Favorites }` and
  `pub(crate) struct Picker { for_: PickFor, initial: Vec<i64>, chosen: BTreeSet<i64>, field: Entity<TextField>, saving: bool }`,
  with `ContactsApp.picker: Option<Picker>` and `picker_list: ListState` (made with `page_list()`).
- **Header** (item 0): a "Cancel" pill, then the title ("Add to Family" / "Favorites"), then a
  "Done" pill that reads "Saving…" while saving. Below them sit a search field (reuse the
  `search_field` look with the picker's own `TextField`) and a line saying "N selected".
- **Rows:** `contacts_view::build_rows_styled` over `index.search(query)`, or over
  `contacts_view::ordered(index.all(), style)` when the query is empty. Each row is a
  `contact_row` look with a trailing `LucideCheck` when chosen. A tap toggles the id. Rows are
  rebuilt on query change through an `observe` on the picker field, like the main search.
- **Back and tab bar:** `can_go_back`/`go_back` close the picker first; a dirty picker closes
  without asking, since nothing is written until Done. The tab bar is hidden while the picker
  is open, like the editor (`editing` becomes `editing || picker.is_some()`).

Group page:
- A `LucideUserPlus` `glass_icon_button` with id `group-members` sits next to Rename and Delete.
- A `tap_row` "Add or Remove Contacts" sits under the title. It also shows in the empty
  state, whose text becomes "Add people with Add or Remove Contacts."
- Both open `Picker { for_: Group(id), initial: group.members (only ids in the book), ... }`.

On Done:
- Call `contact_edit::selection_change`. An empty change closes the picker with no write.
- Otherwise call the new `backend::set_group_members(group_id, &SelectionChange) -> Result<MembersPayload, String>`
  on the background executor. It uses `contacts_edit::set_group_members` and `decode`. On
  desktop, `Unsupported` maps to the usual "needs a phone" words.
- On `Ok`, set `notice = members_summary(title, &payload)`, close the picker and `reload`.
- On `Err`, keep the picker, clear `saving`, and set the notice "Can't change the group: {words}."

In `save_group_edit`:
- When creating, the `Ok(id)` result opens that group (`open_group(id)` once the pull lands;
  reuse a `pending_group: Option<i64>` handled in `reload` next to `pending_open`). The notice
  is "Group created. Add people with Add or Remove Contacts."
- When renaming, the notice is "Group renamed."

Tests (in `picker.rs`, pure helpers only):
- `fn selected_words(n: usize) -> String`: "None selected", "1 selected", "3 selected".
- The initial members are filtered to ids that exist in the book.

### U4. Favorites "+" and star feedback (G2 UI, G5) (done, `cf25757`)

- The Favorites `large_title` trailing is a `LucidePlus` `glass_icon_button` with id
  `favorites-add`, shown when `Book::Ready`. It opens `Picker { for_: Favorites, initial: ids of starred contacts }`.
- **Done for Favorites:** `selection_change`, then one background task that calls
  `rovsti_android::providers::set_starred(id, true/false)` for each changed id.
  - Count the failures; `Unsupported` on desktop counts as success and the change stays local.
    To keep the change local, apply it to `book` first, the way `toggle_star` does. Factor out
    `fn set_starred_local(&mut self, ids: &[i64], starred: bool)`.
  - The notice is "Added 2 to Favorites." / "Removed 1 from Favorites." / both, then `reload`.
    Put the wording in a pure tested helper `favorites_summary(added, removed) -> String` in
    `picker.rs`.
- **`toggle_star`:** on success the notice is "Added to Favorites." / "Removed from Favorites."
  On desktop's `Unsupported` it shows the same wording.
- **Favorites empty state:** "Tap + to choose favorites, or tap the star on a contact."
- Number-less starred contacts now show in Favorites through U1. Check that the Favorites row
  subtitle and avatar still draw with no number (it is the same `Row` as the Contacts tab).

### U5. The card acts on each field (G4, G6) (done, `c16bdf0`)

In `apps/contacts/src/detail.rs` and `main.rs`:

- **Number actions sheet.** Add `ContactsApp.field_sheet: Sheet` and
  `field_target: Option<FieldTarget>`, with
  `enum FieldTarget { Number { raw: String, display: String }, Email(String), Note(String) }`.
  The sheet is drawn like `photo_sheet_view`, ticks in the render loop like `photo_sheet`,
  and is closed by Back first (add it to `can_go_back`/`go_back`).
  - **Number:** Call (`telecom::place_call`), Message (`telecom::send_sms_to(raw)`, so a
    non-default number can be texted), Copy Number, Share Number (`telecom::share_text(display)`).
  - **Email:** Send Email (`telecom::send_email_to`) and Copy Email.
  - **Note:** Copy Note.
  - Copy uses `cx.write_to_clipboard(ClipboardItem::new_string(..))` and the notice "Copied."
- **Number rows:** a tap still calls. A trailing `LucideEllipsis` hit area (`metrics::MIN_HIT`,
  id `("number-more", i)`) and `.on_long_press` open the Number sheet.
- **Email rows:** a tap opens Send Email through `act("send an email", ..)`. A trailing
  ellipsis and long press open the Email sheet. `info_row` gets an optional `on_click` variant,
  or build email rows inline.
- **Note row:** long press and a trailing `LucideCopy` open the Note sheet.
- **Action buttons:** when the contact has no number but has an email (known from `open_draft`),
  a fourth "Email" `action_button` (`LucideMail`) to the first address is shown; it is not
  dimmed.
- **Groups section:** one `tap_row` per group (`("card-group", i)`, `LucideUsers`, title) that
  closes the card, selects `GROUPS_TAB` (`tabs.jump_to`) and calls `open_group(id)`. It
  replaces the single "In: a, b" text row.
- **Pure helper** (tests in `detail.rs`): `fn first_email(draft: Option<&ContactDraft>) -> Option<String>`,
  which skips blank values.

### U6. Settings "New contacts go to" and stale texts (G7, G8) (done, `b042ff8`)

- **Settings:** a "New contacts" section appears when `self.accounts.len() > 1`. It has one
  `tap_row` "Save to" whose trailing text is `account_label(default_account(..))`. A tap calls
  `editor::next_account`, sets `save_to`, calls `save_prefs` and shows the notice
  "New contacts go to {label}."
- **Contacts empty state:** "Tap + to add someone, or import a .vcf file in Settings."
- **Groups tab empty state:** "Make a group, then add people to it from the group's page."
- **Editor:** when the account has no groups, show a Groups section with one Plain footnote row:
  "No groups in this account yet. Make one in the Groups tab."
- Update the module doc of `groups.rs` ("A contact joins a group in its edit form or from the
  group's page").

### U7. Docs and plan status (done, this commit)

- `docs/roadmap.md` (Rovsti Contacts):
  - Tick a "UX completeness pass" line that lists the group picker, Favorites +, the card
    field actions, export of every contact and the Save-to setting, each "(done without a
    device)".
  - Add an open item "Check the group member write and the mailto handoff on a phone".
- `docs/android.md`: the `setContactGroupMembers` and `sendEmailTo` bridge methods.
- This file: mark U1 to U7 done, with their commit hashes.

## Left for round 2 (re-audit)

- G10 Undo for Delete Contact: decide whether to adopt `rovsti_ui::notice_pill::NoticePill` in
  Contacts.
- Re-check the flows end to end after U3 to U6: the picker with a large book, Back from each
  layer, and the notices.
