//! Small pieces the Contacts pages share: a form field box, a tappable row
//! and the confirmation card.

use std::cell::Cell;

use gpui::{
    AnyElement, App, Bounds, Context, Div, ElementId, Entity, Hsla, InteractiveElement,
    IntoElement, ParentElement, Pixels, SharedString, Stateful, Styled, TextInputAction,
    TextInputPurpose, Window, canvas, div, px, svg,
};
use rovsti_ui::{
    Icon,
    components::glass_pill,
    field::{Bare, TextField, bare_field},
    glass::{GlassSpec, glass},
    theme,
    tokens::{Theme, metrics, shadow, typography},
};

/// How a tappable row reads.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Tone {
    Plain,
    Accent,
    Danger,
}

/// A row that can be tapped: an optional icon and a label, with the list
/// separator under it. Callers add `.on_click`.
pub(crate) fn tap_row(
    theme: Theme,
    id: impl Into<ElementId>,
    icon: Option<Icon>,
    label: impl Into<SharedString>,
    tone: Tone,
) -> Stateful<Div> {
    let colors = theme::colors(theme);
    let color = match tone {
        Tone::Plain => colors.label,
        Tone::Accent => colors.accent,
        Tone::Danger => colors.red,
    };
    div()
        .id(id)
        .flex()
        .items_center()
        .gap(px(10.0))
        .min_h(metrics::MIN_HIT)
        .px(metrics::ROW_INSET)
        .border_b(metrics::SEPARATOR)
        .border_color(colors.separator)
        .text_size(typography::BODY.size)
        .text_color(color)
        .children(icon.map(|icon| svg().path(icon.path()).size(px(18.0)).text_color(color)))
        .child(label.into())
}

/// One row of a form section: icons, boxes and buttons side by side, with the
/// list separator under it.
pub(crate) fn form_row(theme: Theme) -> Div {
    let colors = theme::colors(theme);
    div()
        .flex()
        .items_center()
        .gap(px(8.0))
        .min_h(metrics::MIN_HIT)
        .px(metrics::ROW_INSET)
        .border_b(metrics::SEPARATOR)
        .border_color(colors.separator)
}

/// A one-line (or, for a note, several-line) text box. Put it in a
/// [`form_row`].
///
/// It is a [`bare_field`]: the shared field draws the text, a placeholder
/// while the box is empty and untouched, the caret and selection while it has
/// focus; a tap focuses it and raises the keyboard.
pub(crate) fn field_box(
    theme: Theme,
    id: impl Into<ElementId>,
    field: &Entity<TextField>,
    placeholder: &'static str,
    multiline: bool,
    window: &Window,
    cx: &App,
) -> impl IntoElement {
    let typing = field.read(cx).focus_handle().is_focused(window);
    let height = f32::from(metrics::MIN_HIT);
    debug_assert_eq!(multiline, field.read(cx).is_multiline());
    div()
        .relative()
        .flex_1()
        .min_w_0()
        .flex()
        .items_center()
        .child(bare_field(
            theme,
            id,
            field,
            placeholder,
            Bare {
                height: Some(height),
                pad_v: NOTE_PAD_V,
            },
            window,
            cx,
        ))
        .children(typing.then(focused_bounds_probe))
}

/// Space above and below the lines of a note, dp.
const NOTE_PAD_V: f32 = 5.0;

/// Which keyboard a Contacts field asks for.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Config {
    /// The search box: one line, a Search key.
    Search,
    /// A name, an organisation or a group title.
    Text,
    Phone,
    Email,
    /// The note or an address: several lines, Enter makes a new line.
    Note,
}

/// Lines a note starts with, and the most it shows before it scrolls.
const NOTE_LINES: (usize, usize) = (4, 8);

impl Config {
    pub(crate) const SEARCH: Self = Self::Search;
    pub(crate) const TEXT: Self = Self::Text;
    pub(crate) const PHONE: Self = Self::Phone;
    pub(crate) const EMAIL: Self = Self::Email;
    pub(crate) const NOTE: Self = Self::Note;

    /// The keyboard purpose and action key, `None` for the multi-line note.
    fn input(self) -> Option<(TextInputPurpose, TextInputAction)> {
        Some(match self {
            Self::Search => (TextInputPurpose::Text, TextInputAction::Search),
            Self::Text => (TextInputPurpose::Text, TextInputAction::Done),
            Self::Phone => (TextInputPurpose::Phone, TextInputAction::Done),
            Self::Email => (TextInputPurpose::Email, TextInputAction::Done),
            Self::Note => return None,
        })
    }

    /// A field with this keyboard, starting with `text`.
    pub(crate) fn build(self, text: &str, cx: &mut Context<TextField>) -> TextField {
        let mut field = match self.input() {
            Some((purpose, action)) => TextField::with_input(purpose, action, cx),
            None => TextField::multiline(NOTE_LINES.0, NOTE_LINES.1, cx),
        };
        if !text.is_empty() {
            field.set(text, cx);
        }
        field
    }
}

thread_local! {
    /// Where the focused [`field_box`] was last painted, in window
    /// coordinates (UI thread only).
    static FOCUSED_FIELD: Cell<Option<Bounds<Pixels>>> = const { Cell::new(None) };
}

/// Takes the bounds the focused field reported in the last paint, if one
/// was painted. The page reads it each render to keep that field above the
/// keyboard.
pub(crate) fn take_focused_field_bounds() -> Option<Bounds<Pixels>> {
    FOCUSED_FIELD.with(Cell::take)
}

/// An invisible layer that reports the focused field's painted bounds.
fn focused_bounds_probe() -> impl IntoElement {
    canvas(
        |_, _, _| (),
        |bounds, (), _, _| FOCUSED_FIELD.with(|cell| cell.set(Some(bounds))),
    )
    .absolute()
    .size_full()
}

/// A dimmed screen with a centred glass card: a title, a sentence and the
/// buttons (already built, left to right).
pub(crate) fn confirm_card(
    theme: Theme,
    title: impl Into<SharedString>,
    body: impl Into<SharedString>,
    buttons: Vec<AnyElement>,
) -> AnyElement {
    let colors = theme::colors(theme);
    div()
        .id("confirm-scrim")
        .absolute()
        .top_0()
        .left_0()
        .size_full()
        .flex()
        .items_center()
        .justify_center()
        .px(px(28.0))
        .bg(Hsla {
            a: 0.4,
            ..gpui::black()
        })
        // The scrim swallows every touch so nothing underneath reacts (a stop_propagation on
        // mouse down is not enough: the rows below also act on the matching mouse up).
        .occlude()
        .child(
            glass(GlassSpec::control(theme))
                .w_full()
                .max_w(px(340.0))
                .rounded(px(26.0))
                .shadow(shadow::glass(theme.is_dark()))
                .flex()
                .flex_col()
                .gap(px(10.0))
                .p(px(20.0))
                .child(
                    div()
                        .text_size(typography::HEADLINE.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.label)
                        .child(title.into()),
                )
                .child(
                    div()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child(body.into()),
                )
                .child(
                    div()
                        .flex()
                        .justify_end()
                        .gap(px(10.0))
                        .pt(px(8.0))
                        .children(buttons),
                ),
        )
        .into_any_element()
}

/// A glass pill button with an id, coloured as a destructive choice when
/// `danger` is set.
pub(crate) fn pill(
    theme: Theme,
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    danger: bool,
) -> Stateful<Div> {
    let button = glass_pill(theme, label).id(id);
    if danger {
        button.text_color(theme::colors(theme).red)
    } else {
        button
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn each_config_asks_for_its_keyboard() {
        assert_eq!(
            Config::SEARCH.input(),
            Some((TextInputPurpose::Text, TextInputAction::Search))
        );
        assert_eq!(
            Config::TEXT.input(),
            Some((TextInputPurpose::Text, TextInputAction::Done))
        );
        assert_eq!(
            Config::PHONE.input(),
            Some((TextInputPurpose::Phone, TextInputAction::Done))
        );
        assert_eq!(
            Config::EMAIL.input(),
            Some((TextInputPurpose::Email, TextInputAction::Done))
        );
    }

    #[test]
    fn the_note_is_the_only_multiline_config() {
        assert_eq!(Config::NOTE.input(), None);
        for config in [Config::SEARCH, Config::TEXT, Config::PHONE, Config::EMAIL] {
            assert!(config.input().is_some());
        }
    }
}
