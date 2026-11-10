//! `phone.wa_arbitrate`, `phone.wa_screen`, `phone.wa_handoff_number`: which call owns the screen when a
//! WhatsApp call meets a cellular one (AD-WA5), what the WhatsApp call screen enables, and the number a
//! hand-off from a sibling app dials (AD-WA10). Stateless: the caller sends the facts.

use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_app_core::phone::wa_call;
use rovsti_core::wire::Handoff;
use rovsti_wa_proto::{
    Arbitration, CallUpdate, WaCallView, WaControl, WaScreen, WaScreenPhase, arbitrate,
};
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "phone.wa_arbitrate",
        ArbitrateReq,
        ArbitrateAns,
        arbitrate_op,
        arbitrate_cases
    ),
    op!(
        "phone.wa_screen",
        ScreenReq,
        ScreenAns,
        screen,
        screen_cases
    ),
    op!(
        "phone.wa_handoff_number",
        Handoff,
        HandoffAns,
        handoff_number,
        handoff_cases
    ),
];

#[derive(Deserialize)]
struct ArbitrateReq {
    telecom_live: bool,
    telecom_ringing: bool,
    #[serde(default)]
    wa: Option<CallUpdate>,
}

#[derive(Serialize)]
struct ArbitrateAns {
    /// `show_wa`, `wa_waiting`, `reject_wa_busy` or `telecom`.
    decision: &'static str,
}

fn arbitrate_op(req: ArbitrateReq) -> Result<ArbitrateAns, FfiError> {
    let view = req.wa.as_ref().map(|u| WaCallView::from_update(u, 0));
    let decision = match arbitrate(req.telecom_live, req.telecom_ringing, view.as_ref()) {
        Arbitration::ShowWa => "show_wa",
        Arbitration::WaWaiting => "wa_waiting",
        Arbitration::RejectWaBusy => "reject_wa_busy",
        Arbitration::Telecom => "telecom",
    };
    Ok(ArbitrateAns { decision })
}

fn update(phase: serde_json::Value, direction: &str) -> serde_json::Value {
    json!({"call_id": "wa-1", "peer": "905321234567@s.whatsapp.net", "peer_name": "Ayşe Yılmaz",
        "direction": direction, "phase": phase, "is_video": false})
}

fn arbitrate_cases() -> Vec<Case> {
    let ring = update(json!("ringing"), "in");
    vec![
        Case::new(json!({"telecom_live": false, "telecom_ringing": false, "wa": ring})),
        Case::named(
            "waiting",
            json!({"telecom_live": true, "telecom_ringing": false, "wa": ring}),
        ),
        Case::named(
            "busy",
            json!({"telecom_live": true, "telecom_ringing": true, "wa": ring}),
        ),
        Case::named(
            "in_progress",
            json!({"telecom_live": true, "telecom_ringing": false,
                "wa": update(json!("active"), "out")}),
        ),
        Case::named(
            "no_wa",
            json!({"telecom_live": true, "telecom_ringing": false}),
        ),
    ]
}

#[derive(Deserialize)]
struct ScreenReq {
    update: CallUpdate,
    #[serde(default)]
    speaker: bool,
    #[serde(default)]
    muted: bool,
}

#[derive(Serialize)]
struct Enabled {
    mute: bool,
    speaker: bool,
    keypad: bool,
    hold: bool,
    add_call: bool,
    end: bool,
}

#[derive(Serialize)]
struct ScreenAns {
    /// `ringing`, `calling`, `connecting`, `active` or `ended`.
    phase: &'static str,
    enabled: Enabled,
    /// What a tap on a dimmed control (and on Note) says.
    note: &'static str,
}

fn screen(req: ScreenReq) -> Result<ScreenAns, FfiError> {
    let view = WaCallView::from_update(&req.update, 0).with_muted(req.muted);
    let screen = WaScreen::from_view(&view, req.speaker);
    let phase = match screen.phase {
        WaScreenPhase::Ringing => "ringing",
        WaScreenPhase::Calling => "calling",
        WaScreenPhase::Connecting => "connecting",
        WaScreenPhase::Active => "active",
        WaScreenPhase::Ended(_) => "ended",
    };
    Ok(ScreenAns {
        phase,
        enabled: Enabled {
            mute: screen.enabled(WaControl::Mute),
            speaker: screen.enabled(WaControl::Speaker),
            keypad: screen.enabled(WaControl::Keypad),
            hold: screen.enabled(WaControl::Hold),
            add_call: screen.enabled(WaControl::AddCall),
            end: screen.enabled(WaControl::End),
        },
        note: wa_call::UNAVAILABLE_NOTE,
    })
}

fn screen_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"update": update(json!("active"), "in")})),
        Case::named("ringing", json!({"update": update(json!("ringing"), "in")})),
    ]
}

#[derive(Serialize)]
struct HandoffAns {
    /// `+<digits>`, absent for anything that is not a call to a phone JID.
    number: Option<String>,
}

fn handoff_number(req: Handoff) -> Result<HandoffAns, FfiError> {
    Ok(HandoffAns {
        number: wa_call::handoff_number(&req),
    })
}

fn handoff_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"action": "wa_call", "jid": "905321234567@s.whatsapp.net", "name": "Ayşe Yılmaz"}),
        ),
        Case::named(
            "chat",
            json!({"action": "wa_chat", "jid": "905321234567@s.whatsapp.net"}),
        ),
        Case::named("lid", json!({"action": "wa_call", "jid": "12@lid"})),
    ]
}
