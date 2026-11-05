//! Push notifications (AD-WA6). A push only wakes the phone: it carries ids
//! and counts, never message text. The app then connects its WebSocket and
//! fetches what happened.

pub mod fcm;
pub mod jwt;

use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};

use rovsti_wa_proto::WaMessage;

use crate::config::FcmConfig;
use crate::now_ms;
use crate::store::{InstanceId, Store};

use self::fcm::{FcmSender, Outcome};
use self::jwt::ServiceAccount;

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
    Message { chat: String, count: u32 },
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
            Self::Message { chat, count } => BTreeMap::from([
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
                ("from".to_owned(), from.clone()),
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
                ("from".to_owned(), from.clone()),
                ("name".to_owned(), name.clone()),
            ]),
        }
    }

    pub fn high_priority(&self) -> bool {
        match self {
            Self::Message { .. } | Self::Call { .. } | Self::CallEnd { .. } => true,
        }
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
            PushKind::Message { chat, .. } => {
                let key = format!("{instance}/{chat}");
                let decision = self
                    .coalescer
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .offer(&key, now_ms());
                match decision {
                    Decision::Hold => return,
                    Decision::Send { count } => PushKind::Message { chat, count },
                }
            }
            call @ (PushKind::Call { .. } | PushKind::CallEnd { .. }) => call,
        };
        let sender = Arc::clone(&self.sender);
        let store = self.store.clone();
        let instance = instance.clone();
        tokio::spawn(async move {
            let tokens = match store.push_tokens(instance.clone()).await {
                Ok(tokens) => tokens,
                Err(e) => {
                    log::warn!("{instance}: reading push tokens failed: {e}");
                    return;
                }
            };
            for (app, token) in tokens {
                match sender
                    .send(token.clone(), kind.data(), kind.high_priority())
                    .await
                {
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
    use rovsti_wa_proto::MessageBody;

    fn message(from_me: bool, ts: i64) -> WaMessage {
        WaMessage {
            id: "m".into(),
            chat: "c".into(),
            sender: None,
            from_me,
            timestamp_ms: ts,
            body: MessageBody::Text("secret".into()),
            reactions: vec![],
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
            r#"{"call_id":"ABC","from":"905321234567@s.whatsapp.net","kind":"call","name":"Ayse"}"#
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
            r#"{"call_id":"ABC","from":"905321234567@s.whatsapp.net","kind":"call_end","missed":"1","name":"Ayse"}"#
        );
        assert!(kind.high_priority());
        if let PushKind::CallEnd { missed, .. } = &mut kind {
            *missed = false;
        }
        assert_eq!(kind.data()["missed"], "0");
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
}
