# Plan: WhatsApp gateway + shared client + shared link UI

- Base: `6ca7f07` ("Docs: WhatsApp moves to a shared backend gateway")
- Unit owner decisions: everything is local except WhatsApp. WhatsApp lives on
  `services/wa_gateway`. Apps reach it only through `crates/rovsti_wa_client`. A
  linked account is an *instance*. Each app offers two options: link for the first
  time, or connect to an existing instance with a connect key.
- Every step leaves these checks green:

```
cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace
cargo check -p rovsti_android --target aarch64-linux-android
cargo check -p rovsti_wa_client --target aarch64-linux-android   # from step 2 on
cargo check -p rovsti_wa_proto  --target aarch64-linux-android   # from step 1 on (cheap)
```

## What the code looks like today (source of truth)

- `crates/rovsti_whatsapp` (`session.rs`, `qr.rs`): one local whatsapp-rust `Bot` on
  its own Tokio thread. It emits `WaEvent {Qr, PairCode, Connected, LoggedOut,
  Message, Failed}` and maps `MessageContext` to `rovsti_core::Message`. `qr_png()`
  renders a payload as a PNG.
- `apps/messages/src/whatsapp.rs`: the `WhatsAppScreen` entity with `LinkState`,
  QR display and share, plus `take_new_messages()`. `main.rs` observes it and folds
  messages into the inbox.
- Settings tabs: Phone uses `settings_ui.rs`, whose second-level pages are `Sub
  {Blocked, Replies}` with id prefixes for the raw-touch router. Contacts has
  `settings_tab()`. Stops and Messages have an `empty_state` placeholder.
- `rovsti_ui::storage`: `data_dir(app, cx)` plus an atomic temp-and-rename save
  (`save_prefs`). Text input is the crate-private `SearchField` in
  `apps/{phone,contacts}/src/field.rs`, built on gpui `EntityInputHandler`.
  Clipboard: `cx.read_from_clipboard()` / `cx.write_to_clipboard(ClipboardItem::new_string(..))`.
- The `rovsti_android` JNI seam only passes strings, ints and bools.
  `bridge::call_{bool,int,string}`. Kotlin-to-Rust events go through
  `events::dispatch(kind, json)`, coming from `RovstiBridge.nativeOnEvent`. On desktop
  every function returns `Error::Unsupported`.
- Workspace members are `apps/*` and `crates/*`. whatsapp-rust 0.7 is a workspace dep
  with `default-features = false` and `sqlite-storage, tokio-transport, tokio-runtime,
  ureq-client, tokio-native`.

## External APIs verified in source (registry, whatsapp-rust 0.7.0 / wacore 0.7.0)

- Builder (`bot.rs`): `Bot::builder().with_backend(SqliteStore::new(path).await?)`,
  `.on_qr_code(|payload, valid_for| ..)`, `.on_pair_code(|code, valid_for| ..)`,
  `.on_connected`, `.on_logged_out`, `.on_message(|ctx: MessageContext| ..)`,
  `.on_event_for(&[EventKind::..], |Arc<Event>, Arc<Client>| ..)`, `.on_event`,
  `.with_pair_code(PairCodeOptions { phone_number, ..Default::default() })`,
  `.with_push_name`, then `.build().await` -> `Bot`, `bot.client() -> Arc<Client>`,
  `bot.spawn() -> BotHandle`, `client.disconnect().await`.
  `Client::pair_with_code(self: &Arc<Self>, PairCodeOptions) -> Result<String, PairError>`
  requests a code on demand. `examples/multi_session_metrics.rs` shows the
  multi-account pattern: one `SqliteStore` file per account, several Bots in one
  process.
- Events (`wacore::types::events::Event`): `Connected, Disconnected, PairSuccess,
  PairError, LoggedOut, PairingQrCode, PairingCode, Messages(MessageBatch),
  Receipt, ChatPresence, Presence, GroupUpdate, HistorySync(Box<LazyHistorySync>)`
  (`.get() -> Option<&wa::HistorySync>`, `.stream()`), `IncomingCall(IncomingCall),
  MissedCall, CallEndedElsewhere, StreamReplaced, TemporaryBan, ConnectFailure`, and others.
- Send: `client.send_text(to: impl Into<Jid>, text) -> Result<SendResult, SendError>`,
  `client.send_message(to, wa::Message)`, `send_message_with_options`,
  `client.send_reaction(chat, target_key: wa::MessageKey, emoji)` (an empty emoji
  removes the reaction), `client.mark_as_read(&chat, sender: Option<&Jid>, &[&str])`.
- Media: `client.upload(Vec<u8>, MediaType, UploadOptions) -> UploadResponse {url,
  direct_path, media_key, file_enc_sha256, file_sha256, file_length,
  media_key_timestamp, streaming_sidecar}`. `client.download(&dyn Downloadable) ->
  Vec<u8>`. `Downloadable` is implemented for `wa::message::{Image,Video,Audio,
  Document,Sticker}Message`.
- Presence and typing: `client.presence().set_available()/set_unavailable()/subscribe(jid)`,
  `client.chatstate().send_composing(&jid)/send_paused/send_recording`.
- Groups: `client.groups().get_participating() -> HashMap<Jid, GroupMetadata>`,
  `get_metadata(&jid)`, `query_info`, `create_group`, `set_subject`, `leave`,
  `add_participants` / `remove_participants`, `get_invite_link`.
- Calls (`client/voip.rs`, `voip/facade.rs`, `voip/audio.rs`):
  `client.voip().reject(&IncomingCall)`, `.terminate(call_id, &peer, &call_creator)`,
  `.reject_call(..)` (no feature needed). These need feature `voip-runtime`, which is
  pulled in by `voip-mlow`: `client.voip().accept(&incoming).audio(src, sink).start().await
  -> CallHandle` and `client.voip().call(&peer_jid).audio(src, sink).start().await ->
  CallHandle`. `CallHandle::{call_id(), peer_jid(), set_muted(bool), is_muted(),
  hangup().await, events() -> async_channel::Receiver<CallEvent>, wait_ended().await}`.
  `AudioSource` is implemented for `async_channel::Receiver<Vec<i16>>` and `AudioSink`
  for `async_channel::Sender<Vec<i16>>`. Frames are **960 mono i16 samples at 16 kHz
  (60 ms)**; the engine drops frames of any other length. `IncomingCall` is `Clone`
  and has `action: CallAction::Offer { call_id, call_creator, caller_pn, is_video,
  group_jid, .. }`. `CallEvent` covers `RelayAllocated, RelayAllocateFailed(u16),
  RelayAllocateTimedOut, RelayReconnectTimedOut, ..`.
  **Use feature `voip-mlow` only.** It is pure-Rust MLOW with PCM I/O, so libopus is
  not needed. `voip` and `voip-libopus` pull the C `opus` crate. A probe that enables
  `voip-mlow` and calls `accept().audio().start()`, `events()` and `hangup()` passes
  `cargo check` on this host (2m38s cold).
- Server: axum 0.8.9 with feature `ws` (`axum::extract::ws::WebSocketUpgrade`) was
  checked together with the above.
- Android TLS without the NDK: `ring` (and so `rustls/ring` and ureq's default
  `rustls`) **fails** `cargo check --target aarch64-linux-android` here, because no
  `aarch64-linux-android-clang` is installed. A probe with these dependencies **passes**
  that check:
  `rustls = {0.23, default-features=false, features=["std","tls12","logging"]}`,
  `rustls-graviola = "0.4"` (pure Rust crypto provider; aarch64 needs aes, sha2, pmull
  and neon, which every Xiaomi arm64 SoC has),
  `webpki-roots = "1"`,
  `tokio-tungstenite = {0.30, default-features=false, features=["connect","handshake","__rustls-tls"]}`
  (`connect_async_tls_with_config(req, None, false, Some(Connector::Rustls(Arc<ClientConfig>)))`),
  `ureq = {3, default-features=false, features=["rustls-no-provider","json"]}`
  (`TlsConfig::builder().unversioned_rustls_crypto_provider(Arc<CryptoProvider>)`,
  `RootCerts::WebPki`).
- FCM HTTP v1 signing on the server: `ring 0.17` (`RsaKeyPair::from_pkcs8`,
  `signature::RSA_PKCS1_SHA256`) and `rustls-pki-types 1.15` (`PemObject::from_pem_slice`)
  are already in `Cargo.lock`. Send through `ureq` 3, which is already in the lock.
- Gateway database: `rusqlite = "=0.39.0"` is the only rusqlite that uses
  `libsqlite3-sys ^0.37`, the version whatsapp-rust-sqlite-storage/diesel already
  links. Any other version is a `links = "sqlite3"` conflict.

## Architecture decisions for this unit

- **AD-WA1 Crates.**
  - `crates/rovsti_wa_proto` is pure and platform-free: wire types, connect keys, URL
    rules, the audio frame codec, the event log and the call state machine.
  - `crates/rovsti_wa_client` is the HTTP and WebSocket client. It uses rustls with
    graviola and builds for aarch64-linux-android without the NDK.
  - `crates/rovsti_wa_ui` is the shared GPUI settings widget (two options) plus QR
    rendering, which moves here from `rovsti_whatsapp::qr`.
  - `services/wa_gateway` is the server, with lib and bin. The "binary only" rule in
    CLAUDE.md covers `apps/*` only; the gateway needs `src/lib.rs` for `tests/`.
  - `crates/rovsti_whatsapp` is **deleted** in step 7. Its session code moves to
    `services/wa_gateway/src/wa/`.
- **AD-WA2 Connect keys.**
  - Format: `rwk_` followed by base64url (no padding) of 32 random bytes, 47 characters
    in all.
  - The key is a bearer secret. The server stores only `sha256(key)` as hex, plus
    `key_id` (8 random bytes, hex), label, `created_ms` and `last_used_ms`. Lookup is
    by hash and the comparison is constant-time.
  - "Show connect key" in an app **mints a new key** labeled for that share
    (`POST /v1/keys`), so each app's key can be revoked on its own.
  - Apps store `{gateway_url, instance_id, key}` in `whatsapp-link.json` in their own
    `data_dir`, saved atomically.
- **AD-WA3 Transport.** The gateway serves plain HTTP on `bind` (default
  `127.0.0.1:8787`) behind a TLS-terminating reverse proxy. The client accepts
  `https://` anywhere. It accepts `http://` only for loopback, `10.0.2.2` (the
  emulator), RFC1918 and `.local` hosts. `Authorization: Bearer <key>` is sent on every
  request and on the WebSocket upgrade.
- **AD-WA4 Events.**
  - `GET /v1/events?after=<seq>` (WebSocket) sends JSON text frames
    `EventEnvelope { seq: u64, at_ms: i64, event: GatewayEvent }`.
  - The gateway keeps a 1024-entry ring per instance (`EventLog`) so a client resumes
    after a reconnect. A gap becomes `GatewayEvent::Resync`, and the client then
    re-fetches with `GET /v1/chats`.
- **AD-WA5 Call audio.**
  - `GET /v1/calls/{call_id}/audio` (WebSocket) carries binary frames in both
    directions. Each frame is exactly 1920 bytes: 960 i16 LE samples, 16 kHz mono,
    60 ms.
  - The gateway feeds these into `async_channel` pairs and hands them to whatsapp-rust
    as `AudioSource`/`AudioSink`.
  - On Android, mic and speaker go through Kotlin `AudioRecord`/`AudioTrack`. The
    frames cross JNI as **base64 strings**, which keeps the strings-only seam: about
    2.6 KB, 17 times a second each way.
- **AD-WA6 Push.**
  - FCM is enabled only when `fcm_service_account` (a JSON path) and `fcm_project_id`
    are configured; otherwise push is a no-op.
  - Apps register `{app: AppId, token}` per instance.
  - The gateway sends **data-only high-priority** messages with only these fields:
    `{"kind":"call","call_id","from","name"}` and
    `{"kind":"message","chat","count"}`.
  - No message text goes to Google. The app wakes, connects the WebSocket and
    fetches.
- **AD-WA7 Server storage.**
  - `<data>/gateway.db` (rusqlite) holds the tables `instances`, `keys`,
    `push_tokens`, `chats`, `messages` and `media`.
  - `<data>/instances/<id>/wa.db` is whatsapp-rust's `SqliteStore`, one per instance.
  - The gateway keeps message bodies: it is the user's own server, and history sync
    requires it.
  - Anyone who can reach the gateway can create an instance, capped by
    `max_instances` (default 4).
  - Failed bearer authentication is limited per IP by a pure token bucket
    (`rovsti_wa_proto::limit`).

---

## Step 1: `rovsti_wa_proto` (pure wire and domain crate)

**Files:** `crates/rovsti_wa_proto/{Cargo.toml,src/lib.rs,src/key.rs,src/url.rs,src/api.rs,src/event.rs,src/log.rs,src/audio.rs,src/call.rs,src/limit.rs}`,
plus a root `Cargo.toml` workspace dep `rovsti_wa_proto = { path = "crates/rovsti_wa_proto" }`
and the workspace deps `sha2 = "0.10"` and `base64 = "0.22"` (both already in the lock).
The crate depends on serde, serde_json, thiserror, sha2, base64 and rovsti_core (for
`PhoneNumber::whatsapp_user`). It has no I/O and no randomness, because callers
supply the bytes.

- `key.rs`:
  `pub struct ConnectKey(String)` with a redacting `Debug` ("rwk_…").
  `ConnectKey::from_bytes(&[u8; 32]) -> Self`.
  `ConnectKey::parse(&str) -> Result<Self, KeyError>`, which trims whitespace and
  newlines from pasted text, checks the `rwk_` prefix, length 47 and the base64url
  alphabet.
  `fn as_str(&self) -> &str`, `fn hash(&self) -> KeyHash`.
  `pub struct KeyHash([u8; 32])` with `to_hex()`, `from_hex()` and
  `ct_eq(&self, &KeyHash) -> bool` (constant-time XOR fold).
  `pub struct KeyId(String)` with `KeyId::from_bytes(&[u8; 8])`.
- `url.rs`:
  `pub struct GatewayUrl(String)` and `GatewayUrl::parse(&str) -> Result<Self, UrlError>`.
  It enforces AD-WA3, strips trailing `/`, rejects paths other than `/` and empty
  hosts, and has `fn join(&self, path) -> String` and `fn ws(&self, path) -> String`
  (`https`→`wss`, `http`→`ws`). Use a hand-written split; no `url` crate is needed.
- `api.rs` (all `Serialize + Deserialize`, `#[serde(rename_all = "snake_case")]`):
  - `AppId { Phone, Messages, Contacts, Stops }`
  - `LinkRequest { Qr, PhoneNumber { number: String } }`; `fn pair_phone(&self) ->
    Result<Option<String>, LinkError>` returns the digits via `PhoneNumber::parse(..)?.whatsapp_user()`
  - `CreateInstance { link: LinkRequest, app: AppId }`
  - `CreatedInstance { instance_id, key: String, key_id }`
  - `InstanceState { Linking, Connected, Disconnected, LoggedOut, Banned }`
  - `InstanceStatus { instance_id, state, own_jid: Option<String>, push_name: Option<String> }`
  - `MintKey { label }` and `IssuedKey { key_id, key, label }`
  - `KeyInfo { key_id, label, created_ms, last_used_ms }`
  - `PushRegistration { app: AppId, token }`
  - `SendText { chat, text, quoted_id: Option<String> }` and `Sent { message_id, timestamp_ms }`
  - `SendReaction { chat, message_id, from_me: bool, sender: Option<String>, emoji }`
  - `MarkRead { chat, sender: Option<String>, ids: Vec<String> }`
  - `SetPresence { available: bool }` and `ChatState { chat, state: Typing|Recording|Paused }`
  - `MediaKind { Image, Video, Audio, Document, Sticker }`
  - `ChatSummary { jid, name: Option<String>, last: Option<WaMessage>, unread: u32, is_group: bool }`
  - `WaMessage { id, chat, sender: Option<String>, from_me, timestamp_ms, body:
    MessageBody, reactions: Vec<Reaction> }`, where `MessageBody { Text(String), Media
    { kind, caption: Option<String>, mime: String, size: u64 }, Unsupported }`
  - `GroupInfo { jid, subject, participants: Vec<Participant { jid, admin: bool }> }`
  - `PlaceCall { to }`, `CallStarted { call_id }`, `SetMuted { muted }`
  - `ApiError { code: ErrorCode, message }` with `ErrorCode { Unauthorized,
    NotFound, BadRequest, Conflict, LimitReached, NotConnected, RateLimited, Internal }`
  - `fn to_rovsti(&WaMessage) -> rovsti_core::Message` (`ThreadId::WhatsApp(chat)`,
    with the sender parsed as `+{user}` like today's `session::to_rovsti`)
- `event.rs`: `EventEnvelope { seq, at_ms, event }` and `GatewayEvent { Qr { payload,
  valid_for_ms }, PairCode { code, valid_for_ms }, State(InstanceStatus),
  Message(WaMessage), MessageUpdate { chat, id, reactions, read: bool }, Receipt { chat,
  ids, kind: Delivered|Read }, Presence { jid, available, last_seen_ms }, Typing { chat,
  jid, state }, HistorySynced { chats: u32, progress: Option<u32> }, GroupChanged { jid },
  Call(CallUpdate), Resync, KeyRevoked }`.
  `CallUpdate { call_id, peer, peer_name: Option<String>, direction: In|Out, phase: CallPhase, is_video }`.
- `log.rs`: `pub struct EventLog { cap, next_seq, ring: VecDeque<EventEnvelope> }` with
  `push(at_ms, GatewayEvent) -> EventEnvelope` and `since(after: Option<u64>) -> Since
  { Events(Vec<EventEnvelope>) | Gap }`.
- `audio.rs`: `FRAME_SAMPLES = 960`, `SAMPLE_RATE = 16_000`, `FRAME_BYTES = 1920`.
  `encode_frame(&[i16]) -> Result<Vec<u8>, FrameError>` (exact length, LE),
  `decode_frame(&[u8]) -> Result<Vec<i16>, FrameError>`, `frame_to_base64(&[i16]) ->
  String` and `frame_from_base64(&str)` (for the JNI seam), and
  `Rechunker { push(&[i16]) -> Vec<Vec<i16>> }`, which turns arbitrary
  AudioRecord reads into 960-sample frames.
- `call.rs`: the pure call state machine.
  `CallPhase { Ringing, Connecting, Active, Ended(EndReason) }`,
  `EndReason { Hangup, Rejected, Missed, Remote, Failed(String), ElsewhereAnswered }`.
  `CallMachine::new(direction)` with `fn apply(&mut self, CallInput) -> Option<CallPhase>`.
  The inputs are `Accept, Reject, RemoteAccepted, MediaUp, RemoteTerminate, Hangup,
  Timeout, Failed`. `apply` refuses invalid transitions: once ended, a call stays
  ended, and an outgoing call cannot be accepted.
- `limit.rs`: `TokenBucket { capacity, refill_per_sec }` with
  `try_take(now_ms) -> bool`, `AuthLimiter` (a `HashMap<IpAddr, TokenBucket>` with
  pruning) and `fn backoff_ms(attempt: u32) -> u64` (500 ms doubling, 30 s cap,
  deterministic; the client adds jitter).

**Tests** (unit, per module):
- Key round-trip, paste with spaces or newline, wrong prefix or length rejected,
  `Debug` hides the key, the hash is stable (a fixed vector), `ct_eq`.
- URL: `https` accepted; `http` for `127.0.0.1`, `10.0.2.2`, `192.168.1.5` and
  `foo.local`; `http://example.com` rejected; trailing slash removed; ws mapping.
- serde round-trip of every enum, with the snake_case JSON pinned.
- EventLog: resume, gap at the cap, `seq` monotonic.
- Audio: encode/decode round-trip, wrong length rejected, base64 round-trip,
  Rechunker with 333-sample pushes.
- CallMachine transitions table and token bucket refill.
- `LinkRequest::pair_phone` with `+90…` and with a number missing its country code.

**Acceptance:** all checks green, and `cargo check -p rovsti_wa_proto --target
aarch64-linux-android` passes.

## Step 2: `rovsti_wa_client` (HTTP + WebSocket, Android-buildable)

**Files:** `crates/rovsti_wa_client/{Cargo.toml,src/lib.rs,src/tls.rs,src/http.rs,src/events.rs,src/audio.rs,src/error.rs}`
and a workspace dep entry. Dependencies: `rovsti_wa_proto`, `serde`, `serde_json`,
`thiserror`, `log`, `futures`, `tokio` (+`net`), `rustls` (no default features; `std`,
`tls12`, `logging`), `rustls-graviola 0.4`, `webpki-roots 1`, `tokio-tungstenite 0.30`
(`connect`, `handshake`, `__rustls-tls`), `ureq 3` (`rustls-no-provider`, `json`) and
`getrandom 0.3` (already in the lock) for jitter. **Do not** add `ring`, `aws-lc-rs` or
ureq's default `rustls` feature; the android check would fail without the NDK.

- `tls.rs`:
  `fn provider() -> Arc<rustls::crypto::CryptoProvider>` uses
  `rustls_graviola::default_provider()`.
  `fn client_config() -> Arc<rustls::ClientConfig>` uses
  `ClientConfig::builder_with_provider(provider()).with_safe_default_protocol_versions()?
  .with_root_certificates(webpki_roots)`.
- `error.rs`: `ClientError { Url(UrlError), Key(KeyError), Unauthorized, Api(ApiError),
  Http(String), Ws(String), Decode(String), Closed }`.
- `http.rs`:
  ```rust
  pub struct Gateway { url: GatewayUrl, key: Option<ConnectKey>, agent: ureq::Agent }
  impl Gateway {
      pub fn new(url: GatewayUrl, key: Option<ConnectKey>) -> Self;
      pub fn with_key(&self, key: ConnectKey) -> Self;
      pub fn create_instance(&self, req: &CreateInstance) -> Result<CreatedInstance, ClientError>; // no auth
      pub fn status(&self) -> Result<InstanceStatus, ClientError>;
      pub fn relink(&self, link: &LinkRequest) -> Result<(), ClientError>;
      pub fn delete_instance(&self) -> Result<(), ClientError>;
      pub fn mint_key(&self, label: &str) -> Result<IssuedKey, ClientError>;
      pub fn keys(&self) -> Result<Vec<KeyInfo>, ClientError>;
      pub fn revoke_key(&self, key_id: &str) -> Result<(), ClientError>;
      pub fn register_push(&self, reg: &PushRegistration) -> Result<(), ClientError>;
      pub fn chats(&self) -> Result<Vec<ChatSummary>, ClientError>;
      pub fn messages(&self, chat: &str, before_ms: Option<i64>, limit: u32) -> Result<Vec<WaMessage>, ClientError>;
      pub fn send_text(&self, req: &SendText) -> Result<Sent, ClientError>;
      pub fn send_media(&self, chat: &str, kind: MediaKind, mime: &str, caption: Option<&str>, bytes: Vec<u8>) -> Result<Sent, ClientError>;
      pub fn media(&self, message_id: &str) -> Result<Vec<u8>, ClientError>;
      pub fn react(&self, req: &SendReaction) -> Result<(), ClientError>;
      pub fn mark_read(&self, req: &MarkRead) -> Result<(), ClientError>;
      pub fn set_presence(&self, available: bool) -> Result<(), ClientError>;
      pub fn chat_state(&self, req: &ChatState) -> Result<(), ClientError>;
      pub fn groups(&self) -> Result<Vec<GroupInfo>, ClientError>;
      pub fn place_call(&self, to: &str) -> Result<CallStarted, ClientError>;
      pub fn accept_call(&self, call_id: &str) -> Result<(), ClientError>;
      pub fn reject_call(&self, call_id: &str) -> Result<(), ClientError>;
      pub fn end_call(&self, call_id: &str) -> Result<(), ClientError>;
      pub fn set_muted(&self, call_id: &str, muted: bool) -> Result<(), ClientError>;
  }
  ```
  The methods are blocking; GPUI apps run them on `cx.background_executor()`. They
  map non-2xx responses with an `ApiError` body to `ClientError::Api`, and 401 to
  `Unauthorized`. Build the agent with `ureq::Agent::config_builder()
  .tls_config(TlsConfig::builder().provider(TlsProvider::Rustls)
  .unversioned_rustls_crypto_provider(tls::provider()).root_certs(RootCerts::WebPki).build())
  .http_status_as_error(false)`. The routes are the table in step 3; keep them as
  `const`s in `rovsti_wa_proto::api::routes` so the server and client share them
  (add that module in this step).
- `events.rs`:
  `pub fn subscribe(url: GatewayUrl, key: ConnectKey, after: Option<u64>) -> Result<(EventStream, futures::channel::mpsc::UnboundedReceiver<ClientEvent>), ClientError>`
  spawns a named thread `wa-events` running a 1-worker Tokio runtime, as
  `rovsti_whatsapp::Session` does today. It connects with
  `connect_async_tls_with_config(request_with_bearer, None, true, Some(Connector::Rustls(tls::client_config())))`,
  remembers the last `seq`, reconnects with `backoff_ms` plus jitter, and answers
  pings. It emits `ClientEvent { Connected, Event(EventEnvelope), Disconnected(String),
  Unauthorized }`; on `Unauthorized` it stops, because the key was revoked.
  `EventStream` stops on `Drop` through a oneshot, the same as `Session`.
- `audio.rs`:
  `pub fn call_audio(url, key, call_id) -> Result<CallAudio, ClientError>`.
  `CallAudio { pub mic: futures::channel::mpsc::Sender<Vec<i16>>, pub speaker: mpsc::Receiver<Vec<i16>> }`
  runs on its own thread and runtime. Outgoing frames go through `encode_frame`;
  incoming binary frames through `decode_frame`, and frames of the wrong size are
  dropped and logged. Drop closes the socket.

**Tests:**
- Unit: request building (route + bearer header, done with a pure `fn request(..) ->
  http::Request<()>` helper that the WS paths use too), error mapping from canned
  `ApiError` JSON, and `tls::client_config()` builds.
- Integration (`tests/loopback.rs`): a tiny `std::net::TcpListener` HTTP/1.1 stub in
  the test (no server crate) answers `GET /v1/instance` with canned JSON. Assert that
  `status()` parses it and that a 401 maps to `Unauthorized`.

**Acceptance:** checks green, plus `cargo check -p rovsti_wa_client --target
aarch64-linux-android` passes without the NDK, and `cargo tree -p rovsti_wa_client
--target aarch64-linux-android -e normal | grep -E "ring|aws-lc"` is empty.

## Step 3: `services/wa_gateway` core (server, store, keys, events) on a fake driver

**Files:**
- Root `Cargo.toml`: `members = ["apps/*", "crates/*", "services/*"]` (leave
  `default-members` as is, adding `"services/*"` is fine). Workspace deps:
  `axum = { version = "0.8", features = ["ws"] }`, `rusqlite = "=0.39.0"`,
  `async-channel = "2"`, `getrandom = "0.3"`, `env_logger`, and `toml = "0.9"` (in the lock).
- `services/wa_gateway/{Cargo.toml,src/lib.rs,src/main.rs,src/config.rs,src/store.rs,src/auth.rs,src/routes.rs,src/hub.rs,src/driver.rs,tests/api.rs}`.
  Dependencies: `rovsti_wa_proto`, axum, tokio (`full`), rusqlite, serde, serde_json,
  log, env_logger, getrandom and async-channel. Dev-dependency: `rovsti_wa_client`, so
  the tests use the real client.

What each file holds:
- `config.rs`: `Config { bind: SocketAddr, data_dir: PathBuf, max_instances: u32,
  fcm: Option<FcmConfig { project_id, service_account: PathBuf }> }`. It loads from
  `--config <file.toml>` with env overrides `WA_GATEWAY_BIND`, `WA_GATEWAY_DATA`,
  `WA_GATEWAY_MAX_INSTANCES`, `WA_GATEWAY_FCM_PROJECT` and `WA_GATEWAY_FCM_SA`. Write
  it as a pure `Config::from_parts(file: Option<&str>, env: &dyn Fn(&str) -> Option<String>)`
  so it can be tested.
- `store.rs`: `Store` wraps `Mutex<rusqlite::Connection>` and runs its work in
  `spawn_blocking`. A `user_version` migration v1 creates:
  `instances(id TEXT PK, created_ms, state, own_jid, push_name)`,
  `keys(key_id PK, instance_id, hash BLOB UNIQUE, label, created_ms, last_used_ms, revoked INT)`,
  `push_tokens(instance_id, app, token, PRIMARY KEY(instance_id, app))`,
  `chats(instance_id, jid, name, is_group, unread, last_ms, PK(instance_id,jid))`,
  `messages(instance_id, chat, id, sender, from_me, ts_ms, body_json, raw_proto BLOB, PK(instance_id, chat, id))`
  (`raw_proto` holds the encoded `wa::Message`, needed for media download and reactions)
  and `reactions(instance_id, chat, id, sender, emoji)`.
  Functions: `create_instance(id, first_key: (KeyId, KeyHash, label))`,
  `instance_for_key(&KeyHash) -> Option<(InstanceId, KeyId)>` (it also bumps
  `last_used_ms`), `mint_key`, `list_keys`, `revoke_key`, `count_instances`,
  `delete_instance`, `set_state`, `upsert_chat`, `insert_message`,
  `messages(chat, before, limit)`, `chats()`, `set_push_token` and `push_tokens(instance)`.
- `driver.rs`: `#[async_trait]`-free trait objects using boxed futures:
  ```rust
  pub trait WaDriver: Send + Sync + 'static {
      fn start(&self, instance: InstanceId, link: Option<LinkRequest>, sink: EventSink) -> BoxFuture<'_, Result<(), DriverError>>;
      fn stop(&self, instance: &InstanceId) -> BoxFuture<'_, ()>;
      fn logout(&self, instance: &InstanceId) -> BoxFuture<'_, Result<(), DriverError>>;
      fn send_text(&self, instance: &InstanceId, req: SendText) -> BoxFuture<'_, Result<Sent, DriverError>>;
      // grows in steps 4–6: react, mark_read, presence, chat_state, media, groups, calls
  }
  ```
  `EventSink` is a cloneable handle into the hub. It publishes a `GatewayEvent` and
  persists messages through `Store`.
  `FakeDriver` (behind `#[cfg(any(test, feature = "fake"))]`, with feature `fake` turned
  on for `tests/`) emits a scripted `Qr`, then `State(Connected)`, and echoes sends as
  `Message`.
- `hub.rs`: `Hub { per_instance: Mutex<HashMap<InstanceId, Instance { log: EventLog,
  tx: tokio::sync::broadcast::Sender<EventEnvelope> }>> }` with `publish` and `subscribe(after)`.
- `auth.rs`: an axum extractor `Authed { instance: InstanceId, key_id: KeyId }`. It
  parses `Authorization: Bearer`, runs `ConnectKey::parse`, hashes, looks up in the
  store, and checks `AuthLimiter` for the client IP on failure
  (`ConnectInfo<SocketAddr>`, or `X-Forwarded-For` only when `trust_proxy = true`).
- `routes.rs` (axum `Router`, `/v1`, constants shared with `rovsti_wa_proto::api::routes`):

  | Method + path | Auth | Body → Result |
  | --- | --- | --- |
  | `GET /healthz` | – | `ok` |
  | `POST /v1/instances` | – | `CreateInstance` → `CreatedInstance` (409 `LimitReached` above `max_instances`); starts the driver with `link` |
  | `GET /v1/instance` | key | `InstanceStatus` |
  | `POST /v1/instance/link` | key | `LinkRequest` → 202 (relink after `LoggedOut`) |
  | `DELETE /v1/instance` | key | logout + delete files |
  | `GET/POST /v1/keys`, `DELETE /v1/keys/{key_id}` | key | `Vec<KeyInfo>` / `MintKey`→`IssuedKey` / 204, then `KeyRevoked` to sockets using it |
  | `PUT /v1/push` | key | `PushRegistration` → 204 |
  | `GET /v1/events?after=` | key | WebSocket (AD-WA4) |
  | `GET /v1/chats`, `GET /v1/chats/{jid}/messages?before=&limit=` | key | history from the store |
  | `POST /v1/messages/text` | key | `SendText` → `Sent` |

  Steps 4–6 add the rest. `main.rs` holds `#[tokio::main]`, `env_logger`,
  `Config::load`, `Store::open`, `serve(listener, AppState)`, and restarts every stored
  instance's driver at boot. `lib.rs` exports
  `pub async fn serve(listener: tokio::net::TcpListener, state: AppState)` and
  `AppState::new(config, store, driver: Arc<dyn WaDriver>)`.
- Key generation: `getrandom::fill(&mut [u8; 32])` → `ConnectKey::from_bytes`.

**Tests:**
- Unit: store migrations on a temp file, key lookup via the hash only (assert that the
  DB file does not contain the plaintext key), revoke, the instance cap, config
  parsing and env override.
- `tests/api.rs`: bind `127.0.0.1:0` with `FakeDriver` and use `rovsti_wa_client::Gateway`.
  It covers create instance → receive `Qr`, then `State(Connected)` over `subscribe`;
  `mint_key`, then a second client with that key sees the same status; revoke → 401
  plus a `KeyRevoked` event; resume with `after` replays only newer events; send_text
  echo.

**Acceptance:** checks green, and `cargo run -p wa_gateway -- --help` prints the
usage.

## Step 4: real whatsapp-rust driver (link, reconnect, text, history, receipts, presence)

Move the session code server-side.

**Files:** `services/wa_gateway/src/wa/{mod.rs,session.rs,convert.rs,history.rs}`.
`session.rs` starts from `crates/rovsti_whatsapp/src/session.rs`; leave the old crate
in place until step 7, but stop adding to it. `whatsapp-rust.workspace = true` goes into
the gateway; no voip feature yet.

- `WaRuntime` implements `WaDriver`. It holds `Mutex<HashMap<InstanceId, Running {
  client: Arc<Client>, handle: BotHandle }>>`.
  `start()` does the following:
  - opens `SqliteStore::new(<data>/instances/<id>/wa.db)`;
  - registers `.on_qr_code` → `GatewayEvent::Qr` and `.on_pair_code` → `PairCode`;
  - registers `.on_connected` → `State(Connected)` and stores `own_jid`/`push_name`;
  - registers `.on_logged_out` → `State(LoggedOut)`, then stops and deletes `wa.db`;
  - registers `.on_message` → `convert::message(&MessageContext) -> Option<WaMessage>`,
    then `EventSink::message` (persist, then publish);
  - registers `.on_event_for(&[EventKind::Receipt, EventKind::Presence,
    EventKind::ChatPresence, EventKind::HistorySync, EventKind::Disconnected,
    EventKind::StreamReplaced, EventKind::TemporaryBan, EventKind::ConnectFailure], ..)`
    → the matching `GatewayEvent`s;
  - uses `.with_pair_code(PairCodeOptions { phone_number, ..Default::default() })` when
    `LinkRequest::PhoneNumber`;
  - finishes with `build().await`, then `bot.spawn()`.
- Reconnect at boot: for each stored instance whose state is not `LoggedOut`, call
  `start(id, None, sink)`. An existing `wa.db` re-links silently, the same rule as the
  current `WhatsAppScreen::new`.
- `history.rs`: for `Event::HistorySync(lazy)`, iterate `lazy.stream()` (`HistorySyncStream`
  with `next_conversation()`). Map each conversation's messages through
  `convert::web_message_info` and batch-insert them in one transaction in
  `spawn_blocking`. Emit `HistorySynced { chats, progress: lazy.progress() }` and not
  one event per message.
- Driver ops: `send_text` uses `client.send_text(jid, text)` (with `quoted_id`, build
  `wa::Message` with `extended_text_message.context_info`), persists the outgoing
  message, and returns `Sent { message_id, timestamp_ms }`. Also `mark_read` →
  `client.mark_as_read`, `set_presence` → `client.presence().set_available()/set_unavailable()`,
  `chat_state` → `client.chatstate().send_composing/send_recording/send_paused`, and
  `subscribe_presence` → `client.presence().subscribe(jid)` the first time a client
  opens a chat (route `POST /v1/presence/subscribe {jid}`).
- Jid parsing goes through `wacore_binary::Jid`'s `FromStr`; reject malformed input
  as `BadRequest`.
- New routes: `POST /v1/read`, `POST /v1/presence`, `POST /v1/chatstate` and
  `POST /v1/presence/subscribe`. Client: the corresponding `Gateway` methods from
  step 2, if not already added.

**Tests:**
- Unit `convert.rs`: build `wa::Message` protos by hand (text, extended text, image
  with caption, reaction, unknown) → the `WaMessage` body. `is_from_me` maps to
  direction, and the group sender JID maps correctly. Port the existing
  `to_rovsti` expectations.
- `history.rs`: feed a hand-built `wa::HistorySync` (one conversation, two messages)
  through the mapping function, separate from the stream, and check the store rows.
- No network tests: linking needs a real phone.

**Acceptance:** checks green. The gateway binary links with whatsapp-rust. A manual
note in `docs/whatsapp-gateway.md` (new file) covers running it locally and linking by
QR.

## Step 5: media, reactions, groups and FCM push

**Files:** `services/wa_gateway/src/wa/{media.rs,groups.rs}`, `src/push/{mod.rs,fcm.rs,jwt.rs}`
and routes. Gateway deps: `ureq` (workspace, `json`), `ring 0.17` and
`rustls-pki-types` (`std`) for JWT signing, and `base64`.

- Media: `POST /v1/messages/media?chat=&kind=&mime=&caption=` takes the raw body,
  limited to 64 MB with `axum::extract::DefaultBodyLimit::max`. It calls
  `client.upload(bytes, MediaType::from(kind), UploadOptions::default())`, builds the
  `wa::message::{Image,Video,Audio,Document}Message` from the `UploadResponse` fields
  (url, direct_path, media_key, file_enc_sha256, file_sha256, file_length,
  media_key_timestamp, streaming_sidecar), and sends it with `client.send_message(jid, msg)`.
  `GET /v1/media/{message_id}` loads `raw_proto` from the store, picks the media
  sub-message, runs `client.download(&sub)`, and streams the bytes with
  `Content-Type: mime`. It caches up to 256 MB under `<data>/instances/<id>/media/`
  with LRU by mtime.
- Reactions: `POST /v1/reactions` builds `wa::MessageKey { remote_jid: chat, id,
  from_me, participant: sender }` and calls `client.send_reaction(chat, key, emoji)`.
  Incoming reaction messages update the `reactions` table and emit `MessageUpdate`.
- Groups: `GET /v1/groups` → `client.groups().get_participating()` mapped to
  `GroupInfo`, and `GET /v1/groups/{jid}` → `get_metadata`. `Event::GroupUpdate`
  emits `GroupChanged`. Group names fill `chats.name`.
- Push (AD-WA6):
  - `jwt.rs`: a pure `fn assertion(sa: &ServiceAccount, now_s: u64) -> Result<String>`
    builds the RS256 JWT: header `{"alg":"RS256","typ":"JWT"}`, claims `iss`, `scope =
    https://www.googleapis.com/auth/firebase.messaging`, `aud = token_uri`, `iat`, `exp
    = iat + 3600`. It signs with `ring::signature::RsaKeyPair::from_pkcs8(PrivatePkcs8KeyDer::from_pem_slice(pem))`
    and `RSA_PKCS1_SHA256`.
  - `fcm.rs`: `FcmSender` caches the access token from `POST token_uri`
    (`grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer`) until 60 s before
    expiry. `send(token, data: BTreeMap<String,String>, high_priority)` posts
    `https://fcm.googleapis.com/v1/projects/{project}/messages:send` with
    `{"message":{"token":..,"data":{..},"android":{"priority":"HIGH","ttl":"30s"}}}`.
    The ureq calls run in `spawn_blocking`. On a 404/`UNREGISTERED` response it
    deletes the token.
  - `push/mod.rs`: `trait Pusher { fn notify(&self, instance, PushKind) }`, with
    `NoopPusher` when FCM is not configured and `FcmPusher` otherwise.
    Incoming messages from others call `PushKind::Message { chat, count }`, coalesced
    per chat for 3 s by a pure `Coalescer` in `push/mod.rs`. Step 6 adds call pushes.
  - Route `PUT /v1/push` already exists (step 3); it now feeds the pusher.

**Tests:**
- `jwt.rs`: sign with a test RSA key, generated once and committed under
  `services/wa_gateway/tests/data/test-sa.json` (marked test-only), and verify with
  `ring::signature::UnparsedPublicKey`. Check the claims JSON.
- The FCM request body builder is pure and pinned as JSON.
- `Coalescer` windowing.
- Media: the `UploadResponse` → `ImageMessage` mapping, and choosing the
  `Downloadable` for each stored proto kind.
- `MessageKey` construction for reactions in DMs and groups.
- API test (FakeDriver grows `send_media`/`react`): a media round-trip and the body
  limit returning 413.

**Acceptance:** checks green, and with FCM unconfigured the gateway logs "push
disabled" and runs.

## Step 6: voice calls (server bridge + client audio)

**Files:**
- `services/wa_gateway/Cargo.toml`: `whatsapp-rust = { workspace = true, features = ["voip-mlow"] }`.
- `src/wa/calls.rs`: routes `POST /v1/calls`, `POST /v1/calls/{id}/{accept|reject|end}`,
  `POST /v1/calls/{id}/mute`, and the WebSocket `GET /v1/calls/{id}/audio`.

- `CallTable` (per instance): `HashMap<CallId, CallEntry { incoming: Option<IncomingCall>,
  machine: CallMachine, handle: Option<CallHandle>, mic_tx: async_channel::Sender<Vec<i16>>,
  mic_rx, spk_tx, spk_rx: async_channel::Receiver<Vec<i16>> }>`.
  The channels are created when the call is registered and are bounded at 8 frames, so
  a frame is dropped when a side lags.
- Incoming: `on_event_for(&[EventKind::IncomingCall, EventKind::MissedCall,
  EventKind::CallEndedElsewhere], ..)` handles them as follows:
  - `CallAction::Offer` stores the `IncomingCall` clone, runs `machine = Ringing`,
    publishes `GatewayEvent::Call(CallUpdate)` and calls
    `pusher.notify(PushKind::Call { call_id, from, name })` with high priority and no
    coalescing.
  - A terminate, reject or missed call goes to `CallMachine::apply`, then
    `Call(phase = Ended(..))`.
- Accept runs `client.voip().accept(&incoming).audio(mic_rx.clone(), spk_tx.clone()).start().await`
  → `CallHandle`. It spawns a task forwarding `handle.events()` (`CallEvent::RelayAllocated`
  → `MediaUp` → `Active`; `RelayAllocateFailed`/`RelayAllocateTimedOut`/`RelayReconnectTimedOut`
  → `Failed`) and a task on `handle.wait_ended()` → `Ended`.
- Reject uses `client.voip().reject(&incoming)`. End uses `handle.hangup()`, or for an
  unanswered outgoing call, `client.voip().terminate(call_id, &peer, &creator)`. Mute
  uses `handle.set_muted(m)`. Outgoing calls use `client.voip().call(&peer).audio(..).start()`
  → `CallStarted { call_id: handle.call_id() }`.
- Audio WebSocket: authenticate as usual and accept at most one socket per call. A
  binary frame goes through `decode_frame` → `mic_tx.try_send`, and `spk_rx.recv()` →
  `encode_frame` → a binary message. The socket closes when the call ends. If no audio
  socket is attached, the mic channel stays empty; whatsapp-rust keeps the call alive
  because a closed or idle source does not end it.
- `CallTable` timeout: ringing calls end as `Missed` after 60 s (pure, from
  `CallMachine` + `Timeout`).
- Client: add `Gateway::{place_call, accept_call, reject_call, end_call, set_muted}`
  if not yet present, and `call_audio()` from step 2 now has a server to talk to.

**Tests:**
- `CallTable` with a fake handle trait (`trait LiveCall { fn hangup, fn set_muted }`
  wrapping `CallHandle`, so it can be tested without whatsapp-rust). Check the
  ringing → accept → active → end paths, reject, missed timeout, and that a second
  audio socket gets 409.
- API test with FakeDriver calls: the client `call_audio()` sends 3 frames, the fake
  echoes them back, and the test asserts byte equality and that wrong-size frames are
  dropped.

**Acceptance:** checks green (the first build with voip-mlow takes about 3 min).
`cargo tree -p rovsti_wa_client` still has no whatsapp-rust and no ring.

## Step 7: shared link UI (`rovsti_wa_ui`) in all four apps; Messages migrated; `rovsti_whatsapp` removed

**Files:**
- `crates/rovsti_ui/src/storage.rs`: add generic `load_json<T: DeserializeOwned>(dir,
  name) -> Option<T>` and `save_json<T: Serialize>(dir, name, &T) -> io::Result<()>`.
  These are atomic tmp + `sync_all` + rename, factored out of `save_prefs`, which now
  calls `save_json`.
- `crates/rovsti_ui/src/field.rs`: a public `TextField`, a generalised copy of
  `apps/contacts/src/field.rs` with `set()`, `text()`, `clear()`, `backspace()`,
  `focus_handle()`, the `EntityInputHandler` impl, and an `input_mode` for URL or phone
  entry. Copy it as it is; check the gpui method names against `d6c8412`
  (`EntityInputHandler`, `TextInputMode`, `TextInputAction` are already used there).
  Contacts can switch to it later; this step leaves the app copies alone.
- `crates/rovsti_wa_ui/{Cargo.toml,src/lib.rs,src/qr.rs,src/model.rs,src/view.rs}`
  depends on gpui, rovsti_ui, rovsti_wa_proto, rovsti_wa_client, qrcode, image,
  futures and log.
  - `qr.rs`: moved verbatim from `crates/rovsti_whatsapp/src/qr.rs` (`qr_png`, plus its
    test).
  - `model.rs` (pure, unit-tested state machine, no GPUI):
    ```rust
    pub struct StoredLink { pub gateway_url: String, pub instance_id: String, pub key: String }
    pub const LINK_FILE: &str = "whatsapp-link.json";
    pub enum Screen { Choose, EnterUrl, LinkNew { method: LinkMethodChoice }, ShowQr { payload: String }, ShowCode { code: String },
                      PasteKey, Connecting, Connected { status: InstanceStatus }, ShareKey { key: String }, Failed { message: String, back: Box<Screen> } }
    pub enum Input { ChooseLinkNew, ChooseConnectExisting, UrlEntered(String), PhoneEntered(String), KeyPasted(String),
                     Created(CreatedInstance), Event(GatewayEvent), StatusOk(InstanceStatus), Unauthorized, Error(String), ShowKey(IssuedKey), Unlink }
    pub struct LinkModel { pub screen: Screen, pub link: Option<StoredLink> }
    impl LinkModel { pub fn apply(&mut self, input: Input) -> Vec<Effect> }
    pub enum Effect { CreateInstance(CreateInstance), Verify(StoredLink), Save(StoredLink), Forget, Subscribe(StoredLink), MintKey(String), CopyToClipboard(String) }
    ```
    There are **exactly two first-screen options**: "Link WhatsApp for the first
    time" and "Connect to an existing instance". The gateway URL row is above them
    (a default constant, editable). Connect-existing parses the pasted text with
    `ConnectKey::parse`, then `Verify` (`GET /v1/instance`), then `Save`. To find the
    instance_id it reads `status.instance_id`. When connected, the screen shows status,
    "Show connect key" (`MintKey(label = "<app> share")`, then a copyable `ShareKey`
    with a Copy button) and "Unlink this app" (`Forget`; it does not delete the
    instance).
  - `view.rs`: `pub struct WhatsAppSettings` is a GPUI entity. `pub fn new(app: AppId,
    data_dir_name: &'static str, cx) -> Self` loads `StoredLink` via `data_dir` +
    `load_json`. It runs `Effect`s on `cx.background_executor()` with `Gateway`, keeps
    an `EventStream` while it is visible or connected, renders the QR with `qr_png` +
    `Image::from_bytes(ImageFormat::Png, ..)`, and shares the QR as today's Messages
    `share_qr` does (moved here). Paste uses `cx.read_from_clipboard()` and copy uses
    `cx.write_to_clipboard`. Public API:
    `pub fn link(&self) -> Option<&StoredLink>`, `pub fn gateway(&self) -> Option<Gateway>`,
    `pub fn take_events(&mut self) -> Vec<GatewayEvent>`; the apps observe the entity
    (Messages does today).
    Components: `rovsti_ui::components::{section, glass_pill, list_row}`, following the
    liquid-glass-gpui skill. Read its SKILL.md first.
- Apps:
  - Messages: delete `apps/messages/src/whatsapp.rs`. The "WhatsApp" tab hosts
    `Entity<WhatsAppSettings>` (AppId::Messages). `main.rs` observes it, converts
    `GatewayEvent::Message` through `rovsti_wa_proto::to_rovsti`, and, once connected,
    loads `Gateway::chats()` in the background to fill `threads`. Remove
    `rovsti_whatsapp` from `apps/messages/Cargo.toml` and add `rovsti_wa_ui` +
    `rovsti_wa_client` + `rovsti_wa_proto`. Update the module doc ("WhatsApp runs on
    the Rovsti gateway").
  - Contacts: add a "WhatsApp" section row in `settings_tab()` that opens the widget
    as a pushed page or sheet (`rovsti_ui::sheet`).
  - Stops: replace the Settings `empty_state` with a section containing the widget.
  - Phone: add `Sub::WhatsApp` (prefix `"set-wa-"`, title "WhatsApp") and a
    `RowId::WhatsApp` settings row. `settings_body` for `Sub::WhatsApp` renders the
    entity. Check that the raw-touch router passes clicks through to the child entity
    (follow how `Sub::Replies` hosts `SearchField`).
- Delete `crates/rovsti_whatsapp`, remove its workspace dep line, and update README's
  table and Layout.

**Tests:**
- `rovsti_wa_ui::model`: both flows end to end on inputs (create → Qr → Event(State
  Connected) → Save; paste bad key → Failed with the right message; paste good key →
  Verify → Unauthorized → Failed("This key was revoked or is wrong"); Connected →
  ShowKey → CopyToClipboard; Unlink → Forget → Choose), and pair-code flow with a
  number missing its country code.
- `StoredLink` json round-trip.
- `rovsti_ui::storage::save_json/load_json` round-trip and corrupt-file → `None`.
- The `qr_png` test moves with the code.
- If the Phone harness has gpui test-support (`apps/phone` dev-deps), add one test
  that `Sub::WhatsApp` has a unique prefix in the existing prefix-uniqueness test, if
  there is one.

**Acceptance:** checks green; `git grep rovsti_whatsapp` finds only docs and history;
every app builds on desktop and shows the two options.

## Step 8: Android wake and call audio, Phone WhatsApp call UI, docs

**Files:**
- `crates/rovsti_android/src/wa.rs`, added to `lib.rs`. It has desktop
  `Error::Unsupported` and a `desktop_is_unsupported` test like `documents.rs`:
  - `pub fn push_token() -> Result<String>` calls `call_string("waPushToken")`; an empty
    string means FCM is not packaged.
  - `pub fn call_audio_start() -> Result<bool>` and `pub fn call_audio_stop() -> Result<()>`
    (via `call_bool`).
  - `pub fn call_audio_read() -> Result<Option<Vec<i16>>>` calls
    `call_string("waMicFrame")` and decodes with `frame_from_base64`; `""` means no
    frame yet.
  - `pub fn call_audio_play(frame: &[i16]) -> Result<()>` calls
    `call_bool("waPlayFrame", &[Arg::Str(&frame_to_base64(frame))])`.
  - Add the dependency `rovsti_wa_proto` (pure, which keeps `rovsti_android`'s android
    check NDK-free).
- Kotlin, `crates/rovsti_android/kotlin/dev/rovsti/bridge/`:
  - `WaCallAudio.kt`: `AudioRecord` with `VOICE_COMMUNICATION`, 16 kHz mono PCM16, and
    `AudioTrack` with `USAGE_VOICE_COMMUNICATION`. It keeps a mic queue of base64 960-sample
    frames, sets `AudioManager.MODE_IN_COMMUNICATION`, and needs `RECORD_AUDIO`.
  - `WaPush.kt`: reads the token via reflection on `com.google.firebase.messaging.FirebaseMessaging`
    and returns `""` when it is absent, so hosts without Firebase still compile. It
    also has `onMessage(data: Map<String,String>)` →
    `RovstiBridge.nativeOnEvent("wa_push", json)`.
  - Add the bridge statics to `RovstiBridge.kt`. Document in `docs/android.md` the
    optional `FirebaseMessagingService` subclass that a host adds when
    `google-services.json` is present, and that it shows a `CallStyle.forIncomingCall`
    notification for `kind=call`, as `CallRegistry` does.
- Pure call-screen logic, `rovsti_core::wa_call` (new module):
  `WaCallView { peer_label, phase: rovsti_wa_proto::call::CallPhase, muted, elapsed_s }`
  and `fn from_update(&CallUpdate, now_ms) -> WaCallView`. Tests cover the label
  fallbacks (name → number → "WhatsApp call").
  `rovsti_core` must not depend on rovsti_wa_proto in the other direction: put the
  module in `rovsti_wa_proto::call` if a dependency cycle appears, since proto
  already depends on core.
- Phone: `apps/phone/src/wa_call.rs`. When `WhatsAppSettings` is connected, Phone keeps
  an `EventStream`. On `GatewayEvent::Call` with phase `Ringing` it shows a glass
  incoming card reusing `incoming.rs` styling with Answer and Decline. Answer runs
  `accept_call`, then `call_audio()`, then `rovsti_android::wa::call_audio_start()`.
  Two background tasks pump `call_audio_read()` → `CallAudio.mic` and
  `CallAudio.speaker` → `call_audio_play()`. On desktop `Unsupported` means no audio
  and the UI still works. Mute uses `set_muted`; End uses `end_call` +
  `call_audio_stop()`. On connect, Phone registers `push_token()` via
  `Gateway::register_push(AppId::Phone)` when it is non-empty; Messages does the same
  with `AppId::Messages`. Add `android.permission.RECORD_AUDIO` to Phone's
  `gpuiforge.json`.
- Docs:
  - `docs/architecture.md`: the layers diagram adds `services/wa_gateway ⇄ rovsti_wa_client`,
    `rovsti_wa_ui` and `rovsti_wa_proto`, and drops `rovsti_whatsapp`. Add a new
    "WhatsApp gateway" section with instances, connect keys (AD-WA2), transport (AD-WA3),
    events (AD-WA4), call audio (AD-WA5), push (AD-WA6) and storage (AD-WA7). The
    Storage bullet becomes "Nothing leaves the device except WhatsApp traffic to the
    user's own gateway". The Messages row drops "foreground dataSync service keeping the
    WhatsApp socket alive" in favour of FCM wake.
  - `README.md`: the intro becomes "everything runs on the device except WhatsApp,
    which runs on your own gateway". Add the Layout entries `services/wa_gateway`,
    `rovsti_wa_proto`, `rovsti_wa_client` and `rovsti_wa_ui`, and a
    "Run the gateway" quick start.
  - `docs/whatsapp-notes.md`: mark the open questions as resolved and link to the
    architecture section.
  - `docs/roadmap.md`: tick the done items and add the gateway items.
  - `docs/whatsapp-gateway.md`: config, deploy behind Caddy/nginx TLS, FCM setup, and
    the ToS warning.

**Tests:**
- `rovsti_android::wa` desktop-unsupported test.
- `WaCallView` mapping.
- If the pump loop is split into a pure `fn pump_step(mic: Option<Vec<i16>>, ..)`
  helper, add a Rechunker test there too.

**Acceptance:** all checks green, including both android checks. On a device
(owner, later), the flow is: link in Messages → copy the key → paste it in Phone →
an incoming WhatsApp call rings Phone through FCM, and answering gives two-way audio.

---

## Risks and notes

- whatsapp-rust use may break WhatsApp's ToS, and a server-side session is more visible
  to enforcement. The UI warning text stays: the shared widget's intro shows it
  before "Link".
- graviola requires the ARMv8 crypto extensions. If a non-crypto arm64 device ever
  matters, add a `tls-ring` feature to `rovsti_wa_client`; it needs the NDK to check.
- The `voip-mlow` codec negotiates MLOW/PCM. `AcceptCall::start` returns
  `AudioFormatNotOffered(rate)` if the caller's offer does not carry the needed rate.
  Surface it as `Ended(Failed("unsupported codec"))`. libopus (`voip-libopus`) is a
  later option for the gateway only, since it is a server build.
- One `wa.db` per instance on the server, and never two Bots per instance: the
  `WaRuntime` map enforces it. Apps no longer hold whatsapp-rust sessions, so the
  four-companion-slot problem in `docs/whatsapp-notes.md` goes away (one slot per
  instance).
- Step sizes: steps 4 and 6 are the heaviest. If a session runs long, split step 4's
  history sync off into its own commit; it does not change the plan's ordering.
