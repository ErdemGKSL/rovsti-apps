//! Push notifications (AD-WA6). To an app that registered no push key a push
//! only wakes the phone: it carries ids and counts, never message text. To an
//! app with a push key (round 7b) the push also says who wrote and what,
//! sealed to that app install's Keystore key ([`seal`]): FCM carries only
//! ciphertext. Either way the app then connects its WebSocket when it can.

pub mod fcm;
pub mod jwt;
pub mod seal;

use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};

use rosty_wa_proto::meta::body_preview;
use rosty_wa_proto::push::SENDER_KEY;
use rosty_wa_proto::{MessageBody, WaMessage};
use serde::Serialize;

use crate::config::FcmConfig;
use crate::now_ms;
use crate::store::{DevicePushKey, InstanceId, Store};

use self::fcm::{FcmSender, Outcome};
use self::jwt::ServiceAccount;
use self::seal::{SealError, seal};

/// FCM refuses a data message over 4096 bytes (keys and values). A sealed
/// push stays under this, which leaves room for FCM's own keys.
pub const MAX_DATA_BYTES: usize = 3_800;
/// The longest preview text a sealed push carries before the size fit, in
/// bytes (cut on a character boundary, with an ellipsis).
pub const MAX_PREVIEW_BYTES: usize = 1_024;
/// The longest name (sender, chat) a sealed push carries, in bytes.
pub const MAX_NAME_BYTES: usize = 128;

/// What a sealed message push can say beyond the chat (round 7b). Never sent
/// in clear: only to an app with a push key, sealed.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MessagePreview {
    /// The WhatsApp message id.
    pub id: String,
    /// The author in a group (absent in a one-to-one chat).
    pub sender: Option<String>,
    pub sender_alt: Option<String>,
    /// The other form of the chat id (phone JID next to a LID).
    pub chat_alt: Option<String>,
    /// The author's push name when the message arrived.
    pub sender_name: Option<String>,
    /// The text, else the caption, else a media label.
    pub text: Option<String>,
    pub timestamp_ms: i64,
}

impl MessagePreview {
    /// The preview of a stored message (`sender_name` from its meta).
    pub fn of(message: &WaMessage, sender_name: Option<String>) -> Self {
        Self {
            id: message.id.clone(),
            sender: message.sender.clone(),
            sender_alt: message.sender_alt.clone(),
            chat_alt: message.chat_alt.clone(),
            sender_name: sender_name.filter(|n| !n.trim().is_empty()),
            text: preview_text(&message.body),
            timestamp_ms: message.timestamp_ms,
        }
    }
}

/// The text a preview shows: the whole text (not only its first line), else
/// the caption, else the media label.
pub fn preview_text(body: &MessageBody) -> Option<String> {
    match body {
        MessageBody::Text(text) => Some(text.trim().to_owned()).filter(|t| !t.is_empty()),
        MessageBody::Media { caption, .. } => caption
            .as_deref()
            .map(str::trim)
            .filter(|c| !c.is_empty())
            .map(str::to_owned)
            .or_else(|| body_preview(body, MAX_NAME_BYTES)),
        MessageBody::Unsupported => None,
    }
}

/// `text` cut to at most `max` bytes on a character boundary; a cut ends in
/// an ellipsis (counted in `max`).
pub fn cut_bytes(text: &str, max: usize) -> String {
    if text.len() <= max {
        return text.to_owned();
    }
    let ellipsis = '\u{2026}';
    let budget = max.saturating_sub(ellipsis.len_utf8());
    let mut end = budget.min(text.len());
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    let mut cut = text[..end].to_owned();
    cut.push(ellipsis);
    cut
}

/// The sealed JSON of a push (field names are the app's contract; see
/// `docs/whatsapp-gateway.md`, "Sealed push"). Absent fields are left out.
#[derive(Debug, Default, Serialize)]
struct SealedContent<'a> {
    #[serde(skip_serializing_if = "Option::is_none")]
    chat: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    chat_alt: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    chat_name: Option<String>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    group: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    sender: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    sender_alt: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    name: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    id: Option<&'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    ts: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    count: Option<u32>,
}

fn name_of(name: &str) -> Option<String> {
    let name = name.trim();
    (!name.is_empty()).then(|| cut_bytes(name, MAX_NAME_BYTES))
}

/// The bytes FCM counts for a data map: every key and value.
pub fn data_bytes(data: &BTreeMap<String, String>) -> usize {
    data.iter().map(|(k, v)| k.len() + v.len()).sum()
}

/// Pushes for one chat are at most this far apart.
pub const COALESCE_WINDOW_MS: i64 = 3_000;
/// A message older than this (delivered late after a reconnect) does not wake
/// the phone.
pub const MAX_PUSH_AGE_MS: i64 = 2 * 60_000;
/// Coalescer entries kept before expired ones are swept.
const COALESCER_SWEEP_AT: usize = 1024;

/// What the apps are woken for.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PushKind {
    /// New message(s) in `chat`. Producers pass `count: 1`; the pusher
    /// replaces it with the number of messages the push stands for.
    /// `preview` (the newest message) is only ever sent sealed.
    Message {
        chat: String,
        count: u32,
        preview: Option<Box<MessagePreview>>,
    },
    /// A call is ringing. Never coalesced: the phone must wake at once.
    Call {
        call_id: String,
        from: String,
        name: String,
    },
    /// A ring the phone was woken for is over. Never coalesced.
    CallEnd {
        call_id: String,
        /// The caller gave up or nobody answered.
        missed: bool,
        from: String,
        name: String,
    },
}

impl PushKind {
    /// The FCM `data` map. Every value is a string (FCM requires it).
    pub fn data(&self) -> BTreeMap<String, String> {
        match self {
            Self::Message { chat, count, .. } => BTreeMap::from([
                ("kind".to_owned(), "message".to_owned()),
                ("chat".to_owned(), chat.clone()),
                ("count".to_owned(), count.to_string()),
            ]),
            Self::Call {
                call_id,
                from,
                name,
            } => BTreeMap::from([
                ("kind".to_owned(), "call".to_owned()),
                ("call_id".to_owned(), call_id.clone()),
                (SENDER_KEY.to_owned(), from.clone()),
                ("name".to_owned(), name.clone()),
            ]),
            Self::CallEnd {
                call_id,
                missed,
                from,
                name,
            } => BTreeMap::from([
                ("kind".to_owned(), "call_end".to_owned()),
                ("call_id".to_owned(), call_id.clone()),
                (
                    "missed".to_owned(),
                    if *missed { "1" } else { "0" }.to_owned(),
                ),
                (SENDER_KEY.to_owned(), from.clone()),
                ("name".to_owned(), name.clone()),
            ]),
        }
    }

    pub fn high_priority(&self) -> bool {
        match self {
            Self::Message { .. } | Self::Call { .. } | Self::CallEnd { .. } => true,
        }
    }

    /// The FCM `data` map for an app with a push key (round 7b): in clear only
    /// what routes the push even when the app cannot open it (`kind`; a
    /// call's opaque `call_id`; a call end's `missed`), the rest sealed to
    /// `key`. `chat_name` is the stored chat name. A preview text is cut so
    /// the map stays under [`MAX_DATA_BYTES`].
    pub fn sealed_data(
        &self,
        key: &DevicePushKey,
        chat_name: Option<&str>,
    ) -> Result<BTreeMap<String, String>, SealError> {
        let (mut data, mut content) = match self {
            Self::Message {
                chat,
                count,
                preview,
            } => {
                let preview = preview.as_deref();
                let content = SealedContent {
                    chat: Some(chat),
                    chat_alt: preview.and_then(|p| p.chat_alt.as_deref()),
                    chat_name: chat_name.and_then(name_of),
                    group: chat.ends_with("@g.us"),
                    sender: preview.and_then(|p| p.sender.as_deref()),
                    sender_alt: preview.and_then(|p| p.sender_alt.as_deref()),
                    name: preview
                        .and_then(|p| p.sender_name.as_deref())
                        .and_then(name_of),
                    text: preview
                        .and_then(|p| p.text.as_deref())
                        .map(|t| cut_bytes(t, MAX_PREVIEW_BYTES)),
                    id: preview.map(|p| p.id.as_str()),
                    ts: preview.map(|p| p.timestamp_ms),
                    count: Some(*count),
                };
                (
                    BTreeMap::from([("kind".to_owned(), "message".to_owned())]),
                    content,
                )
            }
            Self::Call {
                call_id,
                from,
                name,
            } => (
                BTreeMap::from([
                    ("kind".to_owned(), "call".to_owned()),
                    ("call_id".to_owned(), call_id.clone()),
                ]),
                SealedContent {
                    sender: Some(from),
                    name: name_of(name),
                    ..SealedContent::default()
                },
            ),
            Self::CallEnd {
                call_id,
                missed,
                from,
                name,
            } => (
                BTreeMap::from([
                    ("kind".to_owned(), "call_end".to_owned()),
                    ("call_id".to_owned(), call_id.clone()),
                    (
                        "missed".to_owned(),
                        if *missed { "1" } else { "0" }.to_owned(),
                    ),
                ]),
                SealedContent {
                    sender: Some(from),
                    name: name_of(name),
                    ..SealedContent::default()
                },
            ),
        };
        loop {
            let json = serde_json::to_vec(&content).map_err(|_| SealError::Crypto)?;
            let sealed = seal(key, &json)?;
            let mut out = data.clone();
            out.extend(sealed.entries());
            if data_bytes(&out) <= MAX_DATA_BYTES {
                data = out;
                break;
            }
            // Too long: halve the text, then drop it (ids and names are short).
            content.text = match content.text.take() {
                Some(text) if text.len() > 16 => Some(cut_bytes(&text, text.len() / 2)),
                Some(_) => None,
                None if content.chat_name.is_some() || content.name.is_some() => {
                    content.chat_name = None;
                    content.name = None;
                    None
                }
                None => return Err(SealError::TooLarge),
            };
        }
        Ok(data)
    }
}

/// Wakes the apps of an instance. Fire and forget: failures are logged.
pub trait Pusher: Send + Sync + 'static {
    fn notify(&self, instance: &InstanceId, kind: PushKind);
}

/// Used when FCM is not configured.
#[derive(Debug, Default)]
pub struct NoopPusher;

impl Pusher for NoopPusher {
    fn notify(&self, _instance: &InstanceId, _kind: PushKind) {}
}

/// Whether a stored message deserves a push: somebody else wrote it and it is
/// recent.
pub fn should_push(message: &WaMessage, now_ms: i64) -> bool {
    !message.from_me && now_ms.saturating_sub(message.timestamp_ms) <= MAX_PUSH_AGE_MS
}

/// What the coalescer decided for one message.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Decision {
    /// Push now; the push stands for `count` messages (this one plus the ones
    /// held back since the last push).
    Send { count: u32 },
    /// A push for this chat went out recently; this message rides on the next.
    Hold,
}

#[derive(Clone, Copy, Debug)]
struct Entry {
    last_sent_ms: i64,
    held: u32,
}

/// Rate-limits pushes per key (instance and chat): the first message goes out
/// at once, the rest within the window are held and counted. The phone wakes on
/// the first push and receives the rest over its socket.
#[derive(Debug)]
pub struct Coalescer {
    window_ms: i64,
    entries: HashMap<String, Entry>,
}

impl Coalescer {
    pub fn new(window_ms: i64) -> Self {
        Self {
            window_ms,
            entries: HashMap::new(),
        }
    }

    pub fn offer(&mut self, key: &str, now_ms: i64) -> Decision {
        if self.entries.len() >= COALESCER_SWEEP_AT {
            let window = self.window_ms;
            self.entries
                .retain(|_, e| now_ms.saturating_sub(e.last_sent_ms) < window);
        }
        match self.entries.get_mut(key) {
            Some(entry) if now_ms.saturating_sub(entry.last_sent_ms) < self.window_ms => {
                entry.held += 1;
                Decision::Hold
            }
            Some(entry) => {
                let count = entry.held + 1;
                *entry = Entry {
                    last_sent_ms: now_ms,
                    held: 0,
                };
                Decision::Send { count }
            }
            None => {
                self.entries.insert(
                    key.to_owned(),
                    Entry {
                        last_sent_ms: now_ms,
                        held: 0,
                    },
                );
                Decision::Send { count: 1 }
            }
        }
    }
}

/// Sends through FCM HTTP v1 to every token registered for the instance.
pub struct FcmPusher {
    sender: Arc<FcmSender>,
    store: Store,
    coalescer: Mutex<Coalescer>,
}

impl std::fmt::Debug for FcmPusher {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("FcmPusher")
    }
}

impl FcmPusher {
    pub fn new(sender: FcmSender, store: Store) -> Self {
        Self {
            sender: Arc::new(sender),
            store,
            coalescer: Mutex::new(Coalescer::new(COALESCE_WINDOW_MS)),
        }
    }
}

impl Pusher for FcmPusher {
    fn notify(&self, instance: &InstanceId, kind: PushKind) {
        let kind = match kind {
            PushKind::Message { chat, preview, .. } => {
                let key = format!("{instance}/{chat}");
                let decision = self
                    .coalescer
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .offer(&key, now_ms());
                match decision {
                    Decision::Hold => return,
                    Decision::Send { count } => PushKind::Message {
                        chat,
                        count,
                        preview,
                    },
                }
            }
            call @ (PushKind::Call { .. } | PushKind::CallEnd { .. }) => call,
        };
        let sender = Arc::clone(&self.sender);
        let store = self.store.clone();
        let instance = instance.clone();
        tokio::spawn(async move {
            let targets = match store.push_targets(instance.clone()).await {
                Ok(targets) => targets,
                Err(e) => {
                    log::warn!("{instance}: reading push tokens failed: {e}");
                    return;
                }
            };
            // One local read, and only when some app can open a sealed push.
            let chat_name = match &kind {
                PushKind::Message { chat, .. } if targets.iter().any(|t| t.key.is_some()) => store
                    .chat_name(instance.clone(), chat.clone())
                    .await
                    .unwrap_or_else(|e| {
                        log::warn!("{instance}: reading a chat name failed: {e}");
                        None
                    }),
                _ => None,
            };
            for target in targets {
                let (app, token) = (target.app, target.token);
                let data = match &target.key {
                    Some(key) => kind
                        .sealed_data(key, chat_name.as_deref())
                        .unwrap_or_else(|e| {
                            // Never the content: the wake-up an app without a key gets.
                            log::warn!("{instance}: sealing a push for {app:?} failed: {e}");
                            kind.data()
                        }),
                    None => kind.data(),
                };
                match sender.send(token.clone(), data, kind.high_priority()).await {
                    Ok(Outcome::Sent) => {}
                    Ok(Outcome::Unregistered) => {
                        log::info!("{instance}: dropping a dead {app:?} push token");
                        if let Err(e) = store.delete_push_token(instance.clone(), token).await {
                            log::warn!("{instance}: deleting push token failed: {e}");
                        }
                    }
                    Err(e) => log::warn!("{instance}: push to {app:?} failed: {e}"),
                }
            }
        });
    }
}

#[derive(Debug, thiserror::Error)]
pub enum PushSetupError {
    #[error("cannot read the FCM service account {path}: {source}")]
    Read {
        path: std::path::PathBuf,
        source: std::io::Error,
    },
    #[error("the FCM service account is not valid JSON: {0}")]
    Json(#[from] serde_json::Error),
    #[error(transparent)]
    Fcm(#[from] fcm::FcmError),
}

/// Builds the pusher for `config`: FCM when configured, otherwise a no-op
/// (logged once at boot).
pub fn from_config(
    fcm: Option<&FcmConfig>,
    store: Store,
) -> Result<Arc<dyn Pusher>, PushSetupError> {
    let Some(fcm) = fcm else {
        log::info!("push disabled: set fcm_project_id and fcm_service_account to enable FCM");
        return Ok(Arc::new(NoopPusher));
    };
    let json = std::fs::read(&fcm.service_account).map_err(|source| PushSetupError::Read {
        path: fcm.service_account.clone(),
        source,
    })?;
    let account = ServiceAccount::from_json(&json)?;
    let sender = FcmSender::new(fcm.project_id.clone(), account)?;
    log::info!("push enabled: FCM project {}", fcm.project_id);
    Ok(Arc::new(FcmPusher::new(sender, store)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use rosty_wa_proto::MessageBody;

    fn message(from_me: bool, ts: i64) -> WaMessage {
        WaMessage {
            id: "m".into(),
            chat: "c".into(),
            sender: None,
            from_me,
            timestamp_ms: ts,
            body: MessageBody::Text("secret".into()),
            reactions: vec![],
            chat_alt: None,
            sender_alt: None,
        }
    }

    #[test]
    fn coalescer_sends_first_then_holds_then_reports_the_count() {
        let mut c = Coalescer::new(3_000);
        assert_eq!(c.offer("a", 1_000), Decision::Send { count: 1 });
        assert_eq!(c.offer("a", 1_001), Decision::Hold);
        assert_eq!(c.offer("a", 3_999), Decision::Hold);
        // The window is measured from the last push, and is exclusive.
        assert_eq!(c.offer("a", 4_000), Decision::Send { count: 3 });
        assert_eq!(c.offer("a", 4_500), Decision::Hold);
        assert_eq!(c.offer("a", 20_000), Decision::Send { count: 2 });
        assert_eq!(c.offer("a", 30_000), Decision::Send { count: 1 });
    }

    #[test]
    fn coalescer_windows_are_per_key() {
        let mut c = Coalescer::new(3_000);
        assert_eq!(c.offer("a", 0), Decision::Send { count: 1 });
        assert_eq!(c.offer("b", 1), Decision::Send { count: 1 });
        assert_eq!(c.offer("a", 2), Decision::Hold);
        assert_eq!(c.offer("b", 2), Decision::Hold);
    }

    #[test]
    fn coalescer_sweeps_expired_entries() {
        let mut c = Coalescer::new(10);
        for n in 0..COALESCER_SWEEP_AT {
            c.offer(&n.to_string(), 0);
        }
        assert_eq!(c.entries.len(), COALESCER_SWEEP_AT);
        c.offer("new", 1_000);
        assert_eq!(c.entries.len(), 1);
    }

    #[test]
    fn push_data_has_ids_and_counts_only() {
        let kind = PushKind::Message {
            chat: "905321234567@s.whatsapp.net".into(),
            count: 3,
            preview: None,
        };
        assert_eq!(
            serde_json::to_string(&kind.data()).unwrap(),
            r#"{"chat":"905321234567@s.whatsapp.net","count":"3","kind":"message"}"#
        );
        assert!(kind.high_priority());
    }

    #[test]
    fn call_push_is_high_priority_and_carries_no_text() {
        let kind = PushKind::Call {
            call_id: "ABC".into(),
            from: "905321234567@s.whatsapp.net".into(),
            name: "Ayse".into(),
        };
        assert_eq!(
            serde_json::to_string(&kind.data()).unwrap(),
            r#"{"call_id":"ABC","kind":"call","name":"Ayse","sender":"905321234567@s.whatsapp.net"}"#
        );
        assert!(kind.high_priority());
    }

    #[test]
    fn call_end_push_is_high_priority_and_flags_missed() {
        let mut kind = PushKind::CallEnd {
            call_id: "ABC".into(),
            missed: true,
            from: "905321234567@s.whatsapp.net".into(),
            name: "Ayse".into(),
        };
        assert_eq!(
            serde_json::to_string(&kind.data()).unwrap(),
            r#"{"call_id":"ABC","kind":"call_end","missed":"1","name":"Ayse","sender":"905321234567@s.whatsapp.net"}"#
        );
        assert!(kind.high_priority());
        if let PushKind::CallEnd { missed, .. } = &mut kind {
            *missed = false;
        }
        assert_eq!(kind.data()["missed"], "0");
    }

    #[test]
    fn no_push_uses_a_key_fcm_reserves() {
        // FCM rejects `from`, `notification`, `message_type` and anything
        // starting with `google` or `gcm` in the data map.
        let kinds = [
            PushKind::Message {
                chat: "c".into(),
                count: 1,
                preview: None,
            },
            PushKind::Call {
                call_id: "A".into(),
                from: "f".into(),
                name: "n".into(),
            },
            PushKind::CallEnd {
                call_id: "A".into(),
                missed: false,
                from: "f".into(),
                name: "n".into(),
            },
        ];
        for kind in kinds {
            for key in kind.data().keys() {
                assert!(
                    !matches!(key.as_str(), "from" | "notification" | "message_type")
                        && !key.starts_with("google")
                        && !key.starts_with("gcm"),
                    "{key} in {kind:?}"
                );
            }
        }
    }

    #[test]
    fn only_fresh_incoming_messages_push() {
        let now = 1_000_000;
        assert!(should_push(&message(false, now - 1_000), now));
        assert!(!should_push(&message(true, now), now));
        assert!(!should_push(
            &message(false, now - MAX_PUSH_AGE_MS - 1),
            now
        ));
        assert!(should_push(&message(false, now + 5_000), now));
    }

    #[test]
    fn unconfigured_push_is_a_noop() {
        let store = Store::open_in_memory().unwrap();
        let pusher = from_config(None, store).unwrap();
        pusher.notify(
            &InstanceId::new("x"),
            PushKind::Message {
                chat: "c".into(),
                count: 1,
                preview: None,
            },
        );
    }

    #[test]
    fn a_missing_service_account_file_fails_boot() {
        let store = Store::open_in_memory().unwrap();
        let cfg = FcmConfig {
            project_id: "p".into(),
            service_account: "/nonexistent/sa.json".into(),
        };
        assert!(matches!(
            from_config(Some(&cfg), store),
            Err(PushSetupError::Read { .. })
        ));
    }

    // ---- Sealed pushes (round 7b) ----

    use super::seal::test_device::TestDevice;
    use super::seal::{CIPHERTEXT_KEY, EPHEMERAL_KEY, KEY_ID_KEY, Sealed, VERSION_KEY};

    const CHAT: &str = "100000000001@s.whatsapp.net";
    const GROUP: &str = "100000000002-1600000000@g.us";

    fn preview(text: &str) -> MessagePreview {
        MessagePreview {
            id: "3EB0TEST".into(),
            sender: None,
            sender_alt: None,
            chat_alt: Some("12345@lid".into()),
            sender_name: Some("Ada".into()),
            text: Some(text.into()),
            timestamp_ms: 1_700_000_000_000,
        }
    }

    fn sealed_of(data: &BTreeMap<String, String>) -> Sealed {
        Sealed {
            key_id: data[KEY_ID_KEY].clone(),
            epk: data[EPHEMERAL_KEY].clone(),
            ct: data[CIPHERTEXT_KEY].clone(),
        }
    }

    fn open(device: TestDevice, data: &BTreeMap<String, String>) -> serde_json::Value {
        serde_json::from_slice(&device.open(&sealed_of(data)).unwrap()).unwrap()
    }

    #[test]
    fn a_sealed_message_push_says_who_and_what_to_the_device_only() {
        let device = TestDevice::new("k1");
        let kind = PushKind::Message {
            chat: CHAT.into(),
            count: 2,
            preview: Some(Box::new(preview("Lunch at one?\nOr two."))),
        };
        let data = kind.sealed_data(&device.key, Some("Ada L.")).unwrap();
        let clear: Vec<&str> = data.keys().map(String::as_str).collect();
        assert_eq!(clear, ["ct", "epk", "kid", "kind", "v"]);
        assert_eq!(data["kind"], "message");
        assert_eq!(data[VERSION_KEY], "1");
        assert_eq!(data[KEY_ID_KEY], "k1");
        // Nothing readable reaches FCM.
        for value in data.values() {
            for secret in ["100000000001", "Lunch", "Ada", "3EB0TEST", "lid"] {
                assert!(!value.contains(secret), "{secret} in {value}");
            }
        }
        let content = open(device, &data);
        assert_eq!(
            content,
            serde_json::json!({
                "chat": CHAT,
                "chat_alt": "12345@lid",
                "chat_name": "Ada L.",
                "name": "Ada",
                "text": "Lunch at one?\nOr two.",
                "id": "3EB0TEST",
                "ts": 1_700_000_000_000i64,
                "count": 2,
            })
        );
    }

    #[test]
    fn a_sealed_group_push_names_the_author() {
        let device = TestDevice::new("k1");
        let mut p = preview("hi all");
        p.sender = Some("100000000003@s.whatsapp.net".into());
        p.sender_alt = Some("777@lid".into());
        let kind = PushKind::Message {
            chat: GROUP.into(),
            count: 1,
            preview: Some(Box::new(p)),
        };
        let data = kind.sealed_data(&device.key, Some("Team")).unwrap();
        let content = open(device, &data);
        assert_eq!(content["group"], true);
        assert_eq!(content["chat_name"], "Team");
        assert_eq!(content["sender"], "100000000003@s.whatsapp.net");
        assert_eq!(content["sender_alt"], "777@lid");
    }

    #[test]
    fn sealed_call_pushes_keep_only_routing_in_clear() {
        let device = TestDevice::new("k1");
        let ring = PushKind::Call {
            call_id: "CALL1".into(),
            from: CHAT.into(),
            name: "Ada".into(),
        };
        let data = ring.sealed_data(&device.key, None).unwrap();
        let clear: Vec<&str> = data.keys().map(String::as_str).collect();
        assert_eq!(clear, ["call_id", "ct", "epk", "kid", "kind", "v"]);
        assert_eq!(data["call_id"], "CALL1");
        let content = open(device, &data);
        assert_eq!(content, serde_json::json!({"sender": CHAT, "name": "Ada"}));

        let device = TestDevice::new("k1");
        let end = PushKind::CallEnd {
            call_id: "CALL1".into(),
            missed: true,
            from: CHAT.into(),
            name: String::new(),
        };
        let data = end.sealed_data(&device.key, None).unwrap();
        assert_eq!(data["kind"], "call_end");
        assert_eq!(data["missed"], "1");
        assert!(!data.contains_key(SENDER_KEY) && !data.contains_key("name"));
        assert_eq!(open(device, &data), serde_json::json!({"sender": CHAT}));
    }

    #[test]
    fn a_long_preview_is_cut_to_fit_fcm() {
        let device = TestDevice::new(&"k".repeat(seal::MAX_KEY_ID_LEN));
        let mut p = preview(&"ğ".repeat(5_000));
        p.sender_name = Some("N".repeat(1_000));
        let kind = PushKind::Message {
            chat: CHAT.into(),
            count: 999,
            preview: Some(Box::new(p)),
        };
        let data = kind
            .sealed_data(&device.key, Some(&"C".repeat(1_000)))
            .unwrap();
        assert!(data_bytes(&data) <= MAX_DATA_BYTES, "{}", data_bytes(&data));
        let content = open(device, &data);
        let text = content["text"].as_str().unwrap();
        assert!(text.len() <= MAX_PREVIEW_BYTES && text.ends_with('\u{2026}'));
        assert!(content["name"].as_str().unwrap().len() <= MAX_NAME_BYTES);
        assert!(content["chat_name"].as_str().unwrap().len() <= MAX_NAME_BYTES);
    }

    #[test]
    fn cuts_land_on_character_boundaries() {
        assert_eq!(cut_bytes("abc", 3), "abc");
        assert_eq!(cut_bytes("abcdef", 4), "a\u{2026}");
        // "ğ" is two bytes: no half character before the ellipsis.
        assert_eq!(cut_bytes("ğğğğ", 6), "ğ\u{2026}");
        assert_eq!(cut_bytes("ğğğğ", 0), "\u{2026}");
    }

    #[test]
    fn previews_use_the_whole_text_else_the_caption_else_the_label() {
        assert_eq!(
            preview_text(&MessageBody::Text("  a\nb  ".into())).as_deref(),
            Some("a\nb")
        );
        let photo = |caption: Option<&str>| MessageBody::Media {
            kind: rosty_wa_proto::MediaKind::Image,
            caption: caption.map(str::to_owned),
            mime: "image/jpeg".into(),
            size: 1,
        };
        assert_eq!(preview_text(&photo(Some("sea"))).as_deref(), Some("sea"));
        assert_eq!(preview_text(&photo(None)).as_deref(), Some("[Photo]"));
        assert_eq!(preview_text(&MessageBody::Unsupported), None);
        assert_eq!(preview_text(&MessageBody::Text(" ".into())), None);
    }

    #[test]
    fn no_sealed_push_uses_a_key_fcm_reserves() {
        let device = TestDevice::new("k1");
        let kinds = [
            PushKind::Message {
                chat: CHAT.into(),
                count: 1,
                preview: Some(Box::new(preview("x"))),
            },
            PushKind::Call {
                call_id: "A".into(),
                from: CHAT.into(),
                name: "n".into(),
            },
        ];
        for kind in kinds {
            for key in kind.sealed_data(&device.key, None).unwrap().keys() {
                assert!(
                    !matches!(key.as_str(), "from" | "notification" | "message_type")
                        && !key.starts_with("google")
                        && !key.starts_with("gcm"),
                    "{key}"
                );
            }
        }
    }

    /// The fixture the app's Kotlin test opens (`SealedPushTest`): a test-only
    /// key pair made for it, and a push this code sealed to its public half.
    /// `UPDATE_PUSH_FIXTURE=1` seals a new `data`; otherwise the fixture is
    /// only checked to match this format.
    #[test]
    fn the_app_fixture_is_a_push_sealed_by_this_code() {
        let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join(
            "../../android/platform/src/test/resources/dev/rosty/platform/wa/push/sealed-push-fixture.json",
        );
        let mut fixture: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&path).unwrap()).unwrap();
        let public = rosty_wa_proto::PushKey {
            key_id: fixture["key_id"].as_str().unwrap().to_owned(),
            version: 1,
            public_key: fixture["public_key_spki"].as_str().unwrap().to_owned(),
        };
        let key = seal::parse_push_key(&public).unwrap();
        if std::env::var("UPDATE_PUSH_FIXTURE").is_ok_and(|v| v == "1") {
            let p = &fixture["preview"];
            let kind = PushKind::Message {
                chat: p["chat"].as_str().unwrap().to_owned(),
                count: 2,
                preview: Some(Box::new(MessagePreview {
                    id: p["id"].as_str().unwrap().to_owned(),
                    sender: None,
                    sender_alt: None,
                    chat_alt: None,
                    sender_name: p["name"].as_str().map(str::to_owned),
                    text: p["text"].as_str().map(str::to_owned),
                    timestamp_ms: p["ts"].as_i64().unwrap(),
                })),
            };
            let data = kind.sealed_data(&key, p["chat_name"].as_str()).unwrap();
            fixture["data"] = serde_json::to_value(&data).unwrap();
            let mut out = serde_json::to_string_pretty(&fixture).unwrap();
            out.push('\n');
            std::fs::write(&path, out).unwrap();
        }
        let data = fixture["data"].as_object().unwrap();
        assert_eq!(data["kind"], "message");
        assert_eq!(data[VERSION_KEY], "1");
        assert_eq!(data[KEY_ID_KEY], fixture["key_id"]);
        let ct = data[CIPHERTEXT_KEY].as_str().unwrap();
        assert!(!ct.contains(fixture["preview"]["text"].as_str().unwrap()));
    }
}
