# util-messages: everyday utilities for Rovsti Messages (round 3)

baseSha: e6dfb3ed379aed3f5b37ac71b5b0675d0bc7a4d6

## Audit (what exists at baseSha)

Rounds 1-2 (`util-messages.md`, `util-messages-r2.md`, commits 5d053ee..e6dfb3e) shipped M1-M12,
M14, M16, M21. `git stash list` is empty. Re-checked by grep for the open rows:

- M13 gallery: none. WA bodies are `MessageBody::Media { kind, caption, mime, size }` with
  `MessageMeta::media` (`MediaMeta`, `voice_note`, file name); `Conversation::viewer_items(id)`
  builds the viewer list; `cached_image`/`cached_thumb` exist; `spam::links(body)` /
  `spam::open_links(body, max) -> Vec<OpenLink { label, url }>` find URLs. Page menus:
  `main.rs::open_wa_page_menu` (~1595, "Starred messages" row ~2600) and `open_page_menu` (~1734).
- M15 single SMS delete: only `rovsti_android::sms::delete_thread` (Kotlin
  `SmsQuery.deleteThread`, bridge `smsDeleteThread`, RovstiBridge.kt:381). No per-message delete.
  SMS item keys: `rovsti_core::sms_timeline::SmsKey::{Sms(i64), Mms(i64), Local(u64)}`.
  `Backend::delete_thread` shows the SMS-role / failure handling to copy.
- M17 voice notes: WA audio is "Tap to save" only (`conversation.rs::body`, `saveable`); no
  playback anywhere. No Kotlin player (`KeypadTone.kt`, `AlarmSound.kt` are tone players only).
- M18 global search: the Chats search (`main.rs::search_query`, `inbox::filter_rows`) matches
  titles/snippets only. SMS query lives in `SmsQuery.messagesJson` (Kotlin) /
  `rovsti_android::sms::messages`; WA history only via `Gateway::messages_with_meta(chat, before_ms, limit)`.
  `Conversation::jump_to(id, cx)` (conversation.rs:1075) glows a loaded message.
- M19 dates: `text_detect` explicitly ignores dates (`ignores_dates_amounts_otp_and_urls`).
  `rovsti_core::call_log::civil_from_days` is `pub(crate)`; `reminder.rs` has local-day helpers
  (private). No calendar intent in `rovsti_android`.
- M25 link copy: links have only "Open" pills (`conversation.rs` ~3063, SMS ~980).
- M22, M20, M23, M24, M26, M27: none.
- Desktop: `df` showed 6.7 GB free at planning; if under 6 GB run `rm -rf target/debug/incremental`.

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
| M13 | Per-chat media / links / docs gallery (tabs, month headers, tap opens viewer / link / jump) | done R3 |
| M25 | Copy link pill next to each detected URL; long links shortened in the gallery | done R3 |
| M15 | Delete a single SMS/MMS (confirm, 5 s Undo window, then provider delete) | done R3 |
| M22 | "Message privately" from a group message strip (opens the 1:1 WA chat) | done R3 |
| M17 | Voice note / audio playback: play/pause, scrub bar, 1x/1.5x/2x speed, remembered speed | done R3 |
| M18 | Global message search (SMS provider + recent WA chats) with jump-to-message | done R3 |
| M19 | Date/time detection ("tomorrow 15:00", "12.11.2026") -> Add to calendar / Copy | done R3 |
| M20 | Select mode: copy/forward several messages as one block with names and times | open (R4, round-4 leftover) |
| M23 | Local star for SMS/MMS (starred.json), SMS starred list | open (R4, round-4 leftover) |
| M24 | Jump to date in a conversation (date chooser, loads older pages) | open (R4, round-4 leftover) |
| M26 | Save a shared contact card as .vcf / Add to contacts with all fields | open (R4, round-4 leftover) |
| M27 | Clear chat (SMS: delete thread messages with confirm; WA: delete for me loaded) | open (R4, round-4 leftover) |
| M28 | Copy / Share a single MMS attachment that is not an image (audio, vCard, file) | open (R4, round-4 leftover) |
| M29 | Per-chat notification override shortcut in the header (mute 8 h / 1 week / always) | open (R4, round-4 leftover) |
| -- | Reply, Star, Copy, Share, Edit, Delete for me/everyone, reactions | done (pre-existing) |
| -- | Pin / mute / archive / mark read per chat; spam, block | done (pre-existing) |
| -- | Draft persistence, scheduled send (SMS local, WA gateway) | done (pre-existing) |
| -- | SMS segment counter, link pills, OTP Copy code | done (pre-existing) |

## Plan, round 3 (pure logic first; every step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
Library-crate items are `pub`; app-crate pure helpers not yet called use
`#[cfg_attr(not(test), allow(dead_code))]` until a later step uses them. One focused commit per
step, prefix `Rovsti messages: `. Never invent GPUI APIs: only calls already used in
`apps/messages`, `apps/phone`, or verified in the pinned checkout. Every action gives a pill;
failures say what happened; `rovsti_android::Error::Unsupported` -> "That needs an Android phone.".
Gateway changes (`services/wa_gateway`) are out of scope.

### Step 1. Pure: chat gallery, pending deletes, date detection (rovsti_core)
- `crates/rovsti_core/src/call_log.rs`: add `pub(crate) fn days_from_civil(y: i64, m: u32, d: u32) -> i64`
  (inverse of `civil_from_days`, test `civil_round_trip` over 1970..2100 sample days).
- New `crates/rovsti_core/src/chat_gallery.rs` (`pub mod chat_gallery;`):
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub enum GalleryKind { Image, Video, Audio, Doc, Other }`
  - `pub struct GalleryInput<'a> { pub id: &'a str, pub at_ms: i64, pub kind: Option<GalleryKind>, pub text: Option<&'a str>, pub file_name: Option<&'a str>, pub size: Option<u64> }`
    (`kind: None` = plain text message).
  - `#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)] pub enum GalleryTab { #[default] Media, Links, Docs }` with `label()` ("Media", "Links", "Docs").
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub struct LinkEntry { pub id: String, pub at_ms: i64, pub url: String, pub host: String }`
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub struct DocEntry { pub id: String, pub at_ms: i64, pub name: String, pub detail: String }` (detail "PDF · 1.2 MB" / "Audio · 340 KB").
  - `#[derive(Clone, Debug, Default, PartialEq, Eq)] pub struct Gallery { pub media: Vec<(String, i64)>, pub links: Vec<LinkEntry>, pub docs: Vec<DocEntry> }`
  - `pub fn build(items: &[GalleryInput<'_>]) -> Gallery`: newest first; Image/Video -> media;
    Audio/Doc/Other -> docs (name = file_name or "Audio"/"Document"); every URL in `text`
    (`spam::links`) -> links, deduplicated by URL keeping the newest.
  - `pub fn month_label(ms: i64, tz_offset_s: i32) -> String`: "November 2026".
  - `pub fn sections<T>(items: &[T], at: impl Fn(&T) -> i64, tz_offset_s: i32) -> Vec<(String, std::ops::Range<usize>)>`: consecutive month groups.
  - `pub fn size_label(bytes: u64) -> String`: "820 B", "34 KB", "1.2 MB" (grep for an existing
    size formatter in apps/messages `media_details` first; move it here if it is pure and reuse).
  - `pub fn short_url(url: &str, max: usize) -> String`: strips scheme and `www.`, ellipsis at `max`.
  - `pub fn empty_text(tab: GalleryTab, partial: bool) -> &'static str` ("No photos or videos",
    "No links", "No documents"; partial adds " in the loaded part of this chat").
  - Tests: `splits_media_links_docs`, `links_deduplicate_keep_newest`, `newest_first`,
    `month_sections_group_consecutive`, `month_label_respects_offset`, `size_labels`,
    `short_url_strips_scheme`, `empty_text_partial`.
- New `crates/rovsti_core/src/pending_delete.rs`:
  - `pub const UNDO_MS: i64 = 5_000;`
  - `#[derive(Clone, Debug, Default)] pub struct PendingDeletes<K> { items: Vec<(K, i64)> }` with
    `pub fn add(&mut self, key: K, now_ms: i64)` (re-add refreshes), `pub fn undo(&mut self, key: &K) -> bool`,
    `pub fn contains(&self, key: &K) -> bool`, `pub fn due(&mut self, now_ms: i64) -> Vec<K>` (removes
    and returns keys older than `UNDO_MS`), `pub fn drain(&mut self) -> Vec<K>` (leaving the page
    commits everything), `K: PartialEq + Clone`.
  - `pub fn notice(count: usize) -> String`: "Message deleted" / "3 messages deleted".
  - Tests: `due_after_window`, `undo_removes`, `re_add_refreshes`, `drain_returns_all`.
- New `crates/rovsti_core/src/date_detect.rs`:
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub struct FoundDate { pub span: std::ops::Range<usize>, pub start_ms: i64, pub all_day: bool, pub label: String }`
  - `pub fn detect(body: &str, now_ms: i64, tz_offset_s: i32, max: usize) -> Vec<FoundDate>`:
    `d.m.yyyy`, `d/m/yyyy`, `yyyy-mm-dd` (day-first; invalid dates rejected), optional following
    `HH:MM` / `at HH:MM` / `saat HH:MM`; `today`/`tomorrow`/`tonight` (20:00) and Turkish
    `bugün`/`yarın` (case-insensitive, word boundaries) with optional time; a bare `HH:MM` only
    together with a day word. Years 2000..2100 only; `12.5` / amounts / OTP digits are not dates.
    Dates more than 1 day in the past are dropped. Label like "Thu 12 Nov, 15:00" /
    "Thu 12 Nov 2026" (all day; year shown when not the current year).
  - `pub fn calendar_end_ms(found: &FoundDate) -> i64`: +1 h, all-day +1 day.
  - Tests: `dotted_and_iso_dates`, `day_word_with_time`, `turkish_day_words`,
    `bare_time_needs_day_word`, `invalid_dates_rejected`, `past_dates_dropped`,
    `amounts_and_codes_are_not_dates`, `label_formats`, `respects_max`.
- Commit: `Rovsti messages: Pure: chat gallery, pending deletes, date detection (rovsti_core)`.

### Step 2. Pure: voice player state and global search (rovsti_core)
- New `crates/rovsti_core/src/voice_player.rs`:
  - `#[derive(Clone, Copy, Debug, PartialEq, Default, serde::Serialize, serde::Deserialize)] pub enum Speed { #[default] X1, X1_5, X2 }`
    with `next(self)`, `factor(self) -> f32`, `label(self) -> &'static str` ("1×", "1.5×", "2×").
  - `#[derive(Clone, Debug, Default, PartialEq, serde::Deserialize)] pub struct PlayerState { pub key: String, pub playing: bool, pub position_ms: u64, pub duration_ms: u64, pub ended: bool, pub error: Option<String> }`
    (the Kotlin JSON shape; `serde(default)` on fields).
  - `pub fn parse_state(json: &str) -> PlayerState` (bad JSON -> default).
  - `pub fn progress(position_ms: u64, duration_ms: u64) -> f32` (0..=1, 0 when duration 0).
  - `pub fn seek_target(fraction: f32, duration_ms: u64) -> u64` (clamped).
  - `pub fn time_label(position_ms: u64, duration_ms: u64, playing: bool) -> String`: while
    playing/paused mid-way "0:12 / 0:42", idle "0:42"; hours as "1:02:03".
  - Tests: `speed_cycles`, `parse_state_tolerates_garbage`, `progress_clamps`,
    `seek_target_clamps`, `time_labels`.
- New `crates/rovsti_core/src/global_search.rs`:
  - `pub const MIN_QUERY_CHARS: usize = 2; pub const MAX_HITS: usize = 200;`
  - `pub struct SearchCandidate<'a> { pub chat_key: &'a str, pub chat_title: &'a str, pub message_id: &'a str, pub author: Option<&'a str>, pub text: &'a str, pub at_ms: i64 }`
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub struct SearchHit { pub chat_key: String, pub chat_title: String, pub message_id: String, pub at_ms: i64, pub snippet: String, pub highlight: std::ops::Range<usize> }`
  - `pub fn search(query: &str, items: &[SearchCandidate<'_>]) -> Vec<SearchHit>`: folded match via
    `search::fold` (diacritics/case, same as `chat_find`), newest first, max `MAX_HITS`, query
    under `MIN_QUERY_CHARS` -> empty. Snippet: one line, ~30 chars of context before the match
    with leading "…", total max 90 chars; `highlight` is the byte range of the match inside
    `snippet` (char-boundary safe). Author prefix "Ada: " for groups is the caller's job.
  - `pub fn like_pattern(query: &str) -> String`: `%q%` with `%`, `_`, `\` escaped by `\` (for the
    Kotlin `LIKE ? ESCAPE '\'`).
  - `pub fn summary(hits: usize, partial: bool) -> String`: "12 messages" / "1 message" /
    "No messages found"; partial appends " · recent WhatsApp chats only".
  - Tests: `folds_case_and_diacritics`, `short_query_finds_nothing`, `newest_first_and_capped`,
    `snippet_context_and_highlight`, `snippet_multibyte_safe`, `like_pattern_escapes`, `summary_text`.
- Commit: `Rovsti messages: Pure: voice player state and global search (rovsti_core)`.

### Step 3. Android seams: message delete, SMS search, voice player, calendar (rovsti_android + Kotlin)
- `crates/rovsti_android/src/sms.rs`:
  - `pub fn delete_message(key: &str) -> Result<bool>` (`key` "sms:12" / "mms:7"; bridge
    `smsDeleteMessage`; needs the SMS role).
  - `pub fn search(like: &str, limit: usize) -> Result<String>` (bridge `smsSearch`; returns
    `Envelope<SmsMessagesPayload>` JSON with SMS rows only, newest first, each carrying its
    `thread_id` as the existing `SmsRow` does).
  - Desktop tests in the existing `tests` module (`Err(Error::Unsupported)`).
- Kotlin `SmsQuery.kt`: `deleteMessage(context, key)` (`contentResolver.delete(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI|Telephony.Mms.CONTENT_URI, id), null, null) > 0`,
  RuntimeException -> false) and `searchJson(context, like, limit)` (query `Telephony.Sms.CONTENT_URI`,
  same projection as `messagesJson`, selection `body LIKE ? ESCAPE '\'`, `"date DESC LIMIT n"`,
  `SmsJson.page(sms, emptyList(), n)`). Wire both in `RovstiBridge.kt` next to `smsDeleteThread`.
- New `crates/rovsti_android/src/voice.rs` (`pub mod voice;`, doc: Kotlin side `VoicePlayer`):
  `pub fn play(key: &str, path: &str, from_ms: u64, speed: f32) -> Result<bool>`,
  `pub fn pause() -> Result<bool>`, `pub fn seek(ms: u64) -> Result<bool>`,
  `pub fn set_speed(speed: f32) -> Result<bool>`, `pub fn stop() -> Result<bool>`,
  `pub fn state() -> Result<String>` (JSON for `voice_player::parse_state`). Check `bridge::Arg`
  for a float/long variant; if none, pass numbers as `Arg::Str` (as `sms::messages` does).
  Desktop test `desktop_is_unsupported`.
- New Kotlin `VoicePlayer.kt` (object): one `MediaPlayer`; `play` resets when `key` differs,
  `setDataSource(path)`, `prepare()`, `seekTo`, `playbackParams = playbackParams.setSpeed(speed)`
  (API 23+), requests transient audio focus (`AudioManager.requestAudioFocus` with
  `AudioFocusRequest`, `AUDIOFOCUS_GAIN_TRANSIENT`), `setOnCompletionListener` marks `ended`;
  `stateJson()` -> `{"key","playing","position_ms","duration_ms","ended","error"}`; errors caught
  into `error`. Bridge methods in `RovstiBridge.kt` (`voicePlay`, `voicePause`, `voiceSeek`,
  `voiceSpeed`, `voiceStop`, `voiceState`).
- New `crates/rovsti_android/src/calendar.rs`: `pub fn insert_event(title: &str, begin_ms: i64, end_ms: i64, all_day: bool) -> Result<bool>`
  (bridge `calendarInsert`; Kotlin `Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)`
  with `EXTRA_EVENT_BEGIN_TIME`, `EXTRA_EVENT_END_TIME`, `EXTRA_EVENT_ALL_DAY`, `Events.TITLE`,
  `FLAG_ACTIVITY_NEW_TASK`; `ActivityNotFoundException` -> false). Opens the user's calendar app
  (local, no permission). Desktop test.
- Docs: add the new bridge names to whatever list `docs/` keeps for the bridge (grep
  `smsDeleteThread` in `docs/`).
- Commit: `Rovsti messages: Android: message delete, SMS search, voice player, calendar seams`.

### Step 4. UI: chat gallery and Copy link (M13, M25)
- New `apps/messages/src/gallery_ui.rs`: `pub(crate) struct GallerySheet { tab: GalleryTab, gallery: Gallery, partial: bool }`
  and `pub(crate) fn render_gallery(...)` drawing a full-height glass sheet: header "Media, links
  and docs" + ✕, a segmented control for the three tabs (reuse the app's existing segmented /
  tab pill helper; grep `segmented` in `rovsti_ui`), content scrollable (`.id(..).overflow_y_scroll()`):
  - Media: month headers (`chat_gallery::sections`), 3-column grid of square thumbnails
    (`cached_thumb`/`cached_image`, else a kind icon tile; video tiles show `LucidePlay`).
    Tap: close sheet, emit `OpenMedia` with the viewer list positioned on that id (`viewer_items`).
  - Links: rows (host bold, `short_url(url, 48)` secondary, date); tap opens (`cx.open_url`),
    trailing Copy button -> `copy_text` + pill "Link copied"; row long tap -> jump to the message.
  - Docs: rows (icon, name, detail, date); tap closes and `jump_to(id)` so the existing
    save/open card is used.
  - Empty: `empty_text(tab, partial)`; when `partial` a "Load earlier" pill (one page, rebuild).
- WA `conversation.rs`: `gallery: Option<GallerySheet>`, `pub(crate) fn open_gallery(&mut self, cx)`
  building `GalleryInput`s from loaded, non-hidden, non-revoked items (kind from `MediaKind`,
  text from `copy_text_of`, file name from `MediaMeta`); `go_back` closes it first.
  SMS `sms_conversation.rs`: same, from `SmsItem` (image parts -> Media with `ViewerSource::File`,
  other parts -> Docs, body links -> Links).
- Page menus in `main.rs` (`open_wa_page_menu`, `open_page_menu`): row "Media, links and docs"
  (`Icon::LucideImage`; grep the icon enum) -> `page.open_gallery(cx)`.
- M25: next to each "Open link" pill in WA strip (~3063) and SMS selection pills (~980) add a
  "Copy link" pill (`LucideCopy`) for the first link -> copy + pill "Link copied".
- Tests in `gallery_ui.rs` for any pure mapping helper (e.g. `fn kind_of(MediaKind, Option<&MediaMeta>) -> GalleryKind`).
- Commit: `Rovsti messages: UI: media, links and docs gallery and Copy link (M13, M25)`.

### Step 5. UI: delete one SMS/MMS with Undo, Message privately (M15, M22)
- `backend.rs`: `pub fn delete_sms_message(&mut self, thread_id: i64, key: SmsKey, cx) ` (role check
  like `delete_thread`; `SmsKey::Local` -> drop locally; background `sms::delete_message`; failure
  -> `BackendEvent::Notice("Couldn't delete that message")`; success -> re-pull that thread's
  newest page via the existing refresh path; preview backend: remove from the fake thread).
- `sms_conversation.rs`: "Delete" pill (`LucideTrash2`) in the selection pills for settled items;
  first tap opens a confirm sheet "Delete this message? It is removed from this phone." (reuse the
  WA `delete_confirm` sheet look). Confirm -> `pending: PendingDeletes<SmsKey>`; pending items are
  hidden from rows; pill via new `SmsConversationEvent::Deleted { count }` -> main shows
  `pending_delete::notice(1)` with "Undo" (new `PillAction::UndoSmsDelete(SmsKey)` calling
  `page.undo_delete(key)`). A 1 s `background_executor().timer` loop while `pending` is non-empty
  commits `due(now)` through `backend.delete_sms_message`; leaving the page / app pause commits
  `drain()`. No SMS role -> the existing NEEDS_ROLE pill instead of the confirm.
- M22 (WA groups): strip pill "Message {first name}" (`LucideMessageCircle`) on incoming group
  messages whose author JID is a user JID (not `@lid` without a phone; reuse `jid_user`), emits
  `ConversationEvent::OpenChat { jid, title }` handled like `PillAction::OpenChat` (opens or
  creates the 1:1 page). Not shown for own messages.
- `docs/messages-acceptance.md`: rows for M13, M25, M15, M22.
- Commit: `Rovsti messages: UI: delete one SMS with Undo and Message privately (M15, M22)`.

### Step 6. UI: voice note and audio player (M17)
- `prefs.rs`: `#[serde(default)] pub voice_speed: rovsti_core::voice_player::Speed` (test
  `old_prefs_file_defaults_speed`; `reset()` resets it).
- New `apps/messages/src/voice_ui.rs`: `pub(crate) struct VoiceController { state: PlayerState, loading: Option<String>, poll: Option<Task<()>> }`
  owned by `MessagesApp` (shared by WA and SMS pages through a `Entity<VoiceController>` passed in
  `Setup` / SMS constructor), with `toggle(key, path_or_fetch, cx)`, `seek_fraction(f32)`,
  `cycle_speed()` (persists via `backend.update_prefs`, calls `voice::set_speed`), `stop()`.
  While playing it polls `voice::state()` every 250 ms (`background_executor().timer`) and
  `cx.notify()`s; stops polling on `ended`/pause/error. Errors -> pill "Couldn't play this
  recording"; `Unsupported` -> "That needs an Android phone.".
- WA bubble (`conversation.rs::body`) for `MediaKind::Audio`: replace "Tap to save" by a player
  row: play/pause glass button (`LucidePlay`/`LucidePause`), a scrub bar (a track `div` with a
  filled part; tap/drag on it uses `on_mouse_down` + `on_mouse_move` with the element bounds the
  viewer already uses for drag math — grep `media_viewer.rs` for the bounds pattern, no new API),
  `time_label`, a speed capsule (`Speed::label`) shown while this note is the active one. First
  play fetches bytes with `Gateway::media(id)` into `cache/voice/{id}.{files::ext_for_mime}`
  ("Loading…" while fetching), then `voice::play`. Played incoming voice notes call the existing
  `mark_played` path. Keep "Save" available in the strip for audio (existing save code).
- SMS/MMS audio parts: same row with the part file path.
- Leaving the page or the app pausing calls `stop()`.
- Commit: `Rovsti messages: UI: voice note player with speed and scrubbing (M17)`.

### Step 7. UI: global message search with jump (M18)
- New `apps/messages/src/global_search_ui.rs`: `pub(crate) struct GlobalSearch { field: Entity<TextField>, hits: Vec<SearchHit>, running: Option<Task<()>>, partial: bool, wa_cache: Vec<(String, String, Vec<RichMessage>)> }`.
  Entry: Chats search shows, under the matching chats, a "Messages" section once the query has
  `MIN_QUERY_CHARS`; debounce 300 ms (timer). Sources in a background task:
  SMS `sms::search(&global_search::like_pattern(q), 100)` decoded via `backend::decode`, titles
  from `backend.threads()` / contacts; WA: on first use per search session fetch
  `messages_with_meta(chat, None, 100)` for the 15 most recent WA chats into `wa_cache`, then
  `global_search::search` over both. Rows: chat title, date, snippet with the `highlight` range
  in accent colour (`StyledText::with_highlights` only if already used in the repo; else bold
  split into three `div` children), `summary(hits, partial)` footer.
- Tap a hit: `open_thread(thread, title)` with a new `jump: Option<JumpTarget { id: String, at_ms: i64 }>`
  in WA `Setup` and the SMS constructor. The page loads older pages until the id is loaded or
  10 pages are loaded, then `jump_to(id)` (existing glow); otherwise pill "That message is
  further back. Scroll up to load it.".
- No SMS read permission -> SMS part skipped, footer says so; WA not linked -> WA part skipped.
- Commit: `Rovsti messages: UI: global message search with jump (M18)`.

### Step 8. UI: date pills (M19), docs and backlog
- WA strip and SMS selection pills: for the first `date_detect::detect(text, now, tz, 1)` hit a
  pill "Add to calendar · {label}" (`LucideCalendar`) -> `calendar::insert_event(title, start, calendar_end_ms, all_day)`
  where title is "{chat title}: {first 40 chars of the message}"; `Ok(false)` -> "No calendar app
  found"; plus "Copy date" pill copying the matched text. Use `backend.locale().tz_offset_s`.
- `docs/messages-acceptance.md`: rows for M17, M18, M19.
- This file: mark shipped rows "done R3"; note deferred items; remaining backlog (M20, M23, M24,
  M26-M29) is round 4.
- Commit: `Rovsti messages: UI: date pills (M19), docs, backlog`.

## Round 4 backlog (leftovers)
M20 select mode (copy/forward several), M23 local star for SMS/MMS, M24 jump to date,
M26 save contact card as .vcf, M27 clear chat, M28 copy/share non-image MMS attachment,
M29 mute duration shortcut in the header.
