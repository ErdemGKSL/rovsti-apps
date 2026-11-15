//! Sync planning as pure functions: what to do with a `since(seq)` answer, which chats a resync must page, when a
//! page closes a gap, and how to ask for older history.
//!
//! The gateway pages with `GET /v1/chats/{jid}/messages?before=<ms>&limit=<n>`: newest first, strictly older than
//! `before`, 1 to 200 per page.

use rosty_wa_proto::{EventEnvelope, GatewayEvent, Since, WaMessage};
use serde::{Deserialize, Serialize};

/// The gateway's default page.
pub const DEFAULT_PAGE: u32 = 50;
/// The gateway's largest page.
pub const MAX_PAGE: u32 = 200;

/// A page size the gateway accepts.
pub fn clamp_limit(limit: Option<u32>) -> u32 {
    limit.unwrap_or(DEFAULT_PAGE).clamp(1, MAX_PAGE)
}

/// One `GET .../messages` call to make.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct FetchPage {
    pub chat: String,
    /// `before=`; `None` asks for the newest page.
    pub before: Option<i64>,
    pub limit: u32,
}

/// The hole a resync is filling in one chat.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Gap {
    /// The newest message stored when the resync began; a page that reaches it overlaps.
    pub floor_ts: i64,
    /// `before=` of the next page; `None` for the first.
    pub before: Option<i64>,
}

/// What the store knows about one chat's history.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ChatCursor {
    pub newest_ts: Option<i64>,
    pub newest_id: Option<String>,
    pub oldest_ts: Option<i64>,
    pub reached_start: bool,
    pub gap: Option<Gap>,
}

/// The instance row.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SyncState {
    pub instance_id: String,
    pub last_seq: Option<u64>,
    pub needs_resync: bool,
    pub chats_synced_ms: Option<i64>,
}

/// The work the store still owes, from what is persisted: what to subscribe after, whether to refresh the chat
/// list, and the pages of open gaps.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct SyncPlan {
    /// `after=` of the event socket; `None` on the first run.
    pub after_seq: Option<u64>,
    pub refresh_chats: bool,
    pub fetch: Vec<FetchPage>,
    /// `since=` of the catch-up on the gateway's LID and number pairs (`GET /v1/identity/mappings`); `None`
    /// before the first page was taken in: ask for every pair.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub identity_since: Option<String>,
}

/// One step of answering a `since(seq)` result.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Step {
    /// Apply these events, oldest first (already-applied ones are left out).
    Apply(Vec<u64>),
    /// Remember this `seq` as the last one seen.
    AdvanceSeq(u64),
    /// Refetch `GET /v1/chats`, then page the chats that changed ([`chat_gap`]).
    RefreshChats,
}

/// True when `envelope` is news for a store at `last_seq`. A `resync` is always news: it also resets the sequence
/// (after a gateway restart the numbers start over).
pub fn accepts(last_seq: Option<u64>, envelope: &EventEnvelope) -> bool {
    matches!(envelope.event, GatewayEvent::Resync { .. })
        || last_seq.is_none_or(|last| envelope.seq > last)
}

/// What to do with the gateway's answer to "events since my `seq`". `head_seq` is the gateway's newest sequence
/// (the `seq` of its `resync` event) and only matters for a gap.
pub fn since(state: &SyncState, answer: &Since, head_seq: u64) -> Vec<Step> {
    match answer {
        Since::Events(events) => {
            let mut last = state.last_seq;
            let mut fresh = Vec::new();
            for envelope in events {
                if accepts(last, envelope) {
                    fresh.push(envelope.seq);
                    last = Some(envelope.seq);
                }
            }
            let mut steps = Vec::new();
            if let Some(&newest) = fresh.last() {
                steps.push(Step::Apply(fresh));
                steps.push(Step::AdvanceSeq(newest));
            }
            if state.needs_resync {
                steps.push(Step::RefreshChats);
            }
            steps
        }
        Since::Gap => vec![Step::AdvanceSeq(head_seq), Step::RefreshChats],
    }
}

/// Whether a chat needs paging after the chat list was refreshed, given the gateway's newest message of it.
///
/// * Nothing stored: no gap, the newest message is stored from the list and older history comes on demand.
/// * A gap left open by an interrupted resync starts over from the top and keeps its floor.
/// * The gateway's newest message is the stored newest one: nothing was missed, unless `refresh` asks for one page
///   anyway (to pick up edits, deletes and ticks of recent messages).
/// * Otherwise: a gap from the stored newest message up.
pub fn chat_gap(cursor: &ChatCursor, last: Option<&WaMessage>, refresh: bool) -> Option<Gap> {
    if let Some(open) = cursor.gap {
        return Some(Gap {
            floor_ts: open.floor_ts,
            before: None,
        });
    }
    let floor_ts = cursor.newest_ts?;
    let last = last?;
    let same = cursor.newest_id.as_deref() == Some(last.id.as_str());
    let behind = last.timestamp_ms < floor_ts;
    if (same || behind) && !refresh {
        return None;
    }
    Some(Gap {
        floor_ts,
        before: None,
    })
}

/// `before=` of the page after one that spans `oldest_ts..=newest_ts`. Messages sharing the oldest millisecond are
/// asked for again (the upsert is idempotent) so none is skipped at the page edge; a page of one single millisecond
/// steps past it so paging always moves.
pub fn next_before(oldest_ts: i64, newest_ts: i64) -> i64 {
    if oldest_ts < newest_ts {
        oldest_ts.saturating_add(1)
    } else {
        oldest_ts
    }
}

/// What a page means for an open gap: `None` closes it (the page reached the floor, or the gateway has nothing
/// older), `Some` is the gap with the next `before=`.
pub fn after_page(
    gap: Gap,
    page_ts: Option<(i64, i64)>,
    page_len: usize,
    limit: u32,
) -> Option<Gap> {
    let (oldest, newest) = page_ts?;
    if page_len < limit as usize || oldest <= gap.floor_ts {
        return None;
    }
    Some(Gap {
        floor_ts: gap.floor_ts,
        before: Some(next_before(oldest, newest)),
    })
}

/// The page before the oldest stored message (scrolling up past it); `None` when there is no older history. The
/// oldest millisecond is asked for again, see [`next_before`].
pub fn older(chat: &str, cursor: &ChatCursor, limit: u32) -> Option<FetchPage> {
    if cursor.reached_start {
        return None;
    }
    Some(FetchPage {
        chat: chat.to_owned(),
        before: cursor.oldest_ts.map(|ts| ts.saturating_add(1)),
        limit,
    })
}

/// The pages a resync must fetch first, one per chat with a gap.
pub fn gap_pages<'a>(gaps: impl IntoIterator<Item = (&'a str, Gap)>, limit: u32) -> Vec<FetchPage> {
    gaps.into_iter()
        .map(|(chat, gap)| FetchPage {
            chat: chat.to_owned(),
            before: gap.before,
            limit,
        })
        .collect()
}
