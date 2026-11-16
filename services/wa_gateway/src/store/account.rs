//! The linked account and its history sync, schema v8: which WhatsApp
//! account an instance holds data for, the wipe when another one is linked,
//! and how far the history sync got (kept across a gateway restart).

use rosty_wa_proto::{HistoryState, HistoryStatus, HistorySyncType};
use rusqlite::{Connection, OptionalExtension, Transaction, params};

use super::{InstanceId, Store, StoreError, from_tag, to_tag};

/// Every table that holds what one WhatsApp account said, saw or set: all of
/// it goes when another account is linked to the instance. `instances`,
/// `keys` and `push_tokens` belong to the apps and stay.
pub const ACCOUNT_TABLES: &[&str] = &[
    "chats",
    "messages",
    "reactions",
    "receipts",
    "message_edits",
    "chat_settings",
    "profiles",
    "statuses",
    "scheduled",
    "identity_pairs",
    // People sync v3: the job, its numbers and the lookup answers belong to the account that asked.
    "people_jobs",
    "people_job_numbers",
    "people_lookups",
];

/// How the account that just linked or connected relates to the one the
/// instance holds data for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AccountCheck {
    /// One of the two numbers is not known: nothing can be said, so nothing
    /// is ever cleared.
    Unknown,
    /// The same number: a reconnect, or a relink of the same account.
    Same,
    /// Another number: the stored rows belong to somebody else.
    Changed,
}

/// The digits of a phone-number JID (`user@s.whatsapp.net`, with or without
/// a device or agent part). `None` for anything else, a LID included.
fn pn_user(jid: &str) -> Option<&str> {
    let (user, server) = jid.trim().split_once('@')?;
    if server != "s.whatsapp.net" {
        return None;
    }
    let user = user.split([':', '.']).next()?;
    (!user.is_empty() && user.bytes().all(|b| b.is_ascii_digit())).then_some(user)
}

/// Compares the stored own number with the one that linked. Only two known,
/// different phone numbers are a change.
pub fn account_check(stored: Option<&str>, linked: Option<&str>) -> AccountCheck {
    match (stored.and_then(pn_user), linked.and_then(pn_user)) {
        (Some(old), Some(new)) if old == new => AccountCheck::Same,
        (Some(_), Some(_)) => AccountCheck::Changed,
        _ => AccountCheck::Unknown,
    }
}

/// Another account was linked: what the apps are told.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AccountChange {
    pub old_pn: String,
    pub new_pn: String,
    /// The instance's account epoch after the change.
    pub account_epoch: u32,
}

/// Deletes everything stored for the account of `instance`
/// ([`ACCOUNT_TABLES`]). Returns how many rows went.
fn wipe(tx: &Transaction<'_>, instance: &InstanceId) -> Result<usize, StoreError> {
    let mut rows = 0;
    for table in ACCOUNT_TABLES {
        rows += tx.execute(
            &format!("DELETE FROM {table} WHERE instance_id = ?1"),
            [instance.as_str()],
        )?;
    }
    Ok(rows)
}

/// Part of the overall progress the recent messages take when a full history
/// was asked for; the older messages take the rest.
const RECENT_SHARE: u32 = 25;

/// A sync that sent nothing for this long is reported as done: WhatsApp does
/// not say when a sync ends, and a small account may never send the chunk
/// that would close it. A chunk that arrives later reopens it.
pub const HISTORY_STALE_MS: i64 = 30 * 60 * 1000;

/// The last chunk of the chat history that arrived, as stored.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct HistoryMark {
    /// `Initial`, `Recent` or `Full`.
    pub kind: HistorySyncType,
    /// What WhatsApp said for that chunk, 0 to 100 within its kind.
    pub progress: Option<u32>,
    /// 0 to 100 over the whole sync; never goes back while a sync runs.
    pub overall: Option<u32>,
    pub at_ms: i64,
}

/// Whether a chunk of this kind carries chat history (the others are push
/// names, status updates and answers to single requests).
pub fn is_history_chunk(kind: HistorySyncType) -> bool {
    matches!(
        kind,
        HistorySyncType::Initial | HistorySyncType::Recent | HistorySyncType::Full
    )
}

/// Whether `mark` is the chunk that ends the sync: the recent messages at
/// 100, or with a full history the older messages at 100.
fn is_final(mark: &HistoryMark, full: bool) -> bool {
    let complete = mark.progress.is_some_and(|p| p >= 100);
    match mark.kind {
        HistorySyncType::Full => complete,
        HistorySyncType::Recent => complete && !full,
        _ => false,
    }
}

/// A chunk's progress over the whole sync.
fn overall(kind: HistorySyncType, progress: Option<u32>, full: bool) -> Option<u32> {
    let p = progress?.min(100);
    Some(match (kind, full) {
        (HistorySyncType::Full, true) => RECENT_SHARE + p * (100 - RECENT_SHARE) / 100,
        (_, true) => p * RECENT_SHARE / 100,
        (_, false) => p,
    })
}

/// The mark after a chunk of `kind` arrived at `now_ms`. `full` says a full
/// history was asked for when the account was linked.
pub fn advance(
    before: Option<HistoryMark>,
    kind: HistorySyncType,
    progress: Option<u32>,
    full: bool,
    now_ms: i64,
) -> HistoryMark {
    // Older messages only come when they were asked for, whatever the flag
    // says (it is unset for an instance linked before it existed).
    let full = full || kind == HistorySyncType::Full;
    let mapped = overall(kind, progress, full);
    // Within a running sync the number never goes back; after a finished one
    // a new sync starts from its own number.
    let running = before.filter(|b| !is_final(b, full));
    let overall = match (running.and_then(|b| b.overall), mapped) {
        (Some(was), Some(now)) => Some(was.max(now)),
        (was, now) => now.or(was),
    };
    HistoryMark {
        kind,
        progress,
        overall,
        at_ms: now_ms,
    }
}

/// What the apps are told about a stored mark at `now_ms`.
pub fn history_view(
    mark: Option<HistoryMark>,
    full: bool,
    now_ms: i64,
) -> (HistoryState, Option<u32>) {
    let Some(mark) = mark else {
        return (HistoryState::Idle, None);
    };
    let full = full || mark.kind == HistorySyncType::Full;
    if is_final(&mark, full) {
        (HistoryState::Done, Some(100))
    } else if now_ms.saturating_sub(mark.at_ms) > HISTORY_STALE_MS {
        (HistoryState::Done, mark.overall)
    } else {
        (HistoryState::Syncing, mark.overall)
    }
}

/// The account columns of an instance row.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(super) struct AccountRow {
    pub account_epoch: u32,
    pub full_history: bool,
    pub mark: Option<HistoryMark>,
}

/// Reads [`AccountRow`] from the columns `account_epoch, full_history,
/// history_type, history_progress, history_overall, history_ms`, starting at
/// column `at`.
pub(super) fn account_row(r: &rusqlite::Row<'_>, at: usize) -> Result<AccountRow, StoreError> {
    let kind: Option<String> = r.get(at + 2)?;
    let mark = match kind {
        Some(kind) => Some(HistoryMark {
            kind: from_tag(&kind)?,
            progress: r.get(at + 3)?,
            overall: r.get(at + 4)?,
            at_ms: r.get::<_, Option<i64>>(at + 5)?.unwrap_or(0),
        }),
        None => None,
    };
    Ok(AccountRow {
        account_epoch: r.get(at)?,
        full_history: r.get(at + 1)?,
        mark,
    })
}

/// `InstanceStatus.history`: the state of the sync and what is held.
pub(super) fn history_status(
    conn: &Connection,
    instance: &InstanceId,
    row: &AccountRow,
    now_ms: i64,
) -> Result<HistoryStatus, StoreError> {
    let chats: u32 = conn.query_row(
        "SELECT COUNT(*) FROM chats WHERE instance_id = ?1",
        [instance.as_str()],
        |r| r.get(0),
    )?;
    // Both come from the `messages_by_time` index alone.
    let (messages, oldest_ms): (i64, Option<i64>) = conn.query_row(
        "SELECT COUNT(*), MIN(CASE WHEN ts_ms > 0 THEN ts_ms END) FROM messages
         WHERE instance_id = ?1",
        [instance.as_str()],
        |r| Ok((r.get(0)?, r.get(1)?)),
    )?;
    let (state, progress) = history_view(row.mark, row.full_history, now_ms);
    Ok(HistoryStatus {
        state,
        progress,
        chats,
        messages: u64::try_from(messages).unwrap_or(0),
        oldest_ms,
    })
}

impl Store {
    /// The account `pn` (its phone-number JID) linked or connected on
    /// `instance`. When the instance holds the data of another number, that
    /// data is deleted, the account epoch is raised and the change is
    /// returned, all in one transaction. A missing number on either side
    /// never clears anything.
    ///
    /// `paired` is set when this is a new pairing (not a mere reconnect) and
    /// says whether a full history was asked for: the history state starts
    /// over, since WhatsApp syncs a new device from the start.
    pub async fn link_account(
        &self,
        instance: InstanceId,
        pn: Option<String>,
        paired: Option<bool>,
    ) -> Result<Option<AccountChange>, StoreError> {
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let stored: Option<(Option<String>, u32)> = tx
                .query_row(
                    "SELECT own_jid, account_epoch FROM instances WHERE id = ?1",
                    [instance.as_str()],
                    |r| Ok((r.get(0)?, r.get(1)?)),
                )
                .optional()?;
            let Some((old_pn, epoch)) = stored else {
                return Ok(None);
            };
            let mut change = None;
            if account_check(old_pn.as_deref(), pn.as_deref()) == AccountCheck::Changed
                && let (Some(old_pn), Some(new_pn)) = (old_pn, pn)
            {
                let rows = wipe(&tx, &instance)?;
                let account_epoch = epoch.saturating_add(1);
                // The name and the LID were the other account's too.
                tx.execute(
                    "UPDATE instances SET own_jid = ?2, own_lid = NULL, push_name = NULL,
                     account_epoch = ?3, history_type = NULL, history_progress = NULL,
                     history_overall = NULL, history_ms = NULL
                     WHERE id = ?1",
                    params![instance.as_str(), new_pn, account_epoch],
                )?;
                log::info!(
                    "{instance}: another account was linked: {rows} stored rows cleared, \
                     account epoch {account_epoch}"
                );
                change = Some(AccountChange {
                    old_pn,
                    new_pn,
                    account_epoch,
                });
            }
            if let Some(full_history) = paired {
                tx.execute(
                    "UPDATE instances SET full_history = ?2, history_type = NULL,
                     history_progress = NULL, history_overall = NULL, history_ms = NULL
                     WHERE id = ?1",
                    params![instance.as_str(), full_history],
                )?;
            }
            tx.commit()?;
            Ok(change)
        })
        .await
    }

    /// Records that a chunk of the chat history arrived. Other kinds of
    /// chunk leave the state as it is.
    pub async fn note_history(
        &self,
        instance: InstanceId,
        kind: HistorySyncType,
        progress: Option<u32>,
        now_ms: i64,
    ) -> Result<(), StoreError> {
        if !is_history_chunk(kind) {
            return Ok(());
        }
        self.run(move |conn| {
            let tx = conn.transaction()?;
            let row = tx
                .query_row(
                    "SELECT account_epoch, full_history, history_type, history_progress,
                     history_overall, history_ms FROM instances WHERE id = ?1",
                    [instance.as_str()],
                    |r| Ok(account_row(r, 0)),
                )
                .optional()?
                .transpose()?;
            let Some(row) = row else {
                return Ok(());
            };
            let mark = advance(row.mark, kind, progress, row.full_history, now_ms);
            tx.execute(
                "UPDATE instances SET history_type = ?2, history_progress = ?3,
                 history_overall = ?4, history_ms = ?5 WHERE id = ?1",
                params![
                    instance.as_str(),
                    to_tag(&mark.kind),
                    mark.progress,
                    mark.overall,
                    mark.at_ms
                ],
            )?;
            tx.commit()?;
            Ok(())
        })
        .await
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::store::{
        ChatSettings, HistoryChat, HistoryMessage, MessageExtras, NewKey, NewPair, StoredStatus,
    };
    use rosty_wa_proto::{
        AppId, ConnectKey, IdentitySource, InstanceState, KeyId, MessageBody, Reaction,
        ScheduledState, ScheduledText, StatusItem, WaMessage,
    };

    const OLD: &str = "100000000001@s.whatsapp.net";
    const NEW: &str = "100000000002@s.whatsapp.net";

    fn id(n: u8) -> InstanceId {
        InstanceId::from_bytes(&[n; 12])
    }

    async fn store_with(instances: &[u8]) -> Store {
        let store = Store::open_in_memory().unwrap();
        for &n in instances {
            let key = ConnectKey::from_bytes(&[n; 32]);
            let new = NewKey {
                key_id: KeyId::from_bytes(&[n; 8]),
                hash: key.hash(),
                label: "k".into(),
            };
            store.create_instance(id(n), new, 1, 8).await.unwrap();
        }
        store
    }

    fn text(chat: &str, id: &str, ts: i64) -> WaMessage {
        WaMessage {
            id: id.into(),
            chat: chat.into(),
            sender: None,
            from_me: false,
            timestamp_ms: ts,
            body: MessageBody::Text("x".into()),
            reactions: vec![],
            chat_alt: None,
            sender_alt: None,
        }
    }

    /// A row in every account table of `instance`.
    async fn fill(store: &Store, instance: InstanceId) {
        let chat = "100000000009@s.whatsapp.net";
        store
            .set_state(
                instance.clone(),
                InstanceState::Connected,
                Some(OLD.into()),
                Some("Old".into()),
            )
            .await
            .unwrap();
        store
            .import_history(
                instance.clone(),
                vec![HistoryChat {
                    jid: chat.into(),
                    name: Some("Peer".into()),
                    is_group: false,
                    unread: 1,
                    last_ms: 20,
                    messages: vec![HistoryMessage {
                        message: text(chat, "M1", 20),
                        extras: MessageExtras::default(),
                        reactions: vec![Reaction {
                            sender: None,
                            from_me: true,
                            emoji: "+".into(),
                            sender_alt: None,
                        }],
                    }],
                    settings: ChatSettings {
                        pinned: true,
                        ..Default::default()
                    },
                }],
            )
            .await
            .unwrap();
        store
            .add_scheduled(
                instance.clone(),
                ScheduledText {
                    id: format!("s-{instance}"),
                    chat: chat.into(),
                    text: "later".into(),
                    send_at_ms: 99,
                    created_ms: 1,
                    state: ScheduledState::Pending,
                },
                8,
            )
            .await
            .unwrap();
        store
            .learn_pairs(
                instance.clone(),
                vec![NewPair::new(
                    "77@lid",
                    "100000000009@s.whatsapp.net",
                    IdentitySource::Other,
                    5,
                )],
            )
            .await
            .unwrap();
        store
            .insert_status(
                instance.clone(),
                StoredStatus {
                    item: StatusItem {
                        id: "S1".into(),
                        author: chat.into(),
                        timestamp_ms: 10,
                        ..Default::default()
                    },
                    participant: chat.into(),
                    ..Default::default()
                },
                10,
            )
            .await
            .unwrap();
        store
            .set_push_token(instance.clone(), AppId::Messages, "token".into())
            .await
            .unwrap();
        // The tables no public call fills in one step.
        let conn = super::super::lock(&store.conn);
        conn.execute(
            "INSERT INTO receipts (instance_id, chat, id, participant, status)
             VALUES (?1, ?2, 'M1', ?2, 2)",
            params![instance.as_str(), chat],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO message_edits (instance_id, chat, id, edited_at, previous_text)
             VALUES (?1, ?2, 'M1', 30, 'before')",
            params![instance.as_str(), chat],
        )
        .unwrap();
        conn.execute(
            "INSERT OR REPLACE INTO profiles (instance_id, jid, push_name) VALUES (?1, ?2, 'Peer')",
            params![instance.as_str(), chat],
        )
        .unwrap();
        // People sync v3: a job, one of its numbers, one lookup answer.
        conn.execute(
            "INSERT INTO people_jobs (instance_id, job_id, digest, mode, state, phase, created_ms, updated_ms)
             VALUES (?1, 'job', 'd', 'auto', 'running', 'lookup', 1, 1)",
            [instance.as_str()],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO people_job_numbers (instance_id, seq, jid) VALUES (?1, 1, ?2)",
            params![instance.as_str(), chat],
        )
        .unwrap();
        conn.execute(
            "INSERT INTO people_lookups (instance_id, jid, on_whatsapp, checked_ms) VALUES (?1, ?2, 1, 1)",
            params![instance.as_str(), chat],
        )
        .unwrap();
    }

    fn rows(store: &Store, table: &str, instance: &InstanceId) -> u32 {
        super::super::lock(&store.conn)
            .query_row(
                &format!("SELECT COUNT(*) FROM {table} WHERE instance_id = ?1"),
                [instance.as_str()],
                |r| r.get(0),
            )
            .unwrap()
    }

    #[test]
    fn only_two_known_different_numbers_are_a_change() {
        use AccountCheck::{Changed, Same, Unknown};
        let cases = [
            (Some(OLD), Some(NEW), Changed),
            (Some(OLD), Some(OLD), Same),
            // The same account from another of its devices.
            (Some(OLD), Some("100000000001:7@s.whatsapp.net"), Same),
            (Some("100000000001.0:3@s.whatsapp.net"), Some(OLD), Same),
            // Either side unknown: never a change.
            (None, Some(NEW), Unknown),
            (Some(OLD), None, Unknown),
            (None, None, Unknown),
            (Some(""), Some(NEW), Unknown),
            (Some(OLD), Some(""), Unknown),
            // A LID or anything else that is not a phone number says nothing.
            (Some(OLD), Some("555@lid"), Unknown),
            (Some("555@lid"), Some(NEW), Unknown),
            (Some("100000000001"), Some(NEW), Unknown),
            (Some("@s.whatsapp.net"), Some(NEW), Unknown),
            (Some("12x@s.whatsapp.net"), Some(NEW), Unknown),
            (Some("1-2@g.us"), Some(NEW), Unknown),
        ];
        for (stored, linked, expected) in cases {
            assert_eq!(
                account_check(stored, linked),
                expected,
                "{stored:?} {linked:?}"
            );
        }
    }

    /// Every table with an `instance_id` is either cleared with the account
    /// or kept on purpose: a table added later has to pick a side here.
    #[tokio::test]
    async fn every_instance_table_is_cleared_or_kept_on_purpose() {
        let store = store_with(&[1]).await;
        let conn = super::super::lock(&store.conn);
        let mut stmt = conn
            .prepare(
                "SELECT m.name FROM sqlite_master m JOIN pragma_table_info(m.name) c
                 WHERE m.type = 'table' AND c.name = 'instance_id' ORDER BY m.name",
            )
            .unwrap();
        let tables: Vec<String> = stmt
            .query_map([], |r| r.get(0))
            .unwrap()
            .map(Result::unwrap)
            .collect();
        let kept = ["keys", "push_tokens"];
        let mut expected: Vec<&str> = ACCOUNT_TABLES.iter().chain(&kept).copied().collect();
        expected.sort_unstable();
        assert_eq!(tables, expected);
    }

    #[tokio::test]
    async fn another_number_clears_the_account_and_raises_the_epoch() {
        let store = store_with(&[1, 2]).await;
        fill(&store, id(1)).await;
        fill(&store, id(2)).await;
        store
            .note_history(id(1), HistorySyncType::Recent, Some(100), 50)
            .await
            .unwrap();
        for table in ACCOUNT_TABLES {
            assert_eq!(rows(&store, table, &id(1)), 1, "{table} is filled");
        }

        let change = store
            .link_account(id(1), Some(NEW.into()), None)
            .await
            .unwrap();
        assert_eq!(
            change,
            Some(AccountChange {
                old_pn: OLD.into(),
                new_pn: NEW.into(),
                account_epoch: 1,
            })
        );
        for table in ACCOUNT_TABLES {
            assert_eq!(rows(&store, table, &id(1)), 0, "{table} is cleared");
            assert_eq!(rows(&store, table, &id(2)), 1, "{table} of the other stays");
        }
        // The apps' keys and push tokens are not the account's.
        assert_eq!(rows(&store, "keys", &id(1)), 1);
        assert_eq!(rows(&store, "push_tokens", &id(1)), 1);

        let status = store.instance_status(id(1)).await.unwrap().unwrap();
        assert_eq!(status.own_jid.as_deref(), Some(NEW));
        assert_eq!(status.push_name, None);
        assert_eq!(status.account_epoch, Some(1));
        let history = status.history.unwrap();
        assert_eq!(history.state, HistoryState::Idle);
        assert_eq!((history.chats, history.messages), (0, 0));
        let other = store.instance_status(id(2)).await.unwrap().unwrap();
        assert_eq!(other.account_epoch, Some(0));
        assert_eq!(other.own_jid.as_deref(), Some(OLD));

        // The new account connecting again changes nothing more.
        assert_eq!(
            store
                .link_account(id(1), Some(NEW.into()), None)
                .await
                .unwrap(),
            None
        );
        // And a third account raises the epoch once more.
        let again = store
            .link_account(
                id(1),
                Some("100000000003@s.whatsapp.net".into()),
                Some(true),
            )
            .await
            .unwrap()
            .unwrap();
        assert_eq!(again.account_epoch, 2);
        assert_eq!(again.old_pn, NEW);
    }

    #[tokio::test]
    async fn the_same_or_an_unknown_number_clears_nothing() {
        let store = store_with(&[1, 2]).await;
        fill(&store, id(1)).await;
        let kept = |store: &Store| {
            ACCOUNT_TABLES
                .iter()
                .map(|table| rows(store, table, &id(1)))
                .collect::<Vec<_>>()
        };
        let before = kept(&store);

        // A reconnect, a relink of the same account (from another device
        // slot), no number, a LID.
        for (pn, paired) in [
            (Some(OLD.to_owned()), None),
            (
                Some("100000000001:12@s.whatsapp.net".to_owned()),
                Some(true),
            ),
            (None, Some(false)),
            (Some("555@lid".to_owned()), None),
        ] {
            assert_eq!(store.link_account(id(1), pn, paired).await.unwrap(), None);
            assert_eq!(kept(&store), before);
        }
        let status = store.instance_status(id(1)).await.unwrap().unwrap();
        assert_eq!(status.account_epoch, Some(0));
        assert_eq!(status.own_jid.as_deref(), Some(OLD));
        assert_eq!(status.push_name.as_deref(), Some("Old"));

        // An instance that never knew its number takes the first one as is.
        fill(&store, id(2)).await;
        super::super::lock(&store.conn)
            .execute(
                "UPDATE instances SET own_jid = NULL WHERE id = ?1",
                [id(2).as_str()],
            )
            .unwrap();
        assert_eq!(
            store
                .link_account(id(2), Some(NEW.into()), Some(true))
                .await
                .unwrap(),
            None
        );
        assert_eq!(rows(&store, "messages", &id(2)), 1);
        // An instance that does not exist.
        assert_eq!(
            store
                .link_account(id(9), Some(NEW.into()), None)
                .await
                .unwrap(),
            None
        );
    }

    fn mark(kind: HistorySyncType, progress: Option<u32>, overall: Option<u32>) -> HistoryMark {
        HistoryMark {
            kind,
            progress,
            overall,
            at_ms: 1000,
        }
    }

    #[test]
    fn a_recent_sync_runs_to_a_hundred() {
        use HistorySyncType::{Initial, Recent};
        assert_eq!(history_view(None, false, 0), (HistoryState::Idle, None));
        let first = advance(None, Initial, Some(3), false, 1000);
        assert_eq!(first, mark(Initial, Some(3), Some(3)));
        assert_eq!(
            history_view(Some(first), false, 1001),
            (HistoryState::Syncing, Some(3))
        );
        let mid = advance(Some(first), Recent, Some(60), false, 1000);
        assert_eq!(mid.overall, Some(60));
        // A chunk without a number, or one that arrives out of order, never
        // moves the bar back.
        assert_eq!(
            advance(Some(mid), Recent, None, false, 1000).overall,
            Some(60)
        );
        assert_eq!(
            advance(Some(mid), Recent, Some(40), false, 1000).overall,
            Some(60)
        );
        let last = advance(Some(mid), Recent, Some(100), false, 1000);
        assert_eq!(
            history_view(Some(last), false, 1001),
            (HistoryState::Done, Some(100))
        );
        // The first chunk at 100 does not end it: the recent ones follow.
        let early = advance(None, Initial, Some(100), false, 1000);
        assert_eq!(
            history_view(Some(early), false, 1001),
            (HistoryState::Syncing, Some(100))
        );
        // A new sync after a finished one starts from its own number.
        assert_eq!(
            advance(Some(last), Initial, Some(5), false, 2000).overall,
            Some(5)
        );
    }

    #[test]
    fn a_full_sync_ends_with_the_older_messages() {
        use HistorySyncType::{Full, Initial, Recent};
        let first = advance(None, Initial, Some(10), true, 1000);
        assert_eq!(first.overall, Some(2));
        let recent = advance(Some(first), Recent, Some(100), true, 1000);
        assert_eq!(recent.overall, Some(25));
        // The recent part is over, the older messages are still to come.
        assert_eq!(
            history_view(Some(recent), true, 1001),
            (HistoryState::Syncing, Some(25))
        );
        let half = advance(Some(recent), Full, Some(50), true, 1000);
        assert_eq!(half.overall, Some(62));
        assert_eq!(
            history_view(Some(half), true, 1001),
            (HistoryState::Syncing, Some(62))
        );
        let done = advance(Some(half), Full, Some(100), true, 1000);
        assert_eq!(done.overall, Some(100));
        assert_eq!(
            history_view(Some(done), true, 1001),
            (HistoryState::Done, Some(100))
        );
        // Older messages for an instance whose flag is unset still count as a
        // full sync.
        let unflagged = advance(Some(recent), Full, Some(100), false, 1000);
        assert_eq!(
            history_view(Some(unflagged), false, 1001),
            (HistoryState::Done, Some(100))
        );
    }

    #[test]
    fn a_sync_that_went_quiet_is_done_until_it_resumes() {
        use HistorySyncType::{Full, Recent};
        let recent = advance(None, Recent, Some(100), true, 1000);
        assert_eq!(
            history_view(Some(recent), true, 1000 + HISTORY_STALE_MS),
            (HistoryState::Syncing, Some(25))
        );
        assert_eq!(
            history_view(Some(recent), true, 1001 + HISTORY_STALE_MS),
            (HistoryState::Done, Some(25))
        );
        // The phone came back: the bar goes on from where it was.
        let resumed = advance(Some(recent), Full, Some(20), true, 9_000_000);
        assert_eq!(
            history_view(Some(resumed), true, 9_000_001),
            (HistoryState::Syncing, Some(40))
        );
    }

    #[test]
    fn only_chat_history_moves_the_state() {
        use HistorySyncType::{Full, Initial, OnDemand, Other, Recent};
        for kind in [Initial, Recent, Full] {
            assert!(is_history_chunk(kind));
        }
        assert!(!is_history_chunk(OnDemand));
        assert!(!is_history_chunk(Other));
    }

    /// The state is read back from the database, as after a restart.
    #[tokio::test]
    async fn the_history_state_survives_a_restart() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("gateway.db");
        let now = crate::now_ms();
        {
            let store = Store::open(&path).unwrap();
            let key = ConnectKey::from_bytes(&[1; 32]);
            let new = NewKey {
                key_id: KeyId::from_bytes(&[1; 8]),
                hash: key.hash(),
                label: "k".into(),
            };
            store.create_instance(id(1), new, 1, 4).await.unwrap();
            fill(&store, id(1)).await;
            store
                .link_account(id(1), Some(OLD.into()), Some(true))
                .await
                .unwrap();
            store
                .note_history(id(1), HistorySyncType::Recent, Some(100), now)
                .await
                .unwrap();
            store
                .note_history(id(1), HistorySyncType::Full, Some(40), now)
                .await
                .unwrap();
            // Push names and the like say nothing about the chat history.
            store
                .note_history(id(1), HistorySyncType::Other, Some(100), now)
                .await
                .unwrap();
        }
        let store = Store::open(&path).unwrap();
        let status = store.instance_status(id(1)).await.unwrap().unwrap();
        assert_eq!(status.account_epoch, Some(0));
        assert_eq!(
            status.history,
            Some(HistoryStatus {
                state: HistoryState::Syncing,
                progress: Some(55),
                chats: 1,
                messages: 1,
                oldest_ms: Some(20),
            })
        );
        store
            .note_history(id(1), HistorySyncType::Full, Some(100), now)
            .await
            .unwrap();
        let history = store
            .instance_status(id(1))
            .await
            .unwrap()
            .unwrap()
            .history
            .unwrap();
        assert_eq!(
            (history.state, history.progress),
            (HistoryState::Done, Some(100))
        );

        // A relink of the same account starts the sync state over and keeps
        // what is stored.
        store
            .link_account(id(1), Some(OLD.into()), Some(false))
            .await
            .unwrap();
        let history = store
            .instance_status(id(1))
            .await
            .unwrap()
            .unwrap()
            .history
            .unwrap();
        assert_eq!(
            history,
            HistoryStatus {
                state: HistoryState::Idle,
                progress: None,
                chats: 1,
                messages: 1,
                oldest_ms: Some(20),
            }
        );
    }
}
