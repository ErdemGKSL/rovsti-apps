//! `chat_find.*` and `global_search.*`: finding messages inside one chat and across all chats.

use super::{NOW, Span};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::chat_find::{self, FindCursor};
use rovsti_core::global_search::{self, JumpStep, SearchCandidate, Source};
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[
    op!("chat_find.find", FindReq, FindAns, find, find_cases),
    op!(
        "chat_find.cursor",
        CursorReq,
        CursorAns,
        cursor,
        cursor_cases
    ),
    op!(
        "global_search.search",
        SearchReq,
        SearchAns,
        search,
        search_cases
    ),
    op!(
        "global_search.prepare",
        PrepareReq,
        PrepareAns,
        prepare,
        prepare_cases
    ),
    op!(
        "global_search.footer",
        FooterReq,
        TextAns,
        footer,
        footer_cases
    ),
    op!("global_search.jump", JumpReq, JumpAns, jump, jump_cases),
];

#[derive(Serialize)]
struct TextAns {
    text: String,
}

// ---- chat_find ----

#[derive(Deserialize)]
struct FindReq {
    texts: Vec<String>,
    query: String,
}

#[derive(Serialize)]
struct FindAns {
    /// Indices into `texts` that contain the folded query, oldest first.
    hits: Vec<usize>,
}

fn find(req: FindReq) -> Result<FindAns, FfiError> {
    let texts: Vec<&str> = req.texts.iter().map(String::as_str).collect();
    Ok(FindAns {
        hits: chat_find::find(&texts, &req.query),
    })
}

fn find_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"texts": ["Merhaba", "Kahve içelim mi?", "tamam", "KAHVE tamam"],
                         "query": " kahve "}),
        ),
        Case::named("blank", json!({"texts": ["a"], "query": "  "})),
    ]
}

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum CursorAction {
    /// Just describe the cursor at `at` (the newest hit when absent).
    Start,
    Older,
    Newer,
    /// Fresh `new_hits` after pages loaded: stay on `keep`, one hit older when `step_older`.
    Resume,
    /// Replace the hits, keeping the current item when it is still a hit.
    Retarget,
}

#[derive(Deserialize)]
struct CursorReq {
    hits: Vec<usize>,
    /// Position in `hits` of the current hit; the newest when absent.
    #[serde(default)]
    at: Option<usize>,
    action: CursorAction,
    #[serde(default)]
    new_hits: Vec<usize>,
    #[serde(default)]
    keep: Option<usize>,
    #[serde(default)]
    step_older: bool,
}

#[derive(Serialize)]
struct CursorAns {
    hits: Vec<usize>,
    /// Position in `hits` of the current hit.
    at: Option<usize>,
    /// Item index of the current hit.
    current: Option<usize>,
    /// "2 of 5" or "No matches".
    label: String,
    empty: bool,
    /// The current hit is the oldest one: older pages may hold more.
    oldest: bool,
}

fn cursor(req: CursorReq) -> Result<CursorAns, FfiError> {
    let mut hits = req.hits;
    let mut cursor = match req.at.and_then(|a| hits.get(a).copied()) {
        Some(item) => FindCursor::resume(hits.clone(), Some(item), false),
        None => FindCursor::new(hits.clone()),
    };
    match req.action {
        CursorAction::Start => {}
        CursorAction::Older => {
            cursor.older();
        }
        CursorAction::Newer => {
            cursor.newer();
        }
        CursorAction::Resume => {
            hits = req.new_hits.clone();
            cursor = FindCursor::resume(req.new_hits, req.keep, req.step_older);
        }
        CursorAction::Retarget => {
            hits = req.new_hits.clone();
            cursor.retarget(req.new_hits);
        }
    }
    let current = cursor.current();
    Ok(CursorAns {
        at: current.and_then(|c| hits.iter().position(|&h| h == c)),
        current,
        label: cursor.label(),
        empty: cursor.is_empty(),
        oldest: cursor.is_oldest(),
        hits,
    })
}

fn cursor_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"hits": [1, 4, 9], "action": "start"})),
        Case::named("older", json!({"hits": [1, 4, 9], "action": "older"})),
        Case::named(
            "wraps_newer",
            json!({"hits": [1, 4, 9], "at": 2, "action": "newer"}),
        ),
        Case::named("none", json!({"hits": [], "action": "older"})),
        Case::named(
            "resume",
            json!({"hits": [4, 9], "at": 0, "action": "resume", "new_hits": [2, 4, 9],
                   "keep": 4, "step_older": true}),
        ),
        Case::named(
            "retarget",
            json!({"hits": [1, 4, 9], "at": 1, "action": "retarget", "new_hits": [3, 9]}),
        ),
    ]
}

// ---- global_search ----

#[derive(Deserialize)]
struct Candidate {
    chat_key: String,
    chat_title: String,
    message_id: String,
    #[serde(default)]
    author: Option<String>,
    text: String,
    at_ms: i64,
}

#[derive(Deserialize)]
struct SearchReq {
    query: String,
    /// Only recent chats were read: the summary says so.
    #[serde(default)]
    partial: bool,
    items: Vec<Candidate>,
}

#[derive(Serialize)]
struct Hit {
    chat_key: String,
    chat_title: String,
    message_id: String,
    at_ms: i64,
    snippet: String,
    /// UTF-16 range of the match inside `snippet`.
    highlight: Span,
}

#[derive(Serialize)]
struct SearchAns {
    /// Newest first, at most 200.
    hits: Vec<Hit>,
    /// "3 results", "No results", "200+ results", plus "in recent chats" when partial.
    summary: String,
}

fn search(req: SearchReq) -> Result<SearchAns, FfiError> {
    let items: Vec<SearchCandidate<'_>> = req
        .items
        .iter()
        .map(|c| SearchCandidate {
            chat_key: &c.chat_key,
            chat_title: &c.chat_title,
            message_id: &c.message_id,
            author: c.author.as_deref(),
            text: &c.text,
            at_ms: c.at_ms,
        })
        .collect();
    let found = global_search::search(&req.query, &items);
    Ok(SearchAns {
        summary: global_search::summary(found.len(), req.partial),
        hits: found
            .into_iter()
            .map(|h| Hit {
                highlight: Span::of(&h.snippet, &h.highlight),
                chat_key: h.chat_key,
                chat_title: h.chat_title,
                message_id: h.message_id,
                at_ms: h.at_ms,
                snippet: h.snippet,
            })
            .collect(),
    })
}

fn candidate(key: &str, title: &str, id: &str, text: &str, at: i64) -> serde_json::Value {
    json!({"chat_key": key, "chat_title": title, "message_id": id, "text": text, "at_ms": at})
}

fn search_cases() -> Vec<Case> {
    let items = json!([
        candidate(
            "sms:1",
            "Ayse",
            "sms:10",
            "Akşam kahve içelim mi?",
            NOW - 3_600_000
        ),
        candidate(
            "wa:90@s.whatsapp.net",
            "Mehmet",
            "3EB0",
            "Kahve   benden\ntamam",
            NOW
        ),
        candidate("sms:1", "Ayse", "sms:4", "Toplantı yarın", NOW - 86_400_000),
    ]);
    vec![
        Case::new(json!({"query": "KAHVE", "items": items})),
        Case::named(
            "partial",
            json!({"query": "toplanti", "partial": true, "items": items}),
        ),
        Case::named("too_short", json!({"query": "k", "items": items})),
    ]
}

#[derive(Deserialize)]
struct ChatRecency {
    key: String,
    last_ms: i64,
}

#[derive(Deserialize)]
struct PrepareReq {
    query: String,
    #[serde(default)]
    chats: Vec<ChatRecency>,
    /// How many recent WhatsApp chats to read (15 when absent).
    #[serde(default)]
    limit: Option<usize>,
}

#[derive(Serialize)]
struct PrepareAns {
    /// The query is long enough to search.
    searchable: bool,
    min_query_chars: usize,
    /// `%query%` for `LIKE ... ESCAPE '\'` on the SMS provider.
    like: String,
    /// The newest `limit` chat keys, newest first.
    recent: Vec<String>,
    /// Older pages a jump to a hit loads before giving up.
    max_jump_pages: usize,
}

fn prepare(req: PrepareReq) -> Result<PrepareAns, FfiError> {
    let chats: Vec<(String, i64)> = req.chats.into_iter().map(|c| (c.key, c.last_ms)).collect();
    Ok(PrepareAns {
        searchable: req.query.trim().chars().count() >= global_search::MIN_QUERY_CHARS,
        min_query_chars: global_search::MIN_QUERY_CHARS,
        like: global_search::like_pattern(&req.query),
        recent: global_search::most_recent(&chats, req.limit.unwrap_or(global_search::WA_CHATS)),
        max_jump_pages: global_search::MAX_JUMP_PAGES,
    })
}

fn prepare_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"query": "100%_off",
            "chats": [{"key": "wa:a", "last_ms": 5}, {"key": "wa:b", "last_ms": 9}, {"key": "wa:c", "last_ms": 1}],
            "limit": 2})),
        Case::named("short", json!({"query": " a "})),
    ]
}

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum SourceDto {
    Searched,
    NoAccess,
    NotLinked,
    Failed,
}

impl From<SourceDto> for Source {
    fn from(s: SourceDto) -> Self {
        match s {
            SourceDto::Searched => Self::Searched,
            SourceDto::NoAccess => Self::NoAccess,
            SourceDto::NotLinked => Self::NotLinked,
            SourceDto::Failed => Self::Failed,
        }
    }
}

#[derive(Deserialize)]
struct FooterReq {
    hits: usize,
    #[serde(default)]
    partial: bool,
    sms: SourceDto,
    wa: SourceDto,
}

fn footer(req: FooterReq) -> Result<TextAns, FfiError> {
    Ok(TextAns {
        text: global_search::footer(req.hits, req.partial, req.sms.into(), req.wa.into()),
    })
}

fn footer_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"hits": 3, "sms": "searched", "wa": "searched"})),
        Case::named(
            "sources_missing",
            json!({"hits": 0, "partial": true, "sms": "no_access", "wa": "not_linked"}),
        ),
        Case::named(
            "failed",
            json!({"hits": 1, "sms": "failed", "wa": "failed"}),
        ),
    ]
}

#[derive(Deserialize)]
struct JumpReq {
    loaded: bool,
    has_older: bool,
    pages_loaded: usize,
    #[serde(default)]
    oldest_ms: Option<i64>,
    target_ms: i64,
}

#[derive(Serialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum JumpKind {
    Reveal,
    LoadOlder,
    FurtherBack,
    Gone,
}

#[derive(Serialize)]
struct JumpAns {
    step: JumpKind,
    /// The pill for a jump that stopped short.
    notice: Option<&'static str>,
}

fn jump(req: JumpReq) -> Result<JumpAns, FfiError> {
    let step = global_search::jump_step(
        req.loaded,
        req.has_older,
        req.pages_loaded,
        req.oldest_ms,
        req.target_ms,
    );
    Ok(JumpAns {
        notice: global_search::jump_notice(step),
        step: match step {
            JumpStep::Reveal => JumpKind::Reveal,
            JumpStep::LoadOlder => JumpKind::LoadOlder,
            JumpStep::FurtherBack => JumpKind::FurtherBack,
            JumpStep::Gone => JumpKind::Gone,
        },
    })
}

fn jump_cases() -> Vec<Case> {
    let base = json!({"loaded": false, "has_older": true, "pages_loaded": 2,
                      "oldest_ms": NOW, "target_ms": NOW - 1000});
    let with = |k: &str, v: serde_json::Value| {
        let mut b = base.clone();
        b[k] = v;
        b
    };
    vec![
        Case::new(with("oldest_ms", json!(NOW - 5000))),
        Case::named("loaded", with("loaded", json!(true))),
        Case::named("further_back", with("pages_loaded", json!(10))),
        Case::named("load_older", base.clone()),
    ]
}
