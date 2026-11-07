//! `03-layout-{dark,light}`: the page-level building blocks on one 412x915
//! page: a header with one action, a header with two (one with a badge), a
//! section header over two rows, and an empty state with an action.
//!
//! Thin guides mark the 16 dp margins, so alignment can be judged by eye.
//!
//! Run: `cargo test -p rovsti_ui --features shots layout -- --ignored --nocapture`.

use gpui::{
    AppContext as _, Context, IntoElement, ParentElement as _, Render, Styled as _, Window, div, px,
};
use liquid_glass_kit::tokens::Theme;

use super::{PHONE, Rig, ShotSpec, isolate_data_dir};
use crate::{
    Icon, components,
    layout::MARGIN,
    page::{HeaderAction, PageHeader, empty_state, section_header},
    theme,
};

struct Gallery;

impl Render for Gallery {
    fn render(&mut self, window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        let theme = crate::current_theme(window);
        let colors = theme::colors(theme);
        let guide = colors.label.opacity(0.18);
        let page = div()
            .size_full()
            .flex()
            .flex_col()
            .child(
                PageHeader::new("Contacts")
                    .action(HeaderAction::new("g-add", Icon::LucidePlus, "Add contact"))
                    .render(window, theme),
            )
            .child(
                PageHeader::new("Messages")
                    .subtitle("3 unread")
                    .action(HeaderAction::new("g-search", Icon::LucideSearch, "Search"))
                    .action(HeaderAction::new("g-new", Icon::LucidePlus, "New chat").badge(true))
                    .render(window, theme),
            )
            .child(section_header(theme, "Recent"))
            .child(components::list_row(
                theme,
                Some(components::avatar(theme, "AY", 48.0, colors.accent).into_any_element()),
                "Ayşe Yılmaz",
                Some("See you at the station".into()),
                Some("09:41".into()),
            ))
            .child(components::list_row(
                theme,
                Some(components::avatar(theme, "MK", 48.0, colors.green).into_any_element()),
                "Mehmet Kaya",
                Some("Thanks!".into()),
                Some("Yesterday".into()),
            ))
            .child(empty_state(
                theme,
                Some(Icon::LucideMessageSquare),
                "No messages yet",
                "Start a chat and it will show up here.",
                Some(components::glass_pill(theme, "New message").into_any_element()),
            ));
        div()
            .size_full()
            .relative()
            .child(components::backdrop(theme, theme::accent_glow(theme)))
            .child(page)
            .child(
                div()
                    .absolute()
                    .top_0()
                    .bottom_0()
                    .left(px(MARGIN))
                    .w(px(0.5))
                    .bg(guide),
            )
            .child(
                div()
                    .absolute()
                    .top_0()
                    .bottom_0()
                    .right(px(MARGIN))
                    .w(px(0.5))
                    .bg(guide),
            )
    }
}

/// Run: `cargo test -p rovsti_ui --features shots layout -- --ignored --nocapture`
/// Output: `target/shots/rovsti_ui/03-layout-{dark,light}.png`.
#[test]
#[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
fn layout() {
    isolate_data_dir("rovsti_ui");
    for theme in [Theme::Dark, Theme::Light] {
        let spec = ShotSpec {
            app: "rovsti_ui",
            nn: 3,
            screen: "layout",
            theme,
            size: PHONE,
        };
        let mut rig = Rig::open(spec, |_, cx| cx.new(|_| Gallery));
        rig.theme();
        rig.settle();
        println!("{}", rig.save().display());
    }
}
