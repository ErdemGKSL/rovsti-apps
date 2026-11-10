//! `recents.*`, `call_log.*`, `call_notes.*`, `call_stats.*`: the call history.

use super::{NOW, entries, extras, locale};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::call_log::{self, Frequent, RecentsRow};
use rovsti_core::call_log_query::{self, PruneAge, Query};
use rovsti_core::call_notes::{self, CallDetails, CallNote, NoteRow, Notes, SetOutcome};
use rovsti_core::call_stats::{self, DayBar, PersonStat, Range, StatCall, Totals};
use rovsti_core::recents::{self, ExtraCall, HistoryLine, LocaleTime, RefreshDelta, RowView};
use rovsti_core::wire::CallLogEntry;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use std::collections::HashMap;

pub(super) const OPS: &[OpSpec] = &[
    op!("recents.group", GroupReq, RowsAns, group, group_cases),
    op!("recents.build", BuildReq, BuildAns, build, build_cases),
    op!(
        "recents.refresh_delta",
        DeltaReq,
        RefreshDelta,
        refresh_delta,
        delta_cases
    ),
    op!(
        "recents.merge_extras",
        MergeReq,
        RowsAns,
        merge_extras,
        merge_cases
    ),
    op!(
        "recents.missed_extras",
        MissedReq,
        MissedAns,
        missed_extras,
        missed_cases
    ),
    op!(
        "recents.history",
        HistoryReq,
        HistoryAns,
        history,
        history_cases
    ),
    op!(
        "recents.blocked_history",
        BlockedHistoryReq,
        HistoryAns,
        blocked_history,
        blocked_history_cases
    ),
    op!(
        "recents.history_text",
        HistoryTextReq,
        HistoryTextAns,
        history_text,
        history_text_cases
    ),
    op!(
        "recents.section_starts",
        SectionReq,
        SectionAns,
        section_starts,
        section_cases
    ),
    op!("call_log.query", FindReq, FindAns, find, find_cases),
    op!(
        "call_log.frequent",
        FrequentReq,
        FrequentAns,
        frequent,
        frequent_cases
    ),
    op!(
        "call_log.counts",
        CountsReq,
        CountsAns,
        counts,
        counts_cases
    ),
    op!(
        "call_log.ids_for_numbers",
        IdsReq,
        IdsAns,
        ids_for_numbers,
        ids_cases
    ),
    op!("call_log.csv", CsvReq, CsvAns, csv, csv_cases),
    op!(
        "call_log.prune_plan",
        PruneReq,
        PruneAns,
        prune_plan,
        prune_cases
    ),
    op!("call_notes.set", SetReq, SetAns, notes_set, notes_set_cases),
    op!(
        "call_notes.remove",
        RemoveReq,
        RemoveAns,
        notes_remove,
        notes_remove_cases
    ),
    op!(
        "call_notes.restore",
        RestoreReq,
        NotesAns,
        notes_restore,
        notes_restore_cases
    ),
    op!(
        "call_notes.rows",
        NoteRowsReq,
        NoteRowsAns,
        notes_rows,
        notes_rows_cases
    ),
    op!(
        "call_notes.for_calls",
        ForCallsReq,
        ForCallsAns,
        notes_for_calls,
        for_calls_cases
    ),
    op!(
        "call_notes.details",
        DetailsReq,
        DetailsAns,
        notes_details,
        details_cases
    ),
    op!(
        "call_notes.export",
        NotesExportReq,
        NotesExportAns,
        notes_export,
        notes_export_cases
    ),
    op!(
        "call_stats.overview",
        OverviewReq,
        OverviewAns,
        overview,
        overview_cases
    ),
    op!(
        "call_stats.person",
        PersonReq,
        PersonAns,
        person,
        person_cases
    ),
    op!(
        "call_stats.bucket",
        BucketReq,
        BucketAns,
        bucket,
        bucket_cases
    ),
];

fn strs(list: &[String]) -> Vec<&str> {
    list.iter().map(String::as_str).collect()
}

/// A name lookup from a `{number: name}` request map: exact text first, then the digits of the number.
fn name_lookup(names: &HashMap<String, String>) -> impl Fn(&str) -> Option<String> + '_ {
    move |number| names.get(number.trim()).cloned()
}

// ---- recents.* ----

#[derive(Deserialize)]
struct GroupReq {
    entries: Vec<CallLogEntry>,
}

#[derive(Serialize, Deserialize)]
struct RowsAns {
    rows: Vec<RecentsRow>,
}

fn group(req: GroupReq) -> Result<RowsAns, FfiError> {
    Ok(RowsAns {
        rows: call_log::group(&req.entries),
    })
}

fn group_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"entries": entries()})),
        Case::named("empty", json!({"entries": []})),
    ]
}

/// The rows of the sample log, as `recents.group` answers them.
fn sample_rows() -> Value {
    let entries: Vec<CallLogEntry> = serde_json::from_value(entries()).expect("sample entries");
    serde_json::to_value(call_log::group(&entries)).expect("rows")
}

#[derive(Deserialize)]
struct BuildReq {
    rows: Vec<RecentsRow>,
    now_ms: i64,
    locale: LocaleTime,
}

#[derive(Serialize)]
struct BuildAns {
    views: Vec<RowView>,
}

fn build(req: BuildReq) -> Result<BuildAns, FfiError> {
    Ok(BuildAns {
        views: recents::build(&req.rows, req.now_ms, req.locale),
    })
}

fn build_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"rows": sample_rows(), "now_ms": NOW, "locale": locale()}),
    )]
}

#[derive(Deserialize)]
struct DeltaReq {
    old: Vec<RecentsRow>,
    new: Vec<RecentsRow>,
}

fn refresh_delta(req: DeltaReq) -> Result<RefreshDelta, FfiError> {
    Ok(recents::refresh_delta(&req.old, &req.new))
}

fn delta_cases() -> Vec<Case> {
    let rows = sample_rows();
    let newer: Vec<Value> = rows.as_array().map(|a| a[1..].to_vec()).unwrap_or_default();
    vec![
        Case::new(json!({"old": newer, "new": rows})),
        Case::named("same", json!({"old": rows, "new": rows})),
    ]
}

#[derive(Deserialize)]
struct MergeReq {
    rows: Vec<RecentsRow>,
    extras: Vec<ExtraCall>,
}

fn merge_extras(req: MergeReq) -> Result<RowsAns, FfiError> {
    Ok(RowsAns {
        rows: recents::merge_extras(&req.rows, &req.extras),
    })
}

fn merge_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"rows": sample_rows(), "extras": extras()}),
    )]
}

#[derive(Deserialize)]
struct MissedReq {
    #[serde(default)]
    entries: Vec<CallLogEntry>,
    #[serde(default)]
    extras: Vec<ExtraCall>,
    last_seen_ms: i64,
}

#[derive(Serialize)]
struct MissedAns {
    log: u32,
    extras: u32,
    total: u32,
    /// The tab badge text: absent for zero, "99+" above 99.
    label: Option<String>,
}

fn missed_extras(req: MissedReq) -> Result<MissedAns, FfiError> {
    let log = call_log::missed_badge(&req.entries, req.last_seen_ms);
    let extras = recents::missed_extras(&req.extras, req.last_seen_ms);
    let total = log.saturating_add(extras);
    Ok(MissedAns {
        log,
        extras,
        total,
        label: call_log::badge_label(total).map(|t| t.as_str().to_owned()),
    })
}

fn missed_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"entries": entries(), "extras": extras(), "last_seen_ms": NOW - 100_000})),
        Case::named("none", json!({"last_seen_ms": NOW})),
    ]
}

#[derive(Deserialize)]
struct HistoryReq {
    entries: Vec<CallLogEntry>,
    #[serde(default)]
    extras: Vec<ExtraCall>,
    numbers: Vec<String>,
    limit: usize,
}

#[derive(Serialize)]
struct HistoryAns {
    lines: Vec<HistoryLine>,
}

fn history(req: HistoryReq) -> Result<HistoryAns, FfiError> {
    Ok(HistoryAns {
        lines: recents::history(&req.entries, &req.extras, &strs(&req.numbers), req.limit),
    })
}

fn history_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"entries": entries(), "extras": extras(), "numbers": ["+90 532 123 45 67"], "limit": 10}),
        ),
        Case::named(
            "no_numbers",
            json!({"entries": entries(), "numbers": [], "limit": 10}),
        ),
    ]
}

#[derive(Deserialize)]
struct BlockedHistoryReq {
    entries: Vec<CallLogEntry>,
    numbers: Vec<String>,
    limit: usize,
}

fn blocked_history(req: BlockedHistoryReq) -> Result<HistoryAns, FfiError> {
    Ok(HistoryAns {
        lines: recents::blocked_history(&req.entries, &strs(&req.numbers), req.limit),
    })
}

fn blocked_history_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"entries": entries(), "numbers": ["+491512345678"], "limit": 5}),
    )]
}

#[derive(Deserialize)]
struct HistoryTextReq {
    line: HistoryLine,
    now_ms: i64,
    locale: LocaleTime,
}

#[derive(Serialize)]
struct HistoryTextAns {
    title: String,
    detail: String,
}

fn history_text(req: HistoryTextReq) -> Result<HistoryTextAns, FfiError> {
    let (title, detail) = recents::history_text(&req.line, req.now_ms, req.locale);
    Ok(HistoryTextAns { title, detail })
}

fn history_text_cases() -> Vec<Case> {
    let line = json!({"entry": {"log": 3}, "blocked": false, "call_type": "outgoing", "whatsapp": false,
        "date_ms": NOW - 3_600_000, "duration_s": 125, "number": "+90 212 555 01 02"});
    let wa = json!({"entry": {"whatsapp": "wa-1"}, "blocked": false, "call_type": "missed", "whatsapp": true,
        "date_ms": NOW - 200_000_000, "duration_s": 0, "number": "+905321234567"});
    vec![
        Case::new(json!({"line": line, "now_ms": NOW, "locale": locale()})),
        Case::named(
            "whatsapp_yesterday",
            json!({"line": wa, "now_ms": NOW, "locale": locale()}),
        ),
    ]
}

#[derive(Deserialize)]
struct SectionReq {
    rows: Vec<RecentsRow>,
    visible: Vec<usize>,
    tz_offset_s: i32,
    /// With the time now, `labels` holds the day label of every section ("Today", "Yesterday", "Wednesday", "30 Oct").
    #[serde(default)]
    now_ms: Option<i64>,
}

#[derive(Serialize)]
struct SectionAns {
    starts: Vec<usize>,
    /// The label of each section in `starts`, newest call of its first row; empty without `now_ms`.
    labels: Vec<String>,
}

fn section_starts(req: SectionReq) -> Result<SectionAns, FfiError> {
    let starts = recents::section_starts(&req.rows, &req.visible, req.tz_offset_s);
    let labels = match req.now_ms {
        Some(now_ms) => starts
            .iter()
            .filter_map(|&first| {
                recents::section_for(&req.rows, &req.visible, first, now_ms, req.tz_offset_s)
            })
            .collect(),
        None => Vec::new(),
    };
    Ok(SectionAns { starts, labels })
}

fn section_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"rows": sample_rows(), "visible": [0, 1, 2, 3], "tz_offset_s": 10800, "now_ms": 1_790_000_000_000_i64}),
    )]
}

// ---- call_log.* ----

#[derive(Deserialize)]
struct FindReq {
    rows: Vec<RecentsRow>,
    #[serde(flatten)]
    query: Query,
    /// With notes, a row also passes when a note written for it mentions the text.
    #[serde(default)]
    notes: Option<Notes>,
}

#[derive(Serialize)]
struct FindAns {
    /// Positions in `rows` that pass, in input order.
    visible: Vec<usize>,
    /// The empty-state sentence for this query.
    empty_text: String,
}

fn find(req: FindReq) -> Result<FindAns, FfiError> {
    let visible = match &req.notes {
        Some(notes) => call_log_query::apply_with_notes(&req.rows, &req.query, notes),
        None => call_log_query::apply(&req.rows, &req.query),
    };
    Ok(FindAns {
        visible,
        empty_text: call_log_query::empty_text(&req.query),
    })
}

fn find_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"rows": sample_rows(), "text": "", "filter": "missed"})),
        Case::named(
            "text",
            json!({"rows": sample_rows(), "text": "212", "filter": "all"}),
        ),
        Case::named(
            "with_notes",
            json!({"rows": sample_rows(), "text": "taksi", "filter": "all", "notes": sample_notes()}),
        ),
    ]
}

#[derive(Deserialize)]
struct FrequentReq {
    entries: Vec<CallLogEntry>,
    now_ms: i64,
    #[serde(default = "default_window")]
    window_days: i64,
    #[serde(default = "default_limit")]
    limit: usize,
    /// Contact ids already in Favorites.
    #[serde(default)]
    starred: Vec<i64>,
}

fn default_window() -> i64 {
    call_log::FREQUENT_WINDOW_DAYS
}

fn default_limit() -> usize {
    call_log::FREQUENT_LIMIT
}

#[derive(Serialize)]
struct FrequentAns {
    people: Vec<Frequent>,
}

fn frequent(req: FrequentReq) -> Result<FrequentAns, FfiError> {
    Ok(FrequentAns {
        people: call_log::frequent(&req.entries, req.now_ms, req.window_days, req.limit, |id| {
            req.starred.contains(&id)
        }),
    })
}

fn frequent_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"entries": entries(), "now_ms": NOW})),
        Case::named(
            "starred_left_out",
            json!({"entries": entries(), "now_ms": NOW, "window_days": 30, "limit": 2, "starred": [1]}),
        ),
    ]
}

#[derive(Deserialize)]
struct CountsReq {
    entries: Vec<CallLogEntry>,
    now_ms: i64,
    #[serde(default = "default_window")]
    window_days: i64,
}

#[derive(Serialize)]
struct CountOut {
    id: i64,
    count: u32,
}

#[derive(Serialize)]
struct CountsAns {
    /// Calls per contact id, for `search.set_call_counts`; sorted by id.
    counts: Vec<CountOut>,
}

fn counts(req: CountsReq) -> Result<CountsAns, FfiError> {
    let mut counts: Vec<CountOut> =
        call_log::call_counts(&req.entries, req.now_ms, req.window_days)
            .into_iter()
            .map(|(id, count)| CountOut { id, count })
            .collect();
    counts.sort_by_key(|c| c.id);
    Ok(CountsAns { counts })
}

fn counts_cases() -> Vec<Case> {
    vec![Case::new(json!({"entries": entries(), "now_ms": NOW}))]
}

#[derive(Deserialize)]
struct IdsReq {
    entries: Vec<CallLogEntry>,
    #[serde(default)]
    extras: Vec<ExtraCall>,
    numbers: Vec<String>,
}

#[derive(Serialize)]
struct IdsAns {
    ids: Vec<i64>,
    blocked_ids: Vec<i64>,
    extra_ids: Vec<String>,
}

fn ids_for_numbers(req: IdsReq) -> Result<IdsAns, FfiError> {
    let numbers = strs(&req.numbers);
    Ok(IdsAns {
        ids: call_log::ids_for_numbers(&req.entries, &numbers),
        blocked_ids: call_log::blocked_ids_for_numbers(&req.entries, &numbers),
        extra_ids: recents::extra_ids_for_numbers(&req.extras, &numbers),
    })
}

fn ids_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"entries": entries(), "extras": extras(), "numbers": ["0532 123 4567", "+49 151 2345678"]}),
    )]
}

#[derive(Deserialize)]
struct CsvReq {
    entries: Vec<CallLogEntry>,
    #[serde(default)]
    extras: Vec<ExtraCall>,
    tz_offset_s: i32,
    now_ms: i64,
    #[serde(default)]
    notes: Option<Notes>,
    /// Limits the export to one person: their numbers and the name for the file name.
    #[serde(default)]
    person: Option<PersonSel>,
}

#[derive(Deserialize)]
struct PersonSel {
    numbers: Vec<String>,
    who: String,
}

#[derive(Serialize)]
struct CsvAns {
    /// `None` when the person has no call to export.
    file_name: Option<String>,
    csv: Option<String>,
}

fn csv(req: CsvReq) -> Result<CsvAns, FfiError> {
    let notes = req.notes.unwrap_or_default();
    let exported = match &req.person {
        Some(p) => call_log_query::person_export(
            &req.entries,
            &req.extras,
            &strs(&p.numbers),
            &p.who,
            req.now_ms,
            req.tz_offset_s,
            &notes,
        ),
        None => Some((
            call_log_query::csv_file_name(req.now_ms, req.tz_offset_s),
            call_log_query::to_csv_with_notes(&req.entries, &req.extras, req.tz_offset_s, &notes),
        )),
    };
    Ok(match exported {
        Some((file_name, csv)) => CsvAns {
            file_name: Some(file_name),
            csv: Some(csv),
        },
        None => CsvAns {
            file_name: None,
            csv: None,
        },
    })
}

fn csv_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"entries": entries(), "extras": extras(), "tz_offset_s": 10800, "now_ms": NOW, "notes": sample_notes()}),
        ),
        Case::named(
            "person",
            json!({"entries": entries(), "tz_offset_s": 10800, "now_ms": NOW,
                "person": {"numbers": ["+902125550102"], "who": "Mehmet Demir"}}),
        ),
        Case::named(
            "nobody",
            json!({"entries": entries(), "tz_offset_s": 0, "now_ms": NOW,
                "person": {"numbers": ["+10000000000"], "who": "Nobody"}}),
        ),
    ]
}

#[derive(Deserialize)]
struct PruneReq {
    age: PruneAge,
    entries: Vec<CallLogEntry>,
    #[serde(default)]
    extras: Vec<ExtraCall>,
    now_ms: i64,
}

#[derive(Serialize)]
struct PruneAns {
    cutoff_ms: i64,
    log_ids: Vec<i64>,
    call_ids: Vec<String>,
    count: usize,
    /// The confirmation dialog, or the "nothing to delete" notice when `count` is 0.
    title: Option<String>,
    body: String,
}

fn prune_plan(req: PruneReq) -> Result<PruneAns, FfiError> {
    let cutoff_ms = req.age.cutoff_ms(req.now_ms);
    let (log_ids, call_ids) = call_log_query::ids_older_than(&req.entries, &req.extras, cutoff_ms);
    let count = log_ids.len() + call_ids.len();
    let (title, body) = if count == 0 {
        (None, call_log_query::prune_none_text(req.age))
    } else {
        let (title, body) = call_log_query::prune_text(req.age, count);
        (Some(title), body)
    };
    Ok(PruneAns {
        cutoff_ms,
        log_ids,
        call_ids,
        count,
        title,
        body,
    })
}

fn prune_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"age": "days30", "entries": entries(), "extras": extras(), "now_ms": NOW + 40 * 86_400_000_i64}),
        ),
        Case::named(
            "nothing",
            json!({"age": "year", "entries": entries(), "now_ms": NOW}),
        ),
    ]
}

// ---- call_notes.* ----

fn sample_notes() -> Value {
    json!({"schema": 1, "notes": [
        {"number": "+90 212 555 01 02", "at_ms": NOW - 3_600_000, "text": "Taksi fişi için aradı", "edited_ms": NOW - 3_000_000}
    ]})
}

#[derive(Deserialize)]
struct SetReq {
    notes: Notes,
    number: String,
    at_ms: i64,
    text: String,
    now_ms: i64,
}

#[derive(Serialize)]
struct SetAns {
    outcome: SetOutcome,
    notes: Notes,
}

fn notes_set(mut req: SetReq) -> Result<SetAns, FfiError> {
    let outcome = req.notes.set(&req.number, req.at_ms, &req.text, req.now_ms);
    Ok(SetAns {
        outcome,
        notes: req.notes,
    })
}

fn notes_set_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"notes": {"schema": 1, "notes": []}, "number": "0532 123 45 67", "at_ms": NOW, "text": "  Dönüş yap  ", "now_ms": NOW}),
        ),
        Case::named(
            "remove_by_empty_text",
            json!({"notes": sample_notes(), "number": "+90 212 555 01 02", "at_ms": NOW - 3_600_000, "text": "", "now_ms": NOW}),
        ),
    ]
}

#[derive(Deserialize)]
struct RemoveReq {
    notes: Notes,
    number: String,
    at_ms: i64,
}

#[derive(Serialize)]
struct RemoveAns {
    /// What Undo restores; absent when no note matched.
    removed: Option<CallNote>,
    notes: Notes,
}

fn notes_remove(mut req: RemoveReq) -> Result<RemoveAns, FfiError> {
    let removed = req.notes.remove(&req.number, req.at_ms);
    Ok(RemoveAns {
        removed,
        notes: req.notes,
    })
}

fn notes_remove_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"notes": sample_notes(), "number": "+902125550102", "at_ms": NOW - 3_600_000}),
        ),
        Case::named(
            "no_match",
            json!({"notes": sample_notes(), "number": "+10000000000", "at_ms": 5}),
        ),
    ]
}

#[derive(Deserialize)]
struct RestoreReq {
    notes: Notes,
    note: CallNote,
}

#[derive(Serialize)]
struct NotesAns {
    notes: Notes,
}

fn notes_restore(mut req: RestoreReq) -> Result<NotesAns, FfiError> {
    req.notes.restore(req.note);
    Ok(NotesAns { notes: req.notes })
}

fn notes_restore_cases() -> Vec<Case> {
    vec![Case::new(json!({"notes": {"schema": 1, "notes": []},
        "note": {"number": "+905321234567", "at_ms": NOW, "text": "Geri ara", "edited_ms": NOW}}))]
}

#[derive(Deserialize)]
struct NoteRowsReq {
    notes: Notes,
    #[serde(default)]
    query: String,
    #[serde(default)]
    names: HashMap<String, String>,
}

#[derive(Serialize)]
struct NoteRowsAns {
    rows: Vec<NoteRowOut>,
    /// Shown when `rows` is empty.
    empty_text: &'static str,
}

#[derive(Serialize)]
struct NoteRowOut {
    #[serde(flatten)]
    row: NoteRow,
    title: String,
    subtitle: String,
}

fn notes_rows(req: NoteRowsReq) -> Result<NoteRowsAns, FfiError> {
    let locale = LocaleTime::default();
    let rows = req
        .notes
        .rows(&req.query, name_lookup(&req.names))
        .into_iter()
        .map(|row| {
            let name = req.names.get(row.number.trim()).map(String::as_str);
            NoteRowOut {
                title: call_notes::row_title(name, &row.number),
                subtitle: call_notes::row_subtitle(
                    &call_notes::when_line(row.at_ms, locale),
                    &row.preview,
                ),
                row,
            }
        })
        .collect();
    Ok(NoteRowsAns {
        rows,
        empty_text: if req.query.trim().is_empty() {
            call_notes::EMPTY_TEXT
        } else {
            call_notes::NO_MATCH_TEXT
        },
    })
}

fn notes_rows_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"notes": sample_notes(), "names": {"+90 212 555 01 02": "Mehmet Demir"}})),
        Case::named("no_match", json!({"notes": sample_notes(), "query": "zzz"})),
    ]
}

#[derive(Deserialize)]
struct ForCallsReq {
    notes: Notes,
    calls: Vec<CallRef>,
}

#[derive(Deserialize)]
struct CallRef {
    number: String,
    at_ms: i64,
}

#[derive(Serialize)]
struct ForCallsAns {
    flags: Vec<bool>,
}

fn notes_for_calls(req: ForCallsReq) -> Result<ForCallsAns, FfiError> {
    let calls: Vec<(String, i64)> = req.calls.into_iter().map(|c| (c.number, c.at_ms)).collect();
    Ok(ForCallsAns {
        flags: req.notes.for_calls(&calls),
    })
}

fn for_calls_cases() -> Vec<Case> {
    vec![Case::new(json!({"notes": sample_notes(), "calls": [
        {"number": "+902125550102", "at_ms": NOW - 3_600_000},
        {"number": "+902125550102", "at_ms": NOW}
    ]}))]
}

#[derive(Deserialize)]
struct DetailsReq {
    line: HistoryLine,
    number: String,
    #[serde(default)]
    sim: Option<String>,
    locale: LocaleTime,
    #[serde(default)]
    note: Option<String>,
    /// For the share text: "Call with {who}".
    #[serde(default)]
    who: Option<String>,
}

#[derive(Serialize)]
struct DetailsAns {
    details: CallDetails,
    copy_text: String,
    share_text: String,
}

fn notes_details(req: DetailsReq) -> Result<DetailsAns, FfiError> {
    let details = call_notes::details(&req.line, &req.number, req.sim.as_deref(), req.locale);
    Ok(DetailsAns {
        copy_text: call_notes::details_text(&details, req.note.as_deref()),
        share_text: call_notes::share_text(&details, req.who.as_deref(), req.note.as_deref()),
        details,
    })
}

fn details_cases() -> Vec<Case> {
    let line = json!({"entry": {"log": 3}, "blocked": false, "call_type": "outgoing", "whatsapp": false,
        "date_ms": NOW - 3_600_000, "duration_s": 125, "number": "+90 212 555 01 02"});
    vec![
        Case::new(
            json!({"line": line, "number": "+90 212 555 01 02", "sim": "Turkcell", "locale": locale(),
                "note": "Taksi fişi için aradı", "who": "Mehmet Demir"}),
        ),
        Case::named(
            "bare",
            json!({"line": line, "number": " ", "locale": locale()}),
        ),
    ]
}

#[derive(Deserialize)]
struct NotesExportReq {
    notes: Notes,
    #[serde(default)]
    names: HashMap<String, String>,
    tz_offset_s: i32,
    now_ms: i64,
}

#[derive(Serialize)]
struct NotesExportAns {
    text: String,
    csv: String,
    file_name: String,
}

fn notes_export(req: NotesExportReq) -> Result<NotesExportAns, FfiError> {
    Ok(NotesExportAns {
        text: req.notes.all_text(name_lookup(&req.names), req.tz_offset_s),
        csv: req.notes.to_csv(name_lookup(&req.names), req.tz_offset_s),
        file_name: call_notes::notes_csv_file_name(req.now_ms, req.tz_offset_s),
    })
}

fn notes_export_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"notes": sample_notes(), "names": {"+90 212 555 01 02": "Mehmet Demir"}, "tz_offset_s": 10800, "now_ms": NOW}),
    )]
}

// ---- call_stats.* ----

#[derive(Deserialize)]
struct OverviewReq {
    entries: Vec<CallLogEntry>,
    #[serde(default)]
    extras: Vec<ExtraCall>,
    #[serde(default)]
    range: Range,
    now_ms: i64,
    tz_offset_s: i32,
    #[serde(default = "default_top")]
    top: usize,
}

fn default_top() -> usize {
    5
}

#[derive(Serialize)]
struct OverviewAns {
    since_ms: i64,
    range_label: &'static str,
    totals: Totals,
    summary: String,
    talk: String,
    bars: Vec<DayBar>,
    people: Vec<PersonStat>,
    share_text: String,
    csv: String,
    csv_file_name: String,
}

fn overview(req: OverviewReq) -> Result<OverviewAns, FfiError> {
    let calls = call_stats::from_sources(&req.entries, &req.extras);
    let since_ms = req.range.since_ms(req.now_ms, req.tz_offset_s);
    let totals = call_stats::totals_since(&calls, since_ms);
    let people = call_stats::top_people(&calls, since_ms, req.top);
    Ok(OverviewAns {
        since_ms,
        range_label: req.range.label(),
        summary: call_stats::summary_line(&totals),
        talk: call_stats::talk_text(totals.talk_s),
        bars: req.range.bars(&calls, req.now_ms, req.tz_offset_s),
        share_text: call_stats::range_share_text(&people, req.range),
        csv: call_stats::people_csv(&people, req.range),
        csv_file_name: call_stats::stats_csv_file_name(req.range, req.now_ms, req.tz_offset_s),
        totals,
        people,
    })
}

fn overview_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"entries": entries(), "extras": extras(), "range": "weeks8", "now_ms": NOW, "tz_offset_s": 10800}),
        ),
        Case::named(
            "week",
            json!({"entries": entries(), "now_ms": NOW, "tz_offset_s": 10800, "top": 1}),
        ),
    ]
}

#[derive(Deserialize)]
struct PersonReq {
    entries: Vec<CallLogEntry>,
    #[serde(default)]
    extras: Vec<ExtraCall>,
    numbers: Vec<String>,
    #[serde(default)]
    range: Range,
    #[serde(default)]
    who: String,
    now_ms: i64,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct PersonAns {
    all: Totals,
    in_range: Totals,
    calls: Vec<StatCall>,
    share_text: String,
}

fn person(req: PersonReq) -> Result<PersonAns, FfiError> {
    let calls = call_stats::from_sources(&req.entries, &req.extras);
    let numbers = strs(&req.numbers);
    let since = req.range.since_ms(req.now_ms, req.tz_offset_s);
    let all = call_stats::for_numbers(&calls, &numbers);
    let in_range = call_stats::for_numbers_since(&calls, &numbers, since);
    Ok(PersonAns {
        share_text: call_stats::share_text(&req.who, &all, &in_range),
        calls: call_stats::calls_for_numbers(&calls, &numbers),
        all,
        in_range,
    })
}

fn person_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"entries": entries(), "extras": extras(), "numbers": ["+905321234567"], "who": "Ayşe Yılmaz",
            "now_ms": NOW, "tz_offset_s": 10800}),
    )]
}

#[derive(Deserialize)]
struct BucketReq {
    entries: Vec<CallLogEntry>,
    #[serde(default)]
    extras: Vec<ExtraCall>,
    bar: DayBar,
    range: Range,
    tz_offset_s: i32,
    hour12: bool,
}

#[derive(Serialize)]
struct BucketLine {
    call: StatCall,
    title: String,
    detail: String,
}

#[derive(Serialize)]
struct BucketAns {
    title: String,
    summary: String,
    lines: Vec<BucketLine>,
}

fn bucket(req: BucketReq) -> Result<BucketAns, FfiError> {
    let calls = call_stats::from_sources(&req.entries, &req.extras);
    let inside = call_stats::calls_in(&calls, req.bar.day_start_ms, req.bar.end_ms);
    let lines = inside
        .iter()
        .map(|call| {
            let (title, detail) = call_stats::call_line(call, req.tz_offset_s, req.hour12);
            BucketLine {
                call: call.clone(),
                title,
                detail,
            }
        })
        .collect();
    Ok(BucketAns {
        title: call_stats::bucket_title(&req.bar, req.range, req.tz_offset_s),
        summary: call_stats::bucket_summary(&inside),
        lines,
    })
}

fn bucket_cases() -> Vec<Case> {
    let day_start = NOW - NOW.rem_euclid(86_400_000) - 10_800_000;
    vec![Case::new(json!({"entries": entries(), "extras": extras(),
            "bar": {"day_start_ms": day_start, "end_ms": day_start + 86_400_000, "label": "M", "talk_s": 0, "calls": 3},
            "range": "week", "tz_offset_s": 10800, "hour12": false}))]
}
