//! `04-menus-{dark,light}`: the popover menu over a busy fake inbox, a
//! row menu in the middle of the screen (the list must not read through it).
//!
//! Run: `cargo test -p rovsti_ui --features shots menus -- --ignored --nocapture`.

use std::time::Duration;

use gpui::{
    AppContext as _, Bounds, Context, IntoElement, ParentElement as _, Render, Styled as _, Window,
    div, point, px, size,
};
use liquid_glass_kit::tokens::Theme;

use super::{PHONE, Rig, ShotSpec, isolate_data_dir};
use crate::{
    components,
    menu::{
        Menu, MenuState,
        model::{MenuGroup, MenuItem, MenuModel},
    },
    row::ListRow,
    theme,
};

struct Gallery {
    menu: MenuState,
}

impl Render for Gallery {
    fn render(&mut self, window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        let theme = crate::current_theme(window);
        let colors = theme::colors(theme);
        let mut list = div().size_full().flex().flex_col();
        for (i, name) in [
            "Ayşe Yılmaz",
            "Mehmet Kaya",
            "Elif Bulut",
            "Selim Demir",
            "Okan Kara",
            "Deniz Aksoy",
            "Burak Şen",
            "Zeynep Aydın",
        ]
        .iter()
        .enumerate()
        {
            list = list.child(
                ListRow::new(*name)
                    .leading(components::avatar(theme, "AB", 48.0, colors.accent))
                    .meta("12:4".to_string() + &i.to_string())
                    .subtitle("See you tomorrow at the usual place, bring the files")
                    .render(theme),
            );
        }
        let model = MenuModel::new(vec![
            MenuGroup::new(vec![MenuItem::new("select", "Select").icon("circle-check")]),
            MenuGroup::new(vec![
                MenuItem::new("pin", "Pin").icon("pin"),
                MenuItem::new("read", "Mark as read").icon("check"),
                MenuItem::new("mute", "Mute...").icon("bell-off").submenu(),
                MenuItem::new("archive", "Archive").icon("archive"),
            ]),
            MenuGroup::new(vec![
                MenuItem::new("delete", "Delete conversation")
                    .icon("trash-2")
                    .destructive(),
            ]),
        ]);
        let menu = Menu::new("Conversation", model)
            .confirm(
                "delete",
                "Delete this conversation? This cannot be undone.",
                "Delete",
            )
            .render(&self.menu, theme, window, |_, _, _| {}, |_, _, _| {});
        div()
            .size_full()
            .relative()
            .child(components::backdrop(theme, theme::accent_glow(theme)))
            .child(list)
            .children(menu)
    }
}

/// Output: `target/shots/rovsti_ui/04-menus-{dark,light}.png`.
#[test]
#[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
fn menus() {
    isolate_data_dir("rovsti_ui");
    for theme in [Theme::Dark, Theme::Light] {
        let spec = ShotSpec {
            app: "rovsti_ui",
            nn: 4,
            screen: "menus",
            theme,
            size: PHONE,
        };
        let mut rig = Rig::open(spec, |_, cx| {
            cx.new(|_| {
                let mut menu = MenuState::new();
                menu.open_at(Bounds::new(
                    point(px(300.0), px(260.0)),
                    size(px(0.0), px(0.0)),
                ));
                for _ in 0..240 {
                    menu.tick(Duration::from_millis(8));
                }
                Gallery { menu }
            })
        });
        rig.theme();
        rig.settle();
        println!("{}", rig.save().display());
    }
}
