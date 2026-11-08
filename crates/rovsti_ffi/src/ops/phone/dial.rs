//! `dial.*`: the dial string rules that need no per-keystroke state.
//!
//! Typing into the field (`dial::push`, backspace, long presses, font steps) is Kotlin; these ops answer once per
//! event: a paste, a `tel:` link, a field menu, a redial.

use super::entries;
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_core::dial::{self, DialError, FieldAction, PastePlan, TonePlan};
use rovsti_core::wire::{CallLogEntry, EmergencyList};
use serde::{Deserialize, Serialize};
use serde_json::json;

pub(super) const OPS: &[OpSpec] = &[
    op!(
        "dial.normalise_paste",
        TextReq,
        TextAns,
        normalise_paste,
        normalise_cases
    ),
    op!(
        "dial.from_tel_uri",
        UriReq,
        TextAns,
        from_tel_uri,
        uri_cases
    ),
    op!("dial.validate", TextReq, TextAns, validate, validate_cases),
    op!("dial.field_menu", MenuReq, MenuAns, field_menu, menu_cases),
    op!(
        "dial.extract_numbers",
        TextReq,
        NumbersAns,
        extract_numbers,
        extract_cases
    ),
    op!("dial.paste_plan", TextReq, PlanAns, paste_plan, plan_cases),
    op!(
        "dial.redial_target",
        RedialReq,
        RedialAns,
        redial_target,
        redial_cases
    ),
    op!("dial.tone_plan", TextReq, TonePlan, tone_plan, tone_cases),
    op!(
        "dial.tones_text",
        TextReq,
        TextAns,
        tones_text,
        tones_text_cases
    ),
    op!(
        "dial.tone_preview",
        TextReq,
        TextAns,
        tone_preview,
        preview_cases
    ),
    op!(
        "dial.paste_tones_label",
        ClipReq,
        LabelAns,
        paste_tones_label,
        label_cases
    ),
    op!(
        "dial.is_emergency",
        EmergencyReq,
        EmergencyAns,
        is_emergency,
        emergency_cases
    ),
];

#[derive(Deserialize)]
struct TextReq {
    text: String,
}

#[derive(Serialize, Deserialize)]
struct TextAns {
    text: String,
}

fn text(text: impl Into<String>) -> Result<TextAns, FfiError> {
    Ok(TextAns { text: text.into() })
}

fn normalise_paste(req: TextReq) -> Result<TextAns, FfiError> {
    text(dial::normalise_paste(&req.text))
}

fn normalise_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": " +90 (532) 123-45-67\n"})),
        Case::named("encoded", json!({"text": "%2B90532%23"})),
    ]
}

#[derive(Deserialize)]
struct UriReq {
    uri: String,
}

fn from_tel_uri(req: UriReq) -> Result<TextAns, FfiError> {
    text(dial::from_tel_uri(&req.uri))
}

fn uri_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"uri": "tel:%2B905321234567;phone-context=x"})),
        Case::named("ussd", json!({"uri": "tel:*%23123%23"})),
        Case::named("empty", json!({"uri": "tel:"})),
    ]
}

fn validate(req: TextReq) -> Result<TextAns, FfiError> {
    match dial::validate(&req.text) {
        Ok(ok) => text(ok),
        Err(error) => {
            let reason = match error {
                DialError::Empty => "empty",
                DialError::NoDialable => "no_dialable",
            };
            Err(FfiError::invalid(error.to_string()).with_detail(json!({"reason": reason})))
        }
    }
}

fn validate_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "0532 123 45 67"})),
        Case::named("empty", json!({"text": ""})),
        Case::named("no_dialable", json!({"text": "abc"})),
    ]
}

#[derive(Deserialize)]
struct MenuReq {
    dialed: String,
    clipboard_has_text: bool,
}

#[derive(Serialize)]
struct MenuItem {
    action: FieldAction,
    label: &'static str,
    /// The pill after the action, when it has feedback of its own (Paste reports through its own plan).
    done_text: Option<&'static str>,
}

#[derive(Serialize)]
struct MenuAns {
    items: Vec<MenuItem>,
}

fn field_menu(req: MenuReq) -> Result<MenuAns, FfiError> {
    let items = dial::field_menu(&req.dialed, req.clipboard_has_text)
        .into_iter()
        .map(|action| MenuItem {
            action,
            label: action.label(),
            done_text: match action {
                FieldAction::Paste => None,
                other => Some(dial::field_done_text(other)),
            },
        })
        .collect();
    Ok(MenuAns { items })
}

fn menu_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"dialed": "0532", "clipboard_has_text": true})),
        Case::named(
            "empty_field",
            json!({"dialed": "", "clipboard_has_text": true}),
        ),
        Case::named(
            "nothing",
            json!({"dialed": "", "clipboard_has_text": false}),
        ),
    ]
}

#[derive(Serialize)]
struct NumbersAns {
    numbers: Vec<String>,
}

fn extract_numbers(req: TextReq) -> Result<NumbersAns, FfiError> {
    Ok(NumbersAns {
        numbers: dial::extract_numbers(&req.text),
    })
}

fn extract_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "Ara: 0532 123 45 67 veya +90 212 555 01 02. Kod 12"})),
        Case::named("none", json!({"text": "no digits here"})),
    ]
}

#[derive(Serialize)]
struct PlanAns {
    plan: PastePlan,
}

fn paste_plan(req: TextReq) -> Result<PlanAns, FfiError> {
    Ok(PlanAns {
        plan: dial::paste_plan(&req.text),
    })
}

fn plan_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "0532 123 45 67"})),
        Case::named("choose", json!({"text": "0532 123 45 67 / 0212 555 01 02"})),
        Case::named("nothing", json!({"text": "hello"})),
    ]
}

#[derive(Deserialize)]
struct RedialReq {
    entries: Vec<CallLogEntry>,
}

#[derive(Serialize)]
struct RedialAns {
    number: Option<String>,
}

fn redial_target(req: RedialReq) -> Result<RedialAns, FfiError> {
    Ok(RedialAns {
        number: dial::redial_target(&req.entries),
    })
}

fn redial_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"entries": entries()})),
        Case::named("empty", json!({"entries": []})),
    ]
}

fn tone_plan(req: TextReq) -> Result<TonePlan, FfiError> {
    Ok(dial::tone_plan(&req.text))
}

fn tone_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"text": "1234,56#"})),
        Case::named("empty", json!({"text": ""})),
    ]
}

fn tones_text(req: TextReq) -> Result<TextAns, FfiError> {
    text(dial::tones_text(&dial::tone_plan(&req.text)))
}

fn tones_text_cases() -> Vec<Case> {
    vec![Case::new(json!({"text": "1234,56#"}))]
}

fn tone_preview(req: TextReq) -> Result<TextAns, FfiError> {
    text(dial::tone_preview(&dial::tone_plan(&req.text)))
}

fn preview_cases() -> Vec<Case> {
    vec![Case::new(json!({"text": "1234,56#"}))]
}

#[derive(Deserialize)]
struct ClipReq {
    #[serde(default)]
    clip: Option<String>,
}

#[derive(Serialize)]
struct LabelAns {
    label: Option<String>,
}

fn paste_tones_label(req: ClipReq) -> Result<LabelAns, FfiError> {
    Ok(LabelAns {
        label: dial::paste_tones_label(req.clip.as_deref()),
    })
}

fn label_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"clip": "12,34"})),
        Case::named("none", json!({"clip": null})),
    ]
}

#[derive(Deserialize)]
struct EmergencyReq {
    typed: String,
    #[serde(default)]
    emergency: EmergencyList,
}

#[derive(Serialize)]
struct EmergencyAns {
    emergency: bool,
}

fn is_emergency(req: EmergencyReq) -> Result<EmergencyAns, FfiError> {
    Ok(EmergencyAns {
        emergency: dial::is_emergency(&req.typed, &req.emergency),
    })
}

fn emergency_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"typed": "112"})),
        Case::named(
            "local",
            json!({"typed": "155", "emergency": {"numbers": ["155"]}}),
        ),
        Case::named("ordinary", json!({"typed": "0532"})),
    ]
}
