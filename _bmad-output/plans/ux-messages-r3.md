# UX completeness pass: Rovsti Messages (round 3)

Base: `6eda816d6e43540a74ffedcb625fc4c5b6c81eb0` (main, clean tree, no stash). Rounds 1
(`ux-messages.md`, G1 to G10) and 2 (`ux-messages-r2.md`, H1 to H9) are done. This round
re-audits as a user holding the phone. Scope as before: `apps/messages`, `rovsti_core`,
`rovsti_wa_proto`/`rovsti_wa_client`/`rovsti_wa_ui` as used by Messages, existing
`rovsti_android` seams. No new product features, no backends. GPUI APIs used below were
checked at the pinned rev: `App::open_url(&self, &str)` (`crates/gpui/src/app.rs:1500`,
Android forwards to `openUrl` in `gpui_android/src/bridge.rs:696`).

## Re-audit: gaps found

| # | Sev | Gap | Where |
|---|-----|-----|-------|
| K1 | **High** | **The keyboard's Send key does nothing in a WhatsApp chat** (docs/ux-audit.md M24, deferred since round 0). The composer is built with `TextInputAction::Send`, but nothing reads `TextField::actions()`; the key only hides the keyboard. The SMS page already does it right. | `conversation.rs:286-290`, `on_composer_changed` (786) |
| K2 | **High** | **A message that failed to send can only be retried.** No long-press on a failed bubble (both pages): it cannot be copied, nor removed; on WhatsApp the failed text is lost when the page closes, so the user cannot even copy it out first. | `conversation.rs::bubble` (1817-1822), `sms_conversation.rs::bubble` (736-745, 795), `selectable` (1192) |
| K3 | Medium | **Received MMS pictures and files cannot be saved.** A picture-only MMS is not even selectable (`selectable` needs text); no Save pill exists on SMS pages, while WhatsApp media got Save in round 2 (`documents::export_file` exists). | `sms_conversation.rs::bubble`, `selectable`, `copy_text` |
| K4 | Medium | **Links in messages cannot be opened.** The app detects links (`spam::contains_link`, the "Unknown links" rule) but a bubble with `bit.ly/...` offers only Copy/Share. | `rovsti_core::spam` (213), both bubbles' pills |
| K5 | Medium | **Archiving (or muting) a WhatsApp chat gives no feedback and no Undo**: the row silently vanishes into Archived. SMS Mute shows a pill but no Undo. | `main.rs::chat_action` (1567), `sms_row_action` Mute (1136) |
| K6 | Medium | **The inbox does not say when WhatsApp is offline or logged out.** Chats just stop updating (or vanish; `Unauthorized` is swallowed in `load_chats`). The only way to learn why is to open the WhatsApp tab. | `main.rs::load_chats` (757), `ChatItem`, `rovsti_wa_ui::view` (no state getter) |
| K7 | Low | **A failed page load is dead text** on both pages ("Leave and open the conversation to try again"); no tap to retry. | `sms_conversation.rs::top_slot`/`load_error_text`, `conversation.rs::top_slot` (1605) |
| K8 | Low | **Restore without the SMS role dead-ends** after passphrase and preview: "Make Rovsti Messages your default SMS app to restore messages." with only OK; the opened archive is dropped, so the passphrase must be typed again. | `settings_ui.rs::confirm_restore` (507), backup section (1036) |
| K9 | Low | **Settings has no reset** (Phone got "Reset settings" with Undo in its round 5). Spam rules, notification switches and mutes can only be undone one by one. | `settings_ui.rs::render`, `prefs.rs` |
| — | Info (not planned) | Full-screen picture viewer, forwarding, notification "Mark as read" action, share-into-Messages target, group member page, starred list, delete single SMS / mark SMS thread unread: new screens, new Kotlin or gateway work. Device-only checks stay in `docs/messages-acceptance.md`. |

## Plan (ordered by user impact, pure logic first; every step leaves all checks green)

Checks after every step (from the task):
`cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android && cargo check -p rovsti_tiles --target aarch64-linux-android`.
If `df` shows under 6 GB free (it shows 6.5 GB at planning time), `rm -rf target/debug/incremental` first.

### Step 1. Pure logic (+tests)
- `crates/rovsti_core/src/spam.rs`: `pub fn links(body: &str) -> Vec<String>` with the same
  token scanner as `contains_link` (factor the per-token test into `fn link_token(raw) ->
  Option<String>`); returns openable URLs in order, deduplicated, original case kept, with
  `https://` prepended when the token has no scheme; `contains_link` becomes
  `!links(body).is_empty()` (keep its tests). Test `links_are_openable`:
  `"see https://x.co/a"` → `["https://x.co/a"]`, `"go to bit.ly/3abc now"` →
  `["https://bit.ly/3abc"]`, `"(Example.com)."` → `["https://Example.com"]`, two links
  in order, duplicates once, all `link_scanner` "no" cases → empty.
- `crates/rovsti_core/src/sms_timeline.rs`: `pub fn discard(&mut self, local_id: u64) ->
  Option<String>`: removes a local `Failed` item, returns its body; `None` (nothing
  removed) for pending/sent/unknown. Test `discard_only_removes_failed_local_items`.
- `crates/rovsti_wa_proto/src/timeline.rs`: `pub fn discard(&mut self, local_id: u64) ->
  Option<MessageBody>`, same rule (only `ItemState::Failed { local_id }`). Test alongside
  the `restart` tests.
- `apps/messages/src/inbox.rs`:
  - `pub fn undo_of(action: &ChatActionKind, before: &ChatMeta) -> Option<ChatActionKind>`:
    Archive{a} → Archive{!a}; Pin{p} → Pin{!p}; Mute{..} → `Unmute`; Unmute → Mute with the
    `before` mute deadline (`None` = forever) when `before` was muted; MarkRead → None.
  - `pub fn action_notice(action: &ChatActionKind) -> Option<&'static str>`: "Archived",
    "Unarchived", "Muted", "Unmuted"; `None` for Pin and MarkRead (visible on the row).
  - `pub fn wa_banner(linked: bool, state: Option<InstanceState>) -> Option<&'static str>`:
    not linked → None; Connected or Linking → None; Disconnected or `None` →
    "WhatsApp is offline. Reconnecting…"; LoggedOut → "WhatsApp logged this phone out. Tap
    to link again."; Banned → "WhatsApp blocked this account. Tap for details.".
  - Tests: `undo_reverses_archive_pin_and_mute`, `notices_only_for_hidden_changes`,
    `banner_only_when_linked_and_not_connected`.
- `apps/messages/src/sms_conversation.rs`:
  - `pub struct SavePart { pub name: String, pub mime: String, pub path: String }` and
    `pub fn saveable_parts(item: &SmsItem) -> Vec<SavePart>`: parts with a `path` and a
    non-`text/plain`, non-`application/smil` type; name from
    `mms_save_name(content_type, at_ms, index)` = `"mms-<yyyymmdd-hhmmss UTC>-<n>.<ext>"`.
  - Move the MIME→extension table out of `conversation.rs::save_name` into
    `pub fn ext_for_mime(mime: &str) -> &'static str` in a new `apps/messages/src/files.rs`
    (`mod files;` in `main.rs`); `save_name` and `mms_save_name` both use it (existing
    `save_names_keep_or_invent_a_safe_file_name` test must still pass).
  - `fn selectable(item)`: settled and (`copy_text` or `!saveable_parts` empty), plus a new
    `pub fn failed_local(item: &SmsItem) -> Option<u64>` (local key and `Failed`).
  - `load_error_text` default sentence → "Couldn't load messages. Tap to try again."
  - Tests: `picture_only_mms_is_selectable_and_saveable`, `mms_names_get_extensions`,
    `failed_local_items`, update `load_errors_read_plainly`.
- `apps/messages/src/prefs.rs`: `pub fn reset(&self) -> MessagesPrefs` (defaults, but
  `sim_choices` kept) and `pub fn is_reset(&self) -> bool` (`self.reset() == *self`). Test
  `reset_keeps_sim_choices_only`.
- `apps/messages/src/backup_ui.rs`: `pub const RESTORE_NEEDS_ROLE: &str` (the existing
  sentence, reused by `restore_outcome`) and `pub fn restore_blocked(role_held: bool,
  preview: bool) -> bool` (`!role_held && !preview`). Test.
- Mark new functions `#[cfg_attr(not(test), allow(dead_code))]` only until the step that
  calls them, and remove that attribute in that step.

### Step 2. WhatsApp keyboard Send (fixes K1, ux-audit M24)
- `conversation.rs::new`: build the composer like the SMS page:
  `field.set_keep_focus_on_action(true)`; add `seen_actions: u64` to `Conversation`
  (init 0). In the existing `cx.observe(&composer, ...)` closure, before
  `on_composer_changed`, read `composer.read(cx).actions()`; when it changed, store it and
  call `this.send(cx)` (which already handles edit mode, reply and empty text).
- `docs/ux-audit.md`: M24 `[>]` → `[x]` with "Fixed in ux-messages-r3 step 2".

### Step 3. Failed messages: Retry, Copy, Delete (fixes K2)
- SMS (`sms_conversation.rs::bubble`): a failed local bubble keeps tap = retry and gains
  `on_long_press` → `self.selected = Some(key)`. When selected and `failed_local(item)` is
  `Some(id)`, the pill row shows "Retry" (`this.retry(id)`), "Copy" (when `copy_text`),
  and a red "Delete" → `this.discard_failed(id, cx)`: `timeline.discard(id)`,
  `mms_sends.remove(&id)`, `selected = None`, `rows_dirty`, notice/pill "Message
  deleted" (emit the page's existing notice/pill path used by `copy`).
- WhatsApp (`conversation.rs::bubble`): failed bubble gets `on_long_press` setting
  `self.strip = Some(local key string)`; `actions()` for a `Failed { local_id }` item
  shows only "Retry", "Copy" (`copy_text_of`), "Delete" → `discard_failed(local_id)`:
  `timeline.discard`, drop `pending_paths[local_id]` (remove its cached file) and the
  pending reply entry (`take_reply`), `ConversationEvent::Notice("Message deleted")`.
  No confirmation: nothing left the phone; the text can be copied first.
- Tests: none new in GPUI glue (logic covered in step 1).

### Step 4. Save MMS pictures and files (fixes K3)
- `SmsConversation`: field `saving: bool`. In the selection pills, for each
  `saveable_parts(item)` add a "Save" pill (label "Save picture" for `image/*` when there
  is one part, else "Save <n>" per part) calling `self.save_part(part, cx)`:
  if `saving` → pill "Already saving a file"; else `saving = true` and on the background
  executor `rovsti_android::documents::export_file(&name, &mime, &path)`; map with
  `conversation::save_outcome` (make it `pub(crate)`) → `Unsupported` → "Saving needs an
  Android phone.", failure → "Couldn't save that file" and `saving = false`.
- In `on_backend`, handle `BackendEvent::Document(r)` with `r.op ==
  DocumentOp::ExportMessages` when `saving`: `saving = false`, pill "Saved"/"Not saved".
  (Kotlin's file is in its cache; it is not deleted here.)
- Picture bubbles: a tap on the image of a selected-less bubble also opens the selection
  (same as long-press), so a picture alone can reach Save.

### Step 5. Open links (fixes K4)
- WhatsApp `actions()` and SMS selection pills: for the first link of
  `rovsti_core::spam::links(text)` add an "Open link" pill (`Icon::LucideExternalLink`
  if it exists in `rovsti_ui::Icon`, else `LucideShare2`; grep the enum first) that calls
  `cx.open_url(&url)` and closes the selection; when there are more links, one pill per
  link up to 3, labelled with the host (`url` without scheme, truncated to 24 chars).
- On desktop `open_url` opens the browser; nothing Android-only is added.

### Step 6. Feedback and Undo for chat actions (fixes K5)
- `main.rs`: `PillAction::Chat(String, ChatActionKind)` and `PillAction::SmsMute(i64,
  bool)`. In `chat_action`, capture `before` meta (clone from `wa_info`) before
  `inbox::apply_action`; after the optimistic update, if
  `inbox::action_notice(&action)` is `Some(text)` and `inbox::undo_of(&action, &before)`
  is `Some(undo)`, `show_pill_action(text, "Undo", PillAction::Chat(jid, undo))`.
  `pill_fired`: `Chat(jid, undo)` → `self.chat_action(&jid, undo, cx)` (its own pill
  then says e.g. "Unarchived" without a second Undo: pass a `quiet: bool` parameter to
  `chat_action`, `true` from Undo).
- SMS Mute/Unmute (`sms_row_action`): replace `show_pill` with `show_pill_action(..,
  "Undo", PillAction::SmsMute(id, !mute))`; firing sets the tag back and pills
  "Unmuted"/"Muted".

### Step 7. Inbox WhatsApp banner; tap to retry a failed load (fixes K6, K7)
- `crates/rovsti_wa_ui/src/view.rs`: `pub fn instance_state(&self) ->
  Option<InstanceState>` = `self.model.status.as_ref().map(|s| s.state)` (doc comment;
  no other change to the shared crate).
- `main.rs`: `ChatItem::WaBanner` shown after `RoleBanner` when
  `inbox::wa_banner(link.is_some(), instance_state())` is `Some(text)` and no search is
  active; a `list_row` with `Icon::LucideWifiOff` (or an existing warning icon; grep
  `Icon`) whose tap does what Settings' WhatsApp row does (`SettingsEvent::OpenWhatsApp`
  handling: switch to the WhatsApp tab). Include the banner text in `ChatLayout` so the
  list re-measures when it changes. `load_chats`: `Err(ClientError::Unauthorized)` sets
  `chats = Needed` and logs, so the banner, not silence, explains it.
- `sms_conversation.rs::top_slot` and `conversation.rs::top_slot`: the error line gets an
  id and `on_click` → `this.error = None; this.load(None, cx)` (WhatsApp: only when the
  error came from `load`; give load errors their own field `load_error:
  Option<SharedString>` so a reaction error is not "retried"). WhatsApp load text: 
  "Couldn't load messages. Tap to try again."

### Step 8. Restore needs the role up front; Reset settings; docs (fixes K8, K9)
- `settings_ui.rs`: in the restore preview rows, when
  `backup_ui::restore_blocked(role_held, is_preview)`, show `RESTORE_NEEDS_ROLE` as a
  text line and a "Make default" pill (`backend.request_sms_role(cx)`) instead of
  "Restore"; the preview stays open, so after the role arrives (`BackendEvent` role
  change re-renders) "Restore" appears without typing the passphrase again.
- Settings: a new last section "Reset" with a red "Reset settings" row (hidden when
  `prefs.is_reset()`); first tap shows a confirm line "Spam rules, notification switches
  and mutes go back to the defaults." with "Reset" and "Cancel" pills; Reset saves
  `prefs.reset()` and emits `SettingsEvent::Undoable { text: "Settings reset", undo:
  SettingsUndo::Prefs(Box<MessagesPrefs>) }`; `MessagesApp::undo_settings` restores the
  boxed prefs via `update_prefs(|p| *p = old)`. Blocked numbers (system list) and the
  WhatsApp link are untouched; say so in the confirm line.
- Docs: `docs/messages-acceptance.md` section M gains rows (keyboard Send on WhatsApp,
  failed message Copy/Delete, save MMS picture, open a link, archive Undo, mute Undo,
  WhatsApp offline banner, tap to retry a load, restore asks for the role first, reset
  settings with Undo). `docs/roadmap.md` Messages: one ticked line "Third UX pass: ..."
  (done without a device). Status section at the end of this file.

## Status

Planned; not started.
