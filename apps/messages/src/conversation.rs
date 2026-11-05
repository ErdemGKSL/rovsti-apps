//! One WhatsApp conversation: history from the gateway, live events, and a
//! text composer. The merge logic lives in `rovsti_wa_proto::timeline`; this
//! module is the thin GPUI shell around it plus a few pure text helpers.

use std::{
    collections::{HashMap, HashSet},
    sync::Arc,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use gpui::TextInputAction;
use gpui::TextInputPurpose;
use gpui::{
    Context, Entity, EventEmitter, Image, ImageFormat, InteractiveElement, IntoElement,
    ParentElement, Render, ScrollHandle, SharedString, StatefulInteractiveElement, Styled, Window,
    div, img, prelude::*, px,
};
use rovsti_core::{ThreadId, recents::LocaleTime, wire::DocumentOp};
use rovsti_ui::{
    Icon,
    components::{glass_icon_button, glass_pill},
    current_theme,
    field::{TextField, field_box},
    glass::{GlassSpec, glass},
    theme,
    tokens::{Theme, radius, typography},
};
use rovsti_wa_client::Gateway;

use crate::{
    backend::{Backend, BackendEvent},
    schedule_ui::{self, StripItem, StripKey},
};
use rovsti_wa_proto::{
    ChatState, GatewayEvent, Item, ItemState, MarkRead, MediaKind, MessageBody, SendReaction,
    SendText, Sent, Timeline, TypingState, TypingThrottle, check_size, classify, day_breaks,
    jid_user, reaction_chips,
};

/// Messages per history request.
const PAGE: u32 = 50;

/// The quick reactions offered by the long-press strip.
const QUICK_EMOJIS: [&str; 6] = ["👍", "❤️", "😂", "😮", "😢", "🙏"];

/// Downloaded images kept in memory.
const MEDIA_CACHE: usize = 16;

/// How long a "typing" event keeps the header line alive.
const TYPING_SHOW_MS: i64 = 6_000;

/// What a conversation tells the page that hosts it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ConversationEvent {
    /// The back button was tapped.
    Back,
    /// The header's channel switch was tapped: open the person's other thread.
    Switch(ThreadId),
    /// Long-press on Send: the text wants a time (the "Send later" sheet).
    Schedule(String),
    /// Cancel on a Scheduled strip row.
    CancelScheduled(StripKey),
    /// The header's bell: mute or unmute this chat's notifications.
    ToggleMute,
}

impl EventEmitter<ConversationEvent> for Conversation {}

/// A picked file waiting in the chip above the composer.
#[derive(Clone, Debug, PartialEq, Eq)]
struct Attached {
    path: String,
    name: String,
    /// The picker's MIME type, when it reported one.
    mime: Option<String>,
    kind: MediaKind,
}

/// Why a media send stopped before the gateway had it.
enum MediaError {
    /// The cached copy of the file is missing.
    Gone,
    TooLarge,
    Gateway(String),
}

/// A file read, classified and ready to upload.
struct Prepared {
    bytes: Vec<u8>,
    kind: MediaKind,
    mime: String,
}

pub struct Conversation {
    chat: String,
    title: SharedString,
    is_group: bool,
    timeline: Timeline,
    composer: Entity<TextField>,
    gateway: Gateway,
    next_local: u64,
    loading: bool,
    /// Incoming ids already reported to the gateway as read.
    marked: HashSet<String>,
    /// The last failed load, shown above the messages.
    error: Option<SharedString>,
    scroll: ScrollHandle,
    /// Decoded images by message id, oldest first (at most [`MEDIA_CACHE`]).
    media_cache: Vec<(String, Arc<Image>)>,
    media_loading: HashSet<String>,
    media_failed: HashSet<String>,
    /// The message whose quick-reaction strip is open.
    strip: Option<String>,
    throttle: TypingThrottle,
    /// Composer text at the last observation, to tell edits from re-renders.
    last_text: String,
    /// The peer is typing until this time.
    typing_until: Option<i64>,
    /// `(available, last_seen_ms)` of the peer in a 1:1 chat.
    presence: Option<(bool, Option<i64>)>,
    /// The phone's clock, from the backend.
    locale: LocaleTime,
    /// The same person's other thread, shown as a header pill.
    switch: Option<(ThreadId, crate::inbox::Badge)>,
    /// Waiting scheduled sends of this chat, set by the page.
    scheduled: Vec<StripItem>,
    muted: bool,
    backend: Entity<Backend>,
    /// The file picker is open and its answer is ours.
    awaiting_attachment: bool,
    attachment: Option<Attached>,
    /// Cached file of each pending or failed media send, by local id.
    pending_paths: HashMap<u64, String>,
    /// A line above the composer (attach and send problems).
    notice: Option<SharedString>,
    _subscriptions: Vec<gpui::Subscription>,
}

impl Conversation {
    pub fn new(
        chat: String,
        title: impl Into<SharedString>,
        is_group: bool,
        gateway: Gateway,
        locale: LocaleTime,
        backend: Entity<Backend>,
        cx: &mut Context<Self>,
    ) -> Self {
        let composer =
            cx.new(|cx| TextField::with_input(TextInputPurpose::Text, TextInputAction::Send, cx));
        // Typing re-renders the composer box.
        cx.observe(&composer, |this, _, cx| this.on_composer_changed(cx))
            .detach();
        let subscriptions = vec![cx.subscribe(&backend, |this, _, event, cx| {
            if let BackendEvent::Document(result) = event
                && result.op == DocumentOp::Attachment
            {
                this.on_attachment(
                    result.ok,
                    result.path.clone(),
                    result.name.clone(),
                    result.mime.clone(),
                    cx,
                );
            }
        })];
        let mut this = Self {
            timeline: Timeline::new(chat.clone()),
            chat,
            title: title.into(),
            is_group,
            composer,
            gateway,
            next_local: 1,
            loading: false,
            marked: HashSet::new(),
            error: None,
            scroll: ScrollHandle::new(),
            media_cache: Vec::new(),
            media_loading: HashSet::new(),
            media_failed: HashSet::new(),
            strip: None,
            throttle: TypingThrottle::new(),
            last_text: String::new(),
            typing_until: None,
            presence: None,
            locale,
            switch: None,
            scheduled: Vec::new(),
            muted: false,
            backend,
            awaiting_attachment: false,
            attachment: None,
            pending_paths: HashMap::new(),
            notice: None,
            _subscriptions: subscriptions,
        };
        this.load(None, cx);
        if !this.is_group {
            this.subscribe_presence(cx);
        }
        this
    }

    pub fn set_scheduled(&mut self, scheduled: Vec<StripItem>, cx: &mut Context<Self>) {
        if self.scheduled != scheduled {
            self.scheduled = scheduled;
            cx.notify();
        }
    }

    pub fn set_muted(&mut self, muted: bool, cx: &mut Context<Self>) {
        if self.muted != muted {
            self.muted = muted;
            cx.notify();
        }
    }

    /// The text was scheduled: the composer is done with it.
    pub fn clear_draft(&mut self, cx: &mut Context<Self>) {
        self.composer.update(cx, |field, cx| field.clear(cx));
        self.last_text.clear();
    }

    /// Long-press (or right-click) on Send. Only a typed text can wait.
    fn request_schedule(&mut self, cx: &mut Context<Self>) {
        let text = self.composer.read(cx).text().trim().to_owned();
        if !text.is_empty() {
            cx.emit(ConversationEvent::Schedule(text));
        }
    }

    pub fn set_locale(&mut self, locale: LocaleTime, cx: &mut Context<Self>) {
        if self.locale != locale {
            self.locale = locale;
            cx.notify();
        }
    }

    pub fn set_switch(
        &mut self,
        switch: Option<(ThreadId, crate::inbox::Badge)>,
        cx: &mut Context<Self>,
    ) {
        if self.switch != switch {
            self.switch = switch;
            cx.notify();
        }
    }

    fn subscribe_presence(&self, cx: &mut Context<Self>) {
        let gateway = self.gateway.clone();
        let chat = self.chat.clone();
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = gateway.subscribe_presence(&chat) {
                    log::warn!("subscribing to WhatsApp presence failed: {error}");
                }
            })
            .detach();
    }

    fn send_chat_state(&self, state: TypingState, cx: &mut Context<Self>) {
        let gateway = self.gateway.clone();
        let request = ChatState {
            chat: self.chat.clone(),
            state,
        };
        cx.background_executor()
            .spawn(async move {
                if let Err(error) = gateway.chat_state(&request) {
                    log::warn!("sending a WhatsApp chat state failed: {error}");
                }
            })
            .detach();
    }

    /// Composer text changed: tells the peer we type (throttled) or paused.
    fn on_composer_changed(&mut self, cx: &mut Context<Self>) {
        let text = self.composer.read(cx).text().to_owned();
        if text != self.last_text {
            let empty = text.trim().is_empty();
            self.last_text = text;
            if empty {
                if self.throttle.on_send() {
                    self.send_chat_state(TypingState::Paused, cx);
                }
            } else if self.throttle.on_edit(now_ms()) {
                self.send_chat_state(TypingState::Typing, cx);
            }
        }
        cx.notify();
    }

    /// Sends our reaction (`""` removes it) and shows it right away.
    fn react(
        &mut self,
        message_id: String,
        from_me: bool,
        sender: Option<String>,
        emoji: String,
        cx: &mut Context<Self>,
    ) {
        self.strip = None;
        self.timeline.set_own_reaction(&message_id, &emoji);
        let request = SendReaction {
            chat: self.chat.clone(),
            message_id,
            from_me,
            sender,
            emoji,
        };
        let gateway = self.gateway.clone();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.react(&request) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                log::warn!("sending a WhatsApp reaction failed: {error}");
                this.update(cx, |this, cx| {
                    this.error = Some(format!("Couldn't send the reaction: {error}").into());
                    cx.notify();
                })
                .ok();
            }
        })
        .detach();
        cx.notify();
    }

    fn cached_image(&self, id: &str) -> Option<Arc<Image>> {
        self.media_cache
            .iter()
            .find(|(cached, _)| cached == id)
            .map(|(_, image)| image.clone())
    }

    /// Keeps a decoded picture, dropping the oldest beyond [`MEDIA_CACHE`].
    fn cache_image(&mut self, id: String, image: Arc<Image>) {
        self.media_cache.retain(|(cached, _)| *cached != id);
        self.media_cache.push((id, image));
        if self.media_cache.len() > MEDIA_CACHE {
            self.media_cache.remove(0);
        }
    }

    /// Downloads the media of an image or sticker message off the UI thread.
    fn fetch_media(&mut self, id: String, mime: String, cx: &mut Context<Self>) {
        if self.media_loading.contains(&id) || self.cached_image(&id).is_some() {
            return;
        }
        let Some(format) = image_format(&mime) else {
            self.media_failed.insert(id);
            cx.notify();
            return;
        };
        self.media_failed.remove(&id);
        self.media_loading.insert(id.clone());
        let gateway = self.gateway.clone();
        let wanted = id.clone();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.media(&wanted) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.media_loading.remove(&id);
                match result {
                    Ok(bytes) => {
                        this.cache_image(id, Arc::new(Image::from_bytes(format, bytes)));
                    }
                    Err(error) => {
                        log::warn!("downloading WhatsApp media failed: {error}");
                        this.media_failed.insert(id);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Fetches one page (the newest, or the one before `before_ms`) off the
    /// UI thread.
    fn load(&mut self, before_ms: Option<i64>, cx: &mut Context<Self>) {
        if self.loading {
            return;
        }
        self.loading = true;
        let gateway = self.gateway.clone();
        let chat = self.chat.clone();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.messages(&chat, before_ms, PAGE) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.loading = false;
                match result {
                    Ok(page) => {
                        this.error = None;
                        this.timeline.apply_page(page, PAGE);
                        if before_ms.is_none() {
                            this.scroll.scroll_to_bottom();
                        }
                        this.mark_unread(cx);
                    }
                    Err(error) => {
                        log::warn!("loading WhatsApp messages failed: {error}");
                        this.error = Some(format!("Couldn't load messages: {error}").into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Tells the gateway about incoming messages that were not reported yet.
    fn mark_unread(&mut self, cx: &mut Context<Self>) {
        let ids: Vec<String> = self
            .timeline
            .unread_ids()
            .into_iter()
            .filter(|id| !self.marked.contains(id))
            .collect();
        if ids.is_empty() {
            return;
        }
        self.marked.extend(ids.iter().cloned());
        let request = MarkRead {
            chat: self.chat.clone(),
            sender: None,
            ids,
        };
        let gateway = self.gateway.clone();
        let task = cx
            .background_executor()
            .spawn(async move { gateway.mark_read(&request).map(|()| request.ids) });
        cx.spawn(async move |this, cx| {
            if let Err(error) = task.await {
                log::warn!("marking WhatsApp messages read failed: {error}");
                // Forget them so the next event tries again.
                this.update(cx, |this, _| this.marked.clear()).ok();
            }
        })
        .detach();
    }

    /// A gateway event the host observed. Events of other chats are ignored
    /// by the timeline.
    pub fn on_event(&mut self, event: &GatewayEvent, cx: &mut Context<Self>) {
        if matches!(event, GatewayEvent::Resync) {
            self.load(None, cx);
            return;
        }
        match event {
            GatewayEvent::Typing { chat, state, .. } if *chat == self.chat => {
                if *state == TypingState::Paused {
                    self.typing_until = None;
                } else {
                    self.typing_until = Some(now_ms() + TYPING_SHOW_MS);
                    // Repaint once the line has expired.
                    cx.spawn(async move |this, cx| {
                        cx.background_executor()
                            .timer(Duration::from_millis(TYPING_SHOW_MS as u64 + 100))
                            .await;
                        this.update(cx, |_, cx| cx.notify()).ok();
                    })
                    .detach();
                }
                cx.notify();
                return;
            }
            GatewayEvent::Presence {
                jid,
                available,
                last_seen_ms,
            } if !self.is_group && jid_user(jid) == jid_user(&self.chat) => {
                self.presence = Some((*available, *last_seen_ms));
                cx.notify();
                return;
            }
            GatewayEvent::Message(m) if m.chat == self.chat && !m.from_me => {
                self.typing_until = None;
            }
            _ => {}
        }
        if self.timeline.apply_event(event) {
            self.scroll.scroll_to_bottom();
            self.mark_unread(cx);
            cx.notify();
        }
    }

    /// "typing…", "online" or "last seen …" under the title.
    fn subtitle(&self, now: i64) -> Option<String> {
        if self.typing_until.is_some_and(|until| now < until) {
            return Some("typing…".to_owned());
        }
        if self.is_group {
            return None;
        }
        let (available, last_seen) = self.presence?;
        presence_label(available, last_seen, now, self.locale.tz_offset_s)
    }

    fn send(&mut self, cx: &mut Context<Self>) {
        let text = self.composer.read(cx).text().trim().to_owned();
        match send_plan(&text, self.attachment.is_some()) {
            Plan::Nothing => {}
            Plan::Text => {
                self.composer.update(cx, |field, cx| field.clear(cx));
                self.last_text.clear();
                self.send_text(text, cx);
            }
            Plan::Media { caption } => {
                let Some(attached) = self.attachment.take() else {
                    return;
                };
                self.composer.update(cx, |field, cx| field.clear(cx));
                self.last_text.clear();
                self.send_media(attached, caption, cx);
            }
        }
    }

    /// The paperclip: opens the system file picker.
    fn pick_attachment(&mut self, cx: &mut Context<Self>) {
        self.awaiting_attachment = true;
        self.notice = None;
        self.backend
            .update(cx, |backend, cx| backend.pick_attachment(cx));
        cx.notify();
    }

    fn on_attachment(
        &mut self,
        ok: bool,
        path: Option<String>,
        name: Option<String>,
        mime: Option<String>,
        cx: &mut Context<Self>,
    ) {
        if !std::mem::take(&mut self.awaiting_attachment) {
            return;
        }
        match path.filter(|_| ok) {
            Some(path) => {
                let name = name
                    .filter(|n| !n.is_empty())
                    .unwrap_or_else(|| crate::sms_conversation::attachment_label(&path));
                let (kind, _) = classify(mime.as_deref(), &name, &[]);
                self.attachment = Some(Attached {
                    path,
                    name,
                    mime,
                    kind,
                });
                self.notice = None;
            }
            None => {
                let words = if self.backend.read(cx).is_preview() {
                    "Attachments need an Android phone"
                } else {
                    "Couldn't attach that file"
                };
                self.notice = Some(words.into());
            }
        }
        cx.notify();
    }

    /// Shows the bubble at once, then uploads the file off the UI thread.
    fn send_media(&mut self, attached: Attached, caption: Option<String>, cx: &mut Context<Self>) {
        if self.throttle.on_send() {
            self.send_chat_state(TypingState::Paused, cx);
        }
        let local_id = self.next_local;
        self.next_local += 1;
        let (kind, mime) = classify(attached.mime.as_deref(), &attached.name, &[]);
        let size = std::fs::metadata(&attached.path).map_or(0, |m| m.len());
        self.timeline.push_pending_media(
            local_id,
            kind,
            mime.clone(),
            caption.clone(),
            size,
            now_ms(),
        );
        self.pending_paths.insert(local_id, attached.path.clone());
        self.scroll.scroll_to_bottom();
        self.dispatch_media(
            local_id,
            attached.path,
            attached.name,
            Some(mime),
            caption,
            cx,
        );
        cx.notify();
    }

    /// Reads, checks and classifies the file, then sends it; settles the bubble.
    fn dispatch_media(
        &mut self,
        local_id: u64,
        path: String,
        name: String,
        mime: Option<String>,
        caption: Option<String>,
        cx: &mut Context<Self>,
    ) {
        let read = cx.background_executor().spawn(async move {
            let bytes = std::fs::read(&path).map_err(|_| MediaError::Gone)?;
            check_size(bytes.len() as u64).map_err(|_| MediaError::TooLarge)?;
            let head = &bytes[..bytes.len().min(16)];
            let (kind, mime) = classify(mime.as_deref(), &name, head);
            Ok::<_, MediaError>(Prepared { bytes, kind, mime })
        });
        let gateway = self.gateway.clone();
        let chat = self.chat.clone();
        cx.spawn(async move |this, cx| {
            let prepared = match read.await {
                Ok(prepared) => prepared,
                Err(error) => {
                    this.update(cx, |this, cx| this.media_failed_send(local_id, error, cx))
                        .ok();
                    return;
                }
            };
            // The picture shows under the pending id while it uploads.
            let shown = this
                .update(cx, |this, cx| {
                    let format = (prepared.kind == MediaKind::Image)
                        .then(|| image_format(&prepared.mime))
                        .flatten();
                    let shown = format
                        .map(|format| Arc::new(Image::from_bytes(format, prepared.bytes.clone())));
                    if let Some(image) = &shown {
                        this.cache_image(format!("pending-{local_id}"), image.clone());
                        cx.notify();
                    }
                    shown
                })
                .ok()
                .flatten();
            let Prepared { bytes, kind, mime } = prepared;
            let send = cx.background_executor().spawn(async move {
                gateway
                    .send_media(&chat, kind, &mime, caption.as_deref(), bytes)
                    .map_err(|error| MediaError::Gateway(error.to_string()))
            });
            let result = send.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(sent) => this.media_sent(local_id, &sent, shown),
                    Err(error) => this.media_failed_send(local_id, error, cx),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn media_sent(&mut self, local_id: u64, sent: &Sent, shown: Option<Arc<Image>>) {
        self.timeline.confirm(local_id, sent);
        // The cache copy of the picked file has done its job.
        if let Some(path) = self.pending_paths.remove(&local_id)
            && let Err(error) = std::fs::remove_file(&path)
        {
            log::debug!("removing the sent attachment copy failed: {error}");
        }
        let pending = format!("pending-{local_id}");
        self.media_cache.retain(|(cached, _)| *cached != pending);
        if let Some(image) = shown {
            self.cache_image(sent.message_id.clone(), image);
        }
    }

    fn media_failed_send(&mut self, local_id: u64, error: MediaError, cx: &mut Context<Self>) {
        self.timeline.fail(local_id);
        self.notice = Some(match error {
            MediaError::Gone => "The file is gone; attach it again".into(),
            MediaError::TooLarge => format!(
                "That file is over {} MB",
                rovsti_wa_proto::MAX_SEND_BYTES / (1024 * 1024)
            )
            .into(),
            MediaError::Gateway(message) => {
                log::warn!("sending WhatsApp media failed: {message}");
                "Couldn't send the file".into()
            }
        });
        cx.notify();
    }

    /// Sends `text` now: the composer's, or one typed into a notification.
    pub fn send_text(&mut self, text: String, cx: &mut Context<Self>) {
        let text = text.trim().to_owned();
        if text.is_empty() {
            return;
        }
        if self.throttle.on_send() {
            self.send_chat_state(TypingState::Paused, cx);
        }
        let local_id = self.next_local;
        self.next_local += 1;
        self.timeline.push_pending(local_id, text.clone(), now_ms());
        self.scroll.scroll_to_bottom();
        self.dispatch(local_id, text, cx);
        cx.notify();
    }

    fn retry(&mut self, local_id: u64, cx: &mut Context<Self>) {
        match self.timeline.restart_body(local_id) {
            Some(MessageBody::Text(text)) => self.dispatch(local_id, text, cx),
            Some(MessageBody::Media { caption, mime, .. }) => {
                match self.pending_paths.get(&local_id).cloned() {
                    Some(path) => {
                        let name = crate::sms_conversation::attachment_label(&path);
                        self.notice = None;
                        self.dispatch_media(local_id, path, name, Some(mime), caption, cx);
                    }
                    None => self.media_failed_send(local_id, MediaError::Gone, cx),
                }
            }
            Some(MessageBody::Unsupported) => self.timeline.fail(local_id),
            None => return,
        }
        cx.notify();
    }

    /// Sends one pending text off the UI thread and settles its bubble.
    fn dispatch(&mut self, local_id: u64, text: String, cx: &mut Context<Self>) {
        let gateway = self.gateway.clone();
        let request = SendText {
            chat: self.chat.clone(),
            text,
            quoted_id: None,
        };
        let task = cx
            .background_executor()
            .spawn(async move { gateway.send_text(&request) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(sent) => this.timeline.confirm(local_id, &sent),
                    Err(error) => {
                        log::warn!("sending a WhatsApp message failed: {error}");
                        this.timeline.fail(local_id);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn bubble(
        &self,
        theme: Theme,
        item: &Item,
        now: i64,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let colors = theme::colors(theme);
        let message = &item.message;
        let own = message.from_me;
        let failed = matches!(item.state, ItemState::Failed { .. });
        let (fill, text, quiet) = if own {
            let white = gpui::white();
            (colors.accent, white, white.opacity(0.72))
        } else {
            (
                colors.secondary_background,
                colors.label,
                colors.tertiary_label,
            )
        };
        let meta_color = if failed {
            colors.red
        } else if item.state == ItemState::Read {
            gpui::white()
        } else {
            quiet
        };
        let meta: SharedString = if failed {
            "Not sent · Tap to retry".into()
        } else {
            bubble_meta(item, now, self.locale.tz_offset_s).into()
        };

        let mut bubble = div()
            .id(SharedString::from(format!("msg-{}", message.id)))
            .max_w(px(290.0))
            .px(px(12.0))
            .py(px(7.0))
            .rounded(radius::CARD)
            .bg(fill)
            .flex()
            .flex_col()
            .gap(px(2.0));
        if self.is_group && !own {
            bubble = bubble.child(
                div()
                    .text_size(typography::CAPTION.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.accent)
                    .child(sender_label(message.sender.as_deref().unwrap_or(""))),
            );
        }
        bubble = bubble
            .child(self.body(theme, message, text, quiet, cx))
            .child(
                div()
                    .self_end()
                    .text_size(typography::CAPTION.size)
                    .text_color(meta_color)
                    .child(meta),
            );
        let confirmed = !matches!(
            item.state,
            ItemState::Pending { .. } | ItemState::Failed { .. }
        );
        if let ItemState::Failed { local_id } = item.state {
            bubble = bubble.on_click(cx.listener(move |this, _, _, cx| this.retry(local_id, cx)));
        } else if confirmed {
            let id = message.id.clone();
            bubble = bubble.on_click(cx.listener(move |this, _, _, cx| {
                if this.strip.take().is_some() {
                    cx.notify();
                }
            }));
            bubble = bubble.on_long_press(cx.listener(move |this, _, _, cx| {
                this.strip = Some(id.clone());
                cx.notify();
            }));
        }

        let mut column = div()
            .flex()
            .flex_col()
            .gap(px(2.0))
            .when(own, |c| c.items_end())
            .child(bubble);
        let chips = reaction_chips(&message.reactions);
        if !chips.is_empty() {
            column = column.child(self.chips(theme, message, &chips, cx));
        }
        if confirmed && self.strip.as_deref() == Some(message.id.as_str()) {
            column = column.child(self.quick_strip(theme, message, cx));
        }
        div()
            .flex()
            .when(own, |row| row.justify_end())
            .child(column)
            .into_any_element()
    }

    /// The content of a bubble: text, or a media card.
    fn body(
        &self,
        theme: Theme,
        message: &rovsti_wa_proto::WaMessage,
        text: gpui::Hsla,
        quiet: gpui::Hsla,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let MessageBody::Media {
            kind,
            caption,
            mime,
            size,
        } = &message.body
        else {
            return div()
                .text_size(typography::BODY.size)
                .text_color(text)
                .child(body_text(&message.body))
                .into_any_element();
        };
        let colors = theme::colors(theme);
        let downloadable = matches!(kind, MediaKind::Image | MediaKind::Sticker);
        let mut card = div().flex().flex_col().gap(px(4.0));
        if let Some(image) = self.cached_image(&message.id) {
            card = card.child(
                img(image)
                    .max_w(px(240.0))
                    .max_h(px(320.0))
                    .rounded(radius::CARD),
            );
        } else {
            let status = if self.media_loading.contains(&message.id) {
                "Loading…"
            } else if self.media_failed.contains(&message.id) {
                "Couldn't load · Tap to retry"
            } else if downloadable {
                "Tap to load"
            } else {
                ""
            };
            let mut inner = div()
                .id(SharedString::from(format!("media-{}", message.id)))
                .px(px(10.0))
                .py(px(8.0))
                .rounded(radius::CARD)
                .bg(colors.label.opacity(0.08))
                .flex()
                .flex_col()
                .child(
                    div()
                        .text_size(typography::BODY.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(text)
                        .child(media_label(*kind)),
                )
                .child(
                    div()
                        .text_size(typography::CAPTION.size)
                        .text_color(quiet)
                        .child(if status.is_empty() {
                            format_size(*size)
                        } else {
                            format!("{} · {status}", format_size(*size))
                        }),
                );
            if downloadable {
                let id = message.id.clone();
                let mime = mime.clone();
                inner = inner.on_click(cx.listener(move |this, _, _, cx| {
                    this.fetch_media(id.clone(), mime.clone(), cx);
                }));
            }
            card = card.child(inner);
        }
        if let Some(caption) = caption.as_deref().filter(|c| !c.is_empty()) {
            card = card.child(
                div()
                    .text_size(typography::BODY.size)
                    .text_color(text)
                    .child(caption.to_owned()),
            );
        }
        card.into_any_element()
    }

    /// Reaction chips under a bubble; tapping our own removes it.
    fn chips(
        &self,
        theme: Theme,
        message: &rovsti_wa_proto::WaMessage,
        chips: &[(String, u32, bool)],
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let colors = theme::colors(theme);
        let mut row = div().flex().flex_row().gap(px(4.0)).px(px(6.0));
        for (index, (emoji, count, mine)) in chips.iter().enumerate() {
            let label = if *count > 1 {
                format!("{emoji} {count}")
            } else {
                emoji.clone()
            };
            let mut chip = div()
                .id(SharedString::from(format!("chip-{}-{index}", message.id)))
                .px(px(8.0))
                .py(px(2.0))
                .rounded_full()
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.label)
                .bg(if *mine {
                    colors.accent.opacity(0.25)
                } else {
                    colors.secondary_background
                })
                .child(label);
            if *mine {
                let id = message.id.clone();
                let from_me = message.from_me;
                let sender = message.sender.clone();
                chip = chip.on_click(cx.listener(move |this, _, _, cx| {
                    this.react(id.clone(), from_me, sender.clone(), String::new(), cx);
                }));
            }
            row = row.child(chip);
        }
        row.into_any_element()
    }

    /// The glass strip of quick emojis opened by a long press.
    fn quick_strip(
        &self,
        theme: Theme,
        message: &rovsti_wa_proto::WaMessage,
        cx: &mut Context<Self>,
    ) -> gpui::AnyElement {
        let mine = message
            .reactions
            .iter()
            .find(|r| r.from_me)
            .map(|r| r.emoji.as_str());
        let mut row = glass(GlassSpec::control(theme))
            .rounded_full()
            .px(px(8.0))
            .py(px(4.0))
            .flex()
            .flex_row()
            .gap(px(4.0));
        for emoji in QUICK_EMOJIS {
            let id = message.id.clone();
            let from_me = message.from_me;
            let sender = message.sender.clone();
            // Picking the emoji already set takes it back.
            let send = if mine == Some(emoji) { "" } else { emoji };
            row = row.child(
                div()
                    .id(SharedString::from(format!("quick-{}-{emoji}", message.id)))
                    .px(px(6.0))
                    .py(px(2.0))
                    .text_size(typography::TITLE3.size)
                    .child(emoji)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.react(id.clone(), from_me, sender.clone(), send.to_owned(), cx);
                    })),
            );
        }
        row.into_any_element()
    }
}

impl Render for Conversation {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let now = now_ms();
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));

        let offset = self.locale.tz_offset_s;
        let breaks = day_breaks(self.timeline.items(), offset);
        let mut rows: Vec<gpui::AnyElement> = Vec::new();
        let mut next_break = breaks.iter().peekable();
        for (index, item) in self.timeline.items().iter().enumerate() {
            if let Some(&(_, day_ms)) = next_break.next_if(|(at, _)| *at == index) {
                rows.push(
                    div()
                        .flex()
                        .justify_center()
                        .py(px(6.0))
                        .text_size(typography::CAPTION.size)
                        .text_color(colors.secondary_label)
                        .child(day_label(day_ms, now, offset))
                        .into_any_element(),
                );
            }
            rows.push(self.bubble(theme, item, now, cx));
        }

        let older = self.timeline.has_older().then(|| {
            let label = if self.loading {
                "Loading…"
            } else {
                "Load earlier"
            };
            div()
                .id("conv-older")
                .flex()
                .justify_center()
                .py(px(8.0))
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.accent)
                .child(label)
                .on_click(cx.listener(|this, _, _, cx| {
                    if let Some(oldest) = this.timeline.oldest_ms() {
                        // The gateway's bound is exclusive and WhatsApp stamps whole
                        // seconds: +1 re-reads messages that share the oldest
                        // stamp (the timeline dedups by id) instead of skipping them.
                        this.load(Some(oldest.saturating_add(1)), cx);
                    }
                }))
        });
        let status = self.error.clone().map(|error| {
            div()
                .py(px(8.0))
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.red)
                .child(error)
        });
        let empty = (rows.is_empty() && !self.loading && self.error.is_none()).then(|| {
            div()
                .py(px(48.0))
                .flex()
                .justify_center()
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child("No messages yet")
        });

        let back = glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
            .id("conv-back")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(ConversationEvent::Back)))
            .into_any_element();
        let switch = self.switch.clone().map(|(thread, badge)| {
            glass_pill(theme, badge.label())
                .id("conv-switch")
                .on_click(cx.listener(move |_, _, _, cx| {
                    cx.emit(ConversationEvent::Switch(thread.clone()));
                }))
                .into_any_element()
        });
        let mute = glass_icon_button(theme, Icon::LucideBellOff, 0.0, self.muted)
            .id("conv-mute")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(ConversationEvent::ToggleMute)))
            .into_any_element();
        let trailing = div()
            .flex()
            .items_center()
            .gap(px(8.0))
            .children(switch)
            .child(mute)
            .into_any_element();
        let header = header_bar(
            theme,
            back,
            self.title.clone(),
            self.subtitle(now),
            Some(trailing),
        );
        let strip = schedule_ui::strip(
            theme,
            &self.scheduled,
            now,
            offset,
            cx,
            |_: &mut Self, key, cx| cx.emit(ConversationEvent::CancelScheduled(key)),
        );

        let field = div()
            .flex_1()
            .min_w_0()
            .flex()
            .flex_col()
            .child(field_box(
                theme,
                "conv-field",
                &self.composer,
                "Message",
                window,
                cx,
            ))
            .into_any_element();
        let send = glass_icon_button(theme, Icon::LucideArrowUp, 0.0, false)
            .id("conv-send")
            .on_click(cx.listener(|this, _, _, cx| this.send(cx)))
            .on_long_press(cx.listener(|this, _, _, cx| this.request_schedule(cx)))
            .on_mouse_down(
                gpui::MouseButton::Right,
                cx.listener(|this, _, _, cx| this.request_schedule(cx)),
            )
            .into_any_element();
        let attach = glass_icon_button(theme, Icon::LucidePlus, 0.0, false)
            .id("conv-attach")
            .on_click(cx.listener(|this, _, _, cx| this.pick_attachment(cx)))
            .into_any_element();
        let composer = composer_bar(keyboard, Some(attach), field, send);
        let colors = theme::colors(theme);
        let info = (self.attachment.is_some() || self.notice.is_some()).then(|| {
            let chip = self.attachment.as_ref().map(|attached| {
                glass_pill(
                    theme,
                    format!("{} ✕", attach_chip_label(&attached.name, attached.kind)),
                )
                .id("conv-attached")
                .h(px(30.0))
                .px(px(12.0))
                .text_size(typography::FOOTNOTE.size)
                .on_click(cx.listener(|this, _, _, cx| {
                    this.attachment = None;
                    cx.notify();
                }))
            });
            div()
                .flex_none()
                .flex()
                .flex_col()
                .items_start()
                .gap(px(4.0))
                .px(px(16.0))
                .children(chip)
                .children(self.notice.clone().map(|notice| {
                    div()
                        .text_size(typography::CAPTION.size)
                        .text_color(colors.red)
                        .child(notice)
                }))
        });

        div()
            .size_full()
            .flex()
            .flex_col()
            .child(header)
            .child(
                div()
                    .id("conv-scroll")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .track_scroll(&self.scroll)
                    .px(px(12.0))
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap(px(4.0))
                            .children(older)
                            .children(status)
                            .children(empty)
                            .children(rows),
                    ),
            )
            .children(strip)
            .children(info)
            .child(composer)
    }
}

/// The strip above a conversation, shared by the WhatsApp and SMS views: back
/// button, title with an optional line under it, and an optional trailing
/// control (the channel switch).
pub(crate) fn header_bar(
    theme: Theme,
    back: gpui::AnyElement,
    title: SharedString,
    subtitle: Option<String>,
    trailing: Option<gpui::AnyElement>,
) -> gpui::Div {
    let colors = theme::colors(theme);
    div()
        .flex_none()
        .flex()
        .items_center()
        .gap(px(8.0))
        .px(px(8.0))
        .pt(px(48.0))
        .pb(px(8.0))
        .child(back)
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .child(
                    div()
                        .truncate()
                        .text_size(typography::TITLE3.size)
                        .font_weight(typography::TITLE3.weight)
                        .text_color(colors.label)
                        .child(title),
                )
                .children(subtitle.map(|line| {
                    div()
                        .truncate()
                        .text_size(typography::CAPTION.size)
                        .text_color(colors.secondary_label)
                        .child(line)
                })),
        )
        .children(trailing)
}

/// The strip under a conversation: an optional leading button (attach), the
/// message field and the send button, lifted by the keyboard.
pub(crate) fn composer_bar(
    keyboard: gpui::Pixels,
    leading: Option<gpui::AnyElement>,
    field: gpui::AnyElement,
    send: gpui::AnyElement,
) -> gpui::Div {
    div()
        .flex_none()
        .flex()
        .items_center()
        .gap(px(8.0))
        .px(px(12.0))
        .pt(px(8.0))
        .pb(px(12.0) + keyboard)
        .children(leading)
        .child(field)
        .child(send)
}

pub(crate) fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// `HH:MM` of a timestamp at `offset_s` from UTC.
pub(crate) fn clock(ms: i64, offset_s: i32) -> String {
    let secs = (ms.div_euclid(1000) + i64::from(offset_s)).rem_euclid(86_400);
    format!("{:02}:{:02}", secs / 3600, secs % 3600 / 60)
}

/// The line under a bubble's text: the time, then "Sending…", "Not sent" or
/// the delivery ticks for our own messages. Within a minute the time reads
/// "Now".
pub fn bubble_meta(item: &Item, now_ms: i64, offset_s: i32) -> String {
    meta_at(item, now_ms, offset_s)
}

fn meta_at(item: &Item, now_ms: i64, offset_s: i32) -> String {
    let ts = item.message.timestamp_ms;
    let time = if (0..60_000).contains(&(now_ms - ts)) {
        "Now".to_owned()
    } else {
        clock(ts, offset_s)
    };
    match item.state {
        ItemState::Pending { .. } => "Sending…".to_owned(),
        ItemState::Failed { .. } => "Not sent".to_owned(),
        ItemState::Sent if item.message.from_me => format!("{time} ✓"),
        ItemState::Delivered | ItemState::Read if item.message.from_me => format!("{time} ✓✓"),
        _ => time,
    }
}

/// `+905321234567@s.whatsapp.net` shown as `+905321234567`.
fn sender_label(jid: &str) -> String {
    let user = jid.split(['@', ':']).next().unwrap_or("");
    if user.is_empty() {
        String::new()
    } else {
        format!("+{user}")
    }
}

/// The header line for a peer's presence.
fn presence_label(
    available: bool,
    last_seen_ms: Option<i64>,
    now: i64,
    offset_s: i32,
) -> Option<String> {
    if available {
        return Some("online".to_owned());
    }
    let ts = last_seen_ms?;
    let day_start = ts - (ts.div_euclid(1000) + i64::from(offset_s)).rem_euclid(86_400) * 1000;
    let when = match day_label(day_start, now, offset_s).as_str() {
        "Today" => format!("today at {}", clock(ts, offset_s)),
        "Yesterday" => format!("yesterday at {}", clock(ts, offset_s)),
        date => format!("{date} at {}", clock(ts, offset_s)),
    };
    Some(format!("last seen {when}"))
}

/// The gpui image format for a MIME type, `None` when it cannot be shown.
fn image_format(mime: &str) -> Option<ImageFormat> {
    match mime.split(';').next()?.trim() {
        "image/png" => Some(ImageFormat::Png),
        "image/jpeg" | "image/jpg" => Some(ImageFormat::Jpeg),
        "image/webp" => Some(ImageFormat::Webp),
        "image/gif" => Some(ImageFormat::Gif),
        _ => None,
    }
}

/// `1.4 MB`, `820 KB`, `12 B`.
fn format_size(bytes: u64) -> String {
    const KB: u64 = 1024;
    const MB: u64 = KB * 1024;
    if bytes >= MB {
        format!("{:.1} MB", bytes as f64 / MB as f64)
    } else if bytes >= KB {
        format!("{} KB", bytes / KB)
    } else {
        format!("{bytes} B")
    }
}

/// What Send does with the composer text and the attachment chip.
#[derive(Clone, Debug, PartialEq, Eq)]
enum Plan {
    Nothing,
    Text,
    /// The text, when there is any, becomes the caption.
    Media {
        caption: Option<String>,
    },
}

fn send_plan(text: &str, has_chip: bool) -> Plan {
    let text = text.trim();
    match (has_chip, text.is_empty()) {
        (true, true) => Plan::Media { caption: None },
        (true, false) => Plan::Media {
            caption: Some(text.to_owned()),
        },
        (false, false) => Plan::Text,
        (false, true) => Plan::Nothing,
    }
}

/// The chip's text: the file name and what it will be sent as.
fn attach_chip_label(name: &str, kind: MediaKind) -> String {
    format!("{name} · {}", media_label(kind))
}

fn media_label(kind: MediaKind) -> &'static str {
    match kind {
        MediaKind::Image => "Photo",
        MediaKind::Video => "Video",
        MediaKind::Audio => "Voice message",
        MediaKind::Document => "Document",
        MediaKind::Sticker => "Sticker",
    }
}

/// The text a bubble shows for a body.
fn body_text(body: &MessageBody) -> String {
    match body {
        MessageBody::Text(text) => text.clone(),
        MessageBody::Media { kind, caption, .. } => {
            let label = media_label(*kind);
            match caption.as_deref().filter(|c| !c.is_empty()) {
                Some(caption) => format!("{label}\n{caption}"),
                None => label.to_owned(),
            }
        }
        MessageBody::Unsupported => "Unsupported message".to_owned(),
    }
}

/// Days since 1970-01-01 as (year, month 1..=12, day).
pub(crate) fn civil_from_days(days: i64) -> (i64, usize, i64) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = yoe + era * 400 + i64::from(month <= 2);
    (year, usize::try_from(month).unwrap_or(1), day)
}

/// The separator above a day: "Today", "Yesterday" or "5 Nov 2026".
pub(crate) fn day_label(day_start_ms: i64, now_ms: i64, offset_s: i32) -> String {
    const MONTHS: [&str; 12] = [
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    ];
    let day_of = |ms: i64| (ms.div_euclid(1000) + i64::from(offset_s)).div_euclid(86_400);
    let day = day_of(day_start_ms);
    match day_of(now_ms) - day {
        0 => "Today".to_owned(),
        1 => "Yesterday".to_owned(),
        _ => {
            let (year, month, d) = civil_from_days(day);
            format!("{d} {} {year}", MONTHS[month - 1])
        }
    }
}

#[cfg(test)]
mod tests {
    use rovsti_wa_proto::WaMessage;

    use super::*;

    fn item(ts: i64, from_me: bool, state: ItemState) -> Item {
        Item {
            message: WaMessage {
                id: "a".into(),
                chat: "1@s.whatsapp.net".into(),
                sender: None,
                from_me,
                timestamp_ms: ts,
                body: MessageBody::Text("hi".into()),
                reactions: vec![],
            },
            state,
        }
    }

    // 2026-11-05 14:05:00 UTC
    const T: i64 = 1_793_887_500_000;

    #[test]
    fn bubble_meta_shows_time_and_state() {
        let later = T + 3_600_000;
        assert_eq!(
            meta_at(&item(T, true, ItemState::Sent), later, 0),
            "14:05 ✓"
        );
        assert_eq!(
            meta_at(&item(T, true, ItemState::Delivered), later, 0),
            "14:05 ✓✓"
        );
        assert_eq!(
            meta_at(&item(T, true, ItemState::Read), later, 0),
            "14:05 ✓✓"
        );
        assert_eq!(meta_at(&item(T, false, ItemState::Sent), later, 0), "14:05");
        assert_eq!(
            meta_at(&item(T, true, ItemState::Pending { local_id: 1 }), later, 0),
            "Sending…"
        );
        assert_eq!(
            meta_at(&item(T, true, ItemState::Failed { local_id: 1 }), later, 0),
            "Not sent"
        );
        assert_eq!(
            meta_at(&item(T, false, ItemState::Sent), T + 5_000, 0),
            "Now"
        );
        // The offset moves the clock; a minute ago is not "Now".
        assert_eq!(
            meta_at(&item(T, false, ItemState::Sent), later, 3 * 3600),
            "17:05"
        );
        assert_eq!(
            bubble_meta(&item(T, false, ItemState::Sent), later, 0),
            "14:05"
        );
    }

    #[test]
    fn sender_and_body_text() {
        assert_eq!(sender_label("905321234567@s.whatsapp.net"), "+905321234567");
        assert_eq!(
            sender_label("905321234567:12@s.whatsapp.net"),
            "+905321234567"
        );
        assert_eq!(sender_label(""), "");
        assert_eq!(
            body_text(&MessageBody::Media {
                kind: MediaKind::Image,
                caption: Some("sunset".into()),
                mime: "image/jpeg".into(),
                size: 10,
            }),
            "Photo\nsunset"
        );
        assert_eq!(body_text(&MessageBody::Unsupported), "Unsupported message");
    }

    #[test]
    fn presence_lines() {
        let day = T - T.rem_euclid(86_400_000);
        assert_eq!(presence_label(true, None, T, 0).as_deref(), Some("online"));
        assert_eq!(presence_label(false, None, T, 0), None);
        assert_eq!(
            presence_label(false, Some(T - 3_600_000), T, 0).as_deref(),
            Some("last seen today at 13:05")
        );
        assert_eq!(
            presence_label(false, Some(day - 1_000), T, 0).as_deref(),
            Some("last seen yesterday at 23:59")
        );
        assert_eq!(
            presence_label(false, Some(day - 3 * 86_400_000 + 60_000), T, 0).as_deref(),
            Some("last seen 2 Nov 2026 at 00:01")
        );
    }

    #[test]
    fn media_helpers() {
        assert_eq!(image_format("image/jpeg"), Some(ImageFormat::Jpeg));
        assert_eq!(image_format("image/webp; q=1"), Some(ImageFormat::Webp));
        assert_eq!(image_format("video/mp4"), None);
        assert_eq!(format_size(12), "12 B");
        assert_eq!(format_size(2048), "2 KB");
        assert_eq!(format_size(1_572_864), "1.5 MB");
    }

    #[test]
    fn send_plans() {
        assert_eq!(send_plan("", false), Plan::Nothing);
        assert_eq!(send_plan("  \n", false), Plan::Nothing);
        assert_eq!(send_plan(" hi ", false), Plan::Text);
        assert_eq!(send_plan("", true), Plan::Media { caption: None });
        assert_eq!(send_plan("  ", true), Plan::Media { caption: None });
        assert_eq!(
            send_plan(" look ", true),
            Plan::Media {
                caption: Some("look".into())
            }
        );
    }

    #[test]
    fn chip_labels() {
        assert_eq!(
            attach_chip_label("a.jpg", MediaKind::Image),
            "a.jpg · Photo"
        );
        assert_eq!(
            attach_chip_label("cv.pdf", MediaKind::Document),
            "cv.pdf · Document"
        );
        assert_eq!(
            attach_chip_label("v.mp4", MediaKind::Video),
            "v.mp4 · Video"
        );
    }

    #[test]
    fn day_labels() {
        let day = T - T.rem_euclid(86_400_000);
        assert_eq!(day_label(day, T, 0), "Today");
        assert_eq!(day_label(day - 86_400_000, T, 0), "Yesterday");
        assert_eq!(day_label(day - 3 * 86_400_000, T, 0), "2 Nov 2026");
        assert_eq!(day_label(0, T, 0), "1 Jan 1970");
    }
}
