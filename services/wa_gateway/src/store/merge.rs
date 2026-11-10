//! Merging a chat stored under a WhatsApp LID into its phone-number chat.

use rusqlite::{Transaction, params};

use super::{InstanceId, Store, StoreError};

/// What [`Store::merge_chat`] did.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct MergeReport {
    /// Messages that now live under the surviving chat.
    pub moved: usize,
    /// Messages dropped because the surviving chat had them already.
    pub deduped: usize,
    /// False when there was nothing to merge.
    pub merged: bool,
}

impl Store {
    /// Chat ids of `instance` that are LIDs (`...@lid`).
    pub async fn lid_chats(&self, instance: InstanceId) -> Result<Vec<String>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT jid FROM chats WHERE instance_id = ?1 AND jid LIKE '%@lid' ORDER BY jid",
            )?;
            let rows = stmt.query_map([instance.as_str()], |r| r.get::<_, String>(0))?;
            Ok(rows.collect::<Result<Vec<_>, _>>()?)
        })
        .await
    }

    /// Moves everything stored under chat `from` to chat `into`, in one
    /// transaction. Rows `into` has already win; `from` is removed.
    /// A no-op when the ids are equal or nothing is stored under `from`.
    pub async fn merge_chat(
        &self,
        instance: InstanceId,
        from: String,
        into: String,
    ) -> Result<MergeReport, StoreError> {
        if from == into {
            return Ok(MergeReport::default());
        }
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let report = merge(&tx, &instance, &from, &into)?;
            tx.commit()?;
            Ok(report)
        })
        .await
    }
}

fn merge(
    tx: &Transaction<'_>,
    instance: &InstanceId,
    from: &str,
    into: &str,
) -> Result<MergeReport, StoreError> {
    let i = instance.as_str();
    let has_rows = |table: &str, col: &str| -> Result<bool, StoreError> {
        Ok(tx.query_row(
            &format!("SELECT EXISTS(SELECT 1 FROM {table} WHERE instance_id = ?1 AND {col} = ?2)"),
            params![i, from],
            |r| r.get::<_, bool>(0),
        )?)
    };
    let stored = has_rows("chats", "jid")?
        || has_rows("messages", "chat")?
        || has_rows("chat_settings", "jid")?
        || has_rows("scheduled", "chat")?;
    if !stored {
        return Ok(MergeReport::default());
    }

    let before: i64 = tx.query_row(
        "SELECT COUNT(*) FROM messages WHERE instance_id = ?1 AND chat = ?2",
        params![i, from],
        |r| r.get(0),
    )?;
    let moved = tx.execute(
        "INSERT OR IGNORE INTO messages
            (instance_id, chat, id, sender, from_me, ts_ms, body_json, raw_proto,
             meta_json, secret, thumb, deleted_at, deleted_by)
         SELECT instance_id, ?3, id, sender, from_me, ts_ms, body_json, raw_proto,
                meta_json, secret, thumb, deleted_at, deleted_by
         FROM messages WHERE instance_id = ?1 AND chat = ?2",
        params![i, from, into],
    )?;
    tx.execute(
        "DELETE FROM messages WHERE instance_id = ?1 AND chat = ?2",
        params![i, from],
    )?;

    tx.execute(
        "INSERT OR IGNORE INTO reactions (instance_id, chat, id, sender, from_me, emoji)
         SELECT instance_id, ?3, id, sender, from_me, emoji
         FROM reactions WHERE instance_id = ?1 AND chat = ?2",
        params![i, from, into],
    )?;
    tx.execute(
        "DELETE FROM reactions WHERE instance_id = ?1 AND chat = ?2",
        params![i, from],
    )?;
    tx.execute(
        "INSERT OR IGNORE INTO receipts (instance_id, chat, id, participant, status)
         SELECT instance_id, ?3, id, participant, status
         FROM receipts WHERE instance_id = ?1 AND chat = ?2",
        params![i, from, into],
    )?;
    tx.execute(
        "DELETE FROM receipts WHERE instance_id = ?1 AND chat = ?2",
        params![i, from],
    )?;
    tx.execute(
        "INSERT OR IGNORE INTO message_edits (instance_id, chat, id, edited_at, previous_text)
         SELECT instance_id, ?3, id, edited_at, previous_text
         FROM message_edits WHERE instance_id = ?1 AND chat = ?2",
        params![i, from, into],
    )?;
    tx.execute(
        "DELETE FROM message_edits WHERE instance_id = ?1 AND chat = ?2",
        params![i, from],
    )?;

    // Group authors: the same person may appear as a LID in any chat.
    tx.execute(
        "UPDATE messages SET sender = ?3 WHERE instance_id = ?1 AND sender = ?2",
        params![i, from, into],
    )?;
    tx.execute(
        "UPDATE messages SET deleted_by = ?3 WHERE instance_id = ?1 AND deleted_by = ?2",
        params![i, from, into],
    )?;
    tx.execute(
        "UPDATE OR IGNORE reactions SET sender = ?3 WHERE instance_id = ?1 AND sender = ?2",
        params![i, from, into],
    )?;
    tx.execute(
        "DELETE FROM reactions WHERE instance_id = ?1 AND sender = ?2",
        params![i, from],
    )?;
    tx.execute(
        "UPDATE OR IGNORE receipts SET participant = ?3
         WHERE instance_id = ?1 AND participant = ?2",
        params![i, from, into],
    )?;
    tx.execute(
        "DELETE FROM receipts WHERE instance_id = ?1 AND participant = ?2",
        params![i, from],
    )?;

    // Chat row: combine counters when both exist, else rename.
    let target_exists = tx.query_row(
        "SELECT EXISTS(SELECT 1 FROM chats WHERE instance_id = ?1 AND jid = ?2)",
        params![i, into],
        |r| r.get::<_, bool>(0),
    )?;
    if target_exists {
        tx.execute(
            "UPDATE chats SET
                unread = unread + COALESCE((SELECT unread FROM chats WHERE instance_id = ?1 AND jid = ?2), 0),
                last_ms = MAX(last_ms, COALESCE((SELECT last_ms FROM chats WHERE instance_id = ?1 AND jid = ?2), 0)),
                name = COALESCE(name, (SELECT name FROM chats WHERE instance_id = ?1 AND jid = ?2))
             WHERE instance_id = ?1 AND jid = ?3",
            params![i, from, into],
        )?;
        tx.execute(
            "DELETE FROM chats WHERE instance_id = ?1 AND jid = ?2",
            params![i, from],
        )?;
    } else {
        tx.execute(
            "UPDATE chats SET jid = ?3 WHERE instance_id = ?1 AND jid = ?2",
            params![i, from, into],
        )?;
    }

    // Settings: the surviving chat's row wins.
    tx.execute(
        "INSERT OR IGNORE INTO chat_settings
            (instance_id, jid, archived, pinned, muted_until_ms, marked_unread)
         SELECT instance_id, ?3, archived, pinned, muted_until_ms, marked_unread
         FROM chat_settings WHERE instance_id = ?1 AND jid = ?2",
        params![i, from, into],
    )?;
    tx.execute(
        "DELETE FROM chat_settings WHERE instance_id = ?1 AND jid = ?2",
        params![i, from],
    )?;

    // Profile: fill what the phone-number profile lacks, then drop the LID one.
    tx.execute(
        "INSERT OR IGNORE INTO profiles (instance_id, jid) SELECT ?1, ?3
         WHERE EXISTS (SELECT 1 FROM profiles WHERE instance_id = ?1 AND jid = ?2)",
        params![i, from, into],
    )?;
    tx.execute(
        "UPDATE profiles SET
            push_name = COALESCE(push_name,
                (SELECT push_name FROM profiles WHERE instance_id = ?1 AND jid = ?2)),
            contact_name = COALESCE(contact_name,
                (SELECT contact_name FROM profiles WHERE instance_id = ?1 AND jid = ?2))
         WHERE instance_id = ?1 AND jid = ?3",
        params![i, from, into],
    )?;
    tx.execute(
        "DELETE FROM profiles WHERE instance_id = ?1 AND jid = ?2",
        params![i, from],
    )?;

    tx.execute(
        "UPDATE scheduled SET chat = ?3 WHERE instance_id = ?1 AND chat = ?2",
        params![i, from, into],
    )?;

    Ok(MergeReport {
        moved,
        deduped: usize::try_from(before).unwrap_or(0).saturating_sub(moved),
        merged: true,
    })
}

#[cfg(test)]
mod tests {
    use rovsti_wa_proto::{ConnectKey, KeyId, MessageBody, WaMessage};

    use super::*;
    use crate::store::{ChatChange, NewKey, ProfileChange};

    const LID: &str = "96413459456093@lid";
    const PN: &str = "905551112233@s.whatsapp.net";

    fn id() -> InstanceId {
        InstanceId::from_bytes(&[1; 12])
    }

    async fn store() -> Store {
        let store = Store::open_in_memory().unwrap();
        let key = ConnectKey::from_bytes(&[1; 32]);
        let new = NewKey {
            key_id: KeyId::from_bytes(&[1; 8]),
            hash: key.hash(),
            label: "a".into(),
        };
        store.create_instance(id(), new, 10, 4).await.unwrap();
        store
    }

    fn msg(chat: &str, mid: &str, ts: i64) -> WaMessage {
        WaMessage {
            id: mid.into(),
            chat: chat.into(),
            sender: None,
            from_me: false,
            timestamp_ms: ts,
            body: MessageBody::Text(format!("hi {mid}")),
            reactions: vec![],
        }
    }

    async fn add(store: &Store, chat: &str, mid: &str, ts: i64) {
        assert!(
            store
                .insert_message(id(), msg(chat, mid, ts), None)
                .await
                .unwrap()
        );
    }

    #[tokio::test]
    async fn deleted_marks_and_edit_history_move_with_the_messages() {
        use crate::store::MessageChange;
        let s = store().await;
        add(&s, LID, "a", 100).await;
        add(&s, LID, "b", 200).await;
        let edit = MessageChange::Edit {
            body: MessageBody::Text("edited".into()),
            edited_ms: 150,
        };
        s.change_message(id(), LID.into(), "a".into(), edit)
            .await
            .unwrap();
        let revoke = MessageChange::Revoke {
            by: LID.into(),
            at_ms: 250,
        };
        s.change_message(id(), LID.into(), "b".into(), revoke)
            .await
            .unwrap();
        s.merge_chat(id(), LID.into(), PN.into()).await.unwrap();
        let page = s.messages_rich(id(), PN.into(), None, 10).await.unwrap();
        let (b, a) = (&page[0], &page[1]);
        assert!(b.meta.deleted);
        assert_eq!(
            (b.meta.deleted_at, b.meta.deleted_by.as_deref()),
            (Some(250), Some(PN))
        );
        assert_eq!(a.meta.edit_history.len(), 1);
        assert_eq!(a.meta.edit_history[0].text, "hi a");
    }

    #[tokio::test]
    async fn moves_messages_in_time_order_and_removes_the_lid_chat() {
        let s = store().await;
        add(&s, PN, "b", 200).await;
        add(&s, LID, "a", 100).await;
        add(&s, LID, "c", 300).await;
        assert_eq!(s.lid_chats(id()).await.unwrap(), vec![LID.to_owned()]);

        let r = s.merge_chat(id(), LID.into(), PN.into()).await.unwrap();
        assert_eq!(
            r,
            MergeReport {
                moved: 2,
                deduped: 0,
                merged: true
            }
        );
        let got = s.messages(id(), PN.into(), None, 10).await.unwrap();
        let ids: Vec<_> = got.iter().map(|m| m.id.as_str()).collect();
        assert_eq!(ids, ["c", "b", "a"]);
        assert!(s.lid_chats(id()).await.unwrap().is_empty());
        let jids: Vec<_> = s
            .chats(id())
            .await
            .unwrap()
            .into_iter()
            .map(|c| c.jid)
            .collect();
        assert_eq!(jids, [PN]);
        let rich: Vec<_> = s
            .chats_rich(id())
            .await
            .unwrap()
            .into_iter()
            .map(|c| c.chat.jid)
            .collect();
        assert_eq!(rich, [PN]);
        assert!(
            s.messages(id(), LID.into(), None, 10)
                .await
                .unwrap()
                .is_empty()
        );
    }

    #[tokio::test]
    async fn duplicate_ids_are_kept_once_and_unread_is_summed() {
        let s = store().await;
        add(&s, PN, "same", 100).await;
        add(&s, LID, "same", 100).await;
        add(&s, LID, "x", 150).await;
        let r = s.merge_chat(id(), LID.into(), PN.into()).await.unwrap();
        assert_eq!((r.moved, r.deduped), (1, 1));
        assert_eq!(
            s.messages(id(), PN.into(), None, 10).await.unwrap().len(),
            2
        );
        let chats = s.chats(id()).await.unwrap();
        assert_eq!(chats.len(), 1);
        assert_eq!(chats[0].unread, 3);
    }

    #[tokio::test]
    async fn settings_and_profile_are_carried_over() {
        let s = store().await;
        add(&s, LID, "a", 100).await;
        s.change_chat(id(), LID.into(), ChatChange::Pinned(true))
            .await
            .unwrap();
        s.change_profile(id(), LID.into(), ProfileChange::PushName("Annem".into()), 5)
            .await
            .unwrap();
        s.merge_chat(id(), LID.into(), PN.into()).await.unwrap();
        let rich = s.chats_rich(id()).await.unwrap();
        assert_eq!(rich.len(), 1);
        assert_eq!(rich[0].chat.jid, PN);
        assert!(rich[0].meta.pinned);
        let p = s.profiles(id(), vec![PN.into(), LID.into()]).await.unwrap();
        assert_eq!(p.len(), 1);
        assert_eq!(p[0].push_name.as_deref(), Some("Annem"));
    }

    #[tokio::test]
    async fn second_call_is_a_no_op() {
        let s = store().await;
        add(&s, PN, "b", 200).await;
        add(&s, LID, "a", 100).await;
        assert!(
            s.merge_chat(id(), LID.into(), PN.into())
                .await
                .unwrap()
                .merged
        );
        let r = s.merge_chat(id(), LID.into(), PN.into()).await.unwrap();
        assert_eq!(r, MergeReport::default());
        assert_eq!(
            s.messages(id(), PN.into(), None, 10).await.unwrap().len(),
            2
        );
        assert_eq!(
            s.merge_chat(id(), PN.into(), PN.into()).await.unwrap(),
            MergeReport::default()
        );
    }

    #[tokio::test]
    async fn missing_target_is_renamed() {
        let s = store().await;
        add(&s, LID, "a", 100).await;
        let r = s.merge_chat(id(), LID.into(), PN.into()).await.unwrap();
        assert_eq!((r.moved, r.merged), (1, true));
        let chats = s.chats(id()).await.unwrap();
        assert_eq!(chats.len(), 1);
        assert_eq!(chats[0].jid, PN);
        assert_eq!(chats[0].unread, 1);
    }
}
