# Plan: Rovsti Messages, round 3

- Base: `2390e2399e832c6c285c15660d269bf7c532016f` ("Seam: attachment pick with its own op and the MIME type")
- Unit: Rovsti Messages. Continues `messages-r2.md` (step 1 landed at the base).
- Checks for every step (all must stay green):
  `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android`

## Step 1. Pure: outgoing media in the WhatsApp timeline (`rovsti_wa_proto`)

Round 2 step 2, pending at the base. Files: `crates/rovsti_wa_proto/src/{media.rs (new), timeline.rs, lib.rs}`.

- `media::classify(mime, name, head) -> (MediaKind, String)`: picker MIME wins; missing or
  `application/octet-stream` falls back to magic bytes (JPEG, PNG, WEBP, GIF, PDF, MP4), then
  the file name; unknown is Document / `application/octet-stream`.
- `media::MAX_SEND_BYTES` (16 MiB) and `media::check_size(len) -> Result<(), MediaTooLarge>`.
- `Timeline::push_pending_media(local_id, kind, mime, caption, size, now_ms)`: optimistic
  media bubble, same confirm/echo rules as `push_pending`.
- `Timeline::restart_body(local_id) -> Option<MessageBody>`: retry for failed text or media;
  `restart` keeps its text-only signature.
- Tests: `classify_table`, `size_cap`, `restart_body_returns_media_and_text_restart_refuses_it`,
  and the `push_pending_media` confirm and echo tests.

## Step 2. Messages app: send WhatsApp media from the conversation, docs

Round 2 step 3, unchanged: attach button and chip in `conversation.rs`, retry from the cached
path, roadmap and acceptance docs.
