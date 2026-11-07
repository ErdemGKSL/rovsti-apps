//! The contact card: the page one contact opens to.

use std::{iter, sync::Arc};

use gpui::{
    AnyElement, ClipboardItem, Context, InteractiveElement, IntoElement, ParentElement,
    RenderImage, Role, SharedString, StatefulInteractiveElement, Styled, div, px, svg,
};
use rovsti_core::{contact_card::ContactCard, contact_edit::ContactDraft};
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

/// What the field sheet acts on.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum FieldTarget {
    Number { raw: String, display: String },
    Email(String),
    Note(String),
}

/// The first email address of the full contact that is not blank.
pub(crate) fn first_email(draft: Option<&ContactDraft>) -> Option<String> {
    draft?
        .emails
        .iter()
        .map(|e| e.value.trim())
        .find(|v| !v.is_empty())
        .map(str::to_owned)
}

impl ContactsApp {
    /// Closes the field sheet without animation (the card is going away).
    pub(crate) fn close_field_sheet_now(&mut self) {
        self.field_sheet.close();
        self.field_target = None;
    }

    pub(crate) fn open_field_sheet(&mut self, target: FieldTarget, cx: &mut Context<Self>) {
        self.field_target = Some(target);
        self.field_sheet.open();
        cx.notify();
    }

    pub(crate) fn close_field_sheet(&mut self, cx: &mut Context<Self>) {
        self.field_sheet.close();
        cx.notify();
    }

    fn copy_field(&mut self, text: String, cx: &mut Context<Self>) {
        cx.write_to_clipboard(ClipboardItem::new_string(text));
        self.field_sheet.close();
        self.notice = Some("Copied.".into());
        cx.notify();
    }

    /// Runs an Android action from the field sheet and closes the sheet.
    fn field_act(
        &mut self,
        what: &'static str,
        action: impl FnOnce() -> rovsti_android::Result<bool> + Send + 'static,
        cx: &mut Context<Self>,
    ) {
        self.field_sheet.close();
        self.act(what, action, cx);
        cx.notify();
    }

    /// The field sheet over the card, once it is visible.
    pub(crate) fn field_sheet_view(
        &mut self,
        theme: Theme,
        window: &gpui::Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        self.field_sheet
            .set_reduced(window.prefers_reduced_motion());
        self.field_sheet.set_viewport(window.viewport_size());
        let target = self.field_target.clone()?;
        let colors = theme::colors(theme);
        let title: SharedString = match &target {
            FieldTarget::Number { display, .. } => display.clone().into(),
            FieldTarget::Email(email) => email.clone().into(),
            FieldTarget::Note(_) => "Note".into(),
        };
        let mut body = div().flex().flex_col().pb(px(12.0)).child(
            div()
                .px(px(20.0))
                .pb(px(8.0))
                .text_size(typography::TITLE3.size)
                .font_weight(typography::TITLE3.weight)
                .text_color(colors.label)
                .child(title.clone()),
        );
        let mut rows: Vec<AnyElement> = Vec::new();
        match target {
            FieldTarget::Number { raw, display } => {
                let (call, sms) = (raw.clone(), raw.clone());
                rows.push(
                    tap_row(
                        theme,
                        "field-call",
                        Some(Icon::LucidePhone),
                        "Call",
                        Tone::Plain,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let number = call.clone();
                        this.field_act(
                            "call",
                            move || rovsti_android::telecom::place_call(&number),
                            cx,
                        );
                    }))
                    .into_any_element(),
                );
                rows.push(
                    tap_row(
                        theme,
                        "field-sms",
                        Some(Icon::LucideMessageCircle),
                        "Message",
                        Tone::Plain,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let number = sms.clone();
                        this.field_act(
                            "send a message",
                            move || rovsti_android::telecom::send_sms_to(&number),
                            cx,
                        );
                    }))
                    .into_any_element(),
                );
                let copy = display.clone();
                rows.push(
                    tap_row(
                        theme,
                        "field-copy",
                        Some(Icon::LucideCopy),
                        "Copy Number",
                        Tone::Plain,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| this.copy_field(copy.clone(), cx)))
                    .into_any_element(),
                );
                rows.push(
                    tap_row(
                        theme,
                        "field-share",
                        Some(Icon::LucideUpload),
                        "Share Number",
                        Tone::Plain,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let text = display.clone();
                        this.field_act(
                            "share",
                            move || rovsti_android::telecom::share_text(&text),
                            cx,
                        );
                    }))
                    .into_any_element(),
                );
            }
            FieldTarget::Email(email) => {
                let (send, copy) = (email.clone(), email);
                rows.push(
                    tap_row(
                        theme,
                        "field-email",
                        Some(Icon::LucideMail),
                        "Send Email",
                        Tone::Plain,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let address = send.clone();
                        this.field_act(
                            "send an email",
                            move || rovsti_android::telecom::send_email_to(&address),
                            cx,
                        );
                    }))
                    .into_any_element(),
                );
                rows.push(
                    tap_row(
                        theme,
                        "field-copy",
                        Some(Icon::LucideCopy),
                        "Copy Email",
                        Tone::Plain,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| this.copy_field(copy.clone(), cx)))
                    .into_any_element(),
                );
            }
            FieldTarget::Note(note) => {
                rows.push(
                    tap_row(
                        theme,
                        "field-copy",
                        Some(Icon::LucideCopy),
                        "Copy Note",
                        Tone::Plain,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| this.copy_field(note.clone(), cx)))
                    .into_any_element(),
                );
            }
        }
        body = body.children(rows);
        let entity = cx.entity();
        let drag = entity.clone();
        self.field_sheet.render(
            theme,
            window,
            "Contact actions",
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_field_sheet(cx)),
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.field_sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }

    /// The text column of a field row (label over value); callers add the
    /// tap and long-press listeners.
    fn field_main(
        theme: Theme,
        id: (&'static str, usize),
        label: SharedString,
        value: SharedString,
        value_color: gpui::Hsla,
    ) -> gpui::Stateful<gpui::Div> {
        let colors = theme::colors(theme);
        div()
            .id(id)
            .flex_1()
            .min_w_0()
            .flex()
            .flex_col()
            .justify_center()
            .min_h(metrics::ROW_HEIGHT_SUBTITLE)
            .pl(metrics::ROW_INSET)
            .py(px(6.0))
            .child(
                div()
                    .text_size(typography::FOOTNOTE.size)
                    .text_color(colors.secondary_label)
                    .child(label),
            )
            .child(
                div()
                    .text_size(typography::BODY.size)
                    .text_color(value_color)
                    .child(value),
            )
    }

    /// The trailing hit area of a field row (ellipsis or copy).
    fn field_more(
        theme: Theme,
        id: (&'static str, usize),
        icon: Icon,
    ) -> gpui::Stateful<gpui::Div> {
        let colors = theme::colors(theme);
        div()
            .id(id)
            .flex()
            .items_center()
            .justify_center()
            .size(metrics::MIN_HIT)
            .flex_none()
            .child(
                svg()
                    .path(icon.path())
                    .size(px(20.0))
                    .text_color(colors.secondary_label),
            )
    }

    /// A field row: the main column and the trailing hit area, side by side.
    fn field_line(
        theme: Theme,
        main: gpui::Stateful<gpui::Div>,
        more: gpui::Stateful<gpui::Div>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .flex()
            .items_center()
            .pr(px(4.0))
            .border_b(metrics::SEPARATOR)
            .border_color(colors.separator)
            .child(main)
            .child(more)
            .into_any_element()
    }

    /// A round glass action with a caption (Call, Message, Share).
    pub(crate) fn action_button(
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

    /// The WhatsApp section: status line and Chat and Call while the instance
    /// is connected, else a hint that opens the WhatsApp settings.
    fn whatsapp_section(
        &self,
        card: &ContactCard,
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let numbers: Vec<String> = card.numbers.iter().map(|n| n.raw.clone()).collect();
        if numbers.is_empty() {
            return None;
        }
        if !self.whatsapp.read(cx).is_connected() {
            return Some(
                section(
                    theme,
                    Some("WhatsApp".into()),
                    [tap_row(
                        theme,
                        "wa-hint",
                        Some(Icon::LucideMessageCircle),
                        "Connect WhatsApp to chat and call from here",
                        Tone::Accent,
                    )
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.tabs.jump_to(crate::SETTINGS_TAB);
                        this.open = None;
                        this.open_draft = None;
                        this.set_whatsapp_open(true, cx);
                    }))
                    .into_any_element()],
                )
                .into_any_element(),
            );
        }
        let status = self
            .wa
            .status(card.id, &numbers, crate::whatsapp::now_ms())?;
        let jid = self.wa.jid(card.id, &numbers);
        let enabled = jid.is_some();
        let profile_rows: Vec<AnyElement> = jid
            .as_deref()
            .and_then(|jid| self.avatars.read(cx).profile(jid))
            .map(|profile| crate::whatsapp::profile_lines(profile, &card.name))
            .unwrap_or_default()
            .into_iter()
            .enumerate()
            .map(|(i, (label, value))| {
                Self::info_row(theme, ("wa-profile", i), label.into(), value.into())
            })
            .collect();
        let photo_row = jid
            .as_deref()
            .and_then(|jid| Some((jid.to_owned(), self.avatars.read(cx).picture(jid)?)))
            .map(|(jid, picture)| self.wa_photo_row(card, jid, picture, theme, cx));
        let (chat_jid, call_jid) = (jid.clone(), jid);
        let (chat_name, call_name) = (card.name.clone(), card.name.clone());
        let buttons = div()
            .flex()
            .justify_center()
            .gap(px(28.0))
            .py(px(12.0))
            .child(
                Self::action_button(theme, "wa-chat", Icon::LucideMessageCircle, "Chat", enabled)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        if let Some(jid) = chat_jid.clone() {
                            this.wa_handoff(false, jid, chat_name.clone(), cx);
                        }
                    })),
            )
            .child(
                Self::action_button(theme, "wa-call", Icon::LucidePhone, "Call", enabled).on_click(
                    cx.listener(move |this, _, _, cx| {
                        if let Some(jid) = call_jid.clone() {
                            this.wa_handoff(true, jid, call_name.clone(), cx);
                        }
                    }),
                ),
            )
            .into_any_element();
        Some(
            section(
                theme,
                Some("WhatsApp".into()),
                iter::once(Self::info_row(
                    theme,
                    ("wa-status", 0),
                    "Status".into(),
                    status.into(),
                ))
                .chain(profile_rows)
                .chain(photo_row)
                .chain(iter::once(buttons)),
            )
            .into_any_element(),
        )
    }

    /// The WhatsApp picture with "Use as Contact Photo": saved at once when
    /// the contact has no photo, after a question when it would replace one.
    fn wa_photo_row(
        &self,
        card: &ContactCard,
        jid: String,
        picture: (Arc<RenderImage>, f32),
        theme: Theme,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let id = card.id;
        let tint = hue_pair(theme, card.hue).0;
        let monogram: SharedString = card.monogram.map(String::from).unwrap_or_default().into();
        let replaces = self.photo_uri_of(id).is_some();
        let name = SharedString::from(card.name.clone());
        div()
            .id("wa-photo")
            .flex()
            .items_center()
            .gap(px(12.0))
            .min_h(metrics::ROW_HEIGHT_SUBTITLE)
            .px(metrics::ROW_INSET)
            .py(px(6.0))
            .border_b(metrics::SEPARATOR)
            .border_color(colors.separator)
            .child(photo_avatar(
                theme,
                monogram,
                40.0,
                tint,
                Some((&picture.0, picture.1)),
            ))
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .text_size(typography::BODY.size)
                    .text_color(colors.accent)
                    .child("Use as Contact Photo"),
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                if replaces {
                    this.ask_use_wa_photo(id, jid.clone(), name.clone(), cx);
                } else {
                    this.use_wa_photo(id, jid.clone(), cx);
                }
            }))
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
        let wa_jid = self.card_jid();
        let picture =
            self.avatar_picture(id, self.photo_uri_of(id).is_some(), wa_jid.as_deref(), cx);
        let photo = picture.as_ref().map(|(image, opacity)| (image, *opacity));
        // The viewer opens on what the avatar shows: the full photo is read
        // only for the contact's own photo, not a WhatsApp stand-in.
        let own_uri = self
            .photo_uri_of(id)
            .filter(|_| self.photos.get(id).is_some())
            .map(str::to_owned);
        let viewable = picture.as_ref().map(|(image, _)| Arc::clone(image));
        let view_name: SharedString = card.name.clone().into();
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
            .child(
                div()
                    .id("card-avatar")
                    .role(Role::Button)
                    .aria_label("View photo")
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.open_photo_viewer(
                            id,
                            view_name.clone(),
                            viewable.clone(),
                            own_uri.clone(),
                            cx,
                        );
                    }))
                    .child(photo_avatar(theme, monogram, 96.0, tint, photo)),
            )
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
        let email_only = if has_number { None } else { first_email(full) };
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
                )
                .children(email_only.map(|address| {
                    Self::action_button(theme, "email", Icon::LucideMail, "Email", true).on_click(
                        cx.listener(move |this, _, _, cx| {
                            let address = address.clone();
                            this.act(
                                "send an email",
                                move || rovsti_android::telecom::send_email_to(&address),
                                cx,
                            );
                        }),
                    )
                }));

        let numbers: Vec<AnyElement> = card
            .numbers
            .iter()
            .enumerate()
            .map(|(i, number)| {
                let raw = number.raw.clone();
                let target = FieldTarget::Number {
                    raw: number.raw.clone(),
                    display: number.display.clone(),
                };
                let (more_target, press_target) = (target.clone(), target);
                let label: SharedString = if number.default {
                    format!("{} · default", number.label).into()
                } else {
                    number.label.clone().into()
                };
                let main = Self::field_main(
                    theme,
                    ("number", i),
                    label,
                    number.display.clone().into(),
                    colors.accent,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    let raw = raw.clone();
                    this.act(
                        "call",
                        move || rovsti_android::telecom::place_call(&raw),
                        cx,
                    );
                }))
                .on_long_press(cx.listener(move |this, _, _, cx| {
                    this.open_field_sheet(press_target.clone(), cx);
                }));
                let more = Self::field_more(theme, ("number-more", i), Icon::LucideEllipsis)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.open_field_sheet(more_target.clone(), cx);
                    }));
                Self::field_line(theme, main, more)
            })
            .collect();

        let emails: Vec<AnyElement> = full
            .map(|d| {
                d.emails
                    .iter()
                    .enumerate()
                    .map(|(i, email)| {
                        let address = email.value.clone();
                        let (more_target, press_target) = (
                            FieldTarget::Email(address.clone()),
                            FieldTarget::Email(address.clone()),
                        );
                        let main = Self::field_main(
                            theme,
                            ("email", i),
                            email.title().into(),
                            email.value.clone().into(),
                            colors.accent,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            let address = address.clone();
                            this.act(
                                "send an email",
                                move || rovsti_android::telecom::send_email_to(&address),
                                cx,
                            );
                        }))
                        .on_long_press(cx.listener(
                            move |this, _, _, cx| {
                                this.open_field_sheet(press_target.clone(), cx);
                            },
                        ));
                        let more = Self::field_more(theme, ("email-more", i), Icon::LucideEllipsis)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.open_field_sheet(more_target.clone(), cx);
                            }));
                        Self::field_line(theme, main, more)
                    })
                    .collect()
            })
            .unwrap_or_default();

        let group_rows: Vec<AnyElement> = full
            .map(|d| {
                self.groups
                    .iter()
                    .filter(|g| d.groups.contains(&g.id))
                    .enumerate()
                    .map(|(i, g)| {
                        let group_id = g.id;
                        tap_row(
                            theme,
                            ("card-group", i),
                            Some(Icon::LucideUsers),
                            g.title.clone(),
                            Tone::Plain,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.close_contact(cx);
                            this.tabs.jump_to(crate::GROUPS_TAB);
                            this.open_group(group_id, cx);
                        }))
                        .into_any_element()
                    })
                    .collect()
            })
            .unwrap_or_default();

        let note: Option<AnyElement> =
            full.map(|d| d.note.trim())
                .filter(|n| !n.is_empty())
                .map(|n| {
                    let text = n.to_owned();
                    let (more_target, press_target) = (
                        FieldTarget::Note(text.clone()),
                        FieldTarget::Note(text.clone()),
                    );
                    let main = Self::field_main(
                        theme,
                        ("note", 0),
                        "Note".into(),
                        text.into(),
                        colors.label,
                    )
                    .on_long_press(cx.listener(move |this, _, _, cx| {
                        this.open_field_sheet(press_target.clone(), cx);
                    }));
                    let more = Self::field_more(theme, ("note-more", 0), Icon::LucideCopy)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.open_field_sheet(more_target.clone(), cx);
                        }));
                    Self::field_line(theme, main, more)
                });

        let whatsapp = self.whatsapp_section(card, theme, cx);

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
            .children(whatsapp)
            .children((!emails.is_empty()).then(|| section(theme, Some("Email".into()), emails)))
            .children(
                (!group_rows.is_empty()).then(|| section(theme, Some("Groups".into()), group_rows)),
            )
            .children(note.map(|note| section(theme, None, [note])))
            .child(actions_list)
            .child(delete)
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use rovsti_core::contact_edit::{DraftField, FieldKind};

    use super::*;

    fn draft(emails: &[&str]) -> ContactDraft {
        ContactDraft {
            emails: emails
                .iter()
                .map(|e| DraftField::new(*e, FieldKind::Home))
                .collect(),
            ..ContactDraft::default()
        }
    }

    #[test]
    fn first_email_skips_blank_values() {
        let d = draft(&["", "  ", " a@b.c "]);
        assert_eq!(first_email(Some(&d)).as_deref(), Some("a@b.c"));
    }

    #[test]
    fn first_email_is_none_without_addresses() {
        assert_eq!(first_email(None), None);
        assert_eq!(first_email(Some(&draft(&[]))), None);
        assert_eq!(first_email(Some(&draft(&[" "]))), None);
    }
}
