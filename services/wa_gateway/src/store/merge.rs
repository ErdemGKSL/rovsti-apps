//! Merging a chat stored under a WhatsApp LID into its phone-number chat.

use rosty_wa_proto::MessageMeta;
use rusqlite::{Transaction, params};

use super::{InstanceId, Store, StoreError};

/// What [`Store::merge_chat`] did.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct MergeReport {
    /// Messages that now live under the surviving chat.
    pub moved: usize,
    /// Messages dropped because the surviving chat had them already.
    pub deduped: usize,
    /// A chat was stored under the old id (and now is not): the apps are
    /// told with `chat_merged`. False when the id had no chat of its own.
    pub merged: bool,
    /// Rows in any chat that named the old id as an author, a reactor, a
    /// receipt, a quoted author, a mention or a profile, and now name the
    /// new one.
    pub rewritten: usize,
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

    /// Moves everything stored under chat `from` to chat `into`, and renames
    /// the person `from` to `into` wherever a row of any chat names them, in
    /// one transaction. Rows `into` has already win; `from` is removed.
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
    let mut report = MergeReport::default();
    if stored {
        let (moved, deduped) = move_chat(tx, i, from, into)?;
        report = MergeReport {
            moved,
            deduped,
            merged: true,
            rewritten: 0,
        };
    }
    // The same person may be an author, a reactor, a receipt, a quote, a
    // mention or a profile in any chat, with no chat of their own.
    report.rewritten = rewrite_person(tx, i, from, into)?;
    Ok(report)
}

/// Moves the chat-level rows of `from` under `into`: messages with their
/// reactions, receipts and edits, the chat row, its settings and its
/// scheduled texts. Returns how many messages moved and how many `into` had
/// already.
fn move_chat(
    tx: &Transaction<'_>,
    i: &str,
    from: &str,
    into: &str,
) -> Result<(usize, usize), StoreError> {
    let before: i64 = tx.query_row(
        "SELECT COUNT(*) FROM messages WHERE instance_id = ?1 AND chat = ?2",
        params![i, from],
        |r| r.get(0),
    )?;
    // A moved row arrived under `from`: remember that as its wire chat.
    let moved = tx.execute(
        "INSERT OR IGNORE INTO messages
            (instance_id, chat, id, sender, from_me, ts_ms, body_json, raw_proto,
             meta_json, secret, thumb, deleted_at, deleted_by, wire_chat, wire_sender)
         SELECT instance_id, ?3, id, sender, from_me, ts_ms, body_json, raw_proto,
                meta_json, secret, thumb, deleted_at, deleted_by,
                COALESCE(wire_chat, ?2), wire_sender
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

    tx.execute(
        "UPDATE scheduled SET chat = ?3 WHERE instance_id = ?1 AND chat = ?2",
        params![i, from, into],
    )?;

    Ok((
        moved,
        usize::try_from(before).unwrap_or(0).saturating_sub(moved),
    ))
}

/// Renames the person `from` to `into` wherever a row names them, in every
/// chat: message authors (the id they arrived under is kept as the wire
/// author), who deleted a message, reaction senders, receipt participants,
/// quoted authors, mentions and the profile. Returns how many rows changed.
fn rewrite_person(
    tx: &Transaction<'_>,
    i: &str,
    from: &str,
    into: &str,
) -> Result<usize, StoreError> {
    let mut rewritten = tx.execute(
        "UPDATE messages SET wire_sender = COALESCE(wire_sender, sender), sender = ?3
         WHERE instance_id = ?1 AND sender = ?2",
        params![i, from, into],
    )?;
    rewritten += tx.execute(
        "UPDATE messages SET deleted_by = ?3 WHERE instance_id = ?1 AND deleted_by = ?2",
        params![i, from, into],
    )?;
    // One reaction per person and message: the one under `into` stays.
    rewritten += tx.execute(
        "UPDATE OR IGNORE reactions SET sender = ?3 WHERE instance_id = ?1 AND sender = ?2",
        params![i, from, into],
    )?;
    rewritten += tx.execute(
        "DELETE FROM reactions WHERE instance_id = ?1 AND sender = ?2",
        params![i, from],
    )?;
    // One receipt row per person and message, at the furthest state either
    // id reached.
    rewritten += tx.execute(
        "INSERT INTO receipts (instance_id, chat, id, participant, status)
         SELECT instance_id, chat, id, ?3, status FROM receipts
         WHERE instance_id = ?1 AND participant = ?2
         ON CONFLICT (instance_id, chat, id, participant)
         DO UPDATE SET status = MAX(status, excluded.status)",
        params![i, from, into],
    )?;
    tx.execute(
        "DELETE FROM receipts WHERE instance_id = ?1 AND participant = ?2",
        params![i, from],
    )?;
    rewritten += rewrite_meta(tx, i, from, into)?;

    // Profile: fill what the surviving profile lacks, then drop the other.
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
                (SELECT contact_name FROM profiles WHERE instance_id = ?1 AND jid = ?2)),
            verified_name = COALESCE(verified_name,
                (SELECT verified_name FROM profiles WHERE instance_id = ?1 AND jid = ?2)),
            business = MAX(business,
                COALESCE((SELECT business FROM profiles WHERE instance_id = ?1 AND jid = ?2), 0))
         WHERE instance_id = ?1 AND jid = ?3",
        params![i, from, into],
    )?;
    rewritten += tx.execute(
        "DELETE FROM profiles WHERE instance_id = ?1 AND jid = ?2",
        params![i, from],
    )?;
    Ok(rewritten)
}

/// Renames `from` to `into` in the quoted author and the mentions of every
/// stored message meta. Returns how many messages changed.
fn rewrite_meta(
    tx: &Transaction<'_>,
    i: &str,
    from: &str,
    into: &str,
) -> Result<usize, StoreError> {
    let rows: Vec<(String, String, String)> = {
        let mut stmt = tx.prepare(
            "SELECT chat, id, meta_json FROM messages
             WHERE instance_id = ?1 AND instr(meta_json, ?2) > 0",
        )?;
        stmt.query_map(params![i, from], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
            .collect::<Result<_, _>>()?
    };
    let mut changed = 0;
    for (chat, id, json) in rows {
        let mut meta: MessageMeta = serde_json::from_str(&json)?;
        if !rename_in_meta(&mut meta, from, into) {
            continue;
        }
        tx.execute(
            "UPDATE messages SET meta_json = ?4
             WHERE instance_id = ?1 AND chat = ?2 AND id = ?3",
            params![i, chat, id, serde_json::to_string(&meta)?],
        )?;
        changed += 1;
    }
    Ok(changed)
}

/// Renames `from` to `into` in the quoted author and the mentions of `meta`
/// (each person is mentioned once). True when something changed.
fn rename_in_meta(meta: &mut MessageMeta, from: &str, into: &str) -> bool {
    let mut changed = false;
    if let Some(quoted) = meta.quoted.as_mut()
        && quoted.sender.as_deref() == Some(from)
    {
        quoted.sender = Some(into.to_owned());
        changed = true;
    }
    if meta.mentions.iter().any(|m| m == from) {
        let mut seen = Vec::with_capacity(meta.mentions.len());
        for mention in std::mem::take(&mut meta.mentions) {
            let mention = if mention == from {
                into.to_owned()
            } else {
                mention
            };
            if !seen.contains(&mention) {
                seen.push(mention);
            }
        }
        meta.mentions = seen;
        changed = true;
    }
    changed
}

#[cfg(test)]
mod tests {
    use rosty_wa_proto::{ConnectKey, KeyId, MessageBody, WaMessage};

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
            chat_alt: None,
            sender_alt: None,
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
                merged: true,
                rewritten: 0,
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

    const GROUP: &str = "1-2@g.us";
    const OTHER: &str = "905550000002@s.whatsapp.net";

    fn group_msg(mid: &str, sender: &str, from_me: bool) -> WaMessage {
        WaMessage {
            sender: Some(sender.into()),
            from_me,
            ..msg(GROUP, mid, 100)
        }
    }

    /// A LID that is nobody's chat: only an author, a reactor, a receipt, a
    /// quoted author, a mention, a deleter and a profile in a group.
    #[tokio::test]
    async fn a_lid_without_a_chat_is_renamed_wherever_it_appears() {
        use crate::store::{GroupReceipt, MessageChange, MessageExtras};
        use rosty_wa_proto::{DeliveryStatus, MessageMeta, Quoted, Reaction};
        let s = store().await;
        s.insert_message(id(), group_msg("theirs", LID, false), None)
            .await
            .unwrap();
        s.insert_message(id(), group_msg("mine", OTHER, true), None)
            .await
            .unwrap();
        let quoting = MessageExtras {
            meta: MessageMeta {
                quoted: Some(Quoted {
                    id: "theirs".into(),
                    sender: Some(LID.into()),
                    preview: Some("hi".into()),
                }),
                mentions: vec![LID.into(), OTHER.into(), PN.into()],
                ..Default::default()
            },
            ..Default::default()
        };
        s.insert_message_full(id(), group_msg("reply", OTHER, false), quoting)
            .await
            .unwrap();
        let reaction = |sender: &str, emoji: &str| Reaction {
            sender: Some(sender.into()),
            from_me: false,
            emoji: emoji.into(),
            sender_alt: None,
        };
        s.set_reaction(id(), GROUP.into(), "mine".into(), reaction(LID, "a"))
            .await
            .unwrap();
        // They acked my message under the LID (delivered) and under the
        // number (read): one person, who read it.
        for (who, status) in [(LID, DeliveryStatus::Delivered), (PN, DeliveryStatus::Read)] {
            s.record_group_receipt(
                id(),
                GROUP.into(),
                GroupReceipt {
                    participant: who.into(),
                    ids: vec!["mine".into()],
                    status,
                    members: None,
                    own: Vec::new(),
                },
            )
            .await
            .unwrap();
        }
        let revoke = MessageChange::Revoke {
            by: LID.into(),
            at_ms: 9,
        };
        s.change_message(id(), GROUP.into(), "theirs".into(), revoke)
            .await
            .unwrap();
        s.change_profile(id(), LID.into(), ProfileChange::PushName("Annem".into()), 5)
            .await
            .unwrap();
        assert_eq!(s.lids_seen(id()).await.unwrap(), [LID]);
        assert!(s.lid_chats(id()).await.unwrap().is_empty());

        let r = s.merge_chat(id(), LID.into(), PN.into()).await.unwrap();
        assert!(!r.merged, "no chat was stored under the LID");
        assert_eq!((r.moved, r.deduped), (0, 0));
        assert!(r.rewritten > 0);
        assert!(s.lids_seen(id()).await.unwrap().is_empty());

        let page = s.messages_rich(id(), GROUP.into(), None, 10).await.unwrap();
        let by_id = |mid: &str| page.iter().find(|m| m.message.id == mid).unwrap();
        let theirs = by_id("theirs");
        assert_eq!(theirs.message.sender.as_deref(), Some(PN));
        assert_eq!(theirs.meta.deleted_by.as_deref(), Some(PN));
        // The id it arrived under is kept for keys sent back to WhatsApp,
        // and shown as the other form.
        assert_eq!(theirs.message.sender_alt.as_deref(), Some(LID));
        assert_eq!(
            s.wire_ids(id(), GROUP.into(), "theirs".into())
                .await
                .unwrap()
                .sender
                .as_deref(),
            Some(LID)
        );
        let reply = by_id("reply");
        assert_eq!(
            reply.meta.quoted.as_ref().unwrap().sender.as_deref(),
            Some(PN)
        );
        assert_eq!(
            reply.meta.quoted.as_ref().unwrap().preview.as_deref(),
            Some("hi")
        );
        assert_eq!(reply.meta.mentions, [PN, OTHER], "each person once");
        assert_eq!(by_id("mine").message.reactions, [reaction(PN, "a")]);
        let profiles = s.profiles(id(), vec![PN.into(), LID.into()]).await.unwrap();
        assert_eq!(profiles.len(), 1);
        assert_eq!(profiles[0].push_name.as_deref(), Some("Annem"));

        // One receipt row, at the furthest state either id reached: with one
        // other member the message is read.
        let moved = s
            .record_group_receipt(
                id(),
                GROUP.into(),
                GroupReceipt {
                    participant: PN.into(),
                    ids: vec!["mine".into()],
                    status: DeliveryStatus::Delivered,
                    members: Some(1),
                    own: Vec::new(),
                },
            )
            .await
            .unwrap();
        assert_eq!(moved[0].1.status, Some(DeliveryStatus::Read));

        // Again: nothing left to do.
        assert_eq!(
            s.merge_chat(id(), LID.into(), PN.into()).await.unwrap(),
            MergeReport::default()
        );
    }

    #[tokio::test]
    async fn the_same_persons_two_reactions_become_one() {
        use rosty_wa_proto::Reaction;
        let s = store().await;
        s.insert_message(id(), group_msg("m", OTHER, true), None)
            .await
            .unwrap();
        for (sender, emoji) in [(LID, "old"), (PN, "new")] {
            let reaction = Reaction {
                sender: Some(sender.into()),
                from_me: false,
                emoji: emoji.into(),
                sender_alt: None,
            };
            s.set_reaction(id(), GROUP.into(), "m".into(), reaction)
                .await
                .unwrap();
        }
        s.merge_chat(id(), LID.into(), PN.into()).await.unwrap();
        let m = s
            .message(id(), GROUP.into(), "m".into())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(m.reactions.len(), 1);
        assert_eq!(m.reactions[0].sender.as_deref(), Some(PN));
        assert_eq!(m.reactions[0].emoji, "new");
    }

    #[tokio::test]
    async fn moved_messages_remember_the_chat_they_arrived_under() {
        use crate::store::{MessageExtras, WireIds};
        let s = store().await;
        add(&s, LID, "old", 100).await;
        // One that already knows its wire chat keeps it.
        let kept = MessageExtras {
            wire_chat: Some("77@lid".into()),
            ..Default::default()
        };
        s.insert_message_full(id(), msg(LID, "kept", 110), kept)
            .await
            .unwrap();
        add(&s, PN, "pn", 120).await;
        s.merge_chat(id(), LID.into(), PN.into()).await.unwrap();
        let wire = |mid: &'static str| {
            let s = s.clone();
            async move { s.wire_ids(id(), PN.into(), mid.into()).await.unwrap() }
        };
        assert_eq!(wire("old").await.chat.as_deref(), Some(LID));
        assert_eq!(wire("kept").await.chat.as_deref(), Some("77@lid"));
        assert_eq!(wire("pn").await, WireIds::default());
        let page = s.messages(id(), PN.into(), None, 10).await.unwrap();
        let alt = |mid: &str| page.iter().find(|m| m.id == mid).unwrap().chat_alt.clone();
        assert_eq!(alt("old").as_deref(), Some(LID));
        assert_eq!(alt("pn"), None, "nobody told the gateway the pair");
    }

    #[test]
    fn mentions_and_quotes_are_renamed_exactly() {
        use rosty_wa_proto::Quoted;
        let mut meta = MessageMeta {
            quoted: Some(Quoted {
                id: "q".into(),
                sender: Some("123@lid".into()),
                preview: None,
            }),
            mentions: vec!["9123@lid".into(), "123@lid".into()],
            ..Default::default()
        };
        // An id that merely contains the LID is another person.
        assert!(!rename_in_meta(&mut meta, "23@lid", PN));
        assert!(rename_in_meta(&mut meta, "123@lid", PN));
        assert_eq!(meta.quoted.as_ref().unwrap().sender.as_deref(), Some(PN));
        assert_eq!(meta.mentions, ["9123@lid", PN]);
        assert!(!rename_in_meta(&mut meta, "123@lid", PN));
        assert!(!rename_in_meta(&mut MessageMeta::default(), "123@lid", PN));
    }
}
