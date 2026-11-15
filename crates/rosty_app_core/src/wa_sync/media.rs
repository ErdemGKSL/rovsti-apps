//! Media of a message: the thumbnail is stored inline and stays with the message; the full file is a cached path
//! with LRU bookkeeping. Evicting clears the path only. The chat of a message may be named by any of its ids
//! ([`Store::stored_key`]): media that was being fetched while a merge moved the chat still lands on its message.

use rusqlite::{OptionalExtension, params};
use serde::Serialize;

use super::store::Store;
use super::{Error, Result};

/// What the store holds for the media of one message.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MediaInfo {
    pub thumbnail: Option<Vec<u8>>,
    /// The cached full file; `None` when not downloaded or evicted.
    pub path: Option<String>,
    pub bytes: Option<u64>,
}

/// A file the cache no longer counts: delete it.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Evicted {
    pub chat: String,
    pub id: String,
    pub path: String,
    pub bytes: u64,
}

/// What [`Store::media_evict`] did.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct Eviction {
    /// Least recently used first.
    pub evicted: Vec<Evicted>,
    /// What the cache still counts.
    pub bytes_left: u64,
}

impl Store {
    /// Stores the thumbnail of a message (kept for good, also after the full file is evicted).
    pub fn set_thumbnail(&mut self, chat: &str, id: &str, thumbnail: &[u8]) -> Result<()> {
        let chat = &self.stored_key(chat);
        let changed = self.conn.execute(
            "UPDATE messages SET thumb = ?3 WHERE chat = ?1 AND id = ?2",
            params![chat, id, thumbnail],
        )?;
        found(changed, chat, id)
    }

    /// Records the downloaded full file of a message and counts it as just used.
    pub fn set_media_file(
        &mut self,
        chat: &str,
        id: &str,
        path: &str,
        bytes: u64,
        now_ms: i64,
    ) -> Result<()> {
        let chat = &self.stored_key(chat);
        let changed = self.conn.execute(
            "UPDATE messages SET media_path = ?3, media_bytes = ?4, media_used_ms = ?5 WHERE chat = ?1 AND id = ?2",
            params![chat, id, path, bytes, now_ms],
        )?;
        found(changed, chat, id)
    }

    /// The thumbnail and the cached file of a message. `used_ms` (the media was opened) moves the file to the
    /// fresh end of the LRU order.
    pub fn media(
        &mut self,
        chat: &str,
        id: &str,
        used_ms: Option<i64>,
    ) -> Result<Option<MediaInfo>> {
        let chat = &self.stored_key(chat);
        if let Some(now_ms) = used_ms {
            self.conn.execute(
                "UPDATE messages SET media_used_ms = ?3 WHERE chat = ?1 AND id = ?2 AND media_path IS NOT NULL",
                params![chat, id, now_ms],
            )?;
        }
        Ok(self
            .conn
            .query_row(
                "SELECT thumb, media_path, media_bytes FROM messages WHERE chat = ?1 AND id = ?2",
                params![chat, id],
                |row| {
                    Ok(MediaInfo {
                        thumbnail: row.get(0)?,
                        path: row.get(1)?,
                        bytes: row.get(2)?,
                    })
                },
            )
            .optional()?)
    }

    /// Bytes of full media the cache counts.
    pub fn media_bytes(&self) -> Result<u64> {
        Ok(self.conn.query_row(
            "SELECT COALESCE(SUM(media_bytes), 0) FROM messages WHERE media_path IS NOT NULL",
            [],
            |row| row.get(0),
        )?)
    }

    /// Brings the cache under `max_bytes`: the least recently used files lose their path, the caller deletes them.
    /// The messages and their thumbnails stay, so they read the same and the media can be fetched again.
    pub fn media_evict(&mut self, max_bytes: u64) -> Result<Eviction> {
        let tx = self.conn.transaction()?;
        let mut left: u64 = tx.query_row(
            "SELECT COALESCE(SUM(media_bytes), 0) FROM messages WHERE media_path IS NOT NULL",
            [],
            |row| row.get(0),
        )?;
        let mut evicted = Vec::new();
        if left > max_bytes {
            let mut stmt = tx.prepare(
                "SELECT chat, id, media_path, COALESCE(media_bytes, 0) FROM messages
                 WHERE media_path IS NOT NULL ORDER BY COALESCE(media_used_ms, 0), chat, id",
            )?;
            let cached = stmt
                .query_map([], |row| {
                    Ok(Evicted {
                        chat: row.get(0)?,
                        id: row.get(1)?,
                        path: row.get(2)?,
                        bytes: row.get(3)?,
                    })
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            for file in cached {
                if left <= max_bytes {
                    break;
                }
                tx.execute(
                    "UPDATE messages SET media_path = NULL, media_bytes = NULL, media_used_ms = NULL
                     WHERE chat = ?1 AND id = ?2",
                    params![file.chat, file.id],
                )?;
                left = left.saturating_sub(file.bytes);
                evicted.push(file);
            }
        }
        tx.commit()?;
        Ok(Eviction {
            evicted,
            bytes_left: left,
        })
    }
}

fn found(changed: usize, chat: &str, id: &str) -> Result<()> {
    if changed == 0 {
        return Err(Error::NotFound(format!("message {id} of {chat}")));
    }
    Ok(())
}
