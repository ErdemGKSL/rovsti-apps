# UX completeness pass: Rovsti Contacts (round 3)

Base: `a33acaf5b488b497f34c7aba8424084d8dc28037` (main, "Rovsti contacts: Docs and plan status").
The tree is clean and there is no stash. Round 1 (`ux-contacts.md`, U1 to U7) and round 2
(`ux-contacts-r2.md`, V1 to V5) are done.

In this round I re-audited the app from the user's side: the Contacts, Favorites, Groups and
Settings tabs, the card and its field sheet, the editor, the picker, group create, rename and
delete, import, export, WhatsApp, and Back from every layer. I also did the two checks round 2
left open: the Undo flow when a pull comes in from another app's change, and whether any
notice setter leaves a stale Undo button.

GPUI rules: add no new GPUI calls. Use only what the app already uses: `cx.spawn`,
`background_executor().timer(..)`, `crate::ui::pill`, `tap_row` and `empty_state`.

## Audit: gaps found (round 3)

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| S1 | **Medium** | **The Undo for Delete Contact disappears after almost any tap, but the delete still goes through.** The pending delete lasts 5 s, but about a dozen places set `self.notice = None` while it runs: `open_contact`, `open_group`, `start_group_edit`, `open_picker`, `start_editor`, `set_whatsapp_open`, the tab switch in `go_back`, the tab-bar reset in `render`, `act()` (twice, because its async result also writes `None`), `wa_handoff` and `import_contacts`. After deleting someone, a user who taps another contact, switches tab or calls someone loses the Undo button for good, and the contact is deleted 5 s later. | `main.rs`, `groups.rs`, `picker.rs`, `editor.rs`, `whatsapp.rs`, `transfer.rs` |
| S2 | Low-Medium | **The Undo button can go stale.** `commit_pending_delete` leaves `notice_action = Some(UndoDelete)` in place, so the button stays on screen with nothing behind it. This happens when the app went to the background (the commit runs on deactivation, and on return the "Contact deleted." capsule still shows Undo) and in the small gap between the two 5 s timers. A tap on that Undo does nothing and gives no feedback. | `confirm.rs::commit_pending_delete`, `undo_delete` |
| S3 | Low | **Export does not handle an empty book or a pending delete.** With no contacts, "Export to a .vcf file" still opens Save As, writes an empty file and reports "Saved 0 contacts to the file.". During the 5 s Undo window, the export also includes the contact that was just deleted, because Android still has it. | `transfer.rs::export_contacts`, `backend.rs::export_text` |
| S4 | Info (not planned) | **Delete Group** has a confirm but no Undo. A group holds no contact data (the members stay, and the confirm says so), so the confirm is enough. The new features from round 2's R6 are still out of scope: choosing the default number, importing into a chosen account, sharing a vCard file, and quick actions on list rows. | — |
| S5 | Info (checked, complete) | A pull from another app during the Undo window filters the pending id out (`reload`). Leaving the app commits the delete. A second delete inside the window commits the first one and restarts the timer. The empty states (Contacts, search, Favorites, Groups, group page, picker) and the feedback after every write are in place. Back works through every layer. Permission recovery and Try Again work. Copy, Share and Email work from the card and the field sheet. Groups show their account. Import dedup is in place. | — |

## Plan (ordered by user impact; each step leaves all checks green)

Run these checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
If `df` shows under 6 GB free, run `rm -rf target/debug/incremental` first.
Commit each step on its own, with a focused message ("Rovsti contacts: ...").

### W1. The Undo notice survives navigation and never goes stale (S1, S2)

Pure helper in `apps/contacts/src/main.rs` (it is app-local state, so it stays next to
`NoticeAction`):
- `fn keeps_undo(action: Option<NoticeAction>, pending: bool) -> bool` returns
  `action == Some(NoticeAction::UndoDelete) && pending`.
- Add a `#[cfg(test)] mod notice_tests` with three cases: the Undo is kept while a delete is
  pending, it is not kept without a pending delete, and it is not kept with no action.

Glue:
- `impl ContactsApp { pub(crate) fn clear_notice(&mut self) }`. If
  `keeps_undo(self.notice_action, self.pending_delete.is_some())`, return and leave the notice
  in place. Otherwise set `notice = None` and `notice_action = None`.
- Replace each `self.notice = None;` (and `this.notice = None;`) that is navigation or the
  start of an action with `self.clear_notice();`. These are:
  - `main.rs`: `open_contact`, `set_whatsapp_open`, the tab switch in `go_back`, the tab-bar
    reset closure in `render` (around line 1665), and `act()` before its spawn.
  - `groups.rs`: `open_group` and `start_group_edit`. The `pending_group` path in `reload`,
    which saves the notice and puts it back, still works unchanged.
  - `picker.rs`: `open_picker`.
  - `editor.rs`: `start_editor` and `choose_photo` (around line 446).
  - `whatsapp.rs`: `wa_handoff` (around line 494).
  - `transfer.rs`: `import_contacts`, and the `this.notice = None` in `plan_import` (around
    line 232).
- `act()`, `wa_handoff()` and any other async path that writes `this.notice = notice` where
  `notice` may be `None` change to `if let Some(n) = notice { this.notice = Some(n); }`. A
  successful hand-off must not wipe the Undo.
- Leave alone the places that set a **new** notice text. `keep_notice_timer` already drops
  the button when the text is no longer `UNDO_NOTICE`, and that behavior is intended: a newer
  outcome replaces the Undo, but the deleted contact stays hidden and its row and card stay
  gone.
- `confirm.rs::commit_pending_delete`: right after `take()` succeeds, set
  `self.notice_action = None`. Keep the "Contact deleted." text so it expires through its
  timer, and call `cx.notify()`. The button then goes away exactly when the delete becomes
  final, which also covers the commit that runs when the app goes to the background.
- `confirm.rs::undo_delete`: when `pending_delete` is already `None`, clear the notice and
  its action with `notice = None; notice_action = None; cx.notify()`, so a late tap is never a
  silent no-op. In practice the S2 fix makes this unreachable, but it stays as a guard.
- Doc comment on `clear_notice`: "Navigation clears the notice, except the Undo of a delete
  that is still waiting: it stays until the delete commits or the owner taps Undo."

Acceptance: the `keeps_undo` tests pass. `grep -n "notice = None" apps/contacts/src` lists only
`clear_notice`, the notice timer, `undo_delete` and `keep_notice_timer`. All checks are green.

### W2. Export handles an empty book and a pending delete (S3)

- `backend.rs::export_text(hidden: Option<i64>) -> Result<(String, usize), String>`: after
  `retain(validate)`, also `retain(|c| hidden.is_none() || c.id != hidden)`. The doc
  comment gains "`hidden` is a contact waiting for its Undo, left out of the file."
- `transfer.rs::export_contacts`:
  - Pass `self.pending_delete.as_ref().map(|p| p.id)` in, and make `PendingDelete.id`
    `pub(crate)` if needed.
  - Inside the background closure, when `count == 0`, return `Ok(0)` **without** calling
    `start_export`.
  - On `Ok(0)`, set `transfer_busy = false` and use the notice from
    `export_words(0)`, then leave `exported` at `None`.
- Add a pure helper in `transfer.rs`, `pub(crate) fn export_words(count: usize) -> String`.
  It returns "There are no contacts to export." for 0, "Saved 1 contact to the file." for 1,
  and "Saved {n} contacts to the file." otherwise. `on_document_result` uses it in place of
  the inline `format!`.
- Tests in `transfer.rs`: `export_words` covers 0, 1 and 12. In `backend.rs`, if a desktop
  test can reach the sample book, `export_text(Some(id))` of a sample id has one contact
  fewer than `export_text(None)`. Otherwise skip that test and say so in the commit.

### W3. Docs and plan status

- `docs/roadmap.md` (Rovsti Contacts): add a ticked line "UX completeness pass, round 3: the
  Undo of a delete survives navigation and goes away when the delete commits; export says
  when there is nothing to export (done without a device)".
- `docs/ux-audit.md` (if it tracks Contacts items): note S1 to S3.
- This file: mark W1 to W3 done, with their commit hashes.

## Left for round 4 (re-audit)

- If nothing new comes up after W1 to W3, Contacts is complete apart from the S4 list of
  features that are not planned.
