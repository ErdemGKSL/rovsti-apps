//! Disappearing messages that are due (E6-WA R5, owner decision): a message whose `expires_ms` passed is deleted
//! with everything stored beside it. A kept message ("keep in chat") has no `expires_ms`, so it never comes up here.

use rusqlite::params;

use super::{InstanceId, Store, StoreError};

/// Most messages one purge deletes; the next run takes the rest.
pub const PURGE_BATCH: usize = 2_000;

/// One message a purge deleted: its media file goes too.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExpiredMessage {
    pub instance: InstanceId,
    pub chat: String,
    pub id: String,
}

/// The tables keyed by `(instance_id, chat, id)` of a message besides `messages` itself.
const BESIDE: &[&str] = &[
    "reactions",
    "receipts",
    "message_edits",
    "pending_changes",
    "poll_votes",
];

impl Store {
    /// Deletes every message that disappeared by `now_ms` (at most [`PURGE_BATCH`]), with its reactions, receipts,
    /// edits and waiting changes, in one transaction. Answers what went, so the media cache can drop the files.
    pub async fn purge_expired_messages(
        &self,
        now_ms: i64,
    ) -> Result<Vec<ExpiredMessage>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let due = {
                let mut stmt = tx.prepare(
                    "SELECT instance_id, chat, id FROM messages
                     WHERE expires_ms IS NOT NULL AND expires_ms <= ?1
                     ORDER BY expires_ms LIMIT ?2",
                )?;
                stmt.query_map(params![now_ms, PURGE_BATCH as i64], |r| {
                    Ok(ExpiredMessage {
                        instance: InstanceId::new(r.get::<_, String>(0)?),
                        chat: r.get(1)?,
                        id: r.get(2)?,
                    })
                })?
                .collect::<Result<Vec<_>, _>>()?
            };
            for gone in &due {
                let key = params![gone.instance.as_str(), gone.chat, gone.id];
                for table in BESIDE {
                    tx.execute(
                        &format!(
                            "DELETE FROM {table} WHERE instance_id = ?1 AND chat = ?2 AND id = ?3"
                        ),
                        key,
                    )?;
                }
                tx.execute(
                    "DELETE FROM messages WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
                    key,
                )?;
            }
            tx.commit()?;
            Ok(due)
        })
        .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::{MessageExtras, NewKey};
    use rosty_wa_proto::{ConnectKey, KeyId, MessageBody, MessageMeta, Reaction, WaMessage};

    const CHAT: &str = "100000000011@s.whatsapp.net";

    fn id() -> InstanceId {
        InstanceId::from_bytes(&[1; 12])
    }

    async fn store() -> Store {
        let store = Store::open_in_memory().unwrap();
        let key = ConnectKey::from_bytes(&[1; 32]);
        store
            .create_instance(
                id(),
                NewKey {
                    key_id: KeyId::from_bytes(&[1; 8]),
                    hash: key.hash(),
                    label: "k".into(),
                },
                1,
                4,
            )
            .await
            .unwrap();
        store
    }

    fn message(id: &str, ts: i64) -> WaMessage {
        WaMessage {
            id: id.into(),
            chat: CHAT.into(),
            sender: None,
            from_me: false,
            timestamp_ms: ts,
            body: MessageBody::Text(format!("hello {id}")),
            reactions: vec![],
            chat_alt: None,
            sender_alt: None,
        }
    }

    fn timed(seconds: Option<u32>) -> MessageExtras {
        MessageExtras {
            meta: MessageMeta {
                expires_s: seconds,
                ..Default::default()
            },
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn only_due_messages_go_with_what_hangs_on_them() {
        let store = store().await;
        // Due at 61 000, due at 2 060 000, never.
        for (msg, extras) in [
            (message("due", 1_000), timed(Some(60))),
            (message("later", 2_000_000), timed(Some(60))),
            (message("plain", 1_000), timed(None)),
        ] {
            store.insert_message_full(id(), msg, extras).await.unwrap();
        }
        store
            .set_reaction(
                id(),
                CHAT.into(),
                "due".into(),
                Reaction {
                    sender: Some("100000000012@s.whatsapp.net".into()),
                    emoji: "👍".into(),
                    from_me: false,
                    sender_alt: None,
                },
            )
            .await
            .unwrap();

        assert_eq!(store.purge_expired_messages(60_999).await.unwrap(), vec![]);
        let gone = store.purge_expired_messages(61_000).await.unwrap();
        assert_eq!(
            gone,
            vec![ExpiredMessage {
                instance: id(),
                chat: CHAT.into(),
                id: "due".into()
            }]
        );
        let left: Vec<String> = store
            .messages(id(), CHAT.into(), None, 10)
            .await
            .unwrap()
            .into_iter()
            .map(|m| m.id)
            .collect();
        assert_eq!(left, vec!["later".to_owned(), "plain".to_owned()]);
        let conn = super::super::lock(&store.conn);
        let reactions: i64 = conn
            .query_row("SELECT COUNT(*) FROM reactions", [], |r| r.get(0))
            .unwrap();
        assert_eq!(reactions, 0, "the reaction went with its message");
        drop(conn);
        // Nothing is due twice.
        assert_eq!(store.purge_expired_messages(61_000).await.unwrap(), vec![]);
    }
}
