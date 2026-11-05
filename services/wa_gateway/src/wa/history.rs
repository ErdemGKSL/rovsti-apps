//! History sync: WhatsApp's `HistorySync` protobuf to store rows.

use whatsapp_rust::prelude::*;
use whatsapp_rust::wacore::history_sync::HistorySyncError;
use whatsapp_rust::wacore::types::events::LazyHistorySync;
use whatsapp_rust::waproto::buffa::Message as _;

use super::convert::{self, Converted, Origin};
use crate::store::{HistoryChat, HistoryMessage};
use rovsti_wa_proto::MessageBody;

/// Chats that are not conversations the apps show.
fn is_skipped(jid: &str) -> bool {
    jid == "status@broadcast" || jid.ends_with("@newsletter") || jid.ends_with("@broadcast")
}

fn seconds_to_ms(seconds: u64) -> i64 {
    i64::try_from(seconds)
        .unwrap_or(i64::MAX / 1000)
        .saturating_mul(1000)
}

/// One history message. `None` when it has no id or content the apps show.
fn history_message(chat: &str, info: &wa::WebMessageInfo) -> Option<HistoryMessage> {
    let key = info.key.as_option()?;
    let id = key.id.as_deref().filter(|id| !id.is_empty())?;
    let content = info.message.as_option()?;
    let from_me = key.from_me.unwrap_or(false);
    let author = info
        .participant
        .as_deref()
        .or(key.participant.as_deref())
        .filter(|p| !p.is_empty())
        .and_then(|p| p.parse::<Jid>().ok())
        .and_then(|jid| convert::group_sender(chat, &jid));
    let origin = Origin {
        id,
        chat,
        sender: author.as_deref(),
        from_me,
        timestamp_ms: seconds_to_ms(info.message_timestamp.unwrap_or(0)),
    };
    match convert::convert(origin, content) {
        Converted::Message(message) => {
            let raw_proto =
                matches!(message.body, MessageBody::Media { .. }).then(|| content.encode_to_vec());
            Some(HistoryMessage { message, raw_proto })
        }
        // History carries each message's reactions separately; the live path
        // handles reaction messages.
        Converted::Reaction { .. } | Converted::Ignore => None,
    }
}

/// One conversation. `None` for chats the apps do not show.
pub fn conversation(conv: &wa::Conversation) -> Option<HistoryChat> {
    let jid = conv.id.as_str();
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
    })
}

/// Every conversation of a decoded sync.
pub fn sync_to_chats(sync: &wa::HistorySync) -> Vec<HistoryChat> {
    sync.conversations.iter().filter_map(conversation).collect()
}

/// Walks the lazily decoded sync one conversation at a time, so the whole
/// inflated blob never sits in memory. Blocking and CPU-heavy: call it from
/// `spawn_blocking`.
pub fn drain(lazy: &LazyHistorySync) -> Result<Vec<HistoryChat>, HistorySyncError> {
    let mut stream = lazy.stream();
    let mut chats = Vec::new();
    while let Some(conv) = stream.next_conversation()? {
        chats.extend(conversation(&conv));
    }
    Ok(chats)
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
        let chats = sync_to_chats(&sync());
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
        let chat = conversation(&conv).unwrap();
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

        let chats = sync_to_chats(&sync());
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
}
