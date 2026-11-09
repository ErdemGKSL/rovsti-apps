//! A render-ready description of the WhatsApp settings page for one [`LinkModel`].
//!
//! The Kotlin screen draws a [`SettingsView`] without any decision of its own: which page, which
//! rows and buttons, which text. Every button carries the [`Input`] it sends back to
//! `wa.settings_apply` (`None` for the few that are platform actions: share, paste). A button
//! with a `field` takes the text of that field: the screen puts the typed text into the input's
//! single string leaf (the `value` of `url_entered`, `phone_entered`, `key_pasted`, or the
//! `number` of a `relink` phone request).

use rovsti_wa_proto::{InstanceState, InstanceStatus, KeyInfo, LinkRequest};
use serde::{Deserialize, Serialize};

use super::settings::{
    ACTION_SHOW_KEY, ACTION_UNLINK, Input, LinkMethodChoice, LinkModel, OPTION_CONNECT_EXISTING,
    OPTION_LINK_NEW, Screen, last_used_text,
};

const ACTION_CONNECTED_APPS: &str = "Connected apps";
const ACTION_DELETE: &str = "Delete instance…";

/// One information row (label, optional value). `role` is `status` for the account row and
/// `info` otherwise; a `status` row's `state` is the snake_case instance state.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ViewRow {
    pub label: String,
    pub value: Option<String>,
    pub role: String,
    pub state: Option<String>,
}

/// `role`: `option` (a list row with a detail line), `primary`, `secondary`, `destructive`.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ViewButton {
    pub id: String,
    pub label: String,
    pub detail: Option<String>,
    pub role: String,
    pub destructive: bool,
    /// Header of the group this button starts, if any.
    pub section: Option<String>,
    pub input: Option<Input>,
    /// The id of the field whose text goes into `input`.
    pub field: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct ViewField {
    pub id: String,
    pub placeholder: String,
    /// Text the field starts with.
    pub initial: String,
    /// `url`, `phone` or `key`: the keyboard to show.
    pub keyboard: String,
}

/// One key of the connected-apps list, with `last_used_text` applied.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct ViewKey {
    pub key_id: String,
    pub label: String,
    pub detail: String,
    pub current: bool,
    pub revoke: Input,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct SettingsView {
    /// The screen's snake_case name.
    pub kind: String,
    pub title: String,
    pub notes: Vec<String>,
    pub rows: Vec<ViewRow>,
    pub buttons: Vec<ViewButton>,
    pub fields: Vec<ViewField>,
    pub qr_payload: Option<String>,
    pub code: Option<String>,
    /// The minted key, split in two lines of at most 24 characters.
    pub key_lines: Vec<String>,
    pub keys: Vec<ViewKey>,
    /// An error sentence (the `failed` page).
    pub error: Option<String>,
    /// Waiting for the gateway: show a spinner.
    pub busy: bool,
    /// What the system Back key sends; `None` leaves the settings.
    pub back: Option<Input>,
}

fn button(id: &str, label: &str, role: &str, input: Option<Input>) -> ViewButton {
    ViewButton {
        id: id.to_owned(),
        label: label.to_owned(),
        detail: None,
        role: role.to_owned(),
        destructive: role == "destructive",
        section: None,
        input,
        field: None,
    }
}

fn option(id: &str, label: &str, detail: &str, input: Input) -> ViewButton {
    ViewButton {
        detail: Some(detail.to_owned()),
        ..button(id, label, "option", Some(input))
    }
}

fn field(id: &str, placeholder: &str, keyboard: &str) -> ViewField {
    ViewField {
        id: id.to_owned(),
        placeholder: placeholder.to_owned(),
        initial: String::new(),
        keyboard: keyboard.to_owned(),
    }
}

fn with_field(mut b: ViewButton, field: &str) -> ViewButton {
    b.field = Some(field.to_owned());
    b
}

fn back_button() -> ViewButton {
    button("back", "Back", "secondary", Some(Input::Back))
}

fn state_name(state: InstanceState) -> &'static str {
    match state {
        InstanceState::Linking => "linking",
        InstanceState::Connected => "connected",
        InstanceState::Disconnected => "disconnected",
        InstanceState::LoggedOut => "logged_out",
        InstanceState::Banned => "banned",
    }
}

fn status_row(status: &InstanceStatus) -> ViewRow {
    let state = match status.state {
        InstanceState::Connected => "Connected",
        InstanceState::Linking => "Linking…",
        InstanceState::Disconnected => "Reconnecting…",
        InstanceState::LoggedOut => "Logged out",
        InstanceState::Banned => "Banned",
    };
    let who = status
        .push_name
        .clone()
        .or_else(|| {
            status
                .own_jid
                .as_deref()
                .filter(|jid| rovsti_wa_proto::is_pn_jid(jid))
                .map(|jid| format!("+{}", rovsti_wa_proto::jid_user(jid)))
        })
        .unwrap_or_else(|| "WhatsApp".to_owned());
    ViewRow {
        label: who,
        value: Some(state.to_owned()),
        role: "status".to_owned(),
        state: Some(state_name(status.state).to_owned()),
    }
}

fn key_row(info: &KeyInfo, now_ms: i64) -> ViewKey {
    let used = last_used_text(info.last_used_ms, now_ms);
    ViewKey {
        key_id: info.key_id.clone(),
        label: info.label.clone(),
        detail: if info.current {
            format!("this app · {used}")
        } else {
            used
        },
        current: info.current,
        revoke: Input::RequestRevoke(info.key_id.clone()),
    }
}

fn empty(kind: &str, title: &str) -> SettingsView {
    SettingsView {
        kind: kind.to_owned(),
        title: title.to_owned(),
        notes: Vec::new(),
        rows: Vec::new(),
        buttons: Vec::new(),
        fields: Vec::new(),
        qr_payload: None,
        code: None,
        key_lines: Vec::new(),
        keys: Vec::new(),
        error: None,
        busy: false,
        back: None,
    }
}

/// The page for `model`; `now_ms` turns key timestamps into "5 min ago".
pub fn view(model: &LinkModel, now_ms: i64) -> SettingsView {
    let mut v = build(model, now_ms);
    v.back = model.back_input();
    v
}

#[allow(clippy::too_many_lines)]
fn build(model: &LinkModel, now_ms: i64) -> SettingsView {
    match &model.screen {
        Screen::Choose => {
            let mut v = empty("choose", "WhatsApp");
            v.notes.push(
                "WhatsApp runs on your Rovsti gateway, a server you control. WhatsApp does not \
                 endorse third-party clients and may restrict accounts that use them."
                    .to_owned(),
            );
            let mut address = option("edit_url", "Address", model.gateway_url(), Input::EditUrl);
            address.section = Some("Gateway".to_owned());
            v.buttons.push(address);
            v.buttons.push(option(
                "link_new",
                OPTION_LINK_NEW,
                "Create a new instance and link it from the WhatsApp app",
                Input::ChooseLinkNew,
            ));
            v.buttons.push(option(
                "connect_existing",
                OPTION_CONNECT_EXISTING,
                "Paste a connect key shown by another Rovsti app",
                Input::ChooseConnectExisting,
            ));
            v
        }
        Screen::EnterUrl => {
            let mut v = empty("enter_url", "Gateway address");
            v.notes.push(
                "The address of your gateway, for example https://wa.example.com. Plain http is \
                 only accepted on your own network."
                    .to_owned(),
            );
            let mut f = field("url", "https://", "url");
            f.initial = model.gateway_url().to_owned();
            v.fields.push(f);
            v.buttons.push(with_field(
                button(
                    "use_address",
                    "Use this address",
                    "primary",
                    Some(Input::UrlEntered(String::new())),
                ),
                "url",
            ));
            v.buttons.push(back_button());
            v
        }
        Screen::LinkNew {
            method: LinkMethodChoice::Qr,
        } => {
            let mut v = empty("link_new", "Link WhatsApp");
            v.notes.push(
                "Scan a QR code from WhatsApp on another phone, or share it to a PC. Or get a \
                 pairing code if this phone has WhatsApp."
                    .to_owned(),
            );
            v.buttons.push(button(
                "start_qr",
                "Show a QR code",
                "primary",
                Some(Input::StartQr),
            ));
            v.buttons.push(button(
                "use_code",
                "Use a pairing code instead",
                "secondary",
                Some(Input::UsePairCode),
            ));
            v.buttons.push(back_button());
            v
        }
        Screen::LinkNew {
            method: LinkMethodChoice::PairCode,
        } => {
            let mut v = empty("link_new", "Pairing code");
            v.notes.push(
                "Enter the number of the WhatsApp account with its country code, for example \
                 +90 532 111 22 33."
                    .to_owned(),
            );
            v.fields.push(field("phone", "+90 …", "phone"));
            v.buttons.push(with_field(
                button(
                    "get_code",
                    "Get a pairing code",
                    "primary",
                    Some(Input::PhoneEntered(String::new())),
                ),
                "phone",
            ));
            v.buttons.push(back_button());
            v
        }
        Screen::ShowQr { payload } => {
            let mut v = empty("show_qr", "Scan to link");
            v.notes.push(
                "On another screen, open WhatsApp → Settings → Linked devices → Link a device and \
                 scan this code. Share it to your PC if this is the only phone."
                    .to_owned(),
            );
            v.qr_payload = Some(payload.clone());
            v.buttons
                .push(button("share_qr", "Share QR to PC…", "secondary", None));
            v.buttons
                .push(button("cancel", "Cancel", "secondary", Some(Input::Cancel)));
            v
        }
        Screen::ShowCode { code } => {
            let mut v = empty("show_code", "Pairing code");
            v.notes.push(
                "In WhatsApp → Linked devices → Link a device → Link with phone number instead, \
                 enter:"
                    .to_owned(),
            );
            v.code = Some(code.clone());
            v.buttons
                .push(button("cancel", "Cancel", "secondary", Some(Input::Cancel)));
            v
        }
        Screen::PasteKey => {
            let mut v = empty("paste_key", "Connect key");
            v.notes.push(
                "On a Rovsti app that is already connected, open WhatsApp settings, tap \"Show \
                 connect key\" and paste the key here."
                    .to_owned(),
            );
            v.fields.push(field("key", "rwk_…", "key"));
            v.buttons.push(button(
                "paste_clipboard",
                "Paste from clipboard",
                "secondary",
                None,
            ));
            v.buttons.push(with_field(
                button(
                    "connect",
                    "Connect",
                    "primary",
                    Some(Input::KeyPasted(String::new())),
                ),
                "key",
            ));
            v.buttons.push(back_button());
            v
        }
        Screen::Connecting => {
            let mut v = empty("connecting", "WhatsApp");
            v.notes.push("Connecting to WhatsApp…".to_owned());
            v.busy = true;
            v
        }
        Screen::Connected { status } => {
            let mut v = empty("connected", "WhatsApp");
            v.rows.push(status_row(status));
            v.buttons.push(button(
                "show_key",
                ACTION_SHOW_KEY,
                "secondary",
                Some(Input::RequestShareKey),
            ));
            v.buttons.push(button(
                "keys",
                ACTION_CONNECTED_APPS,
                "secondary",
                Some(Input::OpenKeys),
            ));
            v.buttons.push(button(
                "unlink",
                ACTION_UNLINK,
                "secondary",
                Some(Input::RequestUnlink),
            ));
            v.notes.push(
                "Unlinking only removes WhatsApp from this app. The instance keeps running on \
                 the gateway for your other apps."
                    .to_owned(),
            );
            v.buttons.push(button(
                "delete",
                ACTION_DELETE,
                "destructive",
                Some(Input::RequestDelete),
            ));
            v
        }
        Screen::ShareKey { key } => {
            let mut v = empty("share_key", "Connect key");
            v.notes.push(
                "Copied. Paste this key into \"Connect to an existing instance\" in another \
                 Rovsti app. Anyone with the key can use your WhatsApp, so share it only with \
                 your own apps."
                    .to_owned(),
            );
            let split = key.char_indices().nth(24).map_or(key.len(), |(i, _)| i);
            let (head, tail) = key.split_at(split);
            v.key_lines.push(head.to_owned());
            if !tail.is_empty() {
                v.key_lines.push(tail.to_owned());
            }
            v.buttons.push(button(
                "copy_key",
                "Copy again",
                "primary",
                Some(Input::CopyKey),
            ));
            v.buttons.push(back_button());
            v
        }
        Screen::Keys { keys } => {
            let mut v = empty("keys", "Connected apps");
            v.notes.push(
                "Every app that can use this WhatsApp instance. Revoking a key signs that app \
                 out. Revoking this app's own key unlinks it."
                    .to_owned(),
            );
            v.keys = keys.iter().map(|k| key_row(k, now_ms)).collect();
            v.buttons.push(back_button());
            v
        }
        Screen::LoggedOut => {
            let mut v = empty("logged_out", "Logged out");
            v.notes.push(
                "WhatsApp logged this instance out. Link it again; your other apps keep their \
                 keys."
                    .to_owned(),
            );
            v.buttons.push(button(
                "relink_qr",
                "Link again with a QR code",
                "primary",
                Some(Input::Relink(LinkRequest::Qr)),
            ));
            v.notes.push(
                "Or get a pairing code: enter the number with its country code, for example \
                 +90 532 111 22 33."
                    .to_owned(),
            );
            v.fields.push(field("phone", "+90 …", "phone"));
            v.buttons.push(with_field(
                button(
                    "relink_code",
                    "Link again with a pairing code",
                    "secondary",
                    Some(Input::Relink(LinkRequest::PhoneNumber {
                        number: String::new(),
                    })),
                ),
                "phone",
            ));
            v.buttons.push(button(
                "unlink",
                ACTION_UNLINK,
                "secondary",
                Some(Input::RequestUnlink),
            ));
            v
        }
        Screen::ConfirmDelete => {
            let mut v = empty("confirm_delete", "Delete instance");
            v.notes.push(
                "This logs WhatsApp out and deletes the instance, its history and every connect \
                 key on the gateway. All your Rovsti apps lose WhatsApp. This cannot be undone."
                    .to_owned(),
            );
            v.buttons.push(button(
                "delete_confirm",
                "Delete instance",
                "destructive",
                Some(Input::ConfirmDelete),
            ));
            v.buttons.push(back_button());
            v
        }
        Screen::ConfirmUnlink => {
            let mut v = empty("confirm_unlink", "Unlink this app");
            v.notes.push(
                "This app forgets its WhatsApp link. To use WhatsApp here again, paste a connect \
                 key from another Rovsti app or link again. Your other apps stay linked."
                    .to_owned(),
            );
            v.buttons.push(button(
                "unlink_confirm",
                "Unlink",
                "destructive",
                Some(Input::Unlink),
            ));
            v.buttons.push(back_button());
            v
        }
        Screen::ConfirmRevoke { key, .. } => {
            let mut v = empty("confirm_revoke", "Revoke key");
            v.notes.push(if key.current {
                format!(
                    "“{}” is this app's own key. Revoking it unlinks this app from WhatsApp.",
                    key.label
                )
            } else {
                format!(
                    "The app using “{}” is signed out of WhatsApp and needs a new key.",
                    key.label
                )
            });
            v.buttons.push(button(
                "revoke_confirm",
                "Revoke",
                "destructive",
                Some(Input::Revoke(key.key_id.clone())),
            ));
            v.buttons.push(back_button());
            v
        }
        Screen::Failed { message, .. } => {
            let mut v = empty("failed", "Something went wrong");
            v.error = Some(message.clone());
            v.buttons
                .push(button("retry", "Try again", "primary", Some(Input::Retry)));
            v.buttons.push(back_button());
            v
        }
    }
}

#[cfg(test)]
mod tests {
    use rovsti_wa_proto::{AppId, CreatedInstance, GatewayEvent};

    use super::*;
    use crate::wa_link::settings::{Effect, StoredLink};

    const KEY: &str = "rwk_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    fn info(id: &str, current: bool, used: Option<i64>) -> KeyInfo {
        KeyInfo {
            key_id: id.into(),
            label: format!("label {id}"),
            created_ms: 0,
            last_used_ms: used,
            current,
        }
    }

    fn status(state: InstanceState) -> InstanceStatus {
        InstanceStatus {
            instance_id: "inst1".into(),
            state,
            own_jid: Some("905321234567@s.whatsapp.net".into()),
            push_name: None,
        }
    }

    fn linked() -> LinkModel {
        LinkModel::new(
            AppId::Messages,
            Some(StoredLink {
                gateway_url: "https://wa.example.com".into(),
                instance_id: "inst1".into(),
                key: KEY.into(),
            }),
        )
    }

    /// One model per screen, reached through the machine itself.
    fn every_screen() -> Vec<LinkModel> {
        let mut out = Vec::new();
        let fresh = LinkModel::new(AppId::Messages, None);
        out.push(fresh.clone());
        for input in [
            Input::EditUrl,
            Input::ChooseLinkNew,
            Input::UsePairCode,
            Input::ChooseConnectExisting,
            Input::KeyPasted("bad".into()),
            Input::KeyPasted(KEY.into()),
        ] {
            let mut m = fresh.clone();
            m.apply(input);
            out.push(m);
        }
        let mut m = fresh.clone();
        m.apply(Input::Created(CreatedInstance {
            instance_id: "inst1".into(),
            key: KEY.into(),
            key_id: "k1".into(),
        }));
        m.screen = Screen::Connecting;
        let mut qr = m.clone();
        qr.apply(Input::Event(GatewayEvent::Qr {
            payload: "2@abc".into(),
            valid_for_ms: 20_000,
        }));
        out.push(qr);
        let mut code = m.clone();
        code.apply(Input::Event(GatewayEvent::PairCode {
            code: "ABCD-1234".into(),
            valid_for_ms: 60_000,
        }));
        out.push(code);

        let mut connected = linked();
        connected.apply(Input::StatusOk(status(InstanceState::Connected)));
        out.push(connected.clone());
        let mut share = connected.clone();
        share.apply(Input::ShowKey(rovsti_wa_proto::IssuedKey {
            key_id: "k2".into(),
            key: KEY.into(),
            label: "Messages share".into(),
        }));
        out.push(share);
        let mut keys = connected.clone();
        keys.apply(Input::OpenKeys);
        keys.apply(Input::KeysLoaded(vec![
            info("a", true, Some(1_000_000)),
            info("b", false, None),
        ]));
        out.push(keys.clone());
        let mut revoke = keys.clone();
        revoke.apply(Input::RequestRevoke("b".into()));
        out.push(revoke);
        let mut logged_out = linked();
        logged_out.apply(Input::StatusOk(status(InstanceState::LoggedOut)));
        out.push(logged_out);
        for input in [Input::RequestDelete, Input::RequestUnlink] {
            let mut m = connected.clone();
            m.apply(input);
            out.push(m);
        }
        let mut failed = connected;
        failed.apply(Input::Error("no route to host".into()));
        out.push(failed);
        out
    }

    #[test]
    fn view_for_every_screen() {
        let mut kinds = std::collections::BTreeSet::new();
        for model in every_screen() {
            let v = view(&model, 2_000_000);
            assert!(!v.title.is_empty(), "{:?}", model.screen);
            assert!(
                v.busy || !v.buttons.is_empty() || !v.keys.is_empty() || !v.notes.is_empty(),
                "{:?}",
                model.screen
            );
            // Every input a button carries is one the machine accepts without panicking.
            for b in &v.buttons {
                let mut m = model.clone();
                if let Some(input) = b.input.clone() {
                    m.apply(input);
                }
            }
            kinds.insert(v.kind);
        }
        for kind in [
            "choose",
            "enter_url",
            "link_new",
            "show_qr",
            "show_code",
            "paste_key",
            "connecting",
            "connected",
            "share_key",
            "keys",
            "confirm_revoke",
            "logged_out",
            "confirm_delete",
            "confirm_unlink",
            "failed",
        ] {
            assert!(kinds.contains(kind), "no view of {kind}");
        }
    }

    #[test]
    fn keys_view_applies_last_used_text() {
        let mut m = linked();
        m.screen = Screen::Keys {
            keys: vec![info("a", true, Some(0)), info("b", false, None)],
        };
        let v = view(&m, 5 * 60_000);
        assert_eq!(v.keys[0].detail, "this app · 5 min ago");
        assert_eq!(v.keys[1].detail, "never used");
        assert_eq!(v.keys[1].revoke, Input::RequestRevoke("b".into()));
    }

    #[test]
    fn share_key_splits_in_two_lines() {
        let mut m = linked();
        m.screen = Screen::ShareKey { key: KEY.into() };
        let v = view(&m, 0);
        assert_eq!(v.key_lines.concat(), KEY);
        assert_eq!(v.key_lines.len(), 2);
    }

    #[test]
    fn choose_has_exactly_the_two_options() {
        let v = view(&LinkModel::new(AppId::Phone, None), 0);
        let options: Vec<_> = v
            .buttons
            .iter()
            .filter(|b| b.id != "edit_url")
            .map(|b| b.label.as_str())
            .collect();
        assert_eq!(options, [OPTION_LINK_NEW, OPTION_CONNECT_EXISTING]);
    }

    #[test]
    fn start_of_a_linked_app_verifies() {
        let mut m = linked();
        let effects = m.start();
        assert!(matches!(effects.as_slice(), [Effect::Verify(_)]));
        assert_eq!(view(&m, 0).kind, "connecting");
    }
}
