# util-messages: everyday utilities for Rovsti Messages (round 4)

baseSha: 550a12dd01e23acf443ec1016f05ccb6074cefaf

## Audit (what exists at baseSha)

Rounds 1-3 (`util-messages.md`, `-r2.md`, `-r3.md`, commits 5d053ee..550a12d) shipped M1-M19,
M21, M22, M25. `git stash list` is empty. Re-checked by grep for the open rows:

- M20 select mode: none. WA long-press opens the quick strip (`conversation.rs` `strip:
  Option<String>`, `quick_strip` ~3250, pills Copy/Delete/Reply/Forward/Message privately/Share/
  Copy link/Add to calendar). SMS long-press sets `selected: Option<u64>` (`sms_conversation.rs`
  ~190, pills ~1960-2040). Single delete-for-me is `Conversation::delete_for_me(id)` (~733),
  star is `set_starred(id, starred)` (~701, gateway `StarMessage`, `star_override`). Forward is
  one `forward_ui::ForwardItem { body: ForwardBody, picture }` via `ConversationEvent::Forward` /
  `SmsConversationEvent::Forward`. `chat_export::export_text` already formats "[14:05] Ada: text"
  with day lines.
- M23 SMS star: none. `rovsti_core::starred` only builds rows (`StarCandidate`, `starred_rows`,
  `empty_text`) for WA. SMS keys: `SmsKey::{provider_id, from_provider_id}` ("sms:12"/"mms:7").
  Prefs: `apps/messages/src/prefs.rs::MessagesPrefs` (serde, `reset()` keeps SIM choices and
  quick replies).
- M24 jump to date: none. Search-hit jump exists: `global_search::{JumpTarget, JumpStep,
  jump_step, jump_notice}`, `Conversation::continue_goto` (~1313) and
  `SmsConversation::continue_goto` (~562) load older pages with `self.load(Some(oldest+1))`,
  `goto_pages` counts pages; reveal via `jump_to(id)` / `glow(key)`. Day picker precedent:
  `schedule_ui::CustomPick` (`step_day`, `day_label`).
- M26 contact card: WA `SharedContact { display_name, vcard }` shows name + first number;
  `card_actions` gives Call/Message/Add to contacts(number only)/Copy. `rovsti_core::vcard::parse`
  -> `ContactDraft { given, family, org, note, numbers, emails: Vec<DraftField> }` exists.
  `telecom::add_contact(number)` only puts PHONE in `ACTION_INSERT_OR_EDIT`. No .vcf save in
  Messages (but `documents::export_file(name, mime, path)` is generic). MMS `text/x-vcard`
  parts only show `part_label` + "Save file".
- M27 clear chat: SMS already has "Delete conversation" (`Backend::delete_thread`). WA has no
  gateway "clear chat" endpoint (`rovsti_wa_client::http` has `delete_for_me` per message only).
- M28 share non-image file: MMS non-image parts have Save only (`saveable_parts`, `save_label`);
  WA documents/audio/video have Save only (`save_media` ~1718 downloads to
  `cache/wa-save-<stem>` then `documents::export_file`). `media::share_image(path, mime, title)`
  -> Kotlin `shareImage` (`RovstiBridge.kt:795`); `MediaShare.mimeFor` knows images only and
  `stageForShare` names the copy after the source file (MMS part files may lack an extension).
- M29 mute durations: the chat row menu offers 8 hours / 1 week / Always for WA
  (`main.rs` `mute_choices`, `MUTE_8H_MS`, `MUTE_WEEK_MS`, ~2682). The header bell
  (`ConversationEvent::ToggleMute` / `SmsConversationEvent::ToggleMute` ->
  `main.rs::toggle_open_mute` ~3305) mutes "always" with no choice; SMS mute is a plain tag set
  (`prefs.muted: BTreeSet<String>`), no expiry.
- Not present anywhere (new candidates, round 5+): inbox filter chips, swipe-to-reply,
  double-tap to react, "Remind me" on a message, chat text size.

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
| M20 | Select mode: tick several messages; Copy (one block with names and times), Forward as one text, Star/Unstar, Delete for me (WA) / Delete (SMS, Undo), Select all loaded | planned R4 (S1, S4, S5) |
| M23 | Local star for SMS/MMS (kept in prefs), "Starred messages" sheet for SMS threads | planned R4 (S1, S3, S5) |
| M24 | Jump to date in a conversation (day picker, loads older pages, glows first message of that day) | planned R4 (S2, S6) |
| M29 | Header bell asks 8 hours / 1 week / Always (WA via gateway, SMS local with expiry); "Muted until …" pill | planned R4 (S2, S3, S6) |
| M26 | Shared contact card: "View contact" sheet with every number/email, Add to contacts with all fields, Save .vcf, Share card; also for MMS vCard parts | planned R4 (S2, S3, S7) |
| M28 | Share a single non-image file (MMS part; WA document/audio/video) through the share sheet | planned R4 (S3, S7) |
| M27 | Clear chat | cut: SMS = existing Delete conversation; WA = M20 Select all + Delete for me (no gateway clear endpoint, no gateway change in this pass) |
| M30 | Inbox filter chips: All / Unread / WhatsApp / SMS (count in each) | open (R5) |
| M31 | Swipe a bubble right to reply (spring snap-back, haptic tick) | open (R5) |
| M32 | Double-tap a WA bubble to react with the first quick reaction | open (R5) |
| M33 | "Remind me" on a message: local notification at a chosen time that opens and glows it | open (R5) |
| M34 | Chat text size (small / default / large) in Settings, applied to bubbles | open (R5) |
| M35 | Copy a location as a maps link / share location card | open (R5) |

## Plan, round 4 (pure logic first; every step leaves all checks green)

Conventions as in R3: library-crate items are `pub` with doc comments; every new
`rovsti_core` module is added to `crates/rovsti_core/src/lib.rs` (`pub mod …;`) in
alphabetical order; app-crate pure helpers that are not called yet get
`#[cfg_attr(not(test), allow(dead_code))]` only until the UI step that uses them, then the
attribute is removed. Before building, if `df -h /` shows < 6 GB free, run
`rm -rf target/debug/incremental`. Every step ends with the full check list from the task.
Commit messages: `Rovsti messages: <Pure|Android seams|UI>: <what> (Mxx)`.

### S1 Pure: multi-select and local stars (rovsti_core)

- `crates/rovsti_core/src/multi_select.rs` (new):
  - `pub const MAX_SELECTED: usize = 200;`
  - `#[derive(Clone, Debug, Default)] pub struct Selection<K: Ord + Clone> { keys: BTreeSet<K> }`
    with `new()`, `toggle(&mut self, key: K) -> bool` (true when now selected; refuses past
    `MAX_SELECTED`, returns false), `contains(&K) -> bool`, `len()`, `is_empty()`, `clear()`,
    `select_all<I: IntoIterator<Item = K>>(&mut self, keys: I) -> usize` (adds up to the cap,
    returns added), `retain(&mut self, keep: impl Fn(&K) -> bool)` (drop keys that left the
    timeline), `keys(&self) -> impl Iterator<Item = &K>`.
  - `pub fn title(count: usize) -> String` -> "1 selected" / "3 selected".
  - `pub fn copy_block(lines: &[ExportLine<'_>], tz_offset_s: i32) -> String`: one line ->
    its trimmed text only; several -> `chat_export::block_text` (below).
  - `pub fn star_all(states: &[bool]) -> bool`: star everything unless all are already
    starred (then unstar).
  - Notices: `copied_notice(n)` "Copied" / "Copied 3 messages"; `starred_notice(n, star)`
    "Starred 3 messages" / "Unstarred 1 message"; `deleted_notice(n)` "Deleted 3 messages for
    you"; `cap_notice()` "You can select up to 200 messages".
- `crates/rovsti_core/src/chat_export.rs`: factor the per-message loop of `export_text` into
  `pub fn block_text(lines: &[ExportLine<'_>], tz_offset_s: i32) -> String` (day lines +
  "[14:05] Ada: text", no "Chat with" header, no leading blank line); `export_text` becomes
  header + `"\n"` + block. Existing export tests must stay unchanged and green.
- `crates/rovsti_core/src/starred.rs`: local stars for SMS.
  - `#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)] pub struct LocalStar {
    pub thread_id: i64, pub at_ms: i64, pub author: String, pub preview: String }`
  - `#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)] #[serde(transparent)]
    pub struct LocalStars(BTreeMap<String, LocalStar>)` keyed by provider id ("sms:12").
    `pub const MAX_LOCAL_STARS: usize = 1000;` Methods: `is_starred(&str) -> bool`,
    `set(&mut self, id: &str, star: Option<LocalStar>) -> bool` (Some = star, None = unstar,
    returns changed; past the cap the oldest `at_ms` is evicted), `rows_for(thread_id) ->
    Vec<StarredRow>` (newest first, reuses `one_line`), `count_for(thread_id) -> usize`,
    `forget_thread(thread_id) -> bool` (after Delete conversation), `forget(id) -> bool` (after
    single delete).
- Tests: toggle/cap/select_all/retain; title; copy_block single vs several (day line,
  continuation indent); star_all mixed/all-starred/empty; notices plural/singular;
  block_text equals export_text minus header; LocalStars set/unset/cap eviction/rows_for
  ordering/forget_thread; serde round-trip `{"sms:1":{...}}`.
- Commit: `Rovsti messages: Pure: multi-select, block copy, local stars (rovsti_core)`.

### S2 Pure: jump to date, mute choices, shared contact card (rovsti_core)

- `crates/rovsti_core/src/date_jump.rs` (new):
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub struct DayPick { day: i64 }` (local day
    number as `call_log::day_key` gives). `today(now_ms, tz) -> Self`, `step_day(self, d: i32,
    now_ms, tz) -> Self` and `step_month(self, d: i32, now_ms, tz) -> Self` (never after today;
    month step keeps the day-of-month clamped to the month length), `start_ms(self, tz) -> i64`,
    `label(self, now_ms, tz) -> String` ("Today", "Yesterday", "Mon 9 Nov", "9 Nov 2025" when
    another year), `is_today(now_ms, tz)`.
  - `pub fn first_on_or_after(at_ms: &[i64], start_ms: i64) -> Option<usize>` (input oldest
    first; first index with `at_ms >= start_ms`).
  - `pub enum DateStep { Reveal(usize), LoadOlder, FurtherBack, Newest, Empty }` and
    `pub fn date_step(at_ms: &[i64], has_older: bool, pages_loaded: usize, start_ms: i64) ->
    DateStep`: oldest loaded `<= start_ms` or no older -> `Reveal(first_on_or_after)` or, when
    nothing is on/after that day, `Newest` (go to bottom); otherwise `LoadOlder` until
    `MAX_DATE_PAGES = 20` -> `FurtherBack`; empty timeline and no older -> `Empty`.
  - `pub fn date_notice(step: DateStep, label: &str) -> Option<String>`: `FurtherBack` ->
    "Messages from {label} are further back. Scroll up to load them."; `Newest` -> "No messages
    from {label} or later"; `Empty` -> "No messages yet"; `Reveal` when the revealed message is
    on a later day is decided by the caller with `starts_later(at_ms, start_ms, tz)` ->
    "No messages on {label}: showing the next day with messages". Expose
    `pub fn starts_later(at_ms: i64, start_ms: i64, tz: i32) -> bool`.
- `crates/rovsti_core/src/mute.rs` (new):
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum MuteFor { EightHours, Week, Always }`,
    `pub const ALL: [MuteFor; 3]`, `label(self) -> &'static str` ("8 hours", "1 week",
    "Always"), `until_ms(self, now_ms) -> Option<i64>` (`None` = always; constants
    `8 * HOUR_MS`, `7 * 24 * HOUR_MS`).
  - `pub fn muted_notice(until_ms: Option<i64>, now_ms, tz) -> String`: "Muted" for always,
    "Muted until 18:05" same day, "Muted until Sat 14 Nov 09:00" otherwise.
  - `pub fn is_active(until_ms: Option<i64>, now_ms) -> bool`.
  - Move `MUTE_8H_MS` / `MUTE_WEEK_MS` users in `main.rs` to `MuteFor::until_ms` in S6, not here.
- `crates/rovsti_core/src/shared_card.rs` (new):
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub struct CardView { pub name: String, pub org:
    String, pub numbers: Vec<CardField>, pub emails: Vec<CardField>, pub note: String }`,
    `pub struct CardField { pub value: String, pub label: String }` (label from
    `FieldKind`: "Mobile", "Home", "Work", custom label, "Other").
  - `pub fn from_vcard(display_name: &str, vcard: &str) -> CardView` via `vcard::parse`;
    first card; name falls back to `display_name`, then the first number; empty vcard -> name
    only.
  - `pub fn to_vcard(&CardView) -> String` via `vcard::write` (round-trips the fields).
  - `pub fn vcf_file_name(name: &str) -> String` -> "ada-lovelace.vcf" via
    `number_format::file_slug_opt`, fallback "contact.vcf".
  - `pub fn share_text(&CardView) -> String` ("Ada Lovelace\nMobile: +90 …\nada@x.org").
  - `pub fn insert_json(&CardView) -> String` (serde_json:
    `{"name","org","note","phones":[{"value","label"}],"emails":[…]}`) for the Kotlin insert.
  - `pub fn primary_number(&CardView) -> Option<&str>`.
- Tests: DayPick stepping across month/year ends, never after today, labels (today/yesterday/
  weekday/other year), tz offsets; first_on_or_after; every DateStep branch incl. cap;
  notices; MuteFor until values and labels, muted_notice same-day vs later; from_vcard with
  2 numbers + 2 emails + org, with `item1.TEL;waid=` style lines, with empty vcard;
  to_vcard round trip; vcf_file_name non-ASCII/blank; insert_json shape.
- Commit: `Rovsti messages: Pure: jump to date, mute choices, shared card (rovsti_core)`.

### S3 Android seams and prefs (rovsti_android, Kotlin, prefs.rs)

- `crates/rovsti_android/src/media.rs`: `pub fn share_file(path: &str, mime: &str, name: &str)
  -> Result<bool>` -> bridge `"shareFile"` (`Arg::Str` x3). Doc: any type; `name` is the file
  name the receiver sees. Desktop test `Error::Unsupported`.
- `crates/rovsti_android/src/telecom.rs`: `pub fn insert_contact(card_json: &str) ->
  Result<bool>` -> `bridge::call_bool_str("insertContact", card_json)`. Doc: opens the system
  "new contact" screen pre-filled; needs no permission. Desktop test.
- Kotlin `crates/rovsti_android/kotlin/dev/rovsti/bridge/MediaShare.kt`: `stageForShare(context,
  path, name: String? = null)` uses `safeName("${millis}-${name ?: source.name}", …)`;
  `mimeFor` adds pdf, vcf (`text/x-vcard`), txt, mp3, m4a/aac (`audio/mp4`), ogg/opus
  (`audio/ogg`), amr, 3gp (`video/3gpp`), mp4, zip; default stays octet-stream.
- Kotlin `RovstiBridge.kt`: `@JvmStatic fun shareFile(path, mime, name): Boolean` (like
  `shareImage`, type `mime.ifEmpty { "*/*" }`, stages with `name`); `@JvmStatic fun
  insertContact(json: String): Boolean`: parse with `org.json.JSONObject`, `Intent(Intent.
  ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI)` with `Insert.NAME`, `Insert.COMPANY`,
  `Insert.NOTES`, and `Insert.DATA` = `ArrayList<ContentValues>` of `Phone.CONTENT_ITEM_TYPE`
  (`Phone.NUMBER`, `Phone.TYPE` from label: Mobile/Home/Work else `TYPE_CUSTOM` + `LABEL`) and
  `Email.CONTENT_ITEM_TYPE` rows; malformed JSON -> false; `start(intent)`.
- `apps/messages/src/prefs.rs`:
  - `#[serde(default)] pub sms_stars: rovsti_core::starred::LocalStars` (kept by `reset()`,
    like quick replies).
  - `#[serde(default)] pub mute_until: BTreeMap<String, i64>`; `pub fn set_muted_until(&mut
    self, tag, until: Option<i64>)` (inserts into `muted`; `Some` also records the end, `None`
    clears it); `set_muted(tag, false)` also removes `mute_until[tag]`;
    `pub fn expire_mutes(&mut self, now_ms) -> bool` (drops tags whose end passed, from both
    maps); `pub fn mute_end(&self, tag) -> Option<i64>`. `rekey_thread` (~128, LID merge) moves the
    `mute_until` entry too.
  - Tests: old prefs file without the new fields loads; expire removes only passed ones;
    reset keeps sms_stars; LID merge carries the end.
- `apps/messages/src/main.rs`: call `prefs.expire_mutes(now_ms())` through
  `backend.update_prefs` at start-up and on each foreground resume (where `ui_active` flips on),
  and before the notification mute check (~2329). Nothing else in the UI yet.
- Commit: `Rovsti messages: Android seams: share file, insert contact; prefs: SMS stars, mute ends`.

### S4 UI: select mode in WhatsApp chats (M20)

- `apps/messages/src/conversation.rs`: `select: Option<Selection<String>>` (message ids).
  - Entry: "Select" pill (`Icon::LucideListChecks`, check the icon exists in the pinned
    gpui-fusion / rovsti_ui icon enum by grep; fall back to `LucideCheckSquare`) in
    `quick_strip`; it closes the strip and selects that message.
  - While selecting: taps toggle instead of opening media/strip; each bubble row shows a
    leading round check (filled accent when selected) and a tinted row background; long press
    toggles too. Messages that cannot be acted on (pending, revoked) are not selectable.
  - Header is replaced by a glass bar: Close (X), `multi_select::title(n)`, "Select all"
    (loaded, visible ones, cap pill on overflow), then actions: Copy, Forward, Star/Unstar
    (`star_all` over the selected `starred` states), Delete.
  - Copy: `chat_export::ExportLine` per selected message in timeline order (author = "You" or
    sender name exactly as the export builder at `conversation.rs` ~1820-1835 does), `copy_block`, `telecom::copy_text`,
    pill `copied_notice`, mode closes.
  - Forward: one `ForwardItem { body: ForwardBody::Text(copy_block…), picture: None }` via
    `ConversationEvent::Forward`; with one picture selected alone, reuse the existing single
    forward item (`forward_item(id)`).
  - Star: `set_starred(id, star)` for each id (existing `star_override` path), pill.
  - Delete: confirm card "Delete {n} messages for you?" (Delete red / Cancel), then
    `delete_for_me(id)` for each, pill `deleted_notice`. No "for everyone" in bulk.
  - Back (system back / `handle_back` ~593) and opening another chat close select mode first;
    `timeline` changes call `selection.retain(|id| still loaded)`.
- Keep GPUI glue thin: all strings and decisions come from `multi_select`.
- Acceptance doc rows (`docs/messages-acceptance.md`): M20 WA.
- Commit: `Rovsti messages: UI: select mode in WhatsApp chats (M20)`.

### S5 UI: select mode and stars in SMS chats (M20, M23)

- `apps/messages/src/sms_conversation.rs`: `select: Option<Selection<SmsKey>>`, "Select" pill in
  the selection pills (~1960), same bar and check marks as S4 (shared small helper in a new
  `apps/messages/src/select_ui.rs`: `select_bar(theme, title, actions, cx)` and
  `check_mark(theme, selected)`; S4's inline version moves here).
  - Copy / Forward as in S4 (author "You" / thread title); Star via `prefs.sms_stars.set(
    provider_id, Some(LocalStar{…}))` (Local keys are skipped: no provider id);
    Delete: confirm, then the existing pending-delete flow of M15 for every key
    (`PendingDeletes::add`, one Undo pill "Deleted 3 messages" with Undo for all).
- M23 single star: "Star"/"Unstar" pill (`LucideStar`) in the SMS selection pills; starred
  bubbles show a small star next to the time (like WA). Pill "Starred" / "Unstarred".
- SMS page menu (`main.rs::open_page_menu` ~1830): "Starred messages" row (count from
  `sms_stars.count_for(thread)`) opens a sheet like the WA one (`rows_for`, tap -> existing
  goto/`JumpTarget { id: provider_id, at_ms }` path -> glow; empty text from
  `starred::empty_text(false)`; row long-press offers Unstar).
- Delete conversation and single delete call `forget_thread` / `forget`.
- Commit: `Rovsti messages: UI: select mode and stars in SMS chats (M20, M23)`.

### S6 UI: jump to date and header mute durations (M24, M29)

- Jump to date (both pages): page menu row "Jump to date" (`LucideCalendarSearch` or
  `LucideCalendar`) opens a sheet with a `DayPick` row: "‹ month", "‹ day", label, "day ›",
  "month ›" (day/month forward disabled at today) and quick chips Yesterday / 1 week ago /
  1 month ago, then "Go". Go sets `date_goto = Some((DayPick, pages: 0))`; after every page
  load (where `continue_goto` is called today) run `continue_date_goto`, which feeds
  `date_step(at_ms of loaded items, has_older, pages, start_ms)`; `Reveal(i)` -> existing
  `jump_to(id)` / `glow(key)` plus `starts_later` notice; `Newest` -> scroll to bottom +
  notice; `LoadOlder` -> `load(Some(oldest + 1))`; others -> notice. Cancel stops it.
  Progress line "Loading older messages…" while it pages.
- M29: `ConversationEvent::ToggleMute` / `SmsConversationEvent::ToggleMute` when not muted
  open a small header menu (reuse `menu_card`) with `MuteFor::ALL`; WA -> existing
  `chat_action(jid, ChatActionKind::Mute { until_ms: choice.until_ms(now) })`; SMS ->
  `prefs.set_muted_until(tag, choice.until_ms(now))`. Pill `muted_notice(...)` with Undo
  (existing `PillAction::SmsMute` / WA undo). When muted, the bell unmutes directly as today.
  Replace the row-menu constants `MUTE_8H_MS` / `MUTE_WEEK_MS` with `MuteFor` and add the
  same three choices to the SMS row menu (`SmsAction::Mute`). Header subtitle shows
  "Muted until …" when an end is set (WA from `ChatMeta::muted_until_ms`, ignoring `MUTED_FOREVER`; SMS from `mute_end`).
- Commit: `Rovsti messages: UI: jump to date and mute durations (M24, M29)`.

### S7 UI: contact cards and file sharing (M26, M28)

- M26 WA: `CardAction` gains `View`; the card's pills become View contact / Call / Message /
  Add to contacts (now `telecom::insert_contact(shared_card::insert_json)`; when the number
  already matches a contact keep `edit_contact`) / Copy. "View contact" opens a sheet (name,
  org, every number with Call/Message/Copy, every email with Email/Copy, note) and footer pills
  "Save .vcf" (write `to_vcard` to `cache/card-<stem>.vcf`, `documents::export_file(
  vcf_file_name, "text/x-vcard", path)`, reuse `save_outcome` pills) and "Share"
  (`telecom::share_text(share_text)`). `contact_card` lists all numbers (max 3, "+2 more").
- M26 MMS: a `text/x-vcard`/`text/vcard` part is read (small file, ≤ 64 KB, background) and
  rendered with the same card + View sheet.
- M28: SMS selection pills add "Share file" (one non-image part) / "Share 1…n" next to Save
  (`media::share_file(path, mime, part.name)`); WA strip adds "Share" for document/audio/
  video media: download as `save_media` does (factor `download_media(id) -> Task<Result<
  PathBuf, String>>`), then `share_file(path, mime, file_name)`. Pills: Unsupported ->
  "Sharing needs an Android phone.", false -> "Couldn't open the share sheet".
- Commit: `Rovsti messages: UI: contact cards and file sharing (M26, M28)`.

### S8 Docs and backlog

- `docs/messages-acceptance.md`: rows for M20 (WA, SMS), M23, M24, M26, M28, M29, with the
  confirm/undo/pill expectations above.
- This file: mark shipped rows "done R4", note anything deferred; round 5 backlog is
  M30-M35 (WA clear chat stays cut unless the gateway grows an endpoint).
- Commit: `Rovsti messages: utilities R4 docs and backlog`.

## Round 5 backlog

M30 inbox filter chips, M31 swipe to reply, M32 double-tap react, M33 remind me on a message,
M34 chat text size, M35 copy/share location link.
