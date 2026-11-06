//! Status updates (stories), schema v5: kept apart from the chats, one row
//! per update, gone when it expires.

use rovsti_wa_proto::{StatusItem, StatusKind};
use rusqlite::{OptionalExtension, params};

use super::{InstanceId, MAX_THUMBNAIL_BYTES, Store, StoreError};

/// One status update as stored.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct StoredStatus {
    pub item: StatusItem,
    /// The author's JID as WhatsApp addressed the update (a LID or a phone
    /// number): read receipts name it as the participant.
    pub participant: String,
    /// The encoded WhatsApp message, for downloading a photo or video.
    pub raw_proto: Option<Vec<u8>>,
    /// The small preview image that came inside a photo or video.
    pub thumbnail: Option<Vec<u8>>,
}

/// Who to send a status read receipt to.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StatusTarget {
    pub id: String,
    pub participant: String,
    pub author: String,
    pub from_me: bool,
    pub viewed: bool,
}

/// The JSON kept for an update: everything but the viewed flag, which has a
/// column of its own.
fn item_json(item: &StatusItem) -> Result<String, StoreError> {
    let mut item = item.clone();
    item.viewed = false;
    item.expires_ms = item.expiry();
    Ok(serde_json::to_string(&item)?)
}

fn item_from(json: &str, viewed: bool) -> Result<StatusItem, StoreError> {
    let mut item: StatusItem = serde_json::from_str(json)?;
    item.viewed = viewed;
    Ok(item)
}

fn is_media(kind: StatusKind) -> bool {
    matches!(kind, StatusKind::Image | StatusKind::Video)
}

impl Store {
    /// Stores an update. False when it was stored already (or has expired).
    pub async fn insert_status(
        &self,
        instance: InstanceId,
        status: StoredStatus,
        now_ms: i64,
    ) -> Result<bool, StoreError> {
        if status.item.is_expired(now_ms) || status.item.id.is_empty() {
            return Ok(false);
        }
        self.run(move |conn| {
            let thumb = status
                .thumbnail
                .as_ref()
                .filter(|t| !t.is_empty() && t.len() <= MAX_THUMBNAIL_BYTES);
            let raw = status
                .raw_proto
                .as_ref()
                .filter(|_| is_media(status.item.kind));
            let n = conn.execute(
                "INSERT OR IGNORE INTO statuses
                 (instance_id, id, author, participant, from_me, ts_ms, expires_ms,
                  item_json, raw_proto, thumb, viewed)
                 VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
                params![
                    instance.as_str(),
                    status.item.id,
                    status.item.author,
                    status.participant,
                    status.item.from_me,
                    status.item.timestamp_ms,
                    status.item.expiry(),
                    item_json(&status.item)?,
                    raw,
                    thumb,
                    status.item.viewed || status.item.from_me,
                ],
            )?;
            Ok(n > 0)
        })
        .await
    }

    /// Updates that have not expired, oldest first.
    pub async fn statuses(
        &self,
        instance: InstanceId,
        now_ms: i64,
    ) -> Result<Vec<StatusItem>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT item_json, viewed FROM statuses
                 WHERE instance_id = ?1 AND expires_ms > ?2 ORDER BY ts_ms, id",
            )?;
            let rows = stmt
                .query_map(params![instance.as_str(), now_ms], |r| {
                    Ok((r.get::<_, String>(0)?, r.get::<_, bool>(1)?))
                })?
                .collect::<Result<Vec<_>, _>>()?;
            rows.iter()
                .map(|(json, viewed)| item_from(json, *viewed))
                .collect()
        })
        .await
    }

    /// An update that has not expired, with its encoded message.
    pub async fn status_source(
        &self,
        instance: InstanceId,
        id: String,
        now_ms: i64,
    ) -> Result<Option<(StatusItem, Option<Vec<u8>>)>, StoreError> {
        self.run(move |conn| {
            let row = conn
                .query_row(
                    "SELECT item_json, viewed, raw_proto FROM statuses
                     WHERE instance_id = ?1 AND id = ?2 AND expires_ms > ?3",
                    params![instance.as_str(), id, now_ms],
                    |r| {
                        Ok((
                            r.get::<_, String>(0)?,
                            r.get::<_, bool>(1)?,
                            r.get::<_, Option<Vec<u8>>>(2)?,
                        ))
                    },
                )
                .optional()?;
            row.map(|(json, viewed, raw)| Ok((item_from(&json, viewed)?, raw)))
                .transpose()
        })
        .await
    }

    /// The preview image of an update that has not expired.
    pub async fn status_thumbnail(
        &self,
        instance: InstanceId,
        id: String,
        now_ms: i64,
    ) -> Result<Option<Vec<u8>>, StoreError> {
        self.run(move |conn| {
            Ok(conn
                .query_row(
                    "SELECT thumb FROM statuses
                     WHERE instance_id = ?1 AND id = ?2 AND expires_ms > ?3",
                    params![instance.as_str(), id, now_ms],
                    |r| r.get::<_, Option<Vec<u8>>>(0),
                )
                .optional()?
                .flatten())
        })
        .await
    }

    /// The stored updates among `ids` that have not expired, in request
    /// order (unknown ids are left out).
    pub async fn status_targets(
        &self,
        instance: InstanceId,
        ids: Vec<String>,
        now_ms: i64,
    ) -> Result<Vec<StatusTarget>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn.prepare(
                "SELECT participant, author, from_me, viewed FROM statuses
                 WHERE instance_id = ?1 AND id = ?2 AND expires_ms > ?3",
            )?;
            let mut out = Vec::with_capacity(ids.len());
            for id in ids {
                let row = stmt
                    .query_row(params![instance.as_str(), id, now_ms], |r| {
                        Ok((
                            r.get::<_, String>(0)?,
                            r.get::<_, String>(1)?,
                            r.get::<_, bool>(2)?,
                            r.get::<_, bool>(3)?,
                        ))
                    })
                    .optional()?;
                if let Some((participant, author, from_me, viewed)) = row
                    && !out.iter().any(|t: &StatusTarget| t.id == id)
                {
                    out.push(StatusTarget {
                        id,
                        participant,
                        author,
                        from_me,
                        viewed,
                    });
                }
            }
            Ok(out)
        })
        .await
    }

    /// Marks `ids` seen. Returns the authors whose updates changed.
    pub async fn mark_statuses_viewed(
        &self,
        instance: InstanceId,
        ids: Vec<String>,
    ) -> Result<Vec<String>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let mut authors: Vec<String> = Vec::new();
            for id in &ids {
                let author = tx
                    .query_row(
                        "UPDATE statuses SET viewed = 1
                         WHERE instance_id = ?1 AND id = ?2 AND viewed = 0 RETURNING author",
                        params![instance.as_str(), id],
                        |r| r.get::<_, String>(0),
                    )
                    .optional()?;
                if let Some(author) = author
                    && !authors.contains(&author)
                {
                    authors.push(author);
                }
            }
            tx.commit()?;
            Ok(authors)
        })
        .await
    }

    /// Deletes an update its author took back. Returns its author.
    pub async fn delete_status(
        &self,
        instance: InstanceId,
        id: String,
    ) -> Result<Option<String>, StoreError> {
        self.run(move |conn| {
            Ok(conn
                .query_row(
                    "DELETE FROM statuses WHERE instance_id = ?1 AND id = ?2 RETURNING author",
                    params![instance.as_str(), id],
                    |r| r.get::<_, String>(0),
                )
                .optional()?)
        })
        .await
    }

    /// Deletes every update that expired by `now_ms`. Returns `(instance,
    /// update id)` of each, so cached media can go too.
    pub async fn purge_statuses(
        &self,
        now_ms: i64,
    ) -> Result<Vec<(InstanceId, String)>, StoreError> {
        self.run(move |conn| {
            let mut stmt = conn
                .prepare("DELETE FROM statuses WHERE expires_ms <= ?1 RETURNING instance_id, id")?;
            let rows = stmt
                .query_map([now_ms], |r| {
                    Ok((
                        InstanceId::new(r.get::<_, String>(0)?),
                        r.get::<_, String>(1)?,
                    ))
                })?
                .collect::<Result<Vec<_>, _>>()?;
            Ok(rows)
        })
        .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::NewKey;
    use rovsti_wa_proto::{ConnectKey, KeyId, STATUS_TTL_MS, StatusMedia};

    const NOW: i64 = 1_800_000_000_000;

    async fn store() -> (Store, InstanceId) {
        let store = Store::open_in_memory().unwrap();
        let key = ConnectKey::from_bytes(&[1; 32]);
        let id = InstanceId::from_bytes(&[1; 12]);
        store
            .create_instance(
                id.clone(),
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
        (store, id)
    }

    fn status(id: &str, author: &str, ts: i64) -> StoredStatus {
        StoredStatus {
            item: StatusItem {
                id: id.into(),
                author: author.into(),
                timestamp_ms: ts,
                kind: StatusKind::Text,
                text: Some("hi".into()),
                background_argb: Some(0xFF1E_6E4F),
                ..Default::default()
            },
            participant: format!("{}-lid", author),
            raw_proto: Some(vec![1, 2, 3]),
            thumbnail: None,
        }
    }

    #[tokio::test]
    async fn statuses_are_stored_once_and_expire() {
        let (store, i) = store().await;
        let s = status("S1", "a@s.whatsapp.net", NOW - 1000);
        assert!(
            store
                .insert_status(i.clone(), s.clone(), NOW)
                .await
                .unwrap()
        );
        assert!(!store.insert_status(i.clone(), s, NOW).await.unwrap());
        let old = status("S0", "a@s.whatsapp.net", NOW - STATUS_TTL_MS - 1);
        assert!(!store.insert_status(i.clone(), old, NOW).await.unwrap());

        let listed = store.statuses(i.clone(), NOW).await.unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].expires_ms, NOW - 1000 + STATUS_TTL_MS);
        assert_eq!(listed[0].background_argb, Some(0xFF1E_6E4F));
        assert!(!listed[0].viewed);
        // A text status keeps no encoded message.
        let (_, raw) = store
            .status_source(i.clone(), "S1".into(), NOW)
            .await
            .unwrap()
            .unwrap();
        assert!(raw.is_none());

        let later = NOW + STATUS_TTL_MS;
        assert!(store.statuses(i.clone(), later).await.unwrap().is_empty());
        assert!(
            store
                .status_source(i.clone(), "S1".into(), later)
                .await
                .unwrap()
                .is_none()
        );
        let purged = store.purge_statuses(later).await.unwrap();
        assert_eq!(purged, vec![(i.clone(), "S1".to_owned())]);
        assert!(store.purge_statuses(later).await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn media_thumbnails_viewed_and_revoked() {
        let (store, i) = store().await;
        let mut photo = status("P1", "b@s.whatsapp.net", NOW - 10);
        photo.item.kind = StatusKind::Image;
        photo.item.media = Some(StatusMedia {
            mime: "image/jpeg".into(),
            thumbnail: true,
            ..Default::default()
        });
        photo.thumbnail = Some(vec![0xFF, 0xD8]);
        store.insert_status(i.clone(), photo, NOW).await.unwrap();
        let mut mine = status("M1", "me@s.whatsapp.net", NOW - 5);
        mine.item.from_me = true;
        store.insert_status(i.clone(), mine, NOW).await.unwrap();

        assert_eq!(
            store
                .status_thumbnail(i.clone(), "P1".into(), NOW)
                .await
                .unwrap(),
            Some(vec![0xFF, 0xD8])
        );
        let (_, raw) = store
            .status_source(i.clone(), "P1".into(), NOW)
            .await
            .unwrap()
            .unwrap();
        assert_eq!(raw, Some(vec![1, 2, 3]));

        let targets = store
            .status_targets(
                i.clone(),
                vec!["P1".into(), "nope".into(), "M1".into(), "P1".into()],
                NOW,
            )
            .await
            .unwrap();
        assert_eq!(targets.len(), 2);
        assert_eq!(targets[0].participant, "b@s.whatsapp.net-lid");
        assert!(targets[1].from_me && targets[1].viewed);

        let changed = store
            .mark_statuses_viewed(i.clone(), vec!["P1".into(), "M1".into()])
            .await
            .unwrap();
        assert_eq!(changed, vec!["b@s.whatsapp.net".to_owned()]);
        assert!(
            store
                .mark_statuses_viewed(i.clone(), vec!["P1".into()])
                .await
                .unwrap()
                .is_empty()
        );
        let listed = store.statuses(i.clone(), NOW).await.unwrap();
        assert!(listed.iter().all(|s| s.viewed));

        assert_eq!(
            store.delete_status(i.clone(), "P1".into()).await.unwrap(),
            Some("b@s.whatsapp.net".to_owned())
        );
        assert_eq!(
            store.delete_status(i.clone(), "P1".into()).await.unwrap(),
            None
        );
        assert_eq!(store.statuses(i, NOW).await.unwrap().len(), 1);
    }

    #[tokio::test]
    async fn deleting_an_instance_removes_its_statuses() {
        let (store, i) = store().await;
        store
            .insert_status(i.clone(), status("S1", "a@s.whatsapp.net", NOW), NOW)
            .await
            .unwrap();
        assert!(store.delete_instance(i.clone()).await.unwrap());
        assert!(store.purge_statuses(i64::MAX).await.unwrap().is_empty());
    }
}
