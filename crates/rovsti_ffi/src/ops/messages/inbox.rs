//! `inbox.*`: the unified inbox (one row per person), its filter chips, bulk actions and the delayed delete.

use super::{NOW, canon, contacts_setup, message};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec, PLACEHOLDER_HANDLE as H};
use crate::ops::search::with_index;
use rovsti_core::inbox::{self, InboxRow, InboxThread, PersonKey};
use rovsti_core::inbox_bulk::{self, BulkAction, BulkFacts};
use rovsti_core::inbox_filter::{self, InboxFilter, RowFacts};
use rovsti_core::pending_delete::{self, PendingDeletes};
use rovsti_core::sms::SmsThreadRow;
use rovsti_core::thread::Channel;
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!("inbox.merge", MergeReq, MergeAns, merge, merge_cases),
    op!(
        "inbox.preferred_channel",
        PreferredReq,
        PreferredAns,
        preferred_channel,
        preferred_cases
    ),
    op!(
        "inbox.person_for_number",
        PersonNumberReq,
        PersonAns,
        person_for_number,
        person_number_cases
    ),
    op!(
        "inbox.person_for_jid",
        PersonJidReq,
        PersonAns,
        person_for_jid,
        person_jid_cases
    ),
    op!("inbox.filter", FilterReq, FilterAns, filter, filter_cases),
    op!("inbox.bulk", BulkReq, BulkAns, bulk, bulk_cases),
    op!(
        "inbox.pending_delete",
        PendingReq,
        PendingAns,
        pending_delete,
        pending_cases
    ),
];

// ---- merge ----

#[derive(Deserialize)]
struct MergeReq {
    /// A `search.index_new` handle.
    contacts: i64,
    #[serde(default)]
    sms: Vec<SmsThreadRow>,
    #[serde(default)]
    wa: Vec<InboxThread>,
}

#[derive(Serialize)]
struct MergeAns {
    rows: Vec<InboxRow>,
}

fn merge(req: MergeReq) -> Result<MergeAns, FfiError> {
    with_index(req.contacts, |index| {
        Ok(MergeAns {
            rows: inbox::merge(&req.sms, &req.wa, index),
        })
    })
}

fn threads() -> (Value, Value) {
    let sms = canon::<Vec<SmsThreadRow>>(json!([
        {"thread_id": 5, "addresses": ["05321234567"], "snippet": "See you at 8", "date_ms": NOW - 60_000, "unread": 2},
        {"thread_id": 6, "addresses": ["BANK"], "snippet": "Your code is 482913", "date_ms": NOW - 3_600_000, "unread": 1},
        {"thread_id": 7, "addresses": ["+49 151 2345678"], "snippet": "Hallo", "date_ms": NOW - 86_400_000, "unread": 0}
    ]));
    let wa = canon::<Vec<InboxThread>>(json!([
        {"id": {"WhatsApp": "905321234567@s.whatsapp.net"},
         "last": message(json!({"WhatsApp": "905321234567@s.whatsapp.net"}), "+905321234567", "Ok", NOW - 30_000, false),
         "unread": 1, "title": null, "is_group": false},
        {"id": {"WhatsApp": "120363@g.us"},
         "last": message(json!({"WhatsApp": "120363@g.us"}), "+905321234567", "Photo", NOW - 7_200_000, true),
         "unread": 0, "title": "Weekend plans", "is_group": true}
    ]));
    (sms, wa)
}

fn merge_cases() -> Vec<Case> {
    let (op, req) = contacts_setup();
    let (sms, wa) = threads();
    vec![
        Case::new(json!({"contacts": H, "sms": sms, "wa": wa})).after(op, req.clone()),
        Case::named("empty", json!({"contacts": H})).after(op, req),
        Case::named("stale_handle", json!({"contacts": H, "sms": sms})),
    ]
}

// ---- preferred channel ----

#[derive(Deserialize)]
struct PreferredReq {
    row: InboxRow,
}

#[derive(Serialize)]
struct PreferredAns {
    channel: Channel,
}

fn preferred_channel(req: PreferredReq) -> Result<PreferredAns, FfiError> {
    Ok(PreferredAns {
        channel: inbox::preferred_channel(&req.row),
    })
}

fn row_literal() -> Value {
    let (sms, _) = threads();
    canon::<InboxRow>(json!({"key": {"contact": 1}, "title": "Ayşe Yılmaz",
        "threads": [{"id": {"Sms": 5},
            "last": message(json!({"Sms": 5}), "+905321234567", sms[0]["snippet"].as_str().unwrap_or(""), NOW - 60_000, false),
            "unread": 2, "title": null, "is_group": false}],
        "last": message(json!({"Sms": 5}), "+905321234567", "See you at 8", NOW - 60_000, false),
        "unread": 2, "channels": ["Sms"]}))
}

fn preferred_cases() -> Vec<Case> {
    vec![Case::new(json!({"row": row_literal()}))]
}

// ---- person_for_* ----

#[derive(Deserialize)]
struct PersonNumberReq {
    contacts: i64,
    raw: String,
}

#[derive(Deserialize)]
struct PersonJidReq {
    contacts: i64,
    jid: String,
}

#[derive(Serialize)]
struct PersonAns {
    /// `None` for senders that are not numbers (`"BANK"`) or JIDs that never merge by number.
    key: Option<PersonKey>,
}

fn person_for_number(req: PersonNumberReq) -> Result<PersonAns, FfiError> {
    with_index(req.contacts, |index| {
        Ok(PersonAns {
            key: inbox::person_for_number(&req.raw, index),
        })
    })
}

fn person_for_jid(req: PersonJidReq) -> Result<PersonAns, FfiError> {
    with_index(req.contacts, |index| {
        Ok(PersonAns {
            key: inbox::person_for_jid(&req.jid, index),
        })
    })
}

fn person_number_cases() -> Vec<Case> {
    let (op, req) = contacts_setup();
    vec![
        Case::new(json!({"contacts": H, "raw": "0532 123 45 67"})).after(op, req.clone()),
        Case::named(
            "unknown_number",
            json!({"contacts": H, "raw": "+49 151 2345678"}),
        )
        .after(op, req.clone()),
        Case::named("sender_id", json!({"contacts": H, "raw": "BANK"})).after(op, req),
    ]
}

fn person_jid_cases() -> Vec<Case> {
    let (op, req) = contacts_setup();
    vec![
        Case::new(json!({"contacts": H, "jid": "905321234567@s.whatsapp.net"}))
            .after(op, req.clone()),
        Case::named("group", json!({"contacts": H, "jid": "120363@g.us"})).after(op, req.clone()),
        Case::named("lid", json!({"contacts": H, "jid": "12345@lid"})).after(op, req),
    ]
}

// ---- filter chips ----

#[derive(Deserialize)]
struct FilterReq {
    #[serde(default)]
    facts: Vec<RowFacts>,
    #[serde(default)]
    filter: InboxFilter,
}

#[derive(Serialize)]
struct Chip {
    filter: InboxFilter,
    count: usize,
    /// Accessibility text ("Unread 3").
    label: String,
    /// The chip text and, when worth showing, its count badge ("999+" above the cap).
    text: &'static str,
    badge: Option<String>,
}

#[derive(Serialize)]
struct FilterAns {
    /// Whether each row passes the selected filter, in request order.
    matches: Vec<bool>,
    /// The count of every chip in chip order (All, Unread, WhatsApp, SMS, Groups).
    counts: [usize; 5],
    /// The chips to show, in order; the selected filter is always among them.
    chips: Vec<Chip>,
    empty_text: &'static str,
}

fn filter(req: FilterReq) -> Result<FilterAns, FfiError> {
    let counts = inbox_filter::counts(&req.facts);
    let mut shown = inbox_filter::visible(&counts);
    if !shown.contains(&req.filter) {
        shown.push(req.filter);
    }
    let chips = shown
        .into_iter()
        .map(|filter| {
            let index = inbox_filter::ALL
                .iter()
                .position(|f| *f == filter)
                .unwrap_or(0);
            let count = counts[index];
            let (text, badge) = inbox_filter::chip_parts(filter, count);
            Chip {
                filter,
                count,
                label: inbox_filter::chip_label(filter, count),
                text,
                badge,
            }
        })
        .collect();
    Ok(FilterAns {
        matches: req
            .facts
            .iter()
            .map(|f| inbox_filter::matches(req.filter, *f))
            .collect(),
        counts,
        chips,
        empty_text: inbox_filter::empty_text(req.filter),
    })
}

fn filter_cases() -> Vec<Case> {
    let facts = json!([
        {"unread": true, "sms": true},
        {"unread": true, "whatsapp": true, "sms": true},
        {"whatsapp": true, "group": true},
        {"sms": true}
    ]);
    vec![
        Case::new(json!({"facts": facts, "filter": "unread"})),
        Case::named("empty", json!({"facts": [], "filter": "groups"})),
        Case::named(
            "many",
            json!({"facts": vec![json!({"unread": true}); 1200], "filter": "all"}),
        ),
    ]
}

// ---- bulk ----

#[derive(Deserialize)]
struct BulkReq {
    #[serde(default)]
    selected: Vec<BulkFacts>,
    #[serde(default)]
    in_archive: bool,
    #[serde(default)]
    pinned_wa: usize,
    #[serde(default)]
    pinned_sms: usize,
    /// The action the user picked, for the notices.
    #[serde(default)]
    action: Option<BulkAction>,
    /// Rows the action reached; `selected.len()` when absent.
    #[serde(default)]
    done: Option<usize>,
}

#[derive(Serialize)]
struct ActionRow {
    action: BulkAction,
    label: &'static str,
    destructive: bool,
}

#[derive(Serialize)]
struct BulkAns {
    actions: Vec<ActionRow>,
    /// The pill text when pinning the unpinned rows would pass a cap.
    pin_refusal: Option<&'static str>,
    done_notice: Option<String>,
    /// The confirmation before a destructive action.
    confirm: Option<String>,
    /// "N done; M failed" when only some rows succeeded.
    partial_notice: Option<String>,
}

fn bulk(req: BulkReq) -> Result<BulkAns, FfiError> {
    let total = req.selected.len();
    let done = req.done.unwrap_or(total).min(total);
    Ok(BulkAns {
        actions: inbox_bulk::actions(&req.selected, req.in_archive)
            .into_iter()
            .map(|action| ActionRow {
                action,
                label: action.label(),
                destructive: action.is_destructive(),
            })
            .collect(),
        pin_refusal: inbox_bulk::pin_room(req.pinned_wa, req.pinned_sms, &req.selected).err(),
        done_notice: req
            .action
            .filter(|_| done == total)
            .map(|action| inbox_bulk::done_notice(action, done)),
        confirm: req
            .action
            .filter(|action| action.is_destructive())
            .map(|_| inbox_bulk::delete_prompt(total)),
        partial_notice: (done < total).then(|| inbox_bulk::partial_notice(done, total - done)),
    })
}

fn bulk_cases() -> Vec<Case> {
    let two = json!([
        {"unread": true, "sms": true, "sms_only": true},
        {"muted": true, "sms": true, "sms_only": true}
    ]);
    vec![
        Case::new(json!({"selected": two, "action": "delete"})),
        Case::named("archive", json!({"selected": two, "in_archive": true})),
        Case::named(
            "pin_cap",
            json!({"selected": [{"whatsapp": true}, {"whatsapp": true}], "pinned_wa": 2, "action": "pin"}),
        ),
        Case::named(
            "partial",
            json!({"selected": two, "action": "mute", "done": 1}),
        ),
        Case::named("none", json!({"selected": []})),
    ]
}

// ---- pending delete ----

#[derive(Serialize, Deserialize, Clone)]
struct Waiting {
    key: String,
    at_ms: i64,
}

#[derive(Deserialize)]
struct PendingReq {
    /// The delayed deletes so far, oldest first.
    #[serde(default)]
    pending: Vec<Waiting>,
    #[serde(default)]
    now_ms: i64,
    /// `add`, `undo`, `due`, `drain` or `none` (state only).
    #[serde(default)]
    action: String,
    #[serde(default)]
    key: String,
}

#[derive(Serialize)]
struct PendingAns {
    pending: Vec<Waiting>,
    /// `undo`: the key was pending. `add`: always true.
    ok: bool,
    /// `due` and `drain`: the keys to delete for real now.
    keys: Vec<String>,
    /// The pill text for the deletes just queued or run.
    notice: String,
    undo_ms: i64,
}

fn pending_delete(req: PendingReq) -> Result<PendingAns, FfiError> {
    let mut queue: PendingDeletes<String> = PendingDeletes::default();
    for waiting in req.pending {
        queue.add(waiting.key, waiting.at_ms);
    }
    let (mut ok, mut keys) = (true, Vec::new());
    match req.action.as_str() {
        "add" => queue.add(req.key.clone(), req.now_ms),
        "undo" => ok = queue.undo(&req.key),
        "due" => keys = queue.due(req.now_ms),
        "drain" => keys = queue.drain(),
        "" | "none" => {}
        other => return Err(FfiError::invalid(format!("unknown action {other:?}"))),
    }
    let count = if keys.is_empty() {
        queue.items().len()
    } else {
        keys.len()
    };
    Ok(PendingAns {
        pending: queue
            .items()
            .iter()
            .map(|(key, at_ms)| Waiting {
                key: key.clone(),
                at_ms: *at_ms,
            })
            .collect(),
        ok,
        keys,
        notice: pending_delete::notice(count),
        undo_ms: pending_delete::UNDO_MS,
    })
}

fn pending_cases() -> Vec<Case> {
    let waiting =
        json!([{"key": "sms:12", "at_ms": NOW - 6_000}, {"key": "sms:13", "at_ms": NOW - 1_000}]);
    vec![
        Case::new(json!({"pending": waiting, "action": "due", "now_ms": NOW})),
        Case::named(
            "add",
            json!({"pending": waiting, "action": "add", "key": "sms:14", "now_ms": NOW}),
        ),
        Case::named(
            "undo",
            json!({"pending": waiting, "action": "undo", "key": "sms:13"}),
        ),
        Case::named(
            "undo_unknown",
            json!({"pending": waiting, "action": "undo", "key": "sms:99"}),
        ),
        Case::named("drain", json!({"pending": waiting, "action": "drain"})),
        Case::named("bad_action", json!({"action": "explode"})),
    ]
}
