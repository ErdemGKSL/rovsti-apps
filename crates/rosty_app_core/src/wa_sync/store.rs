//! Opening the store, the instance row and the sync writes (events, the chat list, pages).

use std::path::Path;

use rosty_wa_proto::{EventEnvelope, RichChat, RichMessage};
use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;

use super::apply::{self, Upsert};
use super::model::{AppliedEvents, StoredChats, StoredPage};
use super::plan::{self, ChatCursor, FetchPage, Gap, SyncPlan, SyncState};
use super::{Error, Result, outbox, schema};

/// The offline store of one linked WhatsApp instance.
pub struct Store {
    pub(super) conn: Connection,
    instance_id: String,
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
        Self::prepare(conn, instance_id)
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
        Ok(Store {
            conn,
            instance_id: instance_id.to_owned(),
        })
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

    /// What one chat's history looks like in the store.
    pub fn cursor(&self, chat: &str) -> Result<ChatCursor> {
        cursor(&self.conn, chat)
    }

    /// The work the store still owes: the `seq` to subscribe after, whether to refresh the chat list, and the next
    /// page of every open gap. Ask after opening and after a reconnect.
    pub fn plan(&self, limit: Option<u32>) -> Result<SyncPlan> {
        let state = self.sync_state()?;
        Ok(SyncPlan {
            after_seq: state.last_seq,
            refresh_chats: state.needs_resync,
            fetch: open_gaps(&self.conn, plan::clamp_limit(limit))?,
        })
    }

    /// Applies gateway events in the order given. An event at or before the stored `seq` was applied already and
    /// is skipped; a `resync` resets the `seq` and asks for a chat list refresh. One transaction: all or nothing.
    pub fn apply_events(&mut self, events: &[EventEnvelope]) -> Result<AppliedEvents> {
        let tx = self.conn.transaction()?;
        let mut last_seq: Option<u64> =
            tx.query_row("SELECT last_seq FROM sync_state WHERE id = 1", [], |row| {
                row.get(0)
            })?;
        let mut out = AppliedEvents::default();
        for envelope in events {
            if !plan::accepts(last_seq, envelope) {
                continue;
            }
            apply::apply_event(&tx, envelope, &mut out)?;
            last_seq = Some(envelope.seq);
            out.applied += 1;
        }
        tx.execute(
            "UPDATE sync_state SET last_seq = ?1 WHERE id = 1",
            [last_seq],
        )?;
        tx.commit()?;
        out.last_seq = last_seq;
        Ok(out)
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
        let limit = plan::clamp_limit(limit);
        let tx = self.conn.transaction()?;
        for chat in chats {
            let jid = &chat.chat.jid;
            if rosty_wa_proto::is_status_chat(jid) {
                continue;
            }
            let before = cursor(&tx, jid)?;
            let last = chat.chat.last.as_ref();
            let recent = refresh_recent_ms
                .zip(last)
                .is_some_and(|(window, last)| last.timestamp_ms >= now_ms.saturating_sub(window));
            let gap = plan::chat_gap(&before, last, recent);
            apply::upsert_chat(&tx, chat, now_ms)?;
            set_gap(&tx, jid, gap)?;
        }
        tx.execute(
            "UPDATE sync_state SET needs_resync = 0, chats_synced_ms = ?1 WHERE id = 1",
            [now_ms],
        )?;
        let fetch = open_gaps(&tx, limit)?;
        tx.commit()?;
        Ok(StoredChats {
            chats: chats.len() as u32,
            fetch,
        })
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
        let tx = self.conn.transaction()?;
        let was = cursor(&tx, chat)?;
        let mut new = 0;
        let mut stored = 0;
        let mut reconciled = Vec::new();
        let mut span: Option<(i64, i64)> = None;
        for rich in messages.iter().filter(|rich| rich.message.chat == chat) {
            let ts = rich.message.timestamp_ms;
            span = Some(span.map_or((ts, ts), |(oldest, newest)| {
                (oldest.min(ts), newest.max(ts))
            }));
            stored += 1;
            if apply::upsert_rich(&tx, rich, now_ms)? == Upsert::New {
                new += 1;
                if let Some(client_id) = outbox::reconcile_echo(&tx, &rich.message)? {
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
        if let Some(gap) = was.gap.filter(|gap| gap.before == before) {
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
        tx.commit()?;
        Ok(StoredPage {
            stored,
            new,
            next,
            reached_start: short || was.reached_start,
            reconciled,
        })
    }

    /// The page to fetch when the thread is scrolled past the oldest stored message; `None` when the gateway has
    /// nothing older. Store the answer with [`Self::store_page`].
    pub fn older(&self, chat: &str, limit: Option<u32>) -> Result<Option<FetchPage>> {
        Ok(plan::older(
            chat,
            &self.cursor(chat)?,
            plan::clamp_limit(limit),
        ))
    }
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
