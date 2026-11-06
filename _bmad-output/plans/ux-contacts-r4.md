# UX completeness pass: Rovsti Contacts (round 4)

Base: `de2b514eba9dd9de59bf430a02dbfc96dd231cfb` (main, "Rovsti contacts: Docs and plan status").
The tree is clean and there is no stash. Rounds 1 to 3 (`ux-contacts.md` U1 to U7,
`ux-contacts-r2.md` V1 to V5, `ux-contacts-r3.md` W1 to W3) are done.

Re-audit from the user's side: Contacts, Favorites, Groups and Settings tabs, the search, the
card and its field sheet, the editor (names, numbers, emails, note, groups, photo, Save to,
Delete), the picker (group members and Favorites), group create, rename and delete, import,
export, WhatsApp, the Undo of a delete, and Back from every layer.

GPUI rules: add no new GPUI calls. Use only what the app already uses (`empty_state`,
`glass_pill(..).id(..).on_click(cx.listener(..))`, as in `book_state`).

## Audit: gaps found (round 4)

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| T1 | **Medium** | **A search that finds nobody dead-ends.** Typing a number (or a name or an email) that is not in the book shows "No Results · No contact matches “…”." and nothing else. The obvious next step, "Create Contact" with what was typed already filled in, is missing: the owner must clear the search, tap +, and retype (or paste) the number. `rovsti_core::contacts_view::query_number` already recognises a typed number (Phone uses it for its own "Add to contacts"), and the editor already takes a prefilled `ContactDraft`, so only the entry point is missing. | `main.rs::contacts_item`, `editor.rs::open_new_editor` |
| T2 | Info (not planned) | Tapping the kind button on a number or email with a custom Android label (e.g. "Pager") drops the label for good (Cancel brings it back). The editor only offers Mobile/Home/Work/Other on purpose. | `editor.rs::cycle_kind` |
| T3 | Info (not planned) | Rovsti Contacts only has a launcher intent filter, so "Add to contacts" from Phone or Messages opens the system editor (`ACTION_INSERT_OR_EDIT`). Answering other apps' insert/view intents would be a new integration feature. The R6/S4 list also stays out of scope: choosing the default number, importing into a chosen account, sharing a vCard file, quick actions on list rows, Undo for Delete Group, a custom favorites order (Phone has one). | — |
| T4 | Info (checked, complete) | Create, edit, delete (with Undo) contacts; photo choose/remove/WhatsApp photo; star and Favorites "+" picker; groups create, rename, delete (confirm), members picker, account hint; card per-field call/message/copy/share/email; Settings name format, sort, Save to, import (dedup, confirm, summary), export (empty book and pending delete handled); WhatsApp settings, chat and call; empty states for every list; permission recovery and Try Again; feedback after every write; discard-changes confirm; search clear button. | — |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

Run after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
If `df` shows under 6 GB free, run `rm -rf target/debug/incremental` first.
Commit each step on its own ("Rovsti contacts: ...").

### X1. rovsti_core: a draft from a search query (+ tests) (T1)

In `crates/rovsti_core/src/contact_edit.rs`:

```rust
/// A new-contact draft filled in from a search that found nobody: a number
/// becomes a mobile number, an address with an `@` becomes an email, other
/// text becomes the name (the last word is the last name). `None` for a
/// blank query.
pub fn draft_from_query(query: &str) -> Option<ContactDraft>
```

- `let q = query.trim();` blank → `None`.
- `crate::contacts_view::query_number(q)` is `Some(n)` →
  `ContactDraft { numbers: vec![DraftField::new(n, FieldKind::Mobile)], ..ContactDraft::default() }`.
- Else if `q` has no whitespace and contains `@` →
  `ContactDraft { emails: vec![DraftField::new(q, FieldKind::Home)], ..ContactDraft::blank() }`
  (keeps blank()'s empty mobile row to fill in).
- Else: split on the last run of whitespace (`q.rsplit_once(char::is_whitespace)`); with a
  split, `given = head.trim()`, `family = tail.trim()`; without one, `given = q`. Use
  `..ContactDraft::blank()` so the form still offers an empty number row.
- Add `contacts_view` to the `use crate::{..}` list only if needed (call it by path otherwise).

Tests in `contact_edit.rs` `mod tests`:
- `draft_from_query("  ")` is `None`.
- `"0555 111 22 33"` gives one Mobile number equal to `query_number` of the same text, empty
  names, no emails.
- `"ayse@example.com"` gives that email (Home) and an empty name.
- `"Ayşe Yılmaz"` gives given "Ayşe", family "Yılmaz"; `"Ayşe"` gives given "Ayşe", empty family;
  `"Mary Ann  Smith"` gives given "Mary Ann", family "Smith".
- The name draft, after `given/family` are set, passes `validate()`; the number draft fails
  only with `NoName` (documents that the owner still adds a name).

Acceptance: `cargo test -p rovsti_core contact_edit` passes; checks green.

### X2. "Create Contact" from a search with no results (T1)

`apps/contacts/src/editor.rs`:
- Refactor `open_new_editor` into
  `pub(crate) fn open_new_editor_with(&mut self, draft: ContactDraft, cx: &mut Context<Self>)`
  which sets `draft.account = default_account(&self.accounts, &self.save_to)` and calls
  `start_editor(&draft, cx)`. `open_new_editor` becomes
  `self.open_new_editor_with(ContactDraft::blank(), cx)`.
- Note: `Editor::new` keeps `numbers` from the draft (blank first row only when empty), and
  `initial` is read back from the form, so Cancel on an untouched prefilled form closes
  without the discard confirm. That is intended: the typed text is still in the search box.

`apps/contacts/src/main.rs::contacts_item`, the "No Results" branch (around line 1228):
- Compute `let draft = contact_edit::draft_from_query(query)` (it is `Some` there since the
  query is not blank) only when `matches!(self.book, Book::Ready(_))`.
- Wrap like `book_state`: `div().flex().flex_col().items_center()` with the existing
  `empty_state(theme, "No Results", ..)` and, when a draft exists,
  `glass_pill(theme, "Create Contact").id("create-from-search").on_click(cx.listener(move |this, _, _, cx| this.open_new_editor_with(draft.clone(), cx)))`.
- Keep the query text: after Save, `pending_open` opens the new card and the saved contact
  matches the search, so the list behind it is right.
- Import `rovsti_core::contact_edit` (or the function) at the top of `main.rs` if not already.

Acceptance: on desktop (`cargo run -p contacts` with the sample book) searching "0555 000 00 00"
shows "No Results" plus "Create Contact"; tapping it opens "New Contact" with the number filled
in; searching "Jane Doe" fills First "Jane", Last "Doe". All checks green.

### X3. Docs and plan status

- `docs/roadmap.md` (Rovsti Contacts): add a ticked line "UX completeness pass, round 4: a
  search with no results offers Create Contact with the number, email or name filled in (done
  without a device)".
- `docs/ux-audit.md` (if it tracks Contacts items): note T1, and T2/T3 as not planned.
- This file: mark X1 to X3 done, with their commit hashes.

## Left for round 5 (re-audit)

- If nothing new comes up after X1 to X3, Contacts is complete apart from the T2/T3 list of
  features that are not planned; round 5 should return done.
