//! The quick actions strip of the Contact sheet and the Number sheet: Message,
//! Copy, Share and Block (or Unblock) on one number, four 64 px flat glass tiles.
//! The tiles act on finger-up; the logic that picks the number lives with the
//! sheets, this file only draws and runs the four actions.

use gpui::{
    AnyElement, ClipboardItem, Context, InteractiveElement, IntoElement, MouseButton,
    ParentElement, Role, SharedString, StatefulInteractiveElement, Styled, Window, div, prelude::*,
    px, svg,
};
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
        }
    }

    fn caption(self, blocked: bool) -> &'static str {
        match self {
            Self::SpeedDial(_) => "Speed dial",
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

#[cfg(test)]
mod tests {
    use super::*;

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
    }

    #[test]
    fn block_reads_unblock_when_blocked() {
        assert_eq!(StripAction::Block.caption(false), "Block");
        assert_eq!(StripAction::Block.caption(true), "Unblock");
        assert_eq!(StripAction::Copy.caption(true), "Copy");
    }
}
