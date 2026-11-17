//! Identity in the store: the `person` and `alias` tables behind [`IdentityMap`], and the calls that teach it.
//!
//! The map is the single authority: SQL never decides who an id is. It is loaded when the store opens, every call
//! that changes it writes its [`Changes`] in the same transaction, and a key change is a row of `merge_log` that
//! the stored rows follow ([`super::merge`]). Chats and messages stay keyed by the canonical key string
//! (`<digits>@s.whatsapp.net`, or `<user>@lid` while no number is known), which is what the store always wrote; a
//! `person_id` column on them would have to be kept in step on every write and adds nothing the `alias` table does
//! not answer.

use rosty_core::wa_identity::{
    AliasKind, AliasRow, AliasState, Changes, IdentityKey, IdentityMap, PersonId, PersonRow,
    Rejected, Source, WaIdKind, parse,
};
use rosty_wa_proto::{GroupInfo, IdentityMappings, InstanceStatus, WaProfile};
use rusqlite::{Connection, params};
use serde::{Deserialize, Serialize};

use super::Result;
use super::authors::Moved;
use super::link_state::{Told, Wipe};
use super::merge::{self, ChatRekeyed};
use super::store::Store;

/// `merge_log.reason` of a merge the gateway announced for a pair that is not a LID and its number.
pub(super) const REASON_GATEWAY: &str = "chat_merged";
/// `merge_log.reason` of a stored chat the backfill found under an id that is not its key.
pub(super) const REASON_BACKFILL: &str = "backfill";

/// An id resolved against the store's identity map.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct ResolvedId {
    /// The key chats and messages of this id are stored under.
    pub key: String,
    /// What the id names, as it was written.
    pub kind: WaIdKind,
    /// The id as it was written, without device: what goes back on the wire.
    pub wire: String,
    /// The person behind a phone number or LID; `None` for a group and every other kind.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub person_id: Option<PersonId>,
}

/// What a call that taught the map did.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct IdentityApplied {
    /// The key changes applied to the stored rows, oldest first: drafts, prefs and notification tags follow them.
    pub rekeyed: Vec<ChatRekeyed>,
    /// Pairs that changed nothing, for the log.
    pub rejected: Vec<Rejected>,
    /// The chat list must be refreshed ([`Store::store_chats`]): a merge moved rows.
    pub resync: bool,
    /// The own number is another one than before: another account was linked and the store was emptied
    /// ([`Store::note_status`]).
    pub account_changed: bool,
    /// Media files of the old account to delete.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub delete_files: Vec<String>,
}

/// What the gateway answered outside the event stream that names a person by both ids: the instance (the
/// account's own number and LID), groups (their members) and profiles.
#[derive(Clone, Copy, Debug, Default)]
pub struct Observed<'a> {
    pub status: Option<&'a InstanceStatus>,
    pub groups: &'a [GroupInfo],
    pub profiles: &'a [WaProfile],
}

/// What [`Store::identity_catch_up`] did with one page of the gateway's pairs.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize)]
pub struct IdentityCaughtUp {
    /// Pairs in the page.
    pub pairs: u32,
    /// The cursor the store now holds: `since=` of the next call, now or at the next catch-up.
    pub since: Option<String>,
    /// The gateway has more: ask again with `since`.
    pub more: bool,
    /// The key changes applied to the stored rows, oldest first.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rekeyed: Vec<ChatRekeyed>,
    /// Pairs that changed nothing, for the log.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub rejected: Vec<Rejected>,
    /// The chat list must be refreshed ([`Store::store_chats`]): a merge moved rows.
    pub resync: bool,
}

/// Who every id is, as the encrypted vault cache keeps it between unlocks (`plans/wa-vault.md` 13.5): the rows of
/// the map and the cursor of the identity catch-up. Never message content.
#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct IdentitySnapshot {
    #[serde(default)]
    pub persons: Vec<PersonRow>,
    #[serde(default)]
    pub aliases: Vec<AliasRow>,
    /// `since=` of the next `GET /v1/identity/mappings`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub since: Option<String>,
}

impl IdentitySnapshot {
    pub fn is_empty(&self) -> bool {
        self.aliases.is_empty() && self.since.is_none()
    }
}

/// What the calls of one transaction taught the map, beyond the rows they wrote.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(super) struct Learned {
    /// The key changes the stored rows followed, oldest first.
    pub rekeyed: Vec<ChatRekeyed>,
    /// Pairs that changed nothing, each once.
    pub rejected: Vec<Rejected>,
    /// A key change moved a chat, a message, a cursor or an outbox row.
    pub moved: bool,
}

/// The LID and the number among two ids that are the two forms of one person, in that order. `None` unless one
/// is a LID and the other a phone number.
pub(super) fn pair<'a>(a: &'a str, b: &'a str) -> Option<(&'a str, &'a str)> {
    let kind = |text: &str| parse(text).map(|parsed| parsed.id.kind());
    match (kind(a)?, kind(b)?) {
        (WaIdKind::Lid, WaIdKind::Pn) => Some((a, b)),
        (WaIdKind::Pn, WaIdKind::Lid) => Some((b, a)),
        _ => None,
    }
}

/// Whether the map already holds `lid` and `pn` as the current ids of one person, each from a source at least as
/// trusted as `source`: learning the pair again would at most move a timestamp.
fn settled(map: &IdentityMap, lid: &str, pn: &str, source: Source) -> bool {
    let (Some(lid), Some(pn)) = (map.lookup(lid), map.lookup(pn)) else {
        return false;
    };
    let Some(person) = lid.person_id.filter(|id| pn.person_id == Some(*id)) else {
        return false;
    };
    let rows = map.aliases(person);
    [(AliasKind::Lid, &lid.wire), (AliasKind::Pn, &pn.wire)]
        .into_iter()
        .all(|(kind, wire)| {
            rows.iter().any(|row| {
                row.kind == kind
                    && row.user == wire.user()
                    && row.state == AliasState::Current
                    && row.source.rank() >= source.rank()
            })
        })
}

/// An id as the store keys it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct Canon {
    /// The canonical key; the text itself when it is not an id.
    pub key: String,
    /// The id as it arrived, without device, when it is not the key.
    pub wire: Option<String>,
}

/// The identity map together with the transaction its changes are written in.
pub(super) struct Ids<'a> {
    pub conn: &'a Connection,
    pub map: &'a mut IdentityMap,
    /// What the pairs learned in this transaction did.
    pub learned: Learned,
}

impl<'a> Ids<'a> {
    pub fn new(conn: &'a Connection, map: &'a mut IdentityMap) -> Self {
        Self {
            conn,
            map,
            learned: Learned::default(),
        }
    }

    /// Learns that `lid` and `pn` are one person and lets the stored rows follow at once, so what is matched
    /// next finds them under their key. A pair the map already holds from a source as trusted writes nothing.
    pub fn learn(&mut self, lid: &str, pn: &str, source: Source, at_ms: i64) -> Result<()> {
        if settled(self.map, lid, pn, source) {
            return Ok(());
        }
        let changes = self.map.learn(lid, pn, source, at_ms);
        self.changed(changes, at_ms)
    }

    /// Learns from an id and its other form, whichever of the two is the LID. Anything else than a LID next to
    /// a phone number teaches nothing.
    pub fn learn_alt(
        &mut self,
        id: Option<&str>,
        alt: Option<&str>,
        source: Source,
        at_ms: i64,
    ) -> Result<()> {
        if let (Some(id), Some(alt)) = (id, alt)
            && let Some((lid, pn)) = pair(id, alt)
        {
            self.learn(lid, pn, source, at_ms)?;
        }
        Ok(())
    }

    /// A contact moved to another number ([`IdentityMap::number_changed`]).
    pub fn number_changed(
        &mut self,
        old_pn: &str,
        new_pn: &str,
        old_lid: Option<&str>,
        new_lid: Option<&str>,
        at_ms: i64,
    ) -> Result<()> {
        let changes = self
            .map
            .number_changed(old_pn, new_pn, old_lid, new_lid, at_ms);
        self.changed(changes, at_ms)
    }

    /// Writes what a call changed and applies the key changes it logged.
    fn changed(&mut self, changes: Changes, at_ms: i64) -> Result<()> {
        persist(self.conn, &changes, at_ms)?;
        if !changes.merges.is_empty() {
            self.follow()?;
        }
        for rejected in changes.rejected {
            if !self.learned.rejected.contains(&rejected) {
                self.learned.rejected.push(rejected);
            }
        }
        Ok(())
    }

    /// Applies the key changes the stored rows have not followed yet.
    pub fn follow(&mut self) -> Result<()> {
        while let Some((step, moved)) = merge::apply_next(self.conn)? {
            self.learned.moved |= moved;
            self.learned.rekeyed.push(step);
        }
        Ok(())
    }

    /// Resolves an id as it arrives. A number or LID seen for the first time becomes a person of its own, so a
    /// pair learned later finds it. Text that is not an id is its own key.
    pub fn canon(&mut self, text: &str) -> Result<Canon> {
        let Some(resolved) = self.map.resolve(text) else {
            return Ok(Canon {
                key: text.to_owned(),
                wire: None,
            });
        };
        persist(self.conn, &resolved.changes, 0)?;
        let key = resolved.key.as_str().to_owned();
        let wire = resolved.wire.bare();
        Ok(Canon {
            wire: (wire != key).then_some(wire),
            key,
        })
    }

    /// [`Self::canon`] as an author rewrite: `None` when the text is stored as it is.
    pub fn moved(&mut self, text: &str) -> Result<Option<Moved>> {
        let Some(resolved) = self.map.resolve(text) else {
            return Ok(None);
        };
        persist(self.conn, &resolved.changes, 0)?;
        let key = resolved.key.as_str();
        Ok((key != text).then(|| Moved {
            to: key.to_owned(),
            wire: resolved.wire.bare(),
        }))
    }
}

/// The account's own number and LID as stored, bare.
pub(super) fn own(conn: &Connection) -> Result<(Option<String>, Option<String>)> {
    Ok(conn.query_row(
        "SELECT own_pn, own_lid FROM sync_state WHERE id = 1",
        [],
        |row| Ok((row.get(0)?, row.get(1)?)),
    )?)
}

/// Reads the map from the tables and names the account's own ids. Rows the map repairs or refuses are fixed on
/// the next change that touches them.
pub(super) fn load(conn: &Connection) -> Result<IdentityMap> {
    let persons: Vec<PersonRow> = conn
        .prepare("SELECT person_id, key FROM person")?
        .query_map([], |row| {
            Ok(PersonRow {
                person_id: PersonId(row.get(0)?),
                key: IdentityKey::from_stored(row.get::<_, String>(1)?),
            })
        })?
        .collect::<rusqlite::Result<_>>()?;
    type RawAlias = (String, String, i64, String, String, i64);
    let raw: Vec<RawAlias> = conn
        .prepare("SELECT kind, user, person_id, state, source, learned_ms FROM alias")?
        .query_map([], |row| {
            Ok((
                row.get(0)?,
                row.get(1)?,
                row.get(2)?,
                row.get(3)?,
                row.get(4)?,
                row.get(5)?,
            ))
        })?
        .collect::<rusqlite::Result<_>>()?;
    // A kind or state this build does not know (a row of a newer one) is left out.
    let aliases =
        raw.into_iter()
            .filter_map(|(kind, user, person_id, state, source, learned_ms)| {
                Some(AliasRow {
                    kind: AliasKind::from_stored(&kind)?,
                    user,
                    person_id: PersonId(person_id),
                    state: AliasState::from_stored(&state)?,
                    source: Source::from_wire(&source),
                    learned_ms,
                })
            });
    let mut map = IdentityMap::from_rows(persons, aliases);
    let (pn, lid) = own(conn)?;
    let changes = map.set_own(pn.as_deref(), lid.as_deref());
    if !changes.is_empty() {
        let tx = conn.unchecked_transaction()?;
        persist(&tx, &changes, 0)?;
        tx.commit()?;
    }
    Ok(map)
}

/// Appends a key change for the stored rows to follow.
pub(super) fn log_merge(
    conn: &Connection,
    at_ms: i64,
    from_key: &str,
    into_key: &str,
    reason: &str,
) -> Result<()> {
    conn.prepare_cached(
        "INSERT INTO merge_log (at_ms, from_key, into_key, reason) VALUES (?1, ?2, ?3, ?4)",
    )?
    .execute(params![at_ms, from_key, into_key, reason])?;
    Ok(())
}

/// Writes what one call changed, in the caller's transaction: persons, aliases, then the merges in order. The
/// persons a call touched are taken out before they are written back, so two of them trading a key never meet
/// the unique index half way.
pub(super) fn persist(conn: &Connection, changes: &Changes, at_ms: i64) -> Result<()> {
    if changes.is_empty() {
        return Ok(());
    }
    {
        let mut delete = conn.prepare_cached("DELETE FROM person WHERE person_id = ?1")?;
        for id in &changes.removed_persons {
            delete.execute([id.0])?;
        }
        for row in &changes.persons {
            delete.execute([row.person_id.0])?;
        }
    }
    let mut insert = conn.prepare_cached("INSERT INTO person (person_id, key) VALUES (?1, ?2)")?;
    for row in &changes.persons {
        insert.execute(params![row.person_id.0, row.key.as_str()])?;
    }
    let mut upsert = conn.prepare_cached(
        "INSERT INTO alias (kind, user, person_id, state, source, learned_ms) VALUES (?1, ?2, ?3, ?4, ?5, ?6)
         ON CONFLICT (kind, user) DO UPDATE SET person_id = excluded.person_id, state = excluded.state,
            source = excluded.source, learned_ms = excluded.learned_ms",
    )?;
    for row in &changes.aliases {
        upsert.execute(params![
            row.kind.as_str(),
            row.user,
            row.person_id.0,
            row.state.as_str(),
            row.source.as_str(),
            row.learned_ms,
        ])?;
    }
    for step in &changes.merges {
        log_merge(
            conn,
            at_ms,
            step.from_key.as_str(),
            step.into_key.as_str(),
            step.reason.as_str(),
        )?;
    }
    Ok(())
}

/// The bare id of `text` when it is of `kind`.
pub(super) fn bare_of(text: Option<&str>, kind: WaIdKind) -> Option<String> {
    text.and_then(parse)
        .map(|parsed| parsed.id)
        .filter(|id| id.kind() == kind)
        .map(|id| id.bare())
}

impl Store {
    /// The identity map: who an id is, the account's own ids, a person's numbers.
    pub fn identity(&self) -> &IdentityMap {
        &self.identity
    }

    /// Puts the map back in step with the tables after a transaction that changed it failed.
    pub(super) fn healed<T>(&mut self, result: Result<T>) -> Result<T> {
        if result.is_err()
            && let Ok(map) = load(&self.conn)
        {
            self.identity = map;
        }
        result
    }

    /// Changes the map and writes the change in one transaction, then lets the stored rows follow.
    fn identity_change(
        &mut self,
        at_ms: i64,
        change: impl FnOnce(&mut IdentityMap) -> Changes,
    ) -> Result<IdentityApplied> {
        let written = write_change(&mut self.conn, &mut self.identity, at_ms, change);
        let rejected = self.healed(written)?;
        let (rekeyed, resync) = self.follow_merges()?;
        Ok(IdentityApplied {
            rekeyed,
            rejected,
            resync,
            account_changed: false,
            delete_files: Vec::new(),
        })
    }

    /// Learns that `lid` and `pn` are one person, as `source` said at `at_ms`. Both are JIDs, device allowed. A
    /// LID chat and a number chat of that person become one chat; see [`IdentityMap::learn`] for every case.
    pub fn identity_learn(
        &mut self,
        lid: &str,
        pn: &str,
        source: Source,
        at_ms: i64,
    ) -> Result<IdentityApplied> {
        self.identity_change(at_ms, |map| map.learn(lid, pn, source, at_ms))
    }

    /// A contact moved from `old_pn` to `new_pn`: one person, one chat under the new number. See
    /// [`IdentityMap::number_changed`].
    pub fn identity_number_changed(
        &mut self,
        old_pn: &str,
        new_pn: &str,
        old_lid: Option<&str>,
        new_lid: Option<&str>,
        at_ms: i64,
    ) -> Result<IdentityApplied> {
        self.identity_change(at_ms, |map| {
            map.number_changed(old_pn, new_pn, old_lid, new_lid, at_ms)
        })
    }

    /// Names the account's own number and LID (JIDs, device allowed), as an answer outside the event stream said.
    /// `None`, or an id of the wrong kind, keeps what is stored. An own number other than the stored one means
    /// another account was linked to this instance: the store is emptied (`account_changed` and `delete_files`
    /// in the answer; see [`Store::note_status`]). A number that is unknown on either side proves nothing.
    pub fn identity_set_own(
        &mut self,
        pn: Option<&str>,
        lid: Option<&str>,
        at_ms: i64,
    ) -> Result<IdentityApplied> {
        self.set_own(pn, lid, at_ms, Told::Answer)
    }

    /// [`Self::identity_set_own`], with who said so. While the store waits for the announcement of an account
    /// change it already followed, an event that names another own number is an event of the old account still
    /// in the gateway's ring: it is not believed.
    pub(super) fn set_own(
        &mut self,
        pn: Option<&str>,
        lid: Option<&str>,
        at_ms: i64,
        told: Told,
    ) -> Result<IdentityApplied> {
        let new_pn = bare_of(pn, WaIdKind::Pn);
        let new_lid = bare_of(lid, WaIdKind::Lid);
        let (stored_pn, stored_lid) = own(&self.conn)?;
        let account_changed =
            matches!((&stored_pn, &new_pn), (Some(stored), Some(new)) if stored != new);
        let mut delete_files = Vec::new();
        if account_changed {
            let sweep: bool = self.conn.query_row(
                "SELECT account_sweep FROM sync_state WHERE id = 1",
                [],
                |row| row.get(0),
            )?;
            if told == Told::Event && sweep {
                return Ok(IdentityApplied::default());
            }
            delete_files = self.account_wipe(Wipe {
                keep_outbox: false,
                account_epoch: None,
                sweep: told == Told::Answer,
            })?;
        }
        let (pn, lid) = if account_changed {
            (new_pn, new_lid)
        } else {
            (new_pn.or(stored_pn), new_lid.or(stored_lid))
        };
        self.conn.execute(
            "UPDATE sync_state SET own_pn = ?1, own_lid = ?2 WHERE id = 1",
            params![pn, lid],
        )?;
        let mut applied =
            self.identity_change(at_ms, |map| map.set_own(pn.as_deref(), lid.as_deref()))?;
        applied.account_changed = account_changed;
        applied.resync |= account_changed;
        applied.delete_files = delete_files;
        Ok(applied)
    }

    /// Resolves an id as it arrives (a chat, a sender, a typing or presence id): the key its rows are stored
    /// under, its kind and the id to put back on the wire. A number or LID seen for the first time becomes a
    /// person of its own. `None` when the text is not an id.
    pub fn identity_resolve(&mut self, text: &str) -> Result<Option<ResolvedId>> {
        let Some(resolved) = self.identity.resolve(text) else {
            return Ok(None);
        };
        let written = persist(&self.conn, &resolved.changes, 0);
        self.healed(written)?;
        Ok(Some(ResolvedId {
            key: resolved.key.as_str().to_owned(),
            kind: resolved.kind,
            wire: resolved.wire.bare(),
            person_id: resolved.person_id,
        }))
    }

    /// The key the rows of `text` are stored under now, without teaching the map anything: a reader that still
    /// holds an id of before a merge (a LID whose number is known since) reads the merged rows. Text that is
    /// not an id, and an id the map has not seen, is its own key.
    pub fn stored_key(&self, text: &str) -> String {
        self.identity.lookup(text).map_or_else(
            || text.to_owned(),
            |resolved| resolved.key.as_str().to_owned(),
        )
    }

    /// The phone numbers of the person behind `text` as `+<digits>`: the current one first, then the former
    /// ones, newest first. A device contact is looked up by each in turn, so a contact that only holds a former
    /// number still names the person. A number the map has not seen is its own only number; a LID without a
    /// number, a group and text that is not an id have none. Reads only: the map learns nothing.
    #[must_use]
    pub fn identity_numbers(&self, text: &str) -> Vec<String> {
        let Some(resolved) = self.identity.lookup(text) else {
            return Vec::new();
        };
        match resolved.person_id {
            Some(person) => self.identity.numbers(person),
            None => resolved.wire.number().into_iter().collect(),
        }
    }

    /// The cursor of the last page of the gateway's pairs the store took in: `since=` of the next
    /// `GET /v1/identity/mappings`. `None` before the first one (ask for every pair).
    pub fn identity_since(&self) -> Result<Option<String>> {
        Ok(self.conn.query_row(
            "SELECT identity_cursor FROM sync_state WHERE id = 1",
            [],
            |row| row.get(0),
        )?)
    }

    /// The map and the catch-up cursor, for the encrypted vault cache.
    pub fn identity_snapshot(&self) -> Result<IdentitySnapshot> {
        let (persons, aliases) = self.identity.rows();
        Ok(IdentitySnapshot {
            persons,
            aliases,
            since: self.identity_since()?,
        })
    }

    /// Starts a store that knows nobody yet from a snapshot of the vault cache: the persons, their aliases and
    /// the catch-up cursor, so the next catch-up asks only for what is newer. A store that already knows someone
    /// (or holds a cursor) keeps what it has and answers `false`: the cache never overwrites what was learned.
    pub fn seed_identity(&mut self, snapshot: &IdentitySnapshot) -> Result<bool> {
        if !self.identity.is_empty() || self.identity_since()?.is_some() || snapshot.is_empty() {
            return Ok(false);
        }
        let tx = self.conn.transaction()?;
        {
            let mut person =
                tx.prepare("INSERT OR REPLACE INTO person (person_id, key) VALUES (?1, ?2)")?;
            for row in &snapshot.persons {
                person.execute(params![row.person_id.0, row.key.as_str()])?;
            }
            let mut alias = tx.prepare(
                "INSERT OR REPLACE INTO alias (kind, user, person_id, state, source, learned_ms)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            )?;
            for row in &snapshot.aliases {
                alias.execute(params![
                    row.kind.as_str(),
                    row.user,
                    row.person_id.0,
                    row.state.as_str(),
                    row.source.as_str(),
                    row.learned_ms,
                ])?;
            }
            tx.execute(
                "UPDATE sync_state SET identity_cursor = ?1 WHERE id = 1",
                [&snapshot.since],
            )?;
        }
        tx.commit()?;
        // The map repairs what it refuses; the own ids of the store stay as they were.
        self.identity = load(&self.conn)?;
        Ok(true)
    }

    /// Takes in one page of `GET /v1/identity/mappings?since=<cursor>`: learns every pair and keeps the page's
    /// cursor, in one transaction. Ask with [`Self::identity_since`] when the store opens, when the event socket
    /// reconnects and after a `resync` event, and go on with the answer's `since` while it says `more`. The
    /// cursor only moves forward here, so a catch-up that was cut short goes on where it stopped.
    pub fn identity_catch_up(&mut self, page: &IdentityMappings) -> Result<IdentityCaughtUp> {
        let taken = in_tx(&mut self.conn, &mut self.identity, |ids| {
            for item in &page.items {
                ids.learn(
                    &item.lid,
                    &item.pn,
                    Source::from_wire(&item.source),
                    item.learned_ms,
                )?;
            }
            if let Some(next) = &page.next {
                ids.conn.execute(
                    "UPDATE sync_state SET identity_cursor = ?1 WHERE id = 1",
                    [next],
                )?;
            }
            Ok(())
        });
        let ((), learned) = self.healed(taken)?;
        // Steps logged before this call (a kill between a pair and its merge) are followed too.
        let (mut rekeyed, resync) = (learned.rekeyed, learned.moved);
        let (later, later_moved) = self.follow_merges()?;
        rekeyed.extend(later);
        Ok(IdentityCaughtUp {
            pairs: page.items.len() as u32,
            since: self.identity_since()?,
            more: page.next.is_some(),
            rekeyed,
            rejected: learned.rejected,
            resync: resync || later_moved,
        })
    }

    /// Learns from what the gateway answered outside the event stream: the instance names the account's own
    /// number and LID, a group names its members by both ids, a profile names the LID of a number. Hand the
    /// answers in as they came; what names no pair teaches nothing.
    ///
    /// The instance status is taken in whole ([`Self::note_instance_status`]): its epoch (another one than the
    /// stored is a gap, answered as `resync`), the account epoch and own ids (another account empties the store:
    /// `account_changed`, `delete_files`) and the history report ([`Self::history`]).
    pub fn identity_observe(
        &mut self,
        seen: &Observed<'_>,
        now_ms: i64,
    ) -> Result<IdentityApplied> {
        // The whole status: the gateway's epoch (another one is a gap, `resync`), the account (another one
        // empties the store) and the history report, next to the own ids.
        let mut applied = match seen.status {
            Some(status) => {
                let noted = self.note_instance_status(status, now_ms)?;
                IdentityApplied {
                    rekeyed: noted.rekeyed,
                    rejected: noted.rejected,
                    resync: noted.resync,
                    account_changed: noted.account_changed,
                    delete_files: noted.delete_files,
                }
            }
            None => IdentityApplied::default(),
        };
        let taken = in_tx(&mut self.conn, &mut self.identity, |ids| {
            for member in seen.groups.iter().flat_map(|group| &group.participants) {
                let (jid, lid, pn) = (
                    Some(member.jid.as_str()),
                    member.lid.as_deref(),
                    member.pn.as_deref(),
                );
                for (id, alt) in [(lid, pn), (jid, lid), (jid, pn)] {
                    ids.learn_alt(id, alt, Source::GroupMetadata, now_ms)?;
                }
            }
            for profile in seen.profiles {
                ids.learn_alt(
                    Some(&profile.jid),
                    profile.lid.as_deref(),
                    Source::Usync,
                    now_ms,
                )?;
            }
            Ok(())
        });
        let ((), learned) = self.healed(taken)?;
        applied.rekeyed.extend(learned.rekeyed);
        applied.rejected.extend(learned.rejected);
        applied.resync |= learned.moved;
        Ok(applied)
    }

    /// Applies the key changes of `merge_log` the stored rows have not followed yet, in order, one transaction
    /// per step. A store that was closed, or killed between steps, catches up here; opening the store calls it.
    pub fn apply_pending_merges(&mut self) -> Result<Vec<ChatRekeyed>> {
        Ok(self.follow_merges()?.0)
    }

    /// [`Self::apply_pending_merges`], and whether a step moved a chat (the chat list then needs a refresh).
    fn follow_merges(&mut self) -> Result<(Vec<ChatRekeyed>, bool)> {
        let mut rekeyed = Vec::new();
        let mut any_moved = false;
        loop {
            let tx = self.conn.transaction()?;
            let Some((step, moved)) = merge::apply_next(&tx)? else {
                return Ok((rekeyed, any_moved));
            };
            tx.commit()?;
            any_moved |= moved;
            rekeyed.push(step);
        }
    }

    /// The key changes logged after `seq`, applied or not, oldest first. A layer that keeps state per chat
    /// (drafts, prefs, notification tags) replays them from its own cursor.
    pub fn rekeyed_since(&self, seq: u64) -> Result<Vec<ChatRekeyed>> {
        merge::logged_since(&self.conn, seq)
    }
}

/// Runs `f` in one transaction with the identity map; what it taught the map comes back next to its answer.
pub(super) fn in_tx<T>(
    conn: &mut Connection,
    map: &mut IdentityMap,
    f: impl FnOnce(&mut Ids<'_>) -> Result<T>,
) -> Result<(T, Learned)> {
    let tx = conn.transaction()?;
    let mut ids = Ids::new(&tx, map);
    let out = f(&mut ids)?;
    let learned = ids.learned;
    tx.commit()?;
    Ok((out, learned))
}

fn write_change(
    conn: &mut Connection,
    map: &mut IdentityMap,
    at_ms: i64,
    change: impl FnOnce(&mut IdentityMap) -> Changes,
) -> Result<Vec<Rejected>> {
    let tx = conn.transaction()?;
    let changes = change(map);
    persist(&tx, &changes, at_ms)?;
    tx.commit()?;
    Ok(changes.rejected)
}
