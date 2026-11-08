//! The call scene: which calls the In-Call Screen lays out, and why a control
//! is dimmed (FR-44 to FR-48, AD-24, AD-35).
//!
//! Kotlin picks the roles (foreground, background, waiting, ended) in `Focus`;
//! this module only *validates* those ids against `calls` and turns them into a
//! layout. It never re-selects. Building a [`CallScene`] allocates nothing, so
//! a view may do it per render; participant rows are an iterator over the
//! snapshot, not a copied list.
//!
//! [`SceneDiff`] says which parts of the scene a snapshot changed, so a view
//! re-renders only what moved. A pure timer tick (new `seq` and `now_ms`, same
//! content) changes nothing in it.

use crate::call::{CallModel, caller_label};
use crate::small_text::SmallText;
use crate::wire::{CallSnapshot, PendingOp, WireCall, WireCallState, caps, props};
use std::borrow::Cow;

fn is_conference(call: &WireCall) -> bool {
    call.has_prop(props::CONFERENCE) || !call.children.is_empty()
}

fn find(calls: &[WireCall], id: Option<i32>) -> Option<&WireCall> {
    let id = id?;
    calls.iter().find(|c| c.id == id)
}

/// The roles Kotlin named, resolved against `calls`.
struct Roles<'a> {
    foreground: Option<&'a WireCall>,
    background: Option<&'a WireCall>,
    waiting: Option<&'a WireCall>,
    ended: Option<&'a WireCall>,
}

impl<'a> Roles<'a> {
    fn of(s: &'a CallSnapshot) -> Self {
        let foreground = find(&s.calls, s.foreground_call_id);
        let fg_id = foreground.map(|c| c.id);
        let background = find(&s.calls, s.background_call_id).filter(|c| Some(c.id) != fg_id);
        let waiting = find(&s.calls, s.waiting_call_id)
            .filter(|c| Some(c.id) != fg_id && Some(c.id) != background.map(|b| b.id));
        let ended = find(&s.calls, s.ended_call_id).filter(|c| Some(c.id) != fg_id);
        Self {
            foreground,
            background,
            waiting,
            ended,
        }
    }
}

/// One row of a conference.
#[derive(Clone, Copy, Debug)]
pub struct Participant<'a> {
    pub call: &'a WireCall,
    /// 1-based position among the rows shown.
    pub index: usize,
}

impl Participant<'_> {
    /// The name, else the formatted number, else "Participant n".
    pub fn label(&self) -> Cow<'_, str> {
        let named = self
            .call
            .display_name
            .as_deref()
            .is_some_and(|n| !n.trim().is_empty());
        if named || !self.call.number_raw.trim().is_empty() {
            caller_label(
                self.call.display_name.as_deref(),
                &self.call.number_raw,
                false,
            )
        } else {
            Cow::Owned(format!("Participant {}", self.index))
        }
    }

    /// The child can leave the conference into a private call.
    pub fn can_split(&self) -> bool {
        self.call.has_cap(caps::SPLIT)
    }

    /// The child can be dropped from the conference.
    pub fn can_drop(&self) -> bool {
        self.call.has_cap(caps::DISCONNECT_PARTICIPANT)
    }
}

/// The participants of a conference host, read straight from the snapshot.
#[derive(Clone, Copy, Debug)]
pub struct Participants<'a> {
    host: &'a WireCall,
    calls: &'a [WireCall],
    skipped: usize,
}

impl<'a> Participants<'a> {
    fn new(host: &'a WireCall, calls: &'a [WireCall]) -> Self {
        let skipped = host
            .children
            .iter()
            .filter(|id| **id == host.id || !calls.iter().any(|c| c.id == **id))
            .count();
        Self {
            host,
            calls,
            skipped,
        }
    }

    /// Rows in the carrier's order; child ids missing from `calls` are skipped.
    pub fn iter(&self) -> impl Iterator<Item = Participant<'a>> + use<'a> {
        let host_id = self.host.id;
        let calls = self.calls;
        self.host
            .children
            .iter()
            .filter(move |id| **id != host_id)
            .filter_map(move |id| calls.iter().find(|c| c.id == *id))
            .enumerate()
            .map(|(i, call)| Participant { call, index: i + 1 })
    }

    /// Rows that can be drawn.
    pub fn len(&self) -> usize {
        self.host.children.len() - self.skipped
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Child ids that named no call in the snapshot.
    pub fn skipped(&self) -> usize {
        self.skipped
    }

    /// "n people" when the carrier reported children but none can be drawn.
    pub fn summary(&self) -> Option<SmallText<16>> {
        let known = self.host.children.len();
        if !self.is_empty() || known == 0 {
            return None;
        }
        let mut out = SmallText::new();
        out.push_padded(known as u64, 1);
        out.push_str(if known == 1 { " person" } else { " people" });
        Some(out)
    }
}

/// How the calls are laid out.
#[derive(Clone, Copy, Debug)]
pub enum Layout<'a> {
    /// No call to lay out (a lone waiting call is shown by `waiting`).
    Empty,
    Single {
        primary: &'a WireCall,
    },
    Two {
        primary: &'a WireCall,
        held: &'a WireCall,
    },
    Conference {
        host: &'a WireCall,
        participants: Participants<'a>,
        held: Option<&'a WireCall>,
    },
}

/// What the In-Call Screen shows now.
#[derive(Clone, Copy, Debug)]
pub struct CallScene<'a> {
    pub layout: Layout<'a>,
    /// The ringing call beside the foreground, independent of the layout.
    pub waiting: Option<&'a WireCall>,
    /// A call that just ended while another is live.
    pub ended: Option<&'a WireCall>,
    facts: Facts,
}

/// What the disabled-reason rules read.
#[derive(Clone, Copy, Debug, Default)]
struct Facts {
    can_add_call: bool,
    can_merge: bool,
    can_swap: bool,
    pending: Option<PendingOp>,
    emergency: bool,
    ringing: bool,
    /// Top-level calls that are not ringing and not over.
    live: usize,
}

fn is_live(call: &WireCall) -> bool {
    matches!(
        call.state,
        WireCallState::Dialing | WireCallState::Active | WireCallState::Holding
    )
}

impl<'a> CallScene<'a> {
    pub fn from(model: &'a CallModel) -> Self {
        Self::of(model.snapshot())
    }

    pub fn of(s: &'a CallSnapshot) -> Self {
        let roles = Roles::of(s);
        let layout = match (roles.foreground, roles.background) {
            (None, _) => Layout::Empty,
            (Some(host), held) if is_conference(host) => Layout::Conference {
                host,
                participants: Participants::new(host, &s.calls),
                held,
            },
            (Some(primary), Some(held)) => Layout::Two { primary, held },
            (Some(primary), None) => Layout::Single { primary },
        };
        let top = || s.calls.iter().filter(|c| c.parent_id.is_none());
        let facts = Facts {
            can_add_call: s.can_add_call,
            can_merge: s.can_merge,
            can_swap: s.can_swap,
            pending: s.pending,
            emergency: top()
                .any(|c| c.has_prop(props::EMERGENCY) && c.state != WireCallState::Disconnected),
            ringing: roles.waiting.is_some()
                || roles
                    .foreground
                    .is_some_and(|c| c.state == WireCallState::Ringing),
            live: top().filter(|c| is_live(c)).count(),
        };
        Self {
            layout,
            waiting: roles.waiting,
            ended: roles.ended,
            facts,
        }
    }

    /// The call the main area is about.
    pub fn primary(&self) -> Option<&'a WireCall> {
        match self.layout {
            Layout::Empty => None,
            Layout::Single { primary } | Layout::Two { primary, .. } => Some(primary),
            Layout::Conference { host, .. } => Some(host),
        }
    }

    /// The call on hold beside the primary.
    pub fn held(&self) -> Option<&'a WireCall> {
        match self.layout {
            Layout::Two { held, .. } => Some(held),
            Layout::Conference { held, .. } => held,
            _ => None,
        }
    }

    /// Hold and Swap share one slot: Swap when a held call exists.
    pub fn hold_slot(&self) -> HoldSlot {
        if self.held().is_some() {
            HoldSlot::Swap
        } else {
            HoldSlot::Hold
        }
    }

    /// Mute, Keypad and Audio act on the live call: dim while it is on hold.
    fn on_hold_reason(&self) -> Option<DisabledReason> {
        self.primary()
            .is_some_and(|p| p.state == WireCallState::Holding)
            .then_some(DisabledReason::OnHold)
    }

    /// Why `control` is dimmed, or `None` when it is available.
    /// Precedence: emergency, pending or ringing, count, carrier, capability.
    pub fn reason(&self, control: Control) -> Option<DisabledReason> {
        let f = &self.facts;
        match control {
            Control::Mute | Control::Keypad | Control::Audio => self.on_hold_reason(),
            Control::AddCall => {
                if f.emergency {
                    Some(DisabledReason::Emergency)
                } else if f.pending.is_some() {
                    Some(DisabledReason::Pending)
                } else if f.ringing {
                    Some(DisabledReason::Incoming)
                } else if f.live >= 2 {
                    Some(DisabledReason::TwoCalls)
                } else if self.held().is_none() && self.on_hold_reason().is_some() {
                    Some(DisabledReason::OnHold)
                } else if !f.can_add_call {
                    Some(DisabledReason::AddCallCarrier)
                } else {
                    None
                }
            }
            Control::Merge => {
                if f.emergency {
                    Some(DisabledReason::Emergency)
                } else if f.pending.is_some() {
                    Some(DisabledReason::Pending)
                } else if f.ringing {
                    Some(DisabledReason::Incoming)
                } else if f.live < 2 {
                    Some(DisabledReason::NeedsTwoCalls)
                } else if !f.can_merge {
                    Some(DisabledReason::MergeCarrier)
                } else {
                    None
                }
            }
            Control::HoldSwap => {
                if f.pending.is_some() {
                    return Some(DisabledReason::Pending);
                }
                if self
                    .primary()
                    .is_some_and(|p| p.state == WireCallState::Dialing)
                {
                    return Some(DisabledReason::Connecting);
                }
                match self.hold_slot() {
                    HoldSlot::Swap if !f.can_swap => Some(DisabledReason::SwapUnsupported),
                    HoldSlot::Hold if !self.primary().is_some_and(can_hold) => {
                        Some(DisabledReason::HoldUnsupported)
                    }
                    _ => None,
                }
            }
            Control::HoldAndAnswer => {
                if self.waiting.is_none() {
                    None
                } else if f.emergency {
                    Some(DisabledReason::Emergency)
                } else if f.pending.is_some() {
                    Some(DisabledReason::Pending)
                } else if self.primary().is_some_and(|p| is_live(p) && !can_hold(p)) {
                    Some(DisabledReason::CurrentCallCannotHold)
                } else {
                    None
                }
            }
        }
    }
}

fn can_hold(call: &WireCall) -> bool {
    call.can_hold || call.has_cap(caps::HOLD)
}

/// A control on the In-Call Screen that can be dimmed.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Control {
    Mute,
    Keypad,
    Audio,
    AddCall,
    Merge,
    /// The Hold / Swap slot.
    HoldSwap,
    /// The Waiting card's "Hold and answer".
    HoldAndAnswer,
}

/// What the shared Hold / Swap slot does now.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HoldSlot {
    Hold,
    Swap,
}

/// Why a control is dimmed. Each variant has one English string, used by the
/// Notice pill on long-press and as the control's TalkBack hint.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DisabledReason {
    Emergency,
    /// An operation (answer, swap, merge) is still running.
    Pending,
    /// A call is ringing and must be answered or declined first.
    Incoming,
    TwoCalls,
    AddCallCarrier,
    NeedsTwoCalls,
    MergeCarrier,
    HoldUnsupported,
    SwapUnsupported,
    CurrentCallCannotHold,
    /// The call is on hold: resume it first.
    OnHold,
    /// The call is still dialing.
    Connecting,
    /// The control has no WhatsApp equivalent (keypad, hold, add call, merge).
    WhatsAppCall,
}

impl DisabledReason {
    pub const fn text(self) -> &'static str {
        match self {
            Self::Emergency => "Not available during an emergency call",
            Self::Pending => "Finishing the last action",
            Self::Incoming => "Answer or decline the incoming call first",
            Self::TwoCalls => "Two calls are already in progress",
            Self::AddCallCarrier => "Your carrier doesn't allow adding calls",
            Self::NeedsTwoCalls => "Merge needs two calls",
            Self::MergeCarrier => "Your carrier doesn't allow merging calls",
            Self::HoldUnsupported => "This call can't be put on hold",
            Self::SwapUnsupported => "Your carrier doesn't allow swapping calls",
            Self::CurrentCallCannotHold => "Ends the current call instead: it can't be held",
            Self::OnHold => "Resume the call first",
            Self::Connecting => "Wait for the call to connect",
            Self::WhatsAppCall => "Not available on WhatsApp calls",
        }
    }
}

/// Which parts of the scene a snapshot changed.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct SceneDiff {
    /// The snapshot was newer and replaced the held one.
    pub applied: bool,
    pub primary: bool,
    pub held: bool,
    pub waiting: bool,
    pub ended: bool,
    /// Audio route list (the endpoint part of the snapshot).
    pub endpoints: bool,
    pub participants: bool,
    /// Capability flags and the pending operation.
    pub controls: bool,
    /// Mute and the route summary.
    pub audio: bool,
    /// Keyguard, ringer and account-selection state.
    pub session: bool,
}

impl SceneDiff {
    /// Whether anything a view draws changed.
    pub fn changed(&self) -> bool {
        self.primary
            || self.held
            || self.waiting
            || self.ended
            || self.endpoints
            || self.participants
            || self.controls
            || self.audio
            || self.session
    }

    /// Both diffs together: a part changed if it changed in either.
    pub fn merge(self, other: Self) -> Self {
        Self {
            applied: self.applied || other.applied,
            primary: self.primary || other.primary,
            held: self.held || other.held,
            waiting: self.waiting || other.waiting,
            ended: self.ended || other.ended,
            endpoints: self.endpoints || other.endpoints,
            participants: self.participants || other.participants,
            controls: self.controls || other.controls,
            audio: self.audio || other.audio,
            session: self.session || other.session,
        }
    }

    /// Compares two snapshots. `endpoints` is whether the endpoint list changed.
    pub fn between(old: &CallSnapshot, new: &CallSnapshot, endpoints: bool) -> Self {
        let (a, b) = (Roles::of(old), Roles::of(new));
        let participants = match (a.foreground, b.foreground) {
            (Some(x), Some(y)) => children_differ(old, x, new, y),
            (None, None) => false,
            (Some(x), None) | (None, Some(x)) => !x.children.is_empty(),
        };
        Self {
            applied: true,
            primary: a.foreground != b.foreground,
            held: a.background != b.background,
            waiting: a.waiting != b.waiting,
            ended: a.ended != b.ended,
            endpoints,
            participants,
            controls: old.can_add_call != new.can_add_call
                || old.can_merge != new.can_merge
                || old.can_swap != new.can_swap
                || old.pending != new.pending
                || old.pending_failed != new.pending_failed,
            audio: old.audio.muted != new.audio.muted
                || old.audio.route != new.audio.route
                || old.audio.routes != new.audio.routes,
            session: old.keyguard_locked != new.keyguard_locked
                || old.ringer_silenced != new.ringer_silenced
                || old.silenced_by != new.silenced_by
                || old.selecting_account != new.selecting_account
                || old.accounts != new.accounts,
        }
    }
}

fn children_differ(old: &CallSnapshot, a: &WireCall, new: &CallSnapshot, b: &WireCall) -> bool {
    if a.children != b.children {
        return true;
    }
    a.children
        .iter()
        .any(|id| find(&old.calls, Some(*id)) != find(&new.calls, Some(*id)))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn call(id: i32, state: WireCallState) -> WireCall {
        WireCall {
            id,
            state,
            number_raw: format!("0555 000 00 0{id}"),
            connect_time_ms: if state == WireCallState::Ringing {
                0
            } else {
                1
            },
            can_hold: true,
            ..WireCall::default()
        }
    }

    fn snap(calls: Vec<WireCall>, fg: Option<i32>, bg: Option<i32>) -> CallSnapshot {
        CallSnapshot {
            calls,
            foreground_call_id: fg,
            background_call_id: bg,
            ..CallSnapshot::default()
        }
    }

    fn conf(id: i32, children: &[i32]) -> WireCall {
        WireCall {
            props: props::CONFERENCE,
            children: children.to_vec(),
            ..call(id, WireCallState::Active)
        }
    }

    fn child(id: i32, parent: i32) -> WireCall {
        WireCall {
            parent_id: Some(parent),
            caps: caps::SPLIT | caps::DISCONNECT_PARTICIPANT,
            ..call(id, WireCallState::Active)
        }
    }

    // ---- layout --------------------------------------------------------

    #[test]
    fn no_calls_is_empty() {
        let s = snap(vec![], None, None);
        assert!(matches!(CallScene::of(&s).layout, Layout::Empty));
    }

    #[test]
    fn one_call_is_single() {
        let s = snap(vec![call(1, WireCallState::Active)], Some(1), None);
        let scene = CallScene::of(&s);
        assert!(matches!(scene.layout, Layout::Single { primary } if primary.id == 1));
        assert!(scene.held().is_none());
        assert_eq!(scene.hold_slot(), HoldSlot::Hold);
    }

    #[test]
    fn two_calls_use_the_ids_kotlin_chose() {
        let s = snap(
            vec![
                call(1, WireCallState::Holding),
                call(2, WireCallState::Active),
            ],
            Some(2),
            Some(1),
        );
        let scene = CallScene::of(&s);
        assert!(
            matches!(scene.layout, Layout::Two { primary, held } if primary.id == 2 && held.id == 1)
        );
        assert_eq!(scene.hold_slot(), HoldSlot::Swap);
    }

    #[test]
    fn the_scene_never_reselects() {
        // Kotlin names the held call as foreground; Rust shows exactly that.
        let s = snap(
            vec![
                call(1, WireCallState::Holding),
                call(2, WireCallState::Active),
            ],
            Some(1),
            Some(2),
        );
        assert_eq!(CallScene::of(&s).primary().map(|c| c.id), Some(1));
    }

    #[test]
    fn unknown_ids_are_ignored_not_guessed() {
        let s = snap(vec![call(1, WireCallState::Active)], Some(9), Some(8));
        assert!(matches!(CallScene::of(&s).layout, Layout::Empty));
        let s = snap(vec![call(1, WireCallState::Active)], Some(1), Some(8));
        assert!(matches!(CallScene::of(&s).layout, Layout::Single { .. }));
        let mut s = snap(vec![call(1, WireCallState::Active)], Some(1), Some(1));
        s.waiting_call_id = Some(1);
        let scene = CallScene::of(&s);
        assert!(scene.held().is_none() && scene.waiting.is_none());
    }

    #[test]
    fn waiting_and_ended_are_independent_of_the_layout() {
        let mut s = snap(
            vec![
                call(1, WireCallState::Active),
                call(2, WireCallState::Ringing),
                call(3, WireCallState::Disconnected),
            ],
            Some(1),
            None,
        );
        s.waiting_call_id = Some(2);
        s.ended_call_id = Some(3);
        let scene = CallScene::of(&s);
        assert!(matches!(scene.layout, Layout::Single { .. }));
        assert_eq!(scene.waiting.map(|c| c.id), Some(2));
        assert_eq!(scene.ended.map(|c| c.id), Some(3));
    }

    #[test]
    fn conference_wins_beside_a_held_call() {
        let s = snap(
            vec![
                conf(1, &[3, 4]),
                call(2, WireCallState::Holding),
                child(3, 1),
                child(4, 1),
            ],
            Some(1),
            Some(2),
        );
        let scene = CallScene::of(&s);
        let Layout::Conference {
            host,
            participants,
            held,
        } = scene.layout
        else {
            panic!("expected conference");
        };
        assert_eq!(host.id, 1);
        assert_eq!(held.map(|c| c.id), Some(2));
        assert_eq!(participants.len(), 2);
        assert_eq!(participants.skipped(), 0);
        assert_eq!(scene.hold_slot(), HoldSlot::Swap);
    }

    #[test]
    fn a_held_conference_beside_an_active_call_is_two() {
        let s = snap(
            vec![call(2, WireCallState::Active), conf(1, &[3]), child(3, 1)],
            Some(2),
            Some(1),
        );
        assert!(matches!(CallScene::of(&s).layout, Layout::Two { .. }));
    }

    // ---- participants --------------------------------------------------

    #[test]
    fn participants_are_labelled_by_name_number_or_position() {
        let mut named = child(3, 1);
        named.display_name = Some("Ada".into());
        let numbered = child(4, 1);
        let mut anon = child(5, 1);
        anon.number_raw.clear();
        let s = snap(
            vec![conf(1, &[3, 4, 5]), named, numbered, anon],
            Some(1),
            None,
        );
        let Layout::Conference { participants, .. } = CallScene::of(&s).layout else {
            panic!()
        };
        let labels: Vec<String> = participants
            .iter()
            .map(|p| p.label().into_owned())
            .collect();
        assert_eq!(labels, ["Ada", "0555 000 00 04", "Participant 3"]);
        let first = participants.iter().next().expect("row");
        assert!(first.can_split() && first.can_drop());
    }

    #[test]
    fn missing_children_are_skipped_and_counted() {
        let s = snap(vec![conf(1, &[3, 9, 1]), child(3, 1)], Some(1), None);
        let Layout::Conference { participants, .. } = CallScene::of(&s).layout else {
            panic!()
        };
        assert_eq!(participants.len(), 1);
        assert_eq!(participants.skipped(), 2);
        assert!(participants.summary().is_none());
        // Index counts drawn rows, not ids.
        assert_eq!(
            participants.iter().map(|p| p.index).collect::<Vec<_>>(),
            [1]
        );
    }

    #[test]
    fn a_known_count_without_rows_reads_n_people() {
        let s = snap(vec![conf(1, &[7, 8, 9])], Some(1), None);
        let Layout::Conference { participants, .. } = CallScene::of(&s).layout else {
            panic!()
        };
        assert!(participants.is_empty());
        assert_eq!(
            participants.summary().expect("summary").as_str(),
            "3 people"
        );
        let s = snap(vec![conf(1, &[7])], Some(1), None);
        let Layout::Conference { participants, .. } = CallScene::of(&s).layout else {
            panic!()
        };
        assert_eq!(
            participants.summary().expect("summary").as_str(),
            "1 person"
        );
        // A conference that hides its children has no count at all.
        let mut hidden = conf(1, &[]);
        hidden.props = props::CONFERENCE;
        let s = snap(vec![hidden], Some(1), None);
        let Layout::Conference { participants, .. } = CallScene::of(&s).layout else {
            panic!()
        };
        assert!(participants.summary().is_none());
    }

    // ---- disabled reasons ----------------------------------------------

    struct Case {
        calls: Vec<WireCall>,
        fg: i32,
        bg: Option<i32>,
        add: bool,
        merge: bool,
        swap: bool,
    }

    fn scene_snapshot(c: &Case) -> CallSnapshot {
        CallSnapshot {
            can_add_call: c.add,
            can_merge: c.merge,
            can_swap: c.swap,
            ..snap(c.calls.clone(), Some(c.fg), c.bg)
        }
    }

    fn reasons(s: &CallSnapshot) -> [Option<DisabledReason>; 3] {
        let scene = CallScene::of(s);
        [
            scene.reason(Control::AddCall),
            scene.reason(Control::Merge),
            scene.reason(Control::HoldSwap),
        ]
    }

    #[test]
    fn single_call_matrix() {
        let one = |add: bool, can_hold: bool| {
            let mut c = call(1, WireCallState::Active);
            c.can_hold = can_hold;
            scene_snapshot(&Case {
                calls: vec![c],
                fg: 1,
                bg: None,
                add,
                merge: false,
                swap: false,
            })
        };
        use DisabledReason::*;
        assert_eq!(reasons(&one(true, true)), [None, Some(NeedsTwoCalls), None]);
        assert_eq!(
            reasons(&one(false, true)),
            [Some(AddCallCarrier), Some(NeedsTwoCalls), None]
        );
        assert_eq!(
            reasons(&one(true, false)),
            [None, Some(NeedsTwoCalls), Some(HoldUnsupported)]
        );
        // The CAPABILITY_HOLD bit counts as much as `can_hold`.
        let mut s = one(true, false);
        s.calls[0].caps = caps::HOLD;
        assert_eq!(reasons(&s)[2], None);
    }

    #[test]
    fn two_call_matrix() {
        use DisabledReason::*;
        let two = |add: bool, merge: bool, swap: bool| {
            scene_snapshot(&Case {
                calls: vec![
                    call(1, WireCallState::Holding),
                    call(2, WireCallState::Active),
                ],
                fg: 2,
                bg: Some(1),
                add,
                merge,
                swap,
            })
        };
        // Add call is dimmed by the count even if the carrier said no.
        assert_eq!(reasons(&two(false, true, true))[0], Some(TwoCalls));
        assert_eq!(reasons(&two(true, true, true))[0], Some(TwoCalls));
        assert_eq!(reasons(&two(true, true, true))[1], None);
        assert_eq!(reasons(&two(true, false, true))[1], Some(MergeCarrier));
        // Hold and Swap share a slot: with a held call it is Swap.
        assert_eq!(reasons(&two(true, true, true))[2], None);
        assert_eq!(reasons(&two(true, true, false))[2], Some(SwapUnsupported));
    }

    #[test]
    fn emergency_beats_everything() {
        use DisabledReason::*;
        let mut c = call(1, WireCallState::Active);
        c.props = props::EMERGENCY;
        let mut s = snap(vec![c], Some(1), None);
        s.can_add_call = true;
        s.pending = Some(PendingOp::Swap);
        let scene = CallScene::of(&s);
        assert_eq!(scene.reason(Control::AddCall), Some(Emergency));
        assert_eq!(scene.reason(Control::Merge), Some(Emergency));
        assert_eq!(Emergency.text(), "Not available during an emergency call");
    }

    #[test]
    fn pending_and_ringing_come_before_count_and_carrier() {
        use DisabledReason::*;
        let mut s = snap(
            vec![
                call(1, WireCallState::Active),
                call(2, WireCallState::Holding),
            ],
            Some(1),
            Some(2),
        );
        s.pending = Some(PendingOp::Merge);
        let scene = CallScene::of(&s);
        assert_eq!(scene.reason(Control::AddCall), Some(Pending));
        assert_eq!(scene.reason(Control::Merge), Some(Pending));
        assert_eq!(scene.reason(Control::HoldSwap), Some(Pending));

        let mut s = snap(
            vec![
                call(1, WireCallState::Active),
                call(2, WireCallState::Ringing),
            ],
            Some(1),
            None,
        );
        s.waiting_call_id = Some(2);
        s.can_add_call = true;
        let scene = CallScene::of(&s);
        assert_eq!(scene.reason(Control::AddCall), Some(Incoming));
        assert_eq!(scene.reason(Control::Merge), Some(Incoming));
        // A ringing call is not counted as live.
        assert_eq!(scene.facts.live, 1);
    }

    #[test]
    fn hold_and_answer_needs_a_holdable_current_call() {
        use DisabledReason::*;
        let mut active = call(1, WireCallState::Active);
        active.can_hold = false;
        let mut s = snap(vec![active, call(2, WireCallState::Ringing)], Some(1), None);
        s.waiting_call_id = Some(2);
        assert_eq!(
            CallScene::of(&s).reason(Control::HoldAndAnswer),
            Some(CurrentCallCannotHold)
        );
        s.calls[0].can_hold = true;
        assert_eq!(CallScene::of(&s).reason(Control::HoldAndAnswer), None);
        // Nothing waiting, nothing to dim.
        s.waiting_call_id = None;
        assert_eq!(CallScene::of(&s).reason(Control::HoldAndAnswer), None);
    }

    #[test]
    fn conference_without_a_held_call_offers_hold() {
        let s = scene_snapshot(&Case {
            calls: vec![conf(1, &[2]), child(2, 1)],
            fg: 1,
            bg: None,
            add: true,
            merge: false,
            swap: false,
        });
        let scene = CallScene::of(&s);
        assert_eq!(scene.hold_slot(), HoldSlot::Hold);
        assert_eq!(scene.reason(Control::HoldSwap), None);
        // Child rows are not top-level calls.
        assert_eq!(scene.facts.live, 1);
        assert_eq!(scene.reason(Control::AddCall), None);
    }

    #[test]
    fn every_reason_has_a_distinct_nonempty_string() {
        use DisabledReason::*;
        let all = [
            Emergency,
            Pending,
            Incoming,
            TwoCalls,
            AddCallCarrier,
            NeedsTwoCalls,
            MergeCarrier,
            HoldUnsupported,
            SwapUnsupported,
            CurrentCallCannotHold,
            OnHold,
            Connecting,
            WhatsAppCall,
        ];
        for (i, a) in all.iter().enumerate() {
            assert!(!a.text().is_empty());
            for b in &all[i + 1..] {
                assert_ne!(a.text(), b.text());
            }
        }
        assert_eq!(NeedsTwoCalls.text(), "Merge needs two calls");
        assert_eq!(
            MergeCarrier.text(),
            "Your carrier doesn't allow merging calls"
        );
        assert_eq!(TwoCalls.text(), "Two calls are already in progress");
        assert_eq!(WhatsAppCall.text(), "Not available on WhatsApp calls");
    }

    #[test]
    fn a_held_single_call_dims_the_live_controls_with_one_reason() {
        use DisabledReason::*;
        let s = snap(vec![call(1, WireCallState::Holding)], Some(1), None);
        let scene = CallScene::of(&s);
        for control in [Control::Mute, Control::Keypad, Control::Audio] {
            assert_eq!(scene.reason(control), Some(OnHold));
        }
        let mut live = s.clone();
        live.calls[0].state = WireCallState::Active;
        let scene = CallScene::of(&live);
        for control in [Control::Mute, Control::Keypad, Control::Audio] {
            assert_eq!(scene.reason(control), None);
        }
    }

    #[test]
    fn add_call_is_dimmed_while_the_only_call_is_held_and_hold_while_dialing() {
        use DisabledReason::*;
        let mut s = snap(vec![call(1, WireCallState::Holding)], Some(1), None);
        s.can_add_call = true;
        assert_eq!(CallScene::of(&s).reason(Control::AddCall), Some(OnHold));
        // Resume is still offered on a held call.
        assert_eq!(CallScene::of(&s).reason(Control::HoldSwap), None);
        let d = snap(vec![call(1, WireCallState::Dialing)], Some(1), None);
        assert_eq!(
            CallScene::of(&d).reason(Control::HoldSwap),
            Some(Connecting)
        );
    }

    // ---- diff ----------------------------------------------------------

    #[test]
    fn a_timer_tick_changes_nothing() {
        let mut a = snap(vec![call(1, WireCallState::Active)], Some(1), None);
        a.seq = 3;
        a.now_ms = 1_000;
        let mut b = a.clone();
        b.seq = 4;
        b.now_ms = 2_000;
        let diff = SceneDiff::between(&a, &b, false);
        assert!(!diff.changed());
        assert!(diff.applied);
    }

    #[test]
    fn diff_flags_name_the_part_that_moved() {
        let base = snap(
            vec![
                call(1, WireCallState::Active),
                call(2, WireCallState::Holding),
            ],
            Some(1),
            Some(2),
        );
        let mut swapped = base.clone();
        swapped.foreground_call_id = Some(2);
        swapped.background_call_id = Some(1);
        let d = SceneDiff::between(&base, &swapped, false);
        assert!(d.primary && d.held && !d.waiting && !d.participants);

        let mut waiting = base.clone();
        waiting.calls.push(call(3, WireCallState::Ringing));
        waiting.waiting_call_id = Some(3);
        let d = SceneDiff::between(&base, &waiting, false);
        assert!(d.waiting && !d.primary && !d.held);

        let mut can = base.clone();
        can.can_merge = true;
        let d = SceneDiff::between(&base, &can, true);
        assert!(d.controls && d.endpoints && !d.primary);

        let mut muted = base.clone();
        muted.audio.muted = true;
        assert!(SceneDiff::between(&base, &muted, false).audio);

        let mut ended = base.clone();
        ended.calls.push(call(4, WireCallState::Disconnected));
        ended.ended_call_id = Some(4);
        assert!(SceneDiff::between(&base, &ended, false).ended);
    }

    #[test]
    fn a_child_change_flags_participants_only() {
        let a = snap(
            vec![conf(1, &[2, 3]), child(2, 1), child(3, 1)],
            Some(1),
            None,
        );
        let mut b = a.clone();
        b.calls[2].display_name = Some("Bo".into());
        let d = SceneDiff::between(&a, &b, false);
        assert!(d.participants && !d.primary);
        let mut c = a.clone();
        c.calls[0].children = vec![2];
        c.calls.pop();
        let d = SceneDiff::between(&a, &c, false);
        assert!(d.participants && d.primary);
    }
}
