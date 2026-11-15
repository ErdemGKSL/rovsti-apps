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
use rusqlite::{Connection, params};
use serde::Serialize;

use super::authors::Moved;
use super::merge::{self, ChatRekeyed};
use super::store::Store;
use super::{Result, backfill};

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
    /// The own number is another one than before: the identity tables were reset.
    pub account_changed: bool,
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
}

impl Ids<'_> {
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
fn own(conn: &Connection) -> Result<(Option<String>, Option<String>)> {
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
fn bare_of(text: Option<&str>, kind: WaIdKind) -> Option<String> {
    text.and_then(parse)
        .map(|parsed| parsed.id)
        .filter(|id| id.kind() == kind)
        .map(|id| id.bare())
}

/// Forgets every person, alias and key change: the store now belongs to another WhatsApp account. The merge
/// cursor moves past the dropped log, so sequence numbers never repeat and nothing of the old account replays.
fn reset_identity_tables(conn: &Connection) -> Result<()> {
    conn.execute_batch(
        "DELETE FROM person;
         DELETE FROM alias;
         DELETE FROM merge_log;
         UPDATE sync_state SET identity_backfill = NULL, own_pn = NULL, own_lid = NULL,
            merge_cursor = COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'merge_log'), 0);",
    )?;
    Ok(())
}

/// Runs in the transaction that resets the identity tables when another account is linked to this instance.
///
/// The full wipe of the store (chats, messages, outbox, cursors) belongs here; it is a later round. Until then the
/// rows of the previous account stay and the backfill makes the new map know their ids.
fn account_changed_hook(_conn: &Connection) -> Result<()> {
    Ok(())
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

    /// Names the account's own number and LID (JIDs, device allowed). `None`, or an id of the wrong kind, keeps
    /// what is stored. An own number other than the stored one means another account was linked to this instance:
    /// the identity tables start over (`account_changed` in the answer).
    pub fn identity_set_own(
        &mut self,
        pn: Option<&str>,
        lid: Option<&str>,
        at_ms: i64,
    ) -> Result<IdentityApplied> {
        let new_pn = bare_of(pn, WaIdKind::Pn);
        let new_lid = bare_of(lid, WaIdKind::Lid);
        let (stored_pn, stored_lid) = own(&self.conn)?;
        let account_changed =
            matches!((&stored_pn, &new_pn), (Some(stored), Some(new)) if stored != new);
        if account_changed {
            let reset = reset_for_account(&mut self.conn);
            self.healed(reset)?;
            self.identity = IdentityMap::new();
            let filled = backfill::run(&mut self.conn, &mut self.identity);
            self.healed(filled)?;
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

fn reset_for_account(conn: &mut Connection) -> Result<()> {
    let tx = conn.transaction()?;
    reset_identity_tables(&tx)?;
    account_changed_hook(&tx)?;
    tx.commit()?;
    Ok(())
}
