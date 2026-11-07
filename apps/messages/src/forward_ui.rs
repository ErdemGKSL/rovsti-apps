//! Forward: the chooser sheet that sends a message or picture to another chat.

use gpui::{
    AnyElement, Context, Entity, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, Window, div, px,
};
use rovsti_core::{
    Channel, ThreadId,
    forward::{ForwardBody, ForwardTo, target_label},
};
use rovsti_ui::{
    Icon,
    components::avatar,
    field::{TextField, field_box},
    glass::{GlassSpec, glass},
    theme,
    tokens::{Theme, shadow, typography},
};

use crate::{
    MessagesApp,
    inbox::{self, RowView},
    media_viewer::ViewerItem,
};

/// What is being forwarded: text (or a caption) and/or a picture.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ForwardItem {
    pub body: ForwardBody,
    pub picture: Option<ViewerItem>,
}

/// One chat the chooser offers.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ForwardTarget {
    pub thread: ThreadId,
    pub title: SharedString,
    pub label: SharedString,
    pub initials: SharedString,
    pub channel: Channel,
}

impl ForwardTarget {
    pub(crate) fn to(&self) -> ForwardTo {
        match self.channel {
            Channel::Sms => ForwardTo::Sms,
            Channel::WhatsApp => ForwardTo::WhatsApp,
        }
    }
}

/// The cache file name of a WhatsApp picture; the viewer uses the same one.
pub(crate) fn cache_name(message_id: &str, mime: &str) -> String {
    format!(
        "{}.{}",
        crate::conversation::cache_stem(message_id),
        crate::files::ext_for_mime(mime)
    )
}

/// The open chooser.
pub(crate) struct ForwardSheet {
    pub item: ForwardItem,
    pub field: Entity<TextField>,
    /// The chat the message comes from, left out of the list.
    pub exclude: Option<ThreadId>,
}

/// One target per thread of every row matching `query`, except `exclude`.
pub(crate) fn targets(
    rows: &[RowView],
    query: &str,
    exclude: Option<&ThreadId>,
) -> Vec<ForwardTarget> {
    inbox::filter_rows(rows, query)
        .into_iter()
        .filter_map(|i| rows.get(i))
        .flat_map(|row| {
            let both = row.threads.len() > 1;
            row.threads
                .iter()
                .filter(move |thread| Some(*thread) != exclude)
                .map(move |thread| {
                    let channel = thread.channel();
                    let to = match channel {
                        Channel::Sms => ForwardTo::Sms,
                        Channel::WhatsApp => ForwardTo::WhatsApp,
                    };
                    ForwardTarget {
                        thread: thread.clone(),
                        title: row.title.clone(),
                        label: target_label(&row.title, to, both).into(),
                        initials: row.initials.clone(),
                        channel,
                    }
                })
        })
        .collect()
}

/// The chooser: a dimmed screen and a glass card with a search field and the
/// chats. Tapping a chat calls `pick`; outside the card or the close button
/// calls `close`.
pub(crate) fn forward_overlay(
    theme: Theme,
    sheet: &ForwardSheet,
    rows: &[RowView],
    window: &Window,
    cx: &mut Context<MessagesApp>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let query = sheet.field.read(cx).text().trim().to_owned();
    let found = targets(rows, &query, sheet.exclude.as_ref());
    let empty = found.is_empty();
    let list = found.into_iter().enumerate().map(|(i, target)| {
        let tint = match target.channel {
            Channel::Sms => colors.accent,
            Channel::WhatsApp => colors.green,
        };
        div()
            .id(("forward-target", i))
            .flex()
            .items_center()
            .gap(px(12.0))
            .px(px(12.0))
            .min_h(px(56.0))
            .rounded(px(14.0))
            .hover(|s| s.bg(colors.label.opacity(0.06)))
            .child(avatar(theme, target.initials.clone(), 40.0, tint))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .truncate()
                    .text_size(typography::BODY.size)
                    .text_color(colors.label)
                    .child(target.label.clone()),
            )
            .on_click(cx.listener(move |this, _, _, cx| this.pick_forward(target.clone(), cx)))
            .into_any_element()
    });
    let close = div()
        .id("forward-close")
        .flex_none()
        .size(px(44.0))
        .flex()
        .items_center()
        .justify_center()
        .child(
            gpui::svg()
                .path(Icon::LucideX.path())
                .size(px(20.0))
                .text_color(colors.label),
        )
        .on_click(cx.listener(|this, _, _, cx| this.close_forward(cx)));
    div()
        .id("forward-sheet")
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
        .on_click(cx.listener(|this, _, _, cx| this.close_forward(cx)))
        .child(
            glass(GlassSpec::control(theme))
                .occlude()
                .w_full()
                .max_h(px(520.0))
                .rounded(px(26.0))
                .shadow(shadow::glass(theme.is_dark()))
                .flex()
                .flex_col()
                .gap(px(6.0))
                .p(px(8.0))
                .child(
                    div()
                        .flex()
                        .items_center()
                        .pl(px(16.0))
                        .child(
                            div()
                                .flex_1()
                                .text_size(typography::HEADLINE.size)
                                .font_weight(typography::HEADLINE.weight)
                                .text_color(colors.label)
                                .child("Forward to"),
                        )
                        .child(close),
                )
                .child(field_box(
                    theme,
                    "forward-search",
                    &sheet.field,
                    "Search chats",
                    window,
                    cx,
                ))
                .child(
                    div()
                        .id("forward-list")
                        .flex()
                        .flex_col()
                        .min_h_0()
                        .max_h(px(360.0))
                        .overflow_y_scroll()
                        .children(list)
                        .children(empty.then(|| {
                            div()
                                .p(px(16.0))
                                .text_size(typography::BODY.size)
                                .text_color(colors.secondary_label)
                                .child("No chats match.")
                        })),
                ),
        )
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::inbox::RowAvatar;

    fn row(title: &str, threads: Vec<ThreadId>) -> RowView {
        RowView {
            title: title.to_owned().into(),
            snippet: "".into(),
            badges: vec![],
            unread: 0,
            otp: None,
            time: "".into(),
            channel: threads[0].channel(),
            threads,
            initials: inbox::initials(title).into(),
            avatar: RowAvatar::default(),
            wa_chat: None,
            pinned: false,
            muted: false,
            marked_unread: false,
            tick: None,
            sms_thread: None,
            sms_sender: None,
        }
    }

    fn rows() -> Vec<RowView> {
        vec![
            row(
                "Ada",
                vec![
                    ThreadId::Sms(1),
                    ThreadId::WhatsApp("1@s.whatsapp.net".into()),
                ],
            ),
            row("Bora", vec![ThreadId::Sms(2)]),
        ]
    }

    #[test]
    fn cache_name_is_file_safe() {
        assert_eq!(cache_name("3EB0/a=b", "image/jpeg"), "3EB0_a_b.jpg");
        assert_eq!(cache_name("x", "image/png"), "x.png");
    }

    #[test]
    fn one_target_per_thread() {
        let found = targets(&rows(), "", None);
        assert_eq!(found.len(), 3);
        assert_eq!(found[0].label, "Ada · SMS");
        assert_eq!(found[1].label, "Ada · WhatsApp");
        assert_eq!(found[1].channel, Channel::WhatsApp);
        assert_eq!(found[2].label, "Bora");
    }

    #[test]
    fn query_filters_rows() {
        let found = targets(&rows(), "bor", None);
        assert_eq!(found.len(), 1);
        assert_eq!(found[0].thread, ThreadId::Sms(2));
        assert!(targets(&rows(), "zzz", None).is_empty());
    }

    #[test]
    fn excludes_the_source_chat() {
        let found = targets(&rows(), "", Some(&ThreadId::Sms(1)));
        assert_eq!(found.len(), 2);
        assert!(found.iter().all(|t| t.thread != ThreadId::Sms(1)));
        // A lone chat that is the source leaves nothing for its row.
        assert!(targets(&rows(), "bora", Some(&ThreadId::Sms(2))).is_empty());
    }
}
