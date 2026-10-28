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
    WireCall, WireCallState,
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
    fn preview_apply(&mut self, cx: &mut Context<Self>, edit: impl FnOnce(&mut CallSnapshot)) {
        let mut snapshot = self.calls.snapshot().clone();
        snapshot.seq = self.calls.seq().map_or(0, |s| s + 1);
        snapshot.now_ms = now_ms();
        snapshot.keyguard_locked = self.preview_locked;
        edit(&mut snapshot);
        if self.calls.apply(snapshot) {
            cx.notify();
        }
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

    /// Disconnects the fake call with `cause`, then removes it (as Telecom does).
    fn preview_end(&mut self, cause: DisconnectCause, cx: &mut Context<Self>) {
        self.preview_apply(cx, |s| {
            if let Some(call) = s.calls.first_mut() {
                call.state = WireCallState::Disconnected;
                call.disconnect_cause = Some(cause);
            }
        });
        let task = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(REMOVE_AFTER).await;
            this.update(cx, |this, cx| {
                this.preview_apply(cx, |s| {
                    s.calls.clear();
                    s.foreground_call_id = None;
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
            CallCommand::Disconnect(_) => self.preview_end(DisconnectCause::Local, cx),
            CallCommand::Reject(id) if id == WAITING_ID => self.preview_apply(cx, |s| {
                s.calls.retain(|c| c.id != WAITING_ID);
                s.waiting_call_id = None;
            }),
            CallCommand::Reject(_) => self.preview_end(DisconnectCause::Rejected, cx),
            CallCommand::AnswerReplacing(id) => {
                // The first call ends, then the waiting one is answered.
                self.preview_apply(cx, |s| {
                    s.calls.retain(|c| c.id == id);
                    s.waiting_call_id = None;
                    s.foreground_call_id = Some(id);
                    if let Some(call) = s.calls.first_mut() {
                        call.state = WireCallState::Active;
                        call.connect_time_ms = now_ms();
                    }
                });
            }
            CallCommand::Answer(_) => {
                self.preview_apply(cx, |s| {
                    if let Some(call) = s.calls.first_mut()
                        && call.state == WireCallState::Ringing
                    {
                        call.state = WireCallState::Active;
                        call.connect_time_ms = now_ms();
                    }
                });
            }
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
