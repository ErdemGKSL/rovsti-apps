# UX completeness pass: Rovsti Messages (round 1)

Base: `6eee2b5a87d22e8d3e3ccb539042353ad91e1e68` (main, clean tree, no stash).
Scope: `apps/messages`, `crates/rovsti_core`, `rovsti_wa_client` / `rovsti_wa_proto` as used by
Messages, `crates/rovsti_android` seams Messages already has. No new product features and no
backends. Intent comes from code and docs that exist: `rovsti_android::sms::delete_thread`
(Kotlin `SmsQuery.deleteThread`, planned in `messages.md` step 5) has no caller;
`notify::copy_to_clipboard`, `telecom::{place_call, add_contact}`, `blocklist::block`,
`Gateway::lookup_users`, `rovsti_wa_proto::{user_jid, lookup::wa_target}` exist; architecture
AD-MSG8 says spam is never lost (so it must be recoverable from the Spam folder); roadmap
"Unified inbox" and "Chat ... on WhatsApp ... through Rovsti Messages"; `docs/ux-audit.md` M21
(one number per contact) was skipped there as "a feature", it is a pick-vs-type gap here.

## Audit: gaps found (as a user holding the phone)

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| G1 | **High** | **An SMS conversation cannot be deleted.** The seam `sms::delete_thread` exists and is tested; nothing calls it. SMS rows have no long-press menu at all (only WhatsApp rows do), and the SMS page has no menu. The acceptance sheet (row 39) even says "Delete a thread". | `main.rs::inbox_row` (`menu` only for `wa_chat`), `backend.rs`, `sms_conversation.rs` header |
| G2 | **High** | **Spam cannot be rescued where it is seen.** A wrongly flagged sender sits in the Spam folder; the only way out is to type the sender by hand into Settings > Spam > "Always allow a sender". No "Not spam" on the row. Same for blocking: only by typing the number in Settings. | `main.rs` Spam view rows, `settings_ui.rs::allow_sender/block_number`, `prefs.rs::allow_sender` |
| G3 | **High** | **Message text cannot be copied.** SMS bubbles have no long press at all; the WhatsApp action strip (Reply, Star, Edit, Delete) has no Copy. Only an OTP in the inbox row can be copied. | `sms_conversation.rs::bubble`, `conversation.rs::actions` |
| G4 | Medium | **SMS rows offer no read/mute management, and SMS mute is invisible in the list.** Muting an SMS thread is only via the bell inside it (Settings lists it); the inbox row shows no bell-off icon for a muted SMS thread (`RowView.muted` reads WhatsApp meta only). No "Mark as read" for an SMS row (`sms::mark_read` exists). | `inbox.rs::build/Inputs`, `main.rs` row menu |
| G5 | Medium | **Typed text is lost when leaving a conversation.** Every open builds a fresh `SmsConversation` / `Conversation`; Back, a notification tap or the channel switch drops the composer text without a trace. | `main.rs::open_sms/open_chat/leave_page` |
| G6 | Medium | **Unknown-number SMS conversation dead-ends.** No Call, no Add to contacts, no Block, no Delete from the conversation; the header has only the channel switch and the bell. | `sms_conversation.rs::render` header |
| G7 | Medium | **No way to find a conversation.** The Chats list has no search; with hundreds of threads the user must scroll. (`ContactIndex::search` and `search::fold` exist; only New message uses them.) | `main.rs::chat_item(Title)`, `sync_chat_list` |
| G8 | Medium | **New message only starts SMS.** With WhatsApp linked, picking a contact who has no chat yet can never start a WhatsApp chat in Messages (`Gateway::lookup_users`, `user_jid`, `wa_target` unused here); an existing WhatsApp chat with that number is also skipped in favour of SMS. | `main.rs::open_recipient`, `new_conversation.rs` |
| G9 | Low | **Only one number per contact in New message** (default or first). A contact's work/second number must be typed by hand. (ux-audit M21.) | `new_conversation.rs::options` |
| G10 | Low | **Settings deletes are one tap with no Undo**: "Tap to delete" a spam rule, "Tap to remove" an allowed sender, "Tap to unblock", "Tap to unmute". A stray tap loses a hand-typed rule. | `settings_ui.rs::spam/notifications` |
| G11 | Info (not planned) | Starred WhatsApp messages have no list (the gateway has no starred endpoint; adding one is backend work). Group members/description page (`Gateway::group`) is unused; author names already show in group bubbles, so a member page is a new screen, not a missing path. Deleting a single SMS / marking an SMS thread unread needs new Kotlin provider writes: out of this pass. Pin/archive are WhatsApp-only by design. Runtime permission asks and device-only checks stay in `docs/messages-acceptance.md`. | — |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
(`df` showed 6.2 GB free at planning time; under 6 GB run `rm -rf target/debug/incremental` first).
Never invent GPUI APIs: reuse only what these files already call (`menu_card`, `menu_item`,
`glass_pill`, `glass_icon_button`, `list_row`, `on_long_press`, `NoticePill::{show, pointer,
token, dismiss}` + `PillEvent::Action` as in `apps/phone/src/main.rs:1213` and
`apps/phone/src/block_ui.rs::show_pill_action/pill_fired`, `TextField::{set, text, clear}`,
`cx.background_executor().spawn`).

### S1. Pure logic (+tests)
New `apps/messages/src/sms_actions.rs` (add `mod sms_actions;` in `main.rs`), pure:
- `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum SmsAction { MarkRead, Mute, Unmute, NotSpam, Block, Call, AddContact, Delete }`
- `#[derive(Clone, Copy, Debug, Default)] pub struct SmsActionContext { unread: bool, muted: bool, in_spam: bool, single_sender: bool, known_contact: bool, blocked: bool, on_page: bool }` (pub fields).
- `pub fn actions(ctx: &SmsActionContext) -> Vec<SmsAction>`: row (`on_page == false`):
  MarkRead if unread; Mute or Unmute; NotSpam if in_spam && single_sender; Block if single_sender && !blocked;
  Delete last. Page (`on_page == true`): Call and AddContact (only !known_contact) first when single_sender,
  then Block (single_sender && !blocked), Delete; no MarkRead/Mute (the bell is in the header).
- `pub fn label(action: SmsAction) -> &'static str` ("Mark as read", "Mute", "Unmute", "Not spam",
  "Block", "Call", "Add to contacts", "Delete conversation"); `pub fn is_danger(action) -> bool` (Block, Delete).
- `pub fn delete_prompt(title: &str) -> String`: "Delete the conversation with {title}? Its messages are removed from this phone." (blank title → "this number").
- `pub fn sender_of(addresses: &[String]) -> Option<&str>`: the only non-blank address trimmed; `None` for none or for a group (2+ distinct).
- `pub const NEEDS_ROLE: &str = "Make Rovsti Messages your default SMS app to delete messages";`
- Tests: row actions for unread/muted/spam/group combinations; page actions for unknown vs known contact and blocked; labels non-empty and unique; prompt with and without title; `sender_of` empty, one, blank+one, two.

`apps/messages/src/inbox.rs`:
- `RowView` gains `pub sms_thread: Option<i64>` (the person's newest SMS thread) and `pub sms_sender: Option<SharedString>` (`sms_actions::sender_of` of that thread's addresses).
- `Inputs` gains `pub muted: &'a BTreeSet<String>`; `RowView.muted` is also true when the row's SMS thread tag `notifications::thread_tag(&ThreadId::Sms(id))` is in it.
- `pub fn filter_rows(rows: &[RowView], query: &str) -> Vec<usize>`: blank query → all indices; else rows whose `search::fold(title)` or `fold(snippet)` contains `fold(query)`, or (query with ≥3 digits) whose `sms_sender` digits contain the query digits. Order kept.
- Update every `Inputs { .. }` literal (tests and `main.rs::rebuild`). Tests: `sms_rows_carry_thread_and_sender`, `muted_sms_thread_shows_the_bell`, `filter_rows_matches_title_snippet_digits_and_turkish_fold` (e.g. "ayse" finds "Ayşe"), `blank_query_keeps_all`.

New `apps/messages/src/drafts.rs` (pure): `#[derive(Default)] pub struct Drafts(HashMap<ThreadId, String>)` with
`put(&mut self, thread: ThreadId, text: &str)` (blank or `ThreadId::Sms(id)` with id < 0 removes/ignores),
`take(&mut self, thread: &ThreadId) -> Option<String>`, `forget(&mut self, thread: &ThreadId)`. Tests: put/take round trip, blank removes, negative SMS id ignored, take empties.

Copy helpers:
- `sms_conversation.rs`: `pub fn copy_text(item: &SmsItem) -> Option<String>` (body trimmed, `None` when empty); `pub fn copy_code(item: &SmsItem) -> Option<String>` (`rovsti_core::otp::detect` on the body, incoming only). Tests.
- `conversation.rs`: `fn copy_text_of(body: &MessageBody, meta: Option<&MessageMeta>) -> Option<String>`: text, or a media caption; `None` for revoked, media without caption, location/contact cards. Test in the existing test module.

`apps/messages/src/new_conversation.rs::options`: one `Recipient` per non-blank number of a contact (default first, then provider order), deduped by digits; `detail` = `"{label} · {raw}"` when `WireNumber.label` is non-blank, else `raw`. Keep `LIMIT`. Update/extend tests: a two-number contact gives two rows with labels; duplicate digits give one.

`apps/messages/src/prefs.rs`: `pub fn unallow_sender(&mut self, sender: &str) -> bool` (case-insensitive remove; for Undo). Test.

### S2. SMS rows get a menu; delete a conversation with confirmation (fixes G1, G4)
- `backend.rs`: `pub fn delete_thread(&mut self, thread_id: i64, cx) -> Task<Result<(), &'static str>>`-style flow (or a callback event, follow `cancel_scheduled`'s pattern): refuse with `sms_actions::NEEDS_ROLE` when `!sms_role_held()` and not preview; background `rovsti_android::sms::delete_thread`; `Ok(true)` → `pull_threads`, drop `prefs.muted` tag and `sim_choices` entry via `update_prefs`; `Ok(false)`/`Err` → "Couldn't delete that conversation". Preview (`is_preview()`): remove the row from the preview thread list and notify.
- `backend.rs::mark_read` already exists: used by "Mark as read".
- `main.rs`: `RowMenu` gains `sms: Option<SmsRowMenu { thread_id, sender: Option<String>, unread, muted, in_spam }>` and `wa: Option<..>` (existing fields move into it) so a merged person shows both sets (WhatsApp items, then the SMS items under them). `inbox_row` attaches the long press when `row.wa_chat.is_some() || row.sms_thread.is_some()`. SMS items come from `sms_actions::actions` (row context), labels from `label`, red for `is_danger`.
- Delete opens a second `menu_card` step ("delete_confirm: bool" on `RowMenu`, like `mute_choices`) with the `delete_prompt` title and a red "Delete" item plus "Cancel"; Back steps back to the menu (extend `go_back`). On success: close an open page showing that thread, `dismiss_notification`, pill "Conversation deleted". Mute/Unmute call `backend.update_prefs(|p| p.set_muted(tag, ..))` and show a pill ("Muted" / "Unmuted").
- `rebuild` passes `&prefs.muted` into `Inputs` (and the `InboxKey` already bumps on backend notify).
- Acceptance doc rows added in S8.

### S3. Pill actions; Not spam and Block from the row, with Undo (fixes G2)
- `main.rs`: `enum PillAction { Unallow(String), Unblock(String), Rule(..) }` and `pill_action: Option<(u64, PillAction)>`; `show_pill_action(text, label, action, cx)` and `pill_fired(cx)` copied from Phone's `block_ui.rs` pattern; `overlays` pointer handler checks `== PillEvent::Action`.
- Row menu "Not spam" → `update_prefs(|p| p.allow_sender(sender))`, the row leaves Spam on rebuild, pill "{sender} is no longer spam" with "Undo" → `unallow_sender`.
- "Block" → background `blocklist::block` (refuse emergency numbers with `blocklist::can_block(&raw, &EmergencyList::default())`, show its error text), then `backend.pull_blocked`, pill "Blocked {title}" with "Undo" → `blocklist::unblock`. Desktop `Unsupported` → pill "Blocking needs an Android phone."

### S4. Conversation actions: Copy everywhere, SMS page menu (fixes G3, G6)
- `sms_conversation.rs`: confirmed bubbles get `on_long_press` that selects the message (`selected: Option<u64>` keyed by `message_key`, mark `rows_dirty`); a row of `glass_pill`s under the selected bubble: "Copy" (`copy_text`), "Copy code" when `copy_code` is `Some` (sensitive copy). Tap elsewhere / Back clears the selection (extend `go_back`-like handling through a new `pub fn go_back(&mut self, cx) -> bool` used by `MessagesApp::go_back` like `Conversation`). Copy goes through a new `SmsConversationEvent::Copy { text, sensitive }` handled in `main.rs` by a generalised `copy_text(text, sensitive, cx)` (reuse `copy_code`'s body) with pill "Copied".
- Header gets a "more" `glass_icon_button` (`Icon::LucideEllipsis` if `gpui_icons` has it, else another existing icon; grep the gpui-icons crate) emitting `SmsConversationEvent::Menu`; `main.rs` shows a `menu_card` from `sms_actions::actions(page ctx)`: Call → `telecom::place_call` (dial pad fallback is in the seam), Add to contacts → `telecom::add_contact`, Block → S3 path, Delete → S2 confirm path.
- `conversation.rs::actions`: add a "Copy" pill (`Icon::LucideCopy`, already used in Phone) when `copy_text_of` is `Some`; emit `ConversationEvent::Copy(String)` and handle like SMS.

### S5. Drafts survive leaving a conversation (fixes G5)
- `SmsConversation` and `Conversation`: `pub fn draft(&self, cx: &App) -> String` (composer text) and `pub fn set_draft(&mut self, text: &str, cx)` (`TextField::set`).
- `MessagesApp` gains `drafts: Drafts`. `leave_page` (and `close_page`) stores the open page's composer text under its thread (`open_thread_id`); `open_sms` / `open_chat` call `set_draft` with `drafts.take(&id)`. `clear_draft` after scheduling and a successful send leave nothing behind (put the empty text). A deleted thread `forget`s its draft (S2 path).
- Inbox: rows with a draft show "Draft: …" as the snippet? Only if `RowView` has room: add `draft: bool` and render the snippet prefix in `row_element` (small, pure in `inbox.rs`: `pub fn snippet_with_draft(snippet, draft: Option<&str>) -> String`, tested).

### S6. Search the Chats list (fixes G7)
- `main.rs`: a search `glass_icon_button` (`Icon::LucideSearch`, already used in Contacts) next to the compose pencil in `ChatItem::Title`; it shows a `ChatItem::Search` row with a `TextField` (`TextInputPurpose::Text`, `TextInputAction::Search` if it exists, else `Done`) and a Cancel pill. `shown_rows` callers use `inbox::filter_rows` indices while the query is non-blank (keep `ChatItem::Row(usize)` as the index into `shown_rows()`; build items from the filtered indices; add the query's revision to `ChatLayout`). No match → `empty_state("No Results", "No conversation matches \"{q}\".")`. Back clears the search before leaving Spam/Archived (extend `can_go_back` / `go_back`). Search hides the Status, Spam and Archived entry rows.

### S7. New message: every number, and WhatsApp when linked (fixes G8, G9)
- G9 is done by S1's `options` change; the row shows the detail line.
- `open_recipient`: when `whatsapp.gateway()` is `Some`, compute `wa_target(&[number], chat users of wa_threads)` (`rovsti_wa_proto::lookup::wa_target`, as Contacts does in `apps/contacts/src/whatsapp.rs`). Show a `menu_card` titled with the recipient: "SMS" and "WhatsApp". WhatsApp with an existing chat → `open_chat(jid, title)`; with a bare number → background `Gateway::lookup_users(&[user_jid(number)?])`; on WhatsApp → `open_chat`; not on WhatsApp → pill "{title} is not on WhatsApp"; `user_jid` error (no country code) → pill "Add the country code to message on WhatsApp". Not linked → straight to SMS as today. Pure helper in `new_conversation.rs`: `pub fn channel_choice(linked: bool, sms_thread: Option<i64>) -> bool` (ask or not) only if it removes a branch; otherwise keep the logic inline and thin.

### S8. Undo for Settings deletes; docs (fixes G10)
- `settings_ui.rs`: `SettingsView` emits `SettingsEvent::Undoable { text: SharedString, undo: SettingsUndo }` (`SettingsUndo::{Rule(usize, SpamRule), Allow(String), Block(String), Mute(String)}`) after a rule delete, allow removal, unblock, unmute; `main.rs` subscribes and shows `show_pill_action(text, "Undo", PillAction::Settings(undo))`; firing re-inserts the rule at its index (add `SpamRules::insert_rule(&mut self, index: usize, rule: SpamRule)` next to `remove_rule` in `crates/rovsti_core/src/spam.rs`, clamped index, with a test), re-allows, re-blocks, re-mutes.
- Docs: `docs/messages-acceptance.md` new section "M. Managing conversations" (rows: delete with confirm, Not spam + Undo, Block + Undo, copy text/code, Call/Add contact from the page, draft survives Back, search, new WhatsApp chat from New message, two numbers per contact, Settings Undo); `docs/ux-audit.md` M21 marked fixed; `docs/roadmap.md` Messages: one ticked line "Manage conversations: delete, not spam, block, copy, search, drafts (done without a device)". Status section at the end of this file.
