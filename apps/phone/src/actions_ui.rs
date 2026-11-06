//! The quick actions strip of the Contact sheet and the Number sheet: Message,
//! Copy, Share and Block (or Unblock) on one number, four 64 px flat glass tiles.
//! The tiles act on finger-up; the logic that picks the number lives with the
//! sheets, this file only draws and runs the four actions.

use gpui::{
    AnyElement, ClipboardItem, Context, InteractiveElement, IntoElement, MouseButton,
    ParentElement, Role, SharedString, StatefulInteractiveElement, Styled, Window, div, prelude::*,
    px, svg,
};
use rovsti_core::number_format::{self, NumberForm};
use rovsti_ui::{
    Icon,
    components::flat_glass,
    motion::press_scale,
    motion_tokens::scale,
    theme,
    tokens::{Theme, typography},
    touch::touch_surface,
};

use crate::{PhoneApp, backend, block_ui::shown_number};

/// The open "Copy as" / "Share as" chooser: every format of one number.
pub(crate) struct CopyAs {
    /// Share instead of copy.
    pub share: bool,
    pub forms: Vec<(NumberForm, String)>,
}

/// Row id of the chooser's `index`-th format.
pub(crate) fn format_id(index: usize) -> String {
    format!("sheet-fmt-{index}")
}

/// The index a chooser row id stands for.
pub(crate) fn format_index(id: &str) -> Option<usize> {
    id.strip_prefix("sheet-fmt-")?.parse().ok()
}

/// The pill after copying one format.
pub(crate) fn copied_text(form: NumberForm) -> String {
    format!("Copied {}", form.label())
}

const TILE: f32 = 64.0;
const GAP: f32 = 12.0;

/// Control ids of the strip on the Contact sheet (routed by the `sheet-c` prefix).
pub(crate) const CONTACT_IDS: [&str; 4] = [
    "sheet-ca-msg",
    "sheet-ca-copy",
    "sheet-ca-share",
    "sheet-ca-block",
];

/// Control ids of the strip on the Number sheet (the `sheet-n` prefix).
pub(crate) const NUMBER_IDS: [&str; 4] = [
    "sheet-na-msg",
    "sheet-na-copy",
    "sheet-na-share",
    "sheet-na-block",
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum StripAction {
    Message,
    Copy,
    Share,
    Block,
    /// Assign the chosen number to this speed-dial digit (chooser only, no tile).
    SpeedDial(u8),
    /// Quick reply (chooser only: the Number and Contact sheets have a pill, not a tile).
    Reply,
}

impl StripAction {
    const ALL: [Self; 4] = [Self::Message, Self::Copy, Self::Share, Self::Block];

    /// The action a strip control id stands for (either sheet).
    pub(crate) fn from_id(id: &str) -> Option<Self> {
        let rest = id
            .strip_prefix("sheet-ca-")
            .or_else(|| id.strip_prefix("sheet-na-"))?;
        match rest {
            "msg" => Some(Self::Message),
            "copy" => Some(Self::Copy),
            "share" => Some(Self::Share),
            "block" => Some(Self::Block),
            "reply" => Some(Self::Reply),
            _ => None,
        }
    }

    /// The Contact sheet's chooser header when this action waits for a number.
    pub(crate) fn chooser_title(self) -> &'static str {
        match self {
            Self::SpeedDial(_) => "Speed dial which number?",
            Self::Message => "Message which number?",
            Self::Copy => "Copy which number?",
            Self::Share => "Share which number?",
            Self::Block => "Block which number?",
            Self::Reply => "Reply to which number?",
        }
    }

    /// `Some(share)` for the two tiles a long press turns into the format chooser.
    pub(crate) fn formats_hold(self) -> Option<bool> {
        match self {
            Self::Copy => Some(false),
            Self::Share => Some(true),
            _ => None,
        }
    }

    fn caption(self, blocked: bool) -> &'static str {
        match self {
            Self::SpeedDial(_) => "Speed dial",
            Self::Reply => "Quick reply",
            Self::Message => "Message",
            Self::Copy => "Copy",
            Self::Share => "Share",
            Self::Block if blocked => "Unblock",
            Self::Block => "Block",
        }
    }

    fn icon(self) -> Icon {
        match self {
            Self::SpeedDial(_) => Icon::LucidePhone,
            Self::Reply => Icon::LucideMessageCircle,
            Self::Message => Icon::LucideMessageCircle,
            Self::Copy => Icon::LucideCopy,
            Self::Share => Icon::LucideShare2,
            Self::Block => Icon::LucideBan,
        }
    }
}

/// The strip. `dim_all` makes every tile inert (nothing to act on); `dim_block`
/// only greys the Block tile (a tap still says why it cannot block).
pub(crate) fn action_strip(
    app: &PhoneApp,
    theme: Theme,
    ids: [&'static str; 4],
    blocked: bool,
    dim_all: bool,
    dim_block: bool,
    cx: &mut Context<PhoneApp>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let tiles = StripAction::ALL.into_iter().zip(ids).map(|(action, id)| {
        let dim = dim_all || (action == StripAction::Block && dim_block);
        let caption = action.caption(blocked);
        let press = app.presses.value(id).clamp(0.0, 1.0);
        let k = press_scale(press, scale::PRESS);
        let ink = if action == StripAction::Block && blocked {
            theme::call_colors(theme).danger
        } else {
            colors.label
        };
        let tile = flat_glass(theme, None)
            .id(id)
            .role(Role::Button)
            .aria_label(caption)
            .relative()
            .size(px(TILE * k))
            .rounded(px(20.0))
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .gap(px(4.0))
            .text_size(typography::FOOTNOTE.size)
            .text_color(ink)
            .when(dim, |d| d.opacity(0.4))
            .child(
                svg()
                    .path(action.icon().path())
                    .size(px(22.0))
                    .text_color(ink),
            )
            .child(caption);
        let tile = if dim_all {
            tile
        } else {
            tile.on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, _, window, cx| {
                    this.presses.down(id);
                    this.number_sheet_act(id, window, cx);
                }),
            )
            .child(touch_surface(PhoneApp::touch_listener(cx, Some(id.into()))))
        };
        div()
            .size(px(TILE))
            .flex_none()
            .flex()
            .items_center()
            .justify_center()
            .child(tile)
    });
    div()
        .flex()
        .justify_center()
        .gap(px(GAP))
        .children(tiles)
        .into_any_element()
}

impl PhoneApp {
    /// Long press on Copy or Share: lists the number in every format.
    pub(crate) fn open_copy_as(
        &mut self,
        share: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let raw = match self.sheet_kind {
            crate::SheetKind::Number => self.recents.number.as_ref().map(|r| r.number.to_string()),
            crate::SheetKind::Contact => self.contact_sheet.as_ref().and_then(|s| {
                match rovsti_core::contacts_view::call_target(&s.contact.numbers) {
                    rovsti_core::contacts_view::CallTarget::Direct(i) => {
                        Some(s.contact.numbers[i].raw.to_string())
                    }
                    _ => None,
                }
            }),
            _ => None,
        };
        let Some(raw) = raw.filter(|r| !r.is_empty()) else {
            return;
        };
        let home_cc = match self.backend.read(cx).call_log() {
            backend::Load::Ready(log) => log.home_cc.clone(),
            _ => None,
        };
        let forms = number_format::available(&raw, home_cc.as_deref());
        if forms.len() < 2 {
            return; // one format only: the plain tap already does it
        }
        rovsti_ui::haptics::perform(window, gpui::HapticFeedback::LongPress);
        self.copy_as = Some(CopyAs { share, forms });
        self.sheet_kind = crate::SheetKind::CopyAs;
        self.presses.release_all();
        cx.notify();
    }

    /// A tap on a chooser row.
    pub(crate) fn copy_as_act(&mut self, id: &str, cx: &mut Context<Self>) {
        let Some(chooser) = &self.copy_as else {
            return;
        };
        let Some((form, text)) = format_index(id).and_then(|i| chooser.forms.get(i)).cloned()
        else {
            return;
        };
        let share = chooser.share;
        self.close_sheet(cx);
        if share {
            let outcome = backend::share_text(&text);
            if outcome != backend::Outcome::Done {
                self.show_notice_pill(
                    outcome.why_not("Couldn't open the share sheet"),
                    None,
                    false,
                    cx,
                );
            }
        } else {
            if backend::copy_text(&text) != backend::Outcome::Done {
                cx.write_to_clipboard(ClipboardItem::new_string(text));
            }
            self.show_notice_pill(copied_text(form), None, false, cx);
        }
    }

    /// The chooser sheet.
    pub(crate) fn copy_as_sheet(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.sheet.is_visible() {
            return None;
        }
        let chooser = self.copy_as.as_ref()?;
        let colors = theme::colors(theme);
        let rows = chooser.forms.iter().enumerate().map(|(i, (form, text))| {
            let id: SharedString = format_id(i).into();
            let press = self.presses.value(&id).clamp(0.0, 1.0);
            let act = id.clone();
            div()
                .id(id.clone())
                .role(Role::Button)
                .aria_label(SharedString::from(format!("{}, {}", form.label(), text)))
                .relative()
                .w_full()
                .min_h(px(58.0))
                .px(px(20.0))
                .py(px(8.0))
                .flex()
                .flex_col()
                .justify_center()
                .bg(gpui::Hsla {
                    a: 0.9 * press,
                    ..colors.fill
                })
                .child(
                    div()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child(form.label()),
                )
                .child(
                    div()
                        .truncate()
                        .text_size(typography::BODY.size)
                        .text_color(colors.label)
                        .child(preview_line(text)),
                )
                .on_mouse_down(
                    MouseButton::Left,
                    cx.listener(move |this, _, _, cx| {
                        this.presses.down(act.clone());
                        this.copy_as_act(&act, cx);
                    }),
                )
                .child(touch_surface(PhoneApp::touch_listener(cx, Some(id))))
        });
        let body = div()
            .flex()
            .flex_col()
            .pb(px(16.0))
            .children(rows)
            .into_any_element();
        self.modal_sheet(
            theme,
            window,
            if chooser.share { "Share as" } else { "Copy as" },
            body,
            cx,
        )
    }

    /// Runs a strip action on `number` (as stored); `who` names it in pills.
    pub(crate) fn run_strip_action(
        &mut self,
        action: StripAction,
        number: SharedString,
        who: SharedString,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match action {
            StripAction::Message => {
                let outcome = backend::send_sms_to(&number);
                if outcome != backend::Outcome::Done {
                    self.show_notice_pill(
                        outcome.why_not("Couldn't open the Messages app"),
                        None,
                        false,
                        cx,
                    );
                }
            }
            StripAction::Copy => {
                cx.write_to_clipboard(ClipboardItem::new_string(shown_number(&number)));
                self.show_notice_pill("Number copied", None, false, cx);
            }
            StripAction::Share => {
                let outcome = backend::share_text(&shown_number(&number));
                if outcome != backend::Outcome::Done {
                    self.show_notice_pill(
                        outcome.why_not("Couldn't open the share sheet"),
                        None,
                        false,
                        cx,
                    );
                }
            }
            StripAction::SpeedDial(digit) => self.speed_dial_set(digit, &number, &who, window, cx),
            StripAction::Reply => self.open_quick_reply(number, cx),
            StripAction::Block => {
                self.close_sheet(cx);
                if self.backend.read(cx).is_blocked(&number) {
                    self.run_unblock(number, who, true, window, cx);
                } else {
                    self.start_block(number, who, None, window, cx);
                }
            }
        }
        cx.notify();
    }
}

/// One line of a format for the chooser (a vCard spans several lines).
fn preview_line(text: &str) -> String {
    text.lines().collect::<Vec<_>>().join("  ")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn format_ids_round_trip() {
        for i in [0, 1, 4, 12] {
            assert_eq!(format_index(&format_id(i)), Some(i));
        }
        assert_eq!(format_index("sheet-fmt-"), None);
        assert_eq!(format_index("sheet-fmt-x"), None);
        assert_eq!(format_index("sheet-ca-copy"), None);
    }

    #[test]
    fn only_copy_and_share_open_the_chooser() {
        assert_eq!(StripAction::Copy.formats_hold(), Some(false));
        assert_eq!(StripAction::Share.formats_hold(), Some(true));
        assert_eq!(StripAction::Message.formats_hold(), None);
        assert_eq!(StripAction::Block.formats_hold(), None);
    }

    #[test]
    fn copied_pill_names_the_format() {
        assert_eq!(
            copied_text(NumberForm::E164),
            "Copied International (E.164)"
        );
    }

    #[test]
    fn preview_joins_lines() {
        assert_eq!(preview_line("a\nb"), "a  b");
    }

    #[test]
    fn chooser_titles_name_the_verb() {
        assert_eq!(
            StripAction::Message.chooser_title(),
            "Message which number?"
        );
        assert_eq!(StripAction::Copy.chooser_title(), "Copy which number?");
        assert_eq!(StripAction::Share.chooser_title(), "Share which number?");
        assert_eq!(StripAction::Block.chooser_title(), "Block which number?");
    }

    #[test]
    fn ids_keep_the_router_prefixes_and_round_trip() {
        for (i, action) in StripAction::ALL.into_iter().enumerate() {
            assert!(CONTACT_IDS[i].starts_with("sheet-c"));
            assert!(NUMBER_IDS[i].starts_with("sheet-n"));
            assert_eq!(StripAction::from_id(CONTACT_IDS[i]), Some(action));
            assert_eq!(StripAction::from_id(NUMBER_IDS[i]), Some(action));
        }
        assert_eq!(StripAction::from_id("sheet-cstar"), None);
        assert_eq!(StripAction::from_id("sheet-ncall"), None);
    }

    #[test]
    fn speed_dial_chooser_names_the_verb() {
        assert_eq!(
            StripAction::SpeedDial(5).chooser_title(),
            "Speed dial which number?"
        );
        assert_eq!(StripAction::from_id("sheet-csd-5"), None);
        assert_eq!(
            StripAction::from_id("sheet-ca-reply"),
            Some(StripAction::Reply)
        );
        assert_eq!(StripAction::Reply.chooser_title(), "Reply to which number?");
    }

    #[test]
    fn block_reads_unblock_when_blocked() {
        assert_eq!(StripAction::Block.caption(false), "Block");
        assert_eq!(StripAction::Block.caption(true), "Unblock");
        assert_eq!(StripAction::Copy.caption(true), "Copy");
    }
}
