//! `stops.trip_*`: the trip in progress as a handle over `ActiveTrip`, plus the diameter checks the pick screen
//! needs (`stops.crossing_validate`, `stops.crossing_read`) and the status texts.
//!
//! `stops.trip_start` / `stops.trip_restore` answer `{handle, ...}`; every later op sends the handle back and
//! `RustNative.release` frees it (a released or foreign handle answers `not_found`). With a `dir` an op also
//! writes `trip.json` under the store lock, so the foreground service (which reads that file through the headless
//! kinds) sees the same trip.

use super::{NOW, data_dir, gate};
use crate::envelope::{ErrorKind, FfiError};
use crate::handles;
use crate::op;
use crate::ops::registry::{Case, OpSpec, PLACEHOLDER_HANDLE as H};
use rovsti_app_core::stops::store;
use rovsti_geo::{
    ActiveTrip, Coord, Diameter, DiameterError, Endpoint, FixIn, NoticeDelivery, NoticePrefs,
    Outcome, TripRecord, TripState, status_share_text,
};
use serde::Deserialize;
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!("stops.trip_start", StartReq, Value, start, start_cases),
    op!(
        "stops.trip_restore",
        RestoreReq,
        Value,
        restore,
        restore_cases
    ),
    op!("stops.trip_state", HandleReq, Value, state_of, state_cases),
    op!("stops.trip_fix", FixReq, Value, fix, fix_cases),
    op!(
        "stops.trip_notice_result",
        NoticeReq,
        Value,
        notice_result,
        notice_cases
    ),
    op!("stops.trip_snooze", SnoozeReq, Value, snooze, snooze_cases),
    op!(
        "stops.trip_timeout",
        SnoozeReq,
        Value,
        timeout,
        timeout_cases
    ),
    op!(
        "stops.trip_dismiss",
        DismissReq,
        Value,
        dismiss,
        dismiss_cases
    ),
    op!(
        "stops.trip_status_share",
        ShareReq,
        Value,
        share,
        share_cases
    ),
    op!("stops.trip_end", EndReq, Value, end, end_cases),
    op!(
        "stops.arrival_clock",
        ClockReq,
        Value,
        arrival_clock,
        clock_cases
    ),
    op!(
        "stops.crossing_validate",
        ValidateReq,
        Value,
        validate,
        validate_cases
    ),
    op!("stops.crossing_read", ReadReq, Value, read, read_cases),
];

fn with_trip<R>(
    handle: i64,
    f: impl FnOnce(&mut ActiveTrip) -> Result<R, FfiError>,
) -> Result<R, FfiError> {
    handles::global().with(handle, f).unwrap_or_else(|| {
        Err(FfiError::new(
            ErrorKind::NotFound,
            format!("trip handle {handle} is not open"),
        ))
    })
}

/// A `DiameterError` as an `invalid` error: the sentence in `message`, a stable `reason` in `detail`.
pub(super) fn diameter_error(error: DiameterError) -> FfiError {
    let reason = match error {
        DiameterError::InvalidCoord => "invalid_coord",
        DiameterError::SamePoint => "same_point",
        DiameterError::TooShort => "too_short",
        DiameterError::TooLong => "too_long",
    };
    FfiError::invalid(error.message()).with_detail(json!({"reason": reason}))
}

fn persist(dir: &Option<String>, trip: &ActiveTrip) -> Result<(), FfiError> {
    if dir.is_none() {
        return Ok(());
    }
    let dir = data_dir(dir)?;
    let _guard = store::lock();
    store::save_trip(&dir, trip)?;
    Ok(())
}

/// The trip as the UI needs it: the numbers of the card plus `snapshot`, the whole persisted trip.
fn view_of(trip: &ActiveTrip) -> Result<Value, FfiError> {
    let d = &trip.trip.diameter;
    Ok(json!({
        "id": trip.id,
        "label": trip.label(),
        "a": trip.a,
        "b": trip.b,
        "state": trip.trip.state(),
        "started_ms": trip.started_ms,
        "snoozes": trip.snoozes,
        "snoozes_left": trip.snoozes_left(),
        "ring_at_ms": trip.ring_at_ms,
        "timeouts": trip.timeouts,
        "travelled_m": trip.track.travelled_m,
        "fixes": trip.track.fixes,
        "length_m": d.length_m(),
        "radius_m": d.radius_m(),
        "center": d.center(),
        "side": trip.trip.side(),
        "last_line_m": trip.trip.last_line_m(),
        "notice": trip.notice.as_ref().map(|n| json!({
            "chat": n.chat, "sent": n.sent, "attempts": n.attempts, "gave_up": n.gave_up()
        })),
        "snapshot": serde_json::to_value(trip)?,
    }))
}

fn with_handle(handle: i64, mut view: Value) -> Value {
    if let Some(o) = view.as_object_mut() {
        o.insert("handle".to_owned(), handle.into());
    }
    view
}

// ---- start / restore / state ----

#[derive(Deserialize)]
struct StartReq {
    id: u64,
    a: Endpoint,
    b: Endpoint,
    started_ms: i64,
    /// The "arriving soon" message of the preferences; absent for no message.
    #[serde(default)]
    notice: Option<NoticePrefs>,
    #[serde(default)]
    dir: Option<String>,
}

fn start(req: StartReq) -> Result<Value, FfiError> {
    let mut trip = ActiveTrip::new(req.id, req.a, req.b, req.started_ms).map_err(diameter_error)?;
    trip.notice = req
        .notice
        .filter(|n| !n.chat.is_empty())
        .map(|n| n.to_notice_state());
    persist(&req.dir, &trip)?;
    let view = view_of(&trip)?;
    Ok(with_handle(handles::global().insert(trip), view))
}

fn start_cases() -> Vec<Case> {
    let g = gate();
    vec![
        Case::new(json!({"id": 7, "a": g["a"], "b": g["b"], "started_ms": NOW})),
        Case::named(
            "notice",
            json!({"id": 8, "a": g["a"], "b": g["b"], "started_ms": NOW,
                   "notice": {"chat": "905321234567@s.whatsapp.net", "chat_name": "Ayşe",
                              "template": "Arriving at {stop} in about {min} min.",
                              "when": {"MinutesBefore": 10}}}),
        ),
        Case::named(
            "too_short",
            json!({"id": 9, "a": g["a"], "b": {"label": "Near", "coord": {"lat": 41.0, "lon": 28.9941}},
                   "started_ms": NOW}),
        ),
        Case::named(
            "same_point",
            json!({"id": 9, "a": g["a"], "b": g["a"], "started_ms": NOW}),
        ),
    ]
}

#[derive(Deserialize)]
struct RestoreReq {
    /// A `snapshot` of an earlier answer, or the content of `trip.json`.
    trip: ActiveTrip,
}

fn restore(req: RestoreReq) -> Result<Value, FfiError> {
    let view = view_of(&req.trip)?;
    Ok(with_handle(handles::global().insert(req.trip), view))
}

fn restore_cases() -> Vec<Case> {
    let trip = ActiveTrip::new(
        7,
        Endpoint {
            label: "Home".into(),
            coord: Coord::new(41.0, 28.994),
        },
        Endpoint {
            label: "Work".into(),
            coord: Coord::new(41.0, 29.006),
        },
        1_000,
    )
    .map(|t| serde_json::to_value(t).unwrap_or_default())
    .unwrap_or_default();
    vec![Case::new(json!({"trip": trip}))]
}

#[derive(Deserialize)]
struct HandleReq {
    handle: i64,
}

fn state_of(req: HandleReq) -> Result<Value, FfiError> {
    with_trip(req.handle, |t| view_of(t))
}

fn open_req() -> Value {
    let g = gate();
    json!({"id": 7, "a": g["a"], "b": g["b"], "started_ms": NOW})
}

fn state_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": H})).after("stops.trip_start", open_req()),
        Case::named("closed", json!({"handle": 12345})),
    ]
}

// ---- fix ----

#[derive(Deserialize)]
struct FixReq {
    handle: i64,
    fix: FixIn,
    #[serde(default)]
    dir: Option<String>,
}

fn fix(req: FixReq) -> Result<Value, FfiError> {
    with_trip(req.handle, |trip| {
        let outcome = trip.on_fix(req.fix.fix(), req.fix.at_ms);
        let out = trip.fix_out(&outcome);
        persist(&req.dir, trip)?;
        Ok(json!({
            "out": out,
            "send_notice": outcome.send_notice,
            "eta_min": outcome.update.eta.map(|e| (e.as_secs_f64() / 60.0).ceil().max(1.0) as u32),
        }))
    })
}

fn fix_at(lat: f64, lon: f64, speed: Option<f64>) -> Value {
    let mut fix = json!({"lat": lat, "lon": lon, "accuracy_m": 8.0, "at_ms": NOW + 5_000});
    if let Some(speed) = speed {
        fix["speed_mps"] = speed.into();
    }
    fix
}

fn fix_cases() -> Vec<Case> {
    let open = || open_req();
    vec![
        // 330 m north of the line, inside the circle, walking.
        Case::new(json!({"handle": H, "fix": fix_at(41.003, 28.999, Some(10.0))}))
            .after("stops.trip_start", open()),
        // Far away: a long wait for the next fix.
        Case::named(
            "far",
            json!({"handle": H, "fix": fix_at(41.05, 29.1, None)}),
        )
        .after("stops.trip_start", open()),
        Case::named(
            "inaccurate",
            json!({"handle": H, "fix": {"lat": 41.0, "lon": 29.0, "accuracy_m": 900.0, "at_ms": NOW}}),
        )
        .after("stops.trip_start", open()),
        Case::named("closed", json!({"handle": 12345, "fix": fix_at(41.0, 29.0, None)})),
    ]
}

// ---- notice / snooze / timeout / dismiss ----

#[derive(Deserialize)]
struct NoticeReq {
    handle: i64,
    /// `sent`, `retry_later` or `give_up`.
    result: String,
    #[serde(default)]
    dir: Option<String>,
}

fn notice_result(req: NoticeReq) -> Result<Value, FfiError> {
    let delivery = match req.result.as_str() {
        "sent" => NoticeDelivery::Sent,
        "retry_later" => NoticeDelivery::RetryLater,
        "give_up" => NoticeDelivery::GiveUp,
        other => return Err(FfiError::invalid(format!("unknown delivery {other:?}"))),
    };
    with_trip(req.handle, |trip| {
        trip.notice_result(delivery);
        persist(&req.dir, trip)?;
        let n = trip.notice.as_ref();
        Ok(json!({
            "sent": n.is_some_and(|n| n.sent),
            "failed": n.is_some_and(|n| n.gave_up()),
            "attempts": n.map_or(0, |n| n.attempts),
        }))
    })
}

fn notice_open() -> Value {
    let mut r = open_req();
    r["notice"] = json!({"chat": "905321234567@s.whatsapp.net", "chat_name": "Ayşe",
                         "template": "Arriving at {stop} in about {min} min.", "when": "WithAlarm"});
    r
}

fn notice_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": H, "result": "sent"})).after("stops.trip_start", notice_open()),
        Case::named("retry", json!({"handle": H, "result": "retry_later"}))
            .after("stops.trip_start", notice_open()),
        Case::named("give_up", json!({"handle": H, "result": "give_up"}))
            .after("stops.trip_start", notice_open()),
        Case::named("bad_result", json!({"handle": H, "result": "maybe"}))
            .after("stops.trip_start", notice_open()),
    ]
}

#[derive(Deserialize)]
struct SnoozeReq {
    handle: i64,
    now_ms: i64,
    minutes: u8,
    #[serde(default)]
    dir: Option<String>,
}

fn snooze(req: SnoozeReq) -> Result<Value, FfiError> {
    with_trip(req.handle, |trip| {
        let ring_at_ms = trip.snooze(req.now_ms, req.minutes);
        persist(&req.dir, trip)?;
        Ok(json!({"ring_at_ms": ring_at_ms, "snoozes_left": trip.snoozes_left()}))
    })
}

fn snooze_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": H, "now_ms": NOW, "minutes": 5}))
            .after("stops.trip_start", open_req()),
        // Minutes are clamped to 1..=15.
        Case::named(
            "clamped",
            json!({"handle": H, "now_ms": NOW, "minutes": 99}),
        )
        .after("stops.trip_start", open_req()),
    ]
}

fn timeout(req: SnoozeReq) -> Result<Value, FfiError> {
    with_trip(req.handle, |trip| {
        let ring_at_ms = trip.ring_timed_out(req.now_ms, req.minutes);
        persist(&req.dir, trip)?;
        Ok(json!({"ring_at_ms": ring_at_ms, "timeouts": trip.timeouts}))
    })
}

fn timeout_cases() -> Vec<Case> {
    vec![
        // Not ringing: nothing to re-arm.
        Case::new(json!({"handle": H, "now_ms": NOW, "minutes": 5}))
            .after("stops.trip_start", open_req()),
    ]
}

#[derive(Deserialize)]
struct DismissReq {
    handle: i64,
    #[serde(default)]
    dir: Option<String>,
}

fn dismiss(req: DismissReq) -> Result<Value, FfiError> {
    with_trip(req.handle, |trip| {
        trip.dismiss();
        persist(&req.dir, trip)?;
        Ok(
            json!({"state": trip.trip.state(), "dismissed": trip.trip.state() == TripState::Dismissed}),
        )
    })
}

fn dismiss_cases() -> Vec<Case> {
    vec![Case::new(json!({"handle": H})).after("stops.trip_start", open_req())]
}

// ---- share / end / clock ----

#[derive(Deserialize)]
struct ShareReq {
    handle: i64,
    #[serde(default)]
    line_m: Option<f64>,
    #[serde(default)]
    eta_min: Option<u32>,
    #[serde(default)]
    here: Option<Coord>,
    #[serde(default)]
    crossed: bool,
    /// Local clock text from `stops.arrival_clock`.
    #[serde(default)]
    arrive: Option<String>,
}

fn share(req: ShareReq) -> Result<Value, FfiError> {
    with_trip(req.handle, |trip| {
        let text = status_share_text(
            &trip.label(),
            req.line_m,
            req.eta_min,
            req.here,
            trip.trip.diameter.center(),
            req.crossed,
            req.arrive.as_deref(),
        );
        Ok(json!({"text": text}))
    })
}

fn share_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": H, "line_m": 640.0, "eta_min": 4,
                         "here": {"lat": 41.0, "lon": 28.99}, "arrive": "20:41"}))
        .after("stops.trip_start", open_req()),
        Case::named("crossed", json!({"handle": H, "crossed": true}))
            .after("stops.trip_start", open_req()),
    ]
}

#[derive(Deserialize)]
struct EndReq {
    handle: i64,
    /// `arrived` or `cancelled`.
    outcome: String,
    now_ms: i64,
    /// With a `dir` the trip is written, moved into `history.json` and `trip.json` removed (under the store lock).
    #[serde(default)]
    dir: Option<String>,
}

fn end(req: EndReq) -> Result<Value, FfiError> {
    let outcome = match req.outcome.as_str() {
        "arrived" => Outcome::Arrived,
        "cancelled" => Outcome::Cancelled,
        other => return Err(FfiError::invalid(format!("unknown outcome {other:?}"))),
    };
    with_trip(req.handle, |trip| {
        let record = TripRecord::from_session(trip, req.now_ms, outcome);
        let mut recorded = true;
        if req.dir.is_some() {
            let dir = data_dir(&req.dir)?;
            let _guard = store::lock();
            store::save_trip(&dir, trip)?;
            recorded = store::end_trip(&dir, outcome, req.now_ms)?.is_some();
        }
        Ok(json!({
            "record": record,
            "title": record.title(),
            "summary_line": record.summary_line(),
            "recorded": recorded,
        }))
    })
}

fn end_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"handle": H, "outcome": "arrived", "now_ms": NOW + 600_000}))
            .after("stops.trip_start", open_req()),
        Case::named(
            "cancelled",
            json!({"handle": H, "outcome": "cancelled", "now_ms": NOW + 60_000}),
        )
        .after("stops.trip_start", open_req()),
        Case::named(
            "bad_outcome",
            json!({"handle": H, "outcome": "lost", "now_ms": NOW}),
        )
        .after("stops.trip_start", open_req()),
    ]
}

#[derive(Deserialize)]
struct ClockReq {
    now_ms: i64,
    eta_min: u32,
    tz_offset_s: i32,
}

fn arrival_clock(req: ClockReq) -> Result<Value, FfiError> {
    Ok(json!({"clock": rovsti_geo::arrival_clock(req.now_ms, req.eta_min, req.tz_offset_s)}))
}

fn clock_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"now_ms": NOW, "eta_min": 12, "tz_offset_s": super::TZ})),
        Case::named(
            "zero_eta",
            json!({"now_ms": NOW, "eta_min": 0, "tz_offset_s": 0}),
        ),
    ]
}

// ---- crossing ----

#[derive(Deserialize)]
struct ValidateReq {
    a: Coord,
    b: Coord,
}

/// The circle on AB, or why A and B cannot form one (`invalid`, the sentence in `message`).
fn validate(req: ValidateReq) -> Result<Value, FfiError> {
    let d = Diameter::new(req.a, req.b).map_err(diameter_error)?;
    Ok(json!({
        "length_m": d.length_m(),
        "radius_m": d.radius_m(),
        "center": d.center(),
        "min_m": rovsti_geo::MIN_DIAMETER_M,
        "max_m": rovsti_geo::MAX_DIAMETER_M,
    }))
}

fn validate_cases() -> Vec<Case> {
    let g = gate();
    vec![
        Case::new(json!({"a": g["a"]["coord"], "b": g["b"]["coord"]})),
        Case::named(
            "too_short",
            json!({"a": {"lat": 41.0, "lon": 29.0}, "b": {"lat": 41.0, "lon": 29.0001}}),
        ),
        Case::named(
            "too_long",
            json!({"a": {"lat": 41.0, "lon": 29.0}, "b": {"lat": 42.0, "lon": 29.0}}),
        ),
        Case::named(
            "invalid_coord",
            json!({"a": {"lat": 95.0, "lon": 29.0}, "b": {"lat": 41.0, "lon": 29.0}}),
        ),
    ]
}

#[derive(Deserialize)]
struct ReadReq {
    a: Coord,
    b: Coord,
    at: Coord,
    #[serde(default)]
    accuracy_m: f64,
    /// Points of the circle outline to draw (0 for none).
    #[serde(default)]
    outline_points: usize,
}

/// Where `at` is against the gate: side, distances and (for the map) the circle outline and the two half discs.
fn read(req: ReadReq) -> Result<Value, FfiError> {
    use rovsti_geo::{Side, SideReading};
    let d = Diameter::new(req.a, req.b).map_err(diameter_error)?;
    let reading = match d.read_side(req.at, req.accuracy_m) {
        SideReading::Side(side) => json!({"kind": "side", "side": side}),
        SideReading::OnLine => json!({"kind": "on_line", "side": null}),
    };
    let n = req.outline_points.min(720);
    Ok(json!({
        "reading": reading,
        "offset_m": d.offset_m(req.at),
        "line_m": d.distance_to_segment_m(req.at),
        "circle_m": d.distance_to_circle_m(req.at),
        "contains": d.contains(req.at),
        "outline": if n > 0 { d.outline(n) } else { Vec::new() },
        "left_disc": if n > 0 { d.half_disc(Side::Left, n) } else { Vec::new() },
        "right_disc": if n > 0 { d.half_disc(Side::Right, n) } else { Vec::new() },
    }))
}

fn read_cases() -> Vec<Case> {
    let g = gate();
    let (a, b) = (g["a"]["coord"].clone(), g["b"]["coord"].clone());
    vec![
        Case::new(
            json!({"a": a, "b": b, "at": {"lat": 41.0003, "lon": 28.999}, "accuracy_m": 5.0,
                         "outline_points": 8}),
        ),
        Case::named(
            "outside",
            json!({"a": a, "b": b, "at": {"lat": 41.01, "lon": 29.0}, "accuracy_m": 5.0}),
        ),
        Case::named("too_short", json!({"a": a, "b": a, "at": a})),
    ]
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::ops::call;

    fn run(op: &str, request: Value) -> Value {
        let answer: Value =
            serde_json::from_slice(&call(op, request.to_string().as_bytes())).unwrap();
        answer
    }

    fn ok(op: &str, request: Value) -> Value {
        let mut answer = run(op, request);
        assert!(answer.get("err").is_none(), "{op}: {answer}");
        answer["ok"].take()
    }

    fn fix(lat: f64, lon: f64, at_ms: i64) -> Value {
        json!({"lat": lat, "lon": lon, "accuracy_m": 5.0, "speed_mps": 8.0, "at_ms": at_ms})
    }

    fn open() -> i64 {
        ok("stops.trip_start", open_req())["handle"]
            .as_i64()
            .unwrap()
    }

    #[test]
    fn crossing_the_line_rings_once_then_snooze_and_timeout_rearm() {
        let h = open();
        // Left (north) of the line, then right (south): the crossing rings.
        let first = ok(
            "stops.trip_fix",
            json!({"handle": h, "fix": fix(41.003, 28.999, NOW)}),
        );
        assert_eq!(first["out"]["ring"], false);
        assert_eq!(first["out"]["side"], "left");
        let second = ok(
            "stops.trip_fix",
            json!({"handle": h, "fix": fix(40.997, 29.0, NOW + 5_000)}),
        );
        assert_eq!(second["out"]["ring"], true);
        assert_eq!(second["out"]["state"], "Arrived");
        assert_eq!(second["out"]["title"], "Crossed Home – Work");
        let third = ok(
            "stops.trip_fix",
            json!({"handle": h, "fix": fix(40.996, 29.0, NOW + 10_000)}),
        );
        assert_eq!(third["out"]["ring"], false, "rings once");

        // A ring that timed out re-rings without using a snooze; a snooze uses one.
        let timeout = ok(
            "stops.trip_timeout",
            json!({"handle": h, "now_ms": NOW, "minutes": 5}),
        );
        assert_eq!(timeout["ring_at_ms"], NOW + 300_000);
        assert_eq!(timeout["timeouts"], 1);
        let snooze = ok(
            "stops.trip_snooze",
            json!({"handle": h, "now_ms": NOW, "minutes": 3}),
        );
        assert_eq!(snooze["snoozes_left"], 4);
        let state = ok("stops.trip_state", json!({"handle": h}));
        assert_eq!(state["snoozes"], 1);
        assert_eq!(state["ring_at_ms"], NOW + 180_000);

        let dismissed = ok("stops.trip_dismiss", json!({"handle": h}));
        assert_eq!(dismissed["state"], "Dismissed");
        assert!(handles::global().release(h));
        let gone = run("stops.trip_state", json!({"handle": h}));
        assert_eq!(gone["err"]["kind"], "not_found");
    }

    #[test]
    fn persisting_writes_the_file_the_service_reads_and_end_moves_it_to_the_history() {
        let dir = std::env::temp_dir().join(format!("rovsti-ffi-trip-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let d = dir.to_str().unwrap();
        let mut req = open_req();
        req["dir"] = d.into();
        let h = ok("stops.trip_start", req)["handle"].as_i64().unwrap();
        ok(
            "stops.trip_fix",
            json!({"handle": h, "dir": d, "fix": fix(41.003, 28.999, NOW)}),
        );

        let loaded = ok("stops.store_trip_load", json!({"dir": d}));
        assert_eq!(loaded["trip"]["track"]["fixes"], 1);
        // The headless kind of the foreground service sees the same file.
        let headless = rovsti_app_core::stops::headless::handle(
            "trip_fix",
            &json!({"dir": d, "lat": 40.997, "lon": 29.0, "accuracy_m": 5.0, "speed_mps": 8.0, "at_ms": NOW + 5_000})
                .to_string(),
        );
        assert!(headless.contains("\"ring\":true"), "{headless}");

        let ended = ok(
            "stops.store_trip_end",
            json!({"dir": d, "outcome": "arrived", "now_ms": NOW + 60_000}),
        );
        assert_eq!(ended["record"]["id"], 7);
        let history = ok("stops.store_read", json!({"dir": d, "file": "history"}));
        assert_eq!(history["value"]["records"].as_array().unwrap().len(), 1);
        let again = ok(
            "stops.store_trip_end",
            json!({"dir": d, "outcome": "arrived", "now_ms": NOW}),
        );
        assert!(again["record"].is_null(), "no trip left to end");
        handles::global().release(h);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_store_lock_serialises_trip_writers() {
        let dir = std::env::temp_dir().join(format!("rovsti-ffi-lock-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let guard = store::lock();
        let d = dir.to_str().unwrap().to_owned();
        let started = std::time::Instant::now();
        let worker = std::thread::spawn(move || {
            ok("stops.store_trip_load", json!({"dir": d}));
            started.elapsed()
        });
        std::thread::sleep(std::time::Duration::from_millis(150));
        drop(guard);
        let waited = worker.join().unwrap();
        assert!(
            waited >= std::time::Duration::from_millis(140),
            "{waited:?}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }
}
