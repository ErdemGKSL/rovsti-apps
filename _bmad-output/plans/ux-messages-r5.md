# UX completeness pass: Rovsti Messages (round 5)

Base: `09709cc06dcc9a3ff9d98896bfb59968b4299f36` (main, clean tree, no stash). Rounds 1
(`ux-messages.md`, G1 to G10), 2 (`ux-messages-r2.md`, H1 to H9), 3 (`ux-messages-r3.md`,
K1 to K9) and 4 (`ux-messages-r4.md`, N1 to N8) are done. This round re-audits as a user
holding the phone, looking at what earlier rounds did not: moving a sender *into* Spam,
what survives an app restart, where scheduled messages can be found again, how Send later
is discovered, and the Status viewer. Scope as before: `apps/messages`, `rovsti_core`,
`rovsti_wa_proto`/`rovsti_wa_client` as used by Messages, existing `rovsti_android` seams.
No new product features, no backends, no Kotlin changes (cargo is the only verification).

Checked in this repo / at the pinned gpui-fusion rev: `Icon` is `gpui_icons::GpuiIcons`
with the `lucide-misc` feature on, so `LucideShieldAlert` and `LucideCalendarClock` exist
(assets `misc/shield-alert.svg`, `misc/calendar-clock.svg`); `LucideAlarmClock`,
`LucideMessageCircle`, `LucideArchive` are already used. `rovsti_ui::storage::{load_json,
save_json}`, `Backend::data_dir()`, `Gateway::scheduled()`, `GatewayEvent::Scheduled`,
`blocklist::can_block` (refuses `NotANumber` for alphanumeric senders) all exist.

## Re-audit: gaps found

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| P1 | **High** | **A sender cannot be moved into Spam where it is seen.** "Not spam" exists, its opposite does not. The common Turkish promo senders are alphanumeric (`TRENDYOL`, `GARANTI`): their row and page menus offer Block, Call and Add to contacts, but Block always refuses ("Unknown number, cannot block") and Call/Add make no sense. The only way to silence them is Settings > Spam > Sender and typing the sender id by hand, although `SpamRule::Sender` already does exactly this. Without the SMS role Block is impossible for any sender, so this is also the only path then. | `sms_actions.rs::actions`, `main.rs::row_sms_actions/sms_row_action/open_page_menu`, `prefs.rs`, `rovsti_core::spam` |
| P2 | Medium | **Drafts die with the process.** `drafts.rs` keeps text "for the session" only, and only on `leave_page`. Type half a message, switch to another app, Android kills Messages: the text is gone and the inbox's "Draft:" line with it. | `drafts.rs`, `main.rs::leave_page/on_window_active` |
| P3 | Medium | **Scheduled messages can only be found inside their conversation.** No overview anywhere. A message scheduled to a new number (no provider thread yet) has no inbox row at all until it is sent: to check or cancel it the user must retype the number in New message. Gateway items are only fetched when a chat opens. | `main.rs::sync_chat_list/chat_item/load_remote_scheduled`, `schedule_ui.rs::strip_items` |
| P4 | Medium | **A WhatsApp scheduled message that fails says nothing** unless its chat is open (the SMS case got a pill with "Open" in round 4; the gateway's `Scheduled` event with `Failed` only updates the list). | `main.rs::on_gateway_events` (`GatewayEvent::Scheduled`) |
| P5 | Medium | **Send later is only a long-press on Send**, with no visible entry, and a long-press with an empty composer does nothing at all. | `sms_conversation.rs::request_schedule`, `conversation.rs::request_schedule`, page menus in `main.rs` |
| P6 | Low | **The Status viewer is a dead end**: no way to message the person whose update is showing; the user must close it, find the chat and open it. | `status_ui.rs::viewer_element`, `StatusPageEvent` |
| P7 | Low | **Search does not find archived chats**: typing the name of an archived WhatsApp chat gives "No Results". | `main.rs::sync_chat_list` (`shown_rows`), `inbox::filter_rows` |
| — | Info (not planned) | A draft typed to a new number (no thread yet) is still not kept on Back (needs a recipient-keyed draft and a place to show it; a new number has no row). Search inside one conversation, "Mark all as read", bulk select, empty the Spam folder, replying to a Status with a quote (gateway quoting of status messages is unverified), saving Status photos, plus everything listed as Info in rounds 1 to 4 (voice/video playback, picture viewer, forwarding, starred list, group members page, WhatsApp chat delete/block, single SMS delete, SMS mark unread, notification Mark as read, share target, posting a Status, group SMS creation). New screens, new Kotlin or gateway work, or new features. |

## Plan (ordered by user impact, pure logic first; every step leaves all checks green)

Checks after every step (from the task):
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
`df` showed 6.3 GB free at planning time; under 6 GB run `rm -rf target/debug/incremental` first.
Until a later step calls a new pure function, mark it `#[cfg_attr(not(test), allow(dead_code))]`
and remove the attribute in that step. One focused commit per step (others push to main
in parallel).

### Step 1. Pure logic (+tests)
- `apps/messages/src/sms_actions.rs`:
  - New `SmsAction::MoveToSpam`, `label` "Move to Spam", not danger.
  - `SmsActionContext` gets `pub sender_is_number: bool` and `pub can_be_spam: bool`
    (the row is SMS only: the inbox never puts a person who is also on WhatsApp in Spam).
  - `actions`: Call and Add/Open contact (page) and Block/Unblock (both) only when
    `single_sender && sender_is_number`. `MoveToSpam` when `single_sender && can_be_spam &&
    !in_spam && !known_contact`, placed before Block/Delete (row: after Mute/Unmute; page:
    after Add/Open contact). Update the test helper `row()` to set `sender_is_number: true,
    can_be_spam: true` so old tests keep their meaning, and the `menu()` helper in
    `main.rs` tests.
  - `pub fn moved_to_spam_text(title: &str) -> String` ("Moved {title} to Spam", blank →
    "this sender").
  - `pub const TOO_MANY_RULES: &str = "Too many spam rules. Remove one in Settings first.";`
  - Tests: `alphanumeric_sender_offers_move_to_spam_not_block_or_call`,
    `move_to_spam_hidden_for_contacts_spam_and_mixed_rows`, `page_offers_move_to_spam`.
- `apps/messages/src/prefs.rs`:
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub struct SpamMark { pub rule_added: bool, pub was_allowed: bool }`
  - `pub fn mark_spam(&mut self, sender: &str) -> Result<SpamMark, rovsti_core::spam::RuleError>`:
    trim; `was_allowed = self.unallow_sender(sender)`; `self.spam.add_rule(SpamRule::Sender
    { pattern })`: `Ok` → `rule_added = true`, `Err(Duplicate)` → `rule_added = false`
    (still Ok), `Err(e)` otherwise → put the allow entry back if it was removed and return
    `Err(e)` (nothing changed).
  - `pub fn unmark_spam(&mut self, sender: &str, mark: SpamMark)`: if `rule_added`, remove
    the `SpamRule::Sender` whose trimmed pattern equals the trimmed sender
    (case-insensitive) via `spam.remove_rule(index)`; if `was_allowed`, `allow_sender`.
  - Tests: `mark_spam_adds_a_sender_rule_and_unallows`, `unmark_spam_restores_exactly`
    (both flags), `mark_spam_on_a_full_list_changes_nothing` (fill to `MAX_RULES`, sender
    previously allowed stays allowed), `mark_spam_twice_is_a_duplicate_not_an_error`.
- `apps/messages/src/inbox.rs`:
  - `impl InboxView { pub fn sms_only(&self, thread: &ThreadId) -> bool }`: the row (in
    `all_rows()`) whose `threads` contains it has `wa_chat == None` and only
    `ThreadId::Sms` threads.
  - Test `sms_only_is_false_for_merged_rows`. Also a test that a thread whose sender
    matches a `SpamRule::Sender` (alphanumeric `TRENDYOL`, non-contact) lands in
    `view.spam` (add if no existing test covers it): `sender_rule_moves_a_row_to_spam`.
- `apps/messages/src/drafts.rs`:
  - `pub const DRAFTS_FILE: &str = "drafts.json";`
  - `pub fn to_map(&self) -> BTreeMap<String, String>` keyed by
    `crate::notifications::thread_tag`; `pub fn from_map(map: &BTreeMap<String, String>) -> Drafts`
    through `notifications::parse_tag` (bad tags, negative SMS ids and blank texts skipped;
    `rev` starts at 0).
  - `pub fn merge_loaded(&mut self, loaded: Drafts)`: adds loaded drafts for threads that
    have none in this session (session text wins); bumps `rev` when anything was added.
  - Tests: `drafts_round_trip_through_the_file_map`, `bad_tags_are_skipped`,
    `session_drafts_win_over_loaded_ones`.
- `apps/messages/src/schedule_ui.rs`:
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub enum EntryTarget { Sms(String), WhatsApp(String) }`
    and `#[derive(Clone, Debug, PartialEq, Eq)] pub struct ScheduledEntry { pub key: StripKey, pub target: EntryTarget, pub at_ms: i64, pub body: String, pub failed: bool }`.
  - `pub fn overview(local: &[ScheduledSms], remote: &[ScheduledText]) -> Vec<ScheduledEntry>`:
    local SMS items → `Sms(to)`, local WhatsApp-target items → `WhatsApp(chat)`, gateway
    items not `Sent` → `WhatsApp(chat)` (`failed` for `Failed`); failed first, then soonest
    first.
  - `pub fn overview_subtitle(entries: &[ScheduledEntry]) -> String`: "1 waiting",
    "3 waiting", "2 waiting, 1 couldn't be sent", "1 couldn't be sent".
  - `pub fn newly_failed(before: Option<&ScheduledText>, after: &ScheduledText) -> bool`:
    `after` is `Failed` and `before` was not (`None` counts as not failed).
  - `pub fn remote_failed_text(title: &str) -> String`:
    "Scheduled WhatsApp message to {title} couldn't be sent".
  - `pub const TYPE_FIRST: &str = "Type the message first, then choose Send later";`
  - Tests: `overview_lists_both_queues_failed_first`, `overview_skips_sent_gateway_items`,
    `overview_subtitles_count`, `newly_failed_only_on_the_transition`.
- `apps/messages/src/status_ui.rs`: `pub fn message_target(author: &str, from_me: bool) -> Option<String>`:
  `Some(author)` only when `!from_me` and the JID ends with `@s.whatsapp.net` (LIDs and
  own updates have no chat to open). Test `only_other_peoples_phone_jids_can_be_messaged`.

### Step 2. Move to Spam (fixes P1)
- `main.rs`:
  - `SmsRowMenu` gets `sender_is_number: bool` and `can_be_spam: bool`; fill them in
    `open_row_menu` and `open_page_menu`: `sender_is_number = sender.is_some_and(|s|
    !matches!(blocklist::can_block(s, &EmergencyList::default()),
    Err(BlockRefusal::NoNumber | BlockRefusal::NotANumber)))`,
    `can_be_spam = self.view.sms_only(&ThreadId::Sms(thread_id))`. Pass both in
    `row_sms_actions`.
  - Icon for `MoveToSpam` in the SMS menu item mapping: `Icon::LucideShieldAlert`.
  - `sms_row_action(MoveToSpam)` → new `fn move_to_spam(&mut self, sender: String, title: SharedString, cx)`:
    `backend.update_prefs(|p| result = p.mark_spam(&sender))`; `Ok(mark)` →
    `show_pill_action(sms_actions::moved_to_spam_text(&title), "Undo",
    PillAction::Unspam { sender, mark }, cx)`; `Err(RuleError::TooMany)` →
    `show_pill(TOO_MANY_RULES)`; other errors → `show_pill("Couldn't move that to Spam")`.
    Close the row menu as the other actions do. From the page the conversation stays open;
    `rebuild` → `sync_open_page` already shows the round-4 "This conversation is in Spam."
    line with Not spam.
  - `PillAction::Unspam { sender: String, mark: prefs::SpamMark }`; `pill_fired` →
    `update_prefs(|p| p.unmark_spam(&sender, mark))`, `show_pill("Back in the inbox")`.
- No settings change: the added rule shows in Settings > Spam as "Sender: TRENDYOL" with
  the existing Tap to delete + Undo.

### Step 3. Drafts survive a restart (fixes P2)
- `main.rs`:
  - Fields `drafts_loaded: bool`, `drafts_saved_rev: u64`, `drafts_saving: bool`.
  - In the `cx.observe(&backend, ..)` callback: when `!drafts_loaded` and
    `backend.read(cx).data_dir()` is `Some(dir)`, set `drafts_loaded = true` and spawn a
    background `storage::load_json::<BTreeMap<String, String>>(&dir, drafts::DRAFTS_FILE)`;
    on return `self.drafts.merge_loaded(Drafts::from_map(&map))`, set
    `drafts_saved_rev = self.drafts.rev()` if nothing was merged, `rebuild`, `cx.notify()`.
  - `fn save_drafts(&mut self, cx)`: when `drafts_loaded`, `drafts.rev() !=
    drafts_saved_rev` and not `drafts_saving`, take `to_map()` and the dir, spawn
    `storage::save_json(&dir, DRAFTS_FILE, &map)` on the background executor, log errors,
    record the rev, and run again on return if the rev moved meanwhile (same pattern as
    `Backend::persist`). Call it at the end of `rebuild`.
  - `on_window_active(false)`: put the open page's composer text into `drafts`
    (same match as `leave_page`, without closing anything) and `save_drafts`. On return the
    composer still holds the text; `leave_page` later puts the final text (empty after
    Send removes it). Opening a thread still `take`s its draft (the file follows on the
    next save).
  - `on_thread_deleted` already `forget`s; the save follows from `rebuild`.
- `docs/messages-acceptance.md`: the drafts row says drafts survive closing the app.

### Step 4. Send later from the menu; say why long-press did nothing (fixes P5)
- `sms_conversation.rs` and `conversation.rs`: make `request_schedule` `pub(crate)` and
  return `bool` (`false` when the composer is blank or, for SMS, the role is missing). The
  long-press handlers emit a new `Notice`-style event when it returns `false` and the
  composer is blank: SMS `SmsConversationEvent::Notice(schedule_ui::TYPE_FIRST)` (exists),
  WhatsApp the existing `ConversationEvent::Notice(String)` with `TYPE_FIRST`.
- `main.rs`: `RowMenu` gets `send_later: bool`, set `true` in `open_page_menu` and
  `open_wa_page_menu` (false in `open_row_menu`). `row_menu_overlay` adds, first in the
  card, `menu_item(theme, "menu-send-later", Icon::LucideAlarmClock, "Send later", ..)`:
  closes the menu, then calls the open page's `request_schedule` (SMS needs the role; when
  it returns `false` show `TYPE_FIRST` if the composer is blank, else nothing: the SMS
  page already shows "Make default to reply").

### Step 5. Scheduled overview in Chats (fixes P3)
- `main.rs`:
  - Field `show_scheduled: bool`; `ChatItem::{ScheduledFilter, ScheduledBack, Scheduled(usize)}`;
    `scheduled_entries: Vec<ScheduledEntry>` recomputed in `sync_chat_list` from
    `schedule_ui::overview(backend.queue().items(), &self.remote_scheduled)`; add the
    entries' `(len, failed count, first at_ms)` to `ChatLayout` so the list rebuilds when
    they change.
  - Inbox (no search, not spam/archived): `ScheduledFilter` after the Archived entry when
    entries are non-empty: `list_row` with `Icon::LucideCalendarClock`, title "Scheduled",
    subtitle `overview_subtitle`, id `"scheduled-filter"`, tap → `show_scheduled = true`,
    scroll to top. Keys `key_of((14u8, ..))`, back `key_of(15u8)`, rows
    `key_of((16u8, &entry.key, entry.at_ms, entry.failed))` (derive `Hash` on `StripKey`
    if needed).
  - `show_scheduled`: `ScheduledBack` (same look as `ArchivedBack`, "Inbox") then one row
    per entry: title = contact name via `backend.contacts().match_number(to)` or the
    number for `Sms(to)`, `self.title_of(&ThreadId::WhatsApp(chat))` or `jid_user(chat)` for
    WhatsApp; subtitle `"{when_label} · {preview(body)}"` (orange "Couldn't be sent · …"
    when failed); tap → SMS `open_recipient_sms(&Recipient { title, number: to, detail:
    None })`, WhatsApp `open_chat(chat, title)`; the page's Scheduled strip then offers
    Cancel (which restores the text). Leave scheduled mode on open like archived does.
  - Auto-leave `show_scheduled` when entries become empty (same place as lines 593 to 596),
    and on the tab's go-back path (`go_back` clears it with the other two flags).
  - `load_remote_scheduled` also runs where chats load on connect (next to
    `self.load_chats(cx)` in `on_gateway_events`), so the overview knows gateway items
    before any chat is opened.

### Step 6. Failed WhatsApp scheduled message is announced (fixes P4)
- `main.rs::on_gateway_events`, `GatewayEvent::Scheduled(item)`: before `upsert_remote`,
  `let failed = schedule_ui::newly_failed(self.remote_scheduled.iter().find(|t| t.id ==
  item.id), &item)`. When `failed` and the chat is not the open page:
  `show_pill_action(remote_failed_text(&title), "Open", PillAction::OpenChat { jid, title },
  cx)` (title via `title_of`, else `jid_user`). `pill_fired` → `open_chat(jid, title)`; the
  strip shows the failed row, whose Cancel puts the text back in the composer.

### Step 7. Status viewer: Message; search finds archived chats (fixes P6, P7)
- `status_ui.rs`: `StatusPageEvent::Message { jid: String, title: String }`. In
  `viewer_element`, when `message_target(&person.author, item.from_me)` is `Some(jid)`, a
  `glass_pill(theme, "Message")` (label only; `glass_pill(theme, label) -> Div`), id `"status-message"`, `.occlude()` like `status-close` so the tap
  does not step the viewer, at the bottom centre above the caption; click → close the
  viewer and emit `Message`. `main.rs` subscription maps it to `open_chat(jid, title)`.
- `main.rs::sync_chat_list`: when the query is non-blank and neither Spam nor Archived is
  showing, after the inbox matches append `ChatItem::ArchivedMatch(i)` for
  `inbox::filter_rows(&self.view.archived, &query)`; `NoResults` only when both are
  empty. Render with `inbox_row` from `self.view.archived[i]` with id `2000 + i` (as the
  archived view does) and key `key_of((17u8, &self.view.archived[i]))`. No extra marker
  (the row menu already offers Unarchive).

### Step 8. Docs and status
- `docs/messages-acceptance.md` section M, rows 85 onward: Move to Spam on an
  alphanumeric sender (row and page, Undo), Block/Call hidden for such senders, draft
  survives force-stop, Send later from the page menu and the empty long-press pill,
  Scheduled entry row (new-number SMS, WhatsApp item, failed item, tap opens and Cancel
  restores), failed WhatsApp scheduled pill with Open, Status viewer Message, search finds
  an archived chat.
- `docs/roadmap.md` Messages: one ticked line "Fifth UX pass: move a sender to Spam,
  drafts survive restarts, Send later in the menu, scheduled overview, failed WhatsApp
  schedule notice, message from Status, search archived chats (done without a device)".
- Status section at the end of this file.

## Status

Planned (round 5). Nothing implemented yet.
