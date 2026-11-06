//! Events the gateway pushes over `GET /v1/events`.

use crate::api::{InstanceStatus, MessageBody, Reaction, ScheduledText, TypingState, WaMessage};
use crate::call::{CallDirection, CallPhase};
use crate::meta::{ChatMeta, MessageMeta, WaProfile};

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct EventEnvelope {
    pub seq: u64,
    pub at_ms: i64,
    pub event: GatewayEvent,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ReceiptKind {
    Delivered,
    Read,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct CallUpdate {
    pub call_id: String,
    pub peer: String,
    pub peer_name: Option<String>,
    pub direction: CallDirection,
    pub phase: CallPhase,
    pub is_video: bool,
}

/// One call as `GET /v1/calls` reports it: the latest [`CallUpdate`] plus the
/// gateway's wall-clock timestamps.
#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct CallSummary {
    #[serde(flatten)]
    pub update: CallUpdate,
    /// Wall clock when the gateway first saw the call.
    pub started_ms: i64,
    /// Wall clock when the call ended; `None` while it is live.
    #[serde(default)]
    pub ended_ms: Option<i64>,
}

#[derive(Clone, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum GatewayEvent {
    Qr {
        payload: String,
        valid_for_ms: u64,
    },
    PairCode {
        code: String,
        valid_for_ms: u64,
    },
    State(InstanceStatus),
    Message(WaMessage),
    MessageUpdate {
        chat: String,
        id: String,
        reactions: Vec<Reaction>,
        read: bool,
    },
    Receipt {
        chat: String,
        ids: Vec<String>,
        kind: ReceiptKind,
    },
    Presence {
        jid: String,
        available: bool,
        last_seen_ms: Option<i64>,
    },
    Typing {
        chat: String,
        jid: String,
        state: TypingState,
    },
    HistorySynced {
        chats: u32,
        progress: Option<u32>,
    },
    GroupChanged {
        jid: String,
    },
    Call(CallUpdate),
    /// A scheduled text was added, sent or failed. Older clients skip it.
    Scheduled(ScheduledText),
    /// The meta of a message is new or changed: a reply, ticks, an edit
    /// (`body` holds the new body), a delete for everyone (`meta.revoked`,
    /// `body` is `unsupported`), a star. Follows the `message` event of a new
    /// message whose meta is not empty. Older clients skip it.
    MessageMeta {
        chat: String,
        id: String,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        body: Option<MessageBody>,
        meta: Box<MessageMeta>,
    },
    /// A message was deleted on my devices only: drop it.
    MessageDeleted {
        chat: String,
        id: String,
    },
    /// Archive, pin, mute, unread mark or picture of a chat changed.
    ChatMeta {
        jid: String,
        meta: ChatMeta,
    },
    /// A chat was deleted on another device: drop it and its messages.
    ChatDeleted {
        jid: String,
    },
    /// A chat was cleared on another device: drop its messages, keep it.
    ChatCleared {
        jid: String,
    },
    /// A person's or group's push name, about text or picture changed. A new
    /// `picture_id` means cached picture bytes are stale.
    Profile(WaProfile),
    /// Status updates (stories) changed: one was posted, deleted, seen or
    /// expired. `author` is set when only that person's changed. Refetch
    /// `GET /v1/statuses`. Older clients skip it.
    StatusChanged {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        author: Option<String>,
    },
    /// A chat stored under a LID was merged into the phone-number chat of the
    /// same person: move local state (drafts, pins, mutes) from `from` to
    /// `into`, and refetch chats. Older clients skip it.
    ChatMerged {
        from: String,
        into: String,
    },
    /// The client missed events; refetch chats.
    Resync,
    /// The key used for this socket was revoked.
    KeyRevoked,
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::api::InstanceState;

    fn json(e: &GatewayEvent) -> String {
        serde_json::to_string(e).unwrap()
    }

    #[test]
    fn call_summary_flattens_update() {
        let s = CallSummary {
            update: CallUpdate {
                call_id: "c".into(),
                peer: "1@s.whatsapp.net".into(),
                peer_name: None,
                direction: CallDirection::In,
                phase: CallPhase::Ringing,
                is_video: false,
            },
            started_ms: 5,
            ended_ms: None,
        };
        let text = serde_json::to_string(&s).unwrap();
        assert!(text.contains(r#""call_id":"c""#) && text.contains(r#""started_ms":5"#));
        assert!(!text.contains("update"));
        assert_eq!(serde_json::from_str::<CallSummary>(&text).unwrap(), s);
        let no_end: CallSummary = serde_json::from_str(
            r#"{"call_id":"c","peer":"p","peer_name":null,"direction":"in","phase":"ringing","is_video":false,"started_ms":5}"#,
        )
        .unwrap();
        assert_eq!(no_end.ended_ms, None);
    }

    #[test]
    fn events_are_pinned() {
        let cases: Vec<(GatewayEvent, &str)> = vec![
            (
                GatewayEvent::Qr {
                    payload: "p".into(),
                    valid_for_ms: 20000,
                },
                r#"{"type":"qr","payload":"p","valid_for_ms":20000}"#,
            ),
            (
                GatewayEvent::PairCode {
                    code: "ABCD-1234".into(),
                    valid_for_ms: 1,
                },
                r#"{"type":"pair_code","code":"ABCD-1234","valid_for_ms":1}"#,
            ),
            (
                GatewayEvent::State(InstanceStatus {
                    instance_id: "i".into(),
                    state: InstanceState::LoggedOut,
                    own_jid: None,
                    push_name: None,
                }),
                r#"{"type":"state","instance_id":"i","state":"logged_out","own_jid":null,"push_name":null}"#,
            ),
            (
                GatewayEvent::MessageUpdate {
                    chat: "c".into(),
                    id: "m".into(),
                    reactions: vec![],
                    read: true,
                },
                r#"{"type":"message_update","chat":"c","id":"m","reactions":[],"read":true}"#,
            ),
            (
                GatewayEvent::Receipt {
                    chat: "c".into(),
                    ids: vec!["a".into()],
                    kind: ReceiptKind::Read,
                },
                r#"{"type":"receipt","chat":"c","ids":["a"],"kind":"read"}"#,
            ),
            (
                GatewayEvent::Presence {
                    jid: "j".into(),
                    available: true,
                    last_seen_ms: Some(5),
                },
                r#"{"type":"presence","jid":"j","available":true,"last_seen_ms":5}"#,
            ),
            (
                GatewayEvent::Typing {
                    chat: "c".into(),
                    jid: "j".into(),
                    state: TypingState::Recording,
                },
                r#"{"type":"typing","chat":"c","jid":"j","state":"recording"}"#,
            ),
            (
                GatewayEvent::HistorySynced {
                    chats: 3,
                    progress: None,
                },
                r#"{"type":"history_synced","chats":3,"progress":null}"#,
            ),
            (
                GatewayEvent::GroupChanged { jid: "g".into() },
                r#"{"type":"group_changed","jid":"g"}"#,
            ),
            (
                GatewayEvent::Call(CallUpdate {
                    call_id: "k".into(),
                    peer: "p".into(),
                    peer_name: None,
                    direction: CallDirection::In,
                    phase: CallPhase::Ringing,
                    is_video: false,
                }),
                r#"{"type":"call","call_id":"k","peer":"p","peer_name":null,"direction":"in","phase":"ringing","is_video":false}"#,
            ),
            (
                GatewayEvent::Scheduled(ScheduledText {
                    id: "s".into(),
                    chat: "c".into(),
                    text: "t".into(),
                    send_at_ms: 9,
                    created_ms: 1,
                    state: crate::api::ScheduledState::Pending,
                }),
                r#"{"type":"scheduled","id":"s","chat":"c","text":"t","send_at_ms":9,"created_ms":1,"state":{"status":"pending"}}"#,
            ),
            (
                GatewayEvent::MessageMeta {
                    chat: "c".into(),
                    id: "m".into(),
                    body: None,
                    meta: Box::new(MessageMeta {
                        revoked: true,
                        ..Default::default()
                    }),
                },
                r#"{"type":"message_meta","chat":"c","id":"m","meta":{"revoked":true}}"#,
            ),
            (
                GatewayEvent::MessageMeta {
                    chat: "c".into(),
                    id: "m".into(),
                    body: Some(MessageBody::Text("new".into())),
                    meta: Box::new(MessageMeta {
                        edited_ms: Some(3),
                        ..Default::default()
                    }),
                },
                r#"{"type":"message_meta","chat":"c","id":"m","body":{"text":"new"},"meta":{"edited_ms":3}}"#,
            ),
            (
                GatewayEvent::MessageDeleted {
                    chat: "c".into(),
                    id: "m".into(),
                },
                r#"{"type":"message_deleted","chat":"c","id":"m"}"#,
            ),
            (
                GatewayEvent::ChatMeta {
                    jid: "j".into(),
                    meta: ChatMeta {
                        archived: true,
                        ..Default::default()
                    },
                },
                r#"{"type":"chat_meta","jid":"j","meta":{"archived":true}}"#,
            ),
            (
                GatewayEvent::ChatDeleted { jid: "j".into() },
                r#"{"type":"chat_deleted","jid":"j"}"#,
            ),
            (
                GatewayEvent::ChatCleared { jid: "j".into() },
                r#"{"type":"chat_cleared","jid":"j"}"#,
            ),
            (
                GatewayEvent::Profile(WaProfile {
                    jid: "j".into(),
                    picture_id: Some("7".into()),
                    ..Default::default()
                }),
                r#"{"type":"profile","jid":"j","push_name":null,"contact_name":null,"about":null,"picture_id":"7","business":false,"verified_name":null,"fetched_ms":0}"#,
            ),
            (
                GatewayEvent::StatusChanged {
                    author: Some("a".into()),
                },
                r#"{"type":"status_changed","author":"a"}"#,
            ),
            (
                GatewayEvent::StatusChanged { author: None },
                r#"{"type":"status_changed"}"#,
            ),
            (
                GatewayEvent::ChatMerged {
                    from: "1@lid".into(),
                    into: "2@s.whatsapp.net".into(),
                },
                r#"{"type":"chat_merged","from":"1@lid","into":"2@s.whatsapp.net"}"#,
            ),
            (GatewayEvent::Resync, r#"{"type":"resync"}"#),
            (GatewayEvent::KeyRevoked, r#"{"type":"key_revoked"}"#),
        ];
        for (event, text) in cases {
            assert_eq!(json(&event), text);
            assert_eq!(serde_json::from_str::<GatewayEvent>(text).unwrap(), event);
        }
    }

    #[test]
    fn message_event_and_envelope() {
        let msg = WaMessage {
            id: "m1".into(),
            chat: "c@s.whatsapp.net".into(),
            sender: None,
            from_me: true,
            timestamp_ms: 9,
            body: crate::api::MessageBody::Text("hi".into()),
            reactions: vec![],
        };
        let env = EventEnvelope {
            seq: 4,
            at_ms: 10,
            event: GatewayEvent::Message(msg),
        };
        let text = serde_json::to_string(&env).unwrap();
        assert_eq!(
            text,
            r#"{"seq":4,"at_ms":10,"event":{"type":"message","id":"m1","chat":"c@s.whatsapp.net","sender":null,"from_me":true,"timestamp_ms":9,"body":{"text":"hi"},"reactions":[]}}"#
        );
        assert_eq!(serde_json::from_str::<EventEnvelope>(&text).unwrap(), env);
    }

    #[test]
    fn receipt_kind_pinned() {
        assert_eq!(
            serde_json::to_string(&ReceiptKind::Delivered).unwrap(),
            "\"delivered\""
        );
    }
}
