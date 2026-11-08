//! `sim.*`, `setup.*`, `audio.*`, `scene.*`: what the call screens and the setup page show.

use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::audio::{Decision, Diff, EndpointList, RowKind};
use rovsti_core::call;
use rovsti_core::scene::{CallScene, Control, DisabledReason, HoldSlot, Layout, SceneDiff};
use rovsti_core::setup::{
    self, ItemStatus, OpenedThisSession, SetupFacts, SetupItem, XiaomiConfirmations,
};
use rovsti_core::sim::{self, SimInfo};
use rovsti_core::wire::{CallSnapshot, Endpoint, PhoneAccounts, WireCallState};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!("sim.build", AccountsReq, SimAns, sim_build, sim_cases),
    op!("sim.dot", DotReq, DotAns, sim_dot, dot_cases),
    op!("setup.steps", Value, StepsAns, steps, steps_cases),
    op!(
        "setup.checklist",
        ChecklistReq,
        ChecklistAns,
        checklist,
        checklist_cases
    ),
    op!("setup.xiaomi", XiaomiReq, XiaomiAns, xiaomi, xiaomi_cases),
    op!(
        "setup.reopened",
        ReopenedReq,
        ReopenedAns,
        reopened,
        reopened_cases
    ),
    op!("audio.rows", RowsReq, RowsAns, audio_rows, rows_cases),
    op!("audio.diff", DiffReq, DiffAns, audio_diff, diff_cases),
    op!("scene.of", SceneReq, SceneAns, scene_of, scene_cases),
    op!(
        "scene.diff",
        SceneDiffReq,
        SceneDiffAns,
        scene_diff,
        scene_diff_cases
    ),
    op!("scene.texts", TextsReq, TextsAns, texts, texts_cases),
];

// ---- sim.* ----

#[derive(Deserialize)]
struct AccountsReq {
    accounts: PhoneAccounts,
}

#[derive(Serialize)]
struct SimAccountOut {
    key: String,
    label: String,
    chip: String,
    title: String,
    color_argb: i32,
    slot: Option<u8>,
    enabled: bool,
}

#[derive(Serialize)]
struct SimAns {
    /// The SIM chip and picker only show with two or more SIMs.
    visible: bool,
    ask: bool,
    default_key: Option<String>,
    default_label: String,
    accounts: Vec<SimAccountOut>,
}

fn sim_build(req: AccountsReq) -> Result<SimAns, FfiError> {
    let info = SimInfo::from_payload(&req.accounts);
    Ok(SimAns {
        visible: info.visible(),
        ask: info.ask(),
        default_key: info.default_key().map(str::to_owned),
        default_label: info.default_label().to_owned(),
        accounts: info
            .accounts()
            .iter()
            .map(|a| SimAccountOut {
                key: a.key.to_string(),
                label: a.label.to_string(),
                chip: a.chip.to_string(),
                title: a.title.to_string(),
                color_argb: a.color_argb,
                slot: a.slot,
                enabled: a.enabled,
            })
            .collect(),
    })
}

fn sim_cases() -> Vec<Case> {
    let two = json!({"accounts": [
        {"key": "tel#1", "label": "Turkcell", "slot_index": 0, "color_argb": -16776961, "sim": true},
        {"key": "tel#2", "label": "Vodafone Türkiye Mobil", "slot_index": 1, "color_argb": -65536, "sim": true},
        {"key": "voip#1", "label": "Meet"}
    ], "ask": true});
    vec![
        Case::new(json!({"accounts": two})),
        Case::named(
            "single",
            json!({"accounts": {"accounts": [
            {"key": "tel#1", "label": "Turkcell", "slot_index": 0, "sim": true}], "default_key": "tel#1"}}),
        ),
        Case::named(
            "clash",
            json!({"accounts": {"accounts": [
            {"key": "a", "label": "Operator", "sim": true}, {"key": "b", "label": "Operator", "sim": true}],
            "default_key": "b"}}),
        ),
    ]
}

#[derive(Deserialize)]
struct DotReq {
    color_argb: i32,
    fill_argb: i32,
    #[serde(default = "default_contrast")]
    min_contrast: f32,
}

fn default_contrast() -> f32 {
    sim::DOT_MIN_CONTRAST
}

#[derive(Serialize)]
struct DotAns {
    /// Linear 0..1 channels of the dot colour, moved until it reads against the fill.
    rgb: [f32; 3],
    argb: i32,
}

fn sim_dot(req: DotReq) -> Result<DotAns, FfiError> {
    let rgb = sim::clamp_dot(
        sim::argb_rgb(req.color_argb),
        sim::argb_rgb(req.fill_argb),
        req.min_contrast,
    );
    let byte = |c: f32| (c.clamp(0.0, 1.0) * 255.0).round() as i32;
    Ok(DotAns {
        argb: (0xFF << 24) | (byte(rgb[0]) << 16) | (byte(rgb[1]) << 8) | byte(rgb[2]),
        rgb,
    })
}

fn dot_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"color_argb": -1, "fill_argb": -1})),
        Case::named(
            "readable",
            json!({"color_argb": -16776961, "fill_argb": -1}),
        ),
    ]
}

// ---- setup.* ----

#[derive(Serialize)]
struct StepOut {
    item: SetupItem,
    key: &'static str,
    title: &'static str,
    reason: &'static str,
    xiaomi: bool,
}

#[derive(Serialize)]
struct StepsAns {
    items: Vec<StepOut>,
}

fn steps(_: Value) -> Result<StepsAns, FfiError> {
    Ok(StepsAns {
        items: SetupItem::ALL
            .into_iter()
            .map(|item| StepOut {
                item,
                key: item.key(),
                title: item.title(),
                reason: item.reason(),
                xiaomi: item.is_xiaomi(),
            })
            .collect(),
    })
}

fn steps_cases() -> Vec<Case> {
    vec![Case::new(Value::Null)]
}

#[derive(Clone, Deserialize)]
struct ChecklistReq {
    facts: SetupFacts,
    #[serde(default)]
    xiaomi: bool,
    #[serde(default)]
    confirmations: XiaomiConfirmations,
    #[serde(default)]
    app_version: String,
}

impl ChecklistReq {
    fn derive(&self) -> setup::SetupChecklist {
        setup::derive(
            self.facts,
            self.xiaomi,
            &self.confirmations,
            &self.app_version,
        )
    }
}

#[derive(Serialize)]
struct ProgressOut {
    done: usize,
    total: usize,
    complete: bool,
    label: String,
}

#[derive(Serialize)]
struct ChecklistAns {
    items: Vec<ItemStatus>,
    progress: ProgressOut,
}

fn checklist(req: ChecklistReq) -> Result<ChecklistAns, FfiError> {
    let list = req.derive();
    let p = list.progress();
    Ok(ChecklistAns {
        items: list.items().to_vec(),
        progress: ProgressOut {
            done: p.done,
            total: p.total,
            complete: p.complete,
            label: p.label().as_str().to_owned(),
        },
    })
}

fn sample_facts() -> Value {
    json!({"role_held": true, "phone_and_call_log_granted": true, "contacts_granted": false,
        "notifications_granted": true, "full_screen_allowed": true, "battery_exempt": false,
        "autostart": "unknown", "lock_screen": "denied", "background_popups": "granted"})
}

fn checklist_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"facts": sample_facts()})),
        Case::named(
            "xiaomi",
            json!({"facts": sample_facts(), "xiaomi": true, "app_version": "1.0.0",
                "confirmations": {"app_version": "1.0.0", "confirmed": 32}}),
        ),
    ]
}

#[derive(Clone, Copy, Deserialize)]
#[serde(rename_all = "snake_case")]
enum XiaomiAction {
    Confirm,
    Unconfirm,
    Reconcile,
    Query,
}

#[derive(Deserialize)]
struct XiaomiReq {
    action: XiaomiAction,
    #[serde(default)]
    confirmations: XiaomiConfirmations,
    #[serde(default)]
    item: Option<SetupItem>,
    #[serde(default)]
    app_version: String,
    /// Items whose settings screen was opened in this session.
    #[serde(default)]
    opened: Vec<SetupItem>,
}

#[derive(Serialize)]
struct XiaomiAns {
    confirmations: XiaomiConfirmations,
    /// `reconcile`: a stored confirmation was dropped. `confirm`/`unconfirm`: the call was allowed.
    changed: bool,
    /// `query`: the item is confirmed for this version.
    confirmed: bool,
    /// The item's screen was opened this session, so "Done" may be tapped.
    can_confirm: bool,
}

fn xiaomi(mut req: XiaomiReq) -> Result<XiaomiAns, FfiError> {
    let mut opened = OpenedThisSession::default();
    for item in &req.opened {
        opened.mark(*item);
    }
    let can_confirm = req.item.is_some_and(|i| setup::can_confirm(i, opened));
    let (mut changed, mut confirmed) = (false, false);
    match (req.action, req.item) {
        (XiaomiAction::Reconcile, _) => changed = req.confirmations.reconcile(&req.app_version),
        (XiaomiAction::Confirm, Some(item)) if can_confirm => {
            req.confirmations.confirm(item, &req.app_version);
            changed = true;
        }
        (XiaomiAction::Confirm, Some(_)) => {}
        (XiaomiAction::Unconfirm, Some(item)) => {
            req.confirmations.unconfirm(item);
            changed = true;
        }
        (XiaomiAction::Query, Some(item)) => {
            confirmed = req.confirmations.is_confirmed(item, &req.app_version);
        }
        (_, None) => return Err(FfiError::invalid("this action needs an item")),
    }
    Ok(XiaomiAns {
        confirmations: req.confirmations,
        changed,
        confirmed,
        can_confirm,
    })
}

fn xiaomi_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"action": "confirm", "item": "autostart", "app_version": "1.0.0", "opened": ["autostart"]}),
        ),
        Case::named(
            "not_opened",
            json!({"action": "confirm", "item": "autostart", "app_version": "1.0.0"}),
        ),
        Case::named(
            "reconcile",
            json!({"action": "reconcile", "app_version": "2.0.0",
                "confirmations": {"app_version": "1.0.0", "confirmed": 32}}),
        ),
        Case::named(
            "query",
            json!({"action": "query", "item": "autostart", "app_version": "1.0.0",
                "confirmations": {"app_version": "1.0.0", "confirmed": 32}}),
        ),
        Case::named("missing_item", json!({"action": "unconfirm"})),
    ]
}

#[derive(Deserialize)]
struct ReopenedReq {
    before: ChecklistReq,
    after: ChecklistReq,
}

#[derive(Serialize)]
struct ReopenedAns {
    /// Items that were done before and are not now.
    reopened: Vec<SetupItem>,
    /// The notification title for them; absent when nothing reopened.
    title: Option<String>,
    still_reopened: Vec<SetupItem>,
}

fn reopened(req: ReopenedReq) -> Result<ReopenedAns, FfiError> {
    let (before, after) = (req.before.derive(), req.after.derive());
    let reopened = after.reopened_since(&before);
    Ok(ReopenedAns {
        title: setup::changed_title(&reopened),
        still_reopened: setup::still_reopened(&reopened, &after),
        reopened,
    })
}

fn reopened_cases() -> Vec<Case> {
    let mut lost = sample_facts();
    lost["notifications_granted"] = json!(false);
    vec![Case::new(
        json!({"before": {"facts": sample_facts()}, "after": {"facts": lost}}),
    )]
}

// ---- audio.* ----

#[derive(Serialize)]
struct RowOut {
    id: String,
    kind: RowKind,
    label: String,
    subtitle: &'static str,
    selected: bool,
    unavailable: bool,
    ghost: bool,
    hearing_aid: bool,
    nameless: bool,
}

#[derive(Deserialize)]
struct RowsReq {
    endpoints: Vec<Endpoint>,
    /// The list the user saw before: vanished devices stay as ghosts.
    #[serde(default)]
    previous: Option<Vec<Endpoint>>,
}

#[derive(Serialize)]
struct RowsAns {
    rows: Vec<RowOut>,
    decision: Decision,
    any_nameless: bool,
    diff: Diff,
}

fn audio_rows(req: RowsReq) -> Result<RowsAns, FfiError> {
    let baseline = req
        .previous
        .as_deref()
        .map(|p| EndpointList::from_snapshot(p, None));
    let list = EndpointList::from_snapshot(&req.endpoints, baseline.as_ref());
    Ok(RowsAns {
        rows: list
            .iter()
            .map(|r| RowOut {
                id: r.id.clone(),
                kind: r.kind,
                label: r.label.clone(),
                subtitle: r.subtitle(),
                selected: r.selected,
                unavailable: r.unavailable,
                ghost: r.ghost,
                hearing_aid: r.hearing_aid,
                nameless: r.nameless,
            })
            .collect(),
        decision: list.decision(),
        any_nameless: list.any_nameless(),
        diff: EndpointList::diff(baseline.as_ref(), &list),
    })
}

fn endpoints() -> Value {
    json!([
        {"id": "e1", "type": "earpiece", "selected": true},
        {"id": "e2", "type": "speaker"},
        {"id": "e3", "type": "bluetooth", "name": "Pixel Buds"}
    ])
}

fn rows_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"endpoints": endpoints()})),
        Case::named(
            "ghost",
            json!({"endpoints": [{"id": "e1", "type": "earpiece", "selected": true}], "previous": endpoints()}),
        ),
        Case::named("empty", json!({"endpoints": []})),
    ]
}

#[derive(Deserialize)]
struct DiffReq {
    previous: Option<Vec<Endpoint>>,
    next: Vec<Endpoint>,
}

#[derive(Serialize)]
struct DiffAns {
    diff: Diff,
    empty: bool,
}

fn audio_diff(req: DiffReq) -> Result<DiffAns, FfiError> {
    let prev = req
        .previous
        .as_deref()
        .map(|p| EndpointList::from_snapshot(p, None));
    let next = EndpointList::from_snapshot(&req.next, prev.as_ref());
    let diff = EndpointList::diff(prev.as_ref(), &next);
    Ok(DiffAns {
        empty: diff.is_empty(),
        diff,
    })
}

fn diff_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"previous": [{"id": "e1", "type": "earpiece", "selected": true}], "next": endpoints()}),
        ),
        Case::named(
            "same",
            json!({"previous": endpoints(), "next": endpoints()}),
        ),
    ]
}

// ---- scene.* ----

#[derive(Deserialize)]
struct SceneReq {
    snapshot: CallSnapshot,
}

#[derive(Serialize)]
struct CallOut {
    id: i32,
    state: WireCallState,
    /// Name, formatted number or "Unknown".
    label: String,
    /// "Calling...", the timer, "On hold", "Call ended".
    headline: String,
    /// Second line of a failed call.
    reason: Option<&'static str>,
    /// A ring-out the owner should call back.
    missed: bool,
}

#[derive(Serialize)]
struct ParticipantOut {
    id: i32,
    index: usize,
    label: String,
    can_split: bool,
    can_drop: bool,
}

#[derive(Serialize)]
struct DisabledOut {
    control: Control,
    reason: DisabledReason,
    text: &'static str,
}

#[derive(Serialize)]
struct SceneAns {
    /// `empty`, `single`, `two` or `conference`.
    layout: &'static str,
    primary: Option<CallOut>,
    held: Option<CallOut>,
    waiting: Option<CallOut>,
    ended: Option<CallOut>,
    hold_slot: HoldSlot,
    participants: Vec<ParticipantOut>,
    /// Conference children the carrier named but the snapshot lacks.
    skipped: usize,
    /// "n people" when children exist but none can be drawn.
    participants_summary: Option<String>,
    /// Only the controls that are dimmed, with why.
    disabled: Vec<DisabledOut>,
}

fn call_out(call: &rovsti_core::wire::WireCall, now_ms: i64) -> CallOut {
    let state = call.call_state();
    let status = call::status_text(state, now_ms, call.connect_time_ms);
    CallOut {
        id: call.id,
        state: call.state,
        label: call::caller_label(call.display_name.as_deref(), &call.number_raw, false)
            .into_owned(),
        headline: status.headline().to_owned(),
        reason: status.reason(),
        missed: match state {
            call::CallState::Disconnected { cause } => call::is_missed(cause),
            _ => false,
        },
    }
}

fn scene_of(req: SceneReq) -> Result<SceneAns, FfiError> {
    let now = req.snapshot.now_ms;
    let scene = CallScene::of(&req.snapshot);
    let out = |c: Option<&rovsti_core::wire::WireCall>| c.map(|c| call_out(c, now));
    let (layout, participants, skipped, summary) = match scene.layout {
        Layout::Empty => ("empty", vec![], 0, None),
        Layout::Single { .. } => ("single", vec![], 0, None),
        Layout::Two { .. } => ("two", vec![], 0, None),
        Layout::Conference { participants, .. } => (
            "conference",
            participants
                .iter()
                .map(|p| ParticipantOut {
                    id: p.call.id,
                    index: p.index,
                    label: p.label().into_owned(),
                    can_split: p.can_split(),
                    can_drop: p.can_drop(),
                })
                .collect(),
            participants.skipped(),
            participants.summary().map(|s| s.as_str().to_owned()),
        ),
    };
    let disabled = [
        Control::Mute,
        Control::Keypad,
        Control::Audio,
        Control::AddCall,
        Control::Merge,
        Control::HoldSwap,
        Control::HoldAndAnswer,
    ]
    .into_iter()
    .filter_map(|control| {
        scene.reason(control).map(|reason| DisabledOut {
            control,
            reason,
            text: reason.text(),
        })
    })
    .collect();
    Ok(SceneAns {
        layout,
        primary: out(scene.primary()),
        held: out(scene.held()),
        waiting: out(scene.waiting),
        ended: out(scene.ended),
        hold_slot: scene.hold_slot(),
        participants,
        skipped,
        participants_summary: summary,
        disabled,
    })
}

fn snapshot_two() -> Value {
    json!({"v": 1, "seq": 4, "now_ms": 1_790_000_065_000_i64, "keyguard_locked": false,
    "foreground_call_id": 1, "background_call_id": 2, "can_add_call": true,
    "calls": [
        {"id": 1, "state": "active", "number_raw": "0532 123 45 67", "display_name": "Ayşe Yılmaz",
         "connect_time_ms": 1_790_000_000_000_i64, "can_hold": true},
        {"id": 2, "state": "holding", "number_raw": "+90 212 555 01 02", "can_hold": true}
    ]})
}

fn scene_cases() -> Vec<Case> {
    let incoming = json!({"v": 1, "seq": 1, "now_ms": 1_790_000_000_000_i64, "waiting_call_id": 3,
    "foreground_call_id": 1,
    "calls": [
        {"id": 1, "state": "active", "number_raw": "0532 123 45 67", "connect_time_ms": 1_789_999_990_000_i64},
        {"id": 3, "state": "ringing", "is_incoming": true}
    ]});
    let conference = json!({"v": 1, "seq": 2, "now_ms": 1_790_000_000_000_i64, "foreground_call_id": 10,
    "calls": [
        {"id": 10, "state": "active", "children": [11, 12, 13], "connect_time_ms": 1_789_999_000_000_i64},
        {"id": 11, "state": "active", "number_raw": "0532 123 45 67", "display_name": "Ayşe Yılmaz"},
        {"id": 12, "state": "active", "number_raw": "+90 212 555 01 02"}
    ]});
    vec![
        Case::new(json!({"snapshot": snapshot_two()})),
        Case::named("incoming_waiting", json!({"snapshot": incoming})),
        Case::named("conference", json!({"snapshot": conference})),
        Case::named("empty", json!({"snapshot": {"v": 1}})),
    ]
}

#[derive(Deserialize)]
struct SceneDiffReq {
    old: CallSnapshot,
    new: CallSnapshot,
    #[serde(default)]
    endpoints: bool,
}

#[derive(Serialize)]
struct SceneDiffAns {
    diff: SceneDiff,
    changed: bool,
}

fn scene_diff(req: SceneDiffReq) -> Result<SceneDiffAns, FfiError> {
    let diff = SceneDiff::between(&req.old, &req.new, req.endpoints);
    Ok(SceneDiffAns {
        changed: diff.changed(),
        diff,
    })
}

fn scene_diff_cases() -> Vec<Case> {
    let mut newer = snapshot_two();
    newer["seq"] = json!(5);
    newer["audio"] = json!({"muted": true});
    vec![
        Case::new(json!({"old": snapshot_two(), "new": newer})),
        Case::named(
            "same",
            json!({"old": snapshot_two(), "new": snapshot_two()}),
        ),
    ]
}

#[derive(Deserialize)]
struct TextsReq {
    caller: String,
    #[serde(default)]
    talk_s: u64,
    #[serde(default)]
    now_ms: i64,
    #[serde(default)]
    connect_time_ms: i64,
}

#[derive(Serialize)]
struct TextsAns {
    missed_headline: String,
    incoming_announcement: String,
    after_call: String,
    /// The running timer for a call connected at `connect_time_ms`; empty before it connects.
    elapsed: String,
}

fn texts(req: TextsReq) -> Result<TextsAns, FfiError> {
    Ok(TextsAns {
        missed_headline: call::missed_headline(&req.caller),
        incoming_announcement: call::incoming_announcement(&req.caller),
        after_call: call::after_call_text(&req.caller, req.talk_s),
        elapsed: call::format_elapsed(req.now_ms, req.connect_time_ms)
            .as_str()
            .to_owned(),
    })
}

fn texts_cases() -> Vec<Case> {
    vec![Case::new(
        json!({"caller": "Ayşe Yılmaz", "talk_s": 125, "now_ms": 1_790_000_125_000_i64, "connect_time_ms": 1_790_000_000_000_i64}),
    )]
}
