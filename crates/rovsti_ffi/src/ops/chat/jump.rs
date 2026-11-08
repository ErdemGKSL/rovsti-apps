//! `date_jump.*`, `unread_jump.*` and `voice_player.*`: the jump-to-date sheet, the "latest" button and unread divider,
//! and the voice-note player.

use super::{NOW, TZ};
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::date_jump::{self, DateStep, DayPick};
use rovsti_core::unread_jump::{self, JumpState};
use rovsti_core::voice_player::{self, PlayerState, Speed, TogglePlan};
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[
    op!("date_jump.pick", PickReq, PickAns, pick, pick_cases),
    op!("date_jump.step", StepReq, StepAns, step, step_cases),
    op!("unread_jump.state", StateReq, StateAns, state, state_cases),
    op!(
        "unread_jump.divider",
        DividerReq,
        DividerAns,
        divider,
        divider_cases
    ),
    op!("unread_jump.next", NextReq, NextAns, next, next_cases),
    op!(
        "voice_player.speeds",
        SpeedsReq,
        SpeedsAns,
        speeds,
        speeds_cases
    ),
    op!(
        "voice_player.toggle",
        ToggleReq,
        ToggleAns,
        toggle,
        toggle_cases
    ),
    op!("voice_player.view", ViewReq, ViewAns, view, view_cases),
];

// ---- date_jump ----

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum PickAction {
    /// Just describe the pick (today when `start_ms` is absent).
    Show,
    StepDay,
    StepMonth,
}

#[derive(Deserialize)]
struct PickReq {
    /// Local midnight of the picked day; today when absent.
    #[serde(default)]
    start_ms: Option<i64>,
    action: PickAction,
    #[serde(default)]
    delta: i32,
    now_ms: i64,
    tz_offset_s: i32,
}

#[derive(Serialize)]
struct QuickPick {
    label: &'static str,
    start_ms: i64,
    pick_label: String,
}

#[derive(Serialize)]
struct PickAns {
    /// Local midnight of the day, in epoch milliseconds.
    start_ms: i64,
    /// "Today", "Yesterday", "Mon 9 Nov" or "9 Nov 2025".
    label: String,
    is_today: bool,
    /// Yesterday, 1 week ago, 1 month ago.
    quick_picks: Vec<QuickPick>,
}

fn pick(req: PickReq) -> Result<PickAns, FfiError> {
    let (now, tz) = (req.now_ms, req.tz_offset_s);
    let base = req
        .start_ms
        .map_or_else(|| DayPick::today(now, tz), |s| DayPick::today(s, tz));
    let day = match req.action {
        PickAction::Show => base,
        PickAction::StepDay => base.step_day(req.delta, now, tz),
        PickAction::StepMonth => base.step_month(req.delta, now, tz),
    };
    Ok(PickAns {
        start_ms: day.start_ms(tz),
        label: day.label(now, tz),
        is_today: day.is_today(now, tz),
        quick_picks: date_jump::quick_picks(now, tz)
            .into_iter()
            .map(|(label, p)| QuickPick {
                label,
                start_ms: p.start_ms(tz),
                pick_label: p.label(now, tz),
            })
            .collect(),
    })
}

fn pick_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"action": "show", "now_ms": NOW, "tz_offset_s": TZ})),
        Case::named(
            "step_day",
            json!({"action": "step_day", "delta": -1, "start_ms": NOW - 40_000_000, "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "never_past_today",
            json!({"action": "step_day", "delta": 5, "now_ms": NOW, "tz_offset_s": TZ}),
        ),
        Case::named(
            "step_month",
            json!({"action": "step_month", "delta": -7, "now_ms": NOW, "tz_offset_s": TZ}),
        ),
    ]
}

#[derive(Deserialize)]
struct StepReq {
    /// Loaded timeline, oldest first.
    at_ms: Vec<i64>,
    has_older: bool,
    pages_loaded: usize,
    start_ms: i64,
    /// The picked day's label, for the pills.
    label: String,
    tz_offset_s: i32,
}

#[derive(Serialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum StepKind {
    Reveal,
    LoadOlder,
    FurtherBack,
    Newest,
    Empty,
}

#[derive(Serialize)]
struct StepAns {
    kind: StepKind,
    /// The index to scroll to and glow, for `reveal`.
    index: Option<usize>,
    /// The pill, when the step needs one.
    notice: Option<String>,
    /// For `reveal`: the pill when the message is on a later day than the chosen one.
    later_notice: Option<String>,
    max_pages: usize,
}

fn step(req: StepReq) -> Result<StepAns, FfiError> {
    let decided = date_jump::date_step(&req.at_ms, req.has_older, req.pages_loaded, req.start_ms);
    let (kind, index) = match decided {
        DateStep::Reveal(i) => (StepKind::Reveal, Some(i)),
        DateStep::LoadOlder => (StepKind::LoadOlder, None),
        DateStep::FurtherBack => (StepKind::FurtherBack, None),
        DateStep::Newest => (StepKind::Newest, None),
        DateStep::Empty => (StepKind::Empty, None),
    };
    let later_notice = index
        .and_then(|i| req.at_ms.get(i))
        .filter(|&&at| date_jump::starts_later(at, req.start_ms, req.tz_offset_s))
        .map(|_| date_jump::later_notice(&req.label));
    Ok(StepAns {
        kind,
        index,
        notice: date_jump::date_notice(decided, &req.label),
        later_notice,
        max_pages: date_jump::MAX_DATE_PAGES,
    })
}

fn step_cases() -> Vec<Case> {
    let day = 86_400_000;
    let base = |at: serde_json::Value, older: bool, pages: u64, start: i64| {
        json!({"at_ms": at, "has_older": older, "pages_loaded": pages, "start_ms": start,
               "label": "Mon 14 Sep", "tz_offset_s": TZ})
    };
    let start = NOW - 7 * day;
    vec![
        Case::new(base(
            json!([NOW - 9 * day, NOW - 7 * day + 1000, NOW]),
            false,
            1,
            start,
        )),
        Case::named(
            "reveals_a_later_day",
            base(json!([NOW - 9 * day, NOW - 5 * day, NOW]), false, 1, start),
        ),
        Case::named(
            "load_older",
            base(json!([NOW - 3 * day, NOW]), true, 2, start),
        ),
        Case::named(
            "further_back",
            base(json!([NOW - 3 * day, NOW]), true, 20, start),
        ),
        Case::named("newest", base(json!([NOW - 9 * day]), false, 1, NOW)),
        Case::named("empty", base(json!([]), false, 0, start)),
    ]
}

// ---- unread_jump ----

#[derive(Deserialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum StateEvent {
    /// The list scrolled; `at_end` says whether the newest message is in view.
    Scrolled,
    /// `incoming` messages arrived.
    Arrived,
}

#[derive(Deserialize)]
struct StateReq {
    #[serde(default)]
    away: bool,
    #[serde(default)]
    unseen: u32,
    event: StateEvent,
    #[serde(default)]
    at_end: bool,
    #[serde(default)]
    incoming: u32,
}

#[derive(Serialize)]
struct StateAns {
    away: bool,
    unseen: u32,
    /// Whether the jump button shows.
    visible: bool,
    /// "3", "99+" or `null`.
    badge: Option<String>,
}

fn state(req: StateReq) -> Result<StateAns, FfiError> {
    // `JumpState` keeps its counters private: rebuild it, apply the event, and track `unseen` alongside.
    let mut s = JumpState::default();
    let mut unseen = 0;
    if req.away {
        s.scrolled(false);
        s.arrived(req.unseen);
        unseen = req.unseen;
    }
    match req.event {
        StateEvent::Scrolled => {
            s.scrolled(req.at_end);
            if req.at_end {
                unseen = 0;
            }
        }
        StateEvent::Arrived => {
            s.arrived(req.incoming);
            if req.away {
                unseen = unseen.saturating_add(req.incoming);
            }
        }
    }
    Ok(StateAns {
        away: s.visible(),
        unseen,
        visible: s.visible(),
        badge: s.badge(),
    })
}

fn state_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"event": "scrolled"})),
        Case::named(
            "arrived_while_away",
            json!({"away": true, "unseen": 2, "event": "arrived", "incoming": 3}),
        ),
        Case::named(
            "arrived_at_the_end",
            json!({"event": "arrived", "incoming": 3}),
        ),
        Case::named(
            "back_at_the_end",
            json!({"away": true, "unseen": 4, "event": "scrolled", "at_end": true}),
        ),
        Case::named(
            "capped",
            json!({"away": true, "unseen": 98, "event": "arrived", "incoming": 5}),
        ),
    ]
}

#[derive(Deserialize)]
struct DividerReq {
    /// Per item, oldest first: is it an incoming message.
    incoming: Vec<bool>,
    unread: u32,
    /// Items shown before and after a reload, for the count of new arrivals.
    #[serde(default)]
    before: Option<usize>,
    #[serde(default)]
    after: Option<usize>,
}

#[derive(Serialize)]
struct DividerAns {
    /// The item index where the unread run begins.
    index: Option<usize>,
    /// "1 unread message", "5 unread messages".
    label: String,
    /// How many items a reload added (for the "latest" badge).
    arrived: u32,
}

fn divider(req: DividerReq) -> Result<DividerAns, FfiError> {
    Ok(DividerAns {
        index: unread_jump::unread_divider(&req.incoming, req.unread),
        label: unread_jump::divider_label(req.unread),
        arrived: unread_jump::newly_arrived(req.before.unwrap_or(0), req.after.unwrap_or(0)),
    })
}

fn divider_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"incoming": [true, false, true, true, false, true], "unread": 2,
                         "before": 6, "after": 9}),
        ),
        Case::named(
            "fewer_loaded",
            json!({"incoming": [false, true], "unread": 5}),
        ),
        Case::named("nothing", json!({"incoming": [true], "unread": 0})),
    ]
}

#[derive(Deserialize)]
struct ChatUnread {
    key: String,
    unread: bool,
}

#[derive(Deserialize)]
struct NextReq {
    order: Vec<ChatUnread>,
    #[serde(default)]
    current: Option<String>,
}

#[derive(Serialize)]
struct NextAns {
    /// Index in `order` of the next unread chat after `current`, wrapping.
    index: Option<usize>,
    /// Other chats that are unread.
    others: usize,
    /// "Next unread", "Next unread · 3" or `null`.
    label: Option<String>,
}

fn next(req: NextReq) -> Result<NextAns, FfiError> {
    let order: Vec<(String, bool)> = req.order.into_iter().map(|c| (c.key, c.unread)).collect();
    let others = order
        .iter()
        .filter(|(k, unread)| *unread && Some(k) != req.current.as_ref())
        .count();
    Ok(NextAns {
        index: unread_jump::next_unread(&order, req.current.as_ref()),
        others,
        label: unread_jump::next_unread_label(others),
    })
}

fn next_cases() -> Vec<Case> {
    let order = json!([{"key": "sms:1", "unread": false}, {"key": "sms:2", "unread": true},
                       {"key": "wa:a", "unread": true}, {"key": "wa:b", "unread": true}]);
    vec![
        Case::new(json!({"order": order, "current": "wa:b"})),
        Case::named(
            "none",
            json!({"order": [{"key": "sms:1", "unread": true}], "current": "sms:1"}),
        ),
        Case::named("no_current", json!({"order": order})),
    ]
}

// ---- voice_player ----

#[derive(Deserialize)]
struct SpeedsReq {
    #[serde(default)]
    current: Option<Speed>,
}

#[derive(Serialize)]
struct SpeedOut {
    speed: Speed,
    factor: f32,
    label: &'static str,
}

#[derive(Serialize)]
struct SpeedsAns {
    /// The cycle, in order.
    speeds: Vec<SpeedOut>,
    /// The speed after `current` (1× when absent).
    next: SpeedOut,
}

fn out(speed: Speed) -> SpeedOut {
    SpeedOut {
        speed,
        factor: speed.factor(),
        label: speed.label(),
    }
}

fn speeds(req: SpeedsReq) -> Result<SpeedsAns, FfiError> {
    let first = Speed::default();
    Ok(SpeedsAns {
        speeds: vec![out(first), out(first.next()), out(first.next().next())],
        next: out(req.current.unwrap_or(first).next()),
    })
}

fn speeds_cases() -> Vec<Case> {
    vec![
        Case::new(json!({})),
        Case::named("from_1_5", json!({"current": "X1_5"})),
        Case::named("wraps", json!({"current": "X2"})),
    ]
}

#[derive(Deserialize)]
struct ToggleReq {
    /// The platform player's report; any field may be missing.
    #[serde(default)]
    state: PlayerState,
    /// The note whose play button was tapped.
    key: String,
}

#[derive(Serialize, Clone, Copy)]
#[serde(rename_all = "snake_case")]
enum ToggleKind {
    Pause,
    Resume,
    Start,
}

#[derive(Serialize)]
struct ToggleAns {
    plan: ToggleKind,
    /// For `resume`: where to continue.
    from_ms: Option<u64>,
}

fn toggle(req: ToggleReq) -> Result<ToggleAns, FfiError> {
    Ok(match voice_player::toggle_plan(&req.state, &req.key) {
        TogglePlan::Pause => ToggleAns {
            plan: ToggleKind::Pause,
            from_ms: None,
        },
        TogglePlan::Resume { from_ms } => ToggleAns {
            plan: ToggleKind::Resume,
            from_ms: Some(from_ms),
        },
        TogglePlan::Start => ToggleAns {
            plan: ToggleKind::Start,
            from_ms: None,
        },
    })
}

fn toggle_cases() -> Vec<Case> {
    let state = |playing: bool, pos: u64, ended: bool| json!({"key": "n1", "playing": playing, "position_ms": pos, "duration_ms": 42_000, "ended": ended, "error": null});
    vec![
        Case::new(json!({"state": state(true, 5000, false), "key": "n1"})),
        Case::named(
            "resume",
            json!({"state": state(false, 12_000, false), "key": "n1"}),
        ),
        Case::named(
            "other_note",
            json!({"state": state(true, 5000, false), "key": "n2"}),
        ),
        Case::named(
            "ended",
            json!({"state": state(false, 42_000, true), "key": "n1"}),
        ),
        Case::named("idle", json!({"key": "n1"})),
    ]
}

#[derive(Deserialize)]
struct ViewReq {
    position_ms: u64,
    duration_ms: u64,
    #[serde(default)]
    playing: bool,
    /// A scrub position (0..1) to turn into a seek target.
    #[serde(default)]
    scrub: Option<f32>,
}

#[derive(Serialize)]
struct ViewAns {
    /// Fraction played, 0..1.
    progress: f32,
    /// "0:12 / 0:42" while playing or paused part-way, else "0:42".
    time_label: String,
    seek_ms: Option<u64>,
}

fn view(req: ViewReq) -> Result<ViewAns, FfiError> {
    Ok(ViewAns {
        progress: voice_player::progress(req.position_ms, req.duration_ms),
        time_label: voice_player::time_label(req.position_ms, req.duration_ms, req.playing),
        seek_ms: req
            .scrub
            .map(|f| voice_player::seek_target(f, req.duration_ms)),
    })
}

fn view_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"position_ms": 12_000, "duration_ms": 42_000, "playing": true, "scrub": 0.5}),
        ),
        Case::named("idle", json!({"position_ms": 0, "duration_ms": 3_725_000})),
        Case::named(
            "unknown_duration",
            json!({"position_ms": 500, "duration_ms": 0, "scrub": 2.0}),
        ),
    ]
}
