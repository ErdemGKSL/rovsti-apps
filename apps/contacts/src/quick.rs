//! The quick sheet: a long press on a Favorites, group or Recent row lists the
//! actions of `rovsti_core::contact_quick` for that contact. Also the
//! Favorites header buttons (Edit / Done / +).

use gpui::{
    AnyElement, Context, IntoElement, ParentElement, SharedString, StatefulInteractiveElement,
    Styled, Window, div, px,
};
use rovsti_core::{
    contact_quick::{self, QuickAction},
    contacts_view::{self, CallTarget, NumberVm},
};
use rovsti_ui::{
    Icon,
    theme::{self},
    tokens::{Theme, typography},
};

use crate::{
    ContactsApp, Row,
    ui::{Tone, tap_row},
};

/// What the open quick sheet acts on.
#[derive(Clone, Debug)]
pub(crate) struct QuickTarget {
    pub(crate) id: i64,
    pub(crate) name: String,
    pub(crate) numbers: Vec<NumberVm>,
    pub(crate) wa_jid: Option<String>,
    /// `quick_target` of the row it was opened from.
    pub(crate) flags: (bool, bool, bool),
}

/// `(has a number, is on WhatsApp, is starred)` for a row.
pub(crate) fn quick_target(row: &Row) -> (bool, bool, bool) {
    (
        !row.vm.numbers.is_empty(),
        row.wa_jid.is_some(),
        row.vm.starred,
    )
}

/// A button in the Favorites header.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum FavHeader {
    Add,
    Edit,
    Done,
}

/// The Favorites header buttons: Edit needs two favorites to reorder; while
/// editing only Done shows.
pub(crate) fn favorites_header_actions(n: usize, editing: bool) -> Vec<FavHeader> {
    if editing {
        vec![FavHeader::Done]
    } else if n >= 2 {
        vec![FavHeader::Edit, FavHeader::Add]
    } else {
        vec![FavHeader::Add]
    }
}

fn icon_of(action: QuickAction) -> Icon {
    match action {
        QuickAction::Call => Icon::LucidePhone,
        QuickAction::Message | QuickAction::WhatsApp => Icon::LucideMessageCircle,
        QuickAction::CopyNumber => Icon::LucideCopy,
        QuickAction::Share => Icon::LucideUpload,
        QuickAction::Star | QuickAction::Unstar => Icon::LucideStar,
        QuickAction::AddToHome => Icon::LucideSmartphone,
    }
}

fn row_id(action: QuickAction) -> &'static str {
    match action {
        QuickAction::Call => "quick-call",
        QuickAction::Message => "quick-message",
        QuickAction::WhatsApp => "quick-whatsapp",
        QuickAction::CopyNumber => "quick-copy",
        QuickAction::Share => "quick-share",
        QuickAction::Star => "quick-star",
        QuickAction::Unstar => "quick-unstar",
        QuickAction::AddToHome => "quick-home",
    }
}

impl ContactsApp {
    /// Opens the quick sheet for contact `id` (a long press on its row).
    pub(crate) fn open_quick_for(&mut self, id: i64, cx: &mut Context<Self>) {
        let Some(contact) = self.index().and_then(|i| i.all().find(|c| c.id == id)) else {
            return;
        };
        let mut row: Row = contacts_view::build_rows_styled([contact], "", self.style)
            .into_iter()
            .next()
            .map(Row::from)
            .unwrap_or_else(|| unreachable!("one contact builds one row"));
        row.set_whatsapp(self.wa.marked.get(&id).map(String::as_str));
        self.quick = Some(QuickTarget {
            id,
            name: row.vm.name.clone(),
            numbers: row.vm.numbers.clone(),
            wa_jid: row.wa_jid.clone(),
            flags: quick_target(&row),
        });
        self.quick_sheet.open();
        cx.notify();
    }

    pub(crate) fn close_quick_sheet(&mut self, cx: &mut Context<Self>) {
        self.quick_sheet.close();
        cx.notify();
    }

    /// The number Call, Message and Copy use: the default or only one. With
    /// several and no default the card opens so the owner taps one.
    fn quick_number(
        &mut self,
        target: &QuickTarget,
        hint: &str,
        cx: &mut Context<Self>,
    ) -> Option<String> {
        match contacts_view::call_target(&target.numbers) {
            CallTarget::Direct(i) => Some(target.numbers[i].raw.clone()),
            CallTarget::Choose => {
                self.open_contact(target.id, cx);
                self.notice = Some(hint.to_owned().into());
                cx.notify();
                None
            }
            CallTarget::Nothing => {
                self.notice = Some("This contact has no number.".into());
                cx.notify();
                None
            }
        }
    }

    fn run_quick(&mut self, action: QuickAction, cx: &mut Context<Self>) {
        let Some(target) = self.quick.clone() else {
            return;
        };
        self.quick_sheet.close();
        match action {
            QuickAction::Call => {
                if let Some(number) = self.quick_number(&target, "Choose a number to call.", cx) {
                    self.act(
                        "call",
                        move || rovsti_android::telecom::place_call(&number),
                        cx,
                    );
                }
            }
            QuickAction::Message => {
                if let Some(number) = self.quick_number(&target, "Choose a number to message.", cx)
                {
                    self.act(
                        "send a message",
                        move || rovsti_android::telecom::send_sms_to(&number),
                        cx,
                    );
                }
            }
            QuickAction::WhatsApp => match target.wa_jid {
                Some(jid) => self.wa_handoff(false, jid, target.name, cx),
                None => self.notice = Some("This contact isn't on WhatsApp.".into()),
            },
            QuickAction::CopyNumber => {
                if let Some(number) = self.quick_number(&target, "Tap a number to copy it.", cx) {
                    self.act(
                        "copy the number",
                        move || rovsti_android::telecom::copy_text(&number),
                        cx,
                    );
                    self.notice = Some("Number copied.".into());
                }
            }
            QuickAction::Share => {
                self.open_contact(target.id, cx);
                self.share_sheet.open();
            }
            QuickAction::Star | QuickAction::Unstar => self.toggle_star(target.id, cx),
            QuickAction::AddToHome => self.pin_home(target.id, cx),
        }
        cx.notify();
    }

    /// The quick sheet, while it is on screen.
    pub(crate) fn quick_sheet_view(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        self.quick_sheet
            .set_reduced(window.prefers_reduced_motion());
        self.quick_sheet.set_viewport(window.viewport_size());
        if !self.quick_sheet.is_visible() {
            return None;
        }
        let target = self.quick.as_ref()?;
        let colors = theme::colors(theme);
        let (has_number, on_whatsapp, starred) = target.flags;
        let name: SharedString = target.name.clone().into();
        let rows: Vec<AnyElement> = contact_quick::actions(has_number, on_whatsapp, starred)
            .into_iter()
            .map(|action| {
                tap_row(
                    theme,
                    row_id(action),
                    Some(icon_of(action)),
                    action.label(),
                    Tone::Plain,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.run_quick(action, cx)))
                .into_any_element()
            })
            .collect();
        let body = div()
            .flex()
            .flex_col()
            .pb(px(12.0))
            .child(
                div()
                    .px(px(20.0))
                    .pb(px(8.0))
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(name),
            )
            .children(rows);
        let entity = cx.entity();
        let drag = entity.clone();
        self.quick_sheet.render(
            theme,
            window,
            "Quick actions",
            body,
            move |_, cx| entity.update(cx, |this, cx| this.close_quick_sheet(cx)),
            move |phase, y, _, cx| {
                drag.update(cx, |this, cx| {
                    this.quick_sheet.drag_touch(phase, y);
                    cx.notify();
                });
            },
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::contacts_view::ContactVm;

    fn row(numbers: usize, jid: Option<&str>, starred: bool) -> Row {
        let mut row = Row::from(ContactVm {
            id: 1,
            name: "Ayse".into(),
            subtitle: None,
            letter: 'A',
            header: true,
            monogram: Some('A'),
            hue: None,
            numbers: (0..numbers)
                .map(|i| NumberVm {
                    raw: format!("+90{i}"),
                    display: format!("+90 {i}"),
                    label: "Mobile".into(),
                    default: false,
                })
                .collect(),
            starred,
            photo_uri: None,
        });
        row.set_whatsapp(jid);
        row
    }

    #[test]
    fn target_reads_number_whatsapp_and_star() {
        assert_eq!(quick_target(&row(0, None, false)), (false, false, false));
        assert_eq!(quick_target(&row(2, Some("x@s"), true)), (true, true, true));
        assert_eq!(quick_target(&row(1, None, true)), (true, false, true));
    }

    #[test]
    fn header_shows_edit_only_with_two_favorites() {
        use FavHeader::*;
        assert_eq!(favorites_header_actions(0, false), [Add]);
        assert_eq!(favorites_header_actions(1, false), [Add]);
        assert_eq!(favorites_header_actions(2, false), [Edit, Add]);
        assert_eq!(favorites_header_actions(5, true), [Done]);
        assert_eq!(favorites_header_actions(1, true), [Done]);
    }
}
