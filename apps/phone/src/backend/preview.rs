//! Desktop-only call preview: `cargo run -p rovsti_phone` has no Telecom, so
//! placing a call walks a fake one through Dialing, Active and Disconnected
//! using the same `CallModel` the Android events feed. This is what makes the
//! In-Call screen designable on a desktop.
//!
//! `ROVSTI_PREVIEW_INCOMING` does the same for the Incoming Call screen: `1`
//! rings from an unknown number, any other value rings as that contact. Add
//! `ROVSTI_PREVIEW_LOCKED=1` for the lock-screen look (call-only mode) and
//! `ROVSTI_PREVIEW_MISSED=1` to let the caller give up after a few seconds.

use std::time::Duration;

use gpui::Context;
use rovsti_core::wire::{
    AudioRoute, AudioState, CallSnapshot, DisconnectCause, Endpoint, EndpointHint, EndpointType,
    WireCall, WireCallState, caps, props,
};

use super::{Backend, CallCommand, now_ms};

const CALL_ID: i32 = 1;
/// The second caller of the waiting-call preview.
const WAITING_ID: i32 = 2;
/// The fake network answers after this long.
const ANSWER_AFTER: Duration = Duration::from_millis(1600);
/// The record is removed this long after "disconnected" (Telecom is quick).
const REMOVE_AFTER: Duration = Duration::from_millis(250);
/// The fake caller gives up after this long (`ROVSTI_PREVIEW_MISSED`).
const GIVE_UP_AFTER: Duration = Duration::from_millis(6000);

/// The desktop route list (`ROVSTI_PREVIEW_ROUTES=1`, or `many` for a long one).
fn sample_endpoints(many: bool) -> Vec<Endpoint> {
    let ep = |id: &str, kind, name: Option<&str>| Endpoint {
        id: id.to_owned(),
        kind,
        name: name.map(str::to_owned),
        selected: false,
        hint: None,
    };
    let mut list = vec![
        ep("p", EndpointType::Earpiece, None),
        ep("s", EndpointType::Speaker, None),
        ep("bt-buds", EndpointType::Bluetooth, Some("Galaxy Buds Pro")),
        // Android sent no name: "Bluetooth device 1"; selecting it never lands (the revert demo).
        ep("bt-nameless", EndpointType::Bluetooth, None),
        ep("wired", EndpointType::WiredHeadset, Some("USB-C headset")),
    ];
    if many {
        let mut aid = ep("bt-hear", EndpointType::Bluetooth, Some("Phonak Audeo"));
        aid.hint = Some(EndpointHint::HearingAid);
        list.push(aid);
        list.push(ep("bt-car", EndpointType::Bluetooth, Some("Car stereo")));
    }
    list[0].selected = true;
    list
}

/// Keeps `route` and `routes` derived from the endpoints, as Kotlin does (AD-23).
fn derive_audio(audio: &mut AudioState) {
    if let Some(route) = audio.route_from_endpoints() {
        audio.route = route;
    }
    audio.routes = audio.routes_from_endpoints();
}

fn select_endpoint(audio: &mut AudioState, id: &str) {
    for e in &mut audio.endpoints {
        e.selected = e.id == id;
    }
    derive_audio(audio);
}

fn routes_preview() -> Option<bool> {
    std::env::var("ROVSTI_PREVIEW_ROUTES")
        .ok()
        .map(|v| v == "many")
}

impl Backend {
    pub(super) fn preview_apply(&mut self, cx: &mut Context<Self>, edit: impl FnOnce(&mut CallSnapshot)) {
        let mut snapshot = self.calls.snapshot().clone();
        snapshot.seq = self.calls.seq().map_or(0, |s| s + 1);
        snapshot.now_ms = now_ms();
        snapshot.keyguard_locked = self.preview_locked;
        edit(&mut snapshot);
        self.apply_snapshot(snapshot, cx);
    }

    /// A fake incoming call: rings until Answer, Decline, or (if `give_up`) the
    /// caller hangs up.
    pub(super) fn preview_incoming(
        &mut self,
        name: Option<&str>,
        locked: bool,
        give_up: bool,
        cx: &mut Context<Self>,
    ) {
        self.preview_locked = locked;
        let name = name.map(str::to_owned);
        self.preview_apply(cx, |s| {
            s.calls = vec![WireCall {
                id: CALL_ID,
                state: WireCallState::Ringing,
                number_raw: "+905551112233".to_owned(),
                normalized: "905551112233".to_owned(),
                contact_id: name.as_ref().map(|_| 42),
                display_name: name,
                connect_time_ms: 0,
                can_hold: true,
                is_incoming: true,
                disconnect_cause: None,
                ..WireCall::default()
            }];
            s.foreground_call_id = Some(CALL_ID);
            s.waiting_call_id = None;
            s.audio.muted = false;
            s.audio.route = AudioRoute::Earpiece;
            s.audio.routes = vec![AudioRoute::Earpiece, AudioRoute::Speaker];
        });
        if give_up {
            let task = cx.spawn(async move |this, cx| {
                cx.background_executor().timer(GIVE_UP_AFTER).await;
                this.update(cx, |this, cx| {
                    let ringing = this
                        .calls
                        .call(CALL_ID)
                        .is_some_and(|c| c.state == WireCallState::Ringing);
                    if ringing {
                        this.preview_end(DisconnectCause::Missed, cx);
                    }
                })
                .ok();
            });
            task.detach();
        }
    }

    /// Disconnects the first fake call with `cause`, then removes it (as Telecom does).
    fn preview_end(&mut self, cause: DisconnectCause, cx: &mut Context<Self>) {
        let id = self
            .calls
            .calls()
            .iter()
            .find(|c| c.parent_id.is_none())
            .map_or(CALL_ID, |c| c.id);
        self.preview_end_id(id, cause, cx);
    }

    /// Disconnects the fake call `id` (its conference children go with it), then removes
    /// it. The other call, if any, becomes the foreground and stays as it was (a held call
    /// does not resume by itself).
    pub(super) fn preview_end_id(&mut self, id: i32, cause: DisconnectCause, cx: &mut Context<Self>) {
        self.preview_apply(cx, |s| {
            if let Some(call) = s.calls.iter_mut().find(|c| c.id == id) {
                call.state = WireCallState::Disconnected;
                call.disconnect_cause = Some(cause);
            }
            roles::assign(s, None);
        });
        let task = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(REMOVE_AFTER).await;
            this.update(cx, |this, cx| {
                this.preview_apply(cx, |s| {
                    s.calls.retain(|c| c.id != id && c.parent_id != Some(id));
                    roles::assign(s, None);
                });
            })
            .ok();
        });
        task.detach();
    }

    /// The debug switcher: a fake outgoing call that ends up `variant`
    /// (`hold`, `waiting` or `mute`), like `ROVSTI_PREVIEW_CALL=<variant>`.
    pub fn preview_scenario(&mut self, variant: &'static str, cx: &mut Context<Self>) {
        self.preview_variant = Some(variant);
        self.preview_start("+905551112233", cx);
    }

    pub(super) fn preview_start(&mut self, raw: &str, cx: &mut Context<Self>) {
        let variant = self
            .preview_variant
            .take()
            .map(str::to_owned)
            .or_else(|| std::env::var("ROVSTI_PREVIEW_CALL").ok());
        let raw = raw.to_owned();
        if self.calls.foreground().is_some_and(|c| {
            matches!(c.state, WireCallState::Active | WireCallState::Holding)
        }) {
            self.preview_add_call(&raw, cx);
            return;
        }
        self.preview_apply(cx, |s| {
            s.calls = vec![WireCall {
                id: CALL_ID,
                state: WireCallState::Dialing,
                number_raw: raw.clone(),
                normalized: raw,
                display_name: None,
                contact_id: None,
                connect_time_ms: 0,
                can_hold: true,
                is_incoming: false,
                disconnect_cause: None,
                ..WireCall::default()
            }];
            s.foreground_call_id = Some(CALL_ID);
            s.waiting_call_id = None;
            s.audio.muted = false;
            s.audio.route = AudioRoute::Earpiece;
            s.audio.routes = vec![AudioRoute::Earpiece, AudioRoute::Speaker];
            s.audio.endpoints = Vec::new();
            let many = routes_preview();
            if let Some(many) = many.or((variant.as_deref() == Some("routes")).then_some(false)) {
                s.audio.endpoints = sample_endpoints(many);
                derive_audio(&mut s.audio);
            }
        });
        let task = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(ANSWER_AFTER).await;
            this.update(cx, |this, cx| {
                let still_dialing = this
                    .calls
                    .call(CALL_ID)
                    .is_some_and(|c| c.state == WireCallState::Dialing);
                if still_dialing {
                    let hold = variant.as_deref() == Some("hold");
                    let mute = variant.as_deref() == Some("mute");
                    this.preview_apply(cx, |s| {
                        s.audio.muted = mute;
                        if let Some(call) = s.calls.first_mut() {
                            call.state = if hold {
                                WireCallState::Holding
                            } else {
                                WireCallState::Active
                            };
                            call.connect_time_ms = now_ms();
                        }
                    });
                    if variant.as_deref() == Some("waiting") {
                        this.preview_waiting(cx);
                    }
                }
            })
            .ok();
        });
        task.detach();
    }

    /// A second caller rings while the first call is live (Story 9.2).
    fn preview_waiting(&mut self, cx: &mut Context<Self>) {
        self.preview_apply(cx, |s| {
            s.calls.push(WireCall {
                id: WAITING_ID,
                state: WireCallState::Ringing,
                number_raw: "+905321234567".to_owned(),
                normalized: "905321234567".to_owned(),
                display_name: Some("Burak Demir".to_owned()),
                contact_id: Some(2),
                connect_time_ms: 0,
                can_hold: true,
                is_incoming: true,
                disconnect_cause: None,
                ..WireCall::default()
            });
            s.waiting_call_id = Some(WAITING_ID);
        });
    }

    pub(super) fn preview_command(&mut self, command: CallCommand, cx: &mut Context<Self>) {
        match command {
            CallCommand::Mute(on) => self.preview_apply(cx, |s| s.audio.muted = on),
            CallCommand::Route(route) => self.preview_apply(cx, |s| {
                let id = s
                    .audio
                    .endpoints
                    .iter()
                    .find(|e| e.kind.route() == route)
                    .map(|e| e.id.clone());
                match id {
                    Some(id) => select_endpoint(&mut s.audio, &id),
                    None => s.audio.route = route,
                }
            }),
            CallCommand::Hold(id, on) => self.preview_apply(cx, |s| {
                if let Some(call) = s.calls.iter_mut().find(|c| c.id == id) {
                    call.state = if on {
                        WireCallState::Holding
                    } else {
                        WireCallState::Active
                    };
                }
            }),
            CallCommand::Disconnect(id) => self.preview_disconnect(id, cx),
            CallCommand::Reject(id) if self.calls.waiting().is_some_and(|w| w.id == id) => {
                self.preview_apply(cx, |s| {
                    s.calls.retain(|c| c.id != id);
                    roles::assign(s, None);
                });
            }
            CallCommand::Reject(_) => self.preview_end(DisconnectCause::Rejected, cx),
            CallCommand::AnswerReplacing(id) => self.preview_answer(id, Answer::EndCurrent, cx),
            CallCommand::AnswerHolding(id) => self.preview_answer(id, Answer::Hold, cx),
            CallCommand::AnswerEndingHeld(id) => self.preview_answer(id, Answer::EndHeld, cx),
            CallCommand::Swap => self.preview_swap(cx),
            CallCommand::Merge => self.preview_merge(cx),
            CallCommand::Split(id) => self.preview_split(id, cx),
            CallCommand::EndAll => self.preview_apply(cx, |s| {
                s.calls.retain(|c| c.state == WireCallState::Ringing && c.parent_id.is_none());
                roles::assign(s, None);
            }),
            CallCommand::Answer(id) => self.preview_answer(id, Answer::Plain, cx),
            CallCommand::Dtmf(_) | CallCommand::DtmfStop => {}
        }
    }

    /// Desktop route requests land after a short delay, like Android's. The
    /// nameless Bluetooth device never lands, to show the revert.
    pub(super) fn preview_route_endpoint(&mut self, id: &str, cx: &mut Context<Self>) {
        if id == "bt-nameless" {
            return;
        }
        let id = id.to_owned();
        let task = cx.spawn(async move |this, cx| {
            cx.background_executor()
                .timer(Duration::from_millis(220))
                .await;
            this.update(cx, |this, cx| {
                this.preview_apply(cx, |s| select_endpoint(&mut s.audio, &id));
            })
            .ok();
        });
        task.detach();
    }

    /// Devtools: a Bluetooth device connects (S18).
    pub fn preview_add_bluetooth(&mut self, cx: &mut Context<Self>) {
        const NAMES: [&str; 3] = ["Sony WH-1000XM5", "AirPods Pro", "JBL Flip 6"];
        self.preview_apply(cx, |s| {
            if s.calls.is_empty() {
                return;
            }
            if s.audio.endpoints.is_empty() {
                s.audio.endpoints = sample_endpoints(false);
            }
            let n = s.audio.endpoints.len();
            s.audio.endpoints.push(Endpoint {
                id: format!("bt-new-{n}"),
                kind: EndpointType::Bluetooth,
                name: Some(NAMES[n % NAMES.len()].to_owned()),
                selected: false,
                hint: None,
            });
            derive_audio(&mut s.audio);
        });
    }

    /// Devtools: the newest Bluetooth device disconnects.
    pub fn preview_remove_bluetooth(&mut self, cx: &mut Context<Self>) {
        self.preview_apply(cx, |s| {
            if let Some(i) = s
                .audio
                .endpoints
                .iter()
                .rposition(|e| e.kind == EndpointType::Bluetooth)
            {
                let gone = s.audio.endpoints.remove(i);
                if gone.selected
                    && let Some(first) = s.audio.endpoints.first_mut()
                {
                    first.selected = true;
                }
                derive_audio(&mut s.audio);
            }
        });
    }
}

/// How a waiting call is answered in the preview.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum Answer {
    Plain,
    Hold,
    EndCurrent,
    EndHeld,
}

/// Mirrors Kotlin's `Focus.of` and `MultiCall.canDo` (AD-24) so the fake calls carry
/// the same roles and flags a phone would.
pub(super) mod roles {
    use super::{CallSnapshot, WireCall, WireCallState, caps, props};

    fn live(c: &WireCall) -> bool {
        matches!(
            c.state,
            WireCallState::Dialing | WireCallState::Active | WireCallState::Holding
        )
    }

    /// Fills the role ids and the three `can_*` flags from `calls`. `keep` is the foreground
    /// that stays in front while an operation runs.
    pub(in crate::backend) fn assign(s: &mut CallSnapshot, keep: Option<i32>) {
        let top: Vec<&WireCall> = s.calls.iter().filter(|c| c.parent_id.is_none()).collect();
        let first = |state| top.iter().find(|c| c.state == state).map(|c| c.id);
        let kept = keep.filter(|k| top.iter().any(|c| c.id == *k && live(c)));
        let fg = kept
            .or_else(|| first(WireCallState::Active))
            .or_else(|| first(WireCallState::Dialing))
            .or_else(|| first(WireCallState::Holding))
            .or_else(|| first(WireCallState::Ringing))
            .or_else(|| first(WireCallState::Disconnected));
        let waiting = top
            .iter()
            .find(|c| c.state == WireCallState::Ringing && Some(c.id) != fg)
            .map(|c| c.id);
        let background = top.iter().find(|c| live(c) && Some(c.id) != fg).map(|c| c.id);
        let any_live = top
            .iter()
            .any(|c| live(c) || c.state == WireCallState::Ringing);
        let ended = any_live
            .then(|| {
                top.iter()
                    .find(|c| c.state == WireCallState::Disconnected && Some(c.id) != fg)
                    .map(|c| c.id)
            })
            .flatten();
        let live_count = top.iter().filter(|c| live(c)).count();
        let fg_call = top.iter().find(|c| Some(c.id) == fg);
        let emergency = top.iter().any(|c| c.has_prop(props::EMERGENCY));
        let can_merge = fg_call.is_some_and(|c| c.has_cap(caps::MERGE));
        let held_resumes = top
            .iter()
            .find(|c| Some(c.id) == background)
            .is_some_and(|c| c.can_hold || c.has_cap(caps::HOLD));
        let can_swap = fg_call.is_some_and(|c| c.has_cap(caps::SWAP))
            || (live_count == 2 && held_resumes);
        let can_add = !emergency && live_count < 2 && any_live;
        s.foreground_call_id = fg;
        s.waiting_call_id = waiting;
        s.background_call_id = background;
        s.ended_call_id = ended;
        s.can_merge = can_merge;
        s.can_swap = can_swap;
        s.can_add_call = can_add;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn call(id: i32, state: WireCallState) -> WireCall {
        WireCall {
            id,
            state,
            can_hold: true,
            caps: caps::HOLD,
            ..WireCall::default()
        }
    }

    #[test]
    fn roles_follow_the_kotlin_rules() {
        let mut s = CallSnapshot {
            calls: vec![
                call(1, WireCallState::Holding),
                call(2, WireCallState::Active),
                call(3, WireCallState::Ringing),
            ],
            ..CallSnapshot::default()
        };
        roles::assign(&mut s, None);
        assert_eq!(s.foreground_call_id, Some(2));
        assert_eq!(s.background_call_id, Some(1));
        assert_eq!(s.waiting_call_id, Some(3));
        assert!(s.can_swap && !s.can_add_call && !s.can_merge);
    }

    #[test]
    fn the_active_party_hanging_up_leaves_the_held_call_in_front_and_held() {
        let mut s = CallSnapshot {
            calls: vec![
                call(1, WireCallState::Holding),
                call(2, WireCallState::Disconnected),
            ],
            ..CallSnapshot::default()
        };
        roles::assign(&mut s, None);
        assert_eq!(s.foreground_call_id, Some(1));
        assert_eq!(s.ended_call_id, Some(2));
        s.calls.retain(|c| c.id == 1);
        roles::assign(&mut s, None);
        assert_eq!(s.foreground_call_id, Some(1));
        assert_eq!(s.calls[0].state, WireCallState::Holding);
        assert_eq!(s.ended_call_id, None);
    }

    #[test]
    fn children_never_take_a_role() {
        let mut s = CallSnapshot {
            calls: vec![
                WireCall {
                    parent_id: Some(3),
                    ..call(1, WireCallState::Active)
                },
                WireCall {
                    props: props::CONFERENCE,
                    children: vec![1],
                    ..call(3, WireCallState::Active)
                },
            ],
            ..CallSnapshot::default()
        };
        roles::assign(&mut s, None);
        assert_eq!(s.foreground_call_id, Some(3));
        assert_eq!(s.background_call_id, None);
    }
}
