# E6-WA R3: media

Branch `messages-kotlin`. Epic plan: `e6wa-plan.md`. Research: `../research/e6wa-inventory.md` (the Media table and
the list at its end). Base: WA-R2 closed at `aa076380`.

Goal of the round: WhatsApp media in Messages feels complete: a full-screen viewer with zoom and swipe, video played
in the app, GIFs and stickers shown as what they are, view-once respected, files saved and shared, several files sent
at once with captions, voice notes recorded in the app, and what Rosty sends looks right on the other phone (preview,
size, length, waveform).

## Wire additions (agreed up front; all additive)

- `MediaOptions` (send): `thumbnail_b64: Option<String>` (JPEG, at most 64 KiB decoded), `width`, `height`,
  `waveform_b64: Option<String>` (64 bytes, 0..=100), `animated: bool` (video sent as a GIF), `view_once: bool`.
  `seconds` and `voice_note` exist. The gateway sets `jpeg_thumbnail`, dimensions, `waveform`, `gif_playback` and the
  view-once wrapper from them and makes nothing up when they are missing.
- Received media: `MediaMeta.waveform_b64: Option<String>` for a voice note (the 64 bytes WhatsApp sends).
- Chat state: `POST /v1/chats/{jid}/typing` accepts `kind: "recording"` next to composing and paused, if the library
  offers it (to verify in whatsapp-rust 0.7.0); the `typing` event already names what the other side does.
- Played: `mark_played` exists on the gateway (`POST /v1/played`); it becomes an outbox action on the device.

## Steps (one story each)

| Step | What | Layers | Who |
|---|---|---|---|
| 1 | Gateway and wire: the send options above reach the library builders (`LIB/media.rs` options: `jpeg_thumbnail`, width, height, `waveform`, `gif_playback`; view-once on send), `waveform_b64` on received voice notes, the recording chat state. Own commit, reported. | `services/wa_gateway`, proto, client | subagent A, reviewed |
| 2 | Device store and bridge: the new send options through the outbox (`SendMedia`), `MarkPlayed` as an outbox action, `waveform`, `animated`, `view_once` on rows, a "viewed" mark for a view-once message (its file is deleted and never re-downloaded), a gallery query (`wa_sync.chat_media {chat, before?, limit, kinds}`: media messages of a chat, newest first, by cursor). FFI ops, fixtures, Kotlin facade and fakes. | `rosty_app_core`, `rosty_ffi`, `:core-bridge` | subagent A |
| 3 | Viewer: a full-screen viewer for the photos and videos of a chat: pinch and double-tap zoom, pan with bounds, swipe between items (gallery query, paged both ways), drag down to dismiss with the shared-element return to the bubble, tap to hide the chrome, caption and sender line, download state for an item not on the phone. | Messages, `:design` if a gesture piece is shared | subagent B |
| 4 | Video and GIF: video plays in the viewer (play, pause, seek bar, time, mute; audio focus; keeps position on rotation); a GIF (`animated`) loops muted inline in the bubble and in the viewer; no new player library unless the platform player cannot do it (then it is named in the report). | Messages, `:platform` | subagent B |
| 5 | Save, share, forward from the viewer and the message menu: Save copies through MediaStore (Pictures/Rosty, Movies/Rosty, Download/Rosty; pending flag while writing; no storage permission on API 29+) and marks the file saved in the store (`keepMedia`, exempt from eviction, not saved twice); Share hands a content URI to the system sheet; Forward uses the existing chooser. | Messages, `:platform` | subagent B |
| 6 | Stickers and view-once: a sticker is drawn at sticker size with no bubble, animated WebP animates. **Received view-once cannot be opened on a linked device**: WhatsApp sends companions only an `<unavailable type="view_once">` placeholder (whatsapp-rust 0.7.0 `src/message/receive.rs:185-194`, `UnavailableType::ViewOnce` is an unrecoverable fanout, `wacore types/events.rs:1627`), and the gateway ignores that event today, so such messages vanish. The gateway turns `Event::UndecryptableMessage` with `ViewOnce` into a stored placeholder message (own commit, reported); Messages shows a "View once message: open it on your phone" bubble and preview. Sending a photo or video as view-once from the attachment preview works (the flag is set on send). | gateway, proto, `wa_sync`, Messages | subagent C |
| 7 | Several files: pick several photos, videos or documents (and share several into the app), a preview pager before sending with a caption per item, remove and reorder, each sent as its own message in order through the outbox, with thumbnail, dimensions and length measured on the device. | Messages, `:platform` | subagent C |
| 8 | Voice notes: hold the microphone to record (Opus in Ogg), slide left to cancel, slide up to lock, a live timer and level, preview before sending when locked, the waveform computed on the device and sent, "recording…" shown to the other side; playing a received voice note sends the played receipt once; the received waveform is drawn in the player. Microphone permission asked at first use. | Messages, `:platform` | subagent D |

Order: A (steps 1 and 2) first; then B and D in parallel (viewer and composer are different files); then C.

Out of this round: media re-upload for expired files (WA-R7), a sticker picker and packs, a camera inside the app,
video trimming or compression (a file over the 16 MiB rule is refused as today), albums as one grouped bubble
(received files stay single bubbles).

Close: merge `origin/ui-overhaul`, the gate with Rust plus `cargo test -p wa_gateway --profile checks`, note at the top
of `e6-status.md`, messages to the main thread (gateway commit) and the APK builder.

## Evidence

- **Steps 1 and 2** (uncommitted in the worktree when written; nothing was run against WhatsApp):
  - Wire: `MediaOptions` gained `thumbnail_b64`, `width`, `height`, `waveform_b64`, `animated`, `view_once`;
    `MediaMeta` gained `waveform_b64`. The preview does not travel as base64 in the query (a URL of that size
    does not pass the Cloudflare tunnel): the client puts it in front of the file and names its length
    (`thumbnail_len`). A send with a newer option asks `POST /v1/messages/media/options` (the same handler):
    an older gateway answers 404 there instead of dropping `view_once` without a word; the client then sends the
    file the old way, except view once, which fails.
  - whatsapp-rust 0.7.0 has no width or height in its option structs (`src/media.rs:20-63`); the gateway builds
    the protobuf itself and sets `width`, `height`, `jpegThumbnail`, `waveform`, `gifPlayback`, `isAnimated` and
    `viewOnce` on it (`waproto-0.7.0/src/whatsapp.proto`). View once is the inline flag inside a
    `viewOnceMessage` wrapper; the library marks the stanza from either (`src/send/mod.rs:533-536`).
  - Recording was already there: the route is `POST /v1/chatstate` with `state: "recording"` (not
    `/v1/chats/{jid}/typing` with `kind`), and the `typing` event carries the same three states.
  - No gateway store migration (the meta is a JSON column): no backup needed for this step.
  - Device store schema v6: `messages.viewed_ms`, `messages.played_ms`, index `messages_gallery`. New ops
    `wa_sync.chat_media`, `wa_sync.media_viewed`, `wa_sync.mark_played`; the outbox action `mark_played`; a tap
    now fetches a received view-once message that was not opened (before: never).
