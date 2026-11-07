# util-messages: everyday utilities for Rovsti Messages (round 2)

baseSha: 107b960e91cb2a98e5b6301e5cbf1ab70061228e

## Audit (what exists at baseSha)

Round 1 (`util-messages.md`, commits 5d053ee..20cf993) shipped M1-M8: `media_viewer.rs`
(zoom/pan/paging, Save/Share), header avatar viewer, `find_ui.rs` + `chat_find`, chat export
(`chat_export`), Mark all read (`PillAction::MarkAll` / `UnmarkAll`), `text_detect` pills.
`git stash list` is empty. Re-checked by grep for M9-M21: none exists.

- Forward: only the "Forwarded" label on incoming bubbles (`conversation.rs` ~2192). No forward
  action, no chat chooser. Seams that exist: `Gateway::send_text(&SendText{chat,text,quoted_id})`,
  `Gateway::media(id)`, `Gateway::send_media_with(chat, kind, mime, caption, &MediaOptions, bytes)`
  (`MediaOptions: Default`), `Backend::reply_sms(thread_id, text)` (sends to a thread without
  opening it), `Backend::send_mms(local_id, to, text, image_path, sub_id)`,
  `PillAction::OpenChat { jid, title }`, `MessagesApp::open_thread(ThreadId, String)`,
  `inbox::filter_rows(&[RowView], query)`, `channel_pick_overlay` (overlay pattern in main.rs).
- Starred: per-message Star/Unstar (`set_starred`, `star_override`, `MessageMeta::starred`) but no
  list. The gateway has no "starred in chat" query; the list covers loaded pages (+ Load earlier).
- No scroll-to-bottom / unread jump, no unread divider. `ListState::is_scrolled_to_end()` and
  `set_scroll_handler` exist at the pinned rev (`gpui/src/elements/list.rs:489,557`).
  `SmsThreadRow::unread` and `RowView::unread` carry unread counts.
- No quick replies. `TextField::{text, set}` exist (no insert-at-cursor); prefs live in
  `prefs::MessagesPrefs` (serde defaults), Settings in `settings_ui.rs` with `SettingsUndo`.
- No message info. Data: `MessageMeta::{status, edited_ms, forwarded, starred, media}`,
  `SmsItem::{at_ms, state, sub_id, parts}`, `sms::segments`, `call_notes::when_line(ms, LocaleTime)`.
- No select-text. gpui-fusion has read-only selectable text:
  `InteractiveText::new(id, StyledText).selectable(color).selection_handle(&TextSelectionHandle)`
  and `TextSelectionHandle::selection(&Window) -> Option<TextSelectionSnapshot>` with `.range()`
  (`gpui/src/elements/text.rs:1475-1495`, `text/selection.rs:25-65`). `StyledText::new` is used in
  `apps/phone/src/keypad.rs:1559`.
- Icons: Lucide groups common/core/editing/media/misc/connectivity are enabled, so `LucideZap`
  (misc/zap.svg), `LucideInfo`, `LucideArrowDown`, `LucideTextCursor` (editing/text-cursor.svg),
  `LucideForward`, `LucideStar` are available; still grep the generated enum
  (`target/debug/build/gpui_icons-*/out`) before using a new name, fall back to used icons.
- Gateway changes are out of scope (other agents own `services/wa_gateway`).

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| M1 | Full-screen image viewer (pinch/double-tap zoom, pan, swipe down, tap hides chrome) | done R1 |
| M2 | Save image to gallery / Share image from the viewer (WA and MMS) | done R1 |
| M3 | Swipe between the chat's images, "3 of 7"; MMS pictures open it | done R1 |
| M4 | Header avatar opens the full-screen profile photo, Save/Share | done R1 |
| M5 | Search in conversation with jump + glow | done R1 |
| M6 | Export chat as .txt / Share as text | done R1 |
| M7 | Mark all read (confirm, count pill, Undo for WA) | done R1 |
| M8 | Phone/email detection pills (Call, Message, Email, Copy) | done R1 |
| M9 | Forward a message (text, caption, picture) to any chat via a chooser; also from the viewer | R2 step 3 |
| M10 | Starred messages list per WhatsApp chat, tap jumps, Unstar in the list | R2 step 4 |
| M12 | Scroll-to-bottom button with new-message count, "N unread messages" divider on open | R2 step 5 |
| M11 | Quick reply templates (Settings list, composer chip row, insert) | R2 step 6 |
| M14 | Message info sheet (sent/received time, ticks, edited, forwarded, SIM, length/segments, media) | R2 step 7 |
| M16 | Select text sheet (selectable text, Copy selection / Copy all) | R2 step 7 |
| M21 | Inbox avatar tap opens the profile photo viewer | R2 step 8 |
| M13 | Per-chat media/links/docs gallery (grid of images, link list, files) | open |
| M15 | Delete a single SMS/MMS (confirm + Undo window), Kotlin `deleteMessage` seam | open |
| M17 | Voice note playback with speed (1x/1.5x/2x) and scrubbing (Kotlin MediaPlayer seam) | open |
| M18 | Global message search with jump-to-message (SMS provider query + loaded WA chats) | open |
| M19 | Date/time detection ("tomorrow 15:00", "12.11.2026") -> Remind me / Copy | open |
| M20 | Select mode: copy/forward several messages as one block with names and times | open |
| M22 | "Message {sender}" privately from a group message strip (opens the 1:1 chat) | open |
| M23 | Local star for SMS/MMS (starred.json), SMS starred list | open |
| M24 | Jump to date in a conversation (date chooser, loads older pages) | open |
| M25 | Copy a single link / Share link pill next to each detected URL | open |
| M26 | Save a shared contact card as .vcf / Add to contacts from a vCard with all fields | open |
| M27 | Clear chat (SMS: delete thread messages with confirm; WA: delete for me loaded) | open |
| -- | Reply, Star, Copy, Share, Edit, Delete for me/everyone, reactions | done (pre-existing) |
| -- | Pin / mute / archive / mark read per chat; spam, block | done (pre-existing) |
| -- | Draft persistence, scheduled send (SMS local, WA gateway) | done (pre-existing) |
| -- | SMS segment counter, link pills, OTP Copy code | done (pre-existing) |

## Plan, round 2 (pure logic first; every step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
`df` showed 5.6 GB free at planning time: run `rm -rf target/debug/incremental` before building.
Library-crate items are `pub` (no dead-code issue); app-crate pure helpers not yet called use
`#[cfg_attr(not(test), allow(dead_code))]` until a later step uses them. One focused commit per
step, prefix `Rovsti messages: `. Never invent GPUI APIs: only calls already used in the app,
`apps/phone`, or verified in the pinned checkout. Every action gives a pill; failures say what
happened; `rovsti_android::Error::Unsupported` -> "That needs an Android phone.".

### Step 1. Pure: forward plan and message info (rovsti_core)
- New `crates/rovsti_core/src/forward.rs` (`pub mod forward;` in `lib.rs`):
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub enum ForwardBody { Text(String), Picture { caption: Option<String> } }`
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum ForwardTo { Sms, WhatsApp }`
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub enum ForwardPlan { Text(String), Picture { caption: Option<String> }, Nothing }`
  - `pub fn plan(body: &ForwardBody, to: ForwardTo) -> ForwardPlan`: trims text; blank text ->
    `Nothing`; picture caption trimmed, blank -> `None`; pictures go to both channels (SMS as
    MMS). `to` is kept for future rules (document it); SMS text longer than
    `MAX_SMS_FORWARD_CHARS = 5000` -> `Nothing`.
  - `pub fn notice(title: &str, ok: bool) -> String`: "Forwarded to Ada" / "Couldn't forward to Ada"
    (blank title -> "Forwarded" / "Couldn't forward").
  - `pub fn target_label(title: &str, to: ForwardTo, both: bool) -> String`: "Ada" or, when the
    person has both channels in the chooser, "Ada · WhatsApp" / "Ada · SMS".
  - Tests: `blank_text_forwards_nothing`, `caption_is_trimmed_and_optional`,
    `pictures_go_to_both_channels`, `notice_names_the_chat`, `label_names_the_channel_when_both`.
- New `crates/rovsti_core/src/message_info.rs` (`pub mod message_info;`):
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum InfoState { Pending, Sent, Delivered, Read, Played, Failed, Received }`
    with `pub fn label(self) -> &'static str` ("Sending", "Sent", "Delivered", "Read", "Played",
    "Not sent", "Received").
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum InfoChannel { WhatsApp, Sms, Mms }`
  - `#[derive(Clone, Debug, Default)] pub struct InfoInput<'a> { pub channel: Option<InfoChannel>, pub at_ms: i64, pub outgoing: bool, pub state: Option<InfoState>, pub author: Option<&'a str>, pub edited_ms: Option<i64>, pub forwarded: bool, pub starred: bool, pub sim: Option<&'a str>, pub text: Option<&'a str>, pub media: Option<&'a str> }`
    (`media` is the caller's short description, e.g. "Photo, 1280 × 720").
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub struct InfoRow { pub label: &'static str, pub value: String }`
  - `pub fn rows(input: &InfoInput<'_>, locale: LocaleTime) -> Vec<InfoRow>`, in this order and
    only when known: "From" (author, incoming), "Sent"/"Received" (`call_notes::when_line`),
    "Status" (outgoing: `state.label()`), "Edited" (time), "Forwarded" ("Yes"), "Starred" ("Yes"),
    "Via" ("WhatsApp"/"SMS"/"MMS"), "SIM", "Length" (SMS: `"152 characters, 2 SMS"` from
    `sms::segments`; other: `"152 characters"`, chars counted with `chars().count()`), "Media".
  - `pub fn info_text(rows: &[InfoRow]) -> String`: "Sent: Thu 5 Nov 2026, 14:32\n..." for Copy.
  - Tests: `incoming_wa_rows`, `outgoing_sms_shows_status_sim_and_segments`,
    `edited_and_forwarded_flags`, `unknown_fields_are_left_out`, `info_text_joins_lines`.

### Step 2. Pure: unread jump, quick replies, starred rows (rovsti_core)
- New `crates/rovsti_core/src/unread_jump.rs`:
  - `#[derive(Clone, Debug, Default, PartialEq, Eq)] pub struct JumpState { away: bool, unseen: u32 }`
    with `pub fn scrolled(&mut self, at_end: bool)` (at end: `away=false, unseen=0`),
    `pub fn arrived(&mut self, incoming: u32)` (counts only while `away`),
    `pub fn visible(&self) -> bool`, `pub fn badge(&self) -> Option<String>` ("3", "99+", `None` at 0).
  - `pub fn unread_divider(incoming: &[bool], unread: u32) -> Option<usize>`: index of the item
    where the last `unread` incoming items begin (oldest-first input). `None` when `unread == 0`
    or no item is incoming; when fewer than `unread` incoming items are loaded, the first
    incoming index.
  - `pub fn divider_label(unread: u32) -> String`: "1 unread message" / "5 unread messages".
  - Tests: `badge_counts_only_while_away`, `badge_caps_at_99`, `reaching_the_end_clears`,
    `divider_before_the_last_unread_incoming`, `divider_with_fewer_loaded`, `divider_label_plural`.
- New `crates/rovsti_core/src/quick_replies.rs`:
  - `pub const MAX_REPLIES: usize = 20; pub const MAX_REPLY_CHARS: usize = 500;`
  - `pub fn defaults() -> Vec<String>`: "On my way.", "Can I call you later?",
    "I'm driving, I'll reply soon.", "Thanks!", "Running 10 minutes late."
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq, thiserror::Error)] pub enum ReplyError { Empty, TooLong, Duplicate, Full }`
    with `#[error(...)]` user texts ("Type a reply first", "Keep it under 500 characters",
    "That reply is already in the list", "The list is full (20 replies)").
  - `pub fn add(list: &mut Vec<String>, text: &str) -> Result<usize, ReplyError>` (trim; duplicate
    by `search::fold` equality; appends, returns index).
  - `pub fn remove(list: &mut Vec<String>, index: usize) -> Option<String>`;
    `pub fn restore(list: &mut Vec<String>, index: usize, text: String)` (insert clamped, for Undo,
    no-op when already present); `pub fn move_up(list: &mut [String], index: usize) -> bool`.
  - `pub fn insert_into(draft: &str, reply: &str) -> String`: blank draft -> reply; else
    `draft.trim_end() + " " + reply`.
  - `pub fn chip_label(reply: &str) -> String`: first 28 chars + "…" when longer, one line.
  - Tests: `add_trims_and_rejects_blank`, `add_rejects_duplicates_folded`, `add_stops_when_full`,
    `remove_then_restore_round_trips`, `move_up_swaps`, `insert_appends_with_space`,
    `chip_label_shortens`.
- New `crates/rovsti_core/src/starred.rs`:
  - `pub struct StarCandidate<'a> { pub id: &'a str, pub author: &'a str, pub preview: &'a str, pub at_ms: i64, pub starred: bool }`
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub struct StarredRow { pub id: String, pub author: String, pub preview: String, pub at_ms: i64 }`
  - `pub fn starred_rows(items: &[StarCandidate<'_>]) -> Vec<StarredRow>`: starred only, newest first,
    preview collapsed to one line, max 120 chars + "…".
  - `pub fn empty_text(partial: bool) -> &'static str`: "No starred messages" / "No starred
    messages in the loaded part of this chat".
  - Tests: `keeps_starred_newest_first`, `preview_is_one_line_and_short`, `empty_text_partial`.

### Step 3. UI: Forward (M9)
- New `apps/messages/src/forward_ui.rs`:
  - `#[derive(Clone, Debug)] pub(crate) struct ForwardItem { pub body: ForwardBody, pub picture: Option<ViewerItem> }`
    (`ViewerItem`/`ViewerSource` from `media_viewer.rs` locate the picture).
  - `pub(crate) struct ForwardTarget { pub thread: ThreadId, pub title: SharedString, pub label: SharedString, pub initials: SharedString, pub channel: Channel }`
  - `pub(crate) fn targets(rows: &[RowView], query: &str, exclude: Option<&ThreadId>) -> Vec<ForwardTarget>`
    (pure, tested in this file): rows from `inbox::filter_rows`, one target per thread in
    `RowView::threads`, label via `forward::target_label(title, to, threads.len() > 1)`, skip
    `exclude` (the source chat stays offered only when it is not the open one; simply skip it),
    archived/spam rows are not in `view` so they are not offered. Tests:
    `one_target_per_thread`, `query_filters_rows`, `excludes_the_source_chat`.
  - `pub(crate) struct ForwardSheet { pub item: ForwardItem, pub field: Entity<TextField>, pub exclude: Option<ThreadId> }`
    and a `render` helper `forward_overlay(...)` modelled on `channel_pick_overlay`
    (main.rs ~2199): title "Forward to", the search field (same `TextField::with_input(Text, Search)`
    as the Chats search), a scrollable list of rows (avatar initials, label), ✕ to close.
- Entry points:
  - WA strip (`conversation.rs::actions`): "Forward" pill (`Icon::LucideForward`) when not
    revoked and either `copy_text_of` is `Some` (text/caption) or the body is `MediaKind::Image`
    with a message id (picture = `ViewerItem` built like the `OpenMedia` items). Emits new
    `ConversationEvent::Forward(ForwardItem)`.
  - SMS selection pills (`sms_conversation.rs::bubble`): "Forward" for settled items with text
    (`copy_text`) or an image part (`ViewerSource::File`). New `SmsConversationEvent::Forward(ForwardItem)`.
  - Viewer bottom bar (`media_viewer.rs`): a third button "Forward" next to Save/Share, enabled when
    the full file is loaded; `MediaViewer` gets `pub fn forward_item(&self) -> Option<ForwardItem>`
    (picture with the current item's caption if known, else `None`); main.rs opens the chooser
    and closes the viewer.
- main.rs: `forward: Option<ForwardSheet>`; drawn above the page, below the pill; Back and
  `back_enabled` include it. Tapping a target closes the sheet and runs
  `fn run_forward(&mut self, item: ForwardItem, target: ForwardTarget, cx)`:
  - `forward::plan` -> `Nothing`: pill "Nothing to forward".
  - Text to WA: background `gateway.send_text(&SendText { chat, text, quoted_id: None })`.
  - Text to SMS: new `Backend::forward_sms(&mut self, thread_id: i64, text: String, image_path: Option<String>, cx) -> bool`
    (body of `reply_sms` without its Notice; `image_path` -> `send_mms`). `false` -> fail pill
    (also when the SMS role is not held: "Set Rovsti as the SMS app to forward by SMS").
  - Picture: first a local file: `ViewerSource::File` path, or `Gateway::media(id)` written to
    `cache/forward/{cache_stem}.{files::ext_for_mime}` (reuse the viewer's cache file if it
    exists). WA target: `send_media_with(chat, MediaKind::Image, mime, caption, &MediaOptions::default(), bytes)`;
    SMS target: `forward_sms(thread, caption.unwrap_or_default(), Some(path))`.
  - Result pill `forward::notice(title, ok)`; on success a trailing "Open" (new
    `PillAction::OpenThread { thread: ThreadId, title: String }` calling `open_thread`).
  - While sending show "Forwarding…" pill; a second forward while one runs is allowed (each task
    reports its own pill).
- Commit: `Rovsti messages: UI: forward messages and pictures (M9)`.

### Step 4. UI: Starred messages list (M10)
- `main.rs::open_wa_page_menu`: row "Starred messages" (`Icon::LucideStar`) -> `page.open_starred(cx)`.
- `conversation.rs`: `starred_open: bool`; `fn starred_list(&self) -> Vec<StarredRow>` builds
  `StarCandidate`s from loaded, non-hidden, non-revoked items with `self.starred(id)` (override
  aware), author "You" / `display_name` (same as export), preview via `copy_text_of` or
  `meta::body_preview`. Sheet overlay inside the page (same glass sheet style as the delete
  menu): header "Starred messages" + ✕, rows (author, preview, `call_notes::when_line`-style
  date via the page locale), each with a trailing star button that unstars (`set_starred(id,false)`)
  and shows pill "Unstarred" through `ConversationEvent::Notice`. Row tap: close and
  `jump_to(id)` (existing glow). Empty: `starred::empty_text(self.has_older())`, and when
  `has_older()` a "Load earlier" pill that loads one page and rebuilds.
- Bubble: if not already drawn, a small `LucideStar` glyph (12 px, secondary colour) next to the
  time for starred messages (grep the meta line in `bubble`/`body` first).
- `go_back` closes the starred sheet first.
- Commit: `Rovsti messages: UI: starred messages list (M10)`.

### Step 5. UI: scroll-to-bottom button and unread divider (M12)
- `keyed_list.rs`: `pub(crate) fn at_end(&self) -> bool { self.state.is_scrolled_to_end().unwrap_or(true) }`.
- `conversation.rs` and `sms_conversation.rs`: `jump: JumpState`. In `render` call
  `self.jump.scrolled(self.list.at_end())` (cheap; or via `ListState::set_scroll_handler` if a
  render-time check is not enough; verify signature at list.rs:557). On each incoming live
  message (WA `on_event` new message not from me; SMS backend new incoming items) call
  `self.jump.arrived(n)` before syncing rows. When `jump.visible()`, draw a 40 px round glass
  button (`glass_icon_button(theme, Icon::LucideArrowDown, press, badge)` or the find bar's
  button style) at bottom-right above the composer with the `badge()` count in a small capsule;
  tap -> `self.list.scroll_to_end()`, `jump = JumpState::default()`.
- Unread divider: `Setup` gets `pub unread: u32` (main.rs passes the inbox row's/`wa_threads`
  unread when opening); SMS page constructor gets the thread's `SmsThreadRow::unread`. At the
  first page load only, compute `unread_jump::unread_divider` over loaded items (`!from_me` /
  `!outgoing`) and insert a new `Entry::Unread(u32)` (WA) / equivalent SMS row before that item,
  drawn as a centred capsule "5 unread messages" (`divider_label`), and `list.reveal(index)`
  instead of scrolling to the end. Removed when the user sends a message or leaves the chat.
- Commit: `Rovsti messages: UI: scroll-to-bottom button and unread divider (M12)`.

### Step 6. UI: Quick replies (M11)
- `prefs.rs`: `#[serde(default = "rovsti_core::quick_replies::defaults")] pub quick_replies: Vec<String>`
  in `MessagesPrefs` and `Default`; `reset()` keeps the user's quick replies (like
  `sim_choices`); update `RESET_CONFIRM` ("…Blocked numbers, quick replies and the WhatsApp link
  stay."). Test `old_prefs_file_gets_default_replies` (deserialise `{}`) and
  `reset_keeps_quick_replies`.
- `settings_ui.rs`: section "Quick replies": each reply a `list_row` with up (`LucideChevronUp`)
  and delete (`LucideTrash2`) buttons; delete emits `SettingsEvent::Undoable { text: "Reply removed", undo: SettingsUndo::QuickReply(index, text) }`
  (new variant, handled where `SettingsUndo` is restored in main.rs via `quick_replies::restore`);
  an add field (`TextField`, action Done) + "Add" pill; `ReplyError` text in the pill; success
  "Reply added". All edits through `backend.update_prefs`.
- Composers (WA `composer_bar` call site and SMS page): a `LucideZap` glass icon button in the
  leading slot (next to attach for WA; grep how `leading` is built), toggles a horizontally
  scrollable chip row above the bar (`glass_pill(theme, quick_replies::chip_label(r))`, row id +
  `overflow_x_scroll`). Chip tap: `composer.set(&quick_replies::insert_into(composer.text(), r))`,
  close the row, keep focus. Empty list: one chip "Add quick replies in Settings" that switches to
  the Settings tab (emit an event). The pages read the list from `backend.read(cx).prefs()` (grep
  the accessor) at render.
- Commit: `Rovsti messages: UI: quick reply templates (M11)`.

### Step 7. UI: Message info and Select text (M14, M16)
- New `apps/messages/src/info_ui.rs`: `pub(crate) struct InfoSheet { title: SharedString, rows: Vec<InfoRow>, text: Option<SharedString>, selection: gpui::TextSelectionHandle, select_mode: bool }`
  and a render fn drawing a glass bottom sheet. Two modes:
  - Info: label/value rows (secondary label, primary value), a "Copy" pill (`info_text`).
  - Select text: the full text as
    `InteractiveText::new(("select", key), StyledText::new(text)).selectable(colors.accent.opacity(0.35)).selection_handle(&handle)`
    inside a scrollable `div().id(..).overflow_y_scroll()`; pills "Copy selection" (reads
    `handle.selection(window)`, copies `snapshot.text[range]`; collapsed/none -> pill "Select some
    text first") and "Copy all". Verify every call against `gpui/src/elements/text.rs` and
    `text/selection.rs` at the pinned rev (import paths: grep `pub use` for `InteractiveText`,
    `TextSelectionHandle` in `gpui/src/elements/mod.rs` / `lib.rs`).
- WA strip: "Info" (`LucideInfo`) for every non-failed message: `InfoInput` from the item
  (channel WhatsApp, `state` from `meta.status` mapped Sent/Delivered/Read/Played, pending ->
  Pending, failed -> Failed, incoming -> Received; author for groups; edited/forwarded/starred;
  media from `MediaMeta` "Photo, 1280 × 720" / "Voice note, 0:42" via `duration_label`).
  "Select text" (`LucideTextCursor`) when `copy_text_of` is `Some` and the text has more than
  one word. SMS selection pills: same two, with SIM name from the backend's sims list by
  `sub_id` (grep the existing SIM label helper used for the composer SIM chip), Sms/Mms channel by
  `SmsKey`, `DeliveryState` mapped (`None`+incoming -> Received).
- Pages emit `ConversationEvent::Info(InfoSheet)` / `SmsConversationEvent::Info(..)` or own the
  sheet themselves; prefer the page owning it (like `delete_confirm`), closed by Back first.
- Commit: `Rovsti messages: UI: message info and select text (M14, M16)`.

### Step 8. UI: inbox avatar viewer (M21), docs and backlog
- `inbox.rs` row avatar (the element built from `RowAvatar`): own id + `on_click` that stops
  propagation (`cx.stop_propagation()` as used elsewhere; grep) and opens the profile photo the
  same way as the header avatar (step-5-of-R1 code in main.rs: `ViewerSource::WaPicture` /
  `ContactPhoto`); no photo -> the tap opens the chat as before. Long-press menu unaffected.
- `docs/messages-acceptance.md`: add manual checks for M9-M12, M14, M16, M21 (what to tap, what
  pill to expect, desktop `Unsupported` pills).
- This file: mark the shipped rows "done R2", note anything deferred; the remaining backlog
  (M13, M15, M17-M20, M22-M27) is round 3.
- Commit: `Rovsti messages: UI: inbox avatar viewer (M21), docs, backlog`.
