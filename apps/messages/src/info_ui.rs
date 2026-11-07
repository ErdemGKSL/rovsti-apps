//! Message info and Select text: one glass bottom sheet shared by the WhatsApp
//! and SMS pages. The rows come from `rovsti_core::message_info`; the pages own
//! the sheet and close it on Back.

use std::ops::Range;

use gpui::{
    AnyElement, App, ClickEvent, InteractiveElement, InteractiveText, IntoElement, ParentElement,
    SharedString, StatefulInteractiveElement, Styled, StyledText, TextSelectionHandle, Window, div,
    px,
};
use rovsti_core::{
    message_info::{InfoChannel, InfoRow, InfoState, info_text},
    sms::DeliveryState,
    sms_timeline::SmsKey,
};
use rovsti_ui::{
    components::glass_pill,
    glass::{GlassSpec, glass},
    theme,
    tokens::{Theme, shadow, typography},
};
use rovsti_wa_proto::{DeliveryStatus, ItemState};

/// The pill shown when Copy selection finds nothing selected.
pub(crate) const SELECT_FIRST: &str = "Select some text first";

/// An open Info or Select text sheet.
pub(crate) struct InfoSheet {
    pub title: SharedString,
    pub rows: Vec<InfoRow>,
    /// The message text, when it has any (Select text needs it).
    pub text: Option<SharedString>,
    pub selection: TextSelectionHandle,
    pub select_mode: bool,
}

impl InfoSheet {
    /// The Info sheet of a message.
    pub fn info(title: impl Into<SharedString>, rows: Vec<InfoRow>) -> Self {
        Self {
            title: title.into(),
            rows,
            text: None,
            selection: TextSelectionHandle::default(),
            select_mode: false,
        }
    }

    /// The Select text sheet of a message.
    pub fn select(text: impl Into<SharedString>) -> Self {
        Self {
            title: "Select text".into(),
            rows: Vec::new(),
            text: Some(text.into()),
            selection: TextSelectionHandle::default(),
            select_mode: true,
        }
    }

    /// What Copy puts on the clipboard in Info mode.
    pub fn info_text(&self) -> String {
        info_text(&self.rows)
    }

    /// The selected part of the text (`None` when nothing is selected).
    pub fn selected(&self, window: &Window) -> Option<String> {
        let text = self.text.as_ref()?;
        let snapshot = self.selection.selection(window)?;
        if snapshot.text.as_ref() != text.as_ref() {
            return None;
        }
        selected_text(text, snapshot.range())
    }
}

/// `text[range]` when it is a real, non-blank selection on char boundaries.
pub(crate) fn selected_text(text: &str, range: Range<usize>) -> Option<String> {
    let part = text.get(range)?;
    (!part.trim().is_empty()).then(|| part.to_owned())
}

/// Select text is offered for messages of more than one word.
pub(crate) fn worth_selecting(text: &str) -> bool {
    text.split_whitespace().nth(1).is_some()
}

/// The state of a WhatsApp message: receipts refine the timeline state.
pub(crate) fn wa_state(
    outgoing: bool,
    state: ItemState,
    status: Option<DeliveryStatus>,
) -> InfoState {
    if !outgoing {
        return InfoState::Received;
    }
    match state {
        ItemState::Pending { .. } => InfoState::Pending,
        ItemState::Failed { .. } => InfoState::Failed,
        ItemState::Sent | ItemState::Delivered | ItemState::Read => match status {
            Some(DeliveryStatus::Played) => InfoState::Played,
            Some(DeliveryStatus::Read) => InfoState::Read,
            Some(DeliveryStatus::Delivered) => InfoState::Delivered,
            Some(DeliveryStatus::Sent) => InfoState::Sent,
            None => match state {
                ItemState::Read => InfoState::Read,
                ItemState::Delivered => InfoState::Delivered,
                _ => InfoState::Sent,
            },
        },
    }
}

/// The state of an SMS or MMS; `None` when unknown for a sent message.
pub(crate) fn sms_state(outgoing: bool, state: DeliveryState) -> Option<InfoState> {
    match state {
        DeliveryState::Pending => Some(InfoState::Pending),
        DeliveryState::Failed => Some(InfoState::Failed),
        DeliveryState::Sent => Some(InfoState::Sent),
        DeliveryState::Delivered => Some(InfoState::Delivered),
        DeliveryState::None => (!outgoing).then_some(InfoState::Received),
    }
}

/// SMS or MMS by the provider row the message lives in.
pub(crate) fn sms_channel(key: SmsKey, has_parts: bool) -> InfoChannel {
    match key {
        SmsKey::Mms(_) => InfoChannel::Mms,
        SmsKey::Local(_) if has_parts => InfoChannel::Mms,
        SmsKey::Sms(_) | SmsKey::Local(_) => InfoChannel::Sms,
    }
}

/// "Photo, 1280 × 720", "Voice note, 0:42" or "Document, report.pdf".
pub(crate) fn media_desc(
    label: &str,
    size: Option<(u32, u32)>,
    seconds: Option<u32>,
    file_name: Option<&str>,
) -> String {
    let detail = if let Some((w, h)) = size.filter(|(w, h)| *w > 0 && *h > 0) {
        Some(format!("{w} × {h}"))
    } else if let Some(s) = seconds {
        Some(crate::inbox::duration_label(s))
    } else {
        file_name
            .map(str::trim)
            .filter(|n| !n.is_empty())
            .map(str::to_owned)
    };
    match detail {
        Some(detail) => format!("{label}, {detail}"),
        None => label.to_owned(),
    }
}

/// "Photo", "Video", "Audio" or "File" for an MMS part's type.
pub(crate) fn mime_label(mime: &str) -> &'static str {
    let t = mime.trim().to_ascii_lowercase();
    if t.starts_with("image/") {
        "Photo"
    } else if t.starts_with("video/") {
        "Video"
    } else if t.starts_with("audio/") {
        "Audio"
    } else {
        "File"
    }
}

/// The sheet over the page: a dimmed screen and a glass card. `close` also
/// runs for a tap outside the card; the other handlers are the pills.
pub(crate) fn info_overlay(
    theme: Theme,
    sheet: &InfoSheet,
    close: impl Fn(&ClickEvent, &mut Window, &mut App) + 'static,
    copy_info: impl Fn(&ClickEvent, &mut Window, &mut App) + 'static,
    copy_selection: impl Fn(&ClickEvent, &mut Window, &mut App) + 'static,
    copy_all: impl Fn(&ClickEvent, &mut Window, &mut App) + 'static,
) -> AnyElement {
    let colors = theme::colors(theme);
    let close = std::rc::Rc::new(close);
    let close_pill = std::rc::Rc::clone(&close);
    let mut card = glass(GlassSpec::control(theme))
        .occlude()
        .w_full()
        .max_h(px(560.0))
        .rounded(px(26.0))
        .shadow(shadow::glass(theme.is_dark()))
        .flex()
        .flex_col()
        .gap(px(6.0))
        .p(px(16.0))
        .child(
            div()
                .truncate()
                .text_size(typography::FOOTNOTE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(colors.secondary_label)
                .child(sheet.title.clone()),
        );
    let mut pills = div().flex().flex_wrap().gap(px(6.0)).pt(px(6.0));
    if sheet.select_mode {
        let text = sheet.text.clone().unwrap_or_default();
        card = card.child(
            div()
                .id("info-select-scroll")
                .min_h_0()
                .overflow_y_scroll()
                .text_size(typography::BODY.size)
                .text_color(colors.label)
                .child(
                    InteractiveText::new("info-select", StyledText::new(text))
                        .selectable(colors.accent.opacity(0.35))
                        .selection_handle(&sheet.selection),
                ),
        );
        pills = pills
            .child(
                glass_pill(theme, "Copy selection")
                    .id("info-copy-selection")
                    .on_click(copy_selection),
            )
            .child(
                glass_pill(theme, "Copy all")
                    .id("info-copy-all")
                    .on_click(copy_all),
            );
    } else {
        let rows = sheet.rows.iter().enumerate().map(|(i, row)| {
            div()
                .id(("info-row", i))
                .flex()
                .justify_between()
                .gap(px(16.0))
                .py(px(4.0))
                .text_size(typography::BODY.size)
                .child(
                    div()
                        .flex_none()
                        .text_color(colors.secondary_label)
                        .child(row.label),
                )
                .child(
                    div()
                        .min_w_0()
                        .text_right()
                        .text_color(colors.label)
                        .child(row.value.clone()),
                )
        });
        card = card.children(rows);
        pills = pills.child(
            glass_pill(theme, "Copy")
                .id("info-copy")
                .on_click(copy_info),
        );
    }
    pills = pills.child(
        glass_pill(theme, "Close")
            .id("info-close")
            .on_click(move |e, w, cx| close_pill(e, w, cx)),
    );
    card = card.child(pills);
    div()
        .id("info-sheet")
        .absolute()
        .top_0()
        .left_0()
        .size_full()
        .flex()
        .flex_col()
        .justify_end()
        .px(px(12.0))
        .pb(px(28.0))
        .bg(gpui::Hsla {
            a: 0.4,
            ..gpui::black()
        })
        .occlude()
        .on_click(move |e, w, cx| close(e, w, cx))
        .child(card)
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn selection_copies_the_range_only() {
        assert_eq!(
            selected_text("hello world", 6..11).as_deref(),
            Some("world")
        );
        assert_eq!(selected_text("hello", 2..2), None);
        assert_eq!(selected_text("a   b", 1..4), None);
        assert_eq!(selected_text("hello", 3..40), None);
        assert_eq!(selected_text("héllo", 0..2), None);
        assert_eq!(selected_text("héllo", 0..3).as_deref(), Some("hé"));
    }

    #[test]
    fn select_text_needs_two_words() {
        assert!(!worth_selecting("ok"));
        assert!(!worth_selecting("  ok  "));
        assert!(worth_selecting("see you"));
    }

    #[test]
    fn wa_states() {
        let s = |o, st, d| wa_state(o, st, d);
        assert_eq!(s(false, ItemState::Sent, None), InfoState::Received);
        assert_eq!(
            s(true, ItemState::Pending { local_id: 1 }, None),
            InfoState::Pending
        );
        assert_eq!(
            s(true, ItemState::Failed { local_id: 1 }, None),
            InfoState::Failed
        );
        assert_eq!(s(true, ItemState::Delivered, None), InfoState::Delivered);
        assert_eq!(
            s(true, ItemState::Read, Some(DeliveryStatus::Played)),
            InfoState::Played
        );
        assert_eq!(
            s(true, ItemState::Sent, Some(DeliveryStatus::Read)),
            InfoState::Read
        );
    }

    #[test]
    fn sms_states_and_channels() {
        assert_eq!(
            sms_state(false, DeliveryState::None),
            Some(InfoState::Received)
        );
        assert_eq!(sms_state(true, DeliveryState::None), None);
        assert_eq!(
            sms_state(true, DeliveryState::Failed),
            Some(InfoState::Failed)
        );
        assert_eq!(sms_channel(SmsKey::Sms(1), false), InfoChannel::Sms);
        assert_eq!(sms_channel(SmsKey::Mms(1), false), InfoChannel::Mms);
        assert_eq!(sms_channel(SmsKey::Local(1), true), InfoChannel::Mms);
        assert_eq!(sms_channel(SmsKey::Local(1), false), InfoChannel::Sms);
    }

    #[test]
    fn media_descriptions() {
        assert_eq!(
            media_desc("Photo", Some((1280, 720)), None, None),
            "Photo, 1280 × 720"
        );
        assert_eq!(
            media_desc("Voice message", None, Some(42), None),
            "Voice message, 0:42"
        );
        assert_eq!(
            media_desc("Document", None, None, Some("a.pdf")),
            "Document, a.pdf"
        );
        assert_eq!(media_desc("Sticker", Some((0, 0)), None, None), "Sticker");
    }

    #[test]
    fn mime_labels() {
        assert_eq!(mime_label("image/jpeg"), "Photo");
        assert_eq!(mime_label(" VIDEO/mp4"), "Video");
        assert_eq!(mime_label("audio/amr"), "Audio");
        assert_eq!(mime_label("application/pdf"), "File");
    }

    #[test]
    fn info_text_of_a_sheet() {
        let rows = vec![InfoRow {
            label: "Via",
            value: "SMS".into(),
        }];
        let sheet = InfoSheet::info("Message info", rows);
        assert_eq!(sheet.info_text(), "Via: SMS");
    }
}
