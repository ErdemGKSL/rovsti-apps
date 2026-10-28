//! Desktop-only multi-call preview (stories 15.3 to 15.8): the five scenes of
//! the debug switcher and a small Telecom stand-in for swap, merge, split,
//! hang-ups and the four Waiting choices. It produces the same snapshots a
//! phone would, role ids and `can_*` flags included, so every screen can be
//! designed and screenshotted on a desktop.

use std::time::Duration;

use gpui::Context;
use rovsti_core::wire::{
    AudioRoute, CallSnapshot, PendingOp, WireCall, WireCallState, caps, props,
};

use super::{
    Backend, now_ms,
    preview::{Answer, roles},
};

/// How long the fake network takes to swap, and to merge.
const SWAP_AFTER: Duration = Duration::from_millis(260);
const MERGE_AFTER: Duration = Duration::from_millis(700);
/// The second call of "Add call" connects after this long.
const ADD_CONNECTS: Duration = Duration::from_millis(1500);

const AYSE: (&str, &str, i64) = ("Ayşe Yılmaz", "+905551112233", 17);
const OZGUR: (&str, &str, i64) = ("Özgür Kaya", "+905321234567", 18);
const BURAK: (&str, &str, i64) = ("Burak Demir", "+4915123456789", 2);
const ELIF: (&str, &str, i64) = ("Elif Arslan", "+905441112200", 19);
const MERT: (&str, &str, i64) = ("Mert Çelik", "+905059876543", 20);

const HOLDABLE: u32 = caps::HOLD | caps::SUPPORT_HOLD | caps::MUTE | caps::ADD_PARTICIPANT;

fn person(
    id: i32,
    who: (&str, &str, i64),
    state: WireCallState,
    ago_s: i64,
    caps: u32,
) -> WireCall {
    let now = now_ms();
    WireCall {
        id,
        state,
        number_raw: who.1.to_owned(),
        normalized: who.1.to_owned(),
        display_name: Some(who.0.to_owned()),
        contact_id: Some(who.2),
        connect_time_ms: if state == WireCallState::Ringing {
            0
        } else {
            now - ago_s * 1000
        },
        can_hold: caps & caps::HOLD != 0,
        is_incoming: state == WireCallState::Ringing,
        caps,
        hold_since_ms: (state == WireCallState::Holding).then(|| now - 72_000),
        ..WireCall::default()
    }
}

fn child(id: i32, who: (&str, &str, i64), parent: i32, ago_s: i64) -> WireCall {
    WireCall {
        parent_id: Some(parent),
        caps: caps::SPLIT | caps::DISCONNECT_PARTICIPANT,
        ..person(id, who, WireCallState::Active, ago_s, 0)
    }
}

fn host(id: i32, children: Vec<i32>, ago_s: i64, with_swap: bool) -> WireCall {
    WireCall {
        id,
        state: WireCallState::Active,
        connect_time_ms: now_ms() - ago_s * 1000,
        can_hold: true,
        caps: HOLDABLE | caps::MANAGE | if with_swap { caps::SWAP } else { 0 },
        props: props::CONFERENCE,
        children,
        ..WireCall::default()
    }
}

/// The scene names the debug switcher and `ROVSTI_PREVIEW_SCENE` accept.
pub const SCENES: [&str; 8] = [
    "single", "two", "conf", "conf4", "hidden", "waiting", "nomerge", "three",
];

impl Backend {
    /// Replaces the fake calls by one of the design scenes.
    pub fn preview_scene(&mut self, scene: &str, cx: &mut Context<Self>) {
        let scene = scene.to_owned();
        self.preview_apply(cx, move |s| {
            s.audio.route = AudioRoute::Earpiece;
            s.audio.routes = vec![AudioRoute::Earpiece, AudioRoute::Speaker];
            s.audio.endpoints = Vec::new();
            s.audio.muted = false;
            s.pending = None;
            s.pending_failed = None;
            let merge = caps::MERGE;
            s.calls = match scene.as_str() {
                "single" => vec![person(1, AYSE, WireCallState::Active, 95, HOLDABLE)],
                "two" => vec![
                    person(1, AYSE, WireCallState::Holding, 161, HOLDABLE | merge),
                    person(2, OZGUR, WireCallState::Active, 18, HOLDABLE | merge),
                ],
                "nomerge" => vec![
                    person(1, AYSE, WireCallState::Holding, 161, HOLDABLE),
                    person(2, OZGUR, WireCallState::Active, 18, HOLDABLE),
                ],
                "conf" => vec![
                    child(1, AYSE, 9, 300),
                    child(2, OZGUR, 9, 280),
                    child(3, BURAK, 9, 120),
                    child(4, ELIF, 9, 60),
                    host(9, vec![1, 2, 3, 4], 300, false),
                ],
                "conf4" => vec![
                    child(1, AYSE, 9, 300),
                    child(2, OZGUR, 9, 280),
                    child(3, BURAK, 9, 120),
                    child(4, ELIF, 9, 60),
                    child(5, MERT, 9, 30),
                    WireCall {
                        display_name: None,
                        number_raw: "+905057776655".to_owned(),
                        normalized: "+905057776655".to_owned(),
                        contact_id: None,
                        // Read-only rows: this carrier reports no split or drop bits here.
                        caps: 0,
                        ..child(6, AYSE, 9, 12)
                    },
                    host(9, vec![1, 2, 3, 4, 5, 6], 300, false),
                ],
                "hidden" => vec![host(9, vec![21, 22, 23], 410, false)],
                "waiting" => vec![
                    person(1, AYSE, WireCallState::Active, 95, HOLDABLE),
                    person(3, BURAK, WireCallState::Ringing, 0, HOLDABLE),
                ],
                "three" => vec![
                    person(1, AYSE, WireCallState::Holding, 161, HOLDABLE),
                    person(2, OZGUR, WireCallState::Active, 18, HOLDABLE),
                    person(3, BURAK, WireCallState::Ringing, 0, HOLDABLE),
                ],
                _ => Vec::new(),
            };
            roles::assign(s, None);
            if scene == "hidden" {
                // The carrier hides the participants: a conference with a count only.
                s.can_merge = false;
            }
        });
    }

    /// Add call: the first call goes on hold, the new one dials and connects.
    pub(super) fn preview_add_call(&mut self, raw: &str, cx: &mut Context<Self>) {
        let raw = raw.to_owned();
        let id = self.calls.calls().iter().map(|c| c.id).max().unwrap_or(0) + 1;
        self.preview_apply(cx, move |s| {
            for call in s.calls.iter_mut().filter(|c| c.parent_id.is_none()) {
                if call.state == WireCallState::Active {
                    call.state = WireCallState::Holding;
                    call.hold_since_ms = Some(now_ms());
                }
            }
            s.calls.push(WireCall {
                id,
                state: WireCallState::Dialing,
                normalized: raw.clone(),
                number_raw: raw,
                can_hold: true,
                caps: HOLDABLE | caps::MERGE,
                ..WireCall::default()
            });
            roles::assign(s, None);
        });
        let task = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(ADD_CONNECTS).await;
            this.update(cx, |this, cx| {
                this.preview_apply(cx, |s| {
                    if let Some(call) = s.calls.iter_mut().find(|c| c.id == id)
                        && call.state == WireCallState::Dialing
                    {
                        call.state = WireCallState::Active;
                        call.connect_time_ms = now_ms();
                    }
                    roles::assign(s, None);
                });
            })
            .ok();
        });
        task.detach();
    }

    /// Hang-up of one call, a conference, or one participant.
    pub(super) fn preview_disconnect(&mut self, id: i32, cx: &mut Context<Self>) {
        let is_child = self.calls.call(id).is_some_and(|c| c.parent_id.is_some());
        if is_child {
            self.preview_apply(cx, move |s| drop_child(s, id, false));
        } else {
            self.preview_end_id(id, rovsti_core::wire::DisconnectCause::Local, cx);
        }
    }

    /// Answers the waiting call `id`.
    pub(super) fn preview_answer(&mut self, id: i32, how: Answer, cx: &mut Context<Self>) {
        self.preview_apply(cx, move |s| {
            let held_id = s
                .calls
                .iter()
                .find(|c| c.parent_id.is_none() && c.state == WireCallState::Holding)
                .map(|c| c.id);
            match how {
                Answer::Plain | Answer::Hold => {
                    for c in s.calls.iter_mut().filter(|c| c.parent_id.is_none()) {
                        if c.id != id && c.state == WireCallState::Active {
                            c.state = WireCallState::Holding;
                            c.hold_since_ms = Some(now_ms());
                        }
                    }
                }
                Answer::EndCurrent => {
                    let gone: Vec<i32> = s
                        .calls
                        .iter()
                        .filter(|c| {
                            c.parent_id.is_none() && c.state == WireCallState::Active && c.id != id
                        })
                        .map(|c| c.id)
                        .collect();
                    s.calls.retain(|c| {
                        !gone.contains(&c.id) && c.parent_id.is_none_or(|p| !gone.contains(&p))
                    });
                }
                Answer::EndHeld => {
                    if let Some(h) = held_id {
                        s.calls.retain(|c| c.id != h);
                    }
                    for c in s.calls.iter_mut().filter(|c| c.parent_id.is_none()) {
                        if c.id != id && c.state == WireCallState::Active {
                            c.state = WireCallState::Holding;
                            c.hold_since_ms = Some(now_ms());
                        }
                    }
                }
            }
            if let Some(call) = s.calls.iter_mut().find(|c| c.id == id) {
                call.state = WireCallState::Active;
                call.connect_time_ms = now_ms();
            }
            roles::assign(s, None);
        });
    }

    pub(super) fn preview_swap(&mut self, cx: &mut Context<Self>) {
        let keep = self.calls.snapshot().foreground_call_id;
        self.preview_apply(cx, move |s| {
            s.pending = Some(PendingOp::Swap);
            roles::assign(s, keep);
        });
        let task = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(SWAP_AFTER).await;
            this.update(cx, |this, cx| {
                this.preview_apply(cx, |s| {
                    s.pending = None;
                    for c in s.calls.iter_mut().filter(|c| c.parent_id.is_none()) {
                        match c.state {
                            WireCallState::Active => {
                                c.state = WireCallState::Holding;
                                c.hold_since_ms = Some(now_ms());
                            }
                            WireCallState::Holding => {
                                c.state = WireCallState::Active;
                                c.hold_since_ms = None;
                            }
                            _ => {}
                        }
                    }
                    roles::assign(s, None);
                });
            })
            .ok();
        });
        task.detach();
    }

    pub(super) fn preview_merge(&mut self, cx: &mut Context<Self>) {
        let keep = self.calls.snapshot().foreground_call_id;
        self.preview_apply(cx, move |s| {
            s.pending = Some(PendingOp::Merge);
            roles::assign(s, keep);
        });
        let task = cx.spawn(async move |this, cx| {
            cx.background_executor().timer(MERGE_AFTER).await;
            this.update(cx, |this, cx| {
                this.preview_apply(cx, |s| {
                    s.pending = None;
                    let ids: Vec<i32> = s
                        .calls
                        .iter()
                        .filter(|c| c.parent_id.is_none() && c.children.is_empty())
                        .map(|c| c.id)
                        .collect();
                    let host_id = 90;
                    for c in s.calls.iter_mut().filter(|c| ids.contains(&c.id)) {
                        c.parent_id = Some(host_id);
                        c.state = WireCallState::Active;
                        c.hold_since_ms = None;
                        c.caps = caps::SPLIT | caps::DISCONNECT_PARTICIPANT;
                    }
                    s.calls.push(host(host_id, ids, 1, false));
                    roles::assign(s, None);
                });
            })
            .ok();
        });
        task.detach();
    }

    pub(super) fn preview_split(&mut self, id: i32, cx: &mut Context<Self>) {
        self.preview_apply(cx, move |s| drop_child(s, id, true));
    }
}

/// Removes participant `id` from its conference. `private`: the participant becomes
/// the active private call and the rest of the conference is held (a split); otherwise
/// it simply leaves. A conference left with one person is demoted to a normal call.
fn drop_child(s: &mut CallSnapshot, id: i32, private: bool) {
    let Some(parent) = s
        .calls
        .iter()
        .find(|c| c.id == id)
        .and_then(|c| c.parent_id)
    else {
        return;
    };
    if let Some(host) = s.calls.iter_mut().find(|c| c.id == parent) {
        host.children.retain(|c| *c != id);
    }
    if private {
        if let Some(call) = s.calls.iter_mut().find(|c| c.id == id) {
            call.parent_id = None;
            call.caps = HOLDABLE | caps::MERGE;
            call.can_hold = true;
        }
        if let Some(host) = s.calls.iter_mut().find(|c| c.id == parent) {
            host.state = WireCallState::Holding;
            host.hold_since_ms = Some(now_ms());
        }
    } else {
        s.calls.retain(|c| c.id != id);
    }
    let remaining = s
        .calls
        .iter()
        .find(|c| c.id == parent)
        .map(|h| h.children.clone())
        .unwrap_or_default();
    if remaining.len() == 1 {
        let held = s.calls.iter().find(|c| c.id == parent).map(|h| h.state);
        let last = remaining[0];
        s.calls.retain(|c| c.id != parent);
        if let Some(call) = s.calls.iter_mut().find(|c| c.id == last) {
            call.parent_id = None;
            call.caps = HOLDABLE | caps::MERGE;
            call.can_hold = true;
            call.state = held.unwrap_or(WireCallState::Active);
            call.hold_since_ms = (call.state == WireCallState::Holding).then(now_ms);
        }
    }
    roles::assign(s, None);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn conf() -> CallSnapshot {
        let mut s = CallSnapshot {
            calls: vec![
                child(1, AYSE, 9, 10),
                child(2, OZGUR, 9, 10),
                child(3, BURAK, 9, 10),
                host(9, vec![1, 2, 3], 10, false),
            ],
            ..CallSnapshot::default()
        };
        roles::assign(&mut s, None);
        s
    }

    #[test]
    fn a_split_makes_the_participant_active_and_holds_the_rest() {
        let mut s = conf();
        drop_child(&mut s, 2, true);
        assert_eq!(s.foreground_call_id, Some(2));
        assert_eq!(s.background_call_id, Some(9));
        let host = s.calls.iter().find(|c| c.id == 9).expect("host");
        assert_eq!(host.state, WireCallState::Holding);
        assert_eq!(host.children, vec![1, 3]);
    }

    #[test]
    fn a_conference_of_one_is_demoted_to_a_normal_call() {
        let mut s = conf();
        drop_child(&mut s, 1, false);
        drop_child(&mut s, 2, false);
        assert!(s.calls.iter().all(|c| c.id != 9));
        let last = s.calls.iter().find(|c| c.id == 3).expect("last");
        assert_eq!(last.parent_id, None);
        assert_eq!(s.foreground_call_id, Some(3));
    }
}
