# util-messages: everyday utilities for Rovsti Messages (round 5)

baseSha: 6df9a5604c3cf4e7dff9baab23238f94f003e77a

## Audit (what exists at baseSha)

Rounds 1-4 (`util-messages.md`, `-r2.md`, `-r3.md`, `-r4.md`, last commit 6df9a56) shipped
M1-M26, M28, M29 (M27 cut). The parallel UX pass `ux-messages-r5.md` (P1-P7: Move to Spam,
drafts survive a restart, scheduled overview, Status "Message") is also in. `git stash list`
is empty. Re-checked by grep for the open rows:

- M30 inbox filter chips: none. `main.rs::sync_chat_list` (~4151) builds `ChatItem`s: Title,
  Search, banners, then `Status`, `ArchivedFilter`, `ScheduledFilter`, `SpamFilter`, rows.
  `ChatLayout` (~352) is the change key; row keys `key_of((1u8, show_spam, show_archived, row))`.
  `inbox::RowView` has `unread`, `marked_unread`, `badges`, `wa_chat`, `sms_thread`,
  `sms_sender` (None for a group SMS thread). Nothing filters by channel or unread.
- M31 swipe to reply: none. WA bubbles use `on_click`/`on_long_press`; reply is
  `Conversation::begin_reply(id, cx)` (~846). `rovsti_core::gesture` is the Phone answer
  swipe (vertical), not reusable as is. GPUI: `MouseMoveEvent::pressed_button`,
  `MouseMoveEvent::dragging()` exist (gpui `interactive.rs` ~531/548); the media viewer
  already drives drags with `on_mouse_down/move/up` (`media_viewer.rs` ~1186).
  Haptics: `rovsti_ui::haptics::perform(window, HapticFeedback::Selection)`.
- M32 double-tap react: none. `ClickEvent::click_count()` exists (gpui `interactive.rs` ~456;
  gpui_android passes the count, `bridge.rs` ~1737); `media_viewer.rs` ~1040 uses it.
  Reactions: `Conversation::react(message_id, from_me, sender, emoji, cx)` (~1854),
  `timeline.own_reaction(&id)`, `QUICK_EMOJIS[0] == "👍"` (~97).
- M33 remind me on a message: none. Phone has call-back reminders: `rovsti_core::reminder`
  (`Slot::{In15Min, In1Hour, Tonight, TomorrowMorning}`, `due_ms`, `label`, `clock`),
  `rovsti_android::reminders` -> Kotlin `CallReminders.kt` (`ReminderStore`, inexact
  `setAndAllowWhileIdle`, receiver declared in the Phone manifest only, tap = ACTION_DIAL).
  Messages notification taps: `MessageNotifications.openIntent` puts `EXTRA_TAG`
  ("open_thread_tag"); `RovstiBridge` ~469 posts `open_thread {tag}`;
  `notifications::OpenTag { tag }`; `main.rs::on_open_tag` (~3480). Jump to a message:
  `main.rs::pending_jump: Option<global_search::JumpTarget { id, at_ms }>` consumed on open
  (~1137 WA, ~1252 SMS).
- M34 chat text size: none. Bubbles use `typography::BODY.size` (liquid_glass_kit
  `TextStyle { size: px(16), line: px(22) }`): `conversation.rs` ~3264, 3472, 3574, 3614,
  `sms_conversation.rs` ~2434. Settings sections in `settings_ui.rs` (`section(theme, ..)`).
- M35 location: WA location cards offer "Open in Maps" (`geo_url`) and Copy (name, address,
  coordinates) via `CardAction` (`conversation.rs` ~5190-5260). No Share, no web link.
- New (round 5 audit): SMS threads cannot be pinned, archived or marked unread. Pin,
  archive and marked-unread exist for WhatsApp only (`ChatActionKind::{Pin, Archive,
  MarkUnread}` via the gateway; `inbox::is_archived` treats SMS threads as never archived,
  so archiving a person who also has SMS leaves the row in the inbox). `SmsAction`
  (`sms_actions.rs`) has MarkRead, Mute, Unmute, NotSpam, MoveToSpam, Block, Unblock, Call,
  AddContact, OpenContact, Delete.

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
| M9 | Forward a message (text, caption, picture) to any chat; also from the viewer | done R2 |
| M10 | Starred messages list per WhatsApp chat | done R2 |
| M11 | Quick reply templates | done R2 |
| M12 | Scroll-to-bottom button with count, unread divider | done R2 |
| M14 | Message info sheet | done R2 |
| M16 | Select text sheet | done R2 |
| M21 | Inbox avatar tap opens the profile photo viewer | done R2 |
| M13 | Per-chat media / links / docs gallery | done R3 |
| M25 | Copy link pill next to each detected URL | done R3 |
| M15 | Delete a single SMS/MMS (confirm, 5 s Undo, then provider delete) | done R3 |
| M22 | "Message privately" from a group message strip | done R3 |
| M17 | Voice note / audio playback: play/pause, scrub, 1x/1.5x/2x, remembered speed | done R3 |
| M18 | Global message search with jump-to-message | done R3 |
| M19 | Date/time detection -> Add to calendar / Copy | done R3 |
| M20 | Select mode (Copy block, Forward, Star, Delete, Select all) WA and SMS | done R4 |
| M23 | Local star for SMS/MMS, "Starred messages" sheet for SMS threads | done R4 |
| M24 | Jump to date in a conversation | done R4 |
| M29 | Header bell asks 8 hours / 1 week / Always; "Muted until …" | done R4 |
| M26 | Shared contact card: View contact, Add with all fields, Save .vcf, Share; MMS vCards | done R4 |
| M28 | Share a single non-image file (MMS part; WA document/audio/video) | done R4 |
| M27 | Clear chat | cut, stays cut (SMS = Delete conversation; WA = Select all + Delete for me; no gateway endpoint) |
| M30 | Inbox filter chips: All / Unread / WhatsApp / SMS / Groups, count in each, empty text | done (R5 S1, S4) |
| M36 | Pin SMS conversations (local, up to 5 local pins), pinned rows lead with a pin glyph | done (R5 S1, S4) |
| M37 | Archive SMS conversations (local); a mixed person row archives both channels; Undo | done (R5 S1, S4) |
| M38 | Mark an SMS conversation unread (local dot), cleared on open / Mark read / Mark all read | done (R5 S1, S4) |
| M31 | Swipe a WA bubble right to reply (axis lock, spring snap-back, haptic tick at threshold) | open (R5 S2, S5) |
| M32 | Double-tap a WA text bubble to react 👍 (again removes it), pill on failure | open (R5 S2, S5) |
| M33 | "Remind me" on a message: 15 min / 1 h / tonight / tomorrow; local notification opens the chat and glows the message; Cancel reminder | open (R5 S2, S3, S6) |
| M34 | Chat text size: Small / Default / Large / Larger in Settings, applied to bubbles | open (R5 S2, S7) |
| M35 | Location card: Share location (name, address, OpenStreetMap link), Copy link | open (R5 S2, S7) |
| M39 | Inbox select mode: tick chats; Mark read, Mute, Archive, Pin, Delete (SMS) in bulk | open (R6) |
| M40 | Maps link / coordinate detection in message text -> Open in Maps / Copy | open (R6) |
| M41 | "Remind me in 1 hour" action on message notifications | open (R6) |
| M42 | Header long-press: Copy name / Copy number / Copy WhatsApp id | open (R6) |
| M43 | Jump to the next unread chat from a conversation (header chip "Next unread") | open (R6) |
| M44 | Copy the code from a WhatsApp OTP message (same "Copy code" chip as SMS) | open (R6) |

## Plan, round 5 (pure logic first; every step leaves all checks green)

Conventions as in R4: library-crate items are `pub` with doc comments; each new
`rovsti_core` module is added to `crates/rovsti_core/src/lib.rs` (`pub mod …;`) in
alphabetical order; app-crate pure helpers that are not called yet get
`#[cfg_attr(not(test), allow(dead_code))]` until the UI step that uses them, then the
attribute is removed. Never invent GPUI APIs: grep
`~/.cargo/git/checkouts/gpui-fusion-141089add1664e03/d6c8412/crates/gpui` before using one.
Before building, if `df -h /` shows < 6 GB free, run `rm -rf target/debug/incremental`.
Every step ends green on the full check list:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
Commit messages: `Rovsti messages: <Pure|Android seams|UI>: <what> (Mxx)`; one focused
commit per step (others push to main in parallel).

### S1 Pure: inbox filters and local SMS thread marks (rovsti_core) (M30, M36-M38)

- `crates/rovsti_core/src/inbox_filter.rs` (new):
  - `#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)] pub enum InboxFilter {
    #[default] All, Unread, WhatsApp, Sms, Groups }`, `pub const ALL: [InboxFilter; 5]`,
    `label(self) -> &'static str` ("All", "Unread", "WhatsApp", "SMS", "Groups").
  - `#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)] pub struct RowFacts { pub unread:
    bool, pub whatsapp: bool, pub sms: bool, pub group: bool }` (`unread` = count > 0 or
    marked unread).
  - `pub fn matches(filter, facts: RowFacts) -> bool`.
  - `pub fn counts(facts: &[RowFacts]) -> [usize; 5]` (index = position in `ALL`).
  - `pub fn visible(counts: &[usize; 5]) -> Vec<InboxFilter>`: empty when the inbox has
    < 2 rows; otherwise All and Unread always, WhatsApp and SMS only when both channels
    have rows, Groups only when > 0.
  - `pub fn chip_label(filter, count) -> String`: "All", "Unread 3" (no count for All or
    for 0), "WhatsApp 12".
  - `pub fn empty_text(filter) -> &'static str`: "No unread chats", "No WhatsApp chats",
    "No SMS conversations", "No group chats", All -> "No conversations".
- `crates/rovsti_core/src/thread_marks.rs` (new): local marks for SMS threads.
  - `pub const MAX_LOCAL_PINS: usize = 5;`
  - `#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)] pub struct
    SmsMarks { #[serde(default)] pub pinned: BTreeSet<i64>, #[serde(default)] pub archived:
    BTreeSet<i64>, #[serde(default)] pub unread: BTreeSet<i64> }`.
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum PinError { TooMany }`.
  - Methods: `is_pinned(i64)`, `is_archived(i64)`, `is_marked_unread(i64)`;
    `set_pinned(&mut self, thread, pinned) -> Result<bool, PinError>` (true when changed;
    pinning also unarchives; `TooMany` past the cap); `set_archived(thread, archived) ->
    bool` (archiving also unpins); `set_unread(thread, unread) -> bool`;
    `clear_unread(thread) -> bool` (on open/Mark read); `forget(thread) -> bool` (after
    Delete conversation, from all three); `rekey(old, new) -> bool` (provider thread id
    changes are rare, keep symmetrical with `prefs.rekey_thread`).
  - Notices: `pin_notice(pinned) -> &'static str` ("Pinned" / "Unpinned"),
    `archive_notice(archived)` ("Archived" / "Unarchived"), `unread_notice()` ("Marked as
    unread"), `TOO_MANY_PINS: &str = "You can pin up to 5 SMS conversations"`.
- Tests: every `matches` arm incl. a mixed (WA+SMS) row counted under both channels;
  `counts`; `visible` (one channel only hides WhatsApp/SMS, Groups hidden at 0, tiny
  inbox hides all); `chip_label`; `empty_text`; pin cap and pin-unarchives/archive-unpins;
  `clear_unread`, `forget`, `rekey`; serde round-trip and an empty `{}` loading.
- Commit: `Rovsti messages: Pure: inbox filters and local SMS thread marks (rovsti_core)`.

### S2 Pure: bubble gestures, text size, location links, message reminders (rovsti_core) (M31-M35)

- `crates/rovsti_core/src/bubble_gesture.rs` (new):
  - Constants `LOCK_PX = 12.0`, `COMMIT_PX = 64.0`, `MAX_OFFSET_PX = 96.0`.
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum Axis { Undecided, Horizontal,
    Vertical }`.
  - `#[derive(Clone, Copy, Debug)] pub struct SwipeReply { start: (f32, f32), axis: Axis,
    offset: f32, armed: bool }`; `new(x, y) -> Self`; `moved(&mut self, x, y) -> SwipeMove`
    where `pub struct SwipeMove { pub offset: f32, pub crossed: bool }`: axis locks once
    `max(|dx|, |dy|) >= LOCK_PX` (horizontal only when `dx > 0 && dx > 1.5 * |dy|`,
    otherwise Vertical and every later call returns offset 0); offset = `dx` up to
    COMMIT_PX, then rubber band `COMMIT_PX + (dx - COMMIT_PX) * 0.35` capped at
    MAX_OFFSET_PX; `crossed` is true exactly once on the move that first reaches
    COMMIT_PX (haptic tick), and re-arms if the finger goes back under it.
    `axis(&self) -> Axis`, `is_horizontal(&self) -> bool`,
    `release(&self) -> bool` (reply when horizontal and offset >= COMMIT_PX).
  - `pub fn double_tap_reaction(own: Option<&str>, quick: &str) -> String`: `quick` unless
    our reaction already is `quick` (then `""` = remove).
- `crates/rovsti_core/src/text_scale.rs` (new):
  - `#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
    #[serde(rename_all = "snake_case")] pub enum ChatTextSize { Small, #[default] Default,
    Large, Larger }`, `ALL`, `label()` ("Small", "Default", "Large", "Larger"),
    `factor()` (0.875, 1.0, 1.15, 1.3), `pub fn scaled(self, base: f32) -> f32` rounded to
    0.5 px, `pub fn preview_text() -> &'static str` ("The quick brown fox…" sample line).
- `crates/rovsti_core/src/location_link.rs` (new):
  - `pub fn osm_url(lat: f64, lon: f64) -> String` ->
    `https://www.openstreetmap.org/?mlat=41.008200&mlon=28.978400#map=17/41.008200/28.978400`
    (6 decimals, clamps lat to ±90 and wraps lon to ±180).
  - `pub fn share_text(name: Option<&str>, address: Option<&str>, lat, lon) -> String`:
    trimmed non-empty name, address, then the link, one per line.
  - `pub fn is_valid(lat, lon) -> bool` (finite, in range; (0,0) counts as invalid).
- `crates/rovsti_core/src/message_reminder.rs` (new), reusing `reminder::Slot` and
  `reminder::clock`:
  - `#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)] pub struct
    MessageReminder { pub id: i64, pub tag: String /* "sms:12" | "wa:<jid>" */, pub
    message_id: String /* WA id or "sms:12"/"mms:7" */, pub message_at_ms: i64, pub at_ms:
    i64, pub title: String /* chat title */, pub preview: String }`.
  - `pub fn new_reminder(tag, message_id, message_at_ms, title, preview, slot: Slot, now_ms,
    tz) -> Option<MessageReminder>` (None for blank tag/message id; id = now_ms; preview via
    `starred::one_line`-style trimming to 80 chars; title fallback "Message").
  - `pub fn arm_json(&MessageReminder) -> String` (the struct plus finished
    `notif_title` = "Reminder: {title}" and `notif_body` = preview or "Open the message").
  - `pub fn parse_list(json: &str) -> Vec<MessageReminder>` (tolerant: bad entries skipped).
  - `pub fn for_message<'a>(list, message_id) -> Option<&'a MessageReminder>`;
    `pub fn set_text(&r, now_ms, tz) -> String` ("Reminder set for 18:05" / "… tomorrow at
    09:00"); `pub fn badge_text(&r, tz)` ("Reminder 18:05").
- Tests: axis lock both ways, leftward drag never moves, rubber band and cap, `crossed`
  once and re-arm, release; double_tap toggles; text sizes (labels, factors, rounding,
  serde "large"); osm_url format, clamping/wrap, share_text skipping blanks, is_valid;
  reminder new/None cases, arm_json keys, parse_list skipping garbage, set_text same-day vs
  tomorrow with tz offsets.
- Commit: `Rovsti messages: Pure: bubble gestures, text size, location links, message reminders (rovsti_core)`.

### S3 Android seams and prefs: message reminders, marks, text size (rovsti_android, Kotlin, prefs.rs)

- Kotlin `crates/rovsti_android/kotlin/dev/rovsti/bridge/MessageReminders.kt` (new), modelled
  on `CallReminders.kt` and reusing its `ReminderStore` class (generic over the JSON; give
  it the prefs name `rovsti_message_reminders`):
  - `data class MessageReminderItem(id, tag, messageId, messageAtMs, atMs, title, body, raw)`
    with a pure `parse` (null for id <= 0, blank tag or message id). If `ReminderStore` is
    typed to `ReminderItem`, make it generic `ReminderStore<T>(read, write, parse: (JSONObject)
    -> T?, id: (T) -> Long, raw: (T) -> JSONObject)` and keep `CallReminders` behaviour.
  - `object MessageReminders { arm, disarm, armedJson, rearmAll, fire }`, inexact
    `setAndAllowWhileIdle`, channel `message_reminders` ("Message reminders"), notification
    tapping opens the launch activity with `MessageNotifications.EXTRA_TAG = tag`, plus
    `EXTRA_MESSAGE_ID = "open_message_id"` and `EXTRA_MESSAGE_AT = "open_message_at"`;
    after posting it forgets the item and emits `message_reminder_fired {id}`.
  - `class MessageReminderReceiver : BroadcastReceiver` (same `goAsync` pattern).
- `RovstiBridge.kt`: `armMessageReminder(json)`, `disarmMessageReminder(id: String)`,
  `armedMessageReminders()`, `rearmMessageReminders()`; the open-intent handler (~469) reads
  the two new extras (removing them like `EXTRA_TAG`) and adds `message_id` / `at_ms` to the
  `open_thread` JSON when present.
- `apps/messages/platforms/android/app/src/main/AndroidManifest.xml`: `<receiver
  android:name="dev.rovsti.bridge.MessageReminderReceiver" android:exported="false" />` next
  to `ScheduledSmsReceiver` (~109).
- `crates/rovsti_android/src/reminders.rs`: `pub fn arm_message(json: &str) -> Result<bool>`,
  `disarm_message(id: i64) -> Result<bool>`, `armed_messages() -> Result<String>`,
  `rearm_messages() -> Result<bool>` with doc comments; extend the desktop
  `Error::Unsupported` test.
- `apps/messages/src/notifications.rs`: `OpenTag` gains `#[serde(default)] pub message_id:
  String` and `#[serde(default)] pub at_ms: i64`; test that the old `{tag}` JSON still parses
  and the new keys land.
- `apps/messages/src/prefs.rs`: `#[serde(default)] pub sms_marks:
  rovsti_core::thread_marks::SmsMarks` (kept by `reset()`, like `sms_stars`: they are data, not
  settings) and `#[serde(default)] pub chat_text_size: rovsti_core::text_scale::ChatTextSize`
  (reset to default). `rekey_thread` also calls `sms_marks.rekey`. Tests: old prefs file
  loads; reset keeps marks and resets text size; rekey carries marks.
- Commit: `Rovsti messages: Android seams: message reminders; prefs: SMS marks, text size`.

### S4 UI: inbox filter chips and SMS pin / archive / mark unread (M30, M36-M38)

- `apps/messages/src/inbox.rs`:
  - `Inputs` gains `pub sms_marks: &'a SmsMarks` (update the three test call sites and
    `main.rs` ~687 with `&backend.prefs().sms_marks`).
  - `is_archived`: an SMS thread counts as archived when `sms_marks.is_archived(id)`; a row
    is archived when every thread is (WA meta or SMS mark). `row_view`: `pinned` = WA pinned
    or any SMS thread pinned; `marked_unread` = WA flag or the newest SMS thread marked.
  - `pub fn row_facts(row: &RowView) -> inbox_filter::RowFacts` (`whatsapp` = `wa_chat` is
    Some or a WhatsApp badge, `sms` = `sms_thread` is Some, `group` = WA jid ends `@g.us` or
    `sms_thread.is_some() && sms_sender.is_none()`).
  - Tests: archived SMS row goes to `view.archived`, mixed row stays until both are
    archived, local pin leads, local unread mark shows the dot and is in
    `mark_all_targets`; `row_facts` for SMS group / WA group / mixed.
- `apps/messages/src/sms_actions.rs`: `SmsAction::{Pin, Unpin, Archive, Unarchive,
  MarkUnread}` (labels "Pin", "Unpin", "Archive", "Unarchive", "Mark as unread");
  `SmsActionContext` gains `pinned`, `archived`, `marked_unread`; row menus offer Pin/Unpin
  and Archive/Unarchive (not in Spam), MarkUnread when not unread and not marked; page menu
  offers Archive and MarkUnread only. Update `row()` / `menu()` test helpers; add tests.
- `apps/messages/src/main.rs`:
  - `inbox_filter: InboxFilter` field (session only), `ChatItem::Filters` pushed after
    `Status` when `query.is_empty()` and none of spam/archived/scheduled is showing and
    `inbox_filter::visible(..)` is non-empty; a horizontal row of `glass_pill` chips
    (`chip_label`), the selected one tinted with the accent like the gallery tabs; tap sets
    the filter, scrolls to top, `rows_dirty`. Rows are `(0..rows.len()).filter(|i|
    matches(filter, row_facts(&rows[i])))`; empty result -> a centred caption with
    `empty_text` and an "All" pill. Add the filter to `ChatLayout` and to the row keys.
    Leaving the archived/spam views or clearing the search keeps the filter; Back on the
    inbox with a non-All filter resets it to All first (before the app goes to the
    background).
  - `sms_row_action`: Pin/Unpin (`set_pinned`, `TooMany` -> `TOO_MANY_PINS` pill), Archive
    (pill "Archived" with Undo -> new `PillAction::SmsArchive { thread, archived }`),
    Unarchive, MarkUnread (pill `unread_notice`). All through `backend.update_prefs`.
  - WA row Archive/Unarchive on a mixed row (`row.sms_thread.is_some()`) also sets the SMS
    mark, so the person leaves the inbox; its Undo reverts both.
  - Opening an SMS thread, its MarkRead action and Mark all read call `clear_unread`.
    Delete conversation calls `sms_marks.forget`.
  - Check that `update_prefs` bumps `backend_epoch` (~450) so `InboxKey` rebuilds; if not,
    bump it in these handlers.
- `docs/messages-acceptance.md`: rows for M30, M36, M37, M38.
- Commit: `Rovsti messages: UI: inbox filters, SMS pin/archive/mark unread (M30, M36-M38)`.

### S5 UI: swipe to reply and double-tap to react in WhatsApp chats (M31, M32)

- `apps/messages/src/conversation.rs`:
  - Fields `swipe: Option<(String, SwipeReply)>` (message id) and `swipe_back:
    Option<(String, Spring)>` for the snap-back (use the spring already used for the
    selection/glow animations, or `rovsti_ui::motion`; grep, do not invent).
  - On each selectable, non-revoked, non-pending bubble row (not in select mode, not while
    the viewer is open): `on_mouse_down(MouseButton::Left)` starts `SwipeReply::new(x, y)`
    with `event.position`; `on_mouse_move` with `event.pressed_button ==
    Some(MouseButton::Left)` feeds `moved`; when horizontal, translate the bubble by
    `offset` (relative left margin/`left(px(offset))`, as the media viewer offsets its
    image), show a reply glyph (`Icon::LucideReply`) fading in by `offset / COMMIT_PX`, and
    call `cx.stop_propagation()` only while horizontal so vertical list scrolling keeps
    working; `crossed` -> `rovsti_ui::haptics::perform(window, HapticFeedback::Selection)`.
    `on_mouse_up` -> `release()` true -> `begin_reply(&id, cx)`; always start the snap-back
    spring; mark the following click as consumed (`swallow_click: bool`) so a swipe does not
    open media or the strip; long-press is ignored once horizontal.
  - Double-tap: in the bubble's `on_click`, when `event.click_count() >= 2` and the message
    is a plain text/caption bubble (media taps keep opening the viewer), call
    `react(id, from_me, sender, double_tap_reaction(own, QUICK_EMOJIS[0]), cx)`; existing
    react failure pill stays. Show a short pop (the reaction chip appears immediately via
    `set_own_reaction`). A single click keeps today's behaviour.
  - Reduced motion (`rovsti_ui` reduced-motion flag as used elsewhere in the file): no
    rubber band animation, snap back without spring.
- Keep logic in `bubble_gesture`; the glue only maps events.
- `docs/messages-acceptance.md`: rows for M31, M32 (incl. "vertical scroll unaffected",
  "left swipe does nothing", "double-tap media opens the viewer").
- Commit: `Rovsti messages: UI: swipe to reply and double-tap to react (M31, M32)`.

### S6 UI: Remind me on a message (M33)

- New `apps/messages/src/remind_ui.rs` (thin): a sheet listing `reminder::Slot::ALL` with
  `label(now, tz)`, plus a "Cancel reminder" danger row when `message_reminder::for_message`
  finds one; emits `RemindEvent::{Set(Slot), Cancel}`.
- `apps/messages/src/backend.rs`: `message_reminders: Vec<MessageReminder>`, loaded at start
  with `rovsti_android::reminders::armed_messages()` + `parse_list` (Unsupported -> empty),
  `rearm_messages()` once at launch; `arm_message_reminder(r, cx) -> Task<Result<bool,
  rovsti_android::Error>>`, `disarm_message_reminder(id, cx)`; on the `message_reminder_fired`
  event drop the item and notify.
- WA strip (`conversation.rs` quick strip) and SMS selection pills (`sms_conversation.rs`):
  "Remind me" pill (`Icon::LucideAlarmClock`, already used in the app) opens the sheet via
  `ConversationEvent::Remind { id, at_ms, preview }` / `SmsConversationEvent::Remind {..}`;
  `main.rs` builds the reminder with the open chat's tag and title, arms it, pill
  `set_text` with Undo (disarm); Unsupported -> "Reminders need an Android phone.",
  `Ok(false)` -> "Couldn't set the reminder". Cancel -> confirm card "Cancel the reminder?"
  then disarm, pill "Reminder cancelled".
- Bubbles with an armed reminder show a small `LucideAlarmClock` + `badge_text` next to the
  time.
- `main.rs::on_open_tag`: when `OpenTag.message_id` is non-empty, set `pending_jump =
  Some(JumpTarget { id, at_ms })` before `open_thread`, so the existing jump/glow runs.
- `docs/messages-acceptance.md`: rows for M33 (set, badge, cancel with confirm, notification
  tap opens and glows, desktop pill).
- Commit: `Rovsti messages: UI: Remind me on a message (M33)`.

### S7 UI: chat text size and location sharing (M34, M35)

- M34: `settings_ui.rs` new "Chats" section with a "Text size" row: a segmented control /
  four pills of `ChatTextSize::ALL` and a live preview bubble using `preview_text()`;
  change -> `backend.update_prefs(|p| p.chat_text_size = size)`, pill "Text size: Large".
  `conversation.rs` and `sms_conversation.rs` read `prefs().chat_text_size` once per render
  and replace the bubble body `typography::BODY.size` / line height uses (~3264, 3472, 3574,
  3614 and ~2434) with `px(size.scaled(16.0))` / `px(size.scaled(22.0))` through a small
  shared helper `fn bubble_text(size: ChatTextSize) -> (Pixels, Pixels)` in
  `apps/messages/src/select_ui.rs` or a new `apps/messages/src/text_size.rs`. Captions,
  times and headers stay fixed. A size change marks `rows_dirty` and resets the list's
  measured heights (the same reset the theme change uses) so rows re-measure. Settings
  Reset puts it back to Default.
- M35: `CardAction` gains `ShareLocation(String)` and `CopyLink(String)`; `card_actions`
  for a valid location returns Open in Maps, Share (`location_link::share_text`), Copy link
  (`osm_url`), Copy (existing text). Glue: Share -> `ConversationEvent::ShareText(text)` (or
  the existing share-text path, `telecom::share_text`), pills "Couldn't open the share sheet"
  / "Sharing needs an Android phone."; Copy link -> existing Copy event with the "Link
  copied" pill. Invalid coordinates keep only Copy. Update the `card_actions` unit tests.
- `docs/messages-acceptance.md`: rows for M34, M35.
- Commit: `Rovsti messages: UI: chat text size and location sharing (M34, M35)`.

### S8 Docs and backlog

- `docs/messages-acceptance.md`: make sure rows exist for M30-M38 with the
  confirm/undo/pill expectations above.
- This file: mark shipped rows "done R5", note deferrals; round 6 backlog is M39-M44.
- Commit: `Rovsti messages: utilities R5 docs and backlog`.

## Round 6 backlog

M39 inbox select mode, M40 maps link/coordinate detection, M41 "Remind me in 1 hour"
notification action, M42 header long-press copy name/number, M43 next unread chat,
M44 Copy code chip for WhatsApp OTP messages.
