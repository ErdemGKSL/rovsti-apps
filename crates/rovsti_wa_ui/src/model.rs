//! The pure state machine behind the WhatsApp settings screen.
//!
//! No GPUI and no I/O: [`LinkModel::apply`] takes an [`Input`] (a tap, a pasted
//! text, a gateway answer) and returns the [`Effect`]s the view must run. The
//! first screen has exactly two options: [`OPTION_LINK_NEW`] and
//! [`OPTION_CONNECT_EXISTING`].

use std::fmt;

use rovsti_wa_proto::{
    AppId, ConnectKey, CreateInstance, CreatedInstance, GatewayEvent, GatewayUrl, InstanceState,
    InstanceStatus, IssuedKey, LinkRequest,
};
use serde::{Deserialize, Serialize};

/// Where the link is kept, in the app's own data directory.
pub const LINK_FILE: &str = "whatsapp-link.json";

/// What a fresh install proposes: the gateway on the host machine, as seen
/// from the Android emulator. A real gateway address is entered once.
pub const DEFAULT_GATEWAY_URL: &str = "http://10.0.2.2:8787";

pub const OPTION_LINK_NEW: &str = "Link WhatsApp for the first time";
pub const OPTION_CONNECT_EXISTING: &str = "Connect to an existing instance";
pub const ACTION_SHOW_KEY: &str = "Show connect key";
pub const ACTION_UNLINK: &str = "Unlink this app";

pub const MSG_KEY_REJECTED: &str = "This key was revoked or is wrong";
pub const MSG_LOGGED_OUT: &str = "WhatsApp logged this instance out. Link it again.";
pub const MSG_BANNED: &str = "WhatsApp has banned this account from linked devices.";

/// What an app keeps to reach its instance. The key is a bearer secret.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StoredLink {
    pub gateway_url: String,
    pub instance_id: String,
    pub key: String,
}

impl fmt::Debug for StoredLink {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("StoredLink")
            .field("gateway_url", &self.gateway_url)
            .field("instance_id", &self.instance_id)
            .field("key", &"rwk_…")
            .finish()
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LinkMethodChoice {
    Qr,
    PairCode,
}

#[derive(Clone, Debug, PartialEq)]
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
    Failed {
        message: String,
        back: Box<Screen>,
    },
}

#[derive(Clone, Debug, PartialEq)]
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
    Unlink,
    /// Leave a form screen.
    Back,
    /// Give up a half-made link (QR or code on screen) and delete its instance.
    Cancel,
    /// "Try again" on a failure.
    Retry,
}

#[derive(Clone, Debug, PartialEq)]
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
}

#[derive(Clone, Debug)]
pub struct LinkModel {
    app: AppId,
    pub screen: Screen,
    pub link: Option<StoredLink>,
    pub status: Option<InstanceStatus>,
    gateway_url: String,
    /// A pasted key being verified.
    pending_key: Option<String>,
}

fn app_name(app: AppId) -> &'static str {
    match app {
        AppId::Phone => "Phone",
        AppId::Messages => "Messages",
        AppId::Contacts => "Contacts",
        AppId::Stops => "Stops",
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
    }

    /// Shows what the instance is doing. `Linking` keeps waiting for a QR.
    fn show_status(&mut self, status: InstanceStatus) {
        self.screen = match status.state {
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
            Input::Unlink => {
                self.drop_link();
                self.screen = Screen::Choose;
                vec![Effect::Forget]
            }
            Input::Back => {
                match &self.screen {
                    Screen::EnterUrl | Screen::LinkNew { .. } | Screen::PasteKey => {
                        self.screen = Screen::Choose;
                    }
                    Screen::ShareKey { .. } => self.screen = self.home(),
                    Screen::Failed { back, .. } => self.screen = (**back).clone(),
                    _ => {}
                }
                Vec::new()
            }
            Input::Cancel => match self.screen {
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
        }
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
        match status.state {
            InstanceState::LoggedOut => {
                self.drop_link();
                self.screen = failed(MSG_LOGGED_OUT, Screen::Choose);
                return vec![Effect::Forget];
            }
            InstanceState::Banned => {
                self.drop_link();
                self.screen = failed(MSG_BANNED, Screen::Choose);
                return vec![Effect::Forget];
            }
            _ => {}
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
                    self.drop_link();
                    self.screen = failed(MSG_LOGGED_OUT, Screen::Choose);
                    vec![Effect::Forget]
                }
                InstanceState::Banned => {
                    self.drop_link();
                    self.screen = failed(MSG_BANNED, Screen::Choose);
                    vec![Effect::Forget]
                }
                _ => {
                    // A status never pulls the user out of a form they are
                    // filling in (the key on screen, an error).
                    if matches!(self.screen, Screen::ShareKey { .. } | Screen::Failed { .. }) {
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
        assert_eq!(
            m.apply(Input::Event(GatewayEvent::State(status(
                InstanceState::LoggedOut
            )))),
            vec![Effect::Forget]
        );
        assert!(m.link.is_none());
        // Chatter that is not about linking changes nothing.
        let mut m = linked();
        assert!(m.apply(Input::Event(GatewayEvent::Resync)).is_empty());
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
        rovsti_ui::storage::save_json(&dir, LINK_FILE, &link).unwrap();
        assert_eq!(
            rovsti_ui::storage::load_json::<StoredLink>(&dir, LINK_FILE),
            Some(link)
        );
        let _ = std::fs::remove_dir_all(&dir);
    }
}
