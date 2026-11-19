//! Poll votes (schema v12, E6-WA R5): each voter's newest vote of a poll, kept encrypted until the poll is known,
//! then as the option names they picked. Counting is `rosty_core::wa_rich::tally`.

use rosty_core::wa_rich::Vote;
use rusqlite::{OptionalExtension, Transaction, params};

use super::{InstanceId, Store, StoreError};

/// A vote to record: opened (`options`) or still encrypted (`encrypted`).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct NewVote {
    /// As chats and authors name people (the number first, else the LID).
    pub voter: String,
    pub voter_alt: Option<String>,
    pub at_ms: i64,
    /// The picked option names; `None` while the vote is encrypted (an empty list takes the vote back).
    pub options: Option<Vec<String>>,
    /// `(encPayload, encIv)` of a vote not opened yet.
    pub encrypted: Option<(Vec<u8>, Vec<u8>)>,
}

/// A stored vote.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StoredVote {
    pub voter: String,
    pub voter_alt: Option<String>,
    pub at_ms: i64,
    pub options: Option<Vec<String>>,
    pub encrypted: Option<(Vec<u8>, Vec<u8>)>,
}

impl StoredVote {
    /// The opened vote, for counting; `None` while encrypted.
    pub fn vote(&self) -> Option<Vote> {
        Some(Vote {
            voter: self.voter.clone(),
            voter_alt: self.voter_alt.clone(),
            options: self.options.clone()?,
            at_ms: self.at_ms,
        })
    }
}

/// What opening and counting a poll's votes needs from its message.
#[derive(Clone, Debug, PartialEq)]
pub struct PollSource {
    pub poll: rosty_wa_proto::Poll,
    pub secret: Option<Vec<u8>>,
    pub from_me: bool,
    /// The author (groups), as stored.
    pub sender: Option<String>,
    /// The chat and author ids the poll arrived under.
    pub wire_chat: Option<String>,
    pub wire_sender: Option<String>,
    /// This account's ids: its number JID and its LID.
    pub own_jid: Option<String>,
    pub own_lid: Option<String>,
}

/// Stores `vote` unless the voter has a newer one. True when it was stored.
pub(super) fn upsert_vote(
    tx: &Transaction<'_>,
    instance: &InstanceId,
    chat: &str,
    poll_id: &str,
    vote: &NewVote,
) -> Result<bool, StoreError> {
    let options = vote
        .options
        .as_ref()
        .map(serde_json::to_string)
        .transpose()?;
    let (payload, iv) = match &vote.encrypted {
        Some((payload, iv)) => (Some(payload.as_slice()), Some(iv.as_slice())),
        None => (None, None),
    };
    let n = tx.execute(
        "INSERT INTO poll_votes
            (instance_id, chat, id, voter, voter_alt, ts_ms, enc_payload, enc_iv, options_json)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
         ON CONFLICT (instance_id, chat, id, voter) DO UPDATE SET
            voter_alt = COALESCE(excluded.voter_alt, voter_alt), ts_ms = excluded.ts_ms,
            enc_payload = excluded.enc_payload, enc_iv = excluded.enc_iv,
            options_json = excluded.options_json
         WHERE excluded.ts_ms >= poll_votes.ts_ms",
        params![
            instance.as_str(),
            chat,
            poll_id,
            vote.voter,
            vote.voter_alt,
            vote.at_ms,
            payload,
            iv,
            options
        ],
    )?;
    Ok(n > 0)
}

impl Store {
    /// Records one vote (the newest per voter wins). True when it was stored.
    pub async fn record_vote(
        &self,
        instance: InstanceId,
        chat: String,
        poll_id: String,
        vote: NewVote,
    ) -> Result<bool, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let stored = upsert_vote(&tx, &instance, &chat, &poll_id, &vote)?;
            tx.commit()?;
            Ok(stored)
        })
        .await
    }

    /// The stored votes of a poll, in the order they were cast.
    pub async fn poll_votes(
        &self,
        instance: InstanceId,
        chat: String,
        poll_id: String,
    ) -> Result<Vec<StoredVote>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT voter, voter_alt, ts_ms, options_json, enc_payload, enc_iv FROM poll_votes
                 WHERE instance_id = ?1 AND chat = ?2 AND id = ?3 ORDER BY ts_ms, voter",
            )?;
            let rows = stmt
                .query_map(params![instance.as_str(), chat, poll_id], |r| {
                    Ok((
                        r.get::<_, String>(0)?,
                        r.get::<_, Option<String>>(1)?,
                        r.get::<_, i64>(2)?,
                        r.get::<_, Option<String>>(3)?,
                        r.get::<_, Option<Vec<u8>>>(4)?,
                        r.get::<_, Option<Vec<u8>>>(5)?,
                    ))
                })?
                .collect::<Result<Vec<_>, _>>()?;
            rows.into_iter()
                .map(|(voter, voter_alt, at_ms, options, payload, iv)| {
                    Ok(StoredVote {
                        voter,
                        voter_alt,
                        at_ms,
                        options: options.map(|o| serde_json::from_str(&o)).transpose()?,
                        encrypted: payload.zip(iv),
                    })
                })
                .collect()
        })
        .await
    }

    /// Stores the opened options of `voter`'s vote cast at `at_ms` (a newer vote that came meanwhile stays).
    pub async fn open_vote(
        &self,
        instance: InstanceId,
        chat: String,
        poll_id: String,
        voter: String,
        at_ms: i64,
        options: Vec<String>,
    ) -> Result<(), StoreError> {
        self.run(move |conn| {
            conn.execute(
                "UPDATE poll_votes SET options_json = ?6, enc_payload = NULL, enc_iv = NULL
                 WHERE instance_id = ?1 AND chat = ?2 AND id = ?3 AND voter = ?4 AND ts_ms = ?5",
                params![
                    instance.as_str(),
                    chat,
                    poll_id,
                    voter,
                    at_ms,
                    serde_json::to_string(&options)?
                ],
            )?;
            Ok(())
        })
        .await
    }

    /// The poll `poll_id` of `chat` with what opening its votes needs; `None` when no such poll is stored.
    pub async fn poll_source(
        &self,
        instance: InstanceId,
        chat: String,
        poll_id: String,
    ) -> Result<Option<PollSource>, StoreError> {
        self.run(move |conn| {
            let row = conn
                .query_row(
                    "SELECT m.meta_json, m.secret, m.from_me, m.sender, m.wire_chat, m.wire_sender,
                            i.own_jid, i.own_lid
                     FROM messages m JOIN instances i ON i.id = m.instance_id
                     WHERE m.instance_id = ?1 AND m.chat = ?2 AND m.id = ?3",
                    params![instance.as_str(), chat, poll_id],
                    |r| {
                        Ok((
                            r.get::<_, Option<String>>(0)?,
                            r.get::<_, Option<Vec<u8>>>(1)?,
                            r.get::<_, bool>(2)?,
                            r.get::<_, Option<String>>(3)?,
                            r.get::<_, Option<String>>(4)?,
                            r.get::<_, Option<String>>(5)?,
                            r.get::<_, Option<String>>(6)?,
                            r.get::<_, Option<String>>(7)?,
                        ))
                    },
                )
                .optional()?;
            let Some((meta, secret, from_me, sender, wire_chat, wire_sender, own_jid, own_lid)) =
                row
            else {
                return Ok(None);
            };
            let meta = super::rich::meta_from(meta)?;
            Ok(meta.poll.map(|poll| PollSource {
                poll,
                secret: secret.filter(|s| !s.is_empty()),
                from_me,
                sender,
                wire_chat,
                wire_sender,
                own_jid,
                own_lid,
            }))
        })
        .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::{MessageExtras, NewKey};
    use rosty_wa_proto::{
        ConnectKey, KeyId, MessageBody, MessageMeta, Poll, PollOption, WaMessage,
    };

    const CHAT: &str = "120363000000000031@g.us";
    const ALI: &str = "100000000031@s.whatsapp.net";

    fn id() -> InstanceId {
        InstanceId::from_bytes(&[4; 12])
    }

    async fn store() -> Store {
        let store = Store::open_in_memory().unwrap();
        let key = ConnectKey::from_bytes(&[4; 32]);
        store
            .create_instance(
                id(),
                NewKey {
                    key_id: KeyId::from_bytes(&[4; 8]),
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

    fn vote(at_ms: i64, options: Option<&[&str]>) -> NewVote {
        NewVote {
            voter: ALI.into(),
            voter_alt: None,
            at_ms,
            options: options.map(|o| o.iter().map(|s| (*s).to_owned()).collect()),
            encrypted: options.is_none().then(|| (vec![1, 2], vec![3])),
        }
    }

    #[tokio::test]
    async fn the_newest_vote_of_a_voter_stays() {
        let store = store().await;
        let record = |v| store.record_vote(id(), CHAT.into(), "P".into(), v);
        assert!(record(vote(10, Some(&["A"]))).await.unwrap());
        assert!(record(vote(20, Some(&["B"]))).await.unwrap());
        assert!(!record(vote(15, Some(&["A"]))).await.unwrap(), "older");
        let votes = store
            .poll_votes(id(), CHAT.into(), "P".into())
            .await
            .unwrap();
        assert_eq!(votes.len(), 1);
        assert_eq!(votes[0].options, Some(vec!["B".to_owned()]));
        assert_eq!(votes[0].vote().unwrap().at_ms, 20);
    }

    #[tokio::test]
    async fn an_early_encrypted_vote_waits_and_opens_later() {
        let store = store().await;
        store
            .record_vote(id(), CHAT.into(), "P".into(), vote(30, None))
            .await
            .unwrap();
        // No poll yet.
        assert_eq!(
            store
                .poll_source(id(), CHAT.into(), "P".into())
                .await
                .unwrap(),
            None
        );
        let votes = store
            .poll_votes(id(), CHAT.into(), "P".into())
            .await
            .unwrap();
        assert_eq!(votes[0].encrypted, Some((vec![1, 2], vec![3])));
        assert_eq!(votes[0].vote(), None);

        let poll = Poll {
            question: "Q".into(),
            options: vec![PollOption {
                name: "A".into(),
                voters: vec![],
            }],
            selectable: 1,
            my_votes: vec![],
        };
        store
            .insert_message_full(
                id(),
                WaMessage {
                    id: "P".into(),
                    chat: CHAT.into(),
                    sender: Some(ALI.into()),
                    from_me: false,
                    timestamp_ms: 1,
                    body: MessageBody::Unsupported,
                    reactions: vec![],
                    chat_alt: None,
                    sender_alt: None,
                },
                MessageExtras {
                    meta: MessageMeta {
                        poll: Some(poll.clone()),
                        ..Default::default()
                    },
                    secret: Some(vec![9; 32]),
                    ..Default::default()
                },
            )
            .await
            .unwrap();
        let source = store
            .poll_source(id(), CHAT.into(), "P".into())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(source.poll, poll);
        assert_eq!(source.secret, Some(vec![9; 32]));
        assert_eq!(source.sender.as_deref(), Some(ALI));

        store
            .open_vote(
                id(),
                CHAT.into(),
                "P".into(),
                ALI.into(),
                30,
                vec!["A".into()],
            )
            .await
            .unwrap();
        let votes = store
            .poll_votes(id(), CHAT.into(), "P".into())
            .await
            .unwrap();
        assert_eq!(votes[0].options, Some(vec!["A".to_owned()]));
        assert_eq!(votes[0].encrypted, None);
    }
}
