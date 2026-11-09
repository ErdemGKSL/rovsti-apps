//! The pure state machine behind the WhatsApp settings screen.
//!
//! No GPUI and no I/O: [`LinkModel::apply`] takes an [`Input`] (a tap, a pasted
//! text, a gateway answer) and returns the [`Effect`]s the view must run. The
//! first screen has exactly two options: [`OPTION_LINK_NEW`] and
//! [`OPTION_CONNECT_EXISTING`].

pub use super::link::{LINK_FILE, StoredLink};
use rovsti_wa_proto::{
    AppId, ConnectKey, CreateInstance, CreatedInstance, GatewayEvent, GatewayUrl, InstanceState,
    InstanceStatus, IssuedKey, KeyInfo, LinkRequest,
};
use serde::{Deserialize, Serialize};

/// What a fresh install proposes: the owner's gateway behind the Cloudflare
/// tunnel. Another address can be typed once and is remembered. (The Android
/// emulator reaches a gateway on the host as `http://10.0.2.2:8787`.)
pub const DEFAULT_GATEWAY_URL: &str = "https://rovsti.erdemdev.tr";

pub const OPTION_LINK_NEW: &str = "Link WhatsApp for the first time";
pub const OPTION_CONNECT_EXISTING: &str = "Connect to an existing instance";
pub const ACTION_SHOW_KEY: &str = "Show connect key";
pub const ACTION_UNLINK: &str = "Unlink this app";

pub const MSG_KEY_REJECTED: &str = "This key was revoked or is wrong";
pub const MSG_BANNED: &str = "WhatsApp has banned this account from linked devices.";

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum LinkMethodChoice {
    Qr,
    PairCode,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Screen {
    /// The two options.
    Choose,
    EnterUrl,
    /// Pick QR or a pairing code (which needs the phone number).
    LinkNew {
        method: LinkMethodChoice,
    },
    ShowQr {
        payload: String,
    },
    ShowCode {
        code: String,
    },
    PasteKey,
    /// Waiting for the gateway.
    Connecting,
    Connected {
        status: InstanceStatus,
    },
    ShareKey {
        key: String,
    },
    /// The apps that hold a connect key of this instance.
    Keys {
        keys: Vec<KeyInfo>,
    },
    /// WhatsApp logged the instance out; it can be linked again.
    LoggedOut,
    /// "Delete instance" waits for a second tap.
    ConfirmDelete,
    /// "Unlink this app" waits for a second tap; Back returns to the
    /// connected (or logged-out) page.
    ConfirmUnlink,
    /// Revoking `key` waits for a second tap; Back returns to the list.
    ConfirmRevoke {
        key: KeyInfo,
        keys: Vec<KeyInfo>,
    },
    Failed {
        message: String,
        back: Box<Screen>,
    },
}

// An input is built, moved into `apply` once and dropped; it is never stored
// in bulk, so the size of the gateway event variant costs nothing worth a box
// (and a box would change the public variant).
#[allow(clippy::large_enum_variant)]
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "value", rename_all = "snake_case")]
pub enum Input {
    ChooseLinkNew,
    ChooseConnectExisting,
    EditUrl,
    UrlEntered(String),
    UsePairCode,
    StartQr,
    PhoneEntered(String),
    KeyPasted(String),
    Created(CreatedInstance),
    Event(GatewayEvent),
    StatusOk(InstanceStatus),
    Unauthorized,
    Error(String),
    RequestShareKey,
    ShowKey(IssuedKey),
    CopyKey,
    /// "Unlink this app": asks first ([`Screen::ConfirmUnlink`]).
    RequestUnlink,
    Unlink,
    /// "Revoke" on a key of the list: asks first ([`Screen::ConfirmRevoke`]).
    RequestRevoke(String),
    /// Leave a form screen.
    Back,
    /// Give up a half-made link (QR or code on screen) and delete its instance.
    Cancel,
    /// "Try again" on a failure.
    Retry,
    OpenKeys,
    KeysLoaded(Vec<KeyInfo>),
    /// Revoke the key with this id.
    Revoke(String),
    Revoked(String),
    /// "Link again" on the logged-out page.
    Relink(LinkRequest),
    RequestDelete,
    ConfirmDelete,
    Deleted,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "value", rename_all = "snake_case")]
pub enum Effect {
    /// `POST /v1/instances` on the gateway.
    CreateInstance {
        gateway_url: String,
        request: CreateInstance,
    },
    /// `GET /v1/instance` with the key; the answer is `StatusOk`, `Unauthorized` or `Error`.
    Verify(StoredLink),
    Save(StoredLink),
    /// Delete the saved link and stop listening.
    Forget,
    /// Open the event stream.
    Subscribe(StoredLink),
    /// Best effort: delete an instance this app created and abandoned.
    AbandonInstance(StoredLink),
    /// `POST /v1/keys`, labelled with this text.
    MintKey(String),
    CopyToClipboard(String),
    /// `GET /v1/keys`; the answer is `KeysLoaded`.
    ListKeys,
    /// `DELETE /v1/keys/{id}`; the answer is `Revoked(id)`.
    RevokeKey(String),
    /// `POST /v1/instance/link` on the same instance. Its answer arrives as events.
    Relink {
        link: StoredLink,
        request: LinkRequest,
    },
    /// `DELETE /v1/instance`; the answer is `Deleted`.
    DeleteInstance(StoredLink),
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct LinkModel {
    app: AppId,
    pub screen: Screen,
    pub link: Option<StoredLink>,
    pub status: Option<InstanceStatus>,
    gateway_url: String,
    /// A pasted key being verified.
    pending_key: Option<String>,
    /// A QR or code on screen belongs to a relink of a shared instance, which
    /// "Cancel" must not delete.
    relinking: bool,
}

fn app_name(app: AppId) -> &'static str {
    match app {
        AppId::Phone => "Phone",
        AppId::Messages => "Messages",
        AppId::Contacts => "Contacts",
        AppId::Stops => "Stops",
    }
}

/// "never used", "just now", "5 min ago", "3 h ago", "2 d ago".
pub fn last_used_text(last_used_ms: Option<i64>, now_ms: i64) -> String {
    let Some(then) = last_used_ms else {
        return "never used".to_owned();
    };
    let minutes = (now_ms - then).max(0) / 60_000;
    match minutes {
        0 => "just now".to_owned(),
        1..=59 => format!("{minutes} min ago"),
        60..=1439 => format!("{} h ago", minutes / 60),
        _ => format!("{} d ago", minutes / 1440),
    }
}

/// The label of a key minted for sharing, as the key list shows it.
pub fn share_label(app: AppId) -> String {
    format!("{} share", app_name(app))
}

fn failed(message: impl Into<String>, back: Screen) -> Screen {
    Screen::Failed {
        message: message.into(),
        back: Box::new(back),
    }
}

impl LinkModel {
    pub fn new(app: AppId, link: Option<StoredLink>) -> Self {
        let gateway_url = link
            .as_ref()
            .map_or_else(|| DEFAULT_GATEWAY_URL.to_owned(), |l| l.gateway_url.clone());
        Self {
            app,
            screen: Screen::Choose,
            link,
            status: None,
            gateway_url,
            pending_key: None,
            relinking: false,
        }
    }

    pub fn app(&self) -> AppId {
        self.app
    }

    pub fn gateway_url(&self) -> &str {
        &self.gateway_url
    }

    /// Called once the saved link is loaded: a linked app checks its key.
    pub fn start(&mut self) -> Vec<Effect> {
        match self.link.clone() {
            Some(link) => {
                self.screen = Screen::Connecting;
                vec![Effect::Verify(link)]
            }
            None => Vec::new(),
        }
    }

    /// What the system Back key does on the current screen: a step back inside the
    /// settings, or cancelling a link in progress (the on-screen Cancel). `None` on the
    /// screens that are the top of the settings (the two options, the connected page), so
    /// Back leaves them like it leaves any other page.
    pub fn back_input(&self) -> Option<Input> {
        match self.screen {
            Screen::EnterUrl
            | Screen::LinkNew { .. }
            | Screen::PasteKey
            | Screen::ShareKey { .. }
            | Screen::Keys { .. }
            | Screen::ConfirmDelete
            | Screen::ConfirmUnlink
            | Screen::ConfirmRevoke { .. }
            | Screen::Failed { .. } => Some(Input::Back),
            Screen::ShowQr { .. } | Screen::ShowCode { .. } => Some(Input::Cancel),
            Screen::Choose | Screen::Connecting | Screen::Connected { .. } | Screen::LoggedOut => {
                None
            }
        }
    }

    /// The instance is connected and its events are flowing.
    pub fn is_connected(&self) -> bool {
        self.link.is_some()
            && self
                .status
                .as_ref()
                .is_some_and(|s| s.state == InstanceState::Connected)
    }

    /// Where "back" lands: the connected page when linked, else the two options.
    fn home(&self) -> Screen {
        match (&self.link, &self.status) {
            (Some(_), Some(status)) if status.state == InstanceState::LoggedOut => {
                Screen::LoggedOut
            }
            (Some(_), Some(status)) => Screen::Connected {
                status: status.clone(),
            },
            (Some(link), None) => Screen::Connected {
                status: InstanceStatus {
                    instance_id: link.instance_id.clone(),
                    state: InstanceState::Disconnected,
                    own_jid: None,
                    push_name: None,
                },
            },
            (None, _) => Screen::Choose,
        }
    }

    fn drop_link(&mut self) {
        self.link = None;
        self.status = None;
        self.pending_key = None;
        self.relinking = false;
    }

    /// Shows what the instance is doing. `Linking` keeps waiting for a QR.
    fn show_status(&mut self, status: InstanceStatus) {
        if status.state == InstanceState::Connected {
            self.relinking = false;
        }
        self.screen = match status.state {
            InstanceState::LoggedOut => Screen::LoggedOut,
            InstanceState::Linking => match self.screen {
                Screen::ShowQr { .. } | Screen::ShowCode { .. } => return,
                _ => Screen::Connecting,
            },
            _ => Screen::Connected {
                status: status.clone(),
            },
        };
        self.status = Some(status);
    }

    pub fn apply(&mut self, input: Input) -> Vec<Effect> {
        match input {
            Input::ChooseLinkNew => {
                self.screen = Screen::LinkNew {
                    method: LinkMethodChoice::Qr,
                };
                Vec::new()
            }
            Input::UsePairCode => {
                self.screen = Screen::LinkNew {
                    method: LinkMethodChoice::PairCode,
                };
                Vec::new()
            }
            Input::ChooseConnectExisting => {
                self.screen = Screen::PasteKey;
                Vec::new()
            }
            Input::EditUrl => {
                self.screen = Screen::EnterUrl;
                Vec::new()
            }
            Input::UrlEntered(text) => {
                match GatewayUrl::parse(&text) {
                    Ok(url) => {
                        self.gateway_url = url.as_str();
                        self.screen = Screen::Choose;
                    }
                    Err(error) => self.screen = failed(error.to_string(), Screen::EnterUrl),
                }
                Vec::new()
            }
            Input::StartQr => self.create(LinkRequest::Qr),
            Input::PhoneEntered(number) => {
                let request = LinkRequest::PhoneNumber { number };
                let back = Screen::LinkNew {
                    method: LinkMethodChoice::PairCode,
                };
                match request.pair_phone() {
                    Ok(_) => self.create(request),
                    Err(error) => {
                        self.screen = failed(error.to_string(), back);
                        Vec::new()
                    }
                }
            }
            Input::KeyPasted(text) => match ConnectKey::parse(&text) {
                Ok(key) => {
                    let key = key.as_str().to_owned();
                    self.pending_key = Some(key.clone());
                    self.screen = Screen::Connecting;
                    vec![Effect::Verify(StoredLink {
                        gateway_url: self.gateway_url.clone(),
                        instance_id: String::new(),
                        key,
                    })]
                }
                Err(error) => {
                    self.screen = failed(error.to_string(), Screen::PasteKey);
                    Vec::new()
                }
            },
            Input::Created(created) => self.created(created),
            Input::StatusOk(status) => self.status_ok(status),
            Input::Event(event) => self.event(event),
            Input::Unauthorized => {
                if self.pending_key.take().is_some() {
                    self.screen = failed(MSG_KEY_REJECTED, Screen::PasteKey);
                    Vec::new()
                } else {
                    // The saved key stopped working: the link is useless.
                    self.drop_link();
                    self.screen = failed(MSG_KEY_REJECTED, Screen::Choose);
                    vec![Effect::Forget]
                }
            }
            Input::Error(message) => {
                let back = if self.pending_key.take().is_some() {
                    Screen::PasteKey
                } else if self.link.is_none() && self.screen == Screen::Connecting {
                    Screen::Choose
                } else {
                    self.home()
                };
                self.screen = failed(message, back);
                Vec::new()
            }
            Input::RequestShareKey => match self.link {
                Some(_) => vec![Effect::MintKey(share_label(self.app))],
                None => Vec::new(),
            },
            Input::ShowKey(issued) => {
                self.screen = Screen::ShareKey {
                    key: issued.key.clone(),
                };
                vec![Effect::CopyToClipboard(issued.key)]
            }
            Input::CopyKey => match &self.screen {
                Screen::ShareKey { key } => vec![Effect::CopyToClipboard(key.clone())],
                _ => Vec::new(),
            },
            Input::RequestUnlink => {
                if self.link.is_some() {
                    self.screen = Screen::ConfirmUnlink;
                }
                Vec::new()
            }
            Input::Unlink => {
                self.drop_link();
                self.screen = Screen::Choose;
                vec![Effect::Forget]
            }
            Input::RequestRevoke(key_id) => {
                if let (Screen::Keys { keys }, Some(_)) = (&self.screen, &self.link)
                    && let Some(key) = keys.iter().find(|k| k.key_id == key_id)
                {
                    self.screen = Screen::ConfirmRevoke {
                        key: key.clone(),
                        keys: keys.clone(),
                    };
                }
                Vec::new()
            }
            Input::Back => {
                match &self.screen {
                    Screen::EnterUrl | Screen::LinkNew { .. } | Screen::PasteKey => {
                        self.screen = Screen::Choose;
                    }
                    Screen::ShareKey { .. }
                    | Screen::Keys { .. }
                    | Screen::ConfirmDelete
                    | Screen::ConfirmUnlink => {
                        self.screen = self.home();
                    }
                    Screen::Failed { back, .. } => self.screen = (**back).clone(),
                    Screen::ConfirmRevoke { keys, .. } => {
                        self.screen = Screen::Keys { keys: keys.clone() };
                    }
                    _ => {}
                }
                Vec::new()
            }
            Input::Cancel => match self.screen {
                Screen::ShowQr { .. } | Screen::ShowCode { .. } if self.relinking => {
                    // The instance is shared: stay linked, offer "Link again".
                    self.relinking = false;
                    self.screen = Screen::LoggedOut;
                    Vec::new()
                }
                Screen::ShowQr { .. } | Screen::ShowCode { .. } => {
                    let abandoned = self.link.clone();
                    self.drop_link();
                    self.screen = Screen::Choose;
                    let mut effects = vec![Effect::Forget];
                    effects.extend(abandoned.map(Effect::AbandonInstance));
                    effects
                }
                _ => Vec::new(),
            },
            Input::Retry => match (&self.screen, self.link.clone()) {
                (Screen::Failed { .. }, Some(link)) if self.pending_key.is_none() => {
                    self.screen = Screen::Connecting;
                    vec![Effect::Verify(link)]
                }
                (Screen::Failed { back, .. }, _) => {
                    self.screen = (**back).clone();
                    Vec::new()
                }
                _ => Vec::new(),
            },
            Input::OpenKeys => match self.link {
                Some(_) => {
                    self.screen = Screen::Connecting;
                    vec![Effect::ListKeys]
                }
                None => Vec::new(),
            },
            Input::KeysLoaded(keys) => {
                if self.link.is_some() && self.screen == Screen::Connecting {
                    self.screen = Screen::Keys { keys };
                }
                Vec::new()
            }
            Input::Revoke(key_id) => match (&self.screen, &self.link) {
                (Screen::Keys { .. }, Some(_)) => vec![Effect::RevokeKey(key_id)],
                // Confirmed: back to the list, which `Revoked` then shrinks.
                (Screen::ConfirmRevoke { keys, .. }, Some(_)) => {
                    self.screen = Screen::Keys { keys: keys.clone() };
                    vec![Effect::RevokeKey(key_id)]
                }
                _ => Vec::new(),
            },
            Input::Revoked(key_id) => self.revoked(&key_id),
            Input::Relink(request) => self.relink(request),
            Input::RequestDelete => {
                if self.link.is_some() {
                    self.screen = Screen::ConfirmDelete;
                }
                Vec::new()
            }
            Input::ConfirmDelete => match (&self.screen, self.link.clone()) {
                (Screen::ConfirmDelete, Some(link)) => {
                    self.screen = Screen::Connecting;
                    vec![Effect::DeleteInstance(link)]
                }
                _ => Vec::new(),
            },
            Input::Deleted => {
                self.drop_link();
                self.screen = Screen::Choose;
                vec![Effect::Forget]
            }
        }
    }

    fn revoked(&mut self, key_id: &str) -> Vec<Effect> {
        let Screen::Keys { keys } = &mut self.screen else {
            return Vec::new();
        };
        if keys.iter().any(|k| k.key_id == key_id && k.current) {
            // This app's own key: the link is gone.
            self.drop_link();
            self.screen = Screen::Choose;
            return vec![Effect::Forget];
        }
        keys.retain(|k| k.key_id != key_id);
        Vec::new()
    }

    fn relink(&mut self, request: LinkRequest) -> Vec<Effect> {
        let (Screen::LoggedOut, Some(link)) = (&self.screen, self.link.clone()) else {
            return Vec::new();
        };
        if let Err(error) = request.pair_phone() {
            self.screen = failed(error.to_string(), Screen::LoggedOut);
            return Vec::new();
        }
        self.relinking = true;
        self.screen = Screen::Connecting;
        vec![Effect::Relink { link, request }]
    }

    fn create(&mut self, link: LinkRequest) -> Vec<Effect> {
        self.screen = Screen::Connecting;
        vec![Effect::CreateInstance {
            gateway_url: self.gateway_url.clone(),
            request: CreateInstance {
                link,
                app: self.app,
            },
        }]
    }

    fn created(&mut self, created: CreatedInstance) -> Vec<Effect> {
        if let Err(error) = ConnectKey::parse(&created.key) {
            self.screen = failed(
                format!("The gateway sent a bad key: {error}"),
                Screen::Choose,
            );
            return Vec::new();
        }
        let link = StoredLink {
            gateway_url: self.gateway_url.clone(),
            instance_id: created.instance_id,
            key: created.key,
        };
        self.link = Some(link.clone());
        self.status = None;
        // The key is saved at once: closing the app while the QR is on screen
        // must not orphan the instance.
        vec![Effect::Save(link.clone()), Effect::Subscribe(link)]
    }

    fn status_ok(&mut self, status: InstanceStatus) -> Vec<Effect> {
        let mut effects = Vec::new();
        if let Some(key) = self.pending_key.take() {
            let link = StoredLink {
                gateway_url: self.gateway_url.clone(),
                instance_id: status.instance_id.clone(),
                key,
            };
            self.link = Some(link.clone());
            effects.push(Effect::Save(link));
        }
        let Some(link) = self.link.clone() else {
            return effects;
        };
        // LoggedOut keeps the link: the page offers "Link again".
        if status.state == InstanceState::Banned {
            self.drop_link();
            self.screen = failed(MSG_BANNED, Screen::Choose);
            return vec![Effect::Forget];
        }
        self.show_status(status);
        effects.push(Effect::Subscribe(link));
        effects
    }

    fn event(&mut self, event: GatewayEvent) -> Vec<Effect> {
        let waiting = matches!(
            self.screen,
            Screen::Connecting | Screen::ShowQr { .. } | Screen::ShowCode { .. }
        );
        match event {
            GatewayEvent::Qr { payload, .. } if waiting && self.link.is_some() => {
                self.screen = Screen::ShowQr { payload };
                Vec::new()
            }
            GatewayEvent::PairCode { code, .. } if waiting && self.link.is_some() => {
                self.screen = Screen::ShowCode { code };
                Vec::new()
            }
            GatewayEvent::State(status) if self.link.is_some() => match status.state {
                InstanceState::LoggedOut => {
                    self.relinking = false;
                    self.show_status(status);
                    Vec::new()
                }
                InstanceState::Banned => {
                    self.drop_link();
                    self.screen = failed(MSG_BANNED, Screen::Choose);
                    vec![Effect::Forget]
                }
                _ => {
                    // A status never pulls the user out of a form they are
                    // filling in (the key on screen, an error).
                    let busy = matches!(
                        self.screen,
                        Screen::ShareKey { .. }
                            | Screen::Failed { .. }
                            | Screen::Keys { .. }
                            | Screen::ConfirmDelete
                            | Screen::ConfirmUnlink
                            | Screen::ConfirmRevoke { .. }
                    );
                    // After a logout only a real reconnection ends the page.
                    let logged_out = self.screen == Screen::LoggedOut
                        && status.state != InstanceState::Connected;
                    if busy || logged_out {
                        self.status = Some(status);
                    } else {
                        self.show_status(status);
                    }
                    Vec::new()
                }
            },
            GatewayEvent::KeyRevoked if self.link.is_some() => self.apply(Input::Unauthorized),
            _ => Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use rovsti_wa_proto::GatewayEvent;

    use super::*;

    const KEY: &str = "rwk_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    fn status(state: InstanceState) -> InstanceStatus {
        InstanceStatus {
            instance_id: "inst1".into(),
            state,
            own_jid: Some("905321234567@s.whatsapp.net".into()),
            push_name: Some("Ada".into()),
        }
    }

    fn model() -> LinkModel {
        LinkModel::new(AppId::Messages, None)
    }

    fn linked() -> LinkModel {
        let mut m = LinkModel::new(
            AppId::Messages,
            Some(StoredLink {
                gateway_url: "https://wa.example.com".into(),
                instance_id: "inst1".into(),
                key: KEY.into(),
            }),
        );
        m.status = Some(status(InstanceState::Connected));
        m.screen = Screen::Connected {
            status: status(InstanceState::Connected),
        };
        m
    }

    fn created() -> CreatedInstance {
        CreatedInstance {
            instance_id: "inst1".into(),
            key: KEY.into(),
            key_id: "k1".into(),
        }
    }

    #[test]
    fn the_first_screen_offers_exactly_two_options() {
        assert_eq!(OPTION_LINK_NEW, "Link WhatsApp for the first time");
        assert_eq!(OPTION_CONNECT_EXISTING, "Connect to an existing instance");
        assert_eq!(model().screen, Screen::Choose);
        assert_eq!(
            model().apply(Input::ChooseLinkNew).len()
                + model().apply(Input::ChooseConnectExisting).len(),
            0
        );
    }

    #[test]
    fn link_for_the_first_time_by_qr() {
        let mut m = model();
        m.apply(Input::ChooseLinkNew);
        assert!(matches!(m.screen, Screen::LinkNew { .. }));
        let effects = m.apply(Input::StartQr);
        assert_eq!(m.screen, Screen::Connecting);
        assert_eq!(
            effects,
            vec![Effect::CreateInstance {
                gateway_url: DEFAULT_GATEWAY_URL.into(),
                request: CreateInstance {
                    link: LinkRequest::Qr,
                    app: AppId::Messages
                },
            }]
        );
        let link = StoredLink {
            gateway_url: DEFAULT_GATEWAY_URL.into(),
            instance_id: "inst1".into(),
            key: KEY.into(),
        };
        assert_eq!(
            m.apply(Input::Created(created())),
            vec![Effect::Save(link.clone()), Effect::Subscribe(link.clone())]
        );
        assert_eq!(m.link, Some(link));
        m.apply(Input::Event(GatewayEvent::Qr {
            payload: "2@abc".into(),
            valid_for_ms: 20_000,
        }));
        assert_eq!(
            m.screen,
            Screen::ShowQr {
                payload: "2@abc".into()
            }
        );
        // The QR rotates.
        m.apply(Input::Event(GatewayEvent::Qr {
            payload: "2@def".into(),
            valid_for_ms: 20_000,
        }));
        assert_eq!(
            m.screen,
            Screen::ShowQr {
                payload: "2@def".into()
            }
        );
        m.apply(Input::Event(GatewayEvent::State(status(
            InstanceState::Connected,
        ))));
        assert!(matches!(m.screen, Screen::Connected { .. }));
        assert!(m.is_connected());
    }

    #[test]
    fn pair_code_flow_needs_a_country_code() {
        let mut m = model();
        m.apply(Input::ChooseLinkNew);
        m.apply(Input::UsePairCode);
        assert_eq!(
            m.screen,
            Screen::LinkNew {
                method: LinkMethodChoice::PairCode
            }
        );
        // No country code: the gateway is never asked.
        let effects = m.apply(Input::PhoneEntered("0532 111 22 33".into()));
        assert!(effects.is_empty());
        let Screen::Failed { message, back } = &m.screen else {
            panic!("expected Failed, got {:?}", m.screen);
        };
        assert!(message.contains("country code"), "{message}");
        assert_eq!(
            **back,
            Screen::LinkNew {
                method: LinkMethodChoice::PairCode
            }
        );
        // Garbage is refused too.
        assert!(m.apply(Input::Back).is_empty());
        assert!(m.apply(Input::PhoneEntered("abc".into())).is_empty());
        assert!(matches!(m.screen, Screen::Failed { .. }));
        m.apply(Input::Back);
        // With a country code it goes ahead.
        let effects = m.apply(Input::PhoneEntered("+90 532 111 22 33".into()));
        assert!(matches!(
            &effects[..],
            [Effect::CreateInstance {
                request: CreateInstance {
                    link: LinkRequest::PhoneNumber { .. },
                    ..
                },
                ..
            }]
        ));
        m.apply(Input::Created(created()));
        m.apply(Input::Event(GatewayEvent::PairCode {
            code: "ABCD-1234".into(),
            valid_for_ms: 60_000,
        }));
        assert_eq!(
            m.screen,
            Screen::ShowCode {
                code: "ABCD-1234".into()
            }
        );
    }

    #[test]
    fn connect_to_an_existing_instance() {
        let mut m = model();
        m.apply(Input::ChooseConnectExisting);
        assert_eq!(m.screen, Screen::PasteKey);
        // Pasted text keeps its whitespace out.
        let effects = m.apply(Input::KeyPasted(format!("  {KEY}\n")));
        assert_eq!(m.screen, Screen::Connecting);
        let [Effect::Verify(probe)] = &effects[..] else {
            panic!("{effects:?}");
        };
        assert_eq!(probe.key, KEY);
        assert!(
            m.link.is_none(),
            "nothing is trusted before the gateway answers"
        );
        let effects = m.apply(Input::StatusOk(status(InstanceState::Connected)));
        let link = StoredLink {
            gateway_url: DEFAULT_GATEWAY_URL.into(),
            instance_id: "inst1".into(),
            key: KEY.into(),
        };
        assert_eq!(
            effects,
            vec![Effect::Save(link.clone()), Effect::Subscribe(link)]
        );
        assert!(matches!(m.screen, Screen::Connected { .. }));
    }

    #[test]
    fn a_bad_key_fails_without_asking_the_gateway() {
        let mut m = model();
        m.apply(Input::ChooseConnectExisting);
        for bad in [
            "",
            "hello",
            "rwk_short",
            "xyz_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        ] {
            assert!(m.apply(Input::KeyPasted(bad.into())).is_empty(), "{bad}");
            let Screen::Failed { message, back } = &m.screen else {
                panic!("{bad}: {:?}", m.screen);
            };
            assert!(!message.is_empty());
            assert_eq!(**back, Screen::PasteKey);
            m.apply(Input::Back);
            assert_eq!(m.screen, Screen::PasteKey);
        }
    }

    #[test]
    fn a_revoked_key_fails() {
        let mut m = model();
        m.apply(Input::ChooseConnectExisting);
        m.apply(Input::KeyPasted(KEY.into()));
        assert!(m.apply(Input::Unauthorized).is_empty());
        assert_eq!(
            m.screen,
            Screen::Failed {
                message: "This key was revoked or is wrong".into(),
                back: Box::new(Screen::PasteKey)
            }
        );
        assert!(m.link.is_none());

        // A saved key that stops working is forgotten.
        let mut m = linked();
        assert_eq!(m.apply(Input::Unauthorized), vec![Effect::Forget]);
        assert!(m.link.is_none());
        assert!(matches!(m.screen, Screen::Failed { .. }));
        // The gateway can also say so over the event stream.
        let mut m = linked();
        assert_eq!(
            m.apply(Input::Event(GatewayEvent::KeyRevoked)),
            vec![Effect::Forget]
        );
    }

    #[test]
    fn show_key_mints_then_copies() {
        let mut m = linked();
        assert_eq!(
            m.apply(Input::RequestShareKey),
            vec![Effect::MintKey("Messages share".into())]
        );
        let issued = IssuedKey {
            key_id: "k2".into(),
            key: KEY.into(),
            label: "Messages share".into(),
        };
        assert_eq!(
            m.apply(Input::ShowKey(issued)),
            vec![Effect::CopyToClipboard(KEY.into())]
        );
        assert_eq!(m.screen, Screen::ShareKey { key: KEY.into() });
        assert_eq!(
            m.apply(Input::CopyKey),
            vec![Effect::CopyToClipboard(KEY.into())]
        );
        m.apply(Input::Back);
        assert!(matches!(m.screen, Screen::Connected { .. }));
        // Unlinked apps have nothing to share.
        assert!(model().apply(Input::RequestShareKey).is_empty());
    }

    #[test]
    fn unlink_forgets_and_returns_to_the_two_options() {
        let mut m = linked();
        assert_eq!(m.apply(Input::Unlink), vec![Effect::Forget]);
        assert_eq!(m.screen, Screen::Choose);
        assert!(m.link.is_none() && !m.is_connected());
    }

    #[test]
    fn cancelling_a_half_made_link_deletes_its_instance() {
        let mut m = model();
        m.apply(Input::StartQr);
        m.apply(Input::Created(created()));
        m.apply(Input::Event(GatewayEvent::Qr {
            payload: "2@abc".into(),
            valid_for_ms: 1,
        }));
        let effects = m.apply(Input::Cancel);
        assert!(matches!(
            &effects[..],
            [Effect::Forget, Effect::AbandonInstance(_)]
        ));
        assert_eq!(m.screen, Screen::Choose);
        // A connected app cannot be cancelled by accident.
        assert!(linked().apply(Input::Cancel).is_empty());
    }

    #[test]
    fn a_saved_link_is_verified_on_start() {
        let mut m = linked();
        m.screen = Screen::Choose;
        let effects = m.start();
        assert!(matches!(&effects[..], [Effect::Verify(_)]));
        assert_eq!(m.screen, Screen::Connecting);
        // Offline: Failed, and Retry asks again; Back keeps the link.
        m.apply(Input::Error("http: timed out".into()));
        assert!(matches!(m.screen, Screen::Failed { .. }));
        assert!(matches!(&m.apply(Input::Retry)[..], [Effect::Verify(_)]));
        m.apply(Input::Error("again".into()));
        m.apply(Input::Back);
        assert!(matches!(m.screen, Screen::Connected { .. }));
        assert!(m.link.is_some());
        assert!(model().start().is_empty());
    }

    #[test]
    fn instance_state_changes() {
        let mut m = linked();
        m.apply(Input::Event(GatewayEvent::State(status(
            InstanceState::Disconnected,
        ))));
        assert!(!m.is_connected());
        assert!(matches!(m.screen, Screen::Connected { .. }));
        // Logged out: the link stays so the app can link again.
        assert!(
            m.apply(Input::Event(GatewayEvent::State(status(
                InstanceState::LoggedOut
            ))))
            .is_empty()
        );
        assert_eq!(m.screen, Screen::LoggedOut);
        assert!(m.link.is_some());
        // Banned is final.
        assert_eq!(
            m.apply(Input::Event(GatewayEvent::State(status(
                InstanceState::Banned
            )))),
            vec![Effect::Forget]
        );
        assert!(m.link.is_none());
        // Chatter that is not about linking changes nothing.
        let mut m = linked();
        assert!(m.apply(Input::Event(GatewayEvent::Resync)).is_empty());
    }

    fn key(id: &str, current: bool) -> KeyInfo {
        KeyInfo {
            key_id: id.into(),
            label: format!("app {id}"),
            created_ms: 1,
            last_used_ms: None,
            current,
        }
    }

    fn open_keys(m: &mut LinkModel) {
        assert_eq!(m.apply(Input::OpenKeys), vec![Effect::ListKeys]);
        m.apply(Input::KeysLoaded(vec![key("k1", true), key("k2", false)]));
    }

    #[test]
    fn old_key_info_json_deserializes_without_current() {
        let old = r#"{"key_id":"k1","label":"Phone","created_ms":5,"last_used_ms":null}"#;
        let info: KeyInfo = serde_json::from_str(old).unwrap();
        assert!(!info.current);
    }

    #[test]
    fn revoking_another_key_shrinks_the_list() {
        let mut m = linked();
        open_keys(&mut m);
        assert!(matches!(&m.screen, Screen::Keys { keys } if keys.len() == 2));
        assert_eq!(
            m.apply(Input::Revoke("k2".into())),
            vec![Effect::RevokeKey("k2".into())]
        );
        assert!(m.apply(Input::Revoked("k2".into())).is_empty());
        assert_eq!(
            m.screen,
            Screen::Keys {
                keys: vec![key("k1", true)]
            }
        );
        assert!(m.link.is_some());
        m.apply(Input::Back);
        assert!(matches!(m.screen, Screen::Connected { .. }));
    }

    #[test]
    fn revoking_the_current_key_forgets_the_link() {
        let mut m = linked();
        open_keys(&mut m);
        assert_eq!(
            m.apply(Input::Revoke("k1".into())),
            vec![Effect::RevokeKey("k1".into())]
        );
        assert_eq!(m.apply(Input::Revoked("k1".into())), vec![Effect::Forget]);
        assert_eq!(m.screen, Screen::Choose);
        assert!(m.link.is_none());
    }

    #[test]
    fn unlink_asks_first_and_back_keeps_the_link() {
        let mut m = linked();
        assert!(m.apply(Input::RequestUnlink).is_empty());
        assert_eq!(m.screen, Screen::ConfirmUnlink);
        assert_eq!(m.back_input(), Some(Input::Back));
        m.apply(Input::Back);
        assert!(matches!(m.screen, Screen::Connected { .. }));
        assert!(m.link.is_some());
        // A status arriving while the question is up does not dismiss it.
        m.apply(Input::RequestUnlink);
        m.apply(Input::Event(GatewayEvent::State(status(
            InstanceState::Disconnected,
        ))));
        assert_eq!(m.screen, Screen::ConfirmUnlink);
        assert_eq!(m.apply(Input::Unlink), vec![Effect::Forget]);
        assert_eq!(m.screen, Screen::Choose);
        assert!(m.link.is_none());
        // Nothing to unlink without a link.
        let mut m = model();
        m.apply(Input::RequestUnlink);
        assert_eq!(m.screen, Screen::Choose);
    }

    #[test]
    fn unlink_from_the_logged_out_page_backs_out_to_it() {
        let mut m = linked();
        m.apply(Input::Event(GatewayEvent::State(status(
            InstanceState::LoggedOut,
        ))));
        m.apply(Input::RequestUnlink);
        assert_eq!(m.screen, Screen::ConfirmUnlink);
        m.apply(Input::Back);
        assert_eq!(m.screen, Screen::LoggedOut);
    }

    #[test]
    fn revoke_asks_first_and_back_returns_to_the_list() {
        let mut m = linked();
        open_keys(&mut m);
        assert!(m.apply(Input::RequestRevoke("k2".into())).is_empty());
        assert!(
            matches!(&m.screen, Screen::ConfirmRevoke { key, keys } if key.key_id == "k2" && keys.len() == 2)
        );
        assert_eq!(m.back_input(), Some(Input::Back));
        m.apply(Input::Back);
        assert!(matches!(&m.screen, Screen::Keys { keys } if keys.len() == 2));
        // Confirmed: the gateway is asked, and its answer shrinks the list.
        m.apply(Input::RequestRevoke("k2".into()));
        assert_eq!(
            m.apply(Input::Revoke("k2".into())),
            vec![Effect::RevokeKey("k2".into())]
        );
        assert!(matches!(&m.screen, Screen::Keys { .. }));
        m.apply(Input::Revoked("k2".into()));
        assert_eq!(
            m.screen,
            Screen::Keys {
                keys: vec![key("k1", true)]
            }
        );
        // An unknown key asks nothing.
        m.apply(Input::RequestRevoke("nope".into()));
        assert!(matches!(&m.screen, Screen::Keys { .. }));
    }

    #[test]
    fn logged_out_then_link_again_by_qr_keeps_the_same_link() {
        let mut m = linked();
        let link = m.link.clone().unwrap();
        assert!(
            m.apply(Input::Event(GatewayEvent::State(status(
                InstanceState::LoggedOut
            ))))
            .is_empty()
        );
        assert_eq!(m.screen, Screen::LoggedOut);
        // Other status chatter does not leave the page.
        m.apply(Input::Event(GatewayEvent::State(status(
            InstanceState::Disconnected,
        ))));
        assert_eq!(m.screen, Screen::LoggedOut);
        assert_eq!(
            m.apply(Input::Relink(LinkRequest::Qr)),
            vec![Effect::Relink {
                link: link.clone(),
                request: LinkRequest::Qr
            }]
        );
        assert_eq!(m.screen, Screen::Connecting);
        m.apply(Input::Event(GatewayEvent::State(status(
            InstanceState::Linking,
        ))));
        m.apply(Input::Event(GatewayEvent::Qr {
            payload: "2@new".into(),
            valid_for_ms: 20_000,
        }));
        assert_eq!(
            m.screen,
            Screen::ShowQr {
                payload: "2@new".into()
            }
        );
        let effects = m.apply(Input::Event(GatewayEvent::State(status(
            InstanceState::Connected,
        ))));
        assert!(effects.is_empty(), "no new link is saved: {effects:?}");
        assert!(matches!(m.screen, Screen::Connected { .. }));
        assert_eq!(m.link, Some(link));
    }

    #[test]
    fn logged_out_on_start_offers_link_again_and_unlink() {
        let mut m = linked();
        m.screen = Screen::Connecting;
        let effects = m.apply(Input::StatusOk(status(InstanceState::LoggedOut)));
        assert!(matches!(&effects[..], [Effect::Subscribe(_)]));
        assert_eq!(m.screen, Screen::LoggedOut);
        assert_eq!(m.apply(Input::Unlink), vec![Effect::Forget]);
        assert_eq!(m.screen, Screen::Choose);
    }

    #[test]
    fn relink_by_pairing_code_checks_the_number_and_cancel_keeps_the_instance() {
        let mut m = linked();
        m.apply(Input::Event(GatewayEvent::State(status(
            InstanceState::LoggedOut,
        ))));
        let bad = LinkRequest::PhoneNumber {
            number: "0532 111 22 33".into(),
        };
        assert!(m.apply(Input::Relink(bad)).is_empty());
        assert!(matches!(&m.screen, Screen::Failed { back, .. } if **back == Screen::LoggedOut));
        m.apply(Input::Back);
        assert_eq!(m.screen, Screen::LoggedOut);
        let good = LinkRequest::PhoneNumber {
            number: "+90 532 111 22 33".into(),
        };
        assert!(matches!(
            &m.apply(Input::Relink(good))[..],
            [Effect::Relink { .. }]
        ));
        m.apply(Input::Event(GatewayEvent::PairCode {
            code: "ABCD-1234".into(),
            valid_for_ms: 60_000,
        }));
        assert!(matches!(m.screen, Screen::ShowCode { .. }));
        // The instance is shared: Cancel must not delete it or the link.
        assert!(m.apply(Input::Cancel).is_empty());
        assert_eq!(m.screen, Screen::LoggedOut);
        assert!(m.link.is_some());
        // Relink only makes sense on the logged-out page.
        assert!(linked().apply(Input::Relink(LinkRequest::Qr)).is_empty());
    }

    #[test]
    fn deleting_the_instance_needs_a_confirmation() {
        let mut m = linked();
        let link = m.link.clone().unwrap();
        // Skipping the confirmation does nothing.
        assert!(m.apply(Input::ConfirmDelete).is_empty());
        assert!(m.apply(Input::RequestDelete).is_empty());
        assert_eq!(m.screen, Screen::ConfirmDelete);
        // Back out first.
        m.apply(Input::Back);
        assert!(matches!(m.screen, Screen::Connected { .. }));
        m.apply(Input::RequestDelete);
        assert_eq!(
            m.apply(Input::ConfirmDelete),
            vec![Effect::DeleteInstance(link)]
        );
        assert_eq!(m.screen, Screen::Connecting);
        assert_eq!(m.apply(Input::Deleted), vec![Effect::Forget]);
        assert_eq!(m.screen, Screen::Choose);
        assert!(m.link.is_none());
        // Nothing to delete without a link.
        assert!(model().apply(Input::RequestDelete).is_empty());
    }

    #[test]
    fn last_used_is_human() {
        let now = 10 * 86_400_000;
        assert_eq!(last_used_text(None, now), "never used");
        assert_eq!(last_used_text(Some(now - 5_000), now), "just now");
        assert_eq!(last_used_text(Some(now + 5_000), now), "just now");
        assert_eq!(last_used_text(Some(now - 5 * 60_000), now), "5 min ago");
        assert_eq!(last_used_text(Some(now - 3 * 3_600_000), now), "3 h ago");
        assert_eq!(last_used_text(Some(now - 2 * 86_400_000), now), "2 d ago");
    }

    #[test]
    fn the_gateway_address_is_validated() {
        let mut m = model();
        m.apply(Input::EditUrl);
        assert_eq!(m.screen, Screen::EnterUrl);
        m.apply(Input::UrlEntered("wa.example.com".into()));
        assert!(matches!(m.screen, Screen::Failed { .. }));
        m.apply(Input::Back);
        assert_eq!(m.screen, Screen::EnterUrl);
        m.apply(Input::UrlEntered("https://wa.example.com/".into()));
        assert_eq!(m.screen, Screen::Choose);
        assert_eq!(m.gateway_url(), "https://wa.example.com");
        // Plain http to a public host is refused (AD-WA3).
        m.apply(Input::EditUrl);
        m.apply(Input::UrlEntered("http://example.com".into()));
        assert!(matches!(m.screen, Screen::Failed { .. }));
    }

    #[test]
    fn stored_link_round_trips_and_hides_its_key() {
        let link = StoredLink {
            gateway_url: "https://wa.example.com".into(),
            instance_id: "inst1".into(),
            key: KEY.into(),
        };
        let json = serde_json::to_string(&link).unwrap();
        assert_eq!(serde_json::from_str::<StoredLink>(&json).unwrap(), link);
        assert!(!format!("{link:?}").contains(KEY));
        assert_eq!(
            json,
            format!(
                r#"{{"gateway_url":"https://wa.example.com","instance_id":"inst1","key":"{KEY}"}}"#
            )
        );
    }

    #[test]
    fn stored_link_file_round_trips_on_disk() {
        let dir = std::env::temp_dir().join(format!("rovsti-wa-link-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let link = linked().link.unwrap();
        crate::storage::save_json(&dir, LINK_FILE, &link).unwrap();
        assert_eq!(
            crate::storage::load_json::<StoredLink>(&dir, LINK_FILE),
            Some(link)
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn system_back_steps_back_inside_the_settings_and_leaves_the_top_alone() {
        let mut m = LinkModel::new(AppId::Messages, None);
        assert_eq!(m.back_input(), None, "the two options are the top");
        for screen in [
            Screen::EnterUrl,
            Screen::LinkNew {
                method: LinkMethodChoice::Qr,
            },
            Screen::PasteKey,
            Screen::ConfirmDelete,
        ] {
            m.screen = screen;
            assert_eq!(m.back_input(), Some(Input::Back), "{:?}", m.screen);
        }
        m.screen = Screen::ShowQr {
            payload: "p".into(),
        };
        assert_eq!(m.back_input(), Some(Input::Cancel), "a link in progress");
        m.screen = Screen::Connecting;
        assert_eq!(m.back_input(), None);
        m.screen = Screen::LoggedOut;
        assert_eq!(m.back_input(), None);
        // The step Back takes is the on-screen one.
        m.screen = Screen::EnterUrl;
        let input = m.back_input().expect("a step back");
        m.apply(input);
        assert_eq!(m.screen, Screen::Choose);
    }

    /// Every screen survives JSON, inside the model and on its own, and so do the inputs and
    /// effects that carry newtype variants.
    #[test]
    fn settings_serde_roundtrip() {
        let key = KeyInfo {
            key_id: "k1".into(),
            label: "Phone share".into(),
            created_ms: 1,
            last_used_ms: None,
            current: true,
        };
        let screens = vec![
            Screen::Choose,
            Screen::EnterUrl,
            Screen::LinkNew {
                method: LinkMethodChoice::Qr,
            },
            Screen::LinkNew {
                method: LinkMethodChoice::PairCode,
            },
            Screen::ShowQr {
                payload: "2@abc".into(),
            },
            Screen::ShowCode {
                code: "ABCD-1234".into(),
            },
            Screen::PasteKey,
            Screen::Connecting,
            Screen::Connected {
                status: status(InstanceState::Connected),
            },
            Screen::ShareKey { key: KEY.into() },
            Screen::Keys {
                keys: vec![key.clone()],
            },
            Screen::LoggedOut,
            Screen::ConfirmDelete,
            Screen::ConfirmUnlink,
            Screen::ConfirmRevoke {
                key: key.clone(),
                keys: vec![key],
            },
            Screen::Failed {
                message: "no route".into(),
                back: Box::new(Screen::PasteKey),
            },
        ];
        for screen in screens {
            let json = serde_json::to_string(&screen).unwrap();
            assert_eq!(
                serde_json::from_str::<Screen>(&json).unwrap(),
                screen,
                "{json}"
            );
            let mut m = linked();
            m.screen = screen;
            m.pending_key = Some(KEY.into());
            m.relinking = true;
            let json = serde_json::to_string(&m).unwrap();
            let back: LinkModel = serde_json::from_str(&json).unwrap();
            assert_eq!(back, m, "{json}");
        }
        for input in [
            Input::EditUrl,
            Input::UrlEntered("https://wa.example.com".into()),
            Input::Relink(LinkRequest::PhoneNumber {
                number: "+905321112233".into(),
            }),
            Input::Event(GatewayEvent::KeyRevoked),
            Input::KeysLoaded(Vec::new()),
        ] {
            let json = serde_json::to_string(&input).unwrap();
            assert_eq!(
                serde_json::from_str::<Input>(&json).unwrap(),
                input,
                "{json}"
            );
        }
        let effect = Effect::Relink {
            link: StoredLink {
                gateway_url: "https://wa.example.com".into(),
                instance_id: "i".into(),
                key: KEY.into(),
            },
            request: LinkRequest::Qr,
        };
        let json = serde_json::to_string(&effect).unwrap();
        assert_eq!(serde_json::from_str::<Effect>(&json).unwrap(), effect);
        assert_eq!(
            serde_json::to_value(Effect::Forget).unwrap(),
            serde_json::json!({"kind": "forget"})
        );
    }
}
