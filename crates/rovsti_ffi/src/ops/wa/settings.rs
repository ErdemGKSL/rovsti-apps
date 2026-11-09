//! `wa.settings_apply {model?, link?, app, now_ms, input?} -> {model, effects, view}`: the WhatsApp settings
//! state machine (`rovsti_app_core::wa_link::settings`), stateless.
//!
//! The caller keeps `model` (as JSON, so a recreated process restores the page) and sends it back with the
//! next `input`. Without a model a new one starts for `app` (with the stored `link` when the app has one, and
//! the machine's `start` effects run: a linked app verifies its key). Without an input the model is only
//! viewed. A model or input that does not decode is `invalid`. The gateway URL check and the `rwk_` key check
//! happen inside `apply` (inputs `url_entered`, `key_pasted`), so a bad one answers a `failed` page.

use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_app_core::wa_link::settings::{Effect, Input, LinkModel, StoredLink};
use rovsti_app_core::wa_link::settings_view::{SettingsView, view};
use rovsti_wa_proto::{
    AppId, CreatedInstance, GatewayEvent, InstanceState, InstanceStatus, IssuedKey, KeyInfo,
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[op!(
    "wa.settings_apply",
    ApplyReq,
    ApplyAns,
    settings_apply,
    cases
)];

#[derive(Deserialize)]
struct ApplyReq {
    #[serde(default)]
    model: Option<Value>,
    #[serde(default)]
    link: Option<StoredLink>,
    app: AppId,
    now_ms: i64,
    #[serde(default)]
    input: Option<Value>,
}

#[derive(Serialize)]
struct ApplyAns {
    model: LinkModel,
    effects: Vec<Effect>,
    view: SettingsView,
}

fn settings_apply(req: ApplyReq) -> Result<ApplyAns, FfiError> {
    let (mut model, mut effects) = match req.model {
        Some(value) => (
            serde_json::from_value::<LinkModel>(value)
                .map_err(|e| FfiError::invalid(format!("model: {e}")))?,
            Vec::new(),
        ),
        None => {
            let mut model = LinkModel::new(req.app, req.link);
            let effects = model.start();
            (model, effects)
        }
    };
    if let Some(value) = req.input {
        let input = serde_json::from_value::<Input>(value)
            .map_err(|e| FfiError::invalid(format!("input: {e}")))?;
        effects.extend(model.apply(input));
    }
    let view = view(&model, req.now_ms);
    Ok(ApplyAns {
        model,
        effects,
        view,
    })
}

const KEY: &str = "rwk_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
const NOW: i64 = 1_760_000_000_000;

fn status(state: InstanceState) -> InstanceStatus {
    InstanceStatus {
        instance_id: "inst1".into(),
        state,
        own_jid: Some("905321234567@s.whatsapp.net".into()),
        push_name: Some("Ada".into()),
    }
}

fn link() -> StoredLink {
    StoredLink {
        gateway_url: "https://wa.example.com".into(),
        instance_id: "inst1".into(),
        key: KEY.into(),
    }
}

fn model_json(model: &LinkModel) -> Value {
    serde_json::to_value(model).expect("model json")
}

fn input_json(input: &Input) -> Value {
    serde_json::to_value(input).expect("input json")
}

fn fresh() -> LinkModel {
    LinkModel::new(AppId::Messages, None)
}

fn linked() -> LinkModel {
    LinkModel::new(AppId::Messages, Some(link()))
}

fn connected() -> LinkModel {
    let mut m = linked();
    m.apply(Input::StatusOk(status(InstanceState::Connected)));
    m
}

/// A request that ends on the screen `label` names, each reached through a real transition.
fn step(label: &'static str, model: Option<&LinkModel>, input: Input) -> Case {
    let mut request = json!({"app": "messages", "now_ms": NOW, "input": input_json(&input)});
    if let Some(model) = model {
        request["model"] = model_json(model);
    }
    Case::named(label, request)
}

fn cases() -> Vec<Case> {
    let mut waiting = fresh();
    waiting.apply(Input::StartQr);
    waiting.apply(Input::Created(CreatedInstance {
        instance_id: "inst1".into(),
        key: KEY.into(),
        key_id: "k1".into(),
    }));
    let mut keys = connected();
    keys.apply(Input::OpenKeys);
    let key = |id: &str, current: bool, last: Option<i64>| KeyInfo {
        key_id: id.into(),
        label: format!("Messages {id}"),
        created_ms: NOW - 86_400_000,
        last_used_ms: last,
        current,
    };
    keys.apply(Input::KeysLoaded(vec![
        key("a", true, Some(NOW - 120_000)),
        key("b", false, None),
    ]));
    let mut confirm = connected();
    confirm.apply(Input::RequestDelete);
    vec![
        // The main case: a fresh model, no input.
        Case::new(json!({"app": "messages", "now_ms": NOW})),
        Case::named(
            "linked_start",
            json!({"app": "messages", "now_ms": NOW, "link": link()}),
        ),
        step("enter_url", Some(&fresh()), Input::EditUrl),
        step(
            "bad_url",
            Some(&fresh()),
            Input::UrlEntered("ftp://x".into()),
        ),
        step("link_new_qr", Some(&fresh()), Input::ChooseLinkNew),
        step("link_new_code", Some(&fresh()), Input::UsePairCode),
        step("paste_key", Some(&fresh()), Input::ChooseConnectExisting),
        step("bad_key", Some(&fresh()), Input::KeyPasted("nope".into())),
        step("connecting", Some(&fresh()), Input::KeyPasted(KEY.into())),
        step(
            "show_qr",
            Some(&waiting),
            Input::Event(GatewayEvent::Qr {
                payload: "2@qr-payload".into(),
                valid_for_ms: 20_000,
            }),
        ),
        step(
            "show_code",
            Some(&waiting),
            Input::Event(GatewayEvent::PairCode {
                code: "ABCD-1234".into(),
                valid_for_ms: 60_000,
            }),
        ),
        step(
            "connected",
            Some(&linked()),
            Input::StatusOk(status(InstanceState::Connected)),
        ),
        step(
            "share_key",
            Some(&connected()),
            Input::ShowKey(IssuedKey {
                key_id: "k2".into(),
                key: KEY.into(),
                label: "Messages share".into(),
            }),
        ),
        step("keys", Some(&connected()), Input::OpenKeys),
        step(
            "keys_loaded",
            Some(&{
                let mut m = connected();
                m.apply(Input::OpenKeys);
                m
            }),
            Input::KeysLoaded(vec![
                key("a", true, Some(NOW - 120_000)),
                key("b", false, None),
            ]),
        ),
        step(
            "confirm_revoke",
            Some(&keys),
            Input::RequestRevoke("b".into()),
        ),
        step(
            "logged_out",
            Some(&linked()),
            Input::StatusOk(status(InstanceState::LoggedOut)),
        ),
        step("confirm_delete", Some(&connected()), Input::RequestDelete),
        step("confirm_unlink", Some(&connected()), Input::RequestUnlink),
        step("confirm_unlink_back", Some(&confirm), Input::Back),
        step(
            "failed",
            Some(&connected()),
            Input::Error("no route to host".into()),
        ),
        Case::named(
            "bad_model",
            json!({"app": "messages", "now_ms": NOW, "model": {"screen": 7}}),
        ),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run(request: Value) -> Result<Value, FfiError> {
        (crate::ops::registry::find("wa.settings_apply")
            .expect("registered")
            .run)(request.to_string().as_bytes())
    }

    #[test]
    fn a_walk_through_the_machine_is_stateless() {
        let a = run(json!({"app": "phone", "now_ms": 0})).unwrap();
        assert_eq!(a["view"]["kind"], "choose");
        let b = run(json!({
            "app": "phone", "now_ms": 0, "model": a["model"],
            "input": {"kind": "choose_link_new"}
        }))
        .unwrap();
        assert_eq!(b["view"]["kind"], "link_new");
        let c = run(json!({
            "app": "phone", "now_ms": 0, "model": b["model"],
            "input": {"kind": "start_qr"}
        }))
        .unwrap();
        assert_eq!(c["view"]["kind"], "connecting");
        assert_eq!(c["effects"][0]["kind"], "create_instance");
    }

    #[test]
    fn undecodable_model_or_input_is_invalid() {
        let e = run(json!({"app": "phone", "now_ms": 0, "model": 5})).unwrap_err();
        assert_eq!(e.kind, crate::envelope::ErrorKind::Invalid);
        let e = run(json!({"app": "phone", "now_ms": 0, "input": {"kind": "nope"}})).unwrap_err();
        assert_eq!(e.kind, crate::envelope::ErrorKind::Invalid);
    }

    #[test]
    fn url_and_key_validation_run_inside_apply() {
        let bad = run(json!({
            "app": "phone", "now_ms": 0,
            "input": {"kind": "url_entered", "value": "ftp://x"}
        }))
        .unwrap();
        assert_eq!(bad["view"]["kind"], "failed");
        let key = run(json!({
            "app": "phone", "now_ms": 0,
            "input": {"kind": "key_pasted", "value": "nope"}
        }))
        .unwrap();
        assert_eq!(key["view"]["kind"], "failed");
        assert!(key["effects"].as_array().unwrap().is_empty());
    }
}
