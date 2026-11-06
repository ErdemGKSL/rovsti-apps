//! One SMS/MMS conversation: pages from the provider through the backend,
//! optimistic sends, delivery ticks, a SIM chip and a composer. The merge of
//! pages and `sms_status` events is `rovsti_core::sms_timeline::SmsTimeline`;
//! this module is the GPUI shell around it plus a few pure text helpers.

use std::{collections::HashMap, path::PathBuf};

use gpui::{
    AnyElement, Context, Entity, EventEmitter, InteractiveElement, IntoElement, ListAlignment,
    ParentElement, Render, SharedString, StatefulInteractiveElement, Styled, TextInputAction,
    TextInputPurpose, Window, div, img, list, prelude::*, px,
};
use rovsti_core::{
    ThreadId,
    sms::{DeliveryState, SmsSend, SmsSimsPayload, SmsStatus, segments},
    sms_timeline::{SmsItem, SmsKey, SmsTimeline},
    wire::{DocumentOp, DocumentResult},
};
use rovsti_ui::{
    Icon,
    components::{glass_icon_button, glass_pill},
    current_theme,
    field::{TextField, field_box},
    theme,
    tokens::{Theme, radius, typography},
};

use crate::{
    backend::{Backend, BackendEvent, now_ms, sim_for},
    conversation::{clock, composer_bar, day_label, header_bar},
    inbox::Badge,
    keyed_list::{KeyedList, key_of},
    schedule_ui::{self, StripItem, StripKey},
};

/// Messages per provider page.
const PAGE: usize = 50;

/// The counter shows once a message is this close to a part boundary.
const COUNTER_FROM: u32 = 30;

/// What the conversation tells the page that hosts it.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum SmsConversationEvent {
    Back,
    /// The header's channel switch: open the person's other thread.
    Switch(ThreadId),
    /// Long-press on Send: the text wants a time (the "Send later" sheet).
    Schedule {
        to: Vec<String>,
        body: String,
        sub_id: Option<i32>,
    },
    /// Cancel on a Scheduled strip row.
    CancelScheduled(StripKey),
    /// The header's bell: mute or unmute this thread's notifications.
    ToggleMute,
    /// A copy pill under a selected bubble. `sensitive` hides it from the paste preview.
    Copy {
        text: String,
        sensitive: bool,
    },
    /// A share pill under a selected bubble: the page opens the share sheet.
    Share(String),
    /// The header's more button: the conversation menu.
    Menu,
    /// A short confirmation for the page's pill, such as "Message deleted".
    Notice(&'static str),
}

impl EventEmitter<SmsConversationEvent> for SmsConversation {}

/// One item of the timeline list after its "Load earlier" slot.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Entry {
    /// A day starts at the timeline item at this index.
    Day(usize),
    /// The timeline item at this index.
    Message(usize),
}

pub struct SmsConversation {
    backend: Entity<Backend>,
    /// A negative id is a draft: a new conversation with no provider thread yet.
    thread_id: i64,
    title: SharedString,
    recipients: Vec<String>,
    timeline: SmsTimeline,
    composer: Entity<TextField>,
    /// The composer's action-key count already acted on.
    seen_actions: u64,
    /// The virtualised, bottom-aligned timeline: item 0 is the "Load earlier"
    /// slot, then one item per [`Entry`].
    list: KeyedList,
    /// Day breaks and messages in timeline order, rebuilt when `rows_dirty`.
    entries: Vec<Entry>,
    /// The timeline changed since `entries`.
    rows_dirty: bool,
    /// The clock offset `entries` cut days with.
    rows_offset: i32,
    /// "Now" and the offset for the frame being drawn (rows are built after `render`).
    frame_now: i64,
    loading: bool,
    /// The provider changed while a page was loading: read the newest again.
    reload_due: bool,
    error: Option<SharedString>,
    /// A line above the composer ("Couldn't attach that file").
    notice: Option<SharedString>,
    /// The SIM the user picked here; the backend remembers it per thread.
    chosen_sim: Option<i32>,
    /// A picture picked for the next send (a path in the app's cache).
    attachment: Option<String>,
    /// The picked file's own name, for the chip (the cache copy has none).
    attachment_name: Option<String>,
    awaiting_attachment: bool,
    /// `local_id -> (text, image path)` of MMS sends, so a retry resends the picture.
    mms_sends: HashMap<u64, (String, String)>,
    switch: Option<(ThreadId, Badge)>,
    /// Waiting scheduled sends of this thread, set by the page.
    scheduled: Vec<StripItem>,
    strip_expanded: bool,
    muted: bool,
    /// The bubble (by `message_key`) whose copy pills are showing.
    selected: Option<u64>,
    _subscriptions: Vec<gpui::Subscription>,
}

impl SmsConversation {
    pub fn new(
        backend: Entity<Backend>,
        thread_id: i64,
        title: impl Into<SharedString>,
        recipients: Vec<String>,
        cx: &mut Context<Self>,
    ) -> Self {
        let composer = cx.new(|cx| {
            let mut field =
                TextField::with_input(TextInputPurpose::Text, TextInputAction::Send, cx);
            field.set_keep_focus_on_action(true);
            field
        });
        let mut timeline = SmsTimeline::new(thread_id);
        timeline.set_recipients(recipients.clone());
        let subscriptions = vec![
            // Typing re-renders the counter; the keyboard's Send key sends.
            cx.observe(&composer, |this: &mut Self, composer, cx| {
                let actions = composer.read(cx).actions();
                if actions != this.seen_actions {
                    this.seen_actions = actions;
                    this.send(cx);
                }
                cx.notify();
            }),
            // Role, SIMs and the clock live in the backend.
            cx.observe(&backend, |_, _, cx| cx.notify()),
            cx.subscribe(&backend, |this, _, event, cx| this.on_backend(event, cx)),
        ];
        let mut this = Self {
            backend,
            thread_id,
            title: title.into(),
            recipients,
            timeline,
            composer,
            seen_actions: 0,
            list: KeyedList::new(ListAlignment::Bottom, 1),
            entries: Vec::new(),
            rows_dirty: true,
            rows_offset: 0,
            frame_now: 0,
            loading: false,
            reload_due: false,
            error: None,
            notice: None,
            chosen_sim: None,
            attachment: None,
            attachment_name: None,
            awaiting_attachment: false,
            mms_sends: HashMap::new(),
            switch: None,
            scheduled: Vec::new(),
            strip_expanded: false,
            muted: false,
            selected: None,
            _subscriptions: subscriptions,
        };
        this.fill_from_thread(cx);
        this.load(None, cx);
        this
    }

    pub fn thread(&self) -> ThreadId {
        ThreadId::Sms(self.thread_id)
    }

    pub fn set_switch(&mut self, switch: Option<(ThreadId, Badge)>, cx: &mut Context<Self>) {
        if self.switch != switch {
            self.switch = switch;
            cx.notify();
        }
    }

    pub fn title(&self) -> SharedString {
        self.title.clone()
    }

    pub fn recipients(&self) -> &[String] {
        &self.recipients
    }

    pub fn set_scheduled(&mut self, scheduled: Vec<StripItem>, cx: &mut Context<Self>) {
        if self.scheduled != scheduled {
            self.scheduled = scheduled;
            if self.scheduled.is_empty() {
                self.strip_expanded = false;
            }
            cx.notify();
        }
    }

    /// Cancel on the Scheduled strip: the text goes back into an empty
    /// composer so nothing is lost.
    fn cancel_scheduled(&mut self, key: StripKey, cx: &mut Context<Self>) {
        let body = self
            .scheduled
            .iter()
            .find(|i| i.key == key)
            .map(|i| i.body.clone());
        if let Some(body) = body {
            let current = self.composer.read(cx).text().to_owned();
            if let Some(text) = schedule_ui::text_after_cancel(&current, &body) {
                self.composer.update(cx, |field, cx| field.set(&text, cx));
            }
        }
        cx.emit(SmsConversationEvent::CancelScheduled(key));
    }

    pub fn set_muted(&mut self, muted: bool, cx: &mut Context<Self>) {
        if self.muted != muted {
            self.muted = muted;
            cx.notify();
        }
    }

    /// System Back inside the page: clears the selected bubble; `false` when
    /// nothing was selected.
    pub fn go_back(&mut self, cx: &mut Context<Self>) -> bool {
        if self.selected.take().is_none() {
            return false;
        }
        self.rows_dirty = true;
        cx.notify();
        true
    }

    /// Unsent composer text worth keeping.
    pub fn draft(&self, cx: &gpui::App) -> String {
        self.composer.read(cx).text().to_owned()
    }

    /// Puts kept text back into the composer.
    pub fn set_draft(&mut self, text: &str, cx: &mut Context<Self>) {
        self.composer.update(cx, |field, cx| field.set(text, cx));
    }

    /// The text was scheduled: the composer is done with it.
    pub fn clear_draft(&mut self, cx: &mut Context<Self>) {
        self.composer.update(cx, |field, cx| field.clear(cx));
    }

    /// Long-press (or right-click) on Send. Only a typed text can wait.
    fn request_schedule(&mut self, cx: &mut Context<Self>) {
        let body = self.composer.read(cx).text().trim().to_owned();
        if body.is_empty() || !self.backend.read(cx).sms_role_held() {
            return;
        }
        let sub_id = self.current_sim(cx);
        cx.emit(SmsConversationEvent::Schedule {
            to: self.recipients.clone(),
            body,
            sub_id,
        });
    }

    fn is_draft(&self) -> bool {
        self.thread_id < 0
    }

    fn on_backend(&mut self, event: &BackendEvent, cx: &mut Context<Self>) {
        match event {
            BackendEvent::Status(status) => self.on_status(status, cx),
            // New messages may have arrived: read the newest page again.
            BackendEvent::SmsChanged => {
                self.adopt_thread(cx);
                self.fill_from_thread(cx);
                self.load(None, cx);
            }
            BackendEvent::Document(result) if result.op == DocumentOp::Attachment => {
                self.on_attachment(result, cx);
            }
            _ => {}
        }
    }

    /// A draft whose first send created the provider thread becomes that
    /// thread, so replies show here and it reads like any other.
    fn adopt_thread(&mut self, cx: &mut Context<Self>) {
        if !self.is_draft() {
            return;
        }
        let [number] = self.recipients.as_slice() else {
            return;
        };
        let found = crate::inbox::thread_for_number(self.backend.read(cx).threads(), number)
            .map(|t| t.thread_id);
        if let Some(thread_id) = found {
            self.thread_id = thread_id;
            self.timeline.adopt_thread(thread_id);
            self.rows_dirty = true;
            // The SIM picked while drafting belongs to the thread now.
            if let Some(sub_id) = self.chosen_sim {
                self.backend.update(cx, |backend, cx| {
                    backend.remember_sim(thread_id, sub_id, cx)
                });
            }
        }
    }

    /// A thread opened before the thread list knew it (a notification tapped
    /// at start) gets its addresses, and a title, once the list has it.
    fn fill_from_thread(&mut self, cx: &mut Context<Self>) {
        if !self.recipients.is_empty() || self.is_draft() {
            return;
        }
        let Some(addresses) = self
            .backend
            .read(cx)
            .threads()
            .iter()
            .find(|t| t.thread_id == self.thread_id)
            .map(|t| t.addresses.clone())
            .filter(|a| !a.is_empty())
        else {
            return;
        };
        if self.title.trim().is_empty() {
            let backend = self.backend.read(cx);
            let names: Vec<String> = addresses
                .iter()
                .map(|a| {
                    backend
                        .contacts()
                        .match_number(a)
                        .map(|c| c.display_name.clone())
                        .filter(|n| !n.trim().is_empty())
                        .unwrap_or_else(|| a.clone())
                })
                .collect();
            self.title = names.join(", ").into();
        }
        self.timeline.set_recipients(addresses.clone());
        self.recipients = addresses;
        cx.notify();
    }

    /// `sms_status` for this thread's sends; other threads' are ignored by the timeline.
    fn on_status(&mut self, status: &SmsStatus, cx: &mut Context<Self>) {
        if self.timeline.apply_status(status) {
            self.rows_dirty = true;
            cx.notify();
        }
    }

    fn on_attachment(&mut self, result: &DocumentResult, cx: &mut Context<Self>) {
        if !std::mem::take(&mut self.awaiting_attachment) {
            return;
        }
        let path = result.path.clone().filter(|_| result.ok);
        match path {
            // SMS carries pictures only; the provider's type says early.
            Some(_) if result.mime.as_deref().is_some_and(|m| !is_image(m)) => {
                self.notice = Some("Only pictures can be sent by SMS".into());
            }
            Some(path) => {
                self.attachment = Some(path);
                self.attachment_name = result.name.clone().filter(|n| !n.trim().is_empty());
                self.notice = None;
            }
            None => {
                let words = if self.backend.read(cx).is_preview() {
                    "Pictures need an Android phone"
                } else {
                    "Couldn't attach that picture (up to 4 MB)"
                };
                self.notice = Some(words.into());
            }
        }
        cx.notify();
    }

    /// Fetches one page (the newest, or the one before `before_ms`) off the UI thread.
    fn load(&mut self, before_ms: Option<i64>, cx: &mut Context<Self>) {
        if self.is_draft() {
            return;
        }
        if self.loading {
            // A change while a page is on its way: read the newest page after it.
            self.reload_due |= before_ms.is_none();
            return;
        }
        self.loading = true;
        let thread_id = self.thread_id;
        let task = self
            .backend
            .read(cx)
            .messages(cx, thread_id, before_ms, PAGE);
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.loading = false;
                match result {
                    Ok(page) => {
                        this.error = None;
                        let first = this.timeline.items().is_empty();
                        this.timeline.apply_page(page);
                        this.sync_rows();
                        if before_ms.is_none() {
                            this.list.scroll_to_end();
                        }
                        // Read only while someone looks: the app in the
                        // background leaves new messages unread.
                        if (first || before_ms.is_none()) && this.backend.read(cx).ui_active() {
                            this.backend
                                .update(cx, |backend, cx| backend.mark_read(thread_id, cx));
                        }
                    }
                    Err(error) => {
                        log::warn!("loading SMS messages failed: {error}");
                        // The detail is for the log; the screen says it plainly.
                        this.error = Some(load_error_text(&error).into());
                    }
                }
                if std::mem::take(&mut this.reload_due) {
                    this.load(None, cx);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// The SIM a send uses now: this conversation's pick, else the thread's
    /// remembered one, else the system default.
    fn current_sim(&self, cx: &gpui::App) -> Option<i32> {
        let backend = self.backend.read(cx);
        sim_for(
            backend.sims(),
            self.chosen_sim
                .or_else(|| backend.sim_choice(self.thread_id)),
        )
    }

    fn cycle_sim(&mut self, cx: &mut Context<Self>) {
        let current = self.current_sim(cx);
        let next = next_sim(self.backend.read(cx).sims(), current);
        if let Some(next) = next {
            self.chosen_sim = Some(next);
            let thread = self.thread_id;
            self.backend
                .update(cx, |backend, cx| backend.remember_sim(thread, next, cx));
            cx.notify();
        }
    }

    fn send(&mut self, cx: &mut Context<Self>) {
        let text = self.composer.read(cx).text().trim().to_owned();
        let attachment = self.attachment.clone();
        if !can_send(
            &text,
            attachment.is_some(),
            self.backend.read(cx).sms_role_held(),
        ) {
            return;
        }
        if self.recipients.is_empty() {
            // Opened before the thread list knew it: wait for its addresses.
            self.notice = Some("This conversation is still loading".into());
            cx.notify();
            return;
        }
        self.composer.update(cx, |field, cx| field.clear(cx));
        self.attachment = None;
        self.notice = None;
        self.dispatch(text, attachment, cx);
    }

    /// Sends text typed into a notification as if it came from the composer.
    /// `false` when it could not go (not the default SMS app, no addresses).
    pub fn send_reply(&mut self, text: String, cx: &mut Context<Self>) -> bool {
        let text = text.trim().to_owned();
        let sendable = can_send(&text, false, self.backend.read(cx).sms_role_held())
            && !self.recipients.is_empty();
        if sendable {
            self.dispatch(text, None, cx);
        }
        sendable
    }

    /// Puts the bubble in the timeline and hands the send to the backend.
    fn dispatch(&mut self, text: String, attachment: Option<String>, cx: &mut Context<Self>) {
        let sub_id = self.current_sim(cx);
        let local_id = self
            .backend
            .update(cx, |backend, _| backend.next_local_id());
        let shown = if text.is_empty() {
            "Photo".to_owned()
        } else {
            text.clone()
        };
        self.timeline
            .push_pending(local_id, shown, sub_id, now_ms());
        self.sync_rows();
        self.list.scroll_to_end();
        let to = self.recipients.clone();
        match attachment {
            Some(path) => {
                self.mms_sends
                    .insert(local_id, (text.clone(), path.clone()));
                self.backend.update(cx, |backend, cx| {
                    backend.send_mms(local_id, to, text, Some(path), sub_id, cx);
                });
            }
            None => {
                let req = SmsSend {
                    local_id,
                    to,
                    body: text,
                    sub_id,
                };
                self.backend
                    .update(cx, |backend, cx| backend.send_sms(req, cx));
            }
        }
        cx.notify();
    }

    fn retry(&mut self, local_id: u64, cx: &mut Context<Self>) {
        self.rows_dirty = true;
        let Some(req) = self.timeline.retry(local_id) else {
            return;
        };
        match self.mms_sends.get(&local_id).cloned() {
            Some((text, path)) => self.backend.update(cx, |backend, cx| {
                backend.send_mms(local_id, req.to, text, Some(path), req.sub_id, cx);
            }),
            None => self
                .backend
                .update(cx, |backend, cx| backend.send_sms(req, cx)),
        }
        cx.notify();
    }

    /// The Delete pill on a failed send: nothing left the phone, so it simply goes.
    fn discard_failed(&mut self, local_id: u64, cx: &mut Context<Self>) {
        self.selected = None;
        self.rows_dirty = true;
        if self.timeline.discard(local_id).is_some() {
            self.mms_sends.remove(&local_id);
            cx.emit(SmsConversationEvent::Notice("Message deleted"));
        }
        cx.notify();
    }

    /// A copy pill was tapped: the page copies it and the selection closes.
    fn copy(&mut self, text: String, sensitive: bool, cx: &mut Context<Self>) {
        self.selected = None;
        self.rows_dirty = true;
        cx.emit(SmsConversationEvent::Copy { text, sensitive });
        cx.notify();
    }

    /// A share pill was tapped: the page opens the share sheet and the selection closes.
    fn share(&mut self, text: String, cx: &mut Context<Self>) {
        self.selected = None;
        self.rows_dirty = true;
        cx.emit(SmsConversationEvent::Share(text));
        cx.notify();
    }

    fn pick_attachment(&mut self, cx: &mut Context<Self>) {
        self.awaiting_attachment = true;
        self.notice = None;
        self.backend
            .update(cx, |backend, cx| backend.pick_attachment(cx));
    }

    /// Rebuilds the day breaks and message entries for `offset` and tells the
    /// list which items changed. Runs only after a change, never per frame.
    fn sync_rows_at(&mut self, offset: i32) {
        self.rows_dirty = false;
        self.rows_offset = offset;
        let items = self.timeline.items();
        let starts = day_starts(items, offset);
        let mut entries = Vec::with_capacity(items.len() + starts.len());
        let mut next_start = starts.iter().peekable();
        for index in 0..items.len() {
            if next_start.next_if(|&&at| at == index).is_some() {
                entries.push(Entry::Day(index));
            }
            entries.push(Entry::Message(index));
        }
        let day = |ms: i64| (ms.div_euclid(1000) + i64::from(offset)).div_euclid(86_400);
        // The "Load earlier" slot keeps one key: it is measured while on screen.
        let keys = std::iter::once(key_of(0u8))
            .chain(entries.iter().map(|entry| match *entry {
                Entry::Day(index) => key_of((1u8, day(items[index].at_ms))),
                Entry::Message(index) => {
                    let base = message_key(&items[index]);
                    key_of((base, self.selected == Some(base)))
                }
            }))
            .collect();
        self.list.sync(keys);
        self.entries = entries;
    }

    fn sync_rows(&mut self) {
        self.sync_rows_at(self.rows_offset);
    }

    /// One item of the timeline list: the "Load earlier" slot, a day break or
    /// a bubble. Only items on screen are built.
    fn list_item(
        &mut self,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let now = self.frame_now;
        let offset = self.rows_offset;
        let items = self.timeline.items();
        let content = match index.checked_sub(1).map(|i| self.entries.get(i).copied()) {
            None => self.top_slot(theme, cx),
            Some(Some(Entry::Day(at))) => match items.get(at) {
                Some(item) => div()
                    .flex()
                    .justify_center()
                    .py(px(6.0))
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child(day_label(item.at_ms, now, offset))
                    .into_any_element(),
                None => div().into_any_element(),
            },
            Some(Some(Entry::Message(at))) => match items.get(at) {
                Some(item) => self.bubble(theme, item, now, offset, at, cx),
                None => div().into_any_element(),
            },
            Some(None) => div().into_any_element(),
        };
        // The padding stands in for the old column gap.
        div()
            .w_full()
            .px(px(12.0))
            .pb(px(4.0))
            .child(content)
            .into_any_element()
    }

    /// "Load earlier", a load error, or "No messages yet".
    fn top_slot(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let older = self.timeline.has_older().then(|| {
            div()
                .id("sms-older")
                .flex()
                .justify_center()
                .py(px(8.0))
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.accent)
                .child(if self.loading {
                    "Loading…"
                } else {
                    "Load earlier"
                })
                .on_click(cx.listener(|this, _, _, cx| {
                    if let Some(oldest) = this.timeline.oldest_ms() {
                        // +1 re-reads rows that share the oldest stamp; the
                        // timeline dedups them by key.
                        this.load(Some(oldest.saturating_add(1)), cx);
                    }
                }))
        });
        let error = self.error.clone().map(|error| {
            div()
                .py(px(8.0))
                .text_size(typography::FOOTNOTE.size)
                .text_color(colors.red)
                .child(error)
        });
        let empty = (self.entries.is_empty() && !self.loading && self.error.is_none()).then(|| {
            div()
                .py(px(48.0))
                .flex()
                .justify_center()
                .text_size(typography::SUBHEADLINE.size)
                .text_color(colors.secondary_label)
                .child("No messages yet")
        });
        div()
            .flex()
            .flex_col()
            .gap(px(4.0))
            .children(older)
            .children(error)
            .children(empty)
            .into_any_element()
    }

    fn bubble(
        &self,
        theme: Theme,
        item: &SmsItem,
        now: i64,
        offset: i32,
        index: usize,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let own = item.outgoing;
        let failed = item.state == DeliveryState::Failed && own;
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
            if own { gpui::white() } else { colors.red }
        } else {
            quiet
        };
        let retry = failed_local(item);
        let meta: SharedString = if retry.is_some() {
            "Not sent · Tap to retry".into()
        } else {
            meta_line(item, now, offset).into()
        };

        let mut bubble = div()
            .id(("sms-msg", index))
            .max_w(px(290.0))
            .px(px(12.0))
            .py(px(7.0))
            .rounded(radius::CARD)
            .bg(fill)
            .flex()
            .flex_col()
            .gap(px(4.0));
        if failed {
            bubble = bubble.border_1().border_color(colors.red);
        }
        for part in &item.parts {
            if let Some(path) = part
                .path
                .as_deref()
                .filter(|_| is_image(&part.content_type))
            {
                bubble = bubble.child(
                    img(PathBuf::from(path))
                        .max_w(px(240.0))
                        .max_h(px(320.0))
                        .rounded(radius::CARD),
                );
            } else if part.text.is_none() {
                bubble = bubble.child(
                    div()
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(quiet)
                        .child(part_label(&part.content_type)),
                );
            }
        }
        if !item.body.is_empty() {
            bubble = bubble.child(
                div()
                    .text_size(typography::BODY.size)
                    .text_color(text)
                    .child(item.body.clone()),
            );
        }
        bubble = bubble.child(
            div()
                .self_end()
                .text_size(typography::CAPTION.size)
                .text_color(meta_color)
                .child(meta),
        );
        let key = message_key(item);
        if let Some(local_id) = retry {
            bubble = bubble
                .on_click(cx.listener(move |this, _, _, cx| this.retry(local_id, cx)))
                .on_long_press(cx.listener(move |this, _, _, cx| {
                    this.selected = Some(key);
                    this.rows_dirty = true;
                    cx.notify();
                }));
        } else if selectable(item) {
            bubble = bubble
                .on_click(cx.listener(|this, _, _, cx| {
                    if this.selected.take().is_some() {
                        this.rows_dirty = true;
                        cx.notify();
                    }
                }))
                .on_long_press(cx.listener(move |this, _, _, cx| {
                    this.selected = Some(key);
                    this.rows_dirty = true;
                    cx.notify();
                }));
        }
        let mut column = div()
            .flex()
            .flex_col()
            .gap(px(2.0))
            .when(own, |c| c.items_end())
            .child(bubble);
        if self.selected == Some(key)
            && let Some(local_id) = retry
        {
            let mut pills = div().flex().flex_wrap().gap(px(6.0)).px(px(4.0));
            pills = pills.child(
                glass_pill(theme, "Retry")
                    .id(("sms-retry", index))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.selected = None;
                        this.retry(local_id, cx);
                    })),
            );
            if let Some(text) = copy_text(item) {
                pills = pills.child(glass_pill(theme, "Copy").id(("sms-copy", index)).on_click(
                    cx.listener(move |this, _, _, cx| {
                        this.copy(text.clone(), false, cx);
                    }),
                ));
            }
            pills = pills.child(
                glass_pill(theme, "Delete")
                    .id(("sms-delete", index))
                    .text_color(colors.red)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.discard_failed(local_id, cx);
                    })),
            );
            column = column.child(pills);
        } else if self.selected == Some(key) && selectable(item) {
            let mut pills = div().flex().flex_wrap().gap(px(6.0)).px(px(4.0));
            if let Some(text) = copy_text(item) {
                let shared = text.clone();
                pills = pills.child(glass_pill(theme, "Copy").id(("sms-copy", index)).on_click(
                    cx.listener(move |this, _, _, cx| {
                        this.copy(text.clone(), false, cx);
                    }),
                ));
                pills = pills.child(
                    glass_pill(theme, "Share")
                        .id(("sms-share", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.share(shared.clone(), cx);
                        })),
                );
            }
            if let Some(code) = copy_code(item) {
                pills = pills.child(
                    glass_pill(theme, "Copy code")
                        .id(("sms-copy-code", index))
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.copy(code.clone(), true, cx);
                        })),
                );
            }
            column = column.child(pills);
        }
        div()
            .flex()
            .when(own, |row| row.justify_end())
            .child(column)
            .into_any_element()
    }
}

impl Render for SmsConversation {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let now = now_ms();
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));
        let (locale, role_held, sims) = {
            let backend = self.backend.read(cx);
            (
                backend.locale(),
                backend.sms_role_held(),
                backend.sims().clone(),
            )
        };
        let offset = locale.tz_offset_s;

        self.frame_now = now;
        if self.rows_dirty || self.rows_offset != offset {
            self.sync_rows_at(offset);
        }
        let timeline = list(
            self.list.state().clone(),
            cx.processor(|this, index: usize, window, cx| this.list_item(index, window, cx)),
        )
        .flex_1()
        .min_h_0()
        .w_full();

        let back = glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
            .id("sms-back")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(SmsConversationEvent::Back)))
            .into_any_element();
        let switch = self.switch.clone().map(|(thread, badge)| {
            glass_pill(theme, badge.label())
                .id("sms-switch")
                .on_click(cx.listener(move |_, _, _, cx| {
                    cx.emit(SmsConversationEvent::Switch(thread.clone()));
                }))
                .into_any_element()
        });
        let more = glass_icon_button(theme, Icon::LucideEllipsis, 0.0, false)
            .id("sms-more")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(SmsConversationEvent::Menu)))
            .into_any_element();
        let mute = glass_icon_button(theme, Icon::LucideBellOff, 0.0, self.muted)
            .id("sms-mute")
            .on_click(cx.listener(|_, _, _, cx| cx.emit(SmsConversationEvent::ToggleMute)))
            .into_any_element();
        let trailing = div()
            .flex()
            .items_center()
            .gap(px(8.0))
            .children(switch)
            .child(mute)
            .child(more)
            .into_any_element();
        let header = header_bar(
            theme,
            back,
            self.title.clone(),
            header_subtitle(&self.title, &self.recipients),
            Some(trailing),
        );
        let strip = schedule_ui::strip(
            theme,
            &self.scheduled,
            self.strip_expanded,
            (now, offset),
            cx,
            |this: &mut Self, key, cx| this.cancel_scheduled(key, cx),
            |this: &mut Self, cx| {
                this.strip_expanded = true;
                cx.notify();
            },
        );

        let current_sim = self.current_sim(cx);
        let text = self.composer.read(cx).text().to_owned();
        let mut info = div().flex().items_center().gap(px(8.0)).px(px(16.0));
        let mut has_info = false;
        if sim_chip_visible(&sims) {
            has_info = true;
            info = info.child(
                glass_pill(theme, sim_chip_label(&sims, current_sim))
                    .id("sms-sim")
                    .h(px(30.0))
                    .px(px(12.0))
                    .text_size(typography::FOOTNOTE.size)
                    .on_click(cx.listener(|this, _, _, cx| this.cycle_sim(cx))),
            );
        }
        if let Some(path) = self.attachment.as_deref() {
            has_info = true;
            let label = self
                .attachment_name
                .clone()
                .unwrap_or_else(|| attachment_label(path));
            info = info.child(
                glass_pill(theme, format!("{label} ✕"))
                    .id("sms-attached")
                    .h(px(30.0))
                    .px(px(12.0))
                    .text_size(typography::FOOTNOTE.size)
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.attachment = None;
                        cx.notify();
                    })),
            );
        } else if let Some(counter) = segment_label(&text) {
            has_info = true;
            info = info.child(
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child(counter),
            );
        }
        if let Some(notice) = self.notice.clone() {
            has_info = true;
            info = info.child(
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.red)
                    .child(notice),
            );
        }

        let bottom: AnyElement = if role_held {
            let field = div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .child(field_box(
                    theme,
                    "sms-field",
                    &self.composer,
                    "Text message",
                    window,
                    cx,
                ))
                .into_any_element();
            let attach = glass_icon_button(theme, Icon::LucidePlus, 0.0, false)
                .id("sms-attach")
                .on_click(cx.listener(|this, _, _, cx| this.pick_attachment(cx)))
                .into_any_element();
            let send = glass_icon_button(theme, Icon::LucideArrowUp, 0.0, false)
                .id("sms-send")
                .on_click(cx.listener(|this, _, _, cx| this.send(cx)))
                .on_long_press(cx.listener(|this, _, _, cx| this.request_schedule(cx)))
                .on_mouse_down(
                    gpui::MouseButton::Right,
                    cx.listener(|this, _, _, cx| this.request_schedule(cx)),
                )
                .into_any_element();
            composer_bar(keyboard, Some(attach), field, send).into_any_element()
        } else {
            div()
                .flex_none()
                .flex()
                .justify_center()
                .px(px(12.0))
                .pt(px(8.0))
                .pb(px(12.0) + keyboard)
                .child(
                    glass_pill(theme, "Make default to reply")
                        .id("sms-role")
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.backend
                                .update(cx, |backend, cx| backend.request_sms_role(cx));
                        })),
                )
                .into_any_element()
        };

        div()
            .size_full()
            .flex()
            .flex_col()
            .child(header)
            .child(timeline)
            .children(strip)
            .children(has_info.then_some(info))
            .child(bottom)
    }
}

// ---- pure helpers -----------------------------------------------------------

/// The key of a bubble: everything that changes what it shows or how tall it is.
fn message_key(item: &SmsItem) -> u64 {
    let parts: Vec<(&str, Option<&str>, bool)> = item
        .parts
        .iter()
        .map(|p| (p.content_type.as_str(), p.path.as_deref(), p.text.is_some()))
        .collect();
    key_of((
        2u8,
        item.key,
        item.outgoing,
        &item.body,
        parts,
        item.at_ms,
        item.state,
    ))
}

/// The SIM chip shows only when there is a choice to make.
pub fn sim_chip_visible(sims: &SmsSimsPayload) -> bool {
    sims.sims.len() > 1
}

/// The chip's text: the SIM's label, or a prompt while Android would pick.
pub fn sim_chip_label(sims: &SmsSimsPayload, current: Option<i32>) -> String {
    current
        .and_then(|id| sims.sims.iter().find(|s| s.sub_id == id))
        .map_or_else(|| "Choose SIM".to_owned(), |s| s.label.clone())
}

/// The SIM after `current` in slot order (wraps); the first when none is set.
pub fn next_sim(sims: &SmsSimsPayload, current: Option<i32>) -> Option<i32> {
    let at = current.and_then(|id| sims.sims.iter().position(|s| s.sub_id == id));
    let next = at.map_or(0, |i| (i + 1) % sims.sims.len().max(1));
    sims.sims.get(next).map(|s| s.sub_id)
}

/// The text under a bubble's body: the time, then the delivery tick.
pub fn meta_line(item: &SmsItem, now_ms: i64, offset_s: i32) -> String {
    let time = if (0..60_000).contains(&(now_ms - item.at_ms)) {
        "Now".to_owned()
    } else {
        clock(item.at_ms, offset_s)
    };
    match tick(item.outgoing, item.state) {
        "" => time,
        tick if item.state == DeliveryState::Pending => tick.to_owned(),
        tick => format!("{time} {tick}"),
    }
}

/// Delivery state as shown: sending, one tick sent, two delivered, not sent.
/// Incoming messages and states the provider does not report show nothing.
pub fn tick(outgoing: bool, state: DeliveryState) -> &'static str {
    if !outgoing {
        return "";
    }
    match state {
        DeliveryState::Pending => "Sending…",
        DeliveryState::Sent => "✓",
        DeliveryState::Delivered => "✓✓",
        DeliveryState::Failed => "Not sent",
        DeliveryState::None => "",
    }
}

/// What a failed page load says on screen. The backend's own sentences
/// (access off, desktop) are kept; provider internals are not shown.
pub fn load_error_text(error: &str) -> String {
    match error {
        "SMS access is off" | "this needs an Android phone" => {
            format!("Couldn't load messages: {error}.")
        }
        _ => "Couldn't load messages. Tap to try again.".to_owned(),
    }
}

/// Whether the composer may send: something to send, and the role to send it.
pub fn can_send(text: &str, has_attachment: bool, role_held: bool) -> bool {
    role_held && (has_attachment || !text.trim().is_empty())
}

/// "112 left · 2 SMS" near a part boundary or past the first part; nothing for
/// short texts, where the counter would only be noise.
pub fn segment_label(body: &str) -> Option<String> {
    if body.is_empty() {
        return None;
    }
    let info = segments(body);
    if info.parts == 1 && info.left_in_part > COUNTER_FROM {
        return None;
    }
    let unicode = if info.unicode { " · Unicode" } else { "" };
    Some(format!(
        "{} left · {} SMS{unicode}",
        info.left_in_part, info.parts
    ))
}

/// The line under the title: the number when the title is a name, the head
/// count of a group.
pub fn header_subtitle(title: &str, recipients: &[String]) -> Option<String> {
    match recipients {
        [] => None,
        [one] => (one != title).then(|| one.clone()),
        many => Some(format!("{} people", many.len())),
    }
}

/// Indexes of the items that start a new local day.
pub fn day_starts(items: &[SmsItem], offset_s: i32) -> Vec<usize> {
    let day = |ms: i64| (ms.div_euclid(1000) + i64::from(offset_s)).div_euclid(86_400);
    items
        .iter()
        .enumerate()
        .filter(|(i, item)| *i == 0 || day(items[i - 1].at_ms) != day(item.at_ms))
        .map(|(i, _)| i)
        .collect()
}

pub fn is_image(content_type: &str) -> bool {
    content_type
        .trim()
        .to_ascii_lowercase()
        .starts_with("image/")
}

fn part_label(content_type: &str) -> String {
    match content_type.split('/').next().unwrap_or("") {
        "video" => "Video".to_owned(),
        "audio" => "Audio".to_owned(),
        _ => "Attachment".to_owned(),
    }
}

/// The picked file's name, for the chip.
pub fn attachment_label(path: &str) -> String {
    let name = path.rsplit(['/', '\\']).next().unwrap_or(path);
    if name.is_empty() {
        "Photo".to_owned()
    } else {
        name.to_owned()
    }
}

/// Only sent or received messages with text can be selected for copying, not
/// ones still sending or failed.
fn selectable(item: &SmsItem) -> bool {
    !matches!(item.state, DeliveryState::Pending | DeliveryState::Failed)
        && (copy_text(item).is_some() || !saveable_parts(item).is_empty())
}

/// The local id of a failed send of ours, which can be retried, copied or
/// deleted. `None` for everything else.
pub fn failed_local(item: &SmsItem) -> Option<u64> {
    match item.key {
        SmsKey::Local(id) if item.state == DeliveryState::Failed => Some(id),
        _ => None,
    }
}

/// One MMS picture or file that can be saved to the phone's storage.
#[derive(Clone, Debug, PartialEq, Eq)]
#[cfg_attr(not(test), allow(dead_code))]
pub struct SavePart {
    pub name: String,
    pub mime: String,
    pub path: String,
}

/// The parts of a message that are files (not text or the SMIL layout).
#[cfg_attr(not(test), allow(dead_code))]
pub fn saveable_parts(item: &SmsItem) -> Vec<SavePart> {
    item.parts
        .iter()
        .filter(|p| {
            let t = p.content_type.trim().to_ascii_lowercase();
            !t.starts_with("text/plain") && !t.starts_with("application/smil")
        })
        .filter_map(|p| p.path.as_deref().map(|path| (p, path)))
        .enumerate()
        .map(|(index, (p, path))| SavePart {
            name: mms_save_name(&p.content_type, item.at_ms, index + 1),
            mime: p.content_type.clone(),
            path: path.to_owned(),
        })
        .collect()
}

/// `mms-20261105-140500-1.jpg`: the UTC time of the message, the part number
/// and the extension of the type.
#[cfg_attr(not(test), allow(dead_code))]
pub fn mms_save_name(content_type: &str, at_ms: i64, number: usize) -> String {
    let secs = at_ms.div_euclid(1000);
    let (year, month, day) = crate::conversation::civil_from_days(secs.div_euclid(86_400));
    let rem = secs.rem_euclid(86_400);
    format!(
        "mms-{year:04}{month:02}{day:02}-{:02}{:02}{:02}-{number}.{}",
        rem / 3600,
        rem % 3600 / 60,
        rem % 60,
        crate::files::ext_for_mime(content_type)
    )
}

/// The text of a message to copy; `None` when it has none (a picture alone).
pub fn copy_text(item: &SmsItem) -> Option<String> {
    let text = item.body.trim();
    (!text.is_empty()).then(|| text.to_owned())
}

/// The one-time code of an incoming message, to copy.
pub fn copy_code(item: &SmsItem) -> Option<String> {
    if item.outgoing {
        return None;
    }
    rovsti_core::otp::detect(&item.body).map(|o| o.code)
}

#[cfg(test)]
mod tests {
    use rovsti_core::sms::{MmsPart, SmsSim};

    use super::*;

    const T: i64 = 1_793_887_500_000; // 2026-11-05 14:05 UTC

    fn sim(id: i32, label: &str) -> SmsSim {
        SmsSim {
            sub_id: id,
            slot_index: None,
            label: label.into(),
            color_argb: 0,
        }
    }

    fn sims(list: &[(i32, &str)], default: Option<i32>) -> SmsSimsPayload {
        SmsSimsPayload {
            sims: list.iter().map(|(id, l)| sim(*id, l)).collect(),
            default_sub_id: default,
        }
    }

    fn item(outgoing: bool, state: DeliveryState, at_ms: i64) -> SmsItem {
        SmsItem {
            key: SmsKey::Sms(1),
            outgoing,
            body: "hi".into(),
            parts: Vec::<MmsPart>::new(),
            at_ms,
            state,
            sub_id: None,
        }
    }

    #[test]
    fn copy_helpers() {
        let mut m = item(false, DeliveryState::None, T);
        m.body = "  Your code is 123456 \n".into();
        assert_eq!(copy_text(&m).as_deref(), Some("Your code is 123456"));
        assert_eq!(copy_code(&m).as_deref(), Some("123456"));
        m.outgoing = true;
        assert_eq!(copy_code(&m), None, "outgoing has no code to copy");
        m.body = "  ".into();
        assert_eq!(copy_text(&m), None);
        m.outgoing = false;
        m.body = "see you soon".into();
        assert_eq!(copy_code(&m), None);
    }

    #[test]
    fn only_settled_messages_with_text_are_selectable() {
        let mut m = item(false, DeliveryState::None, T);
        m.body = "hello".into();
        assert!(selectable(&m));
        m.state = DeliveryState::Pending;
        assert!(!selectable(&m));
        m.state = DeliveryState::Failed;
        assert!(!selectable(&m));
        m.state = DeliveryState::None;
        m.body = "  ".into();
        assert!(!selectable(&m));
    }

    #[test]
    fn the_sim_chip_needs_more_than_one_sim() {
        assert!(!sim_chip_visible(&sims(&[], None)));
        assert!(!sim_chip_visible(&sims(&[(1, "SIM 1")], Some(1))));
        assert!(sim_chip_visible(&sims(
            &[(1, "SIM 1"), (2, "SIM 2")],
            Some(1)
        )));
    }

    #[test]
    fn the_sim_chip_names_the_sim_and_cycles() {
        let two = sims(&[(1, "Turkcell"), (2, "Vodafone")], Some(1));
        assert_eq!(sim_chip_label(&two, Some(2)), "Vodafone");
        assert_eq!(sim_chip_label(&two, None), "Choose SIM");
        assert_eq!(sim_chip_label(&two, Some(9)), "Choose SIM");
        assert_eq!(next_sim(&two, Some(1)), Some(2));
        assert_eq!(next_sim(&two, Some(2)), Some(1));
        assert_eq!(next_sim(&two, None), Some(1));
        assert_eq!(next_sim(&sims(&[], None), None), None);
    }

    #[test]
    fn delivery_ticks() {
        assert_eq!(tick(true, DeliveryState::Pending), "Sending…");
        assert_eq!(tick(true, DeliveryState::Sent), "✓");
        assert_eq!(tick(true, DeliveryState::Delivered), "✓✓");
        assert_eq!(tick(true, DeliveryState::Failed), "Not sent");
        assert_eq!(tick(true, DeliveryState::None), "");
        // Only our own messages carry ticks.
        assert_eq!(tick(false, DeliveryState::Delivered), "");
    }

    #[test]
    fn the_meta_line_joins_time_and_tick() {
        let later = T + 3_600_000;
        let own = |state| item(true, state, T);
        assert_eq!(meta_line(&own(DeliveryState::Sent), later, 0), "14:05 ✓");
        assert_eq!(
            meta_line(&own(DeliveryState::Delivered), later, 0),
            "14:05 ✓✓"
        );
        assert_eq!(
            meta_line(&own(DeliveryState::Failed), later, 0),
            "14:05 Not sent"
        );
        assert_eq!(
            meta_line(&own(DeliveryState::Pending), later, 0),
            "Sending…"
        );
        assert_eq!(
            meta_line(&item(false, DeliveryState::None, T), later, 0),
            "14:05"
        );
        assert_eq!(
            meta_line(&item(false, DeliveryState::None, T), T + 5_000, 0),
            "Now"
        );
        assert_eq!(
            meta_line(&item(false, DeliveryState::None, T), later, 3 * 3600),
            "17:05"
        );
    }

    #[test]
    fn sending_needs_text_or_a_picture_and_the_role() {
        assert!(can_send("hi", false, true));
        assert!(can_send("", true, true));
        assert!(!can_send("   ", false, true));
        assert!(!can_send("hi", false, false));
        assert!(!can_send("", true, false));
    }

    #[test]
    fn picture_only_mms_is_selectable_and_saveable() {
        let mut m = item(false, DeliveryState::None, T);
        m.body = String::new();
        assert!(!selectable(&m));
        m.parts = vec![
            MmsPart {
                content_type: "application/smil".into(),
                text: Some("<smil/>".into()),
                path: None,
            },
            MmsPart {
                content_type: "image/jpeg".into(),
                text: None,
                path: Some("/cache/a.jpg".into()),
            },
            MmsPart {
                content_type: "text/plain".into(),
                text: Some("x".into()),
                path: Some("/cache/t.txt".into()),
            },
        ];
        assert!(selectable(&m));
        let parts = saveable_parts(&m);
        assert_eq!(parts.len(), 1);
        assert_eq!(parts[0].mime, "image/jpeg");
        assert_eq!(parts[0].path, "/cache/a.jpg");
        assert!(parts[0].name.starts_with("mms-") && parts[0].name.ends_with("-1.jpg"));
        m.state = DeliveryState::Pending;
        assert!(!selectable(&m));
    }

    #[test]
    fn mms_names_get_extensions() {
        // 2026-11-05 14:05:00 UTC
        let at = 1_793_887_500_000;
        assert_eq!(
            mms_save_name("image/jpeg", at, 1),
            "mms-20261105-140500-1.jpg"
        );
        assert_eq!(
            mms_save_name("application/pdf", at, 2),
            "mms-20261105-140500-2.pdf"
        );
        assert_eq!(
            mms_save_name("video/3gpp", at, 3),
            "mms-20261105-140500-3.bin"
        );
    }

    #[test]
    fn failed_local_items() {
        let mut m = item(true, DeliveryState::Failed, T);
        assert_eq!(failed_local(&m), None);
        m.key = SmsKey::Local(7);
        assert_eq!(failed_local(&m), Some(7));
        m.state = DeliveryState::Pending;
        assert_eq!(failed_local(&m), None);
        m.state = DeliveryState::Failed;
        m.key = SmsKey::Sms(7);
        assert_eq!(failed_local(&m), None);
    }

    #[test]
    fn load_errors_read_plainly() {
        assert_eq!(
            load_error_text("SMS access is off"),
            "Couldn't load messages: SMS access is off."
        );
        assert!(!load_error_text("Internal(\"cursor\")").contains("Internal"));
        assert!(load_error_text("x").ends_with("Tap to try again."));
    }

    #[test]
    fn the_counter_appears_near_a_boundary() {
        assert_eq!(segment_label(""), None);
        assert_eq!(segment_label("short"), None);
        let near = "a".repeat(140);
        assert_eq!(segment_label(&near).as_deref(), Some("20 left · 1 SMS"));
        let long = "a".repeat(161);
        assert_eq!(segment_label(&long).as_deref(), Some("145 left · 2 SMS"));
        assert_eq!(
            segment_label(&"ş".repeat(71)).as_deref(),
            Some("63 left · 2 SMS · Unicode")
        );
    }

    #[test]
    fn subtitles() {
        assert_eq!(
            header_subtitle("Ayşe", &["+905321234567".into()]).as_deref(),
            Some("+905321234567")
        );
        assert_eq!(
            header_subtitle("+905321234567", &["+905321234567".into()]),
            None
        );
        assert_eq!(
            header_subtitle("A, B", &["1".into(), "2".into()]).as_deref(),
            Some("2 people")
        );
        assert_eq!(header_subtitle("x", &[]), None);
    }

    #[test]
    fn day_starts_follow_the_local_day() {
        let day = 86_400_000;
        let items = [
            item(false, DeliveryState::None, T - day),
            item(false, DeliveryState::None, T - day + 60_000),
            item(false, DeliveryState::None, T),
        ];
        assert_eq!(day_starts(&items, 0), vec![0, 2]);
        assert_eq!(day_starts(&[], 0), Vec::<usize>::new());
        // A +10h offset moves the cut to local midnight but keeps two days.
        assert_eq!(day_starts(&items, 10 * 3600), vec![0, 2]);
    }

    #[test]
    fn attachments_and_parts() {
        assert_eq!(attachment_label("/data/cache/IMG_1.jpg"), "IMG_1.jpg");
        assert_eq!(attachment_label("/data/cache/"), "Photo");
        assert!(is_image("image/jpeg"));
        assert!(is_image(" IMAGE/PNG"));
        assert!(!is_image("video/mp4"));
        assert_eq!(part_label("video/mp4"), "Video");
        assert_eq!(part_label("application/pdf"), "Attachment");
    }
}
