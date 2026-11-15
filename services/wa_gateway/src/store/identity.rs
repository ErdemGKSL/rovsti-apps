//! The LID and phone-number pairs the gateway learned (schema v7), and the
//! "other form" of a stored id that the API hands out next to it.
//!
//! One row per LID: a LID belongs to one number at a time, while a number may
//! have had several LIDs. Every new or changed pair gets a fresh, larger
//! `cursor`, so `GET /v1/identity/mappings?since=` only has to return rows
//! above the caller's cursor.

use std::collections::HashMap;

use rosty_wa_proto::{IdentityMapping, IdentitySource, MessageMeta, is_lid_jid, is_pn_jid};
use rusqlite::{Connection, OptionalExtension, params};

use super::{InstanceId, Store, StoreError};

/// Most pairs one page of `GET /v1/identity/mappings` carries.
pub const MAX_IDENTITY_PAGE: u32 = 500;

/// A pair to record. `lid` and `pn` are bare (`<user>@lid`,
/// `<digits>@s.whatsapp.net`).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NewPair {
    pub lid: String,
    pub pn: String,
    pub source: IdentitySource,
    pub learned_ms: i64,
}

impl NewPair {
    pub fn new(
        lid: impl Into<String>,
        pn: impl Into<String>,
        source: IdentitySource,
        learned_ms: i64,
    ) -> Self {
        Self {
            lid: lid.into(),
            pn: pn.into(),
            source,
            learned_ms,
        }
    }
}

/// A pair that is new, or whose LID moved to another number.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LearnedPair {
    pub mapping: IdentityMapping,
    /// The number the LID belonged to before, when it changed.
    pub previous_pn: Option<String>,
}

/// One page of learned pairs.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct IdentityPage {
    pub items: Vec<IdentityMapping>,
    /// The cursor of the last item; `None` for an empty page.
    pub last: Option<i64>,
}

/// What recording a pair against the stored row decided.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Verdict {
    /// The LID is new.
    Insert,
    /// The LID now belongs to another number.
    Repoint,
    /// The same pair from a more trusted source: remember the source.
    Upgrade,
    /// Nothing to write: the same pair again, or a weaker source that
    /// disagrees with what a stronger one said.
    Keep,
}

/// Whether `new` replaces the stored pair `(pn, source, learned_ms)` of its
/// LID. A conflicting number wins when its source is trusted more, or as much
/// and is not older ([`IdentitySource::rank`]).
fn verdict(stored: Option<(&str, IdentitySource, i64)>, new: &NewPair) -> Verdict {
    let Some((pn, source, learned_ms)) = stored else {
        return Verdict::Insert;
    };
    let (old, fresh) = (source.rank(), new.source.rank());
    if pn == new.pn {
        return if fresh > old {
            Verdict::Upgrade
        } else {
            Verdict::Keep
        };
    }
    if fresh > old || (fresh == old && new.learned_ms >= learned_ms) {
        Verdict::Repoint
    } else {
        Verdict::Keep
    }
}

/// Whether `pair` names a LID and a phone-number JID, bare.
fn is_well_formed(pair: &NewPair) -> bool {
    let bare = |id: &str| !id.contains(':') && !id.starts_with('@');
    is_lid_jid(&pair.lid) && is_pn_jid(&pair.pn) && bare(&pair.lid) && bare(&pair.pn)
}

fn learn(
    conn: &Connection,
    instance: &InstanceId,
    pair: &NewPair,
) -> Result<Option<LearnedPair>, StoreError> {
    if !is_well_formed(pair) {
        return Ok(None);
    }
    let i = instance.as_str();
    let stored = conn
        .query_row(
            "SELECT pn, source, learned_ms FROM identity_pairs WHERE instance_id = ?1 AND lid = ?2",
            params![i, pair.lid],
            |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, i64>(2)?,
                ))
            },
        )
        .optional()?;
    let known = stored
        .as_ref()
        .map(|(pn, source, at)| (pn.as_str(), IdentitySource::from_wire(source), *at));
    let previous_pn = match verdict(known, pair) {
        Verdict::Keep => return Ok(None),
        Verdict::Upgrade => {
            conn.execute(
                "UPDATE identity_pairs SET source = ?3 WHERE instance_id = ?1 AND lid = ?2",
                params![i, pair.lid, pair.source.as_str()],
            )?;
            return Ok(None);
        }
        Verdict::Insert => None,
        Verdict::Repoint => {
            // A new row, so the pair gets a cursor above every reader's.
            conn.execute(
                "DELETE FROM identity_pairs WHERE instance_id = ?1 AND lid = ?2",
                params![i, pair.lid],
            )?;
            stored.map(|(pn, ..)| pn)
        }
    };
    conn.execute(
        "INSERT INTO identity_pairs (instance_id, lid, pn, source, learned_ms)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![i, pair.lid, pair.pn, pair.source.as_str(), pair.learned_ms],
    )?;
    Ok(Some(LearnedPair {
        mapping: IdentityMapping::new(&pair.lid, &pair.pn, pair.source, pair.learned_ms),
        previous_pn,
    }))
}

/// The phone-number JID stored for `lid`.
fn pn_of(
    conn: &Connection,
    instance: &InstanceId,
    lid: &str,
) -> Result<Option<String>, StoreError> {
    Ok(conn
        .query_row(
            "SELECT pn FROM identity_pairs WHERE instance_id = ?1 AND lid = ?2",
            params![instance.as_str(), lid],
            |r| r.get(0),
        )
        .optional()?)
}

/// The LID stored for `pn`: the one learned last when it had several.
fn lid_of(
    conn: &Connection,
    instance: &InstanceId,
    pn: &str,
) -> Result<Option<String>, StoreError> {
    Ok(conn
        .query_row(
            "SELECT lid FROM identity_pairs WHERE instance_id = ?1 AND pn = ?2
             ORDER BY cursor DESC LIMIT 1",
            params![instance.as_str(), pn],
            |r| r.get(0),
        )
        .optional()?)
}

/// Finds the other form of stored ids for one read, asking the table once
/// per id.
pub(super) struct Alts<'c> {
    conn: &'c Connection,
    instance: &'c InstanceId,
    seen: HashMap<String, Option<String>>,
}

impl<'c> Alts<'c> {
    pub(super) fn new(conn: &'c Connection, instance: &'c InstanceId) -> Self {
        Self {
            conn,
            instance,
            seen: HashMap::new(),
        }
    }

    fn stored(&mut self, id: &str) -> Result<Option<String>, StoreError> {
        if let Some(known) = self.seen.get(id) {
            return Ok(known.clone());
        }
        let found = if is_pn_jid(id) {
            lid_of(self.conn, self.instance, id)?
        } else if is_lid_jid(id) {
            pn_of(self.conn, self.instance, id)?
        } else {
            None
        };
        self.seen.insert(id.to_owned(), found.clone());
        Ok(found)
    }

    /// The other form of the person `id`: the id it arrived under (`wire`)
    /// when that is the other form, else what the table knows. `None` for
    /// groups and for a person known by one id only.
    pub(super) fn of(
        &mut self,
        id: &str,
        wire: Option<&str>,
    ) -> Result<Option<String>, StoreError> {
        let other_form = |wire: &&str| {
            (is_pn_jid(id) && is_lid_jid(wire)) || (is_lid_jid(id) && is_pn_jid(wire))
        };
        match wire.filter(other_form) {
            Some(wire) => Ok(Some(wire.to_owned())),
            None => self.stored(id),
        }
    }
}

/// Every LID in `meta`: the quoted author and the mentions.
fn meta_lids(meta: &MessageMeta) -> impl Iterator<Item = &str> {
    meta.quoted
        .iter()
        .filter_map(|q| q.sender.as_deref())
        .chain(meta.mentions.iter().map(String::as_str))
        .filter(|id| is_lid_jid(id))
}

impl Store {
    /// Records pairs in one transaction. Returns the ones that are new or
    /// whose LID moved to another number, in order. Ids that are not a bare
    /// LID and a bare phone-number JID are skipped.
    pub async fn learn_pairs(
        &self,
        instance: InstanceId,
        pairs: Vec<NewPair>,
    ) -> Result<Vec<LearnedPair>, StoreError> {
        if pairs.is_empty() {
            return Ok(Vec::new());
        }
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let mut learned = Vec::new();
            for pair in &pairs {
                learned.extend(learn(&tx, &instance, pair)?);
            }
            tx.commit()?;
            Ok(learned)
        })
        .await
    }

    /// The pairs learned or changed after the cursor `since`, oldest change
    /// first, at most `limit` (capped at [`MAX_IDENTITY_PAGE`]).
    pub async fn identity_page(
        &self,
        instance: InstanceId,
        since: i64,
        limit: u32,
    ) -> Result<IdentityPage, StoreError> {
        let limit = limit.clamp(1, MAX_IDENTITY_PAGE);
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT cursor, lid, pn, source, learned_ms FROM identity_pairs
                 WHERE instance_id = ?1 AND cursor > ?2 ORDER BY cursor LIMIT ?3",
            )?;
            let rows = stmt
                .query_map(params![instance.as_str(), since, limit], |r| {
                    Ok((
                        r.get::<_, i64>(0)?,
                        IdentityMapping {
                            lid: r.get(1)?,
                            pn: r.get(2)?,
                            source: r.get(3)?,
                            learned_ms: r.get(4)?,
                        },
                    ))
                })?
                .collect::<Result<Vec<_>, _>>()?;
            Ok(IdentityPage {
                last: rows.last().map(|(cursor, _)| *cursor),
                items: rows.into_iter().map(|(_, mapping)| mapping).collect(),
            })
        })
        .await
    }

    /// The phone-number JID the gateway learned for `lid`.
    pub async fn pn_of_lid(
        &self,
        instance: InstanceId,
        lid: String,
    ) -> Result<Option<String>, StoreError> {
        self.run(move |conn| pn_of(conn, &instance, &lid)).await
    }

    /// The LID the gateway learned for the phone-number JID `pn` (the latest
    /// when it had several).
    pub async fn lid_of_pn(
        &self,
        instance: InstanceId,
        pn: String,
    ) -> Result<Option<String>, StoreError> {
        self.run(move |conn| lid_of(conn, &instance, &pn)).await
    }

    /// Every LID something is stored under: chats, message authors, who
    /// deleted a message, reaction senders, receipt participants, quoted
    /// authors, mentions and profiles. Sorted, each once.
    pub async fn lids_seen(&self, instance: InstanceId) -> Result<Vec<String>, StoreError> {
        self.run(move |conn| {
            let i = instance.as_str();
            let mut found = std::collections::BTreeSet::new();
            for sql in [
                "SELECT jid FROM chats WHERE instance_id = ?1 AND jid LIKE '%@lid'",
                "SELECT DISTINCT sender FROM messages WHERE instance_id = ?1 AND sender LIKE '%@lid'",
                "SELECT DISTINCT deleted_by FROM messages
                 WHERE instance_id = ?1 AND deleted_by LIKE '%@lid'",
                "SELECT DISTINCT sender FROM reactions WHERE instance_id = ?1 AND sender LIKE '%@lid'",
                "SELECT DISTINCT participant FROM receipts
                 WHERE instance_id = ?1 AND participant LIKE '%@lid'",
                "SELECT jid FROM profiles WHERE instance_id = ?1 AND jid LIKE '%@lid'",
                "SELECT jid FROM chat_settings WHERE instance_id = ?1 AND jid LIKE '%@lid'",
            ] {
                let mut stmt = conn.prepare(sql)?;
                for lid in stmt.query_map([i], |r| r.get::<_, String>(0))? {
                    found.insert(lid?);
                }
            }
            let mut stmt = conn.prepare(
                "SELECT meta_json FROM messages
                 WHERE instance_id = ?1 AND meta_json LIKE '%@lid%'",
            )?;
            for json in stmt.query_map([i], |r| r.get::<_, String>(0))? {
                let meta: MessageMeta = serde_json::from_str(&json?)?;
                found.extend(meta_lids(&meta).map(str::to_owned));
            }
            Ok(found.into_iter().collect())
        })
        .await
    }
}

#[cfg(test)]
mod tests {
    use rosty_wa_proto::{
        ConnectKey, InstanceState, KeyId, MessageBody, Quoted, Reaction, WaMessage,
    };

    use super::*;
    use crate::store::{
        GroupReceipt, MIGRATIONS, MessageChange, MessageExtras, NewKey, ProfileChange, WireIds,
    };

    const LID: &str = "1234@lid";
    const PN: &str = "905550000001@s.whatsapp.net";
    const PN2: &str = "905550000002@s.whatsapp.net";

    fn id(n: u8) -> InstanceId {
        InstanceId::from_bytes(&[n; 12])
    }

    async fn store() -> Store {
        let store = Store::open_in_memory().unwrap();
        for n in [1u8, 2] {
            let key = ConnectKey::from_bytes(&[n; 32]);
            let new = NewKey {
                key_id: KeyId::from_bytes(&[n; 8]),
                hash: key.hash(),
                label: "k".into(),
            };
            store.create_instance(id(n), new, 1, 4).await.unwrap();
        }
        store
    }

    fn pair(lid: &str, pn: &str, source: IdentitySource, at: i64) -> NewPair {
        NewPair::new(lid, pn, source, at)
    }

    async fn learn_one(store: &Store, new: NewPair) -> Vec<LearnedPair> {
        store.learn_pairs(id(1), vec![new]).await.unwrap()
    }

    #[test]
    fn a_stronger_or_newer_source_repoints_a_lid() {
        use IdentitySource::{History, NumberChange, PeerMessage, Receipt, Usync};
        let new = |pn: &str, source, at| pair(LID, pn, source, at);
        assert_eq!(verdict(None, &new(PN, History, 1)), Verdict::Insert);
        // The same pair: nothing, unless the source is trusted more.
        assert_eq!(
            verdict(Some((PN, Usync, 5)), &new(PN, History, 9)),
            Verdict::Keep
        );
        assert_eq!(
            verdict(Some((PN, Usync, 5)), &new(PN, Usync, 9)),
            Verdict::Keep
        );
        assert_eq!(
            verdict(Some((PN, History, 5)), &new(PN, PeerMessage, 9)),
            Verdict::Upgrade
        );
        // Another number: the stronger source wins whatever the time.
        assert_eq!(
            verdict(Some((PN, History, 9)), &new(PN2, NumberChange, 1)),
            Verdict::Repoint
        );
        assert_eq!(
            verdict(Some((PN, PeerMessage, 1)), &new(PN2, History, 9)),
            Verdict::Keep
        );
        // Equal trust: the newer one, and a tie goes to the newcomer.
        assert_eq!(
            verdict(Some((PN, PeerMessage, 5)), &new(PN2, Receipt, 9)),
            Verdict::Repoint
        );
        assert_eq!(
            verdict(Some((PN, PeerMessage, 5)), &new(PN2, Receipt, 5)),
            Verdict::Repoint
        );
        assert_eq!(
            verdict(Some((PN, PeerMessage, 5)), &new(PN2, Receipt, 4)),
            Verdict::Keep
        );
    }

    #[tokio::test]
    async fn a_pair_is_learned_once() {
        let store = store().await;
        let learned = learn_one(&store, pair(LID, PN, IdentitySource::PeerMessage, 10)).await;
        assert_eq!(
            learned,
            [LearnedPair {
                mapping: IdentityMapping::new(LID, PN, IdentitySource::PeerMessage, 10),
                previous_pn: None,
            }]
        );
        // Again, from any source, at any time: nothing new.
        for source in IdentitySource::ALL {
            assert!(
                learn_one(&store, pair(LID, PN, source, 99))
                    .await
                    .is_empty()
            );
        }
        let page = store.identity_page(id(1), 0, 50).await.unwrap();
        assert_eq!(page.items.len(), 1);
        assert_eq!(page.items[0].learned_ms, 10, "the first sighting is kept");
        assert_eq!(
            store.pn_of_lid(id(1), LID.into()).await.unwrap().as_deref(),
            Some(PN)
        );
        assert_eq!(
            store.lid_of_pn(id(1), PN.into()).await.unwrap().as_deref(),
            Some(LID)
        );
        assert_eq!(store.pn_of_lid(id(1), "9@lid".into()).await.unwrap(), None);
    }

    #[tokio::test]
    async fn a_more_trusted_source_is_remembered_without_a_new_cursor() {
        let store = store().await;
        learn_one(&store, pair(LID, PN, IdentitySource::History, 10)).await;
        let before = store.identity_page(id(1), 0, 50).await.unwrap();
        assert!(
            learn_one(&store, pair(LID, PN, IdentitySource::Usync, 20))
                .await
                .is_empty()
        );
        let after = store.identity_page(id(1), 0, 50).await.unwrap();
        assert_eq!(after.last, before.last);
        assert_eq!(after.items[0].source, "usync");
        // A weaker one does not take it back.
        learn_one(&store, pair(LID, PN, IdentitySource::Other, 30)).await;
        let again = store.identity_page(id(1), 0, 50).await.unwrap();
        assert_eq!(again.items[0].source, "usync");
    }

    #[tokio::test]
    async fn a_changed_number_gets_a_new_cursor_and_names_the_old_number() {
        let store = store().await;
        learn_one(&store, pair(LID, PN, IdentitySource::PeerMessage, 10)).await;
        learn_one(&store, pair("5@lid", PN2, IdentitySource::Usync, 11)).await;
        let first = store.identity_page(id(1), 0, 50).await.unwrap();
        let cursor = first.last.unwrap();

        // A weaker source that disagrees is not believed.
        assert!(
            learn_one(&store, pair(LID, PN2, IdentitySource::History, 50))
                .await
                .is_empty()
        );
        let learned = learn_one(&store, pair(LID, PN2, IdentitySource::NumberChange, 20)).await;
        assert_eq!(learned.len(), 1);
        assert_eq!(learned[0].previous_pn.as_deref(), Some(PN));
        assert_eq!(learned[0].mapping.pn, PN2);

        // A reader that had everything sees exactly the change.
        let newer = store.identity_page(id(1), cursor, 50).await.unwrap();
        assert_eq!(
            newer.items,
            [IdentityMapping::new(
                LID,
                PN2,
                IdentitySource::NumberChange,
                20
            )]
        );
        assert!(newer.last.unwrap() > cursor);
        // One row per LID: the old pair is gone, the list has two rows.
        let all = store.identity_page(id(1), 0, 50).await.unwrap();
        assert_eq!(
            all.items.iter().map(|m| m.lid.as_str()).collect::<Vec<_>>(),
            ["5@lid", LID]
        );
        assert_eq!(
            store.pn_of_lid(id(1), LID.into()).await.unwrap().as_deref(),
            Some(PN2)
        );
        // The number now has two LIDs; the one learned last answers.
        assert_eq!(
            store.lid_of_pn(id(1), PN2.into()).await.unwrap().as_deref(),
            Some(LID)
        );
        assert_eq!(store.lid_of_pn(id(1), PN.into()).await.unwrap(), None);
    }

    #[tokio::test]
    async fn pages_follow_the_cursor() {
        let store = store().await;
        let pairs: Vec<NewPair> = (1..=5)
            .map(|n| {
                pair(
                    &format!("{n}@lid"),
                    &format!("90555000000{n}@s.whatsapp.net"),
                    IdentitySource::History,
                    n,
                )
            })
            .collect();
        assert_eq!(store.learn_pairs(id(1), pairs).await.unwrap().len(), 5);

        let first = store.identity_page(id(1), 0, 2).await.unwrap();
        assert_eq!(
            first
                .items
                .iter()
                .map(|m| m.lid.as_str())
                .collect::<Vec<_>>(),
            ["1@lid", "2@lid"]
        );
        let second = store
            .identity_page(id(1), first.last.unwrap(), 2)
            .await
            .unwrap();
        assert_eq!(
            second
                .items
                .iter()
                .map(|m| m.lid.as_str())
                .collect::<Vec<_>>(),
            ["3@lid", "4@lid"]
        );
        let third = store
            .identity_page(id(1), second.last.unwrap(), 2)
            .await
            .unwrap();
        assert_eq!(third.items.len(), 1);
        let end = store
            .identity_page(id(1), third.last.unwrap(), 2)
            .await
            .unwrap();
        assert_eq!(end, IdentityPage::default());
        // A zero limit still makes progress; a huge one is capped.
        assert_eq!(
            store.identity_page(id(1), 0, 0).await.unwrap().items.len(),
            1
        );
        assert_eq!(
            store
                .identity_page(id(1), 0, u32::MAX)
                .await
                .unwrap()
                .items
                .len(),
            5
        );
    }

    #[tokio::test]
    async fn pairs_are_per_instance_and_go_with_it() {
        let store = store().await;
        learn_one(&store, pair(LID, PN, IdentitySource::Usync, 1)).await;
        store
            .learn_pairs(id(2), vec![pair(LID, PN2, IdentitySource::Usync, 2)])
            .await
            .unwrap();
        assert_eq!(
            store.pn_of_lid(id(1), LID.into()).await.unwrap().as_deref(),
            Some(PN)
        );
        assert_eq!(
            store.pn_of_lid(id(2), LID.into()).await.unwrap().as_deref(),
            Some(PN2)
        );
        assert_eq!(
            store.identity_page(id(2), 0, 50).await.unwrap().items.len(),
            1
        );

        assert!(store.delete_instance(id(1)).await.unwrap());
        assert_eq!(
            store.identity_page(id(1), 0, 50).await.unwrap(),
            IdentityPage::default()
        );
        assert_eq!(
            store.identity_page(id(2), 0, 50).await.unwrap().items.len(),
            1
        );
    }

    #[tokio::test]
    async fn ids_that_are_not_a_bare_lid_and_number_are_skipped() {
        let store = store().await;
        for (lid, pn) in [
            (PN, LID),
            ("1234:5@lid", PN),
            (LID, "905550000001:7@s.whatsapp.net"),
            (LID, "1-2@g.us"),
            ("@lid", PN),
            (LID, "abc@s.whatsapp.net"),
            ("", ""),
        ] {
            assert!(
                learn_one(&store, pair(lid, pn, IdentitySource::Other, 1))
                    .await
                    .is_empty(),
                "{lid} {pn}"
            );
        }
        assert_eq!(
            store.identity_page(id(1), 0, 50).await.unwrap(),
            IdentityPage::default()
        );
    }

    #[tokio::test]
    async fn the_other_form_comes_from_the_wire_id_then_the_table() {
        let store = store().await;
        learn_one(&store, pair(LID, PN, IdentitySource::Usync, 1)).await;
        let conn = store.conn.lock().unwrap();
        let instance = id(1);
        let mut alts = Alts::new(&conn, &instance);
        assert_eq!(alts.of(PN, None).unwrap().as_deref(), Some(LID));
        assert_eq!(alts.of(LID, None).unwrap().as_deref(), Some(PN));
        // The id the row arrived under wins over the table.
        assert_eq!(
            alts.of(PN, Some("77@lid")).unwrap().as_deref(),
            Some("77@lid")
        );
        // A wire id of the same form says nothing about the other one.
        assert_eq!(alts.of(PN, Some(PN)).unwrap().as_deref(), Some(LID));
        assert_eq!(alts.of(PN2, Some(PN2)).unwrap(), None);
        // Groups and unknown people have none.
        assert_eq!(alts.of("1-2@g.us", Some(LID)).unwrap(), None);
        assert_eq!(alts.of("9@lid", None).unwrap(), None);
        assert_eq!(alts.of("", None).unwrap(), None);
    }

    fn text(chat: &str, mid: &str, sender: Option<&str>, from_me: bool) -> WaMessage {
        WaMessage {
            id: mid.into(),
            chat: chat.into(),
            sender: sender.map(str::to_owned),
            from_me,
            timestamp_ms: 10,
            body: MessageBody::Text("hi".into()),
            reactions: vec![],
            chat_alt: None,
            sender_alt: None,
        }
    }

    fn wire(chat: Option<&str>, sender: Option<&str>) -> MessageExtras {
        MessageExtras {
            wire_chat: chat.map(str::to_owned),
            wire_sender: sender.map(str::to_owned),
            ..Default::default()
        }
    }

    #[tokio::test]
    async fn a_v6_database_upgrades_and_keeps_its_data() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("gateway.db");
        {
            let mut conn = Connection::open(&path).unwrap();
            let tx = conn.transaction().unwrap();
            for sql in &MIGRATIONS[..6] {
                tx.execute_batch(sql).unwrap();
            }
            tx.execute_batch("PRAGMA user_version = 6").unwrap();
            tx.execute_batch(
                "INSERT INTO instances (id, created_ms, state, own_jid)
                     VALUES ('old', 1, 'connected', '905550000009@s.whatsapp.net');
                 INSERT INTO chats (instance_id, jid, unread, last_ms)
                     VALUES ('old', '905550000001@s.whatsapp.net', 0, 6);
                 INSERT INTO messages (instance_id, chat, id, from_me, ts_ms, body_json)
                     VALUES ('old', '905550000001@s.whatsapp.net', 'M1', 0, 5, '{\"text\":\"hi\"}');",
            )
            .unwrap();
            tx.commit().unwrap();
        }
        let old = InstanceId::new("old");
        for _ in 0..2 {
            // Opening twice migrates once and keeps everything.
            let store = Store::open(&path).unwrap();
            let status = store.instance_status(old.clone()).await.unwrap().unwrap();
            assert_eq!(
                status.own_jid.as_deref(),
                Some("905550000009@s.whatsapp.net")
            );
            assert_eq!(status.own_lid, None, "an old row knows no LID");
            let page = store
                .messages_rich(old.clone(), PN.into(), None, 10)
                .await
                .unwrap();
            assert_eq!(page.len(), 1);
            assert_eq!(page[0].message.body, MessageBody::Text("hi".into()));
            // Old rows kept no wire ids and name no other form.
            assert_eq!(page[0].message.chat_alt, None);
            assert_eq!(
                store
                    .wire_ids(old.clone(), PN.into(), "M1".into())
                    .await
                    .unwrap(),
                WireIds::default()
            );
            assert_eq!(
                store.identity_page(old.clone(), 0, 50).await.unwrap(),
                IdentityPage::default()
            );
        }
        let conn = Connection::open(&path).unwrap();
        let version: u32 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap();
        assert_eq!(version, MIGRATIONS.len() as u32);
        assert!(version >= 7);
        drop(conn);

        // A pair learned after the upgrade names the old rows' other form.
        let store = Store::open(&path).unwrap();
        store
            .learn_pairs(old.clone(), vec![pair(LID, PN, IdentitySource::Usync, 1)])
            .await
            .unwrap();
        let page = store
            .messages_rich(old.clone(), PN.into(), None, 10)
            .await
            .unwrap();
        assert_eq!(page[0].message.chat_alt.as_deref(), Some(LID));
        assert_eq!(store.chats(old).await.unwrap()[0].alt.as_deref(), Some(LID));
    }

    #[tokio::test]
    async fn the_own_lid_is_kept_with_the_state() {
        let store = store().await;
        let me = "905550000009@s.whatsapp.net";
        store
            .set_state_with_lid(
                id(1),
                InstanceState::Connected,
                Some(me.into()),
                Some("Ada".into()),
                Some("777@lid".into()),
            )
            .await
            .unwrap();
        // A later state that does not repeat it keeps it, like the number.
        store
            .set_state(id(1), InstanceState::Disconnected, None, None)
            .await
            .unwrap();
        let status = store.instance_status(id(1)).await.unwrap().unwrap();
        assert_eq!(status.state, InstanceState::Disconnected);
        assert_eq!(status.own_jid.as_deref(), Some(me));
        assert_eq!(status.own_lid.as_deref(), Some("777@lid"));
        assert_eq!(
            store.instance_status(id(2)).await.unwrap().unwrap().own_lid,
            None
        );
    }

    #[tokio::test]
    async fn messages_keep_their_wire_ids_and_show_the_other_form() {
        let store = store().await;
        let group = "1-2@g.us";
        // A one-to-one message that arrived under the LID, stored under the
        // number; a group message whose author arrived as a LID.
        store
            .insert_message_full(id(1), text(PN, "D", None, false), wire(Some(LID), None))
            .await
            .unwrap();
        store
            .insert_message_full(
                id(1),
                text(group, "G", Some(PN2), false),
                wire(Some(group), Some("5@lid")),
            )
            .await
            .unwrap();
        // One without wire ids, from a person the table knows.
        store
            .insert_message_full(
                id(1),
                text(group, "H", Some(PN), false),
                MessageExtras::default(),
            )
            .await
            .unwrap();
        learn_one(&store, pair(LID, PN, IdentitySource::Usync, 1)).await;

        assert_eq!(
            store.wire_ids(id(1), PN.into(), "D".into()).await.unwrap(),
            WireIds {
                chat: Some(LID.into()),
                sender: None,
            }
        );
        assert_eq!(
            store
                .wire_ids(id(1), group.into(), "G".into())
                .await
                .unwrap(),
            WireIds {
                chat: Some(group.into()),
                sender: Some("5@lid".into()),
            }
        );
        assert_eq!(
            store
                .wire_ids(id(1), group.into(), "nope".into())
                .await
                .unwrap(),
            WireIds::default()
        );

        let dm = store
            .message(id(1), PN.into(), "D".into())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(dm.chat_alt.as_deref(), Some(LID));
        assert_eq!(dm.sender_alt, None);
        let page = store.messages(id(1), group.into(), None, 10).await.unwrap();
        let by_id = |mid: &str| page.iter().find(|m| m.id == mid).unwrap();
        // A group has no other form; its authors do.
        assert_eq!(by_id("G").chat_alt, None);
        assert_eq!(by_id("G").sender_alt.as_deref(), Some("5@lid"));
        assert_eq!(by_id("H").sender_alt.as_deref(), Some(LID));
    }

    #[tokio::test]
    async fn chats_reactions_and_profiles_show_the_other_form() {
        let store = store().await;
        let group = "1-2@g.us";
        store
            .insert_message(id(1), text(PN, "D", None, false), None)
            .await
            .unwrap();
        store
            .insert_message(id(1), text(group, "G", Some(PN2), false), None)
            .await
            .unwrap();
        store
            .insert_message(id(1), text("9@lid", "L", None, false), None)
            .await
            .unwrap();
        let reaction = Reaction {
            sender: Some(PN.into()),
            from_me: false,
            emoji: "x".into(),
            sender_alt: None,
        };
        store
            .set_reaction(id(1), group.into(), "G".into(), reaction)
            .await
            .unwrap();
        store
            .change_profile(id(1), PN.into(), ProfileChange::PushName("Ada".into()), 5)
            .await
            .unwrap();
        learn_one(&store, pair(LID, PN, IdentitySource::Usync, 1)).await;

        let chats = store.chats(id(1)).await.unwrap();
        let alt_of = |jid: &str| chats.iter().find(|c| c.jid == jid).unwrap().alt.clone();
        assert_eq!(alt_of(PN).as_deref(), Some(LID));
        assert_eq!(alt_of(group), None);
        assert_eq!(alt_of("9@lid"), None, "a LID whose number nobody knows");
        let rich = store.chats_rich(id(1)).await.unwrap();
        let chat = rich.iter().find(|c| c.chat.jid == PN).unwrap();
        assert_eq!(chat.chat.alt.as_deref(), Some(LID));
        assert_eq!(
            chat.chat.last.as_ref().unwrap().chat_alt.as_deref(),
            Some(LID)
        );

        let reacted = store
            .message(id(1), group.into(), "G".into())
            .await
            .unwrap()
            .unwrap();
        assert_eq!(reacted.reactions[0].sender.as_deref(), Some(PN));
        assert_eq!(reacted.reactions[0].sender_alt.as_deref(), Some(LID));

        let profiles = store.profiles(id(1), vec![PN.into()]).await.unwrap();
        assert_eq!(profiles[0].lid.as_deref(), Some(LID));
        assert_eq!(profiles[0].push_name.as_deref(), Some("Ada"));
    }

    #[tokio::test]
    async fn a_business_name_from_a_message_is_a_profile_change() {
        let store = store().await;
        let named = store
            .change_profile(
                id(1),
                PN.into(),
                ProfileChange::VerifiedName(" Shop ".into()),
                5,
            )
            .await
            .unwrap()
            .unwrap();
        assert!(named.business);
        assert_eq!(named.verified_name.as_deref(), Some("Shop"));
        assert_eq!(named.fetched_ms, 0, "nothing was asked of WhatsApp");
        // The same name again is no change; an empty one is ignored.
        for name in ["Shop", "  "] {
            assert_eq!(
                store
                    .change_profile(
                        id(1),
                        PN.into(),
                        ProfileChange::VerifiedName(name.into()),
                        6
                    )
                    .await
                    .unwrap(),
                None
            );
        }
        // Learning the LID later is not a profile change either.
        learn_one(&store, pair(LID, PN, IdentitySource::Usync, 1)).await;
        assert_eq!(
            store
                .change_profile(
                    id(1),
                    PN.into(),
                    ProfileChange::VerifiedName("Shop".into()),
                    7
                )
                .await
                .unwrap(),
            None
        );
    }

    #[tokio::test]
    async fn group_receipts_count_people_not_ids() {
        use rosty_wa_proto::DeliveryStatus::{Delivered, Read};
        let store = store().await;
        let group = "1-2@g.us";
        let me = "905550000009@s.whatsapp.net";
        let my_lid = "777@lid";
        store
            .insert_message(id(1), text(group, "m", Some(me), true), None)
            .await
            .unwrap();
        let receipt = |who: &str, status| {
            store.record_group_receipt(
                id(1),
                group.into(),
                GroupReceipt {
                    participant: who.into(),
                    ids: vec!["m".into()],
                    status,
                    members: Some(2),
                    own: vec![me.to_owned(), my_lid.to_owned()],
                },
            )
        };
        // One member acked under their LID before the gateway knew their
        // number, then read under the number: one person, not two, so the
        // other member is still waited for.
        assert!(receipt(LID, Delivered).await.unwrap().is_empty());
        learn_one(&store, pair(LID, PN, IdentitySource::Receipt, 1)).await;
        assert!(receipt(PN, Read).await.unwrap().is_empty());
        // My own devices are no members, under either id.
        assert!(receipt(me, Read).await.unwrap().is_empty());
        assert!(receipt(my_lid, Read).await.unwrap().is_empty());
        let status = || async {
            store
                .messages_rich(id(1), group.into(), None, 10)
                .await
                .unwrap()[0]
                .meta
                .status
        };
        assert_eq!(status().await, None);

        // The second member gets it: delivered to all. The first one's read
        // counts for them, not their earlier delivery.
        let moved = receipt(PN2, Delivered).await.unwrap();
        assert_eq!(moved.len(), 1);
        assert_eq!(moved[0].1.status, Some(Delivered));
        let moved = receipt(PN2, Read).await.unwrap();
        assert_eq!(moved[0].1.status, Some(Read));
        assert_eq!(status().await, Some(Read));
    }

    #[tokio::test]
    async fn every_stored_lid_is_found() {
        let store = store().await;
        let group = "1-2@g.us";
        // A chat, an author, a reactor, a receipt, a deleter, a quoted
        // author, a mention and a profile: each under its own LID.
        store
            .insert_message(id(1), text("10@lid", "c", None, false), None)
            .await
            .unwrap();
        store
            .insert_message(id(1), text(group, "a", Some("20@lid"), false), None)
            .await
            .unwrap();
        let reaction = Reaction {
            sender: Some("30@lid".into()),
            from_me: false,
            emoji: "x".into(),
            sender_alt: None,
        };
        store
            .set_reaction(id(1), group.into(), "a".into(), reaction)
            .await
            .unwrap();
        store
            .insert_message(id(1), text(group, "mine", Some(PN2), true), None)
            .await
            .unwrap();
        store
            .record_group_receipt(
                id(1),
                group.into(),
                GroupReceipt {
                    participant: "40@lid".into(),
                    ids: vec!["mine".into()],
                    status: rosty_wa_proto::DeliveryStatus::Delivered,
                    members: None,
                    own: Vec::new(),
                },
            )
            .await
            .unwrap();
        let revoke = MessageChange::Revoke {
            by: "50@lid".into(),
            at_ms: 9,
        };
        store
            .change_message(id(1), group.into(), "a".into(), revoke)
            .await
            .unwrap();
        let quoting = MessageExtras {
            meta: MessageMeta {
                quoted: Some(Quoted {
                    id: "a".into(),
                    sender: Some("60@lid".into()),
                    preview: None,
                }),
                mentions: vec!["70@lid".into(), PN.into()],
                ..Default::default()
            },
            ..Default::default()
        };
        store
            .insert_message_full(id(1), text(group, "q", Some(PN), false), quoting)
            .await
            .unwrap();
        store
            .change_profile(
                id(1),
                "80@lid".into(),
                ProfileChange::PushName("Ada".into()),
                5,
            )
            .await
            .unwrap();
        // Another instance's rows are not this one's.
        store
            .insert_message(id(2), text("99@lid", "x", None, false), None)
            .await
            .unwrap();

        assert_eq!(
            store.lids_seen(id(1)).await.unwrap(),
            [
                "10@lid", "20@lid", "30@lid", "40@lid", "50@lid", "60@lid", "70@lid", "80@lid"
            ]
        );
        assert_eq!(store.lids_seen(id(2)).await.unwrap(), ["99@lid"]);
    }
}
