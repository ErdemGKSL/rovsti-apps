# Plan: Rovsti Messages, finish the open roadmap items

- Base: `9223976a49623ccafce1547428912a4ddea9f5ff` ("WIP: carry-over before sync")
- Unit: Rovsti Messages. Architect plan, no source changed.
- Checks for every step (all must stay green):
  `cargo fmt --all && cargo clippy --workspace --all-targets -- -D warnings && cargo test --workspace && cargo check -p rovsti_android --target aarch64-linux-android && cargo check -p rovsti_wa_client --target aarch64-linux-android`
- Nothing runs on a device here. Kotlin is written but not compiled by the checks; pure
  Kotlin helpers get a JVM test next to the existing ones, and every Kotlin change keeps the
  "strings, ints and bools only" JNI seam (AD-5, AD-8).

## What the code already has (source of truth)

| Roadmap item | Code at the base |
|---|---|
| WhatsApp through the gateway (link, history, text, media, reactions, groups, presence) | Done. `services/wa_gateway`, `rovsti_wa_client::Gateway::{chats, messages, send_text, send_media, media, react, mark_read, chat_state, groups, ...}`, `rovsti_wa_proto::timeline::Timeline` |
| WhatsApp conversation view | Done: `apps/messages/src/conversation.rs` (1126 lines; composer, media, reactions, group sender names, typing). `LOCAL_OFFSET_S = 0` is still hard-coded although `rovsti_android::providers::local_time_info` + `rovsti_core::recents::LocaleTime::parse` now exist |
| Shared WhatsApp settings (link for the first time / connect with key) | Done: `rovsti_wa_ui::WhatsAppSettings` in all four apps (Messages: tab 1). Key list and revoke exist. **No new work** |
| FCM wake | Done for the event stream (`WhatsAppSettings::forward_push`, `rovsti_wa_proto::push::Wake::Message { chat }`). The gateway sends `{"kind":"message","chat","count"}` and **no text** (AD-WA6) |
| SMS/MMS | **Missing.** `rovsti_core::thread::{ThreadId::Sms(i64), Message, Channel}` exist; no provider pull, no send, no receiver, no ejected Android host for Messages (`apps/messages` has no `platforms/`) |
| Unified inbox by number | **Partial.** `MessagesApp::threads: Vec<rovsti_core::Message>` holds one row per thread id; SMS rows are inert, nothing merges a person's SMS thread with their WhatsApp chat |
| Notifications with inline reply | **Missing** |
| OTP, spam, scheduled send | **Missing** |
| Encrypted backup / export | **Missing.** Phone's plain JSON prefs backup (`rovsti_core::settings::BackupFile`, `rovsti_android::documents`) is the pattern; `Documents.MAX_BYTES` is 256 KB, too small for messages |

Reusable pieces: `rovsti_core::PhoneNumber::{parse, matches, whatsapp_user}`,
`rovsti_core::search::ContactIndex::match_number`, `rovsti_core::wire::{Envelope, WireError,
DocumentOp, DocumentResult, PhoneAccounts}`, `rovsti_core::sim::SimInfo`,
`rovsti_android::{bridge, events, providers, documents, roles::Role::Sms}`,
`rovsti_ui::storage::{data_dir, save_json, load_json}`, `rovsti_wa_proto::{to_rovsti, jid_user}`.

External APIs, verified:

- whatsapp-rust 0.7 (`~/.cargo/registry/src/*/whatsapp-rust-0.7.0`): sending stays on the
  gateway's existing `WaDriver::send_text` -> `client.send_text(to, text)` /
  `client.send_message(to, message)` (`services/wa_gateway/src/wa/session.rs:474`). This unit
  needs **no new whatsapp-rust call**.
- gpui_android at `d6c8412`: `android_entry!` defines `JNI_OnLoad`, which only registers the
  host's native methods and stores the entry (`crates/gpui_android/src/gpui_android.rs:36`,
  `bridge.rs:817`); `main` runs when the activity starts. So `System.loadLibrary` from a
  BroadcastReceiver is cheap and does not start GPUI. Needs a device check (see step 5).
- Crypto for the backup is already in `Cargo.lock` and the offline registry, pure Rust,
  Android-safe: `aes-gcm 0.10.3` (`default-features = false, features = ["aes", "alloc"]`),
  `pbkdf2 0.12.2` (`features = ["hmac"]`), `sha2 0.10`.
- Android (Kotlin): `Telephony.Sms`, `Telephony.Mms`, `Telephony.Threads`,
  `Telephony.Sms.Intents.{SMS_DELIVER_ACTION, WAP_PUSH_DELIVER_ACTION, getMessagesFromIntent}`,
  `SmsManager.createForSubscriptionId(subId)` (API 31+), `divideMessage`,
  `sendMultipartTextMessage(dest, null, parts, sentIntents, deliveryIntents)`,
  `sendMultimediaMessage(ctx, contentUri, null, null, sentIntent)`,
  `downloadMultimediaMessage(ctx, locationUrl, contentUri, null, downloadedIntent)`,
  `SubscriptionManager.getActiveSubscriptionInfoList`, `Notification.MessagingStyle`,
  `RemoteInput`, `AlarmManager.setExactAndAllowWhileIdle` / `canScheduleExactAlarms`.
  Required role components: `SMS_DELIVER` receiver (`BROADCAST_SMS`), `WAP_PUSH_DELIVER`
  receiver (`BROADCAST_WAP_PUSH`, mime `application/vnd.wap.mms-message`),
  `RESPOND_VIA_MESSAGE` service (`SEND_RESPOND_VIA_MESSAGE`), `SENDTO` activity (`sms:`,
  `smsto:`, `mms:`, `mmsto:`).

## Decisions (AD-MSG)

- **AD-MSG1 Pure first.** SMS wire types, the unified inbox merge, the SMS timeline, the MMS
  PDU codec, OTP, spam rules, the schedule queue, the notification digest and the backup
  archive are `rovsti_core` modules with unit tests. GPUI and Kotlin only map.
- **AD-MSG2 SMS lives in the provider.** Rovsti Messages keeps no SMS database; the
  Telephony provider is the store. Rust pulls through `rovsti_android::sms` (JSON envelopes,
  off the UI thread) and re-pulls on `sms_changed`.
- **AD-MSG3 Unified inbox key.** One person = one `rovsti_core::inbox::PersonKey`: the contact
  id when `ContactIndex::match_number` finds one, else the canonical number. A WhatsApp chat
  joins by `PhoneNumber::parse("+" + jid_user(jid))`. Groups (SMS multi-recipient threads,
  `@g.us` chats) never merge.
- **AD-MSG4 Cold paths stay in Kotlin, logic stays in Rust.** SMS receive, SMS inline reply
  and scheduled SMS run in Kotlin receivers (the process may have no activity). They ask
  Rust for decisions through one headless JNI call, `RovstiBridge.nativeHeadless(kind, json)`,
  exported by the app with `rovsti_android::headless_entry!` (same pattern as
  `gpui_android::android_entry!`). If the library cannot be loaded, Kotlin falls back to a
  plain notification with no OTP chip and no spam filter.
- **AD-MSG5 WhatsApp notifications.** The FCM payload has no text (AD-WA6 stays). On a
  `kind=message` push, Kotlin calls `nativeHeadless("wa_digest", {dir, chat})`; Rust reads
  `whatsapp-link.json` and calls `Gateway::messages(chat, None, 5)`, so the notification
  shows real text. Inline reply calls `nativeHeadless("wa_reply", {dir, chat, text})` ->
  `Gateway::send_text`. While the UI is attached, the app posts notifications itself from
  the event stream and Kotlin skips its own.
- **AD-MSG6 Scheduled send.** SMS: local queue (`scheduled.json`, Rust) armed as one
  AlarmManager alarm per item; Kotlin stores the armed payload and sends from the alarm
  receiver with `SmsManager`, cold. Exact alarm when `canScheduleExactAlarms()`, else
  `setAndAllowWhileIdle` (the UI says "may be a few minutes late"). WhatsApp: the gateway
  owns it (always on): `POST /v1/scheduled`, a store table and one tokio sweeper.
- **AD-MSG7 Encrypted backup.** `RMB1` archive: magic, version, PBKDF2-HMAC-SHA256 (600 000
  iterations, 16-byte salt), AES-256-GCM (12-byte nonce, header as AAD) over a JSON body
  `{sms, mms_text, prefs, spam_rules, scheduled}`. The WhatsApp link key is **never** in a
  backup (bearer secret); WhatsApp history stays on the user's gateway. Randomness is passed
  in, so the module stays pure. Files move by path through SAF (`documents::export_file`),
  not through a JNI string.
- **AD-MSG8 Spam is on-device.** Rules: block list (system `BlockedNumberContract`, reused),
  user rules (sender pattern, keyword, "unknown senders with links"), and a fixed scorer.
  Never sent anywhere. Spam goes to a Spam folder, never deleted, never notified loudly.

## Steps

### Step 1. Pure: SMS wire, unified inbox, SMS timeline (`rovsti_core`)

Files: `crates/rovsti_core/src/sms.rs` (new), `inbox.rs` (new), `sms_timeline.rs` (new),
`lib.rs`; golden fixtures in `crates/rovsti_core/tests/sms_golden.rs`.

```rust
// sms.rs: Kotlin -> Rust payloads, all fields #[serde(default)] like wire.rs.
pub enum SmsBox { Inbox, Sent, Outbox, Failed, Queued, Draft }       // Telephony TYPE 1..6
pub enum DeliveryState { None, Pending, Sent, Delivered, Failed }     // STATUS + our events
pub struct SmsRow { pub id: i64, pub thread_id: i64, pub address: String, pub body: String,
    pub date_ms: i64, pub box_: SmsBox, pub read: bool, pub sub_id: Option<i32>,
    pub delivery: DeliveryState }
pub struct MmsPart { pub content_type: String, pub text: Option<String>, pub path: Option<String> }
pub struct MmsRow { pub id: i64, pub thread_id: i64, pub addresses: Vec<String>, pub from: Option<String>,
    pub date_ms: i64, pub outgoing: bool, pub read: bool, pub parts: Vec<MmsPart>, pub sub_id: Option<i32> }
pub struct SmsThreadRow { pub thread_id: i64, pub addresses: Vec<String>, pub snippet: String,
    pub date_ms: i64, pub unread: u32 }
pub struct SmsThreadsPayload { pub threads: Vec<SmsThreadRow> }
pub struct SmsMessagesPayload { pub sms: Vec<SmsRow>, pub mms: Vec<MmsRow>, pub has_older: bool }
pub struct SmsSim { pub sub_id: i32, pub slot_index: Option<u8>, pub label: String, pub color_argb: i32 }
pub struct SmsSimsPayload { pub sims: Vec<SmsSim>, pub default_sub_id: Option<i32> }
/// `sms_status` event: {local_id, uri, state, error_code}.
pub struct SmsStatus { pub local_id: u64, pub uri: Option<String>, pub state: DeliveryState, pub error_code: Option<i32> }
pub struct SmsSend { pub local_id: u64, pub to: Vec<String>, pub body: String, pub sub_id: Option<i32> }
impl SmsRow { pub fn to_message(&self) -> crate::Message }  // ThreadId::Sms(thread_id)
pub fn segments(body: &str) -> SegmentInfo;  // GSM-7 vs UCS-2, 160/153 and 70/67 per part
pub struct SegmentInfo { pub parts: u32, pub left_in_part: u32, pub unicode: bool }
```

```rust
// inbox.rs
pub enum PersonKey { Contact(i64), Number(PhoneNumber), Group(ThreadId) }
pub struct InboxThread { pub id: ThreadId, pub last: Message, pub unread: u32, pub title: Option<String>, pub is_group: bool }
pub struct InboxRow { pub key: PersonKey, pub title: String, pub threads: Vec<InboxThread>, // newest first
    pub last: Message, pub unread: u32, pub channels: Vec<Channel> }
pub fn person_for_number(raw: &str, contacts: &ContactIndex) -> PersonKey;
pub fn person_for_jid(jid: &str, contacts: &ContactIndex) -> PersonKey;  // @g.us -> Group
pub fn merge(sms: &[SmsThreadRow], wa: &[InboxThread], contacts: &ContactIndex) -> Vec<InboxRow>; // newest first
pub fn preferred_channel(row: &InboxRow) -> Channel;   // channel of the newest thread
```

```rust
// sms_timeline.rs: like rovsti_wa_proto::timeline::Timeline, for one SMS thread
pub struct SmsItem { pub key: SmsKey, pub outgoing: bool, pub body: String, pub parts: Vec<MmsPart>,
    pub at_ms: i64, pub state: DeliveryState, pub sub_id: Option<i32> }
pub enum SmsKey { Sms(i64), Mms(i64), Local(u64) }
pub struct SmsTimeline { .. }
impl SmsTimeline {
    pub fn new(thread_id: i64) -> Self;
    pub fn apply_page(&mut self, page: SmsMessagesPayload);   // dedupe by key, sort by at_ms
    pub fn push_pending(&mut self, local_id: u64, body: String, sub_id: Option<i32>, now_ms: i64);
    pub fn apply_status(&mut self, status: &SmsStatus) -> bool; // Pending->Sent->Delivered/Failed, never back
    pub fn retry(&mut self, local_id: u64) -> Option<SmsSend>;
    pub fn items(&self) -> &[SmsItem];
    pub fn oldest_ms(&self) -> Option<i64>;
}
```

Tests: envelope parse of each payload (golden JSON in the test file, including nulls and
unknown `SmsBox` -> `Inbox`); `segments` for ASCII 160/161, Turkish `ş` (UCS-2, 70/71), GSM
extension chars (`€` counts 2); merge: `+905321234567` SMS + `905321234567@s.whatsapp.net`
chat under one contact; `0532…` national SMS matches the `+90` chat; group chat and a
2-recipient SMS thread stay separate; ordering and unread sums; status never goes back from
Delivered to Sent; retry only for Failed.

Acceptance: `cargo test -p rovsti_core sms inbox sms_timeline` passes; all checks green.

### Step 2. Pure: MMS PDU codec and the encrypted backup archive (`rovsti_core`)

Files: `crates/rovsti_core/src/mms_pdu.rs` (new), `msg_backup.rs` (new), `lib.rs`,
`crates/rovsti_core/Cargo.toml` (add `aes-gcm = { version = "0.10.3", default-features =
false, features = ["aes", "alloc"] }`, `pbkdf2 = { version = "0.12.2", features = ["hmac"] }`,
`sha2.workspace = true`; add the first two to `[workspace.dependencies]`).

```rust
// mms_pdu.rs: OMA-MMS-ENC 1.3 subset, byte-level, no I/O.
pub struct NotificationInd { pub transaction_id: String, pub content_location: String,
    pub from: Option<String>, pub size: u64, pub expiry_s: Option<u64> }
pub fn decode_notification_ind(pdu: &[u8]) -> Result<NotificationInd, PduError>; // X-Mms-Message-Type 0x82
pub struct RetrievedMms { pub from: Option<String>, pub to: Vec<String>, pub date_s: Option<u64>,
    pub subject: Option<String>, pub parts: Vec<PduPart> }
pub struct PduPart { pub content_type: String, pub name: Option<String>, pub data: Vec<u8> }
pub fn decode_retrieve_conf(pdu: &[u8]) -> Result<RetrievedMms, PduError>;        // 0x84, multipart
pub fn encode_send_req(to: &[String], subject: Option<&str>, parts: &[PduPart], transaction_id: &str) -> Vec<u8>; // 0x80
pub fn encode_notify_resp(transaction_id: &str, retrieved: bool) -> Vec<u8>;      // 0x83
#[derive(thiserror::Error)] pub enum PduError { Truncated, WrongType(u8), BadHeader(u8), BadMultipart }
```
Kotlin hands PDUs as base64 strings (they are small; images are written to a file by Kotlin
before encoding is asked for, see step 5). Address encoding: `+90…/TYPE=PLMN`.

```rust
// msg_backup.rs
pub const MAGIC: &[u8; 4] = b"RMB1";
pub const ITERATIONS: u32 = 600_000;
pub struct BackupBody { pub created_ms: i64, pub app_version: String, pub sms: Vec<SmsRow>,
    pub mms: Vec<MmsRow /* text parts only */>, pub spam: SpamRules, pub scheduled: Vec<ScheduledSms> }
pub fn seal(body: &BackupBody, passphrase: &str, salt: [u8; 16], nonce: [u8; 12], iterations: u32) -> Vec<u8>;
pub fn open(bytes: &[u8], passphrase: &str) -> Result<BackupBody, BackupError>;
#[derive(thiserror::Error)] pub enum BackupError { NotABackup, NewerVersion(u8), WrongPassphrase, Corrupt }
pub fn passphrase_strength(p: &str) -> Strength;  // Weak (< 10 chars) refused by the UI
pub struct RestorePlan { pub insert: Vec<SmsRow>, pub skipped_duplicates: usize }
pub fn restore_plan(backup: &[SmsRow], existing: &[SmsRow]) -> RestorePlan; // dup = same address match, body, date_ms
```
`SpamRules` / `ScheduledSms` come from step 3; to keep step 2 independent, define
`BackupBody` with `serde_json::Value` slots `spam` and `scheduled` here and tighten the
types in step 3 (or do step 3 first: order 2 and 3 are interchangeable).

Tests: decode a captured notification-ind and retrieve-conf (hex vectors in the test, built
from the spec examples), round-trip `encode_send_req` through a small decoder of our own,
truncated input -> `Truncated`, wrong type -> `WrongType`; backup round-trip with a low
iteration count (1 000) in tests, wrong passphrase -> `WrongPassphrase`, a flipped header
byte -> `WrongPassphrase` (AAD), unknown version -> `NewerVersion`, `restore_plan` dedupe.

Acceptance: `cargo test -p rovsti_core mms_pdu msg_backup`; `cargo check -p rovsti_android
--target aarch64-linux-android` still passes (new crypto deps build for Android).

### Step 3. Pure: OTP, spam rules, schedule queue, notification digest (`rovsti_core`)

Files: `crates/rovsti_core/src/otp.rs`, `spam.rs`, `schedule.rs`, `notify.rs` (all new), `lib.rs`.

```rust
// otp.rs
pub struct Otp { pub code: String, pub span: (usize, usize) /* byte range in body */ }
pub fn detect(body: &str) -> Option<Otp>;
```
Rules: a 4 to 8 digit run (or `123-456` / `123 456`, returned without the separator) near a
keyword in English or Turkish (`code`, `kod`, `OTP`, `şifre`, `password`, `verification`,
`doğrulama`, `PIN`) within 40 chars, or the message's only digit run of 4..8 when it contains
`G-` style prefixes. Never a phone number, an amount (`1500 TL`, `$1500`), a date or a time.

```rust
// spam.rs
pub enum SpamRule { Sender { pattern: String /* digits or name, `*` wildcard */ },
    Keyword { text: String }, UnknownWithLink, ShortCodeOnly(bool) }
pub struct SpamRules { pub enabled: bool, pub rules: Vec<SpamRule>, pub allow: Vec<String> /* numbers */ }
pub enum Verdict { Ham, Spam(&'static str) /* reason for the UI */ }
pub fn classify(rules: &SpamRules, sender_raw: &str, body: &str, known_contact: bool, blocked: &BlockSet) -> Verdict;
pub fn add_rule(rules: &mut SpamRules, rule: SpamRule) -> Result<(), SpamRuleError>; // empty, dup, > 64 rules
```
Order: allow list and known contacts are always Ham; OTP messages (`otp::detect`) are Ham
unless the sender is blocked; blocked -> Spam("Blocked"); then user rules; then
UnknownWithLink (a URL via a small scanner, no regex dependency).

```rust
// schedule.rs
pub enum ScheduleTarget { Sms { to: Vec<String>, sub_id: Option<i32> }, WhatsApp { chat: String } }
pub struct ScheduledSms { pub id: u64, pub at_ms: i64, pub target: ScheduleTarget, pub body: String, pub created_ms: i64 }
pub struct ScheduleQueue { items: Vec<ScheduledSms> }
pub fn validate(at_ms: i64, now_ms: i64, body: &str) -> Result<(), ScheduleError>; // >= now+60s, <= now+365d, 1..=64 KiB
impl ScheduleQueue { pub fn add(&mut self, item: ScheduledSms) -> Result<(), ScheduleError>;   // cap 200
    pub fn cancel(&mut self, id: u64) -> Option<ScheduledSms>;
    pub fn reconcile(&mut self, fired: &[FiredSms]) -> Vec<ScheduledSms>; // drop fired, report failed
    pub fn due_soon(&self, now_ms: i64) -> impl Iterator<Item = &ScheduledSms>;
    pub fn to_json(&self) -> String; pub fn from_json(s: &str) -> Self /* corrupt -> empty */ }
pub struct FiredSms { pub id: u64, pub ok: bool, pub at_ms: i64 }
pub fn quick_times(now_ms: i64, tz_offset_s: i32) -> [(&'static str, i64); 3]; // "Tonight 20:00", "Tomorrow 08:00", "Monday 08:00"
```

```rust
// notify.rs: what a message notification shows (both channels, both paths)
pub struct Incoming { pub thread: ThreadId, pub sender_title: String, pub body: String, pub at_ms: i64, pub verdict_spam: bool }
pub struct NotificationSpec { pub tag: String /* "sms:<thread>" | "wa:<jid>" */, pub title: String,
    pub lines: Vec<(String, String, i64)> /* sender, text, at */, pub otp: Option<String>,
    pub reply: bool, pub silent: bool }
pub fn build(thread_title: &str, is_group: bool, recent: &[Incoming], otp: Option<&Otp>) -> Option<NotificationSpec>; // None for spam-only
pub fn headless_sms(json: &str) -> String;  // used by the headless entry: {sender, body, known} -> {spam, reason, otp}
```

Tests: OTP table (positive: "Your code is 482913", "Doğrulama kodunuz: 4829", "G-482913",
"123-456 is your code"; negative: "Call 05321234567", "1500 TL", "12:30", "2026-11-05");
spam ordering (allow beats rule, OTP survives keyword rule, blocked wins); rule validation;
schedule bounds, cap, reconcile; `quick_times` across midnight; `notify::build` caps lines at
6, groups prefix sender, spam -> `None`, OTP fills `otp`.

Acceptance: `cargo test -p rovsti_core otp spam schedule notify`; then tighten
`msg_backup::BackupBody` to the real `SpamRules` / `Vec<ScheduledSms>`.

### Step 4. Gateway: scheduled WhatsApp send (`rovsti_wa_proto`, `services/wa_gateway`, `rovsti_wa_client`)

Files: `crates/rovsti_wa_proto/src/api.rs`, `services/wa_gateway/src/store.rs`,
`services/wa_gateway/src/schedule.rs` (new), `lib.rs`, `routes.rs`, `main.rs`,
`crates/rovsti_wa_client/src/http.rs`, `services/wa_gateway/tests/api.rs`,
`docs/whatsapp-gateway.md` (API table).

```rust
// rovsti_wa_proto::api
pub mod routes { pub const SCHEDULED: &str = "/v1/scheduled"; pub const SCHEDULED_ONE: &str = "/v1/scheduled/{id}"; }
pub struct ScheduleText { pub chat: String, pub text: String, pub send_at_ms: i64 }
pub enum ScheduledState { Pending, Sent { message_id: String }, Failed { reason: String } }
pub struct ScheduledText { pub id: String, pub chat: String, pub text: String, pub send_at_ms: i64,
    pub created_ms: i64, pub state: ScheduledState }
```
Store: `MIGRATIONS` v2 `CREATE TABLE scheduled (id TEXT PRIMARY KEY, instance_id TEXT NOT
NULL REFERENCES instances(id) ON DELETE CASCADE, chat TEXT NOT NULL, text TEXT NOT NULL,
send_at_ms INTEGER NOT NULL, created_ms INTEGER NOT NULL, state_json TEXT NOT NULL);
CREATE INDEX scheduled_due ON scheduled(state_json, send_at_ms);` with `Store::{add_scheduled,
list_scheduled, cancel_scheduled, due_scheduled(now_ms, limit), finish_scheduled}`.

`schedule.rs`: `pub fn spawn_sweeper(state: AppState, every: Duration) -> JoinHandle<()>`;
each tick takes `due_scheduled(now, 32)` and calls `state.driver().send_text(instance,
SendText { chat, text, quoted_id: None })`; on success stores `Sent` and lets the normal
outgoing-message event flow happen; on `DriverError` stores `Failed` (no retry storm; one
retry after 60 s when the instance is not connected). Handlers: `POST /v1/scheduled`
(validate JID with `check_jid`, 1 B..64 KiB, `send_at_ms` in `now+30s..now+365d`, at most 200
pending per instance), `GET /v1/scheduled` (pending + finished of the last 7 days),
`DELETE /v1/scheduled/{id}` (only `Pending`, 404 otherwise). `main.rs` starts the sweeper
every 15 s. A `GatewayEvent::Scheduled(ScheduledText)` is published on every state change
(add the variant; an older client logs "undecodable event" and skips it,
`rovsti_wa_client/src/events.rs:254`, so no compatibility shim is needed).

Client: `Gateway::{schedule_text(&ScheduleText) -> Result<ScheduledText>, scheduled() ->
Result<Vec<ScheduledText>>, cancel_scheduled(id) -> Result<()>}`.

Tests: store migration v1 -> v2 keeps data (extend
`migration_sets_user_version_and_is_idempotent`), API tests with `FakeDriver` (add, list,
cancel, cancel twice -> 404, past time -> 400, sweeper sends once and marks Sent, driver error
-> Failed, deleting the instance cascades), client path tests.

Acceptance: `cargo test -p wa_gateway -p rovsti_wa_proto -p rovsti_wa_client`; client still
checks for `aarch64-linux-android`.

### Step 5. Android seam: SMS, notifications, scheduled SMS, documents by file, headless entry (`rovsti_android` + shared Kotlin + `rovsti_wa_ui::headless`)

Rust files: `crates/rovsti_android/src/sms.rs`, `notify.rs`, `headless.rs` (new),
`documents.rs`, `events.rs`, `lib.rs`; `crates/rovsti_wa_ui/src/headless.rs` (new);
`crates/rovsti_core/src/wire.rs` (`DocumentOp::{ExportMessages, ImportMessages}`,
`DocumentResult.path: Option<String>`).

```rust
// rovsti_android::sms (all blocking, off the UI thread, Unsupported on desktop)
pub fn threads(limit: usize) -> Result<String>;                         // pullSmsThreads -> Envelope<SmsThreadsPayload>
pub fn messages(thread_id: i64, before_ms: Option<i64>, limit: usize) -> Result<String>; // pullSmsMessages
pub fn sims() -> Result<String>;                                        // pullSmsSims -> Envelope<SmsSimsPayload>
pub fn send(req: &SmsSend) -> Result<bool>;   // smsSend(json); status comes back as `sms_status`
pub fn send_mms(local_id: u64, to: &[String], text: &str, image_path: Option<&str>, sub_id: Option<i32>) -> Result<bool>;
pub fn mark_read(thread_id: i64) -> Result<bool>;
pub fn delete_thread(thread_id: i64) -> Result<bool>;
pub fn insert_restored(json: &str) -> Result<String>;                   // restore; needs ROLE_SMS
pub fn arm_scheduled(item_json: &str) -> Result<bool>;   pub fn disarm_scheduled(id: u64) -> Result<bool>;
pub fn pull_fired() -> Result<String>;                                  // Vec<FiredSms>, then cleared
// rovsti_android::notify
pub fn show(spec_json: &str) -> Result<bool>;  pub fn cancel(tag: &str) -> Result<bool>;
pub fn copy_to_clipboard(text: &str, sensitive: bool) -> Result<bool>;  // EXTRA_IS_SENSITIVE for OTPs
// rovsti_android::documents
pub fn export_file(name: &str, mime: &str, path: &str) -> Result<bool>; // op export_messages
pub fn import_file(max_bytes: u64) -> Result<bool>;                     // op import_messages, answer has `path`
// rovsti_android::events::kind
SMS_CHANGED, SMS_STATUS, SMS_RECEIVED, MSG_REPLY /* {tag, text} while attached */, SCHEDULED_FIRED, OPEN_THREAD
```

`headless.rs`: `pub type Handler = fn(kind: &str, json: &str) -> String;` and
```rust
#[macro_export]
macro_rules! headless_entry { ($handler:path) => {
    #[cfg(target_os = "android")]
    #[unsafe(no_mangle)]
    pub extern "system" fn Java_dev_rovsti_bridge_RovstiBridge_nativeHeadless<'a>(
        mut env: $crate::jni::JNIEnv<'a>, _class: $crate::jni::objects::JClass<'a>,
        kind: $crate::jni::objects::JString<'a>, json: $crate::jni::objects::JString<'a>,
    ) -> $crate::jni::sys::jstring { $crate::headless::run(&mut env, kind, json, $handler) }
}; }
pub fn run(..) -> jstring   // android only: reads both strings, catch_unwind around the handler, "" on panic
```
Re-export `pub use jni;` under `cfg(target_os = "android")`. Pure dispatcher for tests:
`pub fn dispatch(kind: &str, json: &str, table: &[(&str, Handler)]) -> String`.

`rovsti_wa_ui::headless` (blocking, no GPUI): `pub fn wa_digest(json: &str) -> String`
(`{dir, chat}` -> load `StoredLink` from `dir/whatsapp-link.json`, `Gateway::messages(chat,
None, 5)`, `chats()` for the title, map through `notify::build`; `{}` on any error) and
`pub fn wa_reply(json: &str) -> String` (`{dir, chat, text}` -> `send_text`; `{"ok":bool}`).

Kotlin (`crates/rovsti_android/kotlin/dev/rovsti/bridge/`): `SmsQuery.kt` (provider pulls,
JSON via `WireJson`), `SmsSend.kt` (`createForSubscriptionId`, multipart, sent/delivery
`PendingIntent`s -> `sms_status` and provider `STATUS` update, writes to `Sms.Sent`/`Outbox`),
`MmsCodec.kt` (only file plumbing: write PDU to a cache file, call
`sendMultimediaMessage`/`downloadMultimediaMessage`; PDU bytes come from Rust via base64),
`MessageNotifications.kt` (channel `messages`, `MessagingStyle`, `RemoteInput` reply action
with `FLAG_MUTABLE`, "Copy code" and "Mark read" actions, tag from `NotificationSpec`),
`ReplyReceiver.kt` (`goAsync`; attached -> `msg_reply` event; cold SMS -> `SmsSend`; cold
WhatsApp -> `nativeHeadless("wa_reply")`, then re-posts the notification with the sent line),
`ScheduledSms.kt` (AlarmManager, `SharedPreferences` store of armed payloads, `fired` list),
`Headless.kt` (`ensureLoaded(libName)` with `System.loadLibrary`, `UnsatisfiedLinkError` ->
`null`), `RovstiBridge.kt` (`@JvmStatic external fun nativeHeadless(kind: String, json:
String): String` and the new static methods), `Documents.kt` (`exportFile`/`importFile`,
copy by stream to/from cache, 64 MB cap). JVM tests: `SmsJsonTest`, `NotificationSpecTest`,
`ScheduledSmsStoreTest` in `apps/phone/platforms/android/app/src/test/java/dev/rovsti/bridge/`
(the only Gradle project with a test source set today) or in the new Messages host (step 6).

Rust tests: every facade `Unsupported` on desktop (pattern of `providers.rs`); `dispatch`
unknown kind -> `""`; `wa_digest` with a missing link file -> `{}`; `wa_reply` against the
loopback gateway in `crates/rovsti_wa_client/tests/loopback.rs` style (a `FakeDriver`-backed
`wa_gateway` dev-dependency if acceptable, otherwise only the error paths).

Acceptance: all checks green; `cargo check -p rovsti_android --target aarch64-linux-android`
compiles `headless.rs` and the macro (add `headless_entry!(crate_test_handler)` in a
`#[cfg(test)]`-free doc example or an android-only test module so the expansion is checked).

### Step 6. Rovsti Messages Android host (`apps/messages/platforms/android`)

Eject the host the way Contacts did (`apps/contacts/platforms/android`, Kotlin bridge as a
Gradle source dir `crates/rovsti_android/kotlin`, no symlink). Files:

- `apps/messages/gpuiforge.json`: `"management": "manual"`, `"project-dir":
  "platforms/android"`, `"activity": "dev.rovsti.messages.MainActivity"`, `min_sdk 34`,
  `target_sdk 36`; permissions: keep the SMS/MMS set, add `SCHEDULE_EXACT_ALARM`,
  `RECEIVE_BOOT_COMPLETED` (re-arm alarms after reboot) and `INTERNET` (gateway); drop
  `FOREGROUND_SERVICE*` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (no foreground service,
  AD-WA6); `url-schemes` add `mms`, `mmsto`.
- `AndroidManifest.xml`: `SmsDeliverReceiver` (`SMS_DELIVER`, `BROADCAST_SMS`),
  `MmsWapPushReceiver` (`WAP_PUSH_DELIVER`, `BROADCAST_WAP_PUSH`, mime
  `application/vnd.wap.mms-message`), `HeadlessSmsSendService` (`RESPOND_VIA_MESSAGE`,
  `SEND_RESPOND_VIA_MESSAGE`, schemes sms/smsto/mms/mmsto), `MainActivity` with `SENDTO`
  and `SEND` (text/plain, image/*) filters, `ReplyReceiver`, `ScheduledSmsReceiver`,
  `BootReceiver`, firebase flavor `RovstiMessagingService` (copy Phone's
  `src/firebase/...`, calling `WaPush.onMessage` and, for `kind=message` while not attached,
  `MessageNotifications.fromWaPush` -> `nativeHeadless("wa_digest")`).
- `SmsDeliverReceiver.kt`: writes the message to `Telephony.Sms.Inbox` (the default app
  must), asks `nativeHeadless("sms_classify", {sender, body, known})` (known = contacts
  `PhoneLookup`), posts through `MessageNotifications` unless spam, emits `sms_received` +
  `sms_changed`. `MmsWapPushReceiver.kt`: hands the PDU to `nativeHeadless("mms_notification")`
  (Rust `decode_notification_ind`), downloads with `downloadMultimediaMessage`, then
  `nativeHeadless("mms_retrieved")` decodes parts, Kotlin persists them to `Telephony.Mms`
  and posts the notification.
- `MainActivity.kt`: `nativeLibraryName() = "rovsti_messages"`, `RovstiBridge.attachNative()`,
  forwards `SENDTO`/`SEND` intents and notification taps as a one-shot `open_thread` /
  `ui_request` through the `Mailbox`, `onActivityResult` -> `RovstiBridge.handleActivityResult`.
- Rust: `apps/messages/src/headless.rs` (new) with `fn handle(kind: &str, json: &str) ->
  String` dispatching `sms_classify` (spam + OTP from `rovsti_core`, rules read from
  `<dir>/messages-prefs.json`), `mms_notification`, `mms_retrieved`, `mms_send_req`,
  `wa_digest`, `wa_reply` (from `rovsti_wa_ui::headless`); `main.rs`:
  `rovsti_android::headless_entry!(headless::handle);`. Unit tests for `handle` routing
  and for each kind's JSON in/out (desktop, pure).
- `docs/android.md`: the Messages host section (role components, FCM flavor, exact alarm
  fallback, the headless call and its fallback). `docs/architecture.md`: Messages row and
  AD-MSG4/5.

Acceptance: all checks green (Rust); manifest and Kotlin reviewed against the Contacts host;
`docs/phone-build.md`-style build notes for Messages exist. Device check list added to a new
`docs/messages-acceptance.md` (role grant on HyperOS, SMS_DELIVER cold, inline reply cold,
FCM digest cold, exact alarm denied path, `System.loadLibrary` from a receiver).

### Step 7. Messages app: SMS backend, unified inbox, SMS conversation view

Files: `apps/messages/src/backend.rs` (new), `backend/preview.rs` (new, desktop sample
threads), `inbox.rs` (new, replaces `MessagesApp::chats`), `sms_conversation.rs` (new),
`conversation.rs` (local time), `main.rs`, `apps/messages/Cargo.toml` (add `serde_json`).

- `Backend` entity: installs the event sink (`rovsti_android::events::install_sink_owned`),
  pulls `sms::threads(500)`, `sms::sims()`, `providers::contacts()` -> `ContactIndex`,
  `providers::local_time_info()`, `roles::is_held(Role::Sms)` on the background executor;
  re-pulls on `sms_changed`, `contacts_changed`, `permissions_changed`; applies `sms_status`
  to the open `SmsTimeline`. Desktop uses `preview.rs` (no `Unsupported` errors on screen).
- Inbox: `rovsti_core::inbox::merge(sms_threads, wa_threads, contacts)`; one row per person
  with SMS / WhatsApp badges (both when merged), unread count, OTP chip on the snippet
  (`otp::detect`, tap -> `notify::copy_to_clipboard(code, true)`), spam rows hidden into a
  "Spam" filter row at the bottom (`spam::classify` on the snippet). Tapping a merged row
  opens the newest thread; a channel switch in the conversation header jumps to the other
  thread of the same person.
- Read-only banner while `ROLE_SMS` is not held: "Set as default SMS app" ->
  `roles::request(Role::Sms)`, falling back to `xiaomi` default-apps page (existing pattern).
- `SmsConversation` entity: `SmsTimeline` pages of 50 via `sms::messages`, glass composer
  (reuse `rovsti_ui::field::TextField` and the WhatsApp composer layout), segment counter
  from `sms::segments`, SIM chip when `SmsSimsPayload.sims.len() > 1` (default from
  `default_sub_id`, choice remembered per thread in `messages-prefs.json`), delivery ticks
  (Pending clock, Sent one tick, Delivered two, Failed red with Retry), MMS image parts via
  `img()` from the cached path, an attach button that sends MMS through
  `sms::send_mms`, `sms::mark_read` on open.
- `conversation.rs`: replace `LOCAL_OFFSET_S` with the `LocaleTime` from the backend.
- New conversation: "+" in the title row, number field or contact search
  (`ContactIndex::search`), opens the merged person view.

Tests (pure, in-app `#[cfg(test)]` where logic is thin): row title/badge mapping,
SIM chip visibility, delivery tick mapping. Heavy logic must already be in steps 1 to 3.

Acceptance: all checks green; `cargo run -p rovsti_messages` shows the preview inbox with a
merged SMS + WhatsApp person and an SMS conversation that "sends" against the preview backend.

### Step 8. Messages app: notifications and inline reply, scheduled send, Settings (spam, backup), docs

Files: `apps/messages/src/notifications.rs`, `schedule_ui.rs`, `settings_ui.rs`,
`backup_ui.rs` (new), `main.rs`, `prefs.rs` (new: `messages-prefs.json` with
`SpamRules`, per-thread SIM, notification switches; `rovsti_ui::storage::{save_json,
load_json}`), docs.

- Notifications while attached: on `GatewayEvent::Message` (not from me, not the open chat)
  and on `sms_received`, build `notify::build` and `notify::show`; `msg_reply` events send
  through `sms::send` or `Gateway::send_text` and append to the timeline; `notify::cancel`
  when a thread is opened. `uiAttached(true)` tells Kotlin to skip its own WhatsApp digest.
- Scheduled send: long-press Send -> sheet (`rovsti_ui::sheet::Sheet`) with
  `schedule::quick_times` and a custom time; SMS -> `ScheduleQueue` (`scheduled.json`) +
  `sms::arm_scheduled`; WhatsApp -> `Gateway::schedule_text`. A "Scheduled" strip in each
  conversation lists pending items (local queue + `Gateway::scheduled()`), cancel ->
  `disarm_scheduled` / `cancel_scheduled`. On attach, `sms::pull_fired` ->
  `ScheduleQueue::reconcile`; failures show a notice pill.
- Settings tab (replaces the empty state): Default SMS app, Notifications (on, OTP copy
  chip), Spam (enable, rules list with add/delete, allow list, blocked numbers via
  `rovsti_android::blocklist`), Backup (Export encrypted: passphrase twice, strength check,
  `msg_backup::seal` with `getrandom` salt/nonce in the app, write to cache,
  `documents::export_file(name, "application/octet-stream", path)`; Import:
  `documents::import_file(64 MiB)` -> `msg_backup::open` -> `restore_plan` preview ->
  `sms::insert_restored`), WhatsApp (link to the WhatsApp tab).
- Docs: tick the Messages items in `docs/roadmap.md` with "(done without a device)",
  `docs/architecture.md` AD-MSG1..8 summary, `docs/messages-acceptance.md` filled,
  `docs/whatsapp-gateway.md` scheduled API.

Tests: `prefs.rs` round-trip and corrupt file -> defaults; notification suppression for the
open thread (pure helper `fn should_notify(open: Option<&ThreadId>, msg: &Message) -> bool`);
backup UI state machine (passphrase mismatch, weak, wrong passphrase on import) as a pure
enum in `backup_ui.rs`.

Acceptance: all checks green; desktop run shows Settings, the schedule sheet and the backup
flow against the preview backend (export writes a file in the data dir on desktop).

## Left out (needs a device or an owner decision)

On-device acceptance (`docs/messages-acceptance.md`), RCS, group MMS sending beyond one
image, WhatsApp history in the local backup (it lives on the user's gateway), video/group
WhatsApp calls, first APK and CI.
