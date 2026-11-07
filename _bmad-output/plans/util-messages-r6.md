# util-messages: everyday utilities for Rovsti Messages (round 6)

baseSha: 6837afec5206cb8cd7ffdb1d006b3aac6105563a

## Audit (what exists at baseSha)

Rounds 1-5 (`util-messages.md`, `-r2.md` .. `-r5.md`, last commit 6837afe "utilities review
fixes") shipped M1-M26 and M28-M38 (M27 cut). `git stash list` is empty. The R6 backlog
(M39-M44) was re-checked by grep; none of it exists:

- M39 inbox select mode: none. Long-press on an inbox row opens `RowMenu`
  (`main.rs::open_row_menu` ~1544) with WA `chat_action` (~2610, `ChatActionKind::{Archive,
  Pin, Mute, Unmute, MarkRead}` via the gateway) and SMS `sms_row_action` (~1767, local
  `SmsMarks` pins/archive/unread, mute tags, `backend.delete_thread`). `mark_all_read`
  (~2355) + `inbox::MarkAllTargets` is the only bulk path. `rovsti_core::multi_select::
  Selection<K>` (generic, `toggle`, `select_all`, `retain`, `title(n)`) is reusable.
- M40 maps links / coordinates in text: none. Only WA location cards have
  `CardAction::OpenMap { url }` with the app-local `geo_url` (`conversation.rs` ~5476).
  `rovsti_core::location_link` has `is_valid`, `osm_url`, `share_text`. Detection pills
  are built in the WA strip (`conversation.rs` ~4135-4215: Copy link, Add to calendar,
  `text_detect::actions`) and under SMS bubbles (`sms_conversation.rs` ~3069-3130).
- M41 notification "Remind me": none. `MessageNotifications.kt` (~111-125) adds Reply,
  Copy code, Mark read through `receiverIntent(context, tag, action, code, slot, mutable)`
  -> `ReplyReceiver` (`ACTION_REPLY/COPY/MARK_READ`). `MessageReminders.kt` (R5) arms
  JSON from `message_reminder::arm_json`; `MessageReminderItem.from` rejects a blank
  `message_id`. Headless kinds live in `apps/messages/src/headless.rs::handle` (table of
  `fn(&str, &str) -> String`).
- M42 header long-press copy: none. `conversation.rs::header_bar_with` (~5094) is a plain
  `div` title; called at `conversation.rs` ~4844 and `sms_conversation.rs` ~3250.
  `Div::on_long_press` exists in gpui-fusion (`elements/div.rs` ~388; needs `.id(..)`).
- M43 next unread chat: none.
- M44 WhatsApp OTP Copy code: SMS only. `sms_conversation.rs::copy_code` (~3798) + pill
  "Copy code" (~3120); inbox OTP chip is gated on `Channel::Sms` (`inbox.rs` ~800).
  WA notifications already get `otp` from `notifications::plan` (generic).

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| M1-M8 | Viewer, save/share, swipe, header photo, find, export, mark all read, phone/email pills | done R1 |
| M9-M12, M14, M16, M21 | Forward, starred, quick replies, scroll-to-bottom, info, select text, inbox avatar | done R2 |
| M13, M15, M17-M19, M22, M25 | Gallery, delete SMS + Undo, voice speed/scrub, global search, dates, message privately, copy link | done R3 |
| M20, M23, M24, M26, M28, M29 | Select mode, SMS stars, jump to date, contact cards, share file, mute durations | done R4 |
| M27 | Clear chat | cut, stays cut |
| M30-M38 | Inbox filters, swipe reply, double-tap react, remind me, text size, share location, SMS pin/archive/unread | done R5 |
| M44 | Copy code chip on WhatsApp OTP messages (bubble strip + inbox chip) | done R6 |
| M40 | Maps links / coordinates in text -> Open in Maps / Copy coordinates | done R6 |
| M42 | Header long-press: Copy name / Copy number / Copy WhatsApp id | done R6 |
| M43 | "Next unread" header chip jumps to the next unread chat | done R6 |
| M39 | Inbox select mode: tick chats; Mark read, Mark unread, Mute, Unmute, Pin, Unpin, Archive, Delete (SMS) in bulk | done R6 |
| M41 | "Remind me in 1 hour" action on message notifications | done R6 |
| M45 | Undo send for SMS: 4 s "Sending… Undo" hold before handing to the radio | open (R7) |
| M46 | Paste an image from the clipboard into the composer (WA and MMS) | open (R7) |
| M47 | "Delete codes after copying" option: OTP SMS removed 24 h after Copy code (local, confirm in Settings) | open (R7) |
| M48 | Export chat with media as a .zip (text + attachments), shared via the share sheet | open (R7) |
| M49 | Recent emoji row in the reaction picker (local LRU, top 6) | open (R7) |
| M50 | Tap a group sender name: mini card (Message privately, Call, Copy number, Add contact) | open (R7) |

## Plan, round 6 (pure logic first; every step leaves all checks green)

Conventions as in R5: library items are `pub` with doc comments; each new `rovsti_core`
module is added to `crates/rovsti_core/src/lib.rs` in alphabetical order; app-crate pure
helpers not called yet get `#[cfg_attr(not(test), allow(dead_code))]` until the UI step
that uses them. Never invent GPUI APIs: grep
`~/.cargo/git/checkouts/gpui-fusion-141089add1664e03/d6c8412/crates/gpui` first. Disk is
tight (6.3 GB free at audit): if `df -h /` shows < 6 GB free run
`rm -rf target/debug/incremental` before building. Every step ends green on:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
Commits: `Rovsti messages: <Pure|Android seams|UI>: <what> (Mxx)`, one focused commit per step.

### S1 Pure: places in text, copy choices, next unread (rovsti_core) (M40, M42, M43)

- `crates/rovsti_core/src/location_link.rs` (extend):
  - `#[derive(Clone, Debug, PartialEq)] pub struct Place { pub lat: f64, pub lon: f64,
    pub label: Option<String>, pub span: std::ops::Range<usize> }`.
  - `pub fn find_places(text: &str, max: usize) -> Vec<Place>`: recognises
    (a) `geo:41.0082,28.9784` (optional `?q=..(Label)`), (b) Google Maps URLs with
    `@lat,lon`, `?q=lat,lon`, `query=lat,lon`, `ll=lat,lon` (`google.com/maps`,
    `maps.google.*`), (c) OpenStreetMap `mlat=..&mlon=..` or `#map=z/lat/lon`,
    (d) Apple `maps.apple.com/?ll=lat,lon` / `q=`, (e) bare decimal pairs
    `41.0082, 28.9784` / `41.0082,28.9784` with >= 3 decimals each, (f) DMS
    `41°00'29.5"N 28°58'42.2"E` (N/S/E/W signs). Every result must pass `is_valid`;
    short links (`goo.gl/maps`, `maps.app.goo.gl`) are not resolved (they stay plain links).
    Dedup by rounded 5-decimal coordinates, in order of appearance.
  - `pub fn geo_url(lat: f64, lon: f64, label: Option<&str>) -> String`: move the app's
    `conversation.rs::geo_url` logic here (same output, percent-encoded label) and make
    the app call it; keep the app's existing tests passing (move them here).
  - `pub fn coords_text(lat: f64, lon: f64) -> String` -> `"41.008200, 28.978400"`.
- `crates/rovsti_core/src/copy_choices.rs` (new):
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub struct CopyChoice { pub label:
    &'static str, pub text: String, pub notice: &'static str }`.
  - `pub fn header_choices(name: &str, number: Option<&str>, wa_id: Option<&str>) ->
    Vec<CopyChoice>`: "Copy name" (skipped when blank or equal to the number by
    `text_detect::same_number`), "Copy number" (formatted as given), "Copy WhatsApp ID"
    (only for a group / LID / channel JID, i.e. when there is no number, the JID's user
    part for `@s.whatsapp.net`, the full JID otherwise). Notices: "Name copied", "Number
    copied", "ID copied".
- `crates/rovsti_core/src/unread_jump.rs` (extend):
  - `pub fn next_unread<K: PartialEq>(order: &[(K, bool)], current: Option<&K>) ->
    Option<usize>`: index of the first unread entry after `current` in list order,
    wrapping, never `current` itself; `None` when nothing else is unread.
  - `pub fn next_unread_label(others: usize) -> Option<String>`: `None` for 0,
    "Next unread" for 1, "Next unread · 3" otherwise.
- Tests: each place format incl. negative coords, DMS with S/W, label from `geo:?q=..()`,
  invalid ranges and (0,0) rejected, phone numbers / amounts / times (`12.30, 14.45`)
  not matched, short links skipped, dedup and `max`; geo_url (moved) and coords_text;
  header_choices for 1:1 SMS, saved contact, unknown number (name == number), WA group
  JID, LID JID; next_unread wraps, skips current, `None` cases; labels.
- Commit: `Rovsti messages: Pure: places in text, copy choices, next unread (rovsti_core)`.

### S2 Pure: inbox bulk actions (rovsti_core) (M39)

- `crates/rovsti_core/src/inbox_bulk.rs` (new):
  - `#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)] pub struct BulkFacts { pub
    unread: bool, pub muted: bool, pub pinned: bool, pub archived: bool, pub whatsapp:
    bool, pub sms: bool, pub sms_only: bool }`.
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)] pub enum BulkAction { MarkRead,
    MarkUnread, Mute, Unmute, Pin, Unpin, Archive, Unarchive, Delete }` with `label()`
    ("Mark read", "Mark unread", "Mute", "Unmute", "Pin", "Unpin", "Archive",
    "Unarchive", "Delete") and `is_destructive()` (Delete only).
  - `pub fn actions(selected: &[BulkFacts], in_archive: bool) -> Vec<BulkAction>`:
    empty for an empty selection; MarkRead if any unread else MarkUnread; Unmute if all
    muted else Mute; Unpin if all pinned else Pin (not in the archive); Archive or
    Unarchive by `in_archive`; Delete only when every selected row is `sms_only`.
  - `pub const WA_PIN_LIMIT: usize = 3;` and `pub fn pin_room(already_pinned_wa: usize,
    already_pinned_sms: usize, selected: &[BulkFacts]) -> Result<(), &'static str>`:
    `Err("You can pin up to 3 WhatsApp chats")` / `Err(thread_marks::TOO_MANY_PINS)` when
    pinning the unpinned selected rows would exceed a cap.
  - Notices: `pub fn done_notice(action, n) -> String` ("Marked 3 chats as read",
    "Muted 2 chats", "Pinned 1 chat", "Archived 4 chats", "Deleted 2 conversations", ...),
    `pub fn delete_prompt(n) -> String` ("Delete 2 conversations? This can't be undone."),
    `pub fn partial_notice(done, failed) -> String` ("Archived 3 chats; 1 failed").
- Tests: action set for mixed / all-muted / all-pinned / in-archive / any WA row hides
  Delete; pin_room both caps; every notice singular/plural; destructive flag.
- Commit: `Rovsti messages: Pure: inbox bulk actions (rovsti_core) (M39)`.

### S3 Pure + headless: chat reminder from a notification (M41)

- `crates/rovsti_core/src/message_reminder.rs`:
  - `pub fn chat_reminder(tag: &str, title: &str, preview: &str, minutes: i64, now_ms:
    i64) -> Option<MessageReminder>`: `None` for a blank tag or `minutes <= 0`;
    `message_id` = `""` (a chat reminder; the tap opens the chat, no glow), `at_ms =
    now_ms + minutes * 60_000`, `message_at_ms = now_ms`, title fallback "Message".
  - `for_message` returns `None` for a blank `message_id` (so chat reminders never badge a
    bubble). `parse_list` keeps entries with an empty `message_id`.
  - `pub fn chat_set_toast(r, now_ms, tz) -> String` = `set_text` (reuse).
- `apps/messages/src/headless.rs`: new kind `msg_remind` in `{tag, title, body, minutes?
  (default 60), now_ms, tz_offset_s}` -> `{ok: true, arm: <arm_json>, toast: "Reminder set
  for 18:05"}` or `{ok: false}`; add to the module doc table and `handle` TABLE.
- `apps/messages/src/backend.rs` (or where `message_reminders` is loaded): treat entries
  with an empty `message_id` as chat reminders (kept in the list for cancel/rearm only).
- Tests: chat_reminder None cases and times; for_message ignores blank ids; parse_list
  keeps a chat reminder; headless `msg_remind` ok/blank-tag/bad-JSON.
- Commit: `Rovsti messages: Pure: chat reminders and msg_remind headless kind (M41)`.

### S4 Android seams: "Remind me" notification action (M41)

- `MessageReminders.kt`: `MessageReminderItem.from` accepts a blank `message_id`; the fired
  notification's open intent puts `EXTRA_MESSAGE_ID`/`EXTRA_MESSAGE_AT` only when the id is
  non-blank.
- `MessageNotifications.kt`: a fourth action "Remind me in 1 hour" (`slot` 3, immutable)
  -> `ReplyReceiver.ACTION_REMIND = "dev.rovsti.bridge.MSG_REMIND"`, carrying
  `EXTRA_TITLE` (spec.title) and `EXTRA_BODY` (last line) extras. Not for OTP
  notifications (`spec.otp != null`). Check `tag.hashCode() * 4 + slot` request codes:
  slot 3 collides with `openIntent`'s `+ 3`; move both to a `* 8` scheme (Reply 0, Copy 1,
  Mark read 2, open 3, Remind 4).
- `ReplyReceiver.kt` ACTION_REMIND: `goAsync`, build the request with
  `TimeZone.getDefault().getOffset(now) / 1000`, `Headless.call("msg_remind", ..)`, on ok
  `MessageReminders.arm(context, arm)`, cancel the conversation notification, show
  `Toast.makeText(context, toast, LENGTH_SHORT)` on the main looper, and post
  `message_reminders_changed {}` through the bridge event path used by
  `message_reminder_fired` (no-op when no activity).
- `apps/messages/src/main.rs` / `backend.rs`: on `message_reminders_changed` reload with
  `rovsti_android::reminders::armed_messages()` + `parse_list`.
- No new Rust seam is needed (arming is Kotlin-side); add a desktop unit test that the
  event name parses in the app's event dispatch if there is a table for it.
- `docs/messages-acceptance.md`: rows for M41 (action present, toast, reminder fires and
  opens the chat without a glow, not on OTP notifications).
- Commit: `Rovsti messages: Android seams: Remind me notification action (M41)`.

### S5 UI: WhatsApp Copy code, places in text, header copy (M44, M40, M42)

- M44: `conversation.rs` new pure `pub(crate) fn copy_code_of(message, meta) -> Option<String>`
  (incoming, not revoked, `copy_text_of` + `otp::detect`), unit-tested. Show a "Copy
  code" pill under the incoming bubble (same look/id scheme as `sms-copy-code`) and first
  in the strip; tap -> `ConversationEvent::CopyCode(code)` -> `main.rs::copy_code`
  (sensitive clipboard, "Code copied" pill, existing `copied` state). `inbox.rs` ~800:
  drop the `Channel::Sms` gate so an unread WA OTP row shows the chip; update the
  `otp` inbox tests (add a WA case).
- M40: in the WA strip and SMS bubble pills, for `location_link::find_places(text, 1)`:
  "Open in Maps" (`Icon::LucideMapPin`, `cx.open_url(&geo_url(..))`; failure pill "No
  maps app found" if `open_url` reports one, else none) and "Copy coordinates"
  (`coords_text`, pill "Coordinates copied"). A place found inside a URL suppresses that
  URL's "Copy link" pill only when it is the same span.
- M42: give the header title column an id (`("conv-title", 0)`) and `on_long_press`
  (grep `div.rs` ~388 for the exact signature) in `header_bar_with` via a new optional
  `on_title_hold: Option<Box<dyn Fn(&mut Window, &mut App)>>` parameter (`header_bar`
  passes `None`; update `select_ui.rs` ~84). WA and SMS conversations open a small sheet
  (reuse `info_ui` sheet style or `RowMenu`-like list) listing `header_choices(..)`; tap
  copies via the existing Copy event, pill = `notice`. Haptic `Selection` on hold.
- `docs/messages-acceptance.md`: rows for M44, M40, M42.
- Commit: `Rovsti messages: UI: WhatsApp Copy code, maps in text, header copy (M44, M40, M42)`.

### S6 UI: Next unread chat (M43)

- `main.rs`: `fn next_unread_target(&self) -> Option<(ThreadId, String)>`: build `order`
  from `self.view.rows` (the normal inbox, not Spam/Archived, respecting
  `inbox_filter`), each `(row.newest().cloned(), row.unread > 0 || row.marked_unread)`,
  skipping muted rows; current = `open_thread_id(cx)`; `unread_jump::next_unread`.
- Pass `next_unread_label(count)` into the open `Conversation`/`SmsConversation` (a
  setter `set_next_unread(Option<SharedString>, cx)` called from `sync_chat_list` /
  inbox rebuild); they render a small glass chip in the header trailing area
  (`Icon::LucideChevronsRight`) and emit `ConversationEvent::NextUnread` /
  `SmsConversationEvent::NextUnread`; `main.rs` closes the page (drafts saved as on Back)
  and `open_thread`s the target; pill "No other unread chats" if it vanished meanwhile.
- Hidden in select mode, while find is open, and on narrow headers if the title would
  truncate below ~80 px (just hide when `label` is None).
- `docs/messages-acceptance.md`: row for M43.
- Commit: `Rovsti messages: UI: Next unread chat (M43)`.

### S7 UI: inbox select mode (M39)

- `main.rs`: `inbox_select: Option<Selection<String>>` keyed by a stable
  row key (newest `ThreadId` via `notifications::thread_tag`, as `String`), not the
  index. Enter by "Select" in the row long-press menu (new first item) or long-press
  while already selecting toggles. In select mode a row tap toggles; rows show a
  check circle in place of the avatar (`Icon::LucideCircleCheck` / `LucideCircle`, grep
  the icon enum); the Title item becomes "N selected" (`multi_select::title`) with
  Cancel and Select all; filter chips stay; Back cancels select mode first.
- Bottom glass action bar (over the tab bar, same recipe as the conversation select
  bar) lists `inbox_bulk::actions(..)` built from each selected row's `BulkFacts`
  (`inbox::row_facts` + prefs mute/pin/archive marks).
- Apply: MarkRead -> reuse `mark_all_read` with a `MarkAllTargets` of the selection (it
  already confirms and offers Undo for WA); MarkUnread -> WA `MarkRead { read: false }`,
  SMS `set_unread`; Mute -> `MuteFor` choices sheet then per row (WA gateway mute, SMS
  mute tag); Pin/Unpin -> `pin_room` check, then WA `Pin`, SMS `set_pinned`; Archive
  -> both channels as in the row menu, pill `done_notice` with Undo (new
  `PillAction::BulkUndo(Vec<(String, ChatActionKind)>, Vec<(i64, bool)>)`);
  Delete -> confirm card `delete_prompt(n)` (destructive style), then
  `backend.delete_thread` per SMS thread, pill `done_notice`. WA calls run with
  `quiet = true` and are counted; failures -> `partial_notice`. Exit select mode after
  any action.
- Pure glue helper in `inbox.rs`: `pub fn bulk_facts(row: &RowView, prefs: &Prefs, sms_only:
  bool) -> BulkFacts` with tests.
- `docs/messages-acceptance.md`: rows for M39 (enter/exit, Select all, each action, Undo,
  delete confirm, mixed rows hide Delete, pin caps).
- Commit: `Rovsti messages: UI: inbox select mode and bulk actions (M39)`.

### S8 Docs and backlog

- `docs/messages-acceptance.md`: rows for M39-M44 present with pill/confirm/undo
  expectations and desktop Unsupported behaviour.
- This file: mark shipped rows "done R6", note deferrals; round 7 backlog is M45-M50.
- Commit: `Rovsti messages: utilities R6 docs and backlog`.

## Shipped (R6) and deferrals

S1-S7 shipped as commits b63be10 .. 59ec7ab; M39-M44 are done R6 and have acceptance
rows 151-165 in `docs/messages-acceptance.md` (pill, confirm, Undo, desktop behaviour).
Deferrals: none of M39-M44 was cut. Unverified on a device (cargo only): the Kotlin
"Remind me" action (M41), `on_long_press` haptics (M42) and bulk Undo timing (M39); the
acceptance rows cover them for the next device pass.

## Round 7 backlog

M45 SMS undo send, M46 paste image from clipboard, M47 delete codes after copying,
M48 export chat with media (.zip), M49 recent emoji row, M50 group sender mini card.
