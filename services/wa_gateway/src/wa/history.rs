//! History sync: WhatsApp's `HistorySync` protobuf to store rows.

use rovsti_wa_proto::{DeliveryStatus, MUTED_FOREVER, MessageBody, Reaction};
use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::history_sync::HistorySyncError;
use whatsapp_rust::wacore::types::events::LazyHistorySync;
use whatsapp_rust::waproto::buffa::Message as _;
use whatsapp_rust::waproto::whatsapp::web_message_info::Status as WebStatus;

use super::convert::{self, Converted, Origin};
use crate::store::{ChatSettings, HistoryChat, HistoryMessage};

/// Chats that are not conversations the apps show.
fn is_skipped(jid: &str) -> bool {
    jid == "status@broadcast" || jid.ends_with("@newsletter") || jid.ends_with("@broadcast")
}

fn seconds_to_ms(seconds: u64) -> i64 {
    i64::try_from(seconds)
        .unwrap_or(i64::MAX / 1000)
        .saturating_mul(1000)
}

/// The ticks a history entry of mine had.
fn delivery(status: Option<WebStatus>) -> Option<DeliveryStatus> {
    match status? {
        WebStatus::SERVER_ACK => Some(DeliveryStatus::Sent),
        WebStatus::DELIVERY_ACK => Some(DeliveryStatus::Delivered),
        WebStatus::READ => Some(DeliveryStatus::Read),
        WebStatus::PLAYED => Some(DeliveryStatus::Played),
        WebStatus::ERROR | WebStatus::PENDING => None,
    }
}

/// The author of `key` in `chat`, for groups.
fn author(chat: &str, participant: Option<&str>) -> Option<String> {
    participant
        .filter(|p| !p.is_empty())
        .and_then(|p| p.parse::<Jid>().ok())
        .and_then(|jid| convert::group_sender(chat, &jid))
}

/// The reactions a history entry carries.
fn reactions(chat: &str, info: &wa::WebMessageInfo) -> Vec<Reaction> {
    info.reactions
        .iter()
        .filter_map(|r| {
            let emoji = r.text.clone().filter(|t| !t.is_empty())?;
            let key = r.key.as_option();
            Some(Reaction {
                sender: author(chat, key.and_then(|k| k.participant.as_deref())),
                from_me: key.and_then(|k| k.from_me).unwrap_or(false),
                emoji,
            })
        })
        .collect()
}

/// One history message. `None` when it has no id or content the apps show.
fn history_message(chat: &str, info: &wa::WebMessageInfo) -> Option<HistoryMessage> {
    let key = info.key.as_option()?;
    let id = key.id.as_deref().filter(|id| !id.is_empty())?;
    let content = info.message.as_option()?;
    let from_me = key.from_me.unwrap_or(false);
    let sender = author(
        chat,
        info.participant.as_deref().or(key.participant.as_deref()),
    );
    let origin = Origin {
        id,
        chat,
        sender: sender.as_deref(),
        from_me,
        timestamp_ms: seconds_to_ms(info.message_timestamp.unwrap_or(0)),
        sender_name: info.push_name.as_deref(),
    };
    match convert::convert(origin, content) {
        Converted::Message(message, extras) => {
            let mut extras = *extras;
            if matches!(message.body, MessageBody::Media { .. }) {
                extras.raw_proto = Some(content.encode_to_vec());
            }
            if from_me {
                extras.meta.status = delivery(info.status).or(extras.meta.status);
            }
            extras.meta.starred = info.starred.unwrap_or(false);
            if extras.secret.is_none() {
                extras.secret = info.message_secret.clone().filter(|s| !s.is_empty());
            }
            Some(HistoryMessage {
                reactions: reactions(chat, info),
                message,
                extras,
            })
        }
        // History holds each message as it is now; reactions come with their
        // message, edits and deletes are already applied.
        Converted::Reaction { .. }
        | Converted::Edit { .. }
        | Converted::Revoke { .. }
        | Converted::Ignore => None,
    }
}

/// A conversation's mute end as the apps see it. WhatsApp has sent it in
/// seconds and in milliseconds; a huge value means "forever".
fn mute_end(raw: Option<u64>, now_ms: i64) -> Option<i64> {
    let raw = raw.filter(|&v| v > 0)?;
    let ms = match i64::try_from(raw) {
        Err(_) => return Some(MUTED_FOREVER),
        Ok(v) if v < 100_000_000_000 => v.saturating_mul(1000),
        Ok(v) => v,
    };
    if ms >= i64::MAX / 2 {
        Some(MUTED_FOREVER)
    } else {
        (ms > now_ms).then_some(ms)
    }
}

/// The chat JID to store a conversation under: the phone-number JID when
/// WhatsApp keys it by LID but knows the number.
fn conversation_jid(conv: &wa::Conversation) -> &str {
    let id = conv.id.as_str();
    match conv.pn_jid.as_deref() {
        Some(pn) if id.ends_with("@lid") && pn.ends_with("@s.whatsapp.net") => pn,
        _ => id,
    }
}

/// One conversation. `None` for chats the apps do not show.
pub fn conversation(conv: &wa::Conversation, now_ms: i64) -> Option<HistoryChat> {
    let jid = conversation_jid(conv);
    if jid.is_empty() || is_skipped(jid) {
        return None;
    }
    let messages: Vec<HistoryMessage> = conv
        .messages
        .iter()
        .filter_map(|m| m.message.as_option())
        .filter_map(|info| history_message(jid, info))
        .collect();
    let newest = messages
        .iter()
        .map(|m| m.message.timestamp_ms)
        .max()
        .unwrap_or(0);
    let stamped = conv
        .conversation_timestamp
        .or(conv.last_msg_timestamp)
        .map_or(0, seconds_to_ms);
    let name = conv
        .name
        .as_deref()
        .or(conv.display_name.as_deref())
        .map(str::trim)
        .filter(|n| !n.is_empty())
        .map(str::to_owned);
    Some(HistoryChat {
        jid: jid.to_owned(),
        name,
        is_group: convert::is_group(jid),
        unread: conv.unread_count.unwrap_or(0),
        last_ms: newest.max(stamped),
        messages,
        settings: ChatSettings {
            archived: conv.archived.unwrap_or(false),
            pinned: conv.pinned.unwrap_or(0) > 0,
            muted_until_ms: mute_end(conv.mute_end_time, now_ms),
            marked_unread: conv.marked_as_unread.unwrap_or(false),
        },
    })
}

/// Every conversation of a decoded sync.
pub fn sync_to_chats(sync: &wa::HistorySync, now_ms: i64) -> Vec<HistoryChat> {
    sync.conversations
        .iter()
        .filter_map(|c| conversation(c, now_ms))
        .collect()
}

/// `(jid, push name)` pairs a sync carries.
pub fn push_names(sync: &wa::HistorySync) -> Vec<(String, String)> {
    sync.pushnames
        .iter()
        .filter_map(|p| {
            let jid = p.id.as_deref()?.parse::<Jid>().ok()?;
            let name = p.pushname.as_deref()?.trim();
            (!name.is_empty() && name != "-")
                .then(|| (jid.to_non_ad().to_string(), name.to_owned()))
        })
        .collect()
}

/// What one history sync holds for the store.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Drained {
    pub chats: Vec<HistoryChat>,
    pub push_names: Vec<(String, String)>,
}

/// Walks the lazily decoded sync one conversation at a time, so the whole
/// inflated blob never sits in memory. Blocking and CPU-heavy: call it from
/// `spawn_blocking`.
pub fn drain(lazy: &LazyHistorySync, now_ms: i64) -> Result<Drained, HistorySyncError> {
    let mut stream = lazy.stream();
    let mut chats = Vec::new();
    while let Some(conv) = stream.next_conversation()? {
        chats.extend(conversation(&conv, now_ms));
    }
    // Push names are optional: a sync whose tail does not decode still
    // delivers its chats.
    let push_names = match stream.remainder() {
        Ok(rest) => push_names(&rest),
        Err(e) => {
            log::debug!("history sync remainder unreadable: {e}");
            Vec::new()
        }
    };
    Ok(Drained { chats, push_names })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::{InstanceId, Store};
    use rovsti_wa_proto::{ConnectKey, KeyId};
    use whatsapp_rust::waproto::buffa::MessageField;

    fn web_message(
        id: &str,
        from_me: bool,
        ts: u64,
        text: &str,
        participant: Option<&str>,
    ) -> wa::HistorySyncMsg {
        wa::HistorySyncMsg {
            message: MessageField::some(wa::WebMessageInfo {
                key: MessageField::some(wa::MessageKey {
                    remote_jid: Some("ignored".into()),
                    from_me: Some(from_me),
                    id: Some(id.into()),
                    participant: participant.map(str::to_owned),
                }),
                message: MessageField::some(wa::Message {
                    conversation: Some(text.to_owned()),
                    ..Default::default()
                }),
                message_timestamp: Some(ts),
                ..Default::default()
            }),
            ..Default::default()
        }
    }

    fn sync() -> wa::HistorySync {
        let direct = wa::Conversation {
            id: "905321234567@s.whatsapp.net".into(),
            name: Some("Ayse".into()),
            unread_count: Some(1),
            messages: vec![
                web_message("M1", false, 1_700_000_000, "selam", None),
                web_message("M2", true, 1_700_000_060, "merhaba", None),
            ],
            ..Default::default()
        };
        let group = wa::Conversation {
            id: "123-456@g.us".into(),
            messages: vec![web_message(
                "G1",
                false,
                1_700_000_100,
                "hi all",
                Some("4915123456789:7@s.whatsapp.net"),
            )],
            ..Default::default()
        };
        let status = wa::Conversation {
            id: "status@broadcast".into(),
            messages: vec![web_message("S1", false, 1, "story", None)],
            ..Default::default()
        };
        wa::HistorySync {
            conversations: vec![direct, group, status],
            ..Default::default()
        }
    }

    #[test]
    fn maps_conversations_and_skips_status() {
        let chats = sync_to_chats(&sync(), 0);
        assert_eq!(chats.len(), 2);

        let direct = &chats[0];
        assert_eq!(direct.jid, "905321234567@s.whatsapp.net");
        assert_eq!(direct.name.as_deref(), Some("Ayse"));
        assert!(!direct.is_group);
        assert_eq!(direct.unread, 1);
        assert_eq!(direct.last_ms, 1_700_000_060_000);
        assert_eq!(direct.messages.len(), 2);
        let first = &direct.messages[0].message;
        assert_eq!(first.id, "M1");
        assert!(!first.from_me);
        assert_eq!(first.chat, direct.jid);
        assert_eq!(first.timestamp_ms, 1_700_000_000_000);
        assert_eq!(first.sender, None);
        assert!(direct.messages[1].message.from_me);

        let group = &chats[1];
        assert!(group.is_group);
        assert_eq!(
            group.messages[0].message.sender.as_deref(),
            Some("4915123456789@s.whatsapp.net")
        );
    }

    #[test]
    fn messages_without_id_or_content_are_dropped() {
        let mut broken = web_message("", false, 1, "x", None);
        let conv = wa::Conversation {
            id: "1@s.whatsapp.net".into(),
            messages: vec![broken.clone(), {
                broken.message = MessageField::none();
                broken
            }],
            ..Default::default()
        };
        let chat = conversation(&conv, 0).unwrap();
        assert!(chat.messages.is_empty());
    }

    #[tokio::test]
    async fn rows_land_in_the_store_in_one_import() {
        let store = Store::open_in_memory().unwrap();
        let id = InstanceId::from_bytes(&[1; 12]);
        let key = ConnectKey::from_bytes(&[1; 32]);
        store
            .create_instance(
                id.clone(),
                crate::store::NewKey {
                    key_id: KeyId::from_bytes(&[1; 8]),
                    hash: key.hash(),
                    label: "k".into(),
                },
                1,
                4,
            )
            .await
            .unwrap();

        let chats = sync_to_chats(&sync(), 0);
        assert_eq!(
            store
                .import_history(id.clone(), chats.clone())
                .await
                .unwrap(),
            2
        );

        let summaries = store.chats(id.clone()).await.unwrap();
        assert_eq!(summaries.len(), 2);
        // Newest first: the group message is the latest.
        assert_eq!(summaries[0].jid, "123-456@g.us");
        assert!(summaries[0].is_group);
        assert_eq!(summaries[1].name.as_deref(), Some("Ayse"));
        assert_eq!(summaries[1].unread, 1);
        assert_eq!(
            summaries[1].last.as_ref().map(|m| m.id.as_str()),
            Some("M2")
        );

        let messages = store
            .messages(id.clone(), "905321234567@s.whatsapp.net".into(), None, 10)
            .await
            .unwrap();
        assert_eq!(
            messages.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(),
            ["M2", "M1"]
        );
        assert_eq!(messages[1].body, MessageBody::Text("selam".into()));

        // A second import of the same sync changes nothing.
        store.import_history(id.clone(), chats).await.unwrap();
        let again = store
            .messages(id, "905321234567@s.whatsapp.net".into(), None, 10)
            .await
            .unwrap();
        assert_eq!(again.len(), 2);
    }

    #[test]
    fn ticks_stars_reactions_and_push_names_come_along() {
        let mut mine = web_message("M", true, 10, "x", None);
        let info = mine.message.as_option_mut().unwrap();
        info.status = Some(WebStatus::READ);
        info.starred = Some(true);
        info.reactions = vec![wa::Reaction {
            key: MessageField::some(wa::MessageKey {
                from_me: Some(false),
                ..Default::default()
            }),
            text: Some("\u{1F44D}".into()),
            ..Default::default()
        }];
        let mut theirs = web_message("T", false, 11, "y", None);
        theirs.message.as_option_mut().unwrap().push_name = Some("Ayse".into());
        let conv = wa::Conversation {
            id: "1234@lid".into(),
            pn_jid: Some("905321234567@s.whatsapp.net".into()),
            archived: Some(true),
            pinned: Some(1_700_000_000),
            mute_end_time: Some(u64::MAX),
            messages: vec![mine, theirs],
            ..Default::default()
        };
        let chat = conversation(&conv, 0).unwrap();
        assert_eq!(
            chat.jid, "905321234567@s.whatsapp.net",
            "LID chats use the number"
        );
        assert_eq!(
            chat.settings,
            ChatSettings {
                archived: true,
                pinned: true,
                muted_until_ms: Some(MUTED_FOREVER),
                marked_unread: false,
            }
        );
        let m = &chat.messages[0];
        assert_eq!(m.extras.meta.status, Some(DeliveryStatus::Read));
        assert!(m.extras.meta.starred);
        assert_eq!(m.reactions.len(), 1);
        assert!(!m.reactions[0].from_me);
        assert_eq!(
            chat.messages[1].extras.meta.sender_name.as_deref(),
            Some("Ayse")
        );

        let sync = wa::HistorySync {
            pushnames: vec![
                wa::Pushname {
                    id: Some("905321234567@s.whatsapp.net".into()),
                    pushname: Some("Ayse".into()),
                },
                wa::Pushname {
                    id: Some("bad jid@@".into()),
                    pushname: Some("x".into()),
                },
                wa::Pushname {
                    id: Some("1@s.whatsapp.net".into()),
                    pushname: Some("-".into()),
                },
            ],
            ..Default::default()
        };
        assert_eq!(
            push_names(&sync),
            [("905321234567@s.whatsapp.net".to_owned(), "Ayse".to_owned())]
        );
    }

    #[test]
    fn mute_ends_in_seconds_or_millis() {
        let now = 1_700_000_000_000;
        assert_eq!(mute_end(None, now), None);
        assert_eq!(mute_end(Some(0), now), None);
        assert_eq!(mute_end(Some(1_800_000_000), now), Some(1_800_000_000_000));
        assert_eq!(
            mute_end(Some(1_800_000_000_000), now),
            Some(1_800_000_000_000)
        );
        assert_eq!(mute_end(Some(1_600_000_000), now), None, "already over");
        assert_eq!(mute_end(Some(u64::MAX), now), Some(MUTED_FOREVER));
    }
}
