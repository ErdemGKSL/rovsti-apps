//! `schedule.*`: scheduled sends. Stateless: the caller keeps the queue as the JSON string `scheduled.json` holds
//! and every op answers the new string (see OPS.md for why there is no handle).

use super::{NOW, canon};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::schedule::{self, FiredSms, ScheduleQueue, ScheduledSms};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "schedule.validate",
        ValidateReq,
        ValidateAns,
        validate,
        validate_cases
    ),
    op!("schedule.add", AddReq, QueueAns, add, add_cases),
    op!(
        "schedule.cancel",
        CancelReq,
        CancelAns,
        cancel,
        cancel_cases
    ),
    op!(
        "schedule.reconcile",
        ReconcileReq,
        ReconcileAns,
        reconcile,
        reconcile_cases
    ),
    op!("schedule.due_soon", DueReq, DueAns, due_soon, due_cases),
    op!("schedule.load", LoadReq, QueueAns, load, load_cases),
    op!(
        "schedule.quick_times",
        QuickReq,
        QuickAns,
        quick_times,
        quick_cases
    ),
];

#[derive(Serialize)]
struct QueueAns {
    /// The queue as `scheduled.json` keeps it; save this string.
    queue_json: String,
    items: Vec<ScheduledSms>,
}

fn answer(queue: &ScheduleQueue) -> QueueAns {
    QueueAns {
        queue_json: queue.to_json(),
        items: queue.items().to_vec(),
    }
}

/// A tolerant read: an empty or corrupt string is an empty queue.
fn read(queue_json: &str) -> ScheduleQueue {
    ScheduleQueue::from_json(queue_json)
}

fn item(id: u64, at_ms: i64, body: &str) -> Value {
    canon::<ScheduledSms>(
        json!({"id": id, "at_ms": at_ms, "target": {"Sms": {"to": "+905321234567", "sub_id": 1}},
           "body": body, "created_ms": NOW}),
    )
}

fn queue_json() -> String {
    json!({"items": [item(1, NOW + 3_600_000, "Happy birthday"), item(2, NOW + 7_200_000, "Meeting at 9")]})
        .to_string()
}

// ---- validate ----

#[derive(Deserialize)]
struct ValidateReq {
    at_ms: i64,
    now_ms: i64,
    body: String,
}

#[derive(Serialize)]
struct ValidateAns {
    /// Why the message cannot be scheduled, in the user's words.
    error: Option<String>,
    min_lead_ms: i64,
}

fn validate(req: ValidateReq) -> Result<ValidateAns, FfiError> {
    Ok(ValidateAns {
        error: schedule::validate(req.at_ms, req.now_ms, &req.body)
            .err()
            .map(|e| e.to_string()),
        min_lead_ms: schedule::MIN_LEAD_MS,
    })
}

fn validate_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"at_ms": NOW + 3_600_000, "now_ms": NOW, "body": "hi"})),
        Case::named(
            "too_soon",
            json!({"at_ms": NOW + 1_000, "now_ms": NOW, "body": "hi"}),
        ),
        Case::named(
            "empty",
            json!({"at_ms": NOW + 3_600_000, "now_ms": NOW, "body": ""}),
        ),
    ]
}

// ---- add ----

#[derive(Deserialize)]
struct AddReq {
    #[serde(default)]
    queue_json: String,
    item: ScheduledSms,
    /// Validates the item first when given.
    #[serde(default)]
    now_ms: Option<i64>,
}

fn add(req: AddReq) -> Result<QueueAns, FfiError> {
    if let Some(now_ms) = req.now_ms {
        schedule::validate(req.item.at_ms, now_ms, &req.item.body)
            .map_err(|e| FfiError::invalid(e.to_string()))?;
    }
    let mut queue = read(&req.queue_json);
    queue
        .add(req.item)
        .map_err(|e| FfiError::invalid(e.to_string()))?;
    Ok(answer(&queue))
}

fn add_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"queue_json": queue_json(), "item": item(3, NOW + 5_400_000, "Dinner?"), "now_ms": NOW}),
        ),
        Case::named("first", json!({"item": item(1, NOW + 3_600_000, "Hi")})),
        Case::named(
            "duplicate_id",
            json!({"queue_json": queue_json(), "item": item(1, NOW + 9_000_000, "Again")}),
        ),
        Case::named(
            "too_soon",
            json!({"item": item(1, NOW + 1_000, "Now"), "now_ms": NOW}),
        ),
    ]
}

// ---- cancel ----

#[derive(Deserialize)]
struct CancelReq {
    #[serde(default)]
    queue_json: String,
    id: u64,
}

#[derive(Serialize)]
struct CancelAns {
    #[serde(flatten)]
    queue: QueueAns,
    removed: bool,
}

fn cancel(req: CancelReq) -> Result<CancelAns, FfiError> {
    let mut queue = read(&req.queue_json);
    let removed = queue.cancel(req.id);
    Ok(CancelAns {
        queue: answer(&queue),
        removed,
    })
}

fn cancel_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"queue_json": queue_json(), "id": 2})),
        Case::named("unknown", json!({"queue_json": queue_json(), "id": 99})),
    ]
}

// ---- reconcile ----

#[derive(Deserialize)]
struct ReconcileReq {
    #[serde(default)]
    queue_json: String,
    /// What the platform reports after trying to send due items.
    fired: Vec<FiredSms>,
}

#[derive(Serialize)]
struct ReconcileAns {
    #[serde(flatten)]
    queue: QueueAns,
    /// The items that failed, for the "could not send" notice.
    failed: Vec<ScheduledSms>,
}

fn reconcile(req: ReconcileReq) -> Result<ReconcileAns, FfiError> {
    let mut queue = read(&req.queue_json);
    let failed = queue.reconcile(&req.fired);
    Ok(ReconcileAns {
        queue: answer(&queue),
        failed,
    })
}

fn reconcile_cases() -> Vec<Case> {
    vec![Case::new(json!({"queue_json": queue_json(), "fired": [
        {"id": 1, "ok": false, "at_ms": NOW}, {"id": 2, "ok": true, "at_ms": NOW}, {"id": 42, "ok": true, "at_ms": NOW}]}))]
}

// ---- due_soon ----

#[derive(Deserialize)]
struct DueReq {
    #[serde(default)]
    queue_json: String,
    now_ms: i64,
    within_ms: i64,
}

#[derive(Serialize)]
struct DueAns {
    /// Oldest first: the list to (re)register with the alarm service.
    items: Vec<ScheduledSms>,
}

fn due_soon(req: DueReq) -> Result<DueAns, FfiError> {
    let queue = read(&req.queue_json);
    Ok(DueAns {
        items: queue
            .due_soon(req.now_ms, req.within_ms)
            .into_iter()
            .cloned()
            .collect(),
    })
}

fn due_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"queue_json": queue_json(), "now_ms": NOW, "within_ms": 4_000_000}),
    )]
}

// ---- load ----

#[derive(Deserialize)]
struct LoadReq {
    #[serde(default)]
    queue_json: String,
}

fn load(req: LoadReq) -> Result<QueueAns, FfiError> {
    Ok(answer(&read(&req.queue_json)))
}

fn load_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"queue_json": queue_json()})),
        Case::named("corrupt", json!({"queue_json": "{not json"})),
    ]
}

// ---- quick_times ----

#[derive(Deserialize)]
struct QuickReq {
    now_ms: i64,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct QuickTime {
    label: &'static str,
    at_ms: i64,
}

#[derive(Serialize)]
struct QuickAns {
    /// "In 1 hour", this/tomorrow evening and tomorrow morning, each at least a minute away.
    times: Vec<QuickTime>,
}

fn quick_times(req: QuickReq) -> Result<QuickAns, FfiError> {
    Ok(QuickAns {
        times: schedule::quick_times(req.now_ms, req.tz_offset_s)
            .into_iter()
            .map(|(label, at_ms)| QuickTime { label, at_ms })
            .collect(),
    })
}

fn quick_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"now_ms": NOW, "tz_offset_s": 10_800})),
        Case::named(
            "late_evening",
            json!({"now_ms": NOW + 4 * 3_600_000, "tz_offset_s": 10_800}),
        ),
    ]
}
