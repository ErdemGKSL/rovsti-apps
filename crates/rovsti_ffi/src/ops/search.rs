//! `search.*` and `t9.*`: the contact index as a native handle.
//!
//! `search.index_new` builds a `ContactIndex` (and the `T9Scratch` that goes with it) once per contacts payload
//! and answers its handle; queries then send only the typed text, so a keystroke costs one small JSON round trip
//! and never re-sends the contacts. The handle is released with `RustNative.release`.

use super::registry::{Case, OpSpec, PLACEHOLDER_HANDLE as H};
use crate::envelope::FfiError;
use crate::handles;
use crate::op;
use rovsti_core::search::{self, ContactIndex};
use rovsti_core::t9::T9Scratch;
use rovsti_core::wire::{NameFormat, WireContact, WireNumber};
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::HashMap;

/// What a handle holds: the index and the T9 memory of its last query. The handle registry locks per call, so
/// no second mutex is needed.
struct Contacts {
    index: ContactIndex,
    t9: T9Scratch,
}

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "search.index_new",
        NewReq,
        NewAns,
        index_new,
        index_new_cases
    ),
    op!(
        "search.index_replace",
        ReplaceReq,
        NewAns,
        index_replace,
        index_replace_cases
    ),
    op!(
        "search.set_call_counts",
        CountsReq,
        Empty,
        set_call_counts,
        counts_cases
    ),
    op!("search.query", QueryReq, QueryAns, query, query_cases),
    op!(
        "search.query_loose",
        QueryReq,
        QueryAns,
        query_loose,
        loose_cases
    ),
    op!(
        "search.match_number",
        MatchReq,
        MatchAns,
        match_number,
        match_cases
    ),
    op!("search.fold", FoldReq, FoldAns, fold, fold_cases),
    op!("t9.query", T9Req, T9Ans, t9_query, t9_cases),
];

#[derive(Serialize)]
struct Empty {}

/// Runs `f` on the contacts of `handle`; a released or foreign handle is `not_found`.
fn with_contacts<R>(
    handle: i64,
    f: impl FnOnce(&mut Contacts) -> Result<R, FfiError>,
) -> Result<R, FfiError> {
    let answer = handles::global().with(handle, f);
    answer.unwrap_or_else(|| {
        Err(crate::envelope::FfiError::new(
            crate::envelope::ErrorKind::NotFound,
            format!("contact index handle {handle} is not open"),
        ))
    })
}

fn contacts_of(contacts: Vec<WireContact>, format: NameFormat) -> Contacts {
    let mut t9 = T9Scratch::new();
    t9.set_format(format);
    Contacts {
        index: ContactIndex::new(contacts),
        t9,
    }
}

// ---- index_new / index_replace ----

#[derive(Deserialize)]
struct NewReq {
    contacts: Vec<WireContact>,
    #[serde(default)]
    name_format: NameFormat,
}

#[derive(Serialize)]
struct NewAns {
    handle: i64,
    count: usize,
}

fn index_new(req: NewReq) -> Result<NewAns, FfiError> {
    let count = req.contacts.len();
    let handle = handles::global().insert(contacts_of(req.contacts, req.name_format));
    Ok(NewAns { handle, count })
}

pub(super) fn sample() -> serde_json::Value {
    json!([
        {"id": 1, "display_name": "Ayşe Yılmaz", "numbers": [{"raw": "0532 123 45 67", "default": true}], "starred": true},
        {"id": 2, "display_name": "Mehmet Demir", "numbers": [{"raw": "+90 212 555 01 02", "label": "Work"}]},
        {"id": 3, "display_name": "Борис Иванов", "numbers": [{"raw": "+7 495 123 45 67"}], "email": "boris@example.org"},
        {"id": 4, "display_name": "Zeynep Kaya", "numbers": [], "email": "zeynep@example.org"}
    ])
}

fn index_new_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"contacts": sample(), "name_format": "first_last"})),
        Case::named("empty", json!({"contacts": []})),
    ]
}

#[derive(Deserialize)]
struct ReplaceReq {
    handle: i64,
    contacts: Vec<WireContact>,
}

fn index_replace(req: ReplaceReq) -> Result<NewAns, FfiError> {
    let count = req.contacts.len();
    with_contacts(req.handle, |c| {
        c.index = ContactIndex::new(req.contacts);
        c.t9.forget();
        Ok(())
    })?;
    Ok(NewAns {
        handle: req.handle,
        count,
    })
}

fn setup() -> (&'static str, serde_json::Value) {
    ("search.index_new", json!({"contacts": sample()}))
}

fn index_replace_cases() -> Vec<Case> {
    let (op, req) = setup();
    vec![
        Case::new(json!({"handle": H, "contacts": [
            {"id": 9, "display_name": "Cem Aksoy", "numbers": [{"raw": "0555 000 11 22"}]}
        ]}))
        .after(op, req.clone()),
        Case::named("stale_handle", json!({"handle": H, "contacts": []})),
    ]
}

// ---- call counts ----

#[derive(Deserialize)]
struct CountsReq {
    handle: i64,
    counts: Vec<CallCount>,
}

#[derive(Deserialize)]
struct CallCount {
    id: i64,
    count: u32,
}

fn set_call_counts(req: CountsReq) -> Result<Empty, FfiError> {
    let map: HashMap<i64, u32> = req.counts.into_iter().map(|c| (c.id, c.count)).collect();
    with_contacts(req.handle, |c| {
        c.index.set_call_counts(&map);
        c.t9.forget();
        Ok(Empty {})
    })
}

fn counts_cases() -> Vec<Case> {
    let (op, req) = setup();
    vec![
        Case::new(json!({"handle": H, "counts": [{"id": 2, "count": 7}, {"id": 1, "count": 2}]}))
            .after(op, req),
    ]
}

// ---- search.query / query_loose ----

#[derive(Deserialize)]
struct QueryReq {
    handle: i64,
    query: String,
    /// At most this many rows; absent means all matches.
    #[serde(default)]
    limit: Option<usize>,
}

#[derive(Serialize)]
struct QueryAns {
    /// Contact ids in answer order (the same order as `rows`).
    ids: Vec<i64>,
    rows: Vec<WireContact>,
    /// Matches before `limit` cut the list.
    total: usize,
}

fn answer(found: Vec<&WireContact>, limit: Option<usize>) -> QueryAns {
    let total = found.len();
    let rows: Vec<WireContact> = found
        .into_iter()
        .take(limit.unwrap_or(usize::MAX))
        .cloned()
        .collect();
    QueryAns {
        ids: rows.iter().map(|r| r.id).collect(),
        rows,
        total,
    }
}

fn query(req: QueryReq) -> Result<QueryAns, FfiError> {
    with_contacts(req.handle, |c| {
        Ok(answer(c.index.search(&req.query), req.limit))
    })
}

fn query_loose(req: QueryReq) -> Result<QueryAns, FfiError> {
    with_contacts(req.handle, |c| {
        Ok(answer(c.index.search_loose(&req.query), req.limit))
    })
}

fn query_cases() -> Vec<Case> {
    let (op, req) = setup();
    vec![
        Case::new(json!({"handle": H, "query": "ayse", "limit": 10})).after(op, req.clone()),
        Case::named("digits", json!({"handle": H, "query": "212 555"})).after(op, req.clone()),
        Case::named(
            "cyrillic",
            json!({"handle": H, "query": "ИВАН", "limit": 1}),
        )
        .after(op, req.clone()),
        Case::named("empty_query", json!({"handle": H, "query": "", "limit": 2}))
            .after(op, req.clone()),
        Case::named("stale_handle", json!({"handle": H, "query": "a"})),
    ]
}

fn loose_cases() -> Vec<Case> {
    let (op, req) = setup();
    vec![
        Case::new(json!({"handle": H, "query": "ay"})).after(op, req.clone()),
        Case::named("words", json!({"handle": H, "query": "yilmaz ayse"})).after(op, req),
    ]
}

// ---- match_number ----

#[derive(Deserialize)]
struct MatchReq {
    handle: i64,
    number: String,
}

#[derive(Serialize)]
struct MatchAns {
    id: Option<i64>,
    row: Option<WireContact>,
}

fn match_number(req: MatchReq) -> Result<MatchAns, FfiError> {
    with_contacts(req.handle, |c| {
        let row = c.index.match_number(&req.number).cloned();
        Ok(MatchAns {
            id: row.as_ref().map(|r| r.id),
            row,
        })
    })
}

fn match_cases() -> Vec<Case> {
    let (op, req) = setup();
    vec![
        Case::new(json!({"handle": H, "number": "+90 532 123 45 67"})).after(op, req.clone()),
        Case::named("unknown", json!({"handle": H, "number": "+49 151 2345678"}))
            .after(op, req.clone()),
        Case::named("unparsable", json!({"handle": H, "number": "abc"})).after(op, req),
    ]
}

// ---- fold ----

#[derive(Deserialize)]
struct FoldReq {
    text: String,
}

#[derive(Serialize)]
struct FoldAns {
    folded: String,
}

fn fold(req: FoldReq) -> Result<FoldAns, FfiError> {
    Ok(FoldAns {
        folded: search::fold(&req.text),
    })
}

fn fold_cases() -> Vec<Case> {
    vec![Case::new(json!({"text": "İSTANBUL Çay Şeker Ğ"}))]
}

// ---- t9.query ----

#[derive(Deserialize)]
struct T9Req {
    handle: i64,
    digits: String,
    /// Changes the name order the spans refer to; absent keeps the handle's.
    #[serde(default)]
    name_format: Option<NameFormat>,
}

#[derive(Serialize)]
struct T9Row {
    contact_idx: u32,
    id: i64,
    name: String,
    number_idx: u16,
    number: String,
    name_span: Option<(u16, u16)>,
    rank: u8,
}

#[derive(Serialize)]
struct T9Ans {
    hits: Vec<T9Row>,
}

fn t9_query(req: T9Req) -> Result<T9Ans, FfiError> {
    with_contacts(req.handle, |c| {
        if let Some(format) = req.name_format {
            c.t9.set_format(format);
        }
        let Contacts { index, t9 } = c;
        let hits: Vec<_> = index.t9(req.digits.as_bytes(), t9).to_vec();
        let rows = hits
            .into_iter()
            .filter_map(|hit| {
                let contact = index.contact(hit.contact_idx)?;
                let number = contact.numbers.get(usize::from(hit.number_idx));
                Some(T9Row {
                    contact_idx: hit.contact_idx,
                    id: contact.id,
                    name: contact.display_name.clone(),
                    number_idx: hit.number_idx,
                    number: number
                        .map(|n: &WireNumber| n.raw.clone())
                        .unwrap_or_default(),
                    name_span: hit.name_span,
                    rank: hit.rank,
                })
            })
            .collect();
        Ok(T9Ans { hits: rows })
    })
}

fn t9_cases() -> Vec<Case> {
    let (op, req) = setup();
    vec![
        Case::new(json!({"handle": H, "digits": "29"})).after(op, req.clone()),
        Case::named("by_number", json!({"handle": H, "digits": "532"})).after(op, req.clone()),
        Case::named("not_digits", json!({"handle": H, "digits": "2*9"})).after(op, req.clone()),
        Case::named(
            "last_first",
            json!({"handle": H, "digits": "945", "name_format": "last_first"}),
        )
        .after(op, req),
    ]
}
