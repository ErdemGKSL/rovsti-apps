//! `04-menus-*`: the popover menu and the button set, headless.
//!
//! The menu is drawn over a busy fake inbox, so anything that reads through
//! the panel shows up as contrast inside a label-free patch of the menu body.
//! One state per shot (the menu is a full-window overlay):
//!
//! * `04-menus-{dark,light}`: 3-group row menu below a mid-screen anchor
//! * `04-menus-above-*`: the same menu flipped above an anchor by the tab bar
//! * `04-menus-submenu-*`: the "Mute for" page
//! * `04-menus-confirm-*`: the delete confirm page
//! * `04-menus-buttons-*`: 4 styles x 3 sizes, destructive and disabled
//!
//! Run: `cargo test -p rovsti_ui --features shots menus -- --ignored --nocapture`.

use std::time::Duration;

use gpui::{
    AppContext as _, Bounds, Context, IntoElement, ParentElement as _, Render, Styled as _, Window,
    div, point, px, size,
};
use image::RgbaImage;
use liquid_glass_kit::tokens::{Theme, typography};

use super::{PHONE, Rig, SCALE, SIM_SAFE_BOTTOM, SIM_SAFE_TOP, ShotSpec, color, isolate_data_dir};
use crate::{
    button::{Button, ButtonSize, ButtonStyle},
    components,
    layout::{
        self, MARGIN, TAB_BAR_GAP, TAB_BAR_H,
        geom::{self, Rect},
    },
    menu::{
        Menu, MenuPage, MenuState,
        model::{MenuGroup, MenuItem, MenuModel},
    },
    row::{BadgeTone, ListRow},
    theme,
};

/// Contrast spread allowed inside a label-free patch of the menu body.
const BODY_MAX_CONTRAST: f32 = 1.6;
/// WCAG AA for text.
const LABEL_MIN_CONTRAST: f32 = 4.5;
/// Label-free patch of the menu body (dp).
const PATCH_W: f32 = 40.0;
const PATCH_H: f32 = 16.0;
/// Label sample window (dp), starting at the label column.
const LABEL_W: f32 = 56.0;
const LABEL_H: f32 = 16.0;
/// Where a row's label starts, from the panel's left edge (dp).
const LABEL_X: f32 = 48.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Scene {
    Below,
    Above,
    Submenu,
    Confirm,
}

impl Scene {
    fn screen(self) -> &'static str {
        match self {
            Self::Below => "menus",
            Self::Above => "menus-above",
            Self::Submenu => "menus-submenu",
            Self::Confirm => "menus-confirm",
        }
    }

    /// Anchor (x, y, w, h) in dp: a 44 dp "..." button.
    fn anchor(self) -> Rect {
        match self {
            Self::Below | Self::Submenu | Self::Confirm => Rect::new(352.0, 250.0, 44.0, 44.0),
            // 108 dp above the viewport bottom: the menu cannot fit below.
            Self::Above => Rect::new(352.0, 700.0, 44.0, 44.0),
        }
    }
}

/// Top of row `index` (0-based, across groups) of a menu whose panel starts
/// at `panel_y`, with `groups` items per group (dp).
fn row_top(panel_y: f32, groups: &[usize], index: usize) -> f32 {
    let mut y = panel_y + geom::MENU_PAD_V / 2.0;
    let mut seen = 0;
    for (g, &n) in groups.iter().enumerate() {
        if g > 0 {
            y += geom::MENU_GROUP_GAP;
        }
        for _ in 0..n {
            if seen == index {
                return y;
            }
            y += geom::MENU_ROW_H;
            seen += 1;
        }
    }
    y
}

fn row_model() -> MenuModel {
    MenuModel::new(vec![
        MenuGroup::new(vec![MenuItem::new("select", "Select").icon("circle-check")]),
        MenuGroup::new(vec![
            MenuItem::new("pin", "Pin").icon("pin"),
            MenuItem::new("read", "Mark as read").icon("check"),
            MenuItem::new("mute", "Mute for").icon("bell-off").submenu(),
            MenuItem::new("archive", "Archive").icon("archive"),
        ]),
        MenuGroup::new(vec![
            MenuItem::new("delete", "Delete conversation")
                .icon("trash-2")
                .destructive(),
        ]),
    ])
}

fn mute_model() -> MenuModel {
    MenuModel::new(vec![MenuGroup::new(vec![
        MenuItem::new("mute_1h", "1 hour").icon("clock"),
        MenuItem::new("mute_8h", "8 hours").icon("clock"),
        MenuItem::new("mute_1w", "1 week").icon("calendar"),
        MenuItem::new("mute_always", "Always").icon("bell-off"),
    ])])
}

const NAMES: [&str; 12] = [
    "Ayşe Yılmaz",
    "Mehmet Kaya",
    "Elif Bulut",
    "Selim Demir",
    "Okan Kara",
    "Deniz Aksoy",
    "Burak Şen",
    "Zeynep Aydın",
    "Family",
    "Garanti BBVA",
    "Dr. Konstantinopoulou",
    "Cem Arslan",
];

/// A full-height inbox with long titles, times and badges, as noisy as it gets.
fn busy_inbox(theme: Theme) -> gpui::Div {
    let colors = theme::colors(theme);
    let mut list = div().size_full().flex().flex_col().pt(px(SIM_SAFE_TOP));
    for (i, name) in NAMES.iter().enumerate() {
        let mut row = ListRow::new(*name)
            .leading(components::avatar(theme, &name[..1], 48.0, colors.accent))
            .meta(format!("12:{i:02}"))
            .subtitle("See you tomorrow at the usual place, bring the files and the tickets");
        if i % 3 == 0 {
            row = row.unread(true).trailing_badge("3", BadgeTone::Accent);
        }
        list = list.child(row.render(theme));
    }
    list
}

/// Stand-in for the floating tab bar: same height, gap and margins.
fn tab_bar_stand_in(theme: Theme) -> gpui::Div {
    let colors = theme::colors(theme);
    div()
        .absolute()
        .left(px(MARGIN))
        .right(px(MARGIN))
        .bottom(px(SIM_SAFE_BOTTOM + TAB_BAR_GAP))
        .h(px(TAB_BAR_H))
        .rounded_full()
        .bg(crate::button::gray_fill(theme))
        .flex()
        .items_center()
        .justify_center()
        .text_size(typography::FOOTNOTE.size)
        .text_color(colors.secondary_label)
        .child("Tab bar")
}

struct MenuGallery {
    scene: Scene,
    menu: MenuState,
}

impl Render for MenuGallery {
    fn render(&mut self, window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        let theme = crate::current_theme(window);
        let colors = theme::colors(theme);
        let a = self.scene.anchor();
        let menu = Menu::new("Conversation", row_model())
            .submenu("mute", mute_model())
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
            .child(busy_inbox(theme))
            .child(tab_bar_stand_in(theme))
            // The anchor: a 44 dp "..." button.
            .child(
                div()
                    .absolute()
                    .left(px(a.x))
                    .top(px(a.y))
                    .size(px(a.w))
                    .rounded_full()
                    .bg(colors.accent)
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_color(crate::row::readable_on(colors.accent))
                    .child("..."),
            )
            .children(menu)
    }
}

fn open_menu(scene: Scene) -> MenuState {
    let a = scene.anchor();
    let mut menu = MenuState::new();
    menu.open_at(Bounds::new(point(px(a.x), px(a.y)), size(px(a.w), px(a.h))));
    match scene {
        Scene::Submenu => menu.push_submenu("mute"),
        Scene::Confirm => menu.confirm("delete"),
        Scene::Below | Scene::Above => {}
    }
    for _ in 0..240 {
        menu.tick(Duration::from_millis(8));
    }
    menu
}

fn px_rect(x: f32, y: f32, w: f32, h: f32) -> (u32, u32, u32, u32) {
    let s = SCALE as f32;
    (
        (x * s).round().max(0.0) as u32,
        (y * s).round().max(0.0) as u32,
        (w * s).round() as u32,
        (h * s).round() as u32,
    )
}

/// The asserts of one list-style menu shot.
fn assert_menu(img: &RgbaImage, scene: Scene, theme: Theme, panel: Rect) {
    let tag = format!("{scene:?} {theme:?}");
    // The panel stays between the status bar and the tab-bar clearance.
    let top = SIM_SAFE_TOP + layout::TOP_GAP;
    let bottom = PHONE.height - geom::content_bottom(SIM_SAFE_BOTTOM, 0.0);
    assert!(
        panel.y >= top - 0.01 && panel.bottom() <= bottom + 0.01,
        "{tag}: panel {panel:?} outside [{top}, {bottom}]"
    );
    assert!(
        panel.x >= MARGIN - 0.01 && panel.right() <= PHONE.width - MARGIN + 0.01,
        "{tag}: panel {panel:?} outside the side margins"
    );
    if scene == Scene::Above {
        assert!(
            panel.bottom() <= scene.anchor().y,
            "{tag}: not flipped above"
        );
    }
    if scene == Scene::Confirm {
        return;
    }
    let groups: Vec<usize> = match scene {
        Scene::Submenu => vec![1, 4],
        _ => vec![1, 4, 1],
    };
    let mut labelled = 0;
    // (index -> expected label colour is irrelevant: we measure the spread.)
    let rows: usize = groups.iter().sum();
    for i in 0..rows {
        // The submenu page starts with the back row, which shifts everything.
        let y = row_top(panel.y, &groups, i);
        let rect = px_rect(
            panel.x + LABEL_X,
            y + (44.0 - LABEL_H) / 2.0,
            LABEL_W,
            LABEL_H,
        );
        let c = color::contrast_in(img, rect);
        assert!(
            c >= LABEL_MIN_CONTRAST,
            "{tag}: row {i} label contrast {c:.2}"
        );
        labelled += 1;
    }
    assert!(labelled > 0);
    // A label-free patch: the right end of the second row of the middle
    // group ("Mark as read", no chevron), well past its text. List titles and
    // times sit behind it.
    if scene != Scene::Submenu {
        let y = row_top(panel.y, &groups, 2);
        let patch = px_rect(
            panel.right() - 16.0 - PATCH_W,
            y + (44.0 - PATCH_H) / 2.0,
            PATCH_W,
            PATCH_H,
        );
        let c = color::contrast_in(img, patch);
        assert!(c < BODY_MAX_CONTRAST, "{tag}: list reads through ({c:.2})");
    }
    // The 8 dp band between the groups is flat too.
    let band_y = row_top(panel.y, &groups, 1) - geom::MENU_GROUP_GAP;
    let band = px_rect(
        panel.x + 8.0,
        band_y + 2.0,
        panel.w - 16.0,
        geom::MENU_GROUP_GAP - 4.0,
    );
    let c = color::contrast_in(img, band);
    assert!(c < BODY_MAX_CONTRAST, "{tag}: group band not flat ({c:.2})");
}

fn shoot(scene: Scene, nn: u32) {
    for theme in [Theme::Dark, Theme::Light] {
        let spec = ShotSpec {
            app: "rovsti_ui",
            nn,
            screen: scene.screen(),
            theme,
            size: PHONE,
        };
        let mut rig = Rig::open(spec, move |_, cx| {
            cx.new(|_| MenuGallery {
                scene,
                menu: open_menu(scene),
            })
        });
        rig.theme();
        rig.settle();
        let img = rig.capture();
        let panel = rig
            .root
            .read_with(&rig.cx, |g, _| g.menu.panel_rect())
            .expect("the menu was laid out");
        rig.root.read_with(&rig.cx, |g, _| {
            let expected = match scene {
                Scene::Submenu => MenuPage::Submenu("mute"),
                Scene::Confirm => MenuPage::Confirm("delete"),
                _ => MenuPage::Root,
            };
            assert_eq!(g.menu.page(), expected);
        });
        println!("{}", rig.save_image(&img).display());
        assert_menu(&img, scene, theme, panel);
    }
}

/// Output: `target/shots/rovsti_ui/04-menus-{dark,light}.png`, plus the
/// `-above`, `-submenu`, `-confirm` and `-buttons` variants.
#[test]
#[ignore = "needs a GPU adapter (lavapipe works) and system fonts"]
fn menus() {
    isolate_data_dir("rovsti_ui");
    for scene in [Scene::Below, Scene::Above, Scene::Submenu, Scene::Confirm] {
        shoot(scene, 4);
    }
    buttons();
}

// ---- Button matrix ----------------------------------------------------------

struct ButtonGallery;

fn caption(theme: Theme, text: &'static str) -> gpui::Div {
    let colors = theme::colors(theme);
    div()
        .w(px(64.0))
        .flex_none()
        .text_size(typography::FOOTNOTE.size)
        .text_color(colors.secondary_label)
        .child(text)
}

impl Render for ButtonGallery {
    fn render(&mut self, window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        let theme = crate::current_theme(window);
        let styles = [
            ("Filled", ButtonStyle::Filled),
            ("Tinted", ButtonStyle::Tinted),
            ("Gray", ButtonStyle::Gray),
            ("Plain", ButtonStyle::Plain),
        ];
        let sizes = [ButtonSize::Large, ButtonSize::Regular, ButtonSize::Small];
        let line = || div().w_full().flex().items_center().gap(px(8.0));
        let mut col = div()
            .size_full()
            .pt(px(SIM_SAFE_TOP + 16.0))
            .px(px(MARGIN))
            .flex()
            .flex_col()
            .gap(px(8.0));
        let mut n: usize = 0;
        for (name, style) in styles {
            let mut row = line().child(caption(theme, name));
            for size in sizes {
                n += 1;
                row = row.child(
                    Button::new(("b", n), "Save")
                        .style(style)
                        .size(size)
                        .build(theme),
                );
            }
            col = col.child(row);
        }
        // Icons.
        let mut row = line().child(caption(theme, "Icon"));
        for size in sizes {
            n += 1;
            row = row.child(
                Button::new(("b", n), "Add")
                    .icon(gpui_icons::GpuiIcons::LucidePlus)
                    .style(ButtonStyle::Tinted)
                    .size(size)
                    .build(theme),
            );
        }
        col = col.child(row);
        // Destructive, one per style.
        let mut row = line().child(caption(theme, "Danger"));
        for (_, style) in &styles[..3] {
            n += 1;
            row = row.child(
                Button::new(("b", n), "Delete")
                    .style(*style)
                    .destructive()
                    .build(theme),
            );
        }
        col = col.child(row);
        // Disabled.
        let mut row = line().child(caption(theme, "Disabled"));
        for (_, style) in &styles[..3] {
            n += 1;
            row = row.child(
                Button::new(("b", n), "Send")
                    .style(*style)
                    .disabled()
                    .build(theme),
            );
        }
        col = col.child(row);
        // Equal-width row and a full-width Large.
        n += 1;
        let pair = line()
            .child(
                Button::new(("b", n), "Cancel")
                    .style(ButtonStyle::Gray)
                    .full_width()
                    .build(theme),
            )
            .child(
                Button::new(("b", n + 1), "Delete")
                    .destructive()
                    .full_width()
                    .build(theme),
            );
        col = col.child(div().h(px(16.0))).child(pair).child(
            Button::new(("b", n + 2), "Continue")
                .size(ButtonSize::Large)
                .full_width()
                .build(theme),
        );
        div()
            .size_full()
            .relative()
            .child(components::backdrop(theme, theme::accent_glow(theme)))
            .child(col)
    }
}

fn buttons() {
    for theme in [Theme::Dark, Theme::Light] {
        let spec = ShotSpec {
            app: "rovsti_ui",
            nn: 4,
            screen: "menus-buttons",
            theme,
            size: PHONE,
        };
        let mut rig = Rig::open(spec, |_, cx| cx.new(|_| ButtonGallery));
        rig.theme();
        rig.settle();
        println!("{}", rig.save().display());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn row_tops_step_44_and_add_the_group_band() {
        let groups = [1, 4, 1];
        assert_eq!(row_top(100.0, &groups, 0), 106.0);
        assert_eq!(row_top(100.0, &groups, 1), 106.0 + 44.0 + 8.0);
        assert_eq!(
            row_top(100.0, &groups, 2) - row_top(100.0, &groups, 1),
            44.0
        );
        // The last row ends 6 dp above the panel bottom.
        let h = geom::menu_height(&groups);
        assert_eq!(row_top(100.0, &groups, 5) + 44.0 + 6.0, 100.0 + h);
    }

    #[test]
    fn the_above_anchor_cannot_fit_a_menu_below() {
        let a = Scene::Above.anchor();
        let h = geom::menu_height(&[1, 4, 1]);
        let bottom = PHONE.height - geom::content_bottom(SIM_SAFE_BOTTOM, 0.0);
        assert!(a.bottom() + 8.0 + h > bottom);
        let b = Scene::Below.anchor();
        assert!(b.bottom() + 8.0 + h <= bottom);
    }
}
