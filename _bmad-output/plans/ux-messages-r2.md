# UX completeness pass: Rovsti Messages (round 2)

Base: `12bdf27189c130e73201073e88684a3ac0d82266` (main, clean tree, no stash).
Round 1 (`ux-messages.md`, S1 to S8) has landed: SMS row and page menus, delete with
confirmation, Not spam / Block with Undo, Copy in both conversations, drafts, Chats search,
WhatsApp from New message, Undo for Settings deletes. This round re-audits the app as a
user holding the phone and lists what is still missing. No new product features, no
backends: every fix below uses a seam or gateway call that already exists
(`documents::export_file`, `telecom::{share_text, edit_contact, place_call, add_contact}`,
`blocklist::unblock`, `Backend::request_sms_role`, `Gateway::media`).

## Re-audit: gaps found

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| H1 | **High** | **Received WhatsApp documents, videos and audio dead-end.** The bubble shows "rapor.pdf · 3 pages" but tapping does nothing (`downloadable` is images/stickers only; `fetch_media` marks other MIME types failed). The user can never open or keep the file. `documents::export_file(name, mime, path)` ("Save as") exists and Settings already uses it. Images cannot be saved either. | `conversation.rs::media_card` (~1823), `fetch_media` (821) |
| H2 | Medium | **A blocked sender cannot be unblocked where it is seen, and Block shows for an already blocked number.** `row_sms_actions` builds the context with `..Default` so `blocked` is always false; `SmsAction` has no `Unblock`. Unblocking is only in Settings. | `main.rs::row_sms_actions` (121), `sms_actions.rs` |
| H3 | Medium | **"Make Rovsti Messages your default SMS app to delete messages" dead-ends.** The refusal comes as a plain pill *after* the user confirmed the delete; nothing leads to the role request (`Backend::request_sms_role` exists, used only by the banner). | `backend.rs::delete_thread` (712), `main.rs::sms_row_action` |
| H4 | Medium | **A WhatsApp chat page has no menu.** Pin, Archive, Mark unread exist only on the inbox row; an unknown WhatsApp number cannot be called or added to contacts from its chat (the SMS page got this in round 1). | `conversation.rs` header (~2257), `main.rs::wa_menu_items` |
| H5 | Medium | **Message text cannot be shared**, only copied (`telecom::share_text` exists). Known contacts cannot be opened from the SMS page (`telecom::edit_contact(id)` exists). | `conversation.rs::actions` (~2029), `sms_conversation.rs` selection pills, `sms_actions.rs` |
| H6 | Medium | **Cancelling a scheduled message loses its text and says nothing**; only the first 3 scheduled items can be seen or cancelled ("and N more" is dead text). | `schedule_ui.rs::strip` (424), `main.rs::cancel_scheduled` (2227) |
| H7 | Low | **Drafts are invisible in the list.** Round 1 keeps drafts, but the inbox row does not say "Draft: ..." (planned in round-1 S5, not done), so the user forgets the half-written text. | `inbox.rs`, `main.rs::rebuild` |
| H8 | Low | **Settings > Muted lists raw numbers and JIDs** ("Muted: WhatsApp 9053...", "Muted: +90532...") instead of the names the inbox shows. | `settings_ui.rs::tag_label` (131) |
| H9 | Low | **Custom "Send later" time must be typed** ("18:30 or 2026-12-31 09:00"); only the quick times can be picked. | `schedule_ui.rs::ScheduleSheet::render` |
| — | Info (not planned) | Deleting a single SMS / marking an SMS thread unread needs new Kotlin provider writes. Deleting a WhatsApp chat, starred list, group member page: no gateway endpoint or a new screen. Audio playback in-app: no player seam (Save covers it). Runtime and device-only checks stay in `docs/messages-acceptance.md`. Stale `#[cfg_attr(not(test), allow(dead_code))]` on now-used `sms_actions` functions: cleanup in step 1. |

## Plan (ordered by user impact, pure logic first; each step leaves all checks green)

Checks after every step:
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`
(`df` showed 6.8 GB free at planning time; under 6 GB run `rm -rf target/debug/incremental` first).
Never invent GPUI APIs: reuse what these files already call (`menu_card`, `menu_item`,
`glass_pill`, `glass_icon_button`, `list_row`, `on_long_press`, `show_pill_action` /
`PillAction`, `TextField::{set, text, clear}`, `cx.background_executor().spawn`,
`Icon::LucideEllipsis` as in `sms_conversation.rs:856`). Grep `gpui_icons` before using any
new icon name (e.g. `LucideShare2`, `LucideDownload`, `LucideBan`); fall back to an icon this
app already uses.

### Step 1. Pure logic (+tests)
- `apps/messages/src/sms_actions.rs`:
  - `SmsAction` gains `Unblock` and `OpenContact`. `actions`: when `single_sender && blocked`
    push `Unblock` where `Block` would go (never both); on the page, `OpenContact` replaces
    `AddContact` when `known_contact`. `label`: "Unblock", "Open contact". `is_danger`
    unchanged (Unblock is not red).
  - Remove the stale `#[cfg_attr(not(test), allow(dead_code))]` attributes on functions that
    `main.rs` now calls (keep them only where clippy still needs them).
  - `pub const MAKE_DEFAULT: &str = "Make default";` (pill action label for `NEEDS_ROLE`).
  - Tests: blocked row shows Unblock not Block; page with known contact shows Call +
    OpenContact; unknown shows Call + AddContact; labels stay unique.
- `apps/messages/src/drafts.rs`: `pub fn get(&self, thread: &ThreadId) -> Option<&str>`. Test.
- `apps/messages/src/inbox.rs`: `pub fn snippet_with_draft(snippet: &str, draft: Option<&str>) -> String`
  ("Draft: {first line, trimmed}" when the draft is non-blank, else `snippet`). Tests:
  blank draft keeps snippet, multi-line draft uses first line.
- `apps/messages/src/conversation.rs` (pure, in the existing test module):
  - `fn saveable(kind: MediaKind) -> bool`: Image, Video, Audio, Document (not Sticker).
  - `fn save_name(kind: MediaKind, media: Option<&MediaMeta>, mime: &str, message_id: &str) -> String`:
    `media.file_name` when non-blank, with `/` and `\` replaced by `_`; else
    `"WhatsApp {kind word} {id prefix 8}.{ext}"` with `ext` from the MIME (jpeg→jpg, png,
    webp, gif, mp4, ogg→ogg/opus, mpeg→mp3, pdf, else `bin`). Tests: named doc kept,
    slashes sanitised, unnamed image gets `.jpg`, unknown MIME gets `.bin`.
- `apps/messages/src/schedule_ui.rs`:
  - `pub fn visible_items(items: &[StripItem], expanded: bool) -> &[StripItem]` (first
    `STRIP_ROWS` unless expanded) and `pub fn more_label(hidden: usize) -> String`
    ("Show {n} more"). Tests.
  - `pub fn text_after_cancel(composer: &str, body: &str) -> Option<String>`: `Some(body)` when
    the composer is blank, else `None` (never overwrite what the user is typing). Test.
- `apps/messages/src/settings_ui.rs::tag_label(tag, threads, name_of: impl Fn(&ThreadId) -> Option<String>)`:
  the name first, then today's fallbacks. Update the existing `muted_tags_get_names` test
  and add one where a name wins.

### Step 2. WhatsApp media can be saved (fixes H1)
- `conversation.rs`: media cards whose `saveable(kind)` and that are not images get a
  status line "Tap to save" and `on_click` → `self.save_media(id, kind, mime, name, cx)`;
  image cards keep tap-to-load, and the long-press action strip gains a "Save" pill for any
  `saveable` media (next to Copy).
- `save_media`: refuse when `self.saving.is_some()` (pill "Already saving a file");
  background `gateway.media(&id)` then write the bytes to
  `backend.data_dir()/cache/wa-save-{id}` (create the dir; `None` data dir → notice "The
  app's storage is not ready yet."), then `rovsti_android::documents::export_file(&name, &mime, &path)`.
  `Err(Unsupported)` → notice "Saving needs an Android phone."; `Ok(false)` → "Couldn't
  save that file". Keep `saving: Option<PathBuf>`.
- The answer is a `BackendEvent::Document` with op `ExportMessages` (the seam's op, shared
  with the backup). `Conversation` subscribes to the backend in `new` (as `SmsConversation`
  does for attachments) and handles it only while `saving.is_some()`: delete the cached file
  (`std::fs::remove_file`, ignore errors), notice "Saved" / "Not saved". Settings already
  ignores the op unless its backup is `Busy(Saving)`; note in a doc comment that both
  cannot be waiting at once (the save sheet is modal).
- New `ConversationEvent::Notice(String)`; `main.rs` maps it to `show_pill`.
- Downloading state: reuse `media_loading` so the card shows "Downloading…" meanwhile.

### Step 3. SMS menus: Unblock, Open contact, role recovery (fixes H2, H3, part of H5)
- `main.rs::SmsRowMenu` gains `blocked: bool` and `contact_id: Option<i64>`;
  `row_sms_actions` passes `blocked`. `open_row_menu` and `open_page_menu` fill `blocked`
  from `backend.blocked().is_blocked(sender)` and `contact_id` from
  `backend.contacts().match_number(sender).map(|c| c.id)` (`known_contact = contact_id.is_some()`).
- `sms_row_action`: `Unblock` → background `blocklist::unblock(&sender)`, then `pull_blocked`,
  pill_action "Unblocked {title}" / "Undo" → `PillAction::Reblock(String)` (re-block path of
  `undo_settings` `SettingsUndo::Block`; factor a shared `fn reblock(raw, cx)`).
  `OpenContact` → background `telecom::edit_contact(id)`, errors as in `telecom_action`.
- Role recovery: `SmsAction::Delete` first checks `backend.sms_role_held() || backend.is_preview()`;
  if not held, close the menu and `show_pill_action(NEEDS_ROLE, MAKE_DEFAULT, PillAction::RequestSmsRole, cx)`
  instead of opening the confirmation. `pill_fired` maps `RequestSmsRole` →
  `backend.request_sms_role(cx)`. The `BackendEvent::Notice(NEEDS_ROLE)` path in
  `backend.rs` stays as a fallback.
- Extend the `main.rs` tests (`row_menu_offers_not_spam_and_block`,
  `page_menu_hides_add_contact_for_a_known_number`) for blocked and contact cases.

### Step 4. WhatsApp chat page menu (fixes H4)
- `conversation.rs` header: an `Icon::LucideEllipsis` `glass_icon_button` next to the bell
  emitting new `ConversationEvent::Menu`.
- `main.rs`: `ConversationEvent::Menu` → `open_row_menu(Some(jid), None, title, cx)` then set
  new `RowMenu` fields `page_number: Option<String>` (for a 1:1 chat:
  `format!("+{}", jid_user(jid))`; `None` for groups) and `contact_id: Option<i64>`
  (`backend.contacts().match_number`). `wa_menu_items` appends, when `page_number` is set:
  "Call" (`telecom_action(SmsAction::Call, ..)`) and "Add to contacts" (no contact) or
  "Open contact" (contact). Pin/Archive/Mute/Mark unread reuse the existing items; Archive
  from the page also closes the page (the chat leaves the list).
- Back closes the menu first (already in `go_back`).

### Step 5. Share and Copy everywhere (fixes rest of H5)
- `conversation.rs::actions`: a "Share" pill after Copy when `copy_text_of` is `Some`,
  emitting new `ConversationEvent::Share(String)`.
- `sms_conversation.rs` selection pills: "Share" after "Copy"; new
  `SmsConversationEvent::Share(String)`.
- `main.rs::share_text(text, cx)`: background `rovsti_android::telecom::share_text`,
  `Err(Unsupported)` → pill "Sharing needs an Android phone.", `Ok(false)`/`Err` →
  "Couldn't share that".

### Step 6. Scheduled strip: see all, cancel keeps the text (fixes H6)
- `schedule_ui::strip` gains `expanded: bool` and `on_more: fn(&mut V, &mut Context<V>)`;
  it renders `visible_items(items, expanded)` and, when items are hidden, a tappable
  `more_label` line (`.id("sched-more")`, `on_click` → `on_more`). Both conversations keep
  `strip_expanded: bool`, toggled by `on_more`, reset when the strip empties.
- Cancel: the conversation's `on_cancel` looks up the item's body, applies
  `text_after_cancel(composer, body)` with `TextField::set`, then emits `CancelScheduled`
  as today. `main.rs::cancel_scheduled` shows a pill "Scheduled message cancelled" on
  success (local: after `backend.cancel_scheduled`; gateway: in the `Ok` / `NotFound` arms).

### Step 7. Draft line in the list; names in Settings > Muted (fixes H7, H8)
- `main.rs::rebuild` (or `sync_chat_list` when it builds rows): for each `RowView`, if any
  of its `threads` has `self.drafts.get(..)`, set `row.snippet =
  snippet_with_draft(&row.snippet, draft).into()` and mark the draft prefix with the
  accent colour in `row_element` only if that is a plain text split (otherwise plain text
  is fine). `leave_page` must trigger `rebuild` so the line appears on Back.
- `settings_ui.rs`: build `name_of` from `backend.contacts().match_number` for SMS
  addresses (first address) and, for WhatsApp tags, the chat name the backend/inbox
  already holds (pass a `HashMap<String, String>` jid → title into `SettingsView` via a
  setter called from `MessagesApp::rebuild`, or fall back to `match_number("+{user}")`).

### Step 8. Pick a custom time; docs (fixes H9)
- `schedule_ui.rs` pure: `#[derive(Clone, Copy, Debug, PartialEq, Eq)] pub struct CustomPick { day: u16, minutes: u16 }`
  (day 0 = today, max 30; minutes of day, steps of 15), `CustomPick::initial(now_ms, tz)`
  (next quarter hour at least 15 min ahead), `step_day(self, delta: i32) -> Self`,
  `step_minutes(self, delta: i32) -> Self` (wraps within the day), `at_ms(self, now_ms, tz) -> i64`,
  `label(self, now_ms, tz) -> String` (reuse `when_label`). Tests: wrap, clamps, at_ms
  matches `parse_custom` for the same time, past time refused by the existing check.
- Sheet: above the typed field, two rows of `glass_pill` "−" / label / "+" (day, time) and
  a "Schedule" pill calling `schedule_at(pick.at_ms(..))`. Keep the typed field as the
  alternative. Reset the pick on `open`.
- Docs: `docs/messages-acceptance.md` section M gains rows (save a WhatsApp document,
  Unblock from the row, Make default from the delete refusal, WhatsApp page menu, Share,
  scheduled: show more + cancel restores text, draft line, Muted names, custom time
  picker). `docs/roadmap.md` Messages line extended. Status section at the end of this
  file.

## Status

Steps 1 to 8 implemented (H1 to H9). Step 8 added `CustomPick` (tested) and the -/+ day and
time rows with a Schedule pill in the Send later sheet; the typed field remains. Device
checks are rows 55 to 63 of `docs/messages-acceptance.md`.
