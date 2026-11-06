//! The GPUI side of the WhatsApp settings screen.
//!
//! [`WhatsAppSettings`] owns a [`LinkModel`], runs its [`Effect`]s (gateway
//! calls on the background executor, the saved link, the clipboard) and draws
//! the current [`Screen`]. Apps embed the entity, read [`WhatsAppSettings::gateway`]
//! once connected and drain [`WhatsAppSettings::take_events`].

use std::{path::PathBuf, sync::Arc, time::Duration};

use futures::StreamExt;
use gpui::{
    AnyElement, App, AppContext as _, ClipboardItem, Context, Entity, Image, ImageFormat,
    InteractiveElement, IntoElement, ParentElement, Render, SelectedFile, ShareOptions,
    SharedString, StatefulInteractiveElement, Styled, TextInputAction, TextInputPurpose, Window,
    div, img, px,
};
use rovsti_ui::{
    components::{glass_pill, list_row, section},
    current_theme,
    field::{TextField, field_box},
    storage,
    tokens::{Theme, metrics, radius, typography},
};
use rovsti_wa_client::{ClientError, ClientEvent, EventStream, Gateway};
use rovsti_wa_proto::{
    AppId, ConnectKey, GatewayEvent, GatewayUrl, InstanceState, KeyInfo, LinkRequest,
    PushRegistration,
};

use crate::{
    model::{
        ACTION_SHOW_KEY, ACTION_UNLINK, Effect, Input, LINK_FILE, LinkMethodChoice, LinkModel,
        OPTION_CONNECT_EXISTING, OPTION_LINK_NEW, Screen, StoredLink, last_used_text,
    },
    qr::qr_png,
};

const ACTION_CONNECTED_APPS: &str = "Connected apps";
const ACTION_DELETE: &str = "Delete instance…";
const QR_FILE: &str = "rovsti-whatsapp-link.png";
/// Events kept for the app between two calls of `take_events`.
const EVENT_BACKLOG: usize = 1024;
/// The platform sometimes refuses the first request for the app's data folder
/// ("Android system service rejected the request"); asking again works.
const STORAGE_ATTEMPTS: u32 = 4;
const STORAGE_RETRY: Duration = Duration::from_millis(400);

pub struct WhatsAppSettings {
    model: LinkModel,
    /// Asks for the app's folder under `storage::data_dir`, also after startup (a retry).
    data_dirs: storage::DataDirs,
    data_dir: Option<PathBuf>,
    /// A link made while the data folder was unreachable: written as soon as it is.
    unsaved_link: Option<StoredLink>,
    /// The owner unlinked before the data folder was reachable: the saved link must go, not load.
    skip_saved_link: bool,
    gateway: Option<(StoredLink, Gateway)>,
    stream: Option<(StoredLink, EventStream)>,
    events: Vec<GatewayEvent>,
    /// The link the FCM token was registered for (once per link and launch).
    push_for: Option<StoredLink>,
    qr: Option<(String, Arc<Image>)>,
    /// Why "Share QR to PC…" did not work, shown under the QR.
    share_note: Option<SharedString>,
    url_field: Entity<TextField>,
    phone_field: Entity<TextField>,
    key_field: Entity<TextField>,
    _pump: Option<gpui::Task<()>>,
}

fn error_input(error: ClientError) -> Input {
    match error {
        ClientError::Unauthorized => Input::Unauthorized,
        other => Input::Error(error_words(&other)),
    }
}

/// What the settings say when the gateway can't be asked. Transport and
/// decoding details go to the log, never on screen.
fn error_words(error: &ClientError) -> String {
    match error {
        // A typed address or a pasted key that does not parse: their own words.
        ClientError::Url(_) | ClientError::Key(_) => error.to_string(),
        ClientError::Unauthorized => crate::model::MSG_KEY_REJECTED.to_owned(),
        // The gateway's own sentence ("too many instances").
        ClientError::Api(api) => api.message.clone(),
        ClientError::Http(_) | ClientError::Ws(_) | ClientError::Tls(_) | ClientError::Closed => {
            log::warn!("the gateway can't be reached: {error}");
            MSG_UNREACHABLE.to_owned()
        }
        ClientError::Decode(_) => {
            log::warn!("the gateway answered oddly: {error}");
            MSG_ODD_ANSWER.to_owned()
        }
    }
}

const MSG_UNREACHABLE: &str =
    "Can't reach your gateway. Check the internet connection and the gateway address.";
const MSG_ODD_ANSWER: &str =
    "Your gateway sent an answer this app can't read. Update the gateway and the app.";
const MSG_NO_STORAGE: &str = "Can't open this app's storage on the phone. Try again.";
const MSG_SHARE_FAILED: &str = "Couldn't share the QR code. Scan it from this screen instead.";

/// Runs a blocking gateway job and turns a panic inside it into an error.
///
/// A panicking future leaves its `Task` without output, and awaiting it then
/// panics with "Task polled after completion" on the UI thread, which takes
/// the app down. With `panic = "unwind"` the panic is caught here instead:
/// the job owns everything it touches, so nothing half-updated is observed
/// afterwards. The panic hook has already logged the message and location.
fn guarded<T>(job: impl FnOnce() -> Result<T, ClientError>) -> Result<T, ClientError> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(job)).unwrap_or_else(|payload| {
        let message = payload
            .downcast_ref::<&str>()
            .map(|s| (*s).to_owned())
            .or_else(|| payload.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "unknown panic".to_owned());
        log::error!("a gateway call panicked: {message}");
        Err(ClientError::Http(format!("internal error: {message}")))
    })
}

fn parse_link(link: &StoredLink) -> Result<(GatewayUrl, ConnectKey), ClientError> {
    Ok((
        GatewayUrl::parse(&link.gateway_url)?,
        ConnectKey::parse(&link.key)?,
    ))
}

impl WhatsAppSettings {
    /// `data_dir_name` is the app's folder under `rovsti_ui::storage::data_dir`
    /// (the same name the app uses for its other files).
    pub fn new(app: AppId, data_dir_name: &'static str, cx: &mut Context<Self>) -> Self {
        let field = |purpose, action, cx: &mut Context<Self>| {
            let field = cx.new(|cx| TextField::with_input(purpose, action, cx));
            cx.observe(&field, |_, _, cx| cx.notify()).detach();
            field
        };
        let url_field = field(TextInputPurpose::Url, TextInputAction::Done, cx);
        let phone_field = field(TextInputPurpose::Phone, TextInputAction::Done, cx);
        let key_field = field(TextInputPurpose::Text, TextInputAction::Done, cx);

        // Made here, while the app is starting: `data_dir` may not run later on Android.
        let data_dirs = storage::DataDirs::new(data_dir_name);
        let pump = Self::open_storage(data_dirs.clone(), cx);
        Self {
            model: LinkModel::new(app, None),
            data_dirs,
            data_dir: None,
            unsaved_link: None,
            skip_saved_link: false,
            gateway: None,
            stream: None,
            events: Vec::new(),
            push_for: None,
            qr: None,
            share_note: None,
            url_field,
            phone_field,
            key_field,
            _pump: Some(pump),
        }
    }

    /// Asks for the app's data folder, again a few times when the platform refuses, then
    /// hands it to [`Self::storage_ready`]. Only the last failure is shown.
    fn open_storage(dirs: storage::DataDirs, cx: &mut Context<Self>) -> gpui::Task<()> {
        cx.spawn(async move |this, cx| {
            let mut attempt = 1;
            let found = loop {
                let Ok(request) = this.update(cx, |_, cx| dirs.request(cx)) else {
                    return;
                };
                match request.await {
                    Ok(dir) => break Ok(dir),
                    Err(error) if attempt < STORAGE_ATTEMPTS => {
                        log::warn!(
                            "data folder request failed ({attempt}/{STORAGE_ATTEMPTS}): {error}"
                        );
                        attempt += 1;
                        cx.background_executor().timer(STORAGE_RETRY).await;
                    }
                    Err(error) => break Err(error),
                }
            };
            this.update(cx, |this, cx| match found {
                Ok(dir) => this.storage_ready(dir, cx),
                Err(error) => {
                    log::warn!("the data folder can't be reached: {error}");
                    this.dispatch(Input::Error(MSG_NO_STORAGE.to_owned()), cx);
                }
            })
            .ok();
        })
    }

    /// The data folder is reachable: load the saved link (a linked app checks its key), or
    /// write the one made in the meantime.
    fn storage_ready(&mut self, dir: PathBuf, cx: &mut Context<Self>) {
        if std::mem::take(&mut self.skip_saved_link)
            && let Err(error) = std::fs::remove_file(dir.join(LINK_FILE))
            && error.kind() != std::io::ErrorKind::NotFound
        {
            log::warn!("removing the WhatsApp link failed: {:?}", error.kind());
        }
        self.data_dir = Some(dir.clone());
        if let Some(link) = self.unsaved_link.take().or_else(|| self.model.link.clone()) {
            // Linked in this session while the folder was out of reach: keep that link.
            if let Err(error) = storage::save_json(&dir, LINK_FILE, &link) {
                log::warn!("saving the WhatsApp link failed: {:?}", error.kind());
            }
            cx.notify();
            return;
        }
        let saved = storage::load_json::<StoredLink>(&dir, LINK_FILE);
        self.model = LinkModel::new(self.model.app(), saved);
        let effects = self.model.start();
        self.run(effects, cx);
        cx.notify();
    }

    /// Whether the system Back key has something to do inside this screen (a step back, or
    /// cancelling a link in progress). The app puts its own Back handling after this one.
    pub fn can_go_back(&self) -> bool {
        self.model.back_input().is_some()
    }

    /// Takes one step back inside the settings; `false` when there is none to take.
    pub fn go_back(&mut self, cx: &mut Context<Self>) -> bool {
        match self.model.back_input() {
            Some(input) => {
                self.dispatch(input, cx);
                true
            }
            None => false,
        }
    }

    /// The app's data folder, once the platform handed it over.
    pub fn data_dir(&self) -> Option<&std::path::Path> {
        self.data_dir.as_deref()
    }

    /// The saved link, once this app is connected to an instance.
    pub fn link(&self) -> Option<&StoredLink> {
        self.model.link.as_ref()
    }

    /// A client for the instance, `None` until the app has a verified link.
    pub fn gateway(&self) -> Option<Gateway> {
        self.gateway.as_ref().map(|(_, gateway)| gateway.clone())
    }

    /// The instance is linked to WhatsApp and the gateway says it is connected.
    pub fn is_connected(&self) -> bool {
        self.model.is_connected()
    }

    /// Gateway events that arrived since the last call (the apps observe the
    /// entity and drain this from their observer).
    pub fn take_events(&mut self) -> Vec<GatewayEvent> {
        std::mem::take(&mut self.events)
    }

    /// Registers this app's FCM token with the gateway so it can wake the phone
    /// for calls and messages (AD-WA6). Once per link and launch; a no-op until
    /// the instance is connected, and when the host packages no Firebase (an
    /// empty token) or on desktop. Apps call it from their observer.
    pub fn register_push(&mut self, cx: &mut Context<Self>) {
        if !self.is_connected() {
            return;
        }
        let (Some(link), Some(gateway)) = (self.link().cloned(), self.gateway()) else {
            return;
        };
        if self.push_for.as_ref() == Some(&link) {
            return;
        }
        self.push_for = Some(link);
        let app = self.model.app();
        cx.background_executor()
            .spawn(async move {
                match rovsti_android::wa::push_token() {
                    Ok(token) if !token.is_empty() => {
                        if let Err(error) = gateway.register_push(&PushRegistration { app, token })
                        {
                            log::warn!("registering the push token failed: {error}");
                        }
                    }
                    Ok(_) => log::debug!("no FCM token: Firebase is not packaged"),
                    Err(error) => log::debug!("no push token: {error}"),
                }
            })
            .detach();
    }

    /// An FCM wake-up arrived (AD-WA6): reconnect the event stream at once, or
    /// verify the link when no stream runs (the process was cold-started).
    pub fn wake(&mut self, cx: &mut Context<Self>) {
        if let Some((_, stream)) = &self.stream {
            stream.reconnect_now();
        } else if let Some(link) = self.model.link.clone() {
            self.run(vec![Effect::Verify(link)], cx);
        }
    }

    /// Installs the app's event sink and forwards `WA_PUSH` events to
    /// [`Self::wake`]. For apps with no other Kotlin events (Messages, Stops).
    pub fn forward_push(this: &Entity<Self>, cx: &mut App) {
        let (mut events_rx, sink) = rovsti_android::events::install_sink_owned();
        let this = this.downgrade();
        cx.spawn(async move |cx| {
            while let Some(event) = events_rx.next().await {
                if event.kind != rovsti_android::events::kind::WA_PUSH {
                    continue;
                }
                if this.update(cx, |this, cx| this.wake(cx)).is_err() {
                    break;
                }
            }
            rovsti_android::events::clear_sink_if(sink);
        })
        .detach();
    }

    // ---- state ------------------------------------------------------------------

    fn dispatch(&mut self, input: Input, cx: &mut Context<Self>) {
        // "Try again" after the data folder could not be reached asks for the folder again;
        // the model alone would only go back to the two options, with the saved link unread.
        if matches!(input, Input::Retry) && self.data_dir.is_none() {
            self.model.screen = Screen::Connecting;
            self._pump = Some(Self::open_storage(self.data_dirs.clone(), cx));
            cx.notify();
            return;
        }
        let effects = self.model.apply(input);
        self.sync();
        self.run(effects, cx);
        cx.notify();
    }

    /// Keeps the derived pieces (client, QR image) in step with the model.
    fn sync(&mut self) {
        match &self.model.link {
            Some(link) if self.gateway.as_ref().is_none_or(|(l, _)| l != link) => {
                self.gateway = parse_link(link)
                    .ok()
                    .map(|(url, key)| (link.clone(), Gateway::new(url, Some(key))));
            }
            None => self.gateway = None,
            _ => {}
        }
        if let Screen::ShowQr { payload } = &self.model.screen {
            if self.qr.as_ref().is_none_or(|(p, _)| p != payload) {
                self.qr = match qr_png(payload, 720) {
                    Ok(png) => Some((
                        payload.clone(),
                        Arc::new(Image::from_bytes(ImageFormat::Png, png)),
                    )),
                    Err(error) => {
                        log::warn!("rendering the pairing QR failed: {error}");
                        None
                    }
                };
            }
        } else {
            self.qr = None;
            self.share_note = None;
        }
    }

    fn run(&mut self, effects: Vec<Effect>, cx: &mut Context<Self>) {
        for effect in effects {
            match effect {
                Effect::CreateInstance {
                    gateway_url,
                    request,
                } => self.call(
                    cx,
                    move || {
                        let gateway = Gateway::new(GatewayUrl::parse(&gateway_url)?, None);
                        gateway.create_instance(&request)
                    },
                    Input::Created,
                ),
                Effect::Verify(link) => self.call(
                    cx,
                    move || {
                        let (url, key) = parse_link(&link)?;
                        Gateway::new(url, Some(key)).status()
                    },
                    Input::StatusOk,
                ),
                Effect::MintKey(label) => {
                    let Some(gateway) = self.gateway() else {
                        continue;
                    };
                    self.call(cx, move || gateway.mint_key(&label), Input::ShowKey);
                }
                Effect::ListKeys => {
                    let Some(gateway) = self.gateway() else {
                        continue;
                    };
                    self.call(cx, move || gateway.keys(), Input::KeysLoaded);
                }
                Effect::RevokeKey(key_id) => {
                    let Some(gateway) = self.gateway() else {
                        continue;
                    };
                    let id = key_id.clone();
                    self.call(
                        cx,
                        move || gateway.revoke_key(&key_id),
                        move |()| Input::Revoked(id),
                    );
                }
                Effect::Relink(link, request) => self.call_quiet(cx, move || {
                    let (url, key) = parse_link(&link)?;
                    Gateway::new(url, Some(key)).relink(&request)
                }),
                Effect::DeleteInstance(link) => self.call(
                    cx,
                    move || {
                        let (url, key) = parse_link(&link)?;
                        Gateway::new(url, Some(key)).delete_instance()
                    },
                    |()| Input::Deleted,
                ),
                Effect::AbandonInstance(link) => {
                    cx.background_executor()
                        .spawn(async move {
                            let result = parse_link(&link).and_then(|(url, key)| {
                                Gateway::new(url, Some(key)).delete_instance()
                            });
                            if let Err(error) = result {
                                log::debug!("deleting the abandoned instance failed: {error}");
                            }
                        })
                        .detach();
                }
                Effect::Save(link) => match &self.data_dir {
                    Some(dir) => {
                        if let Err(error) = storage::save_json(dir, LINK_FILE, &link) {
                            log::warn!("saving the WhatsApp link failed: {:?}", error.kind());
                        }
                    }
                    None => {
                        log::warn!("the data folder is not reachable yet: keeping the link");
                        self.unsaved_link = Some(link);
                    }
                },
                Effect::Forget => {
                    self.stream = None;
                    self.push_for = None;
                    self.events.clear();
                    if self.data_dir.is_none() {
                        self.unsaved_link = None;
                        self.skip_saved_link = true;
                    }
                    if let Some(dir) = &self.data_dir
                        && let Err(error) = std::fs::remove_file(dir.join(LINK_FILE))
                        && error.kind() != std::io::ErrorKind::NotFound
                    {
                        log::warn!("removing the WhatsApp link failed: {:?}", error.kind());
                    }
                }
                Effect::Subscribe(link) => self.subscribe(link, cx),
                Effect::CopyToClipboard(text) => {
                    cx.write_to_clipboard(ClipboardItem::new_string(text));
                }
            }
        }
    }

    /// Runs a blocking gateway call off the UI thread and feeds its answer
    /// back as an [`Input`].
    fn call<T: Send + 'static>(
        &mut self,
        cx: &mut Context<Self>,
        job: impl FnOnce() -> Result<T, ClientError> + Send + 'static,
        done: impl FnOnce(T) -> Input + 'static,
    ) {
        let task = cx.background_executor().spawn(async move { guarded(job) });
        cx.spawn(async move |this, cx| {
            let input = match task.await {
                Ok(value) => done(value),
                Err(error) => error_input(error),
            };
            this.update(cx, |this, cx| this.dispatch(input, cx)).ok();
        })
        .detach();
    }

    /// Like [`Self::call`] for a call whose success needs no input: the
    /// gateway answers with events. Only a failure is fed back.
    fn call_quiet(
        &mut self,
        cx: &mut Context<Self>,
        job: impl FnOnce() -> Result<(), ClientError> + Send + 'static,
    ) {
        let task = cx.background_executor().spawn(async move { guarded(job) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                this.update(cx, |this, cx| this.dispatch(error_input(error), cx))
                    .ok();
            }
        })
        .detach();
    }

    fn subscribe(&mut self, link: StoredLink, cx: &mut Context<Self>) {
        if self.stream.as_ref().is_some_and(|(l, _)| *l == link) {
            return;
        }
        let started =
            parse_link(&link).and_then(|(url, key)| rovsti_wa_client::subscribe(url, key, None));
        match started {
            Ok((stream, mut receiver)) => {
                self.stream = Some((link, stream));
                self._pump = Some(cx.spawn(async move |this, cx| {
                    while let Some(event) = receiver.next().await {
                        if this
                            .update(cx, |this, cx| this.on_client_event(event, cx))
                            .is_err()
                        {
                            break;
                        }
                    }
                }));
            }
            Err(error) => self.dispatch(error_input(error), cx),
        }
    }

    fn on_client_event(&mut self, event: ClientEvent, cx: &mut Context<Self>) {
        match event {
            ClientEvent::Event(envelope) => {
                if self.events.len() >= EVENT_BACKLOG {
                    self.events.remove(0);
                }
                self.events.push(envelope.event.clone());
                self.dispatch(Input::Event(envelope.event), cx);
            }
            ClientEvent::Unauthorized => {
                self.stream = None;
                self.dispatch(Input::Unauthorized, cx);
            }
            ClientEvent::Connected => cx.notify(),
            ClientEvent::Disconnected(reason) => {
                log::debug!("gateway events dropped: {reason}");
            }
        }
    }

    /// Writes the current QR to a PNG and opens the system share sheet, so
    /// the user can send it to a PC or tablet and scan it there.
    fn share_qr(&mut self, cx: &mut Context<Self>) {
        let Screen::ShowQr { payload } = &self.model.screen else {
            return;
        };
        let Some(dir) = &self.data_dir else {
            self.share_note = Some(MSG_SHARE_FAILED.into());
            cx.notify();
            return;
        };
        self.share_note = None;
        let path = dir.join(QR_FILE);
        let payload = payload.clone();
        let executor = cx.background_executor().clone();
        let write = executor.spawn({
            let path = path.clone();
            async move {
                let png = qr_png(&payload, 1024).map_err(|e| e.to_string())?;
                std::fs::write(&path, png).map_err(|e| e.to_string())
            }
        });
        cx.spawn(async move |this, cx| {
            let result = match write.await {
                Ok(()) => {
                    let file = SelectedFile::from_path(path, executor, false);
                    let share = this.update(cx, |_, cx| {
                        cx.share(ShareOptions {
                            text: Some(
                                "Scan this from WhatsApp → Linked devices → Link a device.".into(),
                            ),
                            files: vec![file],
                            title: Some("Share WhatsApp link QR".into()),
                        })
                    });
                    match share {
                        Ok(task) => task.await.map_err(|e| e.to_string()),
                        Err(e) => Err(e.to_string()),
                    }
                }
                Err(error) => Err(error),
            };
            if let Err(error) = result {
                log::warn!("sharing the QR failed: {error}");
                this.update(cx, |this, cx| {
                    this.share_note = Some(MSG_SHARE_FAILED.into());
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
    }

    fn paste_key(&mut self, cx: &mut Context<Self>) {
        if let Some(text) = cx.read_from_clipboard().and_then(|item| item.text()) {
            self.key_field
                .update(cx, |field, cx| field.set(text.trim(), cx));
        }
    }

    // ---- views ------------------------------------------------------------------

    fn note(theme: Theme, text: impl Into<SharedString>) -> AnyElement {
        div()
            .px(metrics::ROW_INSET)
            .text_size(typography::SUBHEADLINE.size)
            .text_color(theme.colors().secondary_label)
            .child(text.into())
            .into_any_element()
    }

    fn button(
        theme: Theme,
        id: &'static str,
        label: &'static str,
        cx: &mut Context<Self>,
        tap: impl Fn(&mut Self, &mut Context<Self>) + 'static,
    ) -> AnyElement {
        glass_pill(theme, label)
            .id(id)
            .mx(metrics::ROW_INSET)
            .on_click(cx.listener(move |this, _, _, cx| tap(this, cx)))
            .into_any_element()
    }

    fn page(children: Vec<AnyElement>) -> AnyElement {
        div()
            .flex()
            .flex_col()
            .gap(px(16.0))
            .py(px(16.0))
            .children(children)
            .into_any_element()
    }

    fn option_row(
        theme: Theme,
        id: &'static str,
        title: &'static str,
        subtitle: &'static str,
        cx: &mut Context<Self>,
        tap: impl Fn(&mut Self, &mut Context<Self>) + 'static,
    ) -> AnyElement {
        list_row(theme, None, title, Some(subtitle.into()), None)
            .id(id)
            .on_click(cx.listener(move |this, _, _, cx| tap(this, cx)))
            .into_any_element()
    }

    fn choose(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let url = self.model.gateway_url().to_owned();
        Self::page(vec![
            Self::note(
                theme,
                "WhatsApp runs on your Rovsti gateway, a server you control. \
                 WhatsApp does not endorse third-party clients and may restrict accounts that use them.",
            ),
            section(
                theme,
                Some("Gateway".into()),
                [list_row(theme, None, "Address", Some(url.into()), None)
                    .id("wa-url")
                    .on_click(cx.listener(|this, _, _, cx| {
                        let url = this.model.gateway_url().to_owned();
                        this.url_field.update(cx, |f, cx| f.set(&url, cx));
                        this.dispatch(Input::EditUrl, cx);
                    }))
                    .into_any_element()],
            )
            .into_any_element(),
            section(
                theme,
                None,
                [
                    Self::option_row(
                        theme,
                        "wa-link-new",
                        OPTION_LINK_NEW,
                        "Create a new instance and link it from the WhatsApp app",
                        cx,
                        |this, cx| this.dispatch(Input::ChooseLinkNew, cx),
                    ),
                    Self::option_row(
                        theme,
                        "wa-connect",
                        OPTION_CONNECT_EXISTING,
                        "Paste a connect key shown by another Rovsti app",
                        cx,
                        |this, cx| {
                            this.key_field.update(cx, |f, cx| f.set("", cx));
                            this.dispatch(Input::ChooseConnectExisting, cx);
                        },
                    ),
                ],
            )
            .into_any_element(),
        ])
    }

    fn back_button(theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        Self::button(theme, "wa-back", "Back", cx, |this, cx| {
            this.dispatch(Input::Back, cx);
        })
    }

    fn enter_url(&self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        Self::page(vec![
            Self::note(
                theme,
                "The address of your gateway, for example https://wa.example.com. \
                 Plain http is only accepted on your own network.",
            ),
            div()
                .px(metrics::ROW_INSET)
                .child(field_box(
                    theme,
                    "wa-url-field",
                    &self.url_field,
                    "https://",
                    window,
                    cx,
                ))
                .into_any_element(),
            Self::button(theme, "wa-url-save", "Use this address", cx, |this, cx| {
                let text = this.url_field.read(cx).text().to_owned();
                this.dispatch(Input::UrlEntered(text), cx);
            }),
            Self::back_button(theme, cx),
        ])
    }

    fn link_new(
        &self,
        theme: Theme,
        method: LinkMethodChoice,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        match method {
            LinkMethodChoice::Qr => Self::page(vec![
                Self::note(
                    theme,
                    "Scan a QR code from WhatsApp on another phone, or share it to a PC. \
                     Or get a pairing code if this phone has WhatsApp.",
                ),
                Self::button(theme, "wa-start-qr", "Show a QR code", cx, |this, cx| {
                    this.dispatch(Input::StartQr, cx);
                }),
                Self::button(
                    theme,
                    "wa-use-code",
                    "Use a pairing code instead",
                    cx,
                    |this, cx| {
                        this.dispatch(Input::UsePairCode, cx);
                    },
                ),
                Self::back_button(theme, cx),
            ]),
            LinkMethodChoice::PairCode => Self::page(vec![
                Self::note(
                    theme,
                    "Enter the number of the WhatsApp account with its country code, \
                     for example +90 532 111 22 33.",
                ),
                div()
                    .px(metrics::ROW_INSET)
                    .child(field_box(
                        theme,
                        "wa-phone-field",
                        &self.phone_field,
                        "+90 …",
                        window,
                        cx,
                    ))
                    .into_any_element(),
                Self::button(
                    theme,
                    "wa-get-code",
                    "Get a pairing code",
                    cx,
                    |this, cx| {
                        let number = this.phone_field.read(cx).text().to_owned();
                        this.dispatch(Input::PhoneEntered(number), cx);
                    },
                ),
                Self::back_button(theme, cx),
            ]),
        }
    }

    fn paste_key_screen(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        Self::page(vec![
            Self::note(
                theme,
                "On a Rovsti app that is already connected, open WhatsApp settings, \
                 tap \"Show connect key\" and paste the key here.",
            ),
            div()
                .px(metrics::ROW_INSET)
                .child(field_box(
                    theme,
                    "wa-key-field",
                    &self.key_field,
                    "rwk_…",
                    window,
                    cx,
                ))
                .into_any_element(),
            Self::button(theme, "wa-paste", "Paste from clipboard", cx, |this, cx| {
                this.paste_key(cx);
            }),
            Self::button(theme, "wa-key-connect", "Connect", cx, |this, cx| {
                let text = this.key_field.read(cx).text().to_owned();
                this.dispatch(Input::KeyPasted(text), cx);
            }),
            Self::back_button(theme, cx),
        ])
    }

    fn show_qr(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let code = match &self.qr {
            Some((_, png)) => div()
                .p(px(12.0))
                .rounded(radius::CARD)
                .bg(gpui::white())
                .child(img(png.clone()).size(px(260.0)))
                .into_any_element(),
            None => Self::note(theme, "Could not draw the QR code."),
        };
        let mut children = vec![
            Self::note(
                theme,
                "On another screen, open WhatsApp → Settings → Linked devices → Link a device \
                 and scan this code. Share it to your PC if this is the only phone.",
            ),
            div().flex().justify_center().child(code).into_any_element(),
            Self::button(theme, "wa-share-qr", "Share QR to PC…", cx, |this, cx| {
                this.share_qr(cx);
            }),
        ];
        if let Some(note) = &self.share_note {
            children.push(
                div()
                    .px(metrics::ROW_INSET)
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(theme.colors().red)
                    .child(note.clone())
                    .into_any_element(),
            );
        }
        children.push(Self::button(
            theme,
            "wa-cancel",
            "Cancel",
            cx,
            |this, cx| {
                this.dispatch(Input::Cancel, cx);
            },
        ));
        Self::page(children)
    }

    fn show_code(&self, theme: Theme, code: &str, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme.colors();
        Self::page(vec![
            Self::note(
                theme,
                "In WhatsApp → Linked devices → Link a device → Link with phone number instead, enter:",
            ),
            div()
                .flex()
                .justify_center()
                .text_size(typography::LARGE_TITLE.size)
                .font_weight(typography::LARGE_TITLE.weight)
                .text_color(colors.label)
                .child(SharedString::from(code.to_owned()))
                .into_any_element(),
            Self::button(theme, "wa-cancel", "Cancel", cx, |this, cx| {
                this.dispatch(Input::Cancel, cx);
            }),
        ])
    }

    fn connected(
        &self,
        theme: Theme,
        status: &rovsti_wa_proto::InstanceStatus,
        cx: &mut Context<Self>,
    ) -> AnyElement {
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
                    .map(|jid| format!("+{}", rovsti_wa_proto::jid_user(jid)))
            })
            .unwrap_or_else(|| "WhatsApp".to_owned());
        Self::page(vec![
            section(
                theme,
                None,
                [list_row(theme, None, who, Some(state.into()), None).into_any_element()],
            )
            .into_any_element(),
            Self::button(theme, "wa-show-key", ACTION_SHOW_KEY, cx, |this, cx| {
                this.dispatch(Input::RequestShareKey, cx);
            }),
            Self::button(theme, "wa-keys", ACTION_CONNECTED_APPS, cx, |this, cx| {
                this.dispatch(Input::OpenKeys, cx);
            }),
            Self::button(theme, "wa-unlink", ACTION_UNLINK, cx, |this, cx| {
                this.dispatch(Input::RequestUnlink, cx);
            }),
            Self::note(
                theme,
                "Unlinking only removes WhatsApp from this app. The instance keeps running \
                 on the gateway for your other apps.",
            ),
            Self::danger_button(theme, "wa-delete", ACTION_DELETE, cx, |this, cx| {
                this.dispatch(Input::RequestDelete, cx);
            }),
        ])
    }

    fn danger_button(
        theme: Theme,
        id: &'static str,
        label: &'static str,
        cx: &mut Context<Self>,
        tap: impl Fn(&mut Self, &mut Context<Self>) + 'static,
    ) -> AnyElement {
        glass_pill(theme, label)
            .id(id)
            .mx(metrics::ROW_INSET)
            .text_color(theme.colors().red)
            .on_click(cx.listener(move |this, _, _, cx| tap(this, cx)))
            .into_any_element()
    }

    fn keys_screen(&self, theme: Theme, keys: &[KeyInfo], cx: &mut Context<Self>) -> AnyElement {
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX));
        let mut children = vec![Self::note(
            theme,
            "Every app that can use this WhatsApp instance. Revoking a key signs that app out. \
             Revoking this app's own key unlinks it.",
        )];
        for info in keys {
            let used = last_used_text(info.last_used_ms, now);
            let subtitle = if info.current {
                format!("this app · {used}")
            } else {
                used
            };
            let key_id = info.key_id.clone();
            children.push(
                section(
                    theme,
                    None,
                    [
                        list_row(theme, None, info.label.clone(), Some(subtitle.into()), None)
                            .into_any_element(),
                    ],
                )
                .into_any_element(),
            );
            children.push(
                glass_pill(theme, "Revoke")
                    .id(SharedString::from(format!("wa-revoke-{}", info.key_id)))
                    .mx(metrics::ROW_INSET)
                    .text_color(theme.colors().red)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.dispatch(Input::RequestRevoke(key_id.clone()), cx);
                    }))
                    .into_any_element(),
            );
        }
        children.push(Self::back_button(theme, cx));
        Self::page(children)
    }

    fn logged_out(&self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        Self::page(vec![
            Self::note(
                theme,
                "WhatsApp logged this instance out. Link it again; your other apps keep their keys.",
            ),
            Self::button(
                theme,
                "wa-relink-qr",
                "Link again with a QR code",
                cx,
                |this, cx| {
                    this.dispatch(Input::Relink(LinkRequest::Qr), cx);
                },
            ),
            Self::note(
                theme,
                "Or get a pairing code: enter the number with its country code, for example +90 532 111 22 33.",
            ),
            div()
                .px(metrics::ROW_INSET)
                .child(field_box(
                    theme,
                    "wa-phone-field",
                    &self.phone_field,
                    "+90 …",
                    window,
                    cx,
                ))
                .into_any_element(),
            Self::button(
                theme,
                "wa-relink-code",
                "Link again with a pairing code",
                cx,
                |this, cx| {
                    let number = this.phone_field.read(cx).text().to_owned();
                    this.dispatch(Input::Relink(LinkRequest::PhoneNumber { number }), cx);
                },
            ),
            Self::button(theme, "wa-unlink", ACTION_UNLINK, cx, |this, cx| {
                this.dispatch(Input::RequestUnlink, cx);
            }),
        ])
    }

    fn confirm_delete(theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        Self::page(vec![
            Self::note(
                theme,
                "This logs WhatsApp out and deletes the instance, its history and every connect key \
                 on the gateway. All your Rovsti apps lose WhatsApp. This cannot be undone.",
            ),
            Self::danger_button(
                theme,
                "wa-delete-confirm",
                "Delete instance",
                cx,
                |this, cx| {
                    this.dispatch(Input::ConfirmDelete, cx);
                },
            ),
            Self::back_button(theme, cx),
        ])
    }

    fn confirm_unlink(theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        Self::page(vec![
            Self::note(
                theme,
                "This app forgets its WhatsApp link. To use WhatsApp here again, paste a connect \
                 key from another Rovsti app or link again. Your other apps stay linked.",
            ),
            Self::danger_button(theme, "wa-unlink-confirm", "Unlink", cx, |this, cx| {
                this.dispatch(Input::Unlink, cx);
            }),
            Self::back_button(theme, cx),
        ])
    }

    fn confirm_revoke(theme: Theme, key: &KeyInfo, cx: &mut Context<Self>) -> AnyElement {
        let text = if key.current {
            format!(
                "“{}” is this app's own key. Revoking it unlinks this app from WhatsApp.",
                key.label
            )
        } else {
            format!(
                "The app using “{}” is signed out of WhatsApp and needs a new key.",
                key.label
            )
        };
        let key_id = key.key_id.clone();
        Self::page(vec![
            Self::note(theme, text),
            Self::danger_button(theme, "wa-revoke-confirm", "Revoke", cx, move |this, cx| {
                this.dispatch(Input::Revoke(key_id.clone()), cx);
            }),
            Self::back_button(theme, cx),
        ])
    }

    fn share_key(&self, theme: Theme, key: &str, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme.colors();
        // 47 ASCII characters: two lines fit a phone's width.
        let (head, tail) = key.split_at(key.len().min(24));
        Self::page(vec![
            Self::note(
                theme,
                "Copied. Paste this key into \"Connect to an existing instance\" in another Rovsti app. \
                 Anyone with the key can use your WhatsApp, so share it only with your own apps.",
            ),
            div()
                .mx(metrics::ROW_INSET)
                .p(px(12.0))
                .rounded(radius::CARD)
                .bg(colors.secondary_background)
                .text_size(typography::BODY.size)
                .text_color(colors.label)
                .flex()
                .flex_col()
                .items_center()
                .child(SharedString::from(head.to_owned()))
                .child(SharedString::from(tail.to_owned()))
                .into_any_element(),
            Self::button(theme, "wa-copy-key", "Copy again", cx, |this, cx| {
                this.dispatch(Input::CopyKey, cx);
            }),
            Self::back_button(theme, cx),
        ])
    }

    fn waiting(theme: Theme) -> AnyElement {
        Self::page(vec![Self::note(theme, "Connecting to WhatsApp…")])
    }

    fn failed(theme: Theme, message: &str, cx: &mut Context<Self>) -> AnyElement {
        Self::page(vec![
            div()
                .px(metrics::ROW_INSET)
                .text_color(theme.colors().red)
                .child(SharedString::from(message.to_owned()))
                .into_any_element(),
            Self::button(theme, "wa-retry", "Try again", cx, |this, cx| {
                this.dispatch(Input::Retry, cx);
            }),
            Self::back_button(theme, cx),
        ])
    }
}

impl Render for WhatsAppSettings {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        // Borrowed, not cloned: a key list or QR payload is not copied every frame.
        match &self.model.screen {
            Screen::Choose => self.choose(theme, cx),
            Screen::EnterUrl => self.enter_url(theme, window, cx),
            Screen::LinkNew { method } => self.link_new(theme, *method, window, cx),
            Screen::ShowQr { .. } => self.show_qr(theme, cx),
            Screen::ShowCode { code } => self.show_code(theme, code, cx),
            Screen::PasteKey => self.paste_key_screen(theme, window, cx),
            Screen::Connecting => Self::waiting(theme),
            Screen::Connected { status } => self.connected(theme, status, cx),
            Screen::ShareKey { key } => self.share_key(theme, key, cx),
            Screen::Keys { keys } => self.keys_screen(theme, keys, cx),
            Screen::LoggedOut => self.logged_out(theme, window, cx),
            Screen::ConfirmDelete => Self::confirm_delete(theme, cx),
            Screen::ConfirmUnlink => Self::confirm_unlink(theme, cx),
            Screen::ConfirmRevoke { key, .. } => Self::confirm_revoke(theme, key, cx),
            Screen::Failed { message, .. } => Self::failed(theme, message, cx),
        }
    }
}

#[cfg(test)]
mod tests {
    use gpui::TestAppContext;

    use super::*;

    #[test]
    fn transport_failures_read_as_plain_words() {
        for error in [
            ClientError::Http("io: connection refused (os error 111)".into()),
            ClientError::Ws("handshake".into()),
            ClientError::Tls("bad certificate".into()),
            ClientError::Closed,
        ] {
            assert_eq!(error_input(error), Input::Error(MSG_UNREACHABLE.into()));
        }
        assert_eq!(
            error_input(ClientError::Decode("expected value at line 1".into())),
            Input::Error(MSG_ODD_ANSWER.into())
        );
        assert_eq!(error_input(ClientError::Unauthorized), Input::Unauthorized);
        let api = rovsti_wa_proto::ApiError::new(
            rovsti_wa_proto::ErrorCode::LimitReached,
            "too many instances",
        );
        assert_eq!(
            error_input(ClientError::Api(api)),
            Input::Error("too many instances".into())
        );
    }

    #[test]
    fn a_panicking_job_becomes_an_error() {
        let result: Result<(), ClientError> = guarded(|| panic!("boom"));
        assert_eq!(
            result,
            Err(ClientError::Http("internal error: boom".into()))
        );
        assert_eq!(guarded(|| Ok(7)), Ok(7));
    }

    fn link() -> StoredLink {
        StoredLink {
            gateway_url: "https://rovsti.erdemdev.tr".into(),
            instance_id: "inst-1".into(),
            key: "rwk_test".into(),
        }
    }

    /// A link made before the data folder could be asked for is written once it is, not lost.
    #[gpui::test]
    fn a_link_made_before_the_folder_is_ready_is_saved_afterwards(cx: &mut TestAppContext) {
        let name = "wa-ui-test-unsaved-link";
        let view = cx.new(|cx| WhatsAppSettings::new(AppId::Messages, name, cx));
        view.update(cx, |view, cx| {
            assert!(
                view.data_dir.is_none(),
                "the folder is still being asked for"
            );
            view.run(vec![Effect::Save(link())], cx);
            assert!(view.unsaved_link.is_some());
        });
        cx.run_until_parked();
        let dir = view
            .read_with(cx, |view, _| view.data_dir.clone())
            .expect("folder found");
        assert_eq!(
            storage::load_json::<StoredLink>(&dir, LINK_FILE),
            Some(link())
        );
        view.read_with(cx, |view, _| assert!(view.unsaved_link.is_none()));
        std::fs::remove_dir_all(dir).ok();
    }

    /// Unlinking before the folder is reachable must not bring the old link back.
    #[gpui::test]
    fn unlinking_before_the_folder_is_ready_removes_the_saved_link(cx: &mut TestAppContext) {
        let name = "wa-ui-test-forget";
        // First run: a link is saved.
        let first = cx.new(|cx| WhatsAppSettings::new(AppId::Messages, name, cx));
        first.update(cx, |view, cx| view.run(vec![Effect::Save(link())], cx));
        cx.run_until_parked();
        let dir = first
            .read_with(cx, |view, _| view.data_dir.clone())
            .expect("folder found");
        assert!(storage::load_json::<StoredLink>(&dir, LINK_FILE).is_some());
        // Second run: the owner unlinks while the folder is still being asked for.
        let second = cx.new(|cx| WhatsAppSettings::new(AppId::Messages, name, cx));
        second.update(cx, |view, cx| view.run(vec![Effect::Forget], cx));
        cx.run_until_parked();
        assert_eq!(storage::load_json::<StoredLink>(&dir, LINK_FILE), None);
        second.read_with(cx, |view, _| assert!(view.link().is_none()));
        std::fs::remove_dir_all(dir).ok();
    }
}
