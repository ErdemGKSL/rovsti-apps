//! Anchored popover menus (layout spec 2.15).
//!
//! A tap never inserts controls into the page: a short action list about one
//! element opens as a [`Menu`] next to it. `model` is pure data (no gpui);
//! this file is the gpui side.
//!
//! * [`AnchorSlot`] remembers where the source element was painted (or the
//!   long-press point), so the menu can hang off it.
//! * [`MenuState`] is the owner's animation and navigation state: an open
//!   spring, a page stack (`Root`, `Submenu`, `Confirm`) and a 24 dp slide
//!   between pages. Reduced motion gives a 120 ms fade instead.
//! * [`Menu::render`] draws a full-window catcher (a tap outside dismisses)
//!   and the thick-glass panel, placed by `layout::geom::place_popover`.
//!
//! The panel scales 0.92 to 1.0 about its anchor-side corner while it fades
//! in. gpui has no transform, so the scale is done by growing the clip: the
//! content keeps its size and is revealed from the corner.
//!
//! Owner pattern (like `Sheet`):
//!
//! ```ignore
//! // fields: anchor: AnchorSlot, menu: MenuState
//! // render: put `self.anchor.track()` inside the source element, and last in
//! // a `relative()` full-window root:
//! Menu::new("Conversation", model)
//!     .submenu("mute", mute_model)
//!     .confirm("delete", "Delete this conversation?", "Delete")
//!     .render(
//!         &self.menu, theme, window,
//!         cx.listener(|this, key: &'static str, _, cx| { /* act on key */ this.menu.close(); cx.notify() }),
//!         cx.listener(|this, nav, _, cx| { this.menu.apply(nav); cx.notify() }),
//!     )
//! // open: if let Some(b) = self.anchor.get() { self.menu.open_at(b) }
//! // each frame: Motion::frame(.., |dt| self.menu.tick(dt)) and
//! // window.set_back_enabled(self.menu.is_open());
//! // Window::on_system_back: self.menu.back_or_close()
//! ```

pub mod model;

use std::{cell::Cell, rc::Rc, time::Duration};

use gpui::{
    AnyElement, App, Bounds, Div, InteractiveElement, IntoElement, MouseButton, ParentElement,
    Pixels, Point, Role, SharedString, StatefulInteractiveElement, Styled, Window, canvas,
    deferred, div, px, svg,
};
use liquid_glass_kit::{
    glass::{GlassSpec, glass},
    motion_tokens,
    spring::Spring,
    tokens::{Colors, Theme, radius, shadow, typography},
};

use crate::{
    layout::{self, geom},
    moments::Fade,
    row::readable_on,
};
use model::{MenuItem, MenuModel, MenuRole};

/// Distance the page content slides when pushing or popping a page.
const SLIDE_DP: f32 = 24.0;
/// Panel scale when closed; it grows to 1.0.
const SCALE_FROM: f32 = 0.92;
/// Gap between the anchor and the panel.
const ANCHOR_GAP: f32 = 8.0;
/// Distance of the panel from the screen top below the safe area.
const TOP_GAP: f32 = 8.0;
/// Icon column: 20 dp at x 16, label at x 48.
const ICON: f32 = 20.0;
const PAD_X: f32 = 16.0;
const LABEL_X: f32 = PAD_X + ICON + 12.0;
/// Rough average glyph width of the Body style, for sizing the panel.
const GLYPH_DP: f32 = 8.6;
/// Footnote glyph width, for wrapping the confirm prompt.
const FOOTNOTE_GLYPH_DP: f32 = 6.6;

// ---- Anchor -----------------------------------------------------------------

/// Where the source element was painted. Cheap to clone; clones share one
/// cell. Put [`AnchorSlot::track`] inside the source element.
#[derive(Clone, Default)]
pub struct AnchorSlot(Rc<Cell<Option<Bounds<Pixels>>>>);

impl AnchorSlot {
    pub fn new() -> Self {
        Self::default()
    }

    /// A slot holding a zero-size anchor at `position` (a
    /// `LongPressEvent::position`).
    pub fn point(position: Point<Pixels>) -> Self {
        let slot = Self::new();
        slot.set(Some(Bounds::new(position, gpui::size(px(0.0), px(0.0)))));
        slot
    }

    pub fn get(&self) -> Option<Bounds<Pixels>> {
        self.0.get()
    }

    pub fn set(&self, bounds: Option<Bounds<Pixels>>) {
        self.0.set(bounds);
    }

    /// An invisible child that fills its (relative) parent and records the
    /// parent's bounds every frame, in prepaint, as `field.rs` stores its
    /// content box.
    pub fn track(&self) -> impl IntoElement {
        let slot = self.clone();
        canvas(move |bounds, _, _| slot.set(Some(bounds)), |_, (), _, _| ())
            .absolute()
            .size_full()
    }
}

// ---- State ------------------------------------------------------------------

/// What the panel shows.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MenuPage {
    Root,
    /// The nested list of the item with this key.
    Submenu(&'static str),
    /// The confirmation of the item with this key.
    Confirm(&'static str),
}

/// A navigation request from the panel; feed it to [`MenuState::apply`].
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MenuNav {
    Dismiss,
    Back,
    Submenu(&'static str),
    Confirm(&'static str),
}

/// Animation and navigation state of one menu. The owner keeps it in its
/// view and ticks it once per frame.
pub struct MenuState {
    /// 0 = hidden, 1 = open (overshoots a little).
    open: Spring,
    /// 0 to 1 while a page change slides in.
    slide: Spring,
    /// +1 when the page was pushed (content comes from the right), -1 popped.
    slide_dir: f32,
    page: MenuPage,
    /// Pages below the current one.
    stack: Vec<MenuPage>,
    wants_open: bool,
    anchor: Option<Bounds<Pixels>>,
    reduced: bool,
    fade: Fade,
}

impl Default for MenuState {
    fn default() -> Self {
        Self::new()
    }
}

impl MenuState {
    pub fn new() -> Self {
        Self {
            open: Spring::new(0.0, motion_tokens::morph()).with_epsilon(0.001),
            slide: Spring::new(1.0, motion_tokens::appear()).with_epsilon(0.002),
            slide_dir: 1.0,
            page: MenuPage::Root,
            stack: Vec::new(),
            wants_open: false,
            anchor: None,
            reduced: false,
            fade: Fade::default(),
        }
    }

    /// The system asked for reduced motion (read each frame by the owner).
    pub fn set_reduced(&mut self, reduced: bool) {
        self.reduced = reduced;
    }

    /// Opens on the root page, hanging off `anchor` (window coordinates).
    pub fn open_at(&mut self, anchor: Bounds<Pixels>) {
        self.anchor = Some(anchor);
        self.page = MenuPage::Root;
        self.stack.clear();
        self.slide.snap(1.0);
        self.wants_open = true;
        if self.reduced {
            self.fade.restart();
        }
        self.open.retarget(1.0, motion_tokens::morph());
    }

    /// Opens at the slot's anchor; `false` when nothing was painted yet.
    pub fn open_from(&mut self, slot: &AnchorSlot) -> bool {
        slot.get().map(|b| self.open_at(b)).is_some()
    }

    pub fn close(&mut self) {
        self.wants_open = false;
        if self.reduced {
            self.open.snap(0.0);
        } else {
            self.open.retarget(0.0, motion_tokens::morph());
        }
    }

    pub fn push_submenu(&mut self, key: &'static str) {
        self.push(MenuPage::Submenu(key));
    }

    pub fn confirm(&mut self, key: &'static str) {
        self.push(MenuPage::Confirm(key));
    }

    fn push(&mut self, page: MenuPage) {
        self.stack.push(self.page);
        self.page = page;
        self.restart_slide(1.0);
    }

    /// Pops one page. `false` (and nothing changes) on the root page.
    pub fn back(&mut self) -> bool {
        match self.stack.pop() {
            Some(page) => {
                self.page = page;
                self.restart_slide(-1.0);
                true
            }
            None => false,
        }
    }

    /// System Back: pops a page, then closes.
    pub fn back_or_close(&mut self) {
        if !self.back() {
            self.close();
        }
    }

    /// Runs a request coming out of [`Menu::render`].
    pub fn apply(&mut self, nav: MenuNav) {
        match nav {
            MenuNav::Dismiss => self.close(),
            MenuNav::Back => {
                self.back();
            }
            MenuNav::Submenu(key) => self.push_submenu(key),
            MenuNav::Confirm(key) => self.confirm(key),
        }
    }

    fn restart_slide(&mut self, dir: f32) {
        self.slide_dir = dir;
        if self.reduced {
            self.slide.snap(1.0);
        } else {
            self.slide.snap(0.0);
            self.slide.retarget(1.0, motion_tokens::appear());
        }
    }

    pub fn page(&self) -> MenuPage {
        self.page
    }

    /// Whether the menu is (or is animating towards) open. Drives Back.
    pub fn is_open(&self) -> bool {
        self.wants_open
    }

    /// Whether anything is on screen: open, or still fading away.
    pub fn is_visible(&self) -> bool {
        self.wants_open || self.open.value > 0.002 || !self.open.is_settled()
    }

    /// 0..1 open progress.
    pub fn progress(&self) -> f32 {
        self.open.value.clamp(0.0, 1.0)
    }

    /// Advances the springs; `true` while anything moves.
    pub fn tick(&mut self, dt: Duration) -> bool {
        let a = self.open.tick(dt);
        let b = self.slide.tick(dt);
        a | b
    }

    /// Advances the reduced-motion fade by real time; `true` while it plays.
    pub fn tick_real(&mut self, real: Duration) -> bool {
        self.fade.tick(real)
    }

    /// Panel opacity and scale for the current frame.
    fn look(&self) -> (f32, f32) {
        if self.reduced {
            let o = if self.wants_open {
                self.fade.value()
            } else {
                0.0
            };
            return (o, 1.0);
        }
        let p = self.open.value;
        (
            p.clamp(0.0, 1.0),
            (SCALE_FROM + (1.0 - SCALE_FROM) * p).min(1.03),
        )
    }
}

// ---- Pure sizing ------------------------------------------------------------

/// Height of a confirm page: 16 pad, prompt, 12, two 44 buttons with an 8 gap,
/// 16 pad.
pub fn confirm_height(prompt: &str, width: f32) -> f32 {
    let per_line = ((width - 2.0 * PAD_X) / FOOTNOTE_GLYPH_DP).floor().max(1.0);
    let lines = (prompt.chars().count() as f32 / per_line).ceil().max(1.0);
    PAD_X + lines * typography::FOOTNOTE.line.as_f32() + 12.0 + 2.0 * geom::MENU_ROW_H + 8.0 + PAD_X
}

/// Rough label width in dp.
fn label_dp(label: &str) -> f32 {
    label.chars().count() as f32 * GLYPH_DP
}

// ---- Menu -------------------------------------------------------------------

struct ConfirmSpec {
    key: &'static str,
    prompt: SharedString,
    action: SharedString,
}

/// The popover. Build it each frame from the model, then [`Menu::render`].
pub struct Menu {
    label: &'static str,
    model: MenuModel,
    submenus: Vec<(&'static str, MenuModel)>,
    confirms: Vec<ConfirmSpec>,
}

impl Menu {
    /// `label` is announced as the menu's name.
    pub fn new(label: &'static str, model: MenuModel) -> Self {
        Self {
            label,
            model,
            submenus: Vec::new(),
            confirms: Vec::new(),
        }
    }

    /// The list shown when the item `key` (built with `.submenu()`) is tapped.
    pub fn submenu(mut self, key: &'static str, model: MenuModel) -> Self {
        self.submenus.push((key, model));
        self
    }

    /// Tapping the item `key` asks first: a prompt, a red `action` button and
    /// Cancel. The action then reports `key` through `on_select`.
    pub fn confirm(
        mut self,
        key: &'static str,
        prompt: impl Into<SharedString>,
        action: impl Into<SharedString>,
    ) -> Self {
        self.confirms.push(ConfirmSpec {
            key,
            prompt: prompt.into(),
            action: action.into(),
        });
        self
    }

    fn submenu_of(&self, key: &str) -> Option<&MenuModel> {
        self.submenus
            .iter()
            .find(|(k, _)| *k == key)
            .map(|(_, m)| m)
    }

    fn confirm_of(&self, key: &str) -> Option<&ConfirmSpec> {
        self.confirms.iter().find(|c| c.key == key)
    }

    /// The label of the item `key` in the root or any submenu.
    fn item(&self, key: &str) -> Option<&MenuItem> {
        self.model
            .find(key)
            .or_else(|| self.submenus.iter().find_map(|(_, m)| m.find(key)))
    }

    /// Width shared by every page, so the panel never changes width.
    fn width(&self, viewport_w: f32) -> f32 {
        let longest = self
            .model
            .labels()
            .chain(self.submenus.iter().flat_map(|(_, m)| m.labels()))
            .map(label_dp)
            .fold(0.0, f32::max);
        geom::popover_width(longest, viewport_w)
    }

    fn page_height(&self, page: MenuPage, width: f32) -> f32 {
        match page {
            MenuPage::Root => geom::menu_height(&self.model.group_sizes()),
            MenuPage::Submenu(key) => {
                let n = self.submenu_of(key).map_or(0, MenuModel::item_count);
                geom::menu_height(&[1, n])
            }
            MenuPage::Confirm(key) => self
                .confirm_of(key)
                .map_or(0.0, |c| confirm_height(&c.prompt, width)),
        }
    }

    /// The overlay, or `None` when nothing is visible. Place it as the last
    /// child of a `relative()` root that fills the window.
    ///
    /// * `on_select` gets the key of a leaf item, or of a confirmed action.
    ///   The owner usually closes the menu there.
    /// * `on_nav` gets taps outside, Back rows and page pushes; forward them to
    ///   [`MenuState::apply`].
    pub fn render(
        self,
        state: &MenuState,
        theme: Theme,
        window: &Window,
        on_select: impl Fn(&'static str, &mut Window, &mut App) + 'static,
        on_nav: impl Fn(MenuNav, &mut Window, &mut App) + 'static,
    ) -> Option<AnyElement> {
        if !state.is_visible() {
            return None;
        }
        let anchor = state.anchor?;
        let colors = crate::theme::colors(theme);
        let viewport = window.viewport_size();
        let (vw, vh) = (f32::from(viewport.width), f32::from(viewport.height));
        let safe = layout::safe_area(window);

        let width = self.width(vw);
        let root_h = self.page_height(MenuPage::Root, width);
        let page_h = self.page_height(state.page, width);
        let geo_anchor = geom::Rect::new(
            f32::from(anchor.origin.x),
            f32::from(anchor.origin.y),
            f32::from(anchor.size.width),
            f32::from(anchor.size.height),
        );
        let top = safe.top + TOP_GAP;
        let bottom = vh - f32::from(layout::content_bottom_inset(window));
        let place = |h: f32| {
            geom::place_popover(
                geo_anchor,
                geom::Size::new(width, h),
                geom::Size::new(vw, vh),
                top,
                bottom,
                layout::MARGIN,
                ANCHOR_GAP,
            )
        };
        // The side and the anchor-side edge come from the root page, so a
        // taller or shorter page never makes the panel jump.
        let base = place(root_h);
        let h = page_h.min(base.max_h);
        let rect = geom::Rect::new(
            base.rect.x,
            if base.below {
                base.rect.y
            } else {
                base.rect.bottom() - h
            },
            base.rect.w,
            h,
        );
        let origin_right = geo_anchor.x + geo_anchor.w / 2.0 >= vw / 2.0;

        let (opacity, scale) = state.look();
        let (sw, sh) = (rect.w * scale, rect.h * scale);
        let left = if origin_right {
            rect.right() - sw
        } else {
            rect.x
        };
        let top_px = if base.below {
            rect.y
        } else {
            rect.bottom() - sh
        };

        let on_select = Rc::new(on_select);
        let on_nav = Rc::new(on_nav);
        let dx = (1.0 - state.slide.value.clamp(0.0, 1.0)) * SLIDE_DP * state.slide_dir;
        let fade_in = if state.reduced {
            1.0
        } else {
            state.slide.value.clamp(0.0, 1.0)
        };

        let body = match state.page {
            MenuPage::Root => self.list(&self.model, None, &colors, theme, &on_select, &on_nav),
            MenuPage::Submenu(key) => {
                let title = self.item(key).map_or("", |i| i.label.as_str());
                match self.submenu_of(key) {
                    Some(m) => self.list(m, Some(title), &colors, theme, &on_select, &on_nav),
                    None => div(),
                }
            }
            MenuPage::Confirm(key) => match self.confirm_of(key) {
                Some(c) => confirm_page(c, &colors, &on_select, &on_nav),
                None => div(),
            },
        };

        // Fixed-size content pinned to the anchor-side corner; the outer
        // panel clips it while it grows.
        let mut content = div()
            .id("menu-scroll")
            .absolute()
            .w(px(rect.w))
            .h(px(rect.h))
            .overflow_y_scroll()
            .child(body.relative().left(px(dx)).opacity(fade_in));
        content = if origin_right {
            content.right(px(0.0))
        } else {
            content.left(px(0.0))
        };
        content = if base.below {
            content.top(px(0.0))
        } else {
            content.bottom(px(0.0))
        };

        let nav_out = on_nav.clone();
        let catcher = div()
            .id("menu-catcher")
            .absolute()
            .inset_0()
            .occlude()
            .on_mouse_down(MouseButton::Left, move |_, window, cx| {
                nav_out(MenuNav::Dismiss, window, cx)
            });

        let panel = glass(GlassSpec::thick(theme))
            .id("menu-panel")
            .role(Role::Menu)
            .aria_label(self.label)
            .debug_selector(|| "menu-panel".into())
            .occlude()
            .absolute()
            .left(px(left))
            .top(px(top_px))
            .w(px(sw))
            .h(px(sh))
            .rounded(radius::MENU)
            .overflow_hidden()
            .opacity(opacity)
            .shadow(shadow::glass(theme.is_dark()))
            .child(content);

        Some(
            deferred(div().absolute().inset_0().child(catcher).child(panel))
                .with_priority(20)
                .into_any_element(),
        )
    }

    /// One list page. `back_title` adds the Back row (a submenu page).
    fn list(
        &self,
        model: &MenuModel,
        back_title: Option<&str>,
        colors: &Colors,
        theme: Theme,
        on_select: &Rc<impl Fn(&'static str, &mut Window, &mut App) + 'static>,
        on_nav: &Rc<impl Fn(MenuNav, &mut Window, &mut App) + 'static>,
    ) -> Div {
        let band = || {
            let mut c = colors.separator;
            c.a *= 0.5;
            div().h(px(geom::MENU_GROUP_GAP)).w_full().bg(c)
        };
        let mut col = div()
            .w_full()
            .flex()
            .flex_col()
            .py(px(geom::MENU_PAD_V / 2.0));
        if let Some(title) = back_title {
            let nav = on_nav.clone();
            col = col.child(
                row_shell("menu-back", 0, colors)
                    .debug_selector(|| "menu-back".into())
                    .role(Role::Button)
                    .child(icon_slot("icons/lucide/chevron-left.svg", colors.accent))
                    .child(
                        div()
                            .flex_1()
                            .truncate()
                            .text_size(typography::BODY.size)
                            .font_weight(gpui::FontWeight::SEMIBOLD)
                            .text_color(colors.label)
                            .child(title.to_string()),
                    )
                    .on_click(move |_, window, cx| nav(MenuNav::Back, window, cx)),
            );
            col = col.child(band());
        }
        let mut index = 0usize;
        for (g, group) in model.groups().iter().enumerate() {
            if g > 0 {
                col = col.child(band());
            }
            for (i, item) in group.0.iter().enumerate() {
                if i > 0 {
                    col = col.child(div().h(px(0.5)).ml(px(LABEL_X)).bg(colors.separator));
                }
                col = col.child(self.item_row(item, index, colors, theme, on_select, on_nav));
                index += 1;
            }
        }
        col
    }

    fn item_row(
        &self,
        item: &MenuItem,
        index: usize,
        colors: &Colors,
        _theme: Theme,
        on_select: &Rc<impl Fn(&'static str, &mut Window, &mut App) + 'static>,
        on_nav: &Rc<impl Fn(MenuNav, &mut Window, &mut App) + 'static>,
    ) -> impl IntoElement {
        let key = item.key;
        let ink = match (item.enabled, item.role) {
            (false, _) => colors.tertiary_label,
            (true, MenuRole::Destructive) => colors.red,
            (true, MenuRole::Normal) => colors.label,
        };
        let mut row = row_shell("menu-item", index, colors)
            .debug_selector(move || format!("menu-item-{key}"))
            .role(Role::MenuItem)
            .child(match item.icon_name {
                Some(name) => icon_slot(format!("icons/lucide/{name}.svg"), ink),
                None => div().flex_none().size(px(ICON)),
            })
            .child(
                div()
                    .flex_1()
                    .truncate()
                    .text_size(typography::BODY.size)
                    .text_color(ink)
                    .child(item.label.clone()),
            );
        if item.checked {
            row = row.child(icon_slot("icons/lucide/check.svg", colors.accent));
        }
        if item.submenu {
            row = row.child(icon_slot(
                "icons/lucide/chevron-right.svg",
                colors.tertiary_label,
            ));
        }
        if !item.enabled {
            return row;
        }
        let nav = if item.submenu && self.submenu_of(key).is_some() {
            Some(MenuNav::Submenu(key))
        } else if self.confirm_of(key).is_some() {
            Some(MenuNav::Confirm(key))
        } else {
            None
        };
        match nav {
            Some(nav) => {
                let on_nav = on_nav.clone();
                row.on_click(move |_, window, cx| on_nav(nav, window, cx))
            }
            None => {
                let on_select = on_select.clone();
                row.on_click(move |_, window, cx| on_select(key, window, cx))
            }
        }
    }
}

/// A 44 dp tappable row with the pressed wash.
fn row_shell(id: &'static str, index: usize, colors: &Colors) -> gpui::Stateful<Div> {
    div()
        .id((id, index))
        .w_full()
        .h(px(geom::MENU_ROW_H))
        .flex_none()
        .px(px(PAD_X))
        .flex()
        .items_center()
        .gap(px(12.0))
        .active(|s| s.bg(colors_press(colors)))
}

fn colors_press(colors: &Colors) -> gpui::Hsla {
    colors.fill
}

fn icon_slot(path: impl Into<SharedString>, color: gpui::Hsla) -> Div {
    div().flex_none().size(px(ICON)).child(
        svg()
            .path(path)
            .size(px(ICON))
            .flex_none()
            .text_color(color),
    )
}

/// The confirm page: a Footnote prompt, a red Regular button and a Gray Cancel.
fn confirm_page(
    spec: &ConfirmSpec,
    colors: &Colors,
    on_select: &Rc<impl Fn(&'static str, &mut Window, &mut App) + 'static>,
    on_nav: &Rc<impl Fn(MenuNav, &mut Window, &mut App) + 'static>,
) -> Div {
    let key = spec.key;
    let select = on_select.clone();
    let nav = on_nav.clone();
    let button = |id: &'static str, fill: gpui::Hsla| {
        div()
            .id(id)
            .debug_selector(move || id.to_string())
            .role(Role::Button)
            .w_full()
            .h(px(geom::MENU_ROW_H))
            .flex_none()
            .rounded(radius::BUTTON)
            .bg(fill)
            .flex()
            .items_center()
            .justify_center()
            .text_size(typography::BODY.size)
            .font_weight(gpui::FontWeight::SEMIBOLD)
    };
    div()
        .w_full()
        .p(px(PAD_X))
        .flex()
        .flex_col()
        .gap(px(8.0))
        .child(
            div()
                .pb(px(4.0))
                .text_size(typography::FOOTNOTE.size)
                .line_height(typography::FOOTNOTE.line)
                .text_color(colors.secondary_label)
                .child(spec.prompt.clone()),
        )
        .child(
            button("menu-confirm", colors.red)
                .text_color(readable_on(colors.red))
                .child(spec.action.clone())
                .on_click(move |_, window, cx| select(key, window, cx)),
        )
        .child(
            button("menu-cancel", colors.secondary_background)
                .text_color(colors.label)
                .child("Cancel")
                .on_click(move |_, window, cx| nav(MenuNav::Back, window, cx)),
        )
}

#[cfg(test)]
mod tests {
    use super::*;
    use gpui::{Context, Entity, Modifiers, Render, TestAppContext, VisualTestContext, point};
    use model::{MenuGroup, MenuItem};

    fn model() -> MenuModel {
        MenuModel::new(vec![
            MenuGroup::new(vec![MenuItem::new("pin", "Pin").icon("pin")]),
            MenuGroup::new(vec![
                MenuItem::new("mute", "Mute").submenu(),
                MenuItem::new("delete", "Delete").destructive(),
            ]),
        ])
    }

    struct Host {
        anchor: AnchorSlot,
        menu: MenuState,
        selected: Vec<&'static str>,
    }

    impl Render for Host {
        fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
            let this = cx.entity().downgrade();
            let (a, b) = (this.clone(), this);
            let menu = Menu::new("Test", model())
                .submenu(
                    "mute",
                    MenuModel::new(vec![MenuGroup::new(vec![MenuItem::new(
                        "mute_1h", "1 hour",
                    )])]),
                )
                .confirm("delete", "Delete it?", "Delete")
                .render(
                    &self.menu,
                    Theme::Dark,
                    window,
                    move |key, _, cx| {
                        a.update(cx, |h, cx| {
                            h.selected.push(key);
                            h.menu.close();
                            cx.notify();
                        })
                        .ok();
                    },
                    move |nav, _, cx| {
                        b.update(cx, |h, cx| {
                            h.menu.apply(nav);
                            cx.notify();
                        })
                        .ok();
                    },
                );
            div()
                .relative()
                .size_full()
                .child(
                    div()
                        .absolute()
                        .left(px(40.0))
                        .top(px(100.0))
                        .size(px(40.0))
                        .child(self.anchor.track()),
                )
                .children(menu)
        }
    }

    fn open_host(cx: &mut TestAppContext) -> (Entity<Host>, &mut VisualTestContext) {
        let (host, cx) = cx.add_window_view(|_, _| Host {
            anchor: AnchorSlot::new(),
            menu: MenuState::new(),
            selected: Vec::new(),
        });
        cx.run_until_parked();
        cx.update(|window, _| window.refresh());
        cx.run_until_parked();
        host.update(cx, |h, cx| {
            assert!(h.menu.open_from(&h.anchor), "anchor was painted");
            for _ in 0..240 {
                h.menu.tick(Duration::from_millis(8));
            }
            cx.notify();
        });
        cx.run_until_parked();
        (host, cx)
    }

    fn center(cx: &mut VisualTestContext, selector: &'static str) -> Point<Pixels> {
        cx.debug_bounds(selector)
            .unwrap_or_else(|| panic!("{selector} not drawn"))
            .center()
    }

    #[gpui::test]
    fn outside_tap_closes(cx: &mut TestAppContext) {
        let (host, cx) = open_host(cx);
        assert!(host.read_with(cx, |h, _| h.menu.is_open()));
        cx.simulate_click(point(px(300.0), px(500.0)), Modifiers::none());
        assert!(!host.read_with(cx, |h, _| h.menu.is_open()));
        assert!(host.read_with(cx, |h, _| h.selected.is_empty()));
    }

    #[gpui::test]
    fn selecting_an_item_reports_its_key_once(cx: &mut TestAppContext) {
        let (host, cx) = open_host(cx);
        let at = center(cx, "menu-item-pin");
        cx.simulate_click(at, Modifiers::none());
        assert_eq!(host.read_with(cx, |h, _| h.selected.clone()), vec!["pin"]);
        assert!(!host.read_with(cx, |h, _| h.menu.is_open()));
    }

    #[gpui::test]
    fn back_from_a_submenu_returns_to_the_root(cx: &mut TestAppContext) {
        let (host, cx) = open_host(cx);
        let at = center(cx, "menu-item-mute");
        cx.simulate_click(at, Modifiers::none());
        assert_eq!(
            host.read_with(cx, |h, _| h.menu.page()),
            MenuPage::Submenu("mute")
        );
        host.update(cx, |h, cx| {
            for _ in 0..120 {
                h.menu.tick(Duration::from_millis(8));
            }
            cx.notify();
        });
        cx.run_until_parked();
        let at = center(cx, "menu-back");
        cx.simulate_click(at, Modifiers::none());
        assert_eq!(host.read_with(cx, |h, _| h.menu.page()), MenuPage::Root);
        assert!(host.read_with(cx, |h, _| h.menu.is_open()));
    }

    #[gpui::test]
    fn destructive_item_confirms_before_selecting(cx: &mut TestAppContext) {
        let (host, cx) = open_host(cx);
        let at = center(cx, "menu-item-delete");
        cx.simulate_click(at, Modifiers::none());
        assert_eq!(
            host.read_with(cx, |h, _| h.menu.page()),
            MenuPage::Confirm("delete")
        );
        assert!(host.read_with(cx, |h, _| h.selected.is_empty()));
        host.update(cx, |h, cx| {
            for _ in 0..120 {
                h.menu.tick(Duration::from_millis(8));
            }
            cx.notify();
        });
        cx.run_until_parked();
        let at = center(cx, "menu-confirm");
        cx.simulate_click(at, Modifiers::none());
        assert_eq!(
            host.read_with(cx, |h, _| h.selected.clone()),
            vec!["delete"]
        );
    }

    #[test]
    fn state_pages_and_system_back() {
        let mut s = MenuState::new();
        let anchor = Bounds::new(point(px(10.0), px(10.0)), gpui::size(px(0.0), px(0.0)));
        s.open_at(anchor);
        assert!(s.is_open() && !s.back());
        s.push_submenu("mute");
        s.confirm("x");
        assert_eq!(s.page(), MenuPage::Confirm("x"));
        s.back_or_close();
        assert_eq!(s.page(), MenuPage::Submenu("mute"));
        s.back_or_close();
        assert_eq!(s.page(), MenuPage::Root);
        assert!(s.is_open());
        s.back_or_close();
        assert!(!s.is_open());
        for _ in 0..240 {
            s.tick(Duration::from_millis(8));
        }
        assert!(!s.is_visible());
    }

    #[test]
    fn reduced_motion_fades_without_scale() {
        let mut s = MenuState::new();
        s.set_reduced(true);
        s.open_at(Bounds::new(
            point(px(0.0), px(0.0)),
            gpui::size(px(1.0), px(1.0)),
        ));
        let (o, scale) = s.look();
        assert_eq!((o, scale), (0.0, 1.0));
        s.tick_real(Duration::from_millis(60));
        let (mid, _) = s.look();
        assert!(mid > 0.0 && mid < 1.0);
        s.tick_real(Duration::from_millis(80));
        assert_eq!(s.look().0, 1.0);
        s.close();
        assert!(!s.is_visible());
    }

    #[test]
    fn anchor_point_is_zero_size() {
        let slot = AnchorSlot::point(point(px(12.0), px(34.0)));
        let b = slot.get().expect("point sets the anchor");
        assert_eq!(
            (f32::from(b.size.width), f32::from(b.origin.y)),
            (0.0, 34.0)
        );
    }

    #[test]
    fn confirm_height_grows_with_the_prompt() {
        assert!(confirm_height(&"x".repeat(120), 240.0) > confirm_height("Delete?", 240.0));
    }
}
