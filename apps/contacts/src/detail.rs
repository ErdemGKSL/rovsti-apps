//! The contact card: the page one contact opens to.

use gpui::{
    AnyElement, Context, InteractiveElement, IntoElement, ParentElement, SharedString,
    StatefulInteractiveElement, Styled, div, px, svg,
};
use rovsti_core::contact_card::ContactCard;
use rovsti_ui::{
    Icon,
    components::{glass_icon_button, section},
    glass::{GlassSpec, glass},
    theme::{self, hue_pair},
    tokens::{Theme, metrics, shadow, typography},
};

use crate::{
    ContactsApp,
    photos::photo_avatar,
    ui::{Tone, tap_row},
};

impl ContactsApp {
    /// A round glass action with a caption (Call, Message, Share).
    fn action_button(
        theme: Theme,
        key: &'static str,
        icon: Icon,
        label: &'static str,
        enabled: bool,
    ) -> gpui::Stateful<gpui::Div> {
        let colors = theme::colors(theme);
        div()
            .id(key)
            .flex()
            .flex_col()
            .items_center()
            .gap(px(6.0))
            .opacity(if enabled { 1.0 } else { 0.4 })
            .child(
                glass(GlassSpec::control(theme))
                    .size(px(56.0))
                    .rounded_full()
                    .shadow(shadow::glass(theme.is_dark()))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        svg()
                            .path(icon.path())
                            .size(px(22.0))
                            .text_color(colors.accent),
                    ),
            )
            .child(
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.accent)
                    .child(label),
            )
    }

    /// A labelled value row of the card (Email, Note).
    fn info_row(
        theme: Theme,
        id: (&'static str, usize),
        label: SharedString,
        value: SharedString,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .id(id)
            .flex()
            .flex_col()
            .justify_center()
            .min_h(metrics::ROW_HEIGHT_SUBTITLE)
            .px(metrics::ROW_INSET)
            .py(px(6.0))
            .border_b(metrics::SEPARATOR)
            .border_color(colors.separator)
            .child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(label),
            )
            .child(
                div()
                    .text_size(typography::BODY.size)
                    .text_color(colors.label)
                    .child(value),
            )
            .into_any_element()
    }

    pub(crate) fn detail(
        &self,
        card: &ContactCard,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let id = card.id;
        let primary = card.primary().map(|n| n.raw.clone());
        let tint = hue_pair(theme, card.hue).0;
        let monogram: SharedString = card.monogram.map(String::from).unwrap_or_default().into();
        let photo = self.photo_uri_of(id).and_then(|_| self.photos.get(id));
        // The card's extra facts, once the full contact has been read.
        let full = self.open_draft.as_ref().filter(|d| d.id == Some(id));

        let top = div()
            .flex()
            .items_center()
            .justify_between()
            .px(px(8.0))
            .pt(px(48.0))
            .child(
                glass_icon_button(theme, Icon::LucideChevronLeft, 0.0, false)
                    .id("back")
                    .on_click(cx.listener(|this, _, _, cx| this.close_contact(cx))),
            )
            .child(
                // The accent dot marks a favorite.
                glass_icon_button(theme, Icon::LucideStar, 0.0, card.starred)
                    .id("star")
                    .on_click(cx.listener(move |this, _, _, cx| this.toggle_star(id, cx))),
            );

        let org: Option<SharedString> = full
            .map(|d| d.org.trim())
            .filter(|o| !o.is_empty())
            .map(|o| SharedString::from(o.to_owned()));
        let head = div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(12.0))
            .pt(px(8.0))
            .pb(px(20.0))
            .child(photo_avatar(theme, monogram, 96.0, tint, photo))
            .child(
                div()
                    .px(px(24.0))
                    .text_center()
                    .text_size(typography::TITLE.size)
                    .line_height(typography::TITLE.line)
                    .font_weight(typography::TITLE.weight)
                    .text_color(colors.label)
                    .child(SharedString::from(card.name.clone())),
            )
            .children(org.map(|org| {
                div()
                    .px(px(24.0))
                    .text_center()
                    .text_size(typography::BODY.size)
                    .text_color(colors.secondary_label)
                    .child(org)
            }));

        let has_number = primary.is_some();
        let call_number = primary.clone();
        let sms_number = primary;
        let share = card.share_text();
        let actions =
            div()
                .flex()
                .justify_center()
                .gap(px(28.0))
                .pb(px(24.0))
                .child(
                    Self::action_button(theme, "call", Icon::LucidePhone, "Call", has_number)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            if let Some(number) = call_number.clone() {
                                this.act(
                                    "call",
                                    move || rovsti_android::telecom::place_call(&number),
                                    cx,
                                );
                            }
                        })),
                )
                .child(
                    Self::action_button(
                        theme,
                        "message",
                        Icon::LucideMessageCircle,
                        "Message",
                        has_number,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        if let Some(number) = sms_number.clone() {
                            this.act(
                                "send a message",
                                move || rovsti_android::telecom::send_sms_to(&number),
                                cx,
                            );
                        }
                    })),
                )
                .child(
                    Self::action_button(theme, "share", Icon::LucideUpload, "Share", true)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            let text = share.clone();
                            this.act(
                                "share",
                                move || rovsti_android::telecom::share_text(&text),
                                cx,
                            );
                        })),
                );

        let numbers: Vec<AnyElement> = card
            .numbers
            .iter()
            .enumerate()
            .map(|(i, number)| {
                let raw = number.raw.clone();
                let label: SharedString = if number.default {
                    format!("{} · default", number.label).into()
                } else {
                    number.label.clone().into()
                };
                div()
                    .id(("number", i))
                    .flex()
                    .flex_col()
                    .justify_center()
                    .min_h(metrics::ROW_HEIGHT_SUBTITLE)
                    .px(metrics::ROW_INSET)
                    .border_b(metrics::SEPARATOR)
                    .border_color(colors.separator)
                    .child(
                        div()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child(label),
                    )
                    .child(
                        div()
                            .text_size(typography::BODY.size)
                            .text_color(colors.accent)
                            .child(SharedString::from(number.display.clone())),
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let raw = raw.clone();
                        this.act(
                            "call",
                            move || rovsti_android::telecom::place_call(&raw),
                            cx,
                        );
                    }))
                    .into_any_element()
            })
            .collect();

        let emails: Vec<AnyElement> = full
            .map(|d| {
                d.emails
                    .iter()
                    .enumerate()
                    .map(|(i, email)| {
                        Self::info_row(
                            theme,
                            ("email", i),
                            email.title().into(),
                            email.value.clone().into(),
                        )
                    })
                    .collect()
            })
            .unwrap_or_default();

        let group_names: Vec<String> = full
            .map(|d| {
                self.groups
                    .iter()
                    .filter(|g| d.groups.contains(&g.id))
                    .map(|g| g.title.clone())
                    .collect()
            })
            .unwrap_or_default();

        let note: Option<AnyElement> = full
            .map(|d| d.note.trim())
            .filter(|n| !n.is_empty())
            .map(|n| Self::info_row(theme, ("note", 0), "Note".into(), n.to_owned().into()));

        let actions_list = section(
            theme,
            None,
            [
                tap_row(
                    theme,
                    "edit",
                    Some(Icon::LucidePencil),
                    "Edit Contact",
                    Tone::Accent,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.open_editor_for(id, cx)))
                .into_any_element(),
                tap_row(
                    theme,
                    "edit-system",
                    Some(Icon::LucideExternalLink),
                    "Open in Android Contacts",
                    Tone::Accent,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.act(
                        "edit this contact",
                        move || rovsti_android::telecom::edit_contact(id),
                        cx,
                    );
                }))
                .into_any_element(),
            ],
        );

        let name = SharedString::from(card.name.clone());
        let delete = section(
            theme,
            None,
            [tap_row(
                theme,
                "delete",
                Some(Icon::LucideTrash2),
                "Delete Contact",
                Tone::Danger,
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                this.ask_delete_contact(id, name.clone(), cx);
            }))
            .into_any_element()],
        );

        div()
            .flex()
            .flex_col()
            .gap(px(20.0))
            .child(top)
            .child(head)
            .child(actions)
            .children((!numbers.is_empty()).then(|| section(theme, Some("Phone".into()), numbers)))
            .children((!emails.is_empty()).then(|| section(theme, Some("Email".into()), emails)))
            .children((!group_names.is_empty()).then(|| {
                section(
                    theme,
                    Some("Groups".into()),
                    [Self::info_row(
                        theme,
                        ("groups", 0),
                        "In".into(),
                        group_names.join(", ").into(),
                    )],
                )
            }))
            .children(note.map(|note| section(theme, None, [note])))
            .child(actions_list)
            .child(delete)
            .into_any_element()
    }
}
