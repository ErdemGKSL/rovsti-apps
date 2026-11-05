# Plan: Rovsti Messages, round 2

- Base: `107ac5c81c44d01ec24d01d98beb160e9e450f92` ("Messages app: notifications with inline
  reply, scheduled send, Settings (spam, backup), docs")
- Unit: Rovsti Messages. Architect plan, no source changed.
- Checks for every step (all must stay green):
  `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android`
  All of them pass at the base (run during this planning round). `git stash list` is empty.

## What landed (round 1, all 8 steps)

| Roadmap item | Code at the base |
|---|---|
| SMS/MMS via Telephony | `rovsti_core::{sms, sms_timeline, mms_pdu}`, `rovsti_android::sms`, Kotlin `SmsQuery`/`SmsSend`/`MmsCodec`, Messages host receivers (`SmsDeliverReceiver`, `MmsWapPushReceiver`, `HeadlessSmsSendService`) |
| Conversation view | `apps/messages/src/sms_conversation.rs` (SIM chip, segments, delivery ticks, MMS picture attach), `conversation.rs` (WhatsApp) |
| Unified inbox by number | `rovsti_core::inbox::merge`, `apps/messages/src/inbox.rs` |
| WhatsApp text, reactions, groups, presence | Done in `conversation.rs` (`send_text`, `react`, `chat_state`, group sender names) |
| **WhatsApp media send** | **Missing.** `conversation.rs:912` calls `composer_bar(keyboard, None, field, send)`: no attach button; `Gateway::send_media` (`crates/rovsti_wa_client/src/http.rs:293`) is never called from any app. Media is only received (images rendered, others shown as a labelled card) |
| Notifications + inline reply | `rovsti_core::notify`, `notifications.rs`, Kotlin `MessageNotifications`/`ReplyReceiver`, headless `wa_digest`/`wa_reply` |
| OTP, spam, scheduled send | `rovsti_core::{otp, spam, schedule}`, gateway `/v1/scheduled`, `schedule_ui.rs`, Kotlin `ScheduledSms` |
| Encrypted backup | `rovsti_core::msg_backup` (RMB1), `backup_ui.rs`, `settings_ui.rs` |
| Shared WhatsApp settings | `rovsti_wa_ui::WhatsAppSettings` in Phone, Messages, Contacts, Stops |

Smaller defect found: the MMS picture picker and the backup import share one document op
(`DocumentOp::ImportMessages`, `backend.rs:965`, `sms_conversation.rs:188`,
`settings_ui.rs:420`); only the `awaiting_*` flags keep them apart, and the result carries no
MIME type, so a WhatsApp attach cannot tell an image from a PDF. Step 1 fixes both.

So one roadmap item ("WhatsApp text/media/...") is not fully met. The rest is done as far as
it can be without a device (`docs/messages-acceptance.md` holds the device list).

## Steps

### Step 1. Seam: an attachment pick with its own op and the MIME type

Files: `crates/rovsti_core/src/wire.rs`, `crates/rovsti_android/src/documents.rs`,
`crates/rovsti_android/kotlin/dev/rovsti/bridge/{RovstiBridge.kt, Documents.kt}`,
`apps/phone/platforms/android/app/src/test/java/dev/rovsti/bridge/MessagesSeamTest.kt`,
`apps/messages/src/{backend.rs, sms_conversation.rs}`.

- `rovsti_core::wire::DocumentOp`: add `Attachment` (serde `"attachment"`, before `Unknown`).
  `DocumentResult`: add `#[serde(default, skip_serializing_if = "Option::is_none")] pub mime:
  Option<String>`. Tests: `{"op":"attachment","ok":true,"path":"/c/a.jpg","mime":"image/jpeg"}`
  parses; an old payload without `mime` parses to `None`; unknown op still `Unknown`.
- `rovsti_android::documents`: `pub fn pick_attachment(max_bytes: u64) -> Result<bool>` ->
  `bridge::call_bool("pickAttachment", &[Arg::Int(max)])`. Desktop test: `Unsupported`.
- Kotlin: `RovstiBridge.pickAttachment(maxBytes: Int)` -> `Documents.startFileImport(activity,
  maxBytes, attachment = true)`; `handleFile` uses a separate request code
  `REQUEST_ATTACH_FILE`, emits op `"attachment"` and adds `"mime"` from
  `contentResolver.getType(uri)` (null when unknown). `importFile` stays `import_messages`.
  JVM test in `MessagesSeamTest`: `resultJson("attachment", true, path, mime)` shape.
- App: `Backend::pick_attachment` calls `documents::pick_attachment(ATTACH_MAX_BYTES)` and
  its failure event uses `DocumentOp::Attachment`; `SmsConversation::on_backend` matches
  `DocumentOp::Attachment` (backup import keeps `ImportMessages`).

Acceptance: all checks green; `cargo test -p rovsti_core wire` covers the new op and field.

### Step 2. Pure: outgoing media in the WhatsApp timeline (`rovsti_wa_proto`)

Files: `crates/rovsti_wa_proto/src/{timeline.rs, media.rs (new), lib.rs}`.

```rust
// media.rs
/// What to send a picked file as: from the picker's MIME, else from the first bytes, else the name.
pub fn classify(mime: Option<&str>, name: &str, head: &[u8]) -> (MediaKind, String /* mime */);
// image/jpeg|png|webp|gif -> Image (gif stays Image), video/* -> Video, audio/* -> Audio,
// everything else -> Document. Sniffs FF D8 FF, 89 PNG, RIFF....WEBP, GIF8, %PDF,
// ....ftyp (mp4) when the MIME is missing or application/octet-stream.
pub const MAX_SEND_BYTES: u64 = 16 * 1024 * 1024;   // client cap; gateway allows 64 MiB
pub fn check_size(len: u64) -> Result<(), MediaTooLarge>;
```

```rust
// timeline.rs
impl Timeline {
    /// Optimistic outgoing media bubble (`MessageBody::Media`), same rules as `push_pending`.
    pub fn push_pending_media(&mut self, local_id: u64, kind: MediaKind, mime: String,
        caption: Option<String>, size: u64, now_ms: i64);
    /// Like `restart`, for any body: the retry tap on a failed text or media item.
    pub fn restart_body(&mut self, local_id: u64) -> Option<MessageBody>;
}
```
`restart` keeps its signature (text only) so existing callers do not change.

Tests: `classify` table (picker MIME wins; octet-stream + JPEG magic -> Image/image/jpeg;
`a.pdf` with no MIME -> Document/application/pdf; unknown -> Document/application/octet-stream);
`check_size` at the cap and one byte over; `push_pending_media` then `confirm` keeps the media
body and takes the real id; own echo arriving first drops the pending item; `fail` then
`restart_body` returns the `Media` body; `restart` on a media item stays `None`.

Acceptance: `cargo test -p rovsti_wa_proto media timeline`; all checks green; the crate still
checks for `aarch64-linux-android` through `rovsti_wa_client`.

### Step 3. Messages app: send WhatsApp media from the conversation, docs

Files: `apps/messages/src/{conversation.rs, main.rs}`, `docs/roadmap.md`,
`docs/messages-acceptance.md`, `docs/architecture.md` (one line under AD-MSG).

- `Conversation::new` also takes the `Entity<Backend>` (main.rs:343 passes it, as
  `SmsConversation::new` does) and subscribes to `BackendEvent::Document` with
  `op == DocumentOp::Attachment` while `awaiting_attachment` is set.
- `composer_bar(keyboard, Some(attach), field, send)`: a glass paperclip button
  (`gpui_icons` Lucide `paperclip`, already used by `sms_conversation.rs:643`; grep the
  pinned rev for the exact icon name before use), disabled on the desktop preview with the
  notice "Attachments need an Android phone".
- On a picked file: a chip above the composer (file name, kind label from `media_label`,
  remove "x"); Send with a chip sends media, the composer text becomes the caption.
  Off the UI thread: `std::fs::read(path)`, `media::check_size`, `media::classify(mime,
  name, &bytes[..16])`, `Gateway::send_media(chat, kind, &mime, caption, bytes)`.
  The bubble goes in first through `Timeline::push_pending_media`; `confirm`/`fail` as for
  text. For an image, put the decoded picture into `media_cache` under the pending id and,
  on confirm, under the real id, so the own picture shows without a download.
- Retry: `retry` uses `restart_body`; a media retry re-reads the file from the cached path
  kept in `pending_paths: HashMap<u64, String>` (dropped on confirm). A missing file shows
  the notice "The file is gone; attach it again".
- A notification inline reply stays text only (`send_text`), unchanged.
- Docs: roadmap Messages line "WhatsApp conversation view ..." gains "send pictures, video,
  audio and documents"; `docs/messages-acceptance.md` adds "attach a 5 MB photo and a PDF to
  a WhatsApp chat, both arrive on the other phone; a 20 MB file is refused".

Tests (thin, `#[cfg(test)]` in `conversation.rs`): `attach_chip_label(name, kind)`;
`send_plan(text, chip) -> Plan::{Nothing, Text, Media { caption }}` (empty text + chip ->
Media with `None` caption; text + chip -> caption; whitespace + no chip -> Nothing).

Acceptance: all checks green; `cargo run -p rovsti_messages` shows the attach button in a
preview WhatsApp chat and the "need an Android phone" notice on tap.

## Left out (device or owner decision)

On-device acceptance (`docs/messages-acceptance.md`), RCS, group MMS beyond one picture,
WhatsApp voice-note recording, creating WhatsApp groups from Messages (the gateway lists
groups; creation is not in the roadmap), WhatsApp history in the local backup (it lives on
the user's gateway).
