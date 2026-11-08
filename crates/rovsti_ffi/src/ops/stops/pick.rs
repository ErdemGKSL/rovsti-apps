//! `stops.pick_*` and `stops.snap`: choosing A and B on the map.
//!
//! `stops.pick_replay` replays an ordered list of gesture events on a fresh `Picking` (the state machine of
//! `rovsti_app_core::stops::pick`) and answers the final state plus one outcome per event, the same stateless shape
//! as `sms_timeline.build`: a pick is a handful of small events, so Kotlin keeps its immutable event list and
//! no native state has to follow it. The per-frame part of a drag (the pin following the finger) is Kotlin.

use super::gate;
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_app_core::stops::pick::{self, Hint, Picking, TapOutcome};
use rovsti_app_core::stops::snap::{self, DropInput, DropSnap, VelocityTracker};
use rovsti_app_core::stops::types::{Destination, Ends, Slot, coord_text, distance_text};
use rovsti_geo::{Coord, Endpoint, SavedStops};
use serde::Deserialize;
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!("stops.pick_replay", ReplayReq, Value, replay, replay_cases),
    op!("stops.pick_labels", LabelsReq, Value, labels, labels_cases),
    op!(
        "stops.pick_save_offer",
        OfferReq,
        Value,
        save_offer,
        offer_cases
    ),
    op!("stops.snap", SnapReq, Value, snap_decide, snap_cases),
];

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "lowercase")]
enum SlotDto {
    A,
    B,
}

impl SlotDto {
    fn slot(self) -> Slot {
        match self {
            Self::A => Slot::A,
            Self::B => Slot::B,
        }
    }
}

fn slot_name(slot: Slot) -> &'static str {
    match slot {
        Slot::A => "a",
        Slot::B => "b",
    }
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Event {
    Tap { dest: Destination },
    Place { slot: SlotDto, dest: Destination },
    SetPair { a: Destination, b: Destination },
    Restore { ends: Ends },
    Swap,
    Clear,
    Remove { slot: SlotDto },
    BeginMove { slot: SlotDto },
    OpenMenu { slot: SlotDto },
    CloseMenu,
    CancelMove,
    DragStart { slot: SlotDto },
    DragTo { slot: SlotDto, coord: Coord },
    DragEnd { slot: SlotDto, dest: Destination },
    DragCancel,
    SetSheetSlot { slot: SlotDto },
}

#[derive(Deserialize)]
struct ReplayReq {
    #[serde(default)]
    events: Vec<Event>,
}

fn replay(req: ReplayReq) -> Result<Value, FfiError> {
    let mut p = Picking::default();
    let mut outcomes = Vec::with_capacity(req.events.len());
    for event in req.events {
        let outcome = match event {
            Event::Tap { dest } => match p.tap(dest) {
                TapOutcome::Placed(slot) => format!("placed_{}", slot_name(slot)),
                TapOutcome::MenuClosed => "menu_closed".to_owned(),
                TapOutcome::Full => "full".to_owned(),
            },
            Event::DragStart { slot } => if p.drag_start(slot.slot()) {
                "grabbed"
            } else {
                "empty"
            }
            .to_owned(),
            other => {
                match other {
                    Event::Place { slot, dest } => p.place(slot.slot(), dest),
                    Event::SetPair { a, b } => p.set_pair(a, b),
                    Event::Restore { ends } => p.restore(ends),
                    Event::Swap => p.swap(),
                    Event::Clear => p.clear(),
                    Event::Remove { slot } => p.remove(slot.slot()),
                    Event::BeginMove { slot } => p.begin_move(slot.slot()),
                    Event::OpenMenu { slot } => p.open_menu(slot.slot()),
                    Event::CloseMenu => p.close_menu(),
                    Event::CancelMove => p.cancel_move(),
                    Event::DragTo { slot, coord } => p.drag_to(slot.slot(), coord),
                    Event::DragEnd { slot, dest } => p.drag_end(slot.slot(), dest),
                    Event::DragCancel => p.drag_cancel(),
                    Event::SetSheetSlot { slot } => p.set_sheet_slot(slot.slot()),
                    Event::Tap { .. } | Event::DragStart { .. } => {}
                }
                "ok".to_owned()
            }
        };
        outcomes.push(outcome);
    }
    let hint = p.hint();
    let (hint_kind, metres) = match hint {
        Hint::SetA => ("set_a", None),
        Hint::SetB => ("set_b", None),
        Hint::Moving(_) => ("moving", None),
        Hint::Ready { metres } => ("ready", Some(metres)),
        Hint::Invalid(_) => ("invalid", None),
    };
    Ok(json!({
        "ends": p.ends,
        "outcomes": outcomes,
        "hint": {"kind": hint_kind, "text": hint.text(), "metres": metres},
        "can_start": p.can_start(),
        "moving": p.moving().map(slot_name),
        "menu": p.menu().map(slot_name),
        "dragging": p.dragging().map(slot_name),
        "sheet_slot": slot_name(p.sheet_slot()),
        "confirm_slot": slot_name(p.confirm_slot()),
        "tap_slot": p.slot_for_tap().map(slot_name),
    }))
}

fn dropped(lat: f64, lon: f64) -> Value {
    json!({"label": "", "coord": {"lat": lat, "lon": lon}, "gtfs_stop": null, "pattern": null})
}

fn named(label: &str, lat: f64, lon: f64) -> Value {
    json!({"label": label, "coord": {"lat": lat, "lon": lon}, "gtfs_stop": "S1", "pattern": 4})
}

fn replay_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"events": [
            {"kind": "tap", "dest": dropped(41.0, 28.994)},
            {"kind": "tap", "dest": named("Work", 41.0, 29.006)}
        ]})),
        Case::named("empty", json!({"events": []})),
        Case::named(
            "full_and_menu",
            json!({"events": [
                {"kind": "tap", "dest": dropped(41.0, 28.994)},
                {"kind": "tap", "dest": dropped(41.0, 29.006)},
                {"kind": "tap", "dest": dropped(41.1, 29.1)},
                {"kind": "open_menu", "slot": "a"},
                {"kind": "tap", "dest": dropped(41.1, 29.1)}
            ]}),
        ),
        Case::named(
            "drag",
            json!({"events": [
                {"kind": "tap", "dest": dropped(41.0, 28.994)},
                {"kind": "tap", "dest": dropped(41.0, 29.006)},
                {"kind": "drag_start", "slot": "b"},
                {"kind": "drag_to", "slot": "b", "coord": {"lat": 41.0, "lon": 29.0}},
                {"kind": "drag_cancel"},
                {"kind": "drag_start", "slot": "a"},
                {"kind": "drag_end", "slot": "a", "dest": named("Pier", 41.0, 28.99)},
                {"kind": "swap"}
            ]}),
        ),
        Case::named(
            "too_close",
            json!({"events": [
                {"kind": "place", "slot": "a", "dest": dropped(41.0, 29.0)},
                {"kind": "place", "slot": "b", "dest": dropped(41.0, 29.0001)}
            ]}),
        ),
        Case::named(
            "moving",
            json!({"events": [
                {"kind": "tap", "dest": dropped(41.0, 28.994)},
                {"kind": "begin_move", "slot": "a"},
                {"kind": "remove", "slot": "b"},
                {"kind": "drag_start", "slot": "b"}
            ]}),
        ),
    ]
}

#[derive(Deserialize)]
struct LabelsReq {
    ends: Ends,
    /// Clipboard text to clean for a field.
    #[serde(default)]
    paste: Option<String>,
    /// The picker's search text, for its empty line.
    #[serde(default)]
    query: Option<String>,
    #[serde(default)]
    has_feed: bool,
    /// A stored label of a list row.
    #[serde(default)]
    row_label: Option<String>,
    /// A stored endpoint (a recent trip) to turn back into a destination.
    #[serde(default)]
    endpoint: Option<Endpoint>,
}

fn labels(req: LabelsReq) -> Result<Value, FfiError> {
    let side = |slot: Slot| match req.ends.get(slot) {
        None => Value::Null,
        Some(d) => json!({
            "label": pick::point_label(slot, Some(d)),
            "title": d.title(),
            "named": d.is_named(),
            "details": pick::details_text(d),
            "queued": pick::queued_text(slot, d),
            "endpoint": pick::trip_endpoint(slot, d),
        }),
    };
    let pair = match (&req.ends.a, &req.ends.b) {
        (Some(a), Some(b)) => Some(format!(
            "A-B {}",
            distance_text(a.coord.distance_m(b.coord))
        )),
        _ => None,
    };
    Ok(json!({
        "a": side(Slot::A),
        "b": side(Slot::B),
        "empty_label_a": pick::point_label(Slot::A, None),
        "empty_label_b": pick::point_label(Slot::B, None),
        "picker_title_a": Slot::A.picker_title(),
        "picker_title_b": Slot::B.picker_title(),
        "pair": pair,
        "next_slot": slot_name(req.ends.next_slot()),
        "paste": req.paste.as_deref().map(|p| pick::clean_paste(Some(p))),
        "no_match": req.query.as_deref().map(|q| pick::no_match_text(q, req.has_feed)),
        "row_label": req.row_label.as_deref().map(pick::row_label),
        "destination": req.endpoint.as_ref().map(pick::dest_from_endpoint),
        "coord_text": req.ends.a.as_ref().map(|d| coord_text(d.coord)),
    }))
}

fn labels_cases() -> Vec<Case> {
    let g = gate();
    vec![
        Case::new(json!({
            "ends": {"a": named("Home", 41.0, 28.994), "b": dropped(41.0, 29.006)},
            "paste": "  41.0082,\n 28.9784  ",
            "query": " moda ",
            "has_feed": false,
            "row_label": "  ",
            "endpoint": {"label": "Point A", "coord": g["a"]["coord"]}
        })),
        Case::named("empty", json!({"ends": {"a": null, "b": null}})),
    ]
}

#[derive(Deserialize)]
struct OfferReq {
    ends: Ends,
    saved: SavedStops,
}

fn save_offer(req: OfferReq) -> Result<Value, FfiError> {
    Ok(match pick::save_offer(&req.ends, &req.saved) {
        pick::SaveOffer::None => json!({"kind": "none", "id": null, "name": null}),
        pick::SaveOffer::Save => json!({"kind": "save", "id": null, "name": null}),
        pick::SaveOffer::Saved { id, name } => json!({"kind": "saved", "id": id, "name": name}),
    })
}

pub(super) fn saved_sample() -> Value {
    json!({"version": 2, "next_id": 3, "stops": [
        {"id": 1, "name": "Pier", "a": {"label": "Pier W", "coord": {"lat": 41.0, "lon": 28.994}},
         "b": {"label": "Pier E", "coord": {"lat": 41.0, "lon": 29.006}}, "created_ms": 1_789_000_000_000_i64,
         "favourite": true},
        {"id": 2, "name": "Ferry", "a": {"label": "", "coord": {"lat": 41.02, "lon": 29.0}},
         "b": {"label": "", "coord": {"lat": 41.03, "lon": 29.0}}, "created_ms": 1_789_500_000_000_i64,
         "colour": "blue"}
    ]})
}

fn offer_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"ends": {"a": dropped(41.0, 28.994), "b": dropped(41.0, 29.006)},
                         "saved": saved_sample()}),
        ),
        Case::named(
            "new",
            json!({"ends": {"a": dropped(41.1, 28.994), "b": dropped(41.1, 29.006)},
                   "saved": saved_sample()}),
        ),
        Case::named(
            "incomplete",
            json!({"ends": {"a": dropped(41.1, 28.994), "b": null}, "saved": saved_sample()}),
        ),
    ]
}

#[derive(Deserialize)]
struct Sample {
    t_ms: u64,
    x: f32,
    y: f32,
}

#[derive(Deserialize)]
struct SnapReq {
    /// Pointer samples of the drag; the speeds are read from them at `now_ms` (the release).
    #[serde(default)]
    samples: Vec<Sample>,
    #[serde(default)]
    now_ms: u64,
    /// Used when no samples are sent.
    #[serde(default)]
    release_px_s: f32,
    #[serde(default)]
    peak_px_s: f32,
    /// Pixels to the nearest stop that could take the pin, if any.
    #[serde(default)]
    candidate_px: Option<f32>,
    #[serde(default)]
    travel_px: f32,
    #[serde(default)]
    started_on_candidate: bool,
}

fn snap_decide(req: SnapReq) -> Result<Value, FfiError> {
    let (release, peak) = if req.samples.is_empty() {
        (req.release_px_s, req.peak_px_s)
    } else {
        let mut tracker = VelocityTracker::default();
        for s in &req.samples {
            tracker.push(s.t_ms, (s.x, s.y));
        }
        (
            tracker.speed_px_s(req.now_ms),
            tracker.peak_px_s(req.now_ms),
        )
    };
    let decision = snap::decide(&DropInput {
        release_px_s: release,
        peak_px_s: peak,
        candidate_px: req.candidate_px,
        travel_px: req.travel_px,
        started_on_candidate: req.started_on_candidate,
    });
    Ok(json!({
        "release_px_s": release,
        "peak_px_s": peak,
        "radius_px": snap::snap_radius_px(release),
        "decision": match decision { DropSnap::Snap => "snap", DropSnap::Free => "free" },
        "tap_snaps": snap::tap_snaps(req.candidate_px),
        "tap_radius_px": snap::TAP_RADIUS_PX,
    }))
}

fn snap_cases() -> Vec<Case> {
    vec![
        Case::new(json!({
            "samples": [{"t_ms": 0, "x": 0.0, "y": 0.0}, {"t_ms": 40, "x": 30.0, "y": 0.0},
                        {"t_ms": 80, "x": 70.0, "y": 0.0}],
            "now_ms": 80, "candidate_px": 30.0, "travel_px": 70.0
        })),
        Case::named(
            "slow",
            json!({"release_px_s": 50.0, "candidate_px": 20.0, "travel_px": 80.0}),
        ),
        Case::named(
            "held",
            json!({"candidate_px": 3.0, "travel_px": 4.0, "started_on_candidate": true}),
        ),
        Case::named(
            "no_candidate",
            json!({"release_px_s": 1200.0, "peak_px_s": 1200.0}),
        ),
    ]
}
