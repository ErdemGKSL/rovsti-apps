//! The headless handler behind `RovstiBridge.nativeHeadless(kind, json)`.
//!
//! The Kotlin `TripService` and `TripAlarm` run with no UI (the activity may
//! be gone). On every location fix, and on the alarm buttons, they ask Rust
//! for the decision here. Everything is a blocking function over JSON: no
//! GPUI, no event loop. Bad input answers `""` ("no decision" to Kotlin).
//!
//! | kind         | in                                          | out                                              |
//! |--------------|---------------------------------------------|--------------------------------------------------|
//! | `trip_fix`   | `{dir, lat, lon, accuracy_m, speed_mps?, at_ms}` | a `FixOut`, or `{"state":"ended"}`          |
//! | `trip_alarm` | `{dir, action: "dismiss" \| "snooze"}`      | `{dismissed:true}` or `{ring_at_ms}`             |
//! | `trip_end`   | `{dir, reason: "arrived" \| "cancelled"}`   | `{"state":"ended"}`                              |
//!
//! `dir` is the app data directory (`Headless.dataDir`). With no trip in
//! progress every kind answers `{"state":"ended"}`, which stops the service.

use std::path::Path;

use rovsti_android::headless::{self, Handler};
use rovsti_geo::{FixIn, NoticeDelivery, Outcome, StopsPrefs};
use rovsti_ui::storage;
use rovsti_wa_ui::headless::{SendFailure, send_text_in};
use serde::Deserialize;
use serde_json::json;

use crate::store::{self, now_ms};

const ENDED: &str = "{\"state\":\"ended\"}";

#[derive(Deserialize)]
struct FixRequest {
    dir: String,
    #[serde(flatten)]
    fix: FixIn,
}

#[derive(Deserialize)]
struct AlarmRequest {
    dir: String,
    action: String,
}

#[derive(Deserialize)]
struct EndRequest {
    dir: String,
    #[serde(default)]
    reason: String,
}

/// Sends the arriving-soon text: `(dir, chat, text)`.
type SendNotice<'a> = &'a dyn Fn(&str, &str, &str) -> Result<(), SendFailure>;

/// Handles one headless request; `""` for an unknown kind.
pub fn handle(kind: &str, json: &str) -> String {
    const TABLE: &[(&str, Handler)] = &[
        ("trip_fix", trip_fix),
        ("trip_alarm", trip_alarm),
        ("trip_end", trip_end),
    ];
    headless::dispatch(kind, json, TABLE)
}

fn trip_fix(_: &str, json: &str) -> String {
    let Ok(request) = serde_json::from_str::<FixRequest>(json) else {
        return String::new();
    };
    fix_in(Path::new(&request.dir), request.fix, &send_text_in)
}

/// One fix: advance the trip, persist it, maybe send the notice (outside the
/// lock, it blocks on the network), record how that went, and answer.
fn fix_in(dir: &Path, fix: FixIn, send: SendNotice<'_>) -> String {
    let guard = store::lock();
    let Some(mut trip) = store::load_trip(dir) else {
        return ENDED.to_owned();
    };
    let outcome = trip.on_fix(fix.fix(), fix.at_ms);
    save(dir, &trip);
    drop(guard);

    let (Some(text), Some(notice)) = (outcome.send_notice.as_deref(), trip.notice.as_ref()) else {
        return answer(&trip.fix_out(&outcome));
    };
    let delivery = if notice.chat.is_empty() {
        NoticeDelivery::GiveUp
    } else {
        match send(&dir.to_string_lossy(), &notice.chat, text) {
            Ok(()) => NoticeDelivery::Sent,
            Err(SendFailure::Retryable(why)) => {
                log::warn!("trip notice will be retried: {why}");
                NoticeDelivery::RetryLater
            }
            Err(failure) => {
                log::warn!("trip notice given up: {failure:?}");
                NoticeDelivery::GiveUp
            }
        }
    };

    // Record the result on the trip as it is now: the user may have ended it
    // while the send was in flight.
    let _guard = store::lock();
    match store::load_trip(dir) {
        Some(mut current) if current.id == trip.id => {
            current.notice_result(delivery);
            save(dir, &current);
            answer(&current.fix_out(&outcome))
        }
        _ => {
            trip.notice_result(delivery);
            answer(&trip.fix_out(&outcome))
        }
    }
}

fn trip_alarm(_: &str, json: &str) -> String {
    let Ok(request) = serde_json::from_str::<AlarmRequest>(json) else {
        return String::new();
    };
    alarm_in(Path::new(&request.dir), &request.action, now_ms())
}

fn alarm_in(dir: &Path, action: &str, now_ms: i64) -> String {
    if !matches!(action, "dismiss" | "snooze") {
        return String::new();
    }
    let _guard = store::lock();
    let Some(mut trip) = store::load_trip(dir) else {
        return ENDED.to_owned();
    };
    if action == "snooze" {
        let minutes = storage::load_json::<StopsPrefs>(dir, store::PREFS_FILE)
            .unwrap_or_default()
            .snooze_min;
        if let Some(at) = trip.snooze(now_ms, minutes) {
            save(dir, &trip);
            return json!({ "ring_at_ms": at }).to_string();
        }
        // Out of snoozes: the alarm has done its job, the trip is over.
    }
    end(dir, Outcome::Arrived, now_ms);
    json!({ "dismissed": true }).to_string()
}

fn trip_end(_: &str, json: &str) -> String {
    let Ok(request) = serde_json::from_str::<EndRequest>(json) else {
        return String::new();
    };
    let outcome = if request.reason == "arrived" {
        Outcome::Arrived
    } else {
        Outcome::Cancelled
    };
    let _guard = store::lock();
    end(Path::new(&request.dir), outcome, now_ms());
    ENDED.to_owned()
}

fn end(dir: &Path, outcome: Outcome, now_ms: i64) {
    if let Err(e) = store::end_trip(dir, outcome, now_ms) {
        log::warn!("could not end the trip: {e}");
    }
}

fn save(dir: &Path, trip: &rovsti_geo::ActiveTrip) {
    if let Err(e) = store::save_trip(dir, trip) {
        log::warn!("could not save the trip: {e}");
    }
}

fn answer(out: &rovsti_geo::FixOut) -> String {
    serde_json::to_string(out).unwrap_or_default()
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use std::cell::Cell;

    use rovsti_geo::{
        AlarmRule, Coord, EARTH_RADIUS_M, FixOut, History, NoticeState, TripState,
        notice::DEFAULT_TEMPLATE,
    };

    use super::*;
    use crate::store::tests::{tmp, trip};

    const DEST: Coord = Coord::new(41.0, 29.0);

    fn fix(metres_north: f64, at_ms: i64) -> FixIn {
        FixIn {
            lat: DEST.lat + (metres_north / EARTH_RADIUS_M).to_degrees(),
            lon: DEST.lon,
            accuracy_m: 10.0,
            speed_mps: Some(10.0),
            at_ms,
        }
    }

    fn never(_: &str, _: &str, _: &str) -> Result<(), SendFailure> {
        panic!("no notice expected");
    }

    fn run(dir: &Path, f: FixIn) -> FixOut {
        serde_json::from_str(&fix_in(dir, f, &never)).unwrap()
    }

    fn history(dir: &Path) -> History {
        storage::load_json(dir, store::HISTORY_FILE).unwrap_or_default()
    }

    #[test]
    fn a_fix_sequence_rings_exactly_once_and_persists_the_state() {
        let dir = tmp("hl-ring");
        store::save_trip(&dir, &trip(1)).unwrap();

        let far = run(&dir, fix(5_000.0, 1));
        assert!(!far.ring);
        assert_eq!(far.state, TripState::Tracking);
        assert!(far.next_fix_ms > 0);

        let near = run(&dir, fix(300.0, 2));
        assert!(near.ring);
        assert_eq!(near.state, TripState::Arrived);
        assert!(near.title.starts_with("Arriving at"));
        assert_eq!(
            store::load_trip(&dir).unwrap().trip.state(),
            TripState::Arrived
        );

        let closer = run(&dir, fix(100.0, 3));
        assert!(!closer.ring);
        assert_eq!(closer.state, TripState::Arrived);
        assert_eq!(history(&dir).records.len(), 0);
    }

    #[test]
    fn no_trip_answers_ended() {
        let dir = tmp("hl-none");
        assert_eq!(fix_in(&dir, fix(100.0, 1), &never), ENDED);
        assert_eq!(alarm_in(&dir, "dismiss", 5), ENDED);
        let json = format!(
            "{{\"dir\":{:?},\"reason\":\"cancelled\"}}",
            dir.to_str().unwrap()
        );
        assert_eq!(handle("trip_end", &json), ENDED);
    }

    #[test]
    fn snooze_is_capped_and_then_dismisses() {
        let dir = tmp("hl-snooze");
        store::save_trip(&dir, &trip(2)).unwrap();
        storage::save_json(
            &dir,
            store::PREFS_FILE,
            &StopsPrefs {
                snooze_min: 3,
                ..StopsPrefs::default()
            },
        )
        .unwrap();

        for n in 1..=5 {
            let out: serde_json::Value =
                serde_json::from_str(&alarm_in(&dir, "snooze", 1_000)).unwrap();
            assert_eq!(out["ring_at_ms"], 1_000 + 3 * 60_000, "snooze {n}");
        }
        assert_eq!(store::load_trip(&dir).unwrap().snoozes, 5);

        let out: serde_json::Value =
            serde_json::from_str(&alarm_in(&dir, "snooze", 1_000)).unwrap();
        assert_eq!(out["dismissed"], true);
        assert!(store::load_trip(&dir).is_none());
        let records = history(&dir).records;
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].snoozes, 5);
        // Kotlin follows a refused snooze with a dismiss: nothing is recorded twice.
        assert_eq!(alarm_in(&dir, "dismiss", 2_000), ENDED);
        assert_eq!(history(&dir).records.len(), 1);
    }

    #[test]
    fn dismiss_writes_one_history_record() {
        let dir = tmp("hl-dismiss");
        store::save_trip(&dir, &trip(4)).unwrap();
        let out: serde_json::Value =
            serde_json::from_str(&alarm_in(&dir, "dismiss", 9_000)).unwrap();
        assert_eq!(out["dismissed"], true);
        assert!(store::load_trip(&dir).is_none());
        let records = history(&dir).records;
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].outcome, Outcome::Arrived);
        assert_eq!(records[0].ended_ms, 9_000);
    }

    #[test]
    fn trip_end_records_the_reason() {
        let dir = tmp("hl-end");
        store::save_trip(&dir, &trip(5)).unwrap();
        let json = format!(
            "{{\"dir\":{:?},\"reason\":\"cancelled\"}}",
            dir.to_str().unwrap()
        );
        assert_eq!(handle("trip_end", &json), ENDED);
        assert_eq!(history(&dir).records[0].outcome, Outcome::Cancelled);
    }

    #[test]
    fn bad_input_answers_empty() {
        for kind in ["trip_fix", "trip_alarm", "trip_end"] {
            assert_eq!(handle(kind, "not json"), "");
            assert_eq!(handle(kind, "{}"), "");
        }
        assert_eq!(handle("unknown", "{}"), "");
        let dir = tmp("hl-badaction");
        let json = format!(
            "{{\"dir\":{:?},\"action\":\"explode\"}}",
            dir.to_str().unwrap()
        );
        assert_eq!(handle("trip_alarm", &json), "");
    }

    #[test]
    fn handle_routes_trip_fix_with_a_flat_request() {
        let dir = tmp("hl-route");
        store::save_trip(&dir, &trip(6)).unwrap();
        let json = format!(
            "{{\"dir\":{:?},\"lat\":41.1,\"lon\":29.0,\"accuracy_m\":8.0,\"at_ms\":5}}",
            dir.to_str().unwrap()
        );
        let out: FixOut = serde_json::from_str(&handle("trip_fix", &json)).unwrap();
        assert_eq!(out.state, TripState::Tracking);
    }

    fn with_notice(dir: &Path, chat: &str) {
        let mut t = trip(8);
        t.notice = Some(NoticeState {
            chat: chat.into(),
            text_template: DEFAULT_TEMPLATE.into(),
            rule: AlarmRule::Radius { metres: 1_000.0 },
            sent: false,
            attempts: 0,
        });
        store::save_trip(dir, &t).unwrap();
    }

    #[test]
    fn a_notice_with_no_link_gives_up_and_never_retries() {
        let dir = tmp("hl-nolink");
        with_notice(&dir, "chat@s.whatsapp.net");

        // The real sender: no whatsapp-link.json in the directory.
        let first: FixOut =
            serde_json::from_str(&fix_in(&dir, fix(900.0, 1), &send_text_in)).unwrap();
        assert!(!first.notice_sent);
        let notice = store::load_trip(&dir).unwrap().notice.unwrap();
        assert!(!notice.sent);
        assert_eq!(notice.attempts, 3);

        // Later fixes inside the radius do not try again.
        let calls = Cell::new(0);
        let counting = |_: &str, _: &str, _: &str| {
            calls.set(calls.get() + 1);
            Ok(())
        };
        fix_in(&dir, fix(800.0, 2), &counting);
        assert_eq!(calls.get(), 0);
    }

    #[test]
    fn a_retryable_failure_retries_then_stops_after_three_attempts() {
        let dir = tmp("hl-retry");
        with_notice(&dir, "chat");
        let calls = Cell::new(0);
        let flaky = |_: &str, _: &str, _: &str| {
            calls.set(calls.get() + 1);
            Err(SendFailure::Retryable("offline".into()))
        };
        for at in 1..=5 {
            fix_in(&dir, fix(900.0, at), &flaky);
        }
        assert_eq!(calls.get(), 3);
    }

    #[test]
    fn a_delivered_notice_is_sent_once() {
        let dir = tmp("hl-sent");
        with_notice(&dir, "chat");
        let sent = std::cell::RefCell::new(Vec::new());
        let ok = |_: &str, chat: &str, text: &str| {
            sent.borrow_mut().push((chat.to_owned(), text.to_owned()));
            Ok(())
        };
        let first: FixOut = serde_json::from_str(&fix_in(&dir, fix(900.0, 1), &ok)).unwrap();
        fix_in(&dir, fix(800.0, 2), &ok);
        assert!(first.notice_sent);
        let sent = sent.into_inner();
        assert_eq!(sent.len(), 1);
        assert_eq!(sent[0].0, "chat");
        assert!(sent[0].1.contains("Kadikoy"));
        assert!(store::load_trip(&dir).unwrap().notice.unwrap().sent);
    }
}
