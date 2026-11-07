//! The "Search in chat" bar shared by the WhatsApp and SMS pages: a field, the
//! "2 of 5" label, older/newer buttons and a close button, plus a "Load earlier"
//! pill when the oldest loaded hit is reached. The matching is
//! `rovsti_core::chat_find`; the pages own what a hit reveals.

use gpui::{
    AnyElement, AppContext, Context, Entity, InteractiveElement, IntoElement, ParentElement,
    StatefulInteractiveElement, Styled, TextInputAction, TextInputPurpose, Window, div, px,
};
use rovsti_core::chat_find::FindCursor;
use rovsti_ui::{
    Icon,
    components::{glass_icon_button, glass_pill},
    field::{TextField, field_box},
    theme,
    tokens::{Theme, typography},
};

/// An open find bar. `K` names a message (WhatsApp id, SMS key).
pub(crate) struct FindState<K> {
    pub field: Entity<TextField>,
    pub cursor: FindCursor,
    /// The text the hits were computed for.
    pub query: String,
    /// The hit to stay on (and step older from) when the next page arrives.
    pub anchor: Option<K>,
    /// The page being loaded was asked for by "Load earlier" on this bar.
    pub asked: bool,
    _subscription: gpui::Subscription,
}

impl<K> FindState<K> {
    /// A bar whose field runs `on_edit` after every change of the field.
    pub fn new<T: 'static>(on_edit: fn(&mut T, &mut Context<T>), cx: &mut Context<T>) -> Self {
        let field =
            cx.new(|cx| TextField::with_input(TextInputPurpose::Text, TextInputAction::Search, cx));
        let _subscription = cx.observe(&field, move |this, _, cx| on_edit(this, cx));
        Self {
            field,
            cursor: FindCursor::default(),
            query: String::new(),
            anchor: None,
            asked: false,
            _subscription,
        }
    }
}

/// What the bar's buttons do on page `T`.
pub(crate) struct FindActions<T> {
    pub older: fn(&mut T, &mut Context<T>),
    pub newer: fn(&mut T, &mut Context<T>),
    pub close: fn(&mut T, &mut Context<T>),
    pub load_earlier: fn(&mut T, &mut Context<T>),
}

/// The bar under the header. `can_load` is the page's `has_older()`.
pub(crate) fn find_bar<K, T: 'static>(
    theme: Theme,
    find: &FindState<K>,
    can_load: bool,
    loading: bool,
    actions: &FindActions<T>,
    window: &Window,
    cx: &mut Context<T>,
) -> AnyElement {
    let colors = theme::colors(theme);
    let (older, newer, close, load_earlier) = (
        actions.older,
        actions.newer,
        actions.close,
        actions.load_earlier,
    );
    let has_query = !find.query.trim().is_empty();
    let label = if has_query {
        find.cursor.label()
    } else {
        String::new()
    };
    let none = find.cursor.is_empty();
    let step = |icon: Icon, id: &'static str, run: fn(&mut T, &mut Context<T>)| {
        let button = glass_icon_button(theme, icon, 0.0, false).id(id);
        if none {
            button.opacity(0.4)
        } else {
            button.on_click(cx.listener(move |this, _, _, cx| run(this, cx)))
        }
    };
    let up = step(Icon::LucideChevronUp, "find-older", older);
    let down = step(Icon::LucideChevronDown, "find-newer", newer);
    let x = glass_icon_button(theme, Icon::LucideX, 0.0, false)
        .id("find-close")
        .on_click(cx.listener(move |this, _, _, cx| close(this, cx)));
    let pill =
        (has_query && can_load && (find.cursor.is_empty() || find.cursor.is_oldest())).then(|| {
            div().flex().justify_center().child(
                glass_pill(
                    theme,
                    if loading {
                        "Loading…"
                    } else {
                        "Load earlier"
                    },
                )
                .id("find-load-earlier")
                .on_click(cx.listener(move |this, _, _, cx| load_earlier(this, cx))),
            )
        });
    div()
        .flex_none()
        .flex()
        .flex_col()
        .gap(px(6.0))
        .px(px(12.0))
        .pb(px(8.0))
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(8.0))
                .child(div().flex_1().min_w_0().child(field_box(
                    theme,
                    "find-field",
                    &find.field,
                    "Search in chat",
                    window,
                    cx,
                )))
                .child(
                    div()
                        .text_size(typography::FOOTNOTE.size)
                        .text_color(colors.secondary_label)
                        .child(label),
                )
                .child(up)
                .child(down)
                .child(x),
        )
        .children(pill)
        .into_any_element()
}
