# Plan: WhatsApp gateway, round 2 (finish the app side)

- Base: `ebad6da` ("WhatsApp gateway + shared client + shared link UI: review fixes")
- Round 1 (`wa-gateway.md`) landed all eight steps: `1bba9dd` … `ebad6da`. No stash,
  nothing blocked. All checks are green at the base (fmt, clippy `-D warnings`,
  `cargo test --workspace`, both aarch64-linux-android checks).
- Checks every step keeps green:

```
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android
cargo check -p rovsti_wa_proto  --target aarch64-linux-android
```

## Gap analysis (code at `ebad6da` against the whole goal)

Done and kept as is: `rovsti_wa_proto`, `rovsti_wa_client` (all routes, events
socket with resume, call audio socket), `services/wa_gateway` (linking QR and code,
history sync, text, media, reactions, groups, presence, chat state, incoming and
outgoing calls through `voip-mlow`, FCM HTTP v1, hashed revocable keys), the shared
two-option widget in all four apps, `rovsti_whatsapp` deleted, Kotlin `WaPush` and
`WaCallAudio`, Phone incoming-call card, docs.

Still missing on the app side, so the server features cannot be used:

1. **Messages has no WhatsApp conversation.** The inbox shows the last message per
   chat only. Nothing calls `Gateway::messages`, `send_text`, `mark_read`, `react`
   or `media` (roadmap: "WhatsApp conversation view … open").
2. **No outgoing WhatsApp call.** `Gateway::place_call` exists and the gateway
   implements it, but no app calls it (roadmap: open).
3. **Keys are revocable only on the server.** The widget cannot list or revoke
   connect keys, and `KeyInfo` cannot tell which key is the caller's own.
4. **A logged-out instance is a dead end.** On `InstanceState::LoggedOut` the widget
   forgets the link and returns to "Choose"; the dead instance keeps a
   `max_instances` slot. `POST /v1/instance/link` (`Gateway::relink`) is never used,
   and there is no "Delete instance" either.
5. **FCM wake does nothing in Rust.** `kind::WA_PUSH` is emitted by Kotlin but no app
   handles it; a live process whose event socket sleeps in backoff (Doze) stays
   asleep until the backoff ends.
6. **No deploy files** for the gateway (roadmap: "Packaging the gateway").

Out of scope (owner-approved later items in the roadmap): video and group calls,
sending media from the phone (the strings-only JNI seam has no picker for bytes
yet), device testing.

---

## Step 1: pure WhatsApp conversation model (`rovsti_wa_proto::timeline`)

**Files:** `crates/rovsti_wa_proto/src/timeline.rs`, export in `lib.rs`.

```rust
pub struct Timeline { chat: String, items: Vec<Item>, has_older: bool }
pub enum ItemState { Sent, Delivered, Read, Pending { local_id: u64 }, Failed { local_id: u64 } }
pub struct Item { pub message: WaMessage, pub state: ItemState }
impl Timeline {
    pub fn new(chat: impl Into<String>) -> Self;
    /// A `GET /v1/chats/{jid}/messages` page (newest first). Dedups by id, keeps
    /// ascending time order, sets `has_older = page.len() == limit as usize`.
    pub fn apply_page(&mut self, page: Vec<WaMessage>, limit: u32);
    /// `Message`, `MessageUpdate`, `Receipt` for this chat; `true` when changed.
    pub fn apply_event(&mut self, event: &GatewayEvent) -> bool;
    pub fn push_pending(&mut self, local_id: u64, text: String, now_ms: i64);
    pub fn confirm(&mut self, local_id: u64, sent: &Sent);   // swaps in the real id
    pub fn fail(&mut self, local_id: u64);
    pub fn oldest_ms(&self) -> Option<i64>;                    // for `before_ms`
    pub fn unread_ids(&self) -> Vec<String>;                   // incoming, not yet read
    pub fn items(&self) -> &[Item];
    pub fn has_older(&self) -> bool;
}
/// `+905321234567` → `905321234567@s.whatsapp.net` (via `PhoneNumber::whatsapp_user`).
pub fn user_jid(number: &str) -> Result<String, LinkError>;
/// Day separators: `(index, day_start_ms)` for items whose local day differs from the previous.
pub fn day_breaks(items: &[Item], utc_offset_s: i32) -> Vec<(usize, i64)>;
```

A live `Message` whose id equals a pending item's confirmed id must not duplicate
(the echo of our own send can arrive before or after `confirm`). `Receipt{Read}`
upgrades `Sent/Delivered` but never downgrades.

**Tests:** page merge and dedup over two overlapping pages; live message after a page;
own echo before and after `confirm`; receipt upgrade/no downgrade; `MessageUpdate`
replaces reactions; events for another chat are ignored; `fail` keeps the text;
`user_jid` for `+90…`, local number without country code (error); `day_breaks`.

**Acceptance:** checks green; `rovsti_wa_proto` still has no I/O.

## Step 2: Messages: WhatsApp conversation with a text composer

**Files:** `apps/messages/src/conversation.rs` (new), `apps/messages/src/main.rs`.

- `pub struct Conversation { chat: String, title: SharedString, timeline: Timeline,
  composer: Entity<rovsti_ui::field::TextField>, gateway: Gateway, next_local: u64,
  loading: bool }` is a GPUI entity. On open it fetches
  `gateway.messages(&chat, None, 50)` on `cx.background_executor()`, then
  `mark_read(MarkRead { chat, sender: None, ids: timeline.unread_ids() })` when there
  are any. "Load earlier" at the top (a row, not scroll detection) fetches with
  `before_ms = timeline.oldest_ms()`.
- Send: `push_pending` → background `send_text(SendText{chat, text, quoted_id: None})`
  → `confirm` or `fail`; a failed bubble shows "Not sent · Tap to retry".
- Bubbles: own on the right (accent), others on the left (secondary fill), sender
  name above group messages (`is_group` from `ChatSummary`), time and tick state.
  Use `rovsti_ui::components` and the field helpers `input_layer`/`field_box`; read
  `.claude/skills/liquid-glass-gpui/SKILL.md` first. Glass composer bar above the
  keyboard inset like Phone's reply sheet.
- `main.rs`: the inbox row of a WhatsApp thread gets `.on_click` opening
  `Some(Entity<Conversation>)` as a pushed page with a back button (SMS rows stay
  inert). Gateway events observed from `WhatsAppSettings` are forwarded to the open
  conversation (`timeline.apply_event`) as well as folded into the inbox. Keep the
  `ChatSummary` list so the title is the chat `name` (fallback `+user`).

**Tests:** pure helpers stay in step 1; add a small `fn bubble_meta(&Item, now_ms) ->
String` in `conversation.rs` (time + "Sending…"/"Not sent"/ticks) with a unit test.

**Acceptance:** checks green; desktop Messages opens a WhatsApp thread (empty when
unlinked) without panics.

## Step 3: Messages: reactions, media, typing and presence in the conversation

**Files:** `apps/messages/src/conversation.rs`, `crates/rovsti_wa_proto/src/timeline.rs`.

- Reactions: chips under a bubble (`Reaction` grouped by emoji with counts via a pure
  `fn reaction_chips(&[Reaction]) -> Vec<(String, u32, bool /*mine*/)>` in
  `timeline.rs`). Long-press a bubble opens a glass strip of six quick emojis;
  tapping sends `react(SendReaction { chat, message_id, from_me, sender, emoji })`,
  tapping your own chip again sends `emoji: ""` (removes).
- Media: `MessageBody::Media` renders a placeholder card (kind label, caption, size).
  For `Image`/`Sticker` a tap downloads `gateway.media(id)` in the background and shows
  it with `img(Arc<Image>)` / `Image::from_bytes(ImageFormat::..)` (check the exact
  names at gpui-fusion `d6c8412`, as `rovsti_wa_ui::view` does for the QR); a small
  in-memory LRU of 16 decoded images per conversation. Other kinds show "Open on
  WhatsApp" text only.
- Typing: composer edits send `chat_state(ChatState { chat, state: Typing })`
  throttled to one per 5 s and `Paused` on send/blur; incoming `GatewayEvent::Typing`
  for this chat shows "typing…" under the title for 6 s. On open,
  `subscribe_presence(chat)` for 1:1 chats; `GatewayEvent::Presence` shows "online" /
  "last seen …".
- Pure throttle: `rovsti_wa_proto::timeline::TypingThrottle { fn on_edit(now_ms) ->
  bool, fn on_send() -> bool }`.

**Tests:** `reaction_chips` grouping and "mine"; `TypingThrottle` timing.

**Acceptance:** checks green.

## Step 4: Phone: outgoing WhatsApp calls

**Files:** `apps/phone/src/wa_call.rs`, `apps/phone/src/block_ui.rs` (Recents row menu),
`apps/phone/src/main.rs`.

- `Tracker::on_placed(call_id, peer, now_ms) -> Change` (pure, in `wa_call.rs`) shows
  an `Out`/`Ringing` card right away, so the UI does not wait for the first event; the
  later `CallUpdate`s for that id flow through `on_update` as today.
- `WaCallUi::place(&mut self, number: &str, cx)`: if a call is on screen or the widget
  is not connected, show the error line and return. Otherwise in the background:
  `user_jid(number)` → `gateway.place_call(&jid)` → `on_placed` →
  `rovsti_wa_client::call_audio(url, key, &call_id)` → `call_audio_start()`, and start
  the same `pump` as `answer` (factor the shared tail of `answer` into
  `fn attach_audio(&mut self, call_id, cx)`). `hang_up(false)` already ends it;
  `status_line` already covers `(Ringing, Out)` "Calling…".
- Recents row menu: new item `rm-wa` "WhatsApp call" (`Icon::LucidePhoneCall` or the
  closest existing Lucide phone icon in `gpui_icons` at `d6c8412`; grep, do not guess),
  enabled when the number is non-empty and `whatsapp.read(cx).is_connected()`. Its
  click closes the menu and calls `self.wa_call.update(cx, |ui, cx| ui.place(&number, cx))`.
  Add the id to the raw-touch router the same way as `rm-msg`.

**Tests:** `on_placed` then `Active` update keeps one card and starts the timer; a
second `on_placed` while a call is shown is `Ignored`; `place` refusal reasons via a
pure `fn can_place(connected: bool, busy: bool, number: &str) -> Result<(), &'static str>`.

**Acceptance:** checks green; desktop Phone shows the menu item (disabled when unlinked).

## Step 5: widget: connected apps (list and revoke keys), relink, delete instance

**Files:** `crates/rovsti_wa_proto/src/api.rs`, `services/wa_gateway/src/routes.rs`
(+ `store.rs` if needed), `services/wa_gateway/tests/api.rs`,
`crates/rovsti_wa_ui/src/{model.rs,view.rs}`.

- Proto: `KeyInfo` gains `#[serde(default)] pub current: bool`. Gateway `list_keys`
  sets it for the key that authenticated the request. `DELETE /v1/keys/{own id}` stays
  allowed (it is how an app revokes itself) and answers 204.
- Model (`rovsti_wa_ui::model`):
  - `Screen::Keys { keys: Vec<KeyInfo> }`, `Screen::LoggedOut`,
    `Screen::ConfirmDelete`.
  - `Input::{OpenKeys, KeysLoaded(Vec<KeyInfo>), Revoke(String), Revoked(String),
    Relink(LinkRequest), RequestDelete, ConfirmDelete, Deleted}`.
  - `Effect::{ListKeys, RevokeKey(String), Relink(StoredLink, LinkRequest),
    DeleteInstance(StoredLink)}`.
  - `LoggedOut` (status or event) no longer forgets: it shows `Screen::LoggedOut` with
    "Link again" (QR or pairing code → `Relink` → existing `ShowQr`/`ShowCode` path on
    the **same** instance and keys) and "Unlink this app". `Banned` keeps today's path.
  - Revoking the `current` key ends in `Forget` + `Choose`. Revoking another key
    removes it from the list.
  - `ConfirmDelete` → `DeleteInstance` → `Forget` + `Choose` (every app's key stops
    working; the confirm text says so).
- View: on `Connected`, add "Connected apps" (label, "this app" for `current`, last
  used as a relative day) with a Revoke button per row, and "Delete instance…" in
  destructive red. Effects run through the existing `call` helper with
  `Gateway::{keys, revoke_key, relink, delete_instance}`.

**Tests:** model: keys list → revoke other → list shrinks; revoke current → `Forget`;
`LoggedOut` → `Relink(Qr)` → `Event(Qr)` shows QR → `State(Connected)` back to
`Connected` without `Save` of a new link; delete confirm path; old JSON without
`current` deserialises. Gateway API test: `GET /v1/keys` marks exactly the caller's
key as `current`; relink after a fake-driver logout emits `Qr`.

**Acceptance:** checks green.

## Step 6: FCM wake reaches the event stream

**Files:** `crates/rovsti_wa_proto/src/push.rs` (new), `crates/rovsti_wa_client/src/events.rs`,
`crates/rovsti_wa_ui/src/view.rs`, `apps/phone/src/backend.rs` (or wherever Phone
drains its `events` sink), `apps/messages/src/main.rs`, `apps/contacts/src/main.rs`.

- Pure: `pub enum Wake { Call { call_id: String }, Message { chat: String } }` and
  `pub fn parse_wake(json: &str) -> Option<Wake>` for the AD-WA6 data payloads.
- Client: `EventStream::reconnect_now(&self)` sends on a `tokio::sync::mpsc` /
  `Notify` that the reconnect loop `select!`s on while sleeping in backoff, so the
  next attempt starts at once (no effect while connected). Unit-test the loop's
  decision with a pure helper if the select is hard to test (`fn next_delay(kicked,
  backoff_ms) -> u64`).
- Widget: `pub fn wake(&mut self, cx)` calls `reconnect_now`, or `Verify` when no
  stream runs.
- Apps: Phone's sink drain and Contacts' sink drain route `kind::WA_PUSH` to
  `whatsapp.update(cx, |w, cx| w.wake(cx))`. Messages and Stops install their own
  sink with `events::install_sink_owned()` / `clear_sink_if` (the Contacts pattern) and
  do the same. On desktop nothing is ever sent.

**Tests:** `parse_wake` for call, message, unknown kind, garbage; `next_delay`.

**Acceptance:** checks green, both Android checks.

## Step 7: gateway packaging and docs close-out

**Files:** `services/wa_gateway/deploy/{Dockerfile,wa-gateway.service,Caddyfile.example,config.example.toml}`,
`docs/whatsapp-gateway.md`, `docs/architecture.md`, `docs/roadmap.md`, `README.md`.

- Dockerfile: multi-stage `rust:1.94-bookworm` build of `-p wa_gateway --release`,
  `debian:bookworm-slim` runtime with `ca-certificates`, non-root user, volume for
  the data dir, `EXPOSE 8787`, binds `0.0.0.0:8787` through the config. The systemd
  unit runs as a dedicated user with `StateDirectory=`, `NoNewPrivileges=yes`,
  `ProtectSystem=strict`. Caddy example terminates TLS and proxies WebSockets.
  `config.example.toml` mirrors every field of `services/wa_gateway/src/config.rs`
  (read it; do not invent fields).
- Docs: the deploy section points at these files; architecture gains the key list and
  revoke, relink, wake (`reconnect_now`) and outgoing calls; roadmap ticks the
  conversation view, outgoing calls, key UI and packaging; README quick start links
  the deploy folder.

**Tests:** a gateway unit test that `config.example.toml` parses with the real config
loader (`include_str!`), so the example cannot drift.

**Acceptance:** checks green; the unit is done after this step.
