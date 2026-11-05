//! One WhatsApp conversation: history from the gateway, live events, and a
//! text composer. The merge logic lives in `rovsti_wa_proto::timeline`; this
//! module is the thin GPUI shell around it plus a few pure text helpers.

use std::{
    collections::HashSet,
    time::{SystemTime, UNIX_EPOCH},
};

use gpui::TextInputAction;
use gpui::TextInputPurpose;
use gpui::{
    Context, Entity, EventEmitter, InteractiveElement, IntoElement, ParentElement, Render,
    ScrollHandle, SharedString, StatefulInteractiveElement, Styled, Window, div, prelude::*, px,
};
use rovsti_ui::{
    Icon,
    components::glass_icon_button,
    current_theme,
    field::{TextField, field_box},
    theme,
    tokens::{Theme, radius, typography},
};
use rovsti_wa_client::Gateway;
use rovsti_wa_proto::{
    GatewayEvent, Item, ItemState, MarkRead, MediaKind, MessageBody, SendText, Timeline, day_breaks,
};

/// Messages per history request.
const PAGE: u32 = 50;

/// Offset of the local clock from UTC, in seconds. The platform seam has no
/// time-zone call yet, so times show in UTC; this is the one place to wire it.
const LOCAL_OFFSET_S: i32 = 0;

/// What a conversation tells the page that hosts it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConversationEvent {
    /// The back button was tapped.
    Back,
}

impl EventEmitter<ConversationEvent> for Conversation {}

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
}

impl Conversation {
    pub fn new(
        chat: String,
        title: impl Into<SharedString>,
        is_group: bool,
        gateway: Gateway,
        cx: &mut Context<Self>,
    ) -> Self {
        let composer =
            cx.new(|cx| TextField::with_input(TextInputPurpose::Text, TextInputAction::Send, cx));
        // Typing re-renders the composer box.
        cx.observe(&composer, |_, _, cx| cx.notify()).detach();
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
        };
        this.load(None, cx);
        this
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
        if self.timeline.apply_event(event) {
            self.scroll.scroll_to_bottom();
            self.mark_unread(cx);
            cx.notify();
        }
    }

    fn send(&mut self, cx: &mut Context<Self>) {
        let text = self.composer.read(cx).text().trim().to_owned();
        if text.is_empty() {
            return;
        }
        self.composer.update(cx, |field, cx| field.clear(cx));
        let local_id = self.next_local;
        self.next_local += 1;
        self.timeline.push_pending(local_id, text.clone(), now_ms());
        self.scroll.scroll_to_bottom();
        self.dispatch(local_id, text, cx);
        cx.notify();
    }

    fn retry(&mut self, local_id: u64, cx: &mut Context<Self>) {
        if let Some(text) = self.timeline.restart(local_id) {
            self.dispatch(local_id, text, cx);
            cx.notify();
        }
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
            bubble_meta(item, now).into()
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
            .child(
                div()
                    .text_size(typography::BODY.size)
                    .text_color(text)
                    .child(body_text(&message.body)),
            )
            .child(
                div()
                    .self_end()
                    .text_size(typography::CAPTION.size)
                    .text_color(meta_color)
                    .child(meta),
            );
        if let ItemState::Failed { local_id } = item.state {
            bubble = bubble.on_click(cx.listener(move |this, _, _, cx| this.retry(local_id, cx)));
        }
        div()
            .flex()
            .when(own, |row| row.justify_end())
            .child(bubble)
            .into_any_element()
    }
}

impl Render for Conversation {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = current_theme(window);
        let colors = theme::colors(theme);
        let now = now_ms();
        let insets = window.insets();
        let keyboard = (insets.ime.bottom - insets.consumed.bottom).max(px(0.0));

        let breaks = day_breaks(self.timeline.items(), LOCAL_OFFSET_S);
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
                        .child(day_label(day_ms, now, LOCAL_OFFSET_S))
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
                        this.load(Some(oldest), cx);
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

        let header = div()
            .flex_none()
            .flex()
            .items_center()
            .gap(px(8.0))
            .px(px(8.0))
            .pt(px(48.0))
            .pb(px(8.0))
            .child(
                glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                    .id("conv-back")
                    .on_click(cx.listener(|_, _, _, cx| cx.emit(ConversationEvent::Back))),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .truncate()
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(self.title.clone()),
            );

        let composer = div()
            .flex_none()
            .flex()
            .items_center()
            .gap(px(8.0))
            .px(px(12.0))
            .pt(px(8.0))
            .pb(px(12.0) + keyboard)
            .child(div().flex_1().min_w_0().flex().flex_col().child(field_box(
                theme,
                "conv-field",
                &self.composer,
                "Message",
                window,
                cx,
            )))
            .child(
                glass_icon_button(theme, Icon::LucideArrowUp, 0.0, false)
                    .id("conv-send")
                    .on_click(cx.listener(|this, _, _, cx| this.send(cx))),
            );

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
            .child(composer)
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX))
}

/// `HH:MM` of a timestamp at `offset_s` from UTC.
fn clock(ms: i64, offset_s: i32) -> String {
    let secs = (ms.div_euclid(1000) + i64::from(offset_s)).rem_euclid(86_400);
    format!("{:02}:{:02}", secs / 3600, secs % 3600 / 60)
}

/// The line under a bubble's text: the time, then "Sending…", "Not sent" or
/// the delivery ticks for our own messages. Within a minute the time reads
/// "Now".
pub fn bubble_meta(item: &Item, now_ms: i64) -> String {
    meta_at(item, now_ms, LOCAL_OFFSET_S)
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

/// The text a bubble shows for a body.
fn body_text(body: &MessageBody) -> String {
    match body {
        MessageBody::Text(text) => text.clone(),
        MessageBody::Media { kind, caption, .. } => {
            let label = match kind {
                MediaKind::Image => "Photo",
                MediaKind::Video => "Video",
                MediaKind::Audio => "Voice message",
                MediaKind::Document => "Document",
                MediaKind::Sticker => "Sticker",
            };
            match caption.as_deref().filter(|c| !c.is_empty()) {
                Some(caption) => format!("{label}\n{caption}"),
                None => label.to_owned(),
            }
        }
        MessageBody::Unsupported => "Unsupported message".to_owned(),
    }
}

/// Days since 1970-01-01 as (year, month 1..=12, day).
fn civil_from_days(days: i64) -> (i64, usize, i64) {
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
fn day_label(day_start_ms: i64, now_ms: i64, offset_s: i32) -> String {
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
            bubble_meta(&item(T, false, ItemState::Sent), later),
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
    fn day_labels() {
        let day = T - T.rem_euclid(86_400_000);
        assert_eq!(day_label(day, T, 0), "Today");
        assert_eq!(day_label(day - 86_400_000, T, 0), "Yesterday");
        assert_eq!(day_label(day - 3 * 86_400_000, T, 0), "2 Nov 2026");
        assert_eq!(day_label(0, T, 0), "1 Jan 1970");
    }
}
