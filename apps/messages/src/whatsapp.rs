//! The "Link WhatsApp" screen: start a companion session, show the rotating
//! pairing QR, and share it to another screen to scan.

use std::{path::PathBuf, sync::Arc};

use futures::StreamExt;
use gpui::{
    Context, Image, ImageFormat, InteractiveElement, IntoElement, ParentElement, Render,
    SelectedFile, ShareOptions, SharedString, StatefulInteractiveElement, Styled, Task, Window,
    div, img, px,
};
use rovsti_core::Message;
use rovsti_ui::{
    components::{glass_pill, section},
    current_theme,
    tokens::{radius, typography},
};
use rovsti_whatsapp::{LinkMethod, Session, WaEvent, qr_png};

const DATABASE: &str = "whatsapp.db";
const QR_FILE: &str = "rovsti-whatsapp-link.png";

enum LinkState {
    Idle,
    Starting,
    Qr { payload: String, png: Arc<Image> },
    PairCode(SharedString),
    Linked,
    Failed(SharedString),
}

pub struct WhatsAppScreen {
    state: LinkState,
    data_dir: Option<PathBuf>,
    session: Option<Session>,
    new_messages: Vec<Message>,
    _events: Option<Task<()>>,
}

impl WhatsAppScreen {
    pub fn new(cx: &mut Context<Self>) -> Self {
        let dir = rovsti_ui::storage::data_dir("messages", cx);
        cx.spawn(async move |this, cx| {
            let dir = dir.await;
            let _ = this.update(cx, |this, cx| {
                match dir {
                    Ok(dir) => {
                        // An existing database means we were linked before:
                        // reconnect silently instead of showing a QR.
                        let linked = dir.join(DATABASE).exists();
                        this.data_dir = Some(dir);
                        if linked {
                            this.start(cx);
                        }
                    }
                    Err(error) => this.state = LinkState::Failed(error.into()),
                }
                cx.notify();
            });
        })
        .detach();

        Self {
            state: LinkState::Idle,
            data_dir: None,
            session: None,
            new_messages: Vec::new(),
            _events: None,
        }
    }

    pub fn take_new_messages(&mut self) -> Vec<Message> {
        std::mem::take(&mut self.new_messages)
    }

    fn start(&mut self, cx: &mut Context<Self>) {
        let Some(dir) = self.data_dir.clone() else {
            return;
        };
        match Session::start(dir.join(DATABASE), LinkMethod::Qr) {
            Ok((session, mut events)) => {
                self.session = Some(session);
                self.state = LinkState::Starting;
                self._events = Some(cx.spawn(async move |this, cx| {
                    while let Some(event) = events.next().await {
                        if this.update(cx, |this, cx| this.handle(event, cx)).is_err() {
                            break;
                        }
                    }
                }));
            }
            Err(error) => self.state = LinkState::Failed(error.to_string().into()),
        }
        cx.notify();
    }

    fn handle(&mut self, event: WaEvent, cx: &mut Context<Self>) {
        match event {
            WaEvent::Qr { payload, .. } => match qr_png(&payload, 720) {
                Ok(png) => {
                    self.state = LinkState::Qr {
                        payload,
                        png: Arc::new(Image::from_bytes(ImageFormat::Png, png)),
                    };
                }
                Err(error) => self.state = LinkState::Failed(error.to_string().into()),
            },
            WaEvent::PairCode { code, .. } => self.state = LinkState::PairCode(code.into()),
            WaEvent::Connected => self.state = LinkState::Linked,
            WaEvent::LoggedOut => {
                self.session = None;
                self.state = LinkState::Idle;
            }
            WaEvent::Message(message) => self.new_messages.push(message),
            WaEvent::Failed(error) => self.state = LinkState::Failed(error.into()),
        }
        cx.notify();
    }

    /// Writes the current QR to a PNG and opens the system share sheet, so
    /// the user can send it to a PC or tablet and scan it there.
    fn share_qr(&mut self, cx: &mut Context<Self>) {
        let (LinkState::Qr { payload, .. }, Some(dir)) = (&self.state, &self.data_dir) else {
            return;
        };
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
            }
        })
        .detach();
    }
}

impl Render for WhatsAppScreen {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let colors = theme.colors();
        let text = |s: &'static str| {
            div()
                .px(px(16.0))
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child(s)
        };

        let content = match &self.state {
            LinkState::Idle => div()
                .flex()
                .flex_col()
                .gap(px(16.0))
                .child(text(
                    "Use WhatsApp here as a linked device. Messages stay on this phone; there is no Rovsti server. \
                     WhatsApp does not endorse third-party clients and may restrict accounts that use them.",
                ))
                .child(
                    glass_pill(theme, "Link WhatsApp")
                        .id("link")
                        .mx(px(16.0))
                        .on_click(cx.listener(|this, _, _, cx| this.start(cx))),
                ),
            LinkState::Starting => div().child(text("Connecting to WhatsApp…")),
            LinkState::Qr { png, .. } => div()
                .flex()
                .flex_col()
                .items_center()
                .gap(px(16.0))
                .child(text(
                    "On another screen, open WhatsApp → Settings → Linked devices → Link a device and scan this code. \
                     Share it to your PC if this is the only phone.",
                ))
                .child(
                    div()
                        .p(px(12.0))
                        .rounded(radius::CARD)
                        .bg(gpui::white())
                        .child(img(png.clone()).size(px(260.0))),
                )
                .child(
                    glass_pill(theme, "Share QR to PC…")
                        .id("share")
                        .on_click(cx.listener(|this, _, _, cx| this.share_qr(cx))),
                ),
            LinkState::PairCode(code) => div()
                .flex()
                .flex_col()
                .items_center()
                .gap(px(12.0))
                .child(text("In WhatsApp → Linked devices → Link with phone number instead, enter:"))
                .child(
                    div()
                        .text_size(typography::LARGE_TITLE.size)
                        .font_weight(typography::LARGE_TITLE.weight)
                        .text_color(colors.label)
                        .child(code.clone()),
                ),
            LinkState::Linked => div().child(text("WhatsApp is linked. New chats appear in the Chats tab.")),
            LinkState::Failed(error) => div()
                .flex()
                .flex_col()
                .gap(px(12.0))
                .child(div().px(px(16.0)).text_color(colors.red).child(error.clone()))
                .child(
                    glass_pill(theme, "Try again")
                        .id("retry")
                        .mx(px(16.0))
                        .on_click(cx.listener(|this, _, _, cx| this.start(cx))),
                ),
        };

        section(
            theme,
            None,
            [div().py(px(16.0)).child(content).into_any_element()],
        )
    }
}
