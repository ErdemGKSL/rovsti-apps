//! Opening the store, the instance row and the sync writes (events, the chat list, pages).

use std::path::{Path, PathBuf};

use rosty_core::wa_identity::{IdentityMap, Source};
use rosty_wa_proto::{EventEnvelope, GatewayEvent, RichChat, RichMessage};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;

use super::apply::{self, Upsert};
use super::identity::{self, Canon, Ids};
use super::link_state::Told;
use super::model::{AppliedEvents, StoredChats, StoredPage};
use super::plan::{self, ChatCursor, FetchPage, Gap, SyncPlan, SyncState};
use super::{Error, Result, backfill, merge, outbox, schema};

/// The offline store of one linked WhatsApp instance.
pub struct Store {
    pub(super) conn: Connection,
    instance_id: String,
    /// Who every id is. Loaded from the `person` and `alias` tables and written back in the transaction of each
    /// call that changes it.
    pub(super) identity: IdentityMap,
    /// The database file; `None` for a store in memory.
    pub(super) path: Option<PathBuf>,
}

/// What [`Store::opened`] reports.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Opened {
    pub instance_id: String,
    pub schema_version: u32,
    pub last_seq: Option<u64>,
    pub needs_resync: bool,
}

/// The file of an instance inside the data directory: `wa-sync-<instance>.db`. Characters outside
/// `[A-Za-z0-9_-]` are written as `~xx` (hex), so two instance ids never share a file.
pub fn file_name(instance_id: &str) -> String {
    let mut name = String::from("wa-sync-");
    for byte in instance_id.bytes() {
        if byte.is_ascii_alphanumeric() || byte == b'_' || byte == b'-' {
            name.push(byte as char);
        } else {
            name.push_str(&format!("~{byte:02x}"));
        }
    }
    name.push_str(".db");
    name
}

impl Store {
    /// Opens (or creates) the store of `instance_id` in `dir`. Another instance has another file, so its data is
    /// neither read nor touched.
    pub fn open(dir: &Path, instance_id: &str) -> Result<Store> {
        std::fs::create_dir_all(dir)?;
        Self::open_file(&dir.join(file_name(instance_id)), instance_id)
    }

    /// Opens the store at `path`. A file that belongs to another instance is refused.
    pub fn open_file(path: &Path, instance_id: &str) -> Result<Store> {
        let conn = Connection::open(path)?;
        // WAL: the UI reads while the sync writes, and a kill mid-write loses at most the last transaction.
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "synchronous", "NORMAL")?;
        let mut store = Self::prepare(conn, instance_id)?;
        store.path = Some(path.to_owned());
        Ok(store)
    }

    /// A store that lives in memory (tests, previews).
    pub fn open_in_memory(instance_id: &str) -> Result<Store> {
        Self::prepare(Connection::open_in_memory()?, instance_id)
    }

    fn prepare(mut conn: Connection, instance_id: &str) -> Result<Store> {
        if instance_id.trim().is_empty() {
            return Err(Error::Invalid("the instance id is empty".into()));
        }
        conn.busy_timeout(std::time::Duration::from_secs(5))?;
        schema::migrate(&mut conn)?;
        conn.execute(
            "INSERT OR IGNORE INTO sync_state (id, instance_id) VALUES (1, ?1)",
            [instance_id],
        )?;
        let found: String = conn.query_row(
            "SELECT instance_id FROM sync_state WHERE id = 1",
            [],
            |row| row.get(0),
        )?;
        if found != instance_id {
            return Err(Error::InstanceMismatch {
                wanted: instance_id.to_owned(),
                found,
            });
        }
        let mut identity = identity::load(&conn)?;
        // Before the first pair is learned the map must know every stored id.
        backfill::run(&mut conn, &mut identity)?;
        let mut store = Store {
            conn,
            instance_id: instance_id.to_owned(),
            identity,
            path: None,
        };
        // Key changes logged before the store was closed or killed.
        store.apply_pending_merges()?;
        Ok(store)
    }

    pub fn instance_id(&self) -> &str {
        &self.instance_id
    }

    pub fn opened(&self) -> Result<Opened> {
        let state = self.sync_state()?;
        Ok(Opened {
            instance_id: state.instance_id,
            schema_version: schema::version(&self.conn)?,
            last_seq: state.last_seq,
            needs_resync: state.needs_resync,
        })
    }

    /// The instance row: the last event applied and whether the chat list must be refreshed.
    pub fn sync_state(&self) -> Result<SyncState> {
        Ok(self.conn.query_row(
            "SELECT instance_id, last_seq, needs_resync, chats_synced_ms FROM sync_state WHERE id = 1",
            [],
            |row| {
                Ok(SyncState {
                    instance_id: row.get(0)?,
                    last_seq: row.get(1)?,
                    needs_resync: row.get(2)?,
                    chats_synced_ms: row.get(3)?,
                })
            },
        )?)
    }

    /// What one chat's history looks like in the store. `chat` may be any id of the chat
    /// ([`Self::stored_key`]).
    pub fn cursor(&self, chat: &str) -> Result<ChatCursor> {
        cursor(&self.conn, &self.stored_key(chat))
    }

    /// The work the store still owes: the `seq` to subscribe after, whether to refresh the chat list, and the next
    /// page of every open gap. Ask after opening and after a reconnect.
    pub fn plan(&self, limit: Option<u32>) -> Result<SyncPlan> {
        let state = self.sync_state()?;
        Ok(SyncPlan {
            after_seq: state.last_seq,
            refresh_chats: state.needs_resync,
            fetch: open_gaps(&self.conn, plan::clamp_limit(limit))?,
            identity_since: self.identity_since()?,
        })
    }

    /// Applies gateway events in the order given. An event at or before the stored `seq` was applied already and
    /// is skipped; a `resync` resets the `seq` and asks for a chat list refresh. One transaction, all or nothing,
    /// unless a `state` event names the account.
    ///
    /// A `state` event that names the account (its own number, and its LID when the gateway knows it) sets the
    /// store's own ids first ([`Self::identity_set_own`]): another account empties the store
    /// (`account_changed`), which is a transaction of its own, not a step of the events' transaction. The events before such an event are then a transaction of their
    /// own, with their `seq` stored, so a failure later in the batch leaves them applied and the rest is applied
    /// when the batch is handed in again.
    pub fn apply_events(&mut self, events: &[EventEnvelope]) -> Result<AppliedEvents> {
        let mut out = AppliedEvents::default();
        let mut start = 0;
        loop {
            let end = events[start..]
                .iter()
                .skip(1)
                .position(|envelope| own_ids(envelope).is_some())
                .map_or(events.len(), |at| start + 1 + at);
            let chunk = &events[start..end];
            if let Some(first) = chunk.first()
                && let Some((pn, lid)) = own_ids(first)
                && plan::accepts(self.sync_state()?.last_seq, first)
            {
                let own = self.set_own(Some(pn), lid, first.at_ms, Told::Event)?;
                out.rekeyed.extend(own.rekeyed);
                out.rejected.extend(own.rejected);
                out.resync |= own.resync;
                out.account_changed |= own.account_changed;
                out.delete_files.extend(own.delete_files);
            }
            let applied = apply_events(&mut self.conn, &mut self.identity, chunk, &mut out);
            self.healed(applied)?;
            if end == events.len() {
                return Ok(out);
            }
            start = end;
        }
    }

    /// Stores a refreshed chat list (`GET /v1/chats`) and decides, per chat, whether messages were missed: those
    /// chats get a gap from their newest stored message up, and the answer names their first pages. Chats the
    /// gateway no longer lists stay. `refresh_recent_ms`: chats active within this long before `now_ms` get one
    /// page even when nothing seems missed, to pick up edits, deletes and ticks an events gap swallowed.
    pub fn store_chats(
        &mut self,
        chats: &[RichChat],
        now_ms: i64,
        limit: Option<u32>,
        refresh_recent_ms: Option<i64>,
    ) -> Result<StoredChats> {
        let stored = store_chats(
            &mut self.conn,
            &mut self.identity,
            chats,
            now_ms,
            plan::clamp_limit(limit),
            refresh_recent_ms,
        );
        self.healed(stored)
    }

    /// Stores one page of `GET /v1/chats/{chat}/messages?before=&limit=` (newest first). `before` and `limit` are
    /// what was asked. A page of an open gap moves or closes the gap; a short page means the gateway has nothing
    /// older. Stored history is only ever added to.
    pub fn store_page(
        &mut self,
        chat: &str,
        before: Option<i64>,
        limit: u32,
        messages: &[RichMessage],
        now_ms: i64,
    ) -> Result<StoredPage> {
        let page = Page {
            chat,
            before,
            limit,
            messages,
            now_ms,
        };
        let stored = store_page(&mut self.conn, &mut self.identity, &page);
        self.healed(stored)
    }

    /// The page to fetch when the thread is scrolled past the oldest stored message; `None` when the gateway has
    /// nothing older. Store the answer with [`Self::store_page`]. `chat` may be any id of the chat; the page
    /// names its key.
    pub fn older(&self, chat: &str, limit: Option<u32>) -> Result<Option<FetchPage>> {
        let chat = self.stored_key(chat);
        Ok(plan::older(
            &chat,
            &cursor(&self.conn, &chat)?,
            plan::clamp_limit(limit),
        ))
    }
}

/// The account's own number and LID, when `envelope` is a `state` event that names the account.
fn own_ids(envelope: &EventEnvelope) -> Option<(&str, Option<&str>)> {
    match &envelope.event {
        GatewayEvent::State(status) => {
            Some((status.own_jid.as_deref()?, status.own_lid.as_deref()))
        }
        _ => None,
    }
}

/// Applies `events` in one transaction and adds what they did to `out`. A failure leaves `out` as it was.
fn apply_events(
    conn: &mut Connection,
    map: &mut IdentityMap,
    events: &[EventEnvelope],
    out: &mut AppliedEvents,
) -> Result<()> {
    let tx = conn.transaction()?;
    let mut last_seq: Option<u64> =
        tx.query_row("SELECT last_seq FROM sync_state WHERE id = 1", [], |row| {
            row.get(0)
        })?;
    let mut done = AppliedEvents {
        // Nothing is matched against rows that still wait for a key change.
        rekeyed: merge::apply_pending(&tx)?,
        ..AppliedEvents::default()
    };
    let mut ids = Ids::new(&tx, map);
    for envelope in events {
        if !plan::accepts(last_seq, envelope) {
            continue;
        }
        apply::apply_event(&mut ids, envelope, &mut done)?;
        last_seq = Some(envelope.seq);
        done.applied += 1;
    }
    let learned = ids.learned;
    tx.execute(
        "UPDATE sync_state SET last_seq = ?1 WHERE id = 1",
        [last_seq],
    )?;
    tx.commit()?;
    out.last_seq = last_seq;
    out.applied += done.applied;
    out.resync |= done.resync || learned.moved;
    out.reconciled.extend(done.reconciled);
    // The chats a key change joined read differently, whatever the events themselves touched.
    let merged = learned.rekeyed.iter().map(|step| &step.into_key);
    for chat in done.chats.iter().chain(merged) {
        if !out.chats.contains(chat) {
            out.chats.push(chat.clone());
        }
    }
    out.rekeyed.extend(done.rekeyed);
    out.rekeyed.extend(learned.rekeyed);
    out.rejected.extend(learned.rejected);
    Ok(())
}

/// The newer of two newest messages, with its meta.
fn newer_last(kept: &mut RichChat, other: RichChat) {
    let stamp = |chat: &RichChat| {
        chat.chat
            .last
            .as_ref()
            .map(|last| (last.timestamp_ms, last.id.clone()))
    };
    if stamp(&other) > stamp(kept) {
        kept.chat.last = other.chat.last;
        kept.last_meta = other.last_meta;
    }
}

/// The listed chats under their keys. Two entries that are one chat here (the gateway still lists a LID chat next
/// to the number chat of a person the map knows) are joined the way a merge joins two chat rows.
///
/// The pairs the list names (a chat next to its other form, and what its newest message carries) are learned
/// before any entry is keyed, so two entries of one person are seen as one whichever comes first.
fn listed(ids: &mut Ids<'_>, chats: &[RichChat], now_ms: i64) -> Result<Vec<(Canon, RichChat)>> {
    for chat in chats {
        let summary = &chat.chat;
        // The gateway's own resolution of the chat, not something WhatsApp said about it.
        ids.learn_alt(
            Some(&summary.jid),
            summary.alt.as_deref(),
            Source::Other,
            now_ms,
        )?;
        if let Some(last) = &summary.last {
            apply::learn_message(ids, last)?;
        }
    }
    let mut out: Vec<(Canon, RichChat)> = Vec::with_capacity(chats.len());
    for chat in chats {
        if rosty_wa_proto::is_status_chat(&chat.chat.jid) {
            continue;
        }
        let key = ids.canon(&chat.chat.jid)?;
        let Some((first, kept)) = out.iter_mut().find(|(seen, _)| seen.key == key.key) else {
            out.push((key, chat.clone()));
            continue;
        };
        let other = chat.clone();
        first.wire = first.wire.take().or(key.wire);
        kept.chat.name = kept.chat.name.take().or(other.chat.name.clone());
        kept.chat.unread = kept.chat.unread.saturating_add(other.chat.unread);
        kept.meta.picture_id = kept
            .meta
            .picture_id
            .take()
            .or(other.meta.picture_id.clone());
        kept.meta.push_name = kept.meta.push_name.take().or(other.meta.push_name.clone());
        kept.meta.archived |= other.meta.archived;
        kept.meta.pinned |= other.meta.pinned;
        kept.meta.marked_unread |= other.meta.marked_unread;
        kept.meta.muted_until_ms =
            merge::later_mute(kept.meta.muted_until_ms, other.meta.muted_until_ms);
        newer_last(kept, other);
    }
    Ok(out)
}

fn store_chats(
    conn: &mut Connection,
    map: &mut IdentityMap,
    chats: &[RichChat],
    now_ms: i64,
    limit: u32,
    refresh_recent_ms: Option<i64>,
) -> Result<StoredChats> {
    let tx = conn.transaction()?;
    let mut ids = Ids::new(&tx, map);
    for (key, chat) in listed(&mut ids, chats, now_ms)? {
        let jid = key.key.as_str();
        let before = cursor(&tx, jid)?;
        let last = chat.chat.last.as_ref();
        let recent = refresh_recent_ms
            .zip(last)
            .is_some_and(|(window, last)| last.timestamp_ms >= now_ms.saturating_sub(window));
        let gap = plan::chat_gap(&before, last, recent);
        apply::upsert_chat(&mut ids, &key, &chat, now_ms)?;
        set_gap(&tx, jid, gap)?;
    }
    tx.execute(
        "UPDATE sync_state SET needs_resync = 0, chats_synced_ms = ?1 WHERE id = 1",
        [now_ms],
    )?;
    let fetch = open_gaps(&tx, limit)?;
    let learned = ids.learned;
    tx.commit()?;
    Ok(StoredChats {
        chats: chats.len() as u32,
        fetch,
        rekeyed: learned.rekeyed,
        rejected: learned.rejected,
    })
}

/// One answered page of a chat's messages.
struct Page<'a> {
    chat: &'a str,
    before: Option<i64>,
    limit: u32,
    messages: &'a [RichMessage],
    now_ms: i64,
}

fn store_page(conn: &mut Connection, map: &mut IdentityMap, page: &Page<'_>) -> Result<StoredPage> {
    let tx = conn.transaction()?;
    let mut ids = Ids::new(&tx, map);
    // The pairs the page names are learned before its chat is keyed: a page asked under a LID whose messages
    // name the number is a page of the number's chat.
    for rich in page.messages {
        apply::learn_message(&mut ids, &rich.message)?;
    }
    // The page was asked under the key or under an id of the same chat; its messages are matched by key.
    let chat = ids.canon(page.chat)?.key;
    let chat = chat.as_str();
    let (limit, messages) = (page.limit, page.messages);
    let was = cursor(&tx, chat)?;
    let mut new = 0;
    let mut stored = 0;
    let mut reconciled = Vec::new();
    let mut span: Option<(i64, i64)> = None;
    for rich in messages {
        let arrived = apply::arrive(&mut ids, &rich.message, Some(&rich.meta))?;
        if arrived.message.chat != chat {
            continue;
        }
        let ts = arrived.message.timestamp_ms;
        span = Some(span.map_or((ts, ts), |(oldest, newest)| {
            (oldest.min(ts), newest.max(ts))
        }));
        stored += 1;
        if apply::upsert_arrived(&tx, &arrived, None, page.now_ms)? == Upsert::New {
            new += 1;
            if let Some(client_id) = outbox::reconcile_echo(&tx, &arrived.message)? {
                reconciled.push(client_id);
            }
        }
    }
    let short = messages.len() < limit as usize;
    if short {
        apply::ensure_chat(&tx, chat)?;
        tx.execute(
            "INSERT INTO chat_sync (jid, reached_start) VALUES (?1, 1)
             ON CONFLICT (jid) DO UPDATE SET reached_start = 1",
            [chat],
        )?;
    }
    let mut next = None;
    if let Some(gap) = was.gap.filter(|gap| gap.before == page.before) {
        let after = plan::after_page(gap, span, messages.len(), limit);
        set_gap(&tx, chat, after)?;
        if after.is_none() {
            tx.execute("UPDATE chat_sync SET gap_open = 0 WHERE jid = ?1", [chat])?;
        }
        next = after.map(|gap| FetchPage {
            chat: chat.to_owned(),
            before: gap.before,
            limit,
        });
    }
    let learned = ids.learned;
    tx.commit()?;
    Ok(StoredPage {
        stored,
        new,
        next,
        reached_start: short || was.reached_start,
        reconciled,
        rekeyed: learned.rekeyed,
        rejected: learned.rejected,
    })
}

fn cursor(conn: &Connection, chat: &str) -> Result<ChatCursor> {
    Ok(conn
        .query_row(
            "SELECT newest_ts, newest_id, oldest_ts, reached_start, gap_open, gap_floor_ts, gap_before
             FROM chat_sync WHERE jid = ?1",
            [chat],
            |row| {
                let open: bool = row.get(4)?;
                let floor: Option<i64> = row.get(5)?;
                let before: Option<i64> = row.get(6)?;
                Ok(ChatCursor {
                    newest_ts: row.get(0)?,
                    newest_id: row.get(1)?,
                    oldest_ts: row.get(2)?,
                    reached_start: row.get(3)?,
                    gap: floor.filter(|_| open).map(|floor_ts| Gap {
                        floor_ts,
                        before,
                    }),
                })
            },
        )
        .optional()?
        .unwrap_or_default())
}

/// Opens or moves a gap; `None` leaves the row as it is (closing is the caller's explicit step).
fn set_gap(conn: &Connection, chat: &str, gap: Option<Gap>) -> Result<()> {
    if let Some(gap) = gap {
        conn.execute(
            "UPDATE chat_sync SET gap_open = 1, gap_floor_ts = ?2, gap_before = ?3 WHERE jid = ?1",
            params![chat, gap.floor_ts, gap.before],
        )?;
    }
    Ok(())
}

/// The next page of every open gap, most recently active chat first.
fn open_gaps(conn: &Connection, limit: u32) -> Result<Vec<FetchPage>> {
    let mut stmt = conn.prepare(
        "SELECT jid, gap_floor_ts, gap_before FROM chat_sync
         WHERE gap_open = 1 AND gap_floor_ts IS NOT NULL ORDER BY newest_ts DESC, jid",
    )?;
    let gaps: Vec<(String, Gap)> = stmt
        .query_map([], |row| {
            Ok((
                row.get(0)?,
                Gap {
                    floor_ts: row.get(1)?,
                    before: row.get(2)?,
                },
            ))
        })?
        .collect::<rusqlite::Result<_>>()?;
    Ok(plan::gap_pages(
        gaps.iter().map(|(jid, gap)| (jid.as_str(), *gap)),
        limit,
    ))
}
