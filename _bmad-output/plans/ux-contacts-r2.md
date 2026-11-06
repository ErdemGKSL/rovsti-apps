# UX completeness pass: Rovsti Contacts (round 2)

Base: `ee44136a360a574af815ed65fe0b21d3f651458c` (main, "Rovsti contacts: UX gap review fixes").
Clean tree, no stash. Round 1 (`ux-contacts.md`, U1 to U7) is done: group picker, Favorites "+",
per-field card actions, export of every contact, the Save-to setting, and the stale texts.

This round re-audits the app end to end from the user's side (Contacts, Favorites, Groups,
Settings tabs; card, editor, picker, import and export; Back from every layer). It also
settles G10, the Undo for Delete Contact that round 1 left open.

GPUI rules: use only APIs the workspace already uses. Those are `cx.spawn` and
`background_executor().timer(..)` (Contacts `keep_notice_timer`), `observe_window_activation`
(Contacts `new`), `crate::ui::pill` and `glass_pill`, `tap_row`, `section` and `empty_state`.
Add no new GPUI calls.

## Audit: gaps found (round 2)

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| R1 | **High** | **Importing a file you exported adds every contact that has no number a second time.** `import_plan::plan` builds duplicate keys only from phone numbers, so a card with no number never counts as "already in your address book". Since U2, export writes email-only and org-only contacts, so export followed by import duplicates all of them. The confirm card says "Add 12 contacts?" when nothing is new. | `crates/rovsti_core/src/import_plan.rs` |
| R2 | Medium | **Delete Contact cannot be undone.** A confirm card exists, but one wrong tap on "Delete" loses a contact and its photo for good, and the delete also reaches the synced account. Messages and Stops offer Undo after their deletes, so Contacts is the odd one out. This is G10 from round 1, now decided: keep the confirm and defer the delete for 5 s behind an Undo button. | `confirm.rs::delete_contact`, `main.rs::notice` |
| R3 | Medium | **On a phone with several accounts, groups hide their account.** A new group silently goes to the "Save to" account, and the New Group form has no way to see or change that. The Groups tab and the group page do not show the account. The picker then lists every contact, and members from another account are only reported afterwards as "N contacts are in another account and can't join". A user with Google contacts who makes "Family" on the phone account has no way to understand why nobody can join. | `groups.rs::start_group_edit`, `save_group_edit`, `build_group_items`, `group_page_item`, `picker.rs::picker_item` |
| R4 | Medium | **Search does not find the email shown on the row.** A contact with no number shows its email as the row subtitle (`WireContact.email`), but `ContactIndex::search` only matches names and digits. Typing "ayse@" finds nothing, even though that text is on screen. | `crates/rovsti_core/src/search.rs::search` |
| R5 | Low | **Import wording is wrong.** The card says "N contacts without a name or a phone number". The cards that are really skipped are those with no name, a bad number or a bad email (`DraftError`). Cards with no number import fine since round 1. The doc of `export_contacts` still reads "Reads every contact with a number". | `confirm.rs::import_summary`, `transfer.rs` |
| R6 | Info (not planned) | Still new features, not gaps: choosing the default number (needs a new `IS_SUPER_PRIMARY` write path), importing into a chosen account (Kotlin `importJson` is phone-only by design and the card says "saved on this phone"), sharing a vCard file, and quick actions on list rows. Re-checked and complete: Back through every layer (confirm, sheets, picker, editor, group edit, card, group page, WhatsApp, tabs, search), empty states on all tabs and in the picker, the notices after each write, permission recovery, Try Again, and discard-changes. | — |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
(if `df` shows under 6 GB free, `rm -rf target/debug/incremental` first).
Commit each step on its own with a focused message ("Rovsti contacts: ...").

### V1. rovsti_core: import dedup for cards without a number, and search by email (R1, R4) (done, 4457acb)

In `crates/rovsti_core/src/import_plan.rs`:
- Keep the number keys. When a contact has **no** number key, give it these fallback keys:
  - one key per email, `(folded name, "@" + lowercase trimmed email)`. The `@` prefix keeps
    them apart from digit keys. Existing contacts use `WireContact.email`, and incoming cards
    use every non-blank `card.emails[..].value`.
  - with no email either, one name-only key `(folded name, String::new())`.
- Write it as a private
  `fn fallback_keys<'a>(name: &str, emails: impl IntoIterator<Item = &'a str>) -> Vec<(String, String)>`.
  Call it from both the `existing` side and the `incoming` side when `keys(..)` is empty, so a
  card with a number never matches through its name alone.
- Update the module doc: "A card with no number counts as there when a contact with the same
  name and no number shares its email (or neither has an email)."
- Tests:
  - An email-only card matching an existing number-less contact with the same name and email
    is a duplicate, and the match ignores case.
  - The same name with a different email is added.
  - A name-only card matches a name-only existing contact.
  - A name-only card does **not** match an existing contact of that name that has a number.
  - Two identical email-only cards in one file are added once.
  - A round trip `vcard::write` then `vcard::parse` then `plan` over a book with number-less
    contacts gives `add.is_empty()`. Use the existing vCard test helpers. If writing goes
    through `ContactDraft`, build the `WireContact`s with matching `email`.

In `crates/rovsti_core/src/search.rs`:
- `Entry` gets `folded_email: Option<String>` (`fold` of the trimmed `WireContact.email`; none
  when it is blank).
- `Query::Text(folded)` matches `e.folded_name.contains(&folded)` **or**
  `e.folded_email.as_deref().is_some_and(|m| m.contains(&folded))`. The Digits branch is
  unchanged. Make sure `fold` keeps `@` and `.`. If it strips them, match the query against a
  lowercase email instead, and say so in a comment.
- Doc of `search`: "names, numbers, and the email shown for a contact without a number".
- Tests: "ayse@" and "example.com" find a number-less contact through its email, and a
  contact with no email is not matched by "@". Phone shares this index, and showing a contact
  matched by its visible email is correct there too.

### V2. Import wording and stale docs (R5) (done, d3aa51e)

- `apps/contacts/src/confirm.rs::import_summary`: the unreadable part reads
  "{n} without a name, or with a broken number or email" (it replaces "without a name or a
  phone number"). Update `the_import_summary_names_what_is_left_out`.
- `apps/contacts/src/transfer.rs::export_contacts` doc: "Reads every contact and offers it as
  `contacts.vcf`."
- Grep `apps/contacts` for other "with a number" or "phone number appear" leftovers and fix
  any that are wrong now.

### V3. Groups show and choose their account (R3) (done, e00019a)

Pure helpers in `crates/rovsti_core/src/contact_edit.rs`, with tests:
- `pub fn group_subtitle(members: usize, account: Option<&str>) -> String`. It returns
  "No contacts" / "1 contact" / "N contacts", and with `Some(key)` appends
  " · {account_label(key)}". For example, "3 contacts · Google, a@b.com" and
  "No contacts · On this phone". Move the wording of `groups.rs::count_words` here and
  delete `count_words`.
- `pub fn group_join_hint(account: &str) -> String`. The empty key gives "Only contacts
  saved on this phone can join this group.", and any other key gives
  "Only contacts saved in {account_label(key)} can join this group.", for example
  "Only contacts saved in Google, a@b.com can join this group."
- Tests cover both helpers, the singular and plural counts, the phone key, and a Google key.

In `apps/contacts/src/groups.rs`:
- `GroupEdit` gets `account: String`. `start_group_edit(None, ..)` sets it to
  `editor::default_account(&self.accounts, &self.save_to)`. Renaming sets it to the group's
  own `account`.
- `group_edit_section`: when creating and `self.accounts.len() > 1`, add a second row under
  the name field, laid out like the editor's Save-to row (`tap_row(theme, "group-save-to",
  None, "Save to", Tone::Plain)` with the trailing accent label). Tapping it sets
  `edit.account = editor::next_account(&self.accounts, &edit.account)` and calls
  `cx.notify()`. It does **not** change `self.save_to`, because that setting belongs to new
  contacts.
- `save_group_edit` uses `edit.account` (clone it before the spawn) instead of the
  `default_account(..)` call.
- `build_group_items`: `count` becomes
  `group_subtitle(count, (self.accounts.len() > 1).then_some(group.account.as_str()))`.
  It still runs per pull, never per frame.
- `group_page_item`: when `self.accounts.len() > 1`, a footnote line
  (`px(32.0)`, `typography::FOOTNOTE`, `colors.secondary_label`, like Settings' closing note)
  with `group_join_hint(&group.account)` sits under the "Add or Remove Contacts" section.

In `apps/contacts/src/picker.rs::picker_item`:
- For `PickFor::Group(id)` with `self.accounts.len() > 1`, show the same
  `group_join_hint` footnote under the "N selected" line, so the owner knows before Done
  which contacts can join.

### V4. Undo for Delete Contact (R2, closes G10) (done, 9e83e87)

The confirm card stays. After "Delete", the contact disappears at once and the notice reads
"Contact deleted." with an **Undo** button for 5 s. The real delete runs only when that time
is up. Nothing is restored on Android, so the photo, the id and the sync identity all
survive an Undo.

Pure logic in `crates/rovsti_core/src/contacts_view.rs` (or `contact_card.rs`, next to
`favorites`), with tests:
- `pub fn without<'a>(contacts: impl IntoIterator<Item = &'a WireContact>, hidden: &[i64]) -> Vec<WireContact>`
  clones every contact whose id is not in `hidden`, in the order given.
- Tests: it hides the listed ids, keeps the order, and an empty `hidden` keeps everything.

In `apps/contacts/src/main.rs` and `confirm.rs`:
- Add the state `pending_delete: Option<PendingDelete>` with
  `struct PendingDelete { id: i64, task: gpui::Task<()> }`, and
  `const UNDO_LIFE: Duration = Duration::from_secs(5)`.
- `notice_action: Option<NoticeAction>` with `enum NoticeAction { UndoDelete }`. It belongs
  to the current notice text: whatever sets `notice` to another value also clears
  `notice_action`. Put this in one helper, `fn say(&mut self, text: impl Into<SharedString>)`,
  only where needed. At minimum, `keep_notice_timer` clears the action when it clears the
  notice.
- `confirm.rs::delete_contact(id)` becomes:
  1. `self.commit_pending_delete(cx)`, so an earlier pending delete runs now.
  2. Hide the contact locally. Rebuild `book` as
     `ContactIndex::new(contacts_view::without(index.all(), &[id]))`, the same way
     `set_starred_local` does. Clear `editor`, `open` and `open_draft`, run
     `page.restart()`, then `rebuild(cx)`.
  3. Set `notice = "Contact deleted."` and `notice_action = Some(UndoDelete)`.
  4. Spawn a task that waits `UNDO_LIFE` on the background timer, then calls
     `this.commit_pending_delete(cx)`. Store it in `pending_delete`.
- `fn commit_pending_delete(&mut self, cx)`: takes `pending_delete`, dropping its task. If
  there was one, it runs the old body (`backend::delete(id)` on the background executor). On
  `Err`, the notice is "Can't delete the contact: {words}." and the app calls `reload`. On
  `Ok`, it calls `reload` with no new notice.
- `fn undo_delete(&mut self, cx)`: takes `pending_delete` (dropping the task cancels the
  timer), clears the notice and its action, sets `pending_open = Some(id)`, and calls
  `reload(cx)`. The contact comes back from Android and its card reopens.
- `reload`: when `pending_delete` is `Some(p)`, filter `p.id` out of the pulled book with
  `contacts_view::without` before storing it. A pull during the 5 s must not bring the contact
  back.
- `observe_window_activation`: when the window goes **inactive**, call
  `commit_pending_delete`, so leaving the app never leaves a delete undone.
- `notice(theme, cx)`: when `notice_action` is `Some(UndoDelete)`, the glass capsule becomes
  a row with the text and a trailing `crate::ui::pill(theme, "notice-undo", "Undo", false)`
  whose `on_click` calls `undo_delete`. Keep `.occlude()`. The notice timer for a notice with
  an action uses `UNDO_LIFE` instead of `NOTICE_LIFE`. Pass the life into `keep_notice_timer`
  through the action.
- Back while the Undo notice shows does nothing special, and the delete still commits on
  time.

Tests: the `without` tests in rovsti_core. The app-side state stays thin glue with no new
pure logic beyond them.

### V5. Docs and plan status (done, this commit)

- `docs/roadmap.md` (Rovsti Contacts): tick a "UX completeness pass, round 2" line covering
  import dedup for contacts without a number, search by email, group accounts, and Undo for
  Delete Contact, each "(done without a device)". Extend the open device-check item with
  "a delete commits after 5 s or when leaving the app".
- `docs/ux-audit.md` (if it tracks Contacts items): note R1 to R5.
- This file: mark V1 to V5 done, with their commit hashes.

## Left for round 3 (re-audit)

- Re-check the Undo flow against a pull from another app's change (`observe_window_activation`
  on return), and check that no other notice setter leaves a stale Undo button.
- If nothing new comes up, Contacts is complete apart from the R6 feature list.
