# util-messages: everyday utilities for Rovsti Messages (round 1)

baseSha: 9919208386bf597db36dba01c721880fe530533a

## Audit (what exists at baseSha)

No earlier `util-messages*.md`; `git stash list` is empty. Earlier Messages rounds
(`messages*.md`, `ux-messages*.md` R1-R5) shipped a lot of the candidate list already. Checked
by grep at baseSha:

Already there (pre-existing, marked done below):
- WhatsApp message strip (`conversation.rs::actions`, `quick_strip`): emoji reactions, Reply,
  Star/Unstar (`set_starred`), Edit, Delete for me / for everyone (`RevokeMessage`,
  `DeleteForMe`), Copy (`copy_text_of`), Share (`ConversationEvent::Share`), up to 3 link pills
  (`rovsti_core::spam::open_links`), location/contact card actions (`CardAction`), tap on a quote
  jumps to it with a glow (`jump_to` -> `KeyedList::reveal`).
- SMS bubble selection (`sms_conversation.rs::bubble`): Copy, Share, Save picture/file
  (`saveable_parts`, `documents::export_file`), link pills, "Copy code" (`rovsti_core::otp`),
  Retry/Delete for failed sends, the segment counter (`segment_label` / `sms::segments`).
- Chats: Pin/Unpin, Mute (lengths), Archive, Mark read/unread per chat (`ChatActionKind`), SMS
  Mute/Spam/Block/Delete (`sms_actions.rs`), inbox search over title/snippet/digits
  (`inbox::filter_rows`), drafts kept across restarts (`drafts.rs`, `DRAFTS_FILE`), Send later for
  SMS (local `ScheduleQueue`) and WhatsApp (gateway), encrypted backup/restore.

Gaps (all confirmed absent by grep):
- **No image viewer at all.** A loaded WhatsApp image (`body()`, `cached_image`) is an `img()` with
  no handler; images are never saved to the gallery (`save_here` excludes `Image`/`Sticker`,
  stickers aside) nor shared as images. MMS pictures are only "Save picture" via Save as.
  The header avatar (`header_avatar`) has no tap. The Phone app's `apps/phone/src/photo_viewer.rs`
  (U1/U2 there) is the reference: `rovsti_core::zoom` math, `PinchEvent`, touch/mouse handlers,
  `rovsti_android::media::{save_image_to_gallery, share_image}` (Kotlin `MediaShare.kt` is already
  in the Messages build via `crates/rovsti_android/kotlin`). No new Kotlin needed.
- No search inside a conversation; no "Mark all read"; no chat export; no phone/email detection
  (only URLs and OTP codes); no forward; no starred list; no quick replies; no unread jump; no
  voice-note playback (audio is "Tap to save"); a settled SMS cannot be deleted singly
  (`rovsti_android::sms` has `delete_thread` only).

Gateway seams that exist (`rovsti_wa_client::Gateway`): `media(id)`, `thumbnail(id)`,
`picture(jid, full, known_id) -> Picture::{Image{id,bytes},Unchanged,None}`, `send_text`,
`send_media_with`, `star_message`, `messages_with_meta`, `chat_action`. No forward endpoint
(forward = re-send text/media through `send_text`/`send_media_with`).

## Utility backlog (ordered by user value)

| # | Utility | Status |
|---|---------|--------|
| M1 | Full-screen image viewer for WhatsApp images: pinch / double-tap zoom, pan, swipe down to close, tap hides chrome | R1 step 3 |
| M2 | Save image to gallery and Share image from the viewer (WA and MMS) | R1 step 3 |
| M3 | Swipe left/right between the chat's images in the viewer, "3 of 7"; MMS pictures open it too | R1 step 4 |
| M4 | Tap the conversation header avatar: full-screen profile photo (WA full picture, contact photo), Save/Share | R1 step 5 |
| M5 | Search in conversation (WA + SMS): find bar, "2 of 5", up/down, jump with glow | R1 step 6 |
| M6 | Export chat as text (Save as .txt) and Share as text, from the page menu | R1 step 7 |
| M7 | Mark all read (Chats title button, confirm, pill with count) | R1 step 7 |
| M8 | Phone number and email detection in messages: Call / Message / Email / Copy pills | R1 step 8 |
| M9 | Forward a message (text, caption, image) to another chat via a chat chooser | open |
| M10 | Starred messages list per WhatsApp chat (page menu), tap jumps to the message | open |
| M11 | Quick reply templates (local list in Settings, chip row in composer, insert) | open |
| M12 | Unread jump / scroll-to-bottom button with new-message count | open |
| M13 | Per-chat media/links/docs gallery (grid of loaded images, list of links, files) | open |
| M14 | Message info sheet: sent/delivered/read times (WA receipts, SMS state + SIM) | open |
| M15 | Delete a single SMS/MMS (confirm + Undo window), Kotlin `deleteMessage` seam | open |
| M16 | Select text: read-only selectable text sheet for long messages | open |
| M17 | Voice note playback with speed (1x/1.5x/2x) and scrubbing (Kotlin MediaPlayer seam) | open |
| M18 | Global message search with jump-to-message (SMS provider query + loaded WA chats) | open |
| M19 | Date/time detection ("tomorrow 15:00", "12.11.2026") -> "Remind me" / copy | open |
| M20 | Copy several messages (select mode) as one text block with names and times | open |
| M21 | Inbox avatar tap opens the profile photo viewer | open |
| -- | Reply, Star, Copy, Share, Edit, Delete for me/everyone, reactions | done (pre-existing) |
| -- | Pin / mute / archive / mark read per chat; spam, block | done (pre-existing) |
| -- | Draft persistence, scheduled send (SMS local, WA gateway) | done (pre-existing) |
| -- | SMS segment counter, link pills, OTP Copy code | done (pre-existing) |

## Plan, round 1 (pure logic first; every step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
`df` showed 6.1 GB free at planning time: run `rm -rf target/debug/incremental` first when under 6 GB.
Until a later step calls a new pure function, mark it `#[cfg_attr(not(test), allow(dead_code))]`
(app crate) or leave it `pub` (library crate) and drop the attribute when it is used. One focused
commit per step, prefix `Rovsti messages: ` (others push to main in parallel). Never invent GPUI
APIs: copy only calls already used in `apps/phone/src/photo_viewer.rs`, `conversation.rs` or the
pinned checkout; grep `gpui_icons` for every new `Icon::Lucide*` name (fallback to icons the app
already uses).

### Step 1. Pure: viewer paging and in-chat find (rovsti_core)
- `crates/rovsti_core/src/zoom.rs` add:
  - `pub const PAGE_SWIPE_FRACTION: f32 = 0.25;` `pub const PAGE_FLING_PX_S: f32 = 800.0;`
  - `pub fn page_drag(dx: f32, index: usize, count: usize) -> f32`: the shown horizontal shift;
    rubber-banded by `RUBBER` when dragging past the first (dx > 0 at 0) or last page.
  - `pub fn page_after_release(index: usize, count: usize, dx: f32, velocity_px_s: f32, width: f32) -> usize`:
    moves one page when `|dx| >= width * PAGE_SWIPE_FRACTION` or a fling (`|v| > PAGE_FLING_PX_S`
    and `|dx| >= FLING_MIN_PX` in the same direction); clamped to `0..count`; `count == 0` -> 0.
  - `pub fn page_caption(index: usize, count: usize) -> Option<String>`: `"3 of 7"`, `None` when
    `count <= 1`.
  - Tests: `page_moves_past_a_quarter`, `page_flings_move_short_drags`, `page_stays_at_the_ends`,
    `page_drag_rubber_bands_at_the_ends`, `page_caption_hides_for_one`.
- New `crates/rovsti_core/src/chat_find.rs` (add `pub mod chat_find;` to `lib.rs`):
  - `pub fn find(texts: &[&str], query: &str) -> Vec<usize>`: indices whose `search::fold(text)`
    contains the folded trimmed query (blank -> empty; Turkish fold as `search::fold`), in order.
  - `#[derive(Clone, Debug, Default, PartialEq, Eq)] pub struct FindCursor { hits: Vec<usize>, at: Option<usize> }`
    with `pub fn new(hits: Vec<usize>) -> Self` (at = last hit, the newest, since chats read
    bottom-up), `pub fn current(&self) -> Option<usize>`, `pub fn older(&mut self) -> Option<usize>`
    and `pub fn newer(&mut self) -> Option<usize>` (wrap around), `pub fn label(&self) -> String`
    ("2 of 5", "No matches"), `pub fn is_empty(&self) -> bool`.
  - `pub fn retarget(&mut self, hits: Vec<usize>)` keeps the same item when it is still a hit
    (older pages loaded shift indices: the caller passes new indices; keep `at` by value), else
    the newest.
  - Tests: `find_folds_case_and_turkish`, `blank_query_finds_nothing`,
    `cursor_starts_at_newest_and_wraps`, `label_counts_from_oldest` (label index is 1-based in
    oldest-first order), `retarget_keeps_the_current_hit`.

### Step 2. Pure: export, detection, mark-all targets
- New `crates/rovsti_core/src/chat_export.rs` (`pub mod chat_export;`):
  - `pub struct ExportLine<'a> { pub at_ms: i64, pub author: &'a str, pub text: &'a str }`
  - `pub fn export_text(title: &str, lines: &[ExportLine<'_>], tz_offset_s: i32) -> String`:
    header `"Chat with {title}\n"` and a `"-- 12 Nov 2026 --"` line at each local-day change, then
    `"[14:05] Ada: text"`; multi-line text indents continuation lines by two spaces; blank text
    skipped. Use `call_log::civil_from_days` (pub(crate)) for the date. Trailing newline.
  - `pub fn export_file_name(title: &str, now_ms: i64, tz_offset_s: i32) -> String`:
    `"chat-{slug}-2026-11-12.txt"` via `number_format::file_slug_opt`, `"chat-2026-11-12.txt"` when
    the slug is empty.
  - Tests: `export_groups_by_local_day`, `export_indents_multiline`, `export_skips_blank`,
    `file_name_slugs_title`, `file_name_without_title`.
- New `crates/rovsti_core/src/text_detect.rs` (`pub mod text_detect;`):
  - `#[derive(Clone, Debug, PartialEq, Eq)] pub enum Detected { Phone(String), Email(String) }`
    (Phone holds the digits with an optional leading `+`, as dialled).
  - `pub fn detect(body: &str, max: usize) -> Vec<Detected>`: emails (`local@domain.tld`, simple
    char classes, no trailing punctuation); phone runs of 7 to 15 digits allowing spaces, `-`,
    `.`, `(`, `)` and one leading `+`; skip runs that are inside a URL found by
    `spam::links`, that equal the OTP code from `otp::detect`, or look like dates
    (`dd.mm.yyyy`, `yyyy-mm-dd`) or amounts with a decimal comma/point; de-duplicated, first `max`.
  - `impl Detected { pub fn label(&self) -> String }`: "Call 0532 123 45 67"-style via
    `number_format` display if a fitting helper exists (grep), else the raw value; "Email ada@x.com".
  - Tests: `finds_turkish_mobile_and_international`, `finds_emails_without_trailing_dot`,
    `ignores_dates_amounts_otp_and_urls`, `dedupes_and_caps`.
- `apps/messages/src/inbox.rs`: `pub struct MarkAllTargets { pub sms_threads: Vec<i64>, pub wa_chats: Vec<String> }`
  and `pub fn mark_all_targets(rows: &[RowView]) -> MarkAllTargets` over unread, non-spam,
  non-archived rows shown in Chats (read the `RowView` fields that carry unread, wa_chat and the
  SMS thread ids; grep before writing). `pub fn mark_all_text(n: usize) -> String`
  ("Marked 3 chats as read", singular for 1). Tests `mark_all_skips_read_and_spam_rows`,
  `mark_all_text_plural`.

### Step 3. UI: WhatsApp image viewer with Save and Share (M1, M2)
- New `apps/messages/src/media_viewer.rs`, modelled on `apps/phone/src/photo_viewer.rs` (same
  gesture state, `Spring` glide, `CHROME_ZONE`, double tap timing, `image_rect`, touch + mouse
  paths, `press_scale` chrome buttons, liquid-glass chrome per the skill). Do not move or edit the
  Phone file (other agents work there); copy what is needed.
  - `pub(crate) struct ViewerItem { pub key: String, pub title: SharedString, pub mime: String, pub source: ViewerSource }`,
    `pub(crate) enum ViewerSource { WaMedia { message_id: String }, File { path: PathBuf } }`.
  - `pub(crate) struct MediaViewer` holds `items: Vec<ViewerItem>`, `index`, the `ZoomView`
    state, `full: Option<(Arc<RenderImage>, PathBuf)>`, `loading`, plus a placeholder
    `Option<Arc<gpui::Image>>` (the bubble's cached image) while the full decode runs.
  - Loading on the background executor: `WaMedia` -> `Gateway::media(id)` bytes, written to
    `cache/viewer/{cache_stem(id)}.{files::ext_for_mime}`; `File` -> read the path; then
    `rovsti_wa_ui::decode_image(&bytes, 4096)`. Errors show "Couldn't load the picture" in the
    viewer with Retry.
- `MessagesApp` (main.rs) owns `media_viewer: Option<MediaViewer>` and draws it as the top
  overlay (above sheets and pills; the pill must stay visible, so render the pill after it or
  raise it). Back (`go_back` path) closes the viewer first.
- `conversation.rs`: loaded image bubble (`body()`, `cached_image` branch) gets an id and
  `on_click` that emits new `ConversationEvent::OpenMedia { items: Vec<ViewerItem>, index: usize }`
  (for this step: just the tapped image, `items.len() == 1`). Keep the strip-closing behaviour of
  the bubble click: when the strip is open the tap closes it and does not open the viewer.
- Bottom bar: Save (`media::save_image_to_gallery(path, name, mime)`, name via a new
  `media_viewer::save_name(title, mime, at)` using `files::ext_for_mime`) and Share
  (`media::share_image(path, mime, title)`), off the UI thread; pills: "Saved to Photos",
  "Couldn't save the picture", `Unsupported` -> "Saving needs an Android phone." /
  "Sharing needs an Android phone.". Buttons disabled (dimmed) until the full file exists.
- Unit tests in `media_viewer.rs` for `save_name` and `image_rect` (copy Phone's cases).

### Step 4. UI: swipe between images, MMS pictures (M3)
- WA: `OpenMedia` now carries every image/sticker-less image message currently in the timeline
  (oldest first; `MediaKind::Image`, not revoked) and the tapped index. Viewer drag: when not
  zoomed and the drag is mostly horizontal, shift by `zoom::page_drag`; on release use
  `zoom::page_after_release`, spring to the new page, reset the `ZoomView`, start loading the new
  item (and prefetch the next one). Swipe down still closes. Top chrome shows the title (sender or
  "You") and `zoom::page_caption`.
- SMS: tapping an image part (`sms_conversation.rs::bubble`, the `is_image(&part.content_type)`
  branch ~line 878) emits new `SmsConversationEvent::OpenMedia { items, index }` with
  `ViewerSource::File` for every image part of the loaded thread; main.rs opens the viewer.
- Tests: none new in UI; Step 1 covers the maths.

### Step 5. UI: profile photo full screen (M4)
- `conversation.rs::header_avatar` and the SMS page header avatar get an id and `on_click`:
  emit `ConversationEvent::OpenAvatar` / `SmsConversationEvent::OpenAvatar`.
- main.rs opens the viewer with one item, new `ViewerSource::WaPicture { jid }` (background:
  `Gateway::picture(jid, true, None)`; `Picture::None` -> pill "No profile photo", viewer not
  opened) or `ViewerSource::ContactPhoto { uri }` (`rovsti_android::providers::contact_photo(uri,
  1024)` returns a PNG path; grep its result format first). Placeholder: the avatar image already
  shown (`WaAvatars::picture` / `ContactPhotos`). No photo at all -> no handler (monogram only).
- Save name `"{slug}-photo.{ext}"`; Save and Share as in step 3.

### Step 6. UI: search in conversation (M5)
- Page menus (`open_wa_page_menu`, `open_page_menu`) get "Search in chat" (`LucideSearch`),
  routed to the open page: `begin_find(window, cx)`.
- Each page (`conversation.rs`, `sms_conversation.rs`) gains `find: Option<FindState>` with a
  `TextField` (same `TextField::with_input(TextInputPurpose::Text, TextInputAction::Search, cx)`
  as the inbox search), a `rovsti_core::chat_find::FindCursor`, and a bar replacing the header
  subtitle row: field, `"2 of 5"`, up (older) / down (newer) glass icon buttons, ✕.
  On every edit: `chat_find::find` over `body_text`/caption (WA) or `copy_text` (SMS) of loaded
  items; reveal the current hit with the existing glow (`jump_to` logic for WA; add the same
  `highlight` + `list.reveal(position + offset)` to the SMS page). Matched text in the bubble
  does not need inline highlighting this round.
- When the oldest hit is reached and `has_older()`, the bar shows a "Load earlier" pill that
  loads one more page and `retarget`s. "No matches" when empty. Back closes the find bar first.

### Step 7. UI: Export chat and Mark all read (M6, M7)
- Page menus get "Export chat" (`LucideDownload`) and "Share as text" (`LucideShare`): the page
  builds `ExportLine`s from loaded items (WA author: "You" / `display_name`; SMS: "You" / title),
  `chat_export::export_text` with the backend's tz offset; Export writes
  `cache/export/{export_file_name}` and calls `documents::export_file(name, "text/plain", path)`
  reusing the page's existing `saving` + `DocumentOp::ExportMessages` handling (pill "Chat
  exported" / "Couldn't export"); Share uses the existing `share_text`. A chat with older pages
  shows the pill "Exported the loaded messages" when `has_older()`.
- Chats title: a `LucideCheckCheck` glass icon button (grep the icon; fallback `LucideCheck`),
  only when `inbox::mark_all_targets` is non-empty. Tap asks via the existing pill-with-action
  pattern ("Mark 5 chats as read?" + "Mark") or the row-menu confirmation pattern; on confirm
  `rovsti_android::sms::mark_read` per thread and `chat_action(jid, MarkRead{read:true}, ..)` per
  chat, then pill `mark_all_text(n)`; WA chats get Undo (`MarkRead{read:false}`), SMS cannot be
  re-marked unread (no Undo when SMS were included; say so in the confirm text).

### Step 8. UI: phone and email pills (M8)
- WA strip (`conversation.rs::actions`) and the SMS selection pills: for each
  `text_detect::detect(text, 2)` add pills: Phone -> "Call …" (`telecom::place_call`),
  "Message" (opens New message prefilled through the existing new-conversation path, or
  `telecom::compose_sms` if no in-app path exists), "Copy number"; Email -> "Email"
  (`telecom::send_email_to`), "Copy". Results via the existing `show_pill` patterns
  (`Unsupported` -> "That needs an Android phone."). Do not offer Call for a number equal to the
  thread's own sender (the page menu already has Call).
- Update this file: statuses of M1-M8 to "done R1", note anything deferred.
