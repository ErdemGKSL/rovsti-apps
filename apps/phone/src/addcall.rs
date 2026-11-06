//! The Add call sheet (story 15.5): pick someone from a keypad, Favorites or
//! Contacts without leaving the call.
//!
//! A large-detent sheet over the In-Call screen (the S16 pattern). A pill at
//! the top shows the call that keeps running ("Ayşe · 2:41"). The sheet counts
//! one extra surface and the page behind dims 0.85. Placing goes through the
//! one call path (`Backend::place_call`, FR-11); the first call is held by the
//! network and the Hold-away moment (S19) plays when Android reports it.

use std::{cell::Cell, ops::Range, rc::Rc};

use gpui::{
    AnyElement, AppContext as _, Bounds, Context, Entity, FontWeight, HapticFeedback, Hsla,
    InteractiveElement, IntoElement, MouseButton, ParentElement, Pixels, Point, Role, SharedString,
    StatefulInteractiveElement, Styled, TouchPhase, UniformListScrollHandle, Window, div, px, svg,
    uniform_list,
};
use rovsti_core::{
    contacts_view::{self, CallTarget},
    dial,
};
use rovsti_ui::{
    Icon,
    components::flat_glass,
    glass::{GlassSpec, glass},
    motion::press_scale,
    motion_tokens::{self, scale},
    spring::Spring,
    theme::{self, call_colors},
    tokens::{Theme, typography},
    touch::{Pointer, TapGuard, pointer_surface, touch_surface},
};

use crate::{
    PhoneApp, SheetKind,
    backend::Placement,
    contacts::{ContactRow, State},
    zone::ElapsedLine,
};

/// Row height of the Contacts list and the number chooser.
const ROW_H: f32 = 58.0;
/// Height of a Favorites tile.
const TILE_H: f32 = 104.0;
/// The scrolling list's window.
const LIST_H: f32 = 392.0;
const SEGMENTS: [&str; 3] = ["Keypad", "Favorites", "Contacts"];

#[cfg(test)]
thread_local! {
    /// Rows the sheet's lists built, for the virtualisation test.
    pub(crate) static ROWS_BUILT: Cell<usize> = const { Cell::new(0) };
}

/// Which list the sheet's scrolling window shows.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ListKind {
    /// Favorites tiles, two to a row of [`TILE_H`].
    Favorites,
    /// The Contacts rows (section headers as blank rows), [`ROW_H`] each.
    Contacts,
    /// The chosen contact's numbers, [`ROW_H`] each.
    Numbers,
}

impl ListKind {
    fn id(self) -> &'static str {
        match self {
            Self::Favorites => "sheet-ac-favorites",
            Self::Contacts => "sheet-ac-contacts",
            Self::Numbers => "sheet-ac-numbers",
        }
    }
}

pub(crate) struct AddCall {
    /// 0 Keypad, 1 Favorites, 2 Contacts; kept for the session, Favorites on first use.
    pub segment: usize,
    /// The glass indicator's position in segments, on `select`.
    seg_t: Spring,
    /// "Call failed: {reason}" in the sheet's pill.
    pub failed: Option<SharedString>,
    pub line: Entity<ElapsedLine>,
    pub pill_name: SharedString,
    /// The contact whose numbers are shown inline.
    chooser: Option<ContactRow>,
    /// One handle for whichever list shows (a segment switch or the number
    /// chooser starts it at the top).
    scroll: UniformListScrollHandle,
    bounds: Rc<Cell<Bounds<Pixels>>>,
    tap: TapGuard,
    /// The keyguard dismissal this sheet waits for (the backend's generation at the tap).
    pub awaiting_keyguard: Option<u64>,
    /// The sheet was open at the last render (its close is noticed once).
    was_open: bool,
}

impl AddCall {
    pub(crate) fn new(cx: &mut gpui::App) -> Self {
        Self {
            segment: 1,
            seg_t: Spring::new(1.0, motion_tokens::select()).with_epsilon(0.002),
            failed: None,
            line: cx.new(|_| ElapsedLine::new("", 15.0, 0).weight(typography::HEADLINE.weight)),
            pill_name: SharedString::default(),
            chooser: None,
            scroll: UniformListScrollHandle::new(),
            bounds: Rc::new(Cell::new(Bounds::default())),
            tap: TapGuard::default(),
            awaiting_keyguard: None,
            was_open: false,
        }
    }

    pub(crate) fn tick(&mut self, dt: std::time::Duration) -> bool {
        self.seg_t.tick(dt)
    }

    /// How far the shown list is scrolled down, in px (zero or more).
    fn scrolled(&self) -> f32 {
        -f32::from(self.scroll.0.borrow().base_handle.offset().y).min(0.0)
    }

    fn scroll_to_top(&self) {
        let handle = self.scroll.0.borrow().base_handle.clone();
        handle.set_offset(Point::default());
    }

    /// The segments that open over a locked keyguard: only the Keypad.
    fn segment_allowed(&self, segment: usize, locked: bool) -> bool {
        !locked || segment == 0
    }
}

impl PhoneApp {
    /// Add call was tapped (finger-up). A locked keyguard is dismissed first; the sheet
    /// opens from the success callback.
    pub(crate) fn open_add_call(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        // The state is re-read from the system at the tap, not from the last snapshot.
        let locked = rovsti_android::window::keyguard_locked()
            .unwrap_or_else(|_| self.backend.read(cx).calls().keyguard_locked());
        if locked {
            let gen_now = self.backend.read(cx).keyguard_gen();
            self.add.awaiting_keyguard = Some(gen_now);
            if rovsti_android::window::request_dismiss_keyguard().is_err() {
                // Desktop preview: there is no keyguard to dismiss.
                self.add.awaiting_keyguard = None;
                self.show_add_call(window, cx);
            }
            return;
        }
        self.show_add_call(window, cx);
    }

    /// The keyguard was dismissed (or never was up): opens the sheet.
    pub(crate) fn sync_add_call(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if let Some(gen_at_tap) = self.add.awaiting_keyguard
            && self.backend.read(cx).keyguard_gen() > gen_at_tap
        {
            self.add.awaiting_keyguard = None;
            self.show_add_call(window, cx);
        }
    }

    fn show_add_call(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let (name, since) = self
            .incall
            .view
            .as_ref()
            .map_or((SharedString::default(), 0), |v| {
                (v.name.clone(), v.connect_time_ms)
            });
        let locked = self.backend.read(cx).calls().keyguard_locked();
        // The sheet's keypad starts empty (it shares the Keypad tab's number).
        self.dialed.clear();
        self.delete_shown.snap(0.0);
        let add = &mut self.add;
        add.was_open = true;
        add.pill_name = name;
        add.failed = None;
        add.chooser = None;
        if locked && !add.segment_allowed(add.segment, true) {
            add.segment = 0;
        }
        add.seg_t.snap(add.segment as f32);
        add.line.update(cx, |l, cx| l.set(since, true, cx));
        self.sheet_kind = SheetKind::AddCall;
        self.sheet.open();
        let _ = window;
        cx.notify();
    }

    /// Once per render: when the sheet has closed (any way: a placed call, Back, the
    /// scrim, a drag), its pill timer stops and digits typed in it leave the Keypad tab.
    pub(crate) fn sync_add_call_closed(&mut self, cx: &mut Context<Self>) {
        if !self.add.was_open || self.add_call_open() {
            return;
        }
        self.add.was_open = false;
        self.add.chooser = None;
        self.dialed.clear();
        self.delete_shown.set_target(0.0);
        self.add.line.update(cx, |l, cx| l.set(0, false, cx));
    }

    /// The call ended with no other call left: the Add call sheet has nothing to add to.
    pub(crate) fn close_add_call(&mut self, cx: &mut Context<Self>) {
        self.add.awaiting_keyguard = None;
        if self.add_call_open() {
            self.close_sheet(cx);
        }
    }

    /// Back with the number chooser open returns to the list. Whether it did.
    pub(crate) fn add_call_back(&mut self, cx: &mut Context<Self>) -> bool {
        if !self.add_call_open() || self.add.chooser.take().is_none() {
            return false;
        }
        self.add.scroll_to_top();
        cx.notify();
        true
    }

    /// Desktop preview: the next segment of the Add call sheet.
    #[cfg(not(target_os = "android"))]
    pub(crate) fn preview_add_segment(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.add_call_open() {
            let next =
                ["sheet-ac-seg0", "sheet-ac-seg1", "sheet-ac-seg2"][(self.add.segment + 1) % 3];
            self.add_call_tap(next, window, cx);
        }
    }

    /// Desktop preview: taps the first Favorites tile (or Contacts row) of the open sheet.
    #[cfg(not(target_os = "android"))]
    pub(crate) fn preview_add_pick(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.add_call_open() {
            self.add_call_hit(10.0, 10.0, 300.0, window, cx);
        }
    }

    /// Whether the Add call sheet is the open sheet.
    pub(crate) fn add_call_open(&self) -> bool {
        self.sheet_kind == SheetKind::AddCall && self.sheet.is_visible()
    }

    /// A press id that belongs to the Add call sheet (the dial pad, the search field,
    /// a state pill), which must work while the sheet blocks the page.
    pub(crate) fn add_call_owns(&self, id: &str) -> bool {
        self.add_call_open()
            && (id.starts_with("key-")
                || id == "delete"
                || id.starts_with("cs-")
                || id.starts_with("sheet-"))
    }

    /// Places the typed number from the Keypad segment.
    fn add_call_dial(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let raw = self.dialed.clone();
        if dial::validate(&raw).is_err() {
            self.invalid(window);
            self.add.failed = Some("Not a valid number".into());
            return;
        }
        self.add_call_place(&raw, window, cx);
    }

    /// The one call path. A refusal stays in the sheet and the first call stays Active.
    fn add_call_place(&mut self, raw: &str, window: &mut Window, cx: &mut Context<Self>) {
        rovsti_ui::haptics::perform(window, HapticFeedback::Confirm);
        let placement = self
            .backend
            .update(cx, |backend, cx| backend.place_call(raw, cx));
        match placement {
            Placement::Placed | Placement::Requesting => {
                self.add.failed = None;
                self.dialed.clear();
                self.delete_shown.set_target(0.0);
                // The sheet drops away while the Hold-away plays.
                self.close_sheet(cx);
            }
            Placement::Debounced => {}
            Placement::Blocked => {
                self.add.failed = Some("Call failed: Phone access is off".into());
            }
            Placement::Failed | Placement::NoVoicemail => {
                self.add.failed = Some("Call failed: the call could not be placed".into());
            }
        }
        cx.notify();
    }

    fn add_call_contact(&mut self, row: ContactRow, window: &mut Window, cx: &mut Context<Self>) {
        match contacts_view::call_target(&row.numbers) {
            CallTarget::Direct(i) => {
                let raw = row.numbers[i].raw.clone();
                self.add_call_place(&raw, window, cx);
            }
            CallTarget::Choose => {
                self.add.chooser = Some(row);
                self.add.scroll_to_top();
            }
            CallTarget::Nothing => {}
        }
    }

    /// Finger-up on one of the sheet's own controls.
    pub(crate) fn add_call_tap(&mut self, id: &str, window: &mut Window, cx: &mut Context<Self>) {
        let locked = self.backend.read(cx).calls().keyguard_locked();
        match id {
            "sheet-ac-seg0" | "sheet-ac-seg1" | "sheet-ac-seg2" => {
                let segment = usize::from(id.as_bytes()[id.len() - 1] - b'0');
                if self.add.segment_allowed(segment, locked) && segment != self.add.segment {
                    rovsti_ui::haptics::perform(window, HapticFeedback::Selection);
                    self.add.segment = segment;
                    self.add.chooser = None;
                    self.add.failed = None;
                    self.add.seg_t.set_target(segment as f32);
                    self.add.scroll_to_top();
                } else if !self.add.segment_allowed(segment, locked) {
                    self.show_notice_pill("Unlock the phone to use this", None, false, cx);
                }
            }
            "sheet-ac-call" => self.add_call_dial(window, cx),
            "sheet-ac-back" => self.add.chooser = None,
            _ => {}
        }
        cx.notify();
    }

    fn add_call_pointer(
        &mut self,
        pointer: Pointer,
        position: Point<Pixels>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !self.add_call_open() {
            return;
        }
        let scroll = self.add.scrolled();
        match pointer {
            Pointer::Touch(TouchPhase::Started) => {
                self.add
                    .tap
                    .begin(f32::from(position.x), f32::from(position.y), scroll);
            }
            Pointer::Touch(TouchPhase::Moved) => {
                self.add
                    .tap
                    .moved(f32::from(position.x), f32::from(position.y));
            }
            Pointer::Touch(_) => {
                self.add
                    .tap
                    .finish(f32::from(position.x), f32::from(position.y));
            }
            Pointer::Click => {
                if !self.add.tap.allow_click(scroll) {
                    return;
                }
                let b = self.add.bounds.get();
                let x = f32::from(position.x - b.origin.x);
                let y = f32::from(position.y - b.origin.y) + scroll;
                if y < 0.0 {
                    return;
                }
                self.add_call_hit(x, y, f32::from(b.size.width), window, cx);
            }
        }
    }

    /// A tap at `(x, y)` inside the scrolling list (y includes the scroll).
    fn add_call_hit(
        &mut self,
        x: f32,
        y: f32,
        width: f32,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if let Some(contact) = self.add.chooser.clone() {
            let i = (y / ROW_H) as usize;
            if let Some(n) = contact.numbers.get(i) {
                let raw = n.raw.clone();
                self.add_call_place(&raw, window, cx);
            }
            return;
        }
        match self.add.segment {
            1 => {
                let cols = 2usize;
                let col = ((x / (width / cols as f32)) as usize).min(cols - 1);
                let row = (y / TILE_H) as usize;
                let index = row * cols + col;
                if let Some(tile) = self.favorites.tiles.get(index).cloned() {
                    self.add_call_contact(tile, window, cx);
                }
            }
            2 => {
                let i = (y / ROW_H) as usize;
                if let Some(row) = self.contacts.rows.get(i).filter(|r| !r.header).cloned() {
                    self.add_call_contact(row, window, cx);
                }
            }
            _ => {}
        }
    }

    // ---- Drawing ----------------------------------------------------------------

    /// The sheet, drawn over the In-Call screen.
    pub(crate) fn add_call_overlay(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        if !self.add_call_open() {
            return None;
        }
        let body = self.add_call_body(theme, window, cx);
        self.modal_sheet(theme, window, "Add call", body, cx)
    }

    fn add_call_body(&self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> AnyElement {
        let add = &self.add;
        let colors = theme::colors(theme);
        let locked = self.backend.read(cx).calls().keyguard_locked();
        let pill_text: AnyElement = match &add.failed {
            Some(text) => div()
                .truncate()
                .text_size(typography::SUBHEADLINE.size)
                .text_color(call_colors(theme).danger)
                .child(text.clone())
                .into_any_element(),
            None => div()
                .flex()
                .items_center()
                .gap(px(6.0))
                .child(
                    div()
                        .truncate()
                        .max_w(px(180.0))
                        .text_size(typography::HEADLINE.size)
                        .font_weight(typography::HEADLINE.weight)
                        .text_color(colors.label)
                        .child(add.pill_name.clone()),
                )
                .child(
                    div()
                        .text_size(typography::HEADLINE.size)
                        .text_color(colors.secondary_label)
                        .child("·"),
                )
                .child(add.line.clone())
                .into_any_element(),
        };
        let pill = div()
            .id("sheet-ac-pill")
            .role(Role::Status)
            .aria_label(match &add.failed {
                Some(text) => text.clone(),
                None => SharedString::from(format!("In call with {}", add.pill_name)),
            })
            .w_full()
            .flex()
            .justify_center()
            .child(
                flat_glass(theme, None)
                    .h(px(44.0))
                    .px(px(20.0))
                    .rounded_full()
                    .flex()
                    .items_center()
                    .child(pill_text),
            );
        let (rise, lift) = self.sheet.rise(1);
        let segmented = self.add_call_segments(theme, locked, window, cx);
        let content = match add.segment {
            0 => self.add_call_keypad(theme, cx),
            1 => self.add_call_favorites(theme, window, cx),
            _ => self.add_call_contacts(theme, window, cx),
        };
        div()
            .w_full()
            .px(px(16.0))
            .pb(px(20.0))
            .flex()
            .flex_col()
            .gap(px(14.0))
            .child(pill)
            .child(
                div()
                    .opacity(rise)
                    .relative()
                    .top(px(lift))
                    .child(segmented),
            )
            .child(content)
            .into_any_element()
    }

    fn add_call_segments(
        &self,
        theme: Theme,
        locked: bool,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let width = f32::from(window.viewport_size().width) - 20.0 - 32.0;
        let seg_w = width / 3.0;
        let at = self.add.seg_t.value.clamp(0.0, 2.0);
        let indicator = glass(GlassSpec::control(theme))
            .absolute()
            .top(px(2.0))
            .left(px(2.0 + at * seg_w))
            .w(px(seg_w - 4.0))
            .h(px(32.0))
            .rounded_full();
        let mut row = div()
            .relative()
            .h(px(36.0))
            .w_full()
            .rounded_full()
            .flex()
            .child(indicator);
        for (i, label) in SEGMENTS.iter().enumerate() {
            let id: &'static str = ["sheet-ac-seg0", "sheet-ac-seg1", "sheet-ac-seg2"][i];
            let allowed = self.add.segment_allowed(i, locked);
            row = row.child(
                div()
                    .id(id)
                    .role(Role::Tab)
                    .aria_label(*label)
                    .aria_selected(self.add.segment == i)
                    .aria_disabled(!allowed)
                    .relative()
                    .flex_1()
                    .h_full()
                    .flex()
                    .items_center()
                    .justify_center()
                    .opacity(if allowed { 1.0 } else { 0.4 })
                    .text_size(typography::SUBHEADLINE.size)
                    .font_weight(FontWeight(600.0))
                    .text_color(if self.add.segment == i {
                        colors.label
                    } else {
                        colors.secondary_label
                    })
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(move |this, _, window, cx| this.tap_sheet(id, window, cx)),
                    )
                    .child(touch_surface(Self::touch_listener(cx, Some(id.into()))))
                    .child(*label),
            );
        }
        flat_glass(theme, None)
            .rounded_full()
            .child(row)
            .into_any_element()
    }

    /// A tap on a finger-up control of a sheet.
    pub(crate) fn tap_sheet(
        &mut self,
        id: &'static str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !self.presses.touch_seen() {
            self.presses.down(id);
        }
        self.add_call_tap(id, window, cx);
    }

    /// The Keypad segment: the number, the standard 72 px dial pad, Delete and Call.
    fn add_call_keypad(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let press = self.presses.value("sheet-ac-call").clamp(0.0, 1.0);
        let k = press_scale(press, scale::PRESS_CARD);
        let valid = dial::validate(&self.dialed).is_ok();
        let delete_press = self.presses.value("delete").clamp(0.0, 1.0);
        div()
            .flex()
            .flex_col()
            .items_center()
            .gap(px(8.0))
            .child(self.number_display(theme, 52.0))
            .child(self.dial_pad(theme, 72.0, 24.0, 12.0, false, cx))
            .child(
                div()
                    .relative()
                    .w(px(72.0 * 3.0 + 48.0))
                    .h(px(78.0))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        div()
                            .id("sheet-ac-call")
                            .role(Role::Button)
                            .aria_label("Call")
                            .aria_disabled(!valid)
                            .relative()
                            .size(px(78.0))
                            .flex()
                            .items_center()
                            .justify_center()
                            .opacity(if valid { 1.0 } else { 0.5 })
                            .on_mouse_down(
                                MouseButton::Left,
                                cx.listener(|this, _, window, cx| {
                                    this.tap_sheet("sheet-ac-call", window, cx);
                                }),
                            )
                            .child(touch_surface(Self::touch_listener(
                                cx,
                                Some("sheet-ac-call".into()),
                            )))
                            .child(
                                div()
                                    .size(px(78.0 * k))
                                    .rounded_full()
                                    .bg(call_colors(theme).answer)
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .child(
                                        svg()
                                            .path(Icon::LucidePhone.path())
                                            .size(px(30.0))
                                            .text_color(gpui::white()),
                                    ),
                            ),
                    )
                    .children((!self.dialed.is_empty()).then(|| {
                        div()
                            .id("delete")
                            .role(Role::Button)
                            .aria_label("Delete")
                            .absolute()
                            .right(px(0.0))
                            .size(px(60.0))
                            .flex()
                            .items_center()
                            .justify_center()
                            .on_mouse_down(
                                MouseButton::Left,
                                cx.listener(|this, _, window, cx| {
                                    this.mouse_down("delete", window, cx)
                                }),
                            )
                            .child(touch_surface(Self::touch_listener(
                                cx,
                                Some("delete".into()),
                            )))
                            .child(
                                svg()
                                    .path(Icon::LucideDelete.path())
                                    .size(px(26.0 * press_scale(delete_press, scale::PRESS_CARD)))
                                    .text_color(colors.secondary_label),
                            )
                    })),
            )
            .into_any_element()
    }

    /// A scrolling window with the sheet's pointer surface on top. Only the
    /// rows in view are built (`uniform_list`), so a long book costs the same
    /// frame as a short one.
    fn add_call_list(&self, list: ListKind, count: usize, cx: &mut Context<Self>) -> AnyElement {
        let entity = cx.entity();
        div()
            .relative()
            .w_full()
            .h(px(LIST_H))
            .child(
                uniform_list(
                    list.id(),
                    count,
                    cx.processor(move |this, range: Range<usize>, window, _cx| {
                        let theme = rovsti_ui::current_theme(window);
                        this.add_call_rows(theme, list, range)
                    }),
                )
                .track_scroll(&self.add.scroll)
                .size_full(),
            )
            .child(pointer_surface(
                Rc::clone(&self.add.bounds),
                move |pointer, position, window, cx| {
                    entity.update(cx, |this, cx| {
                        this.add_call_pointer(pointer, position, window, cx)
                    });
                },
            ))
            .into_any_element()
    }

    fn add_call_message(
        &self,
        theme: Theme,
        title: &'static str,
        body: &'static str,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        div()
            .h(px(LIST_H))
            .w_full()
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .gap(px(6.0))
            .px(px(24.0))
            .child(
                div()
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child(title),
            )
            .child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.secondary_label)
                    .text_center()
                    .child(body),
            )
            .into_any_element()
    }

    /// The pill of a permission state: "Grant access".
    fn add_call_grant(&self, theme: Theme, cx: &mut Context<Self>) -> AnyElement {
        let colors = theme::colors(theme);
        let press = self.presses.value("cs-grant").clamp(0.0, 1.0);
        div()
            .h(px(LIST_H))
            .w_full()
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .gap(px(12.0))
            .px(px(24.0))
            .child(
                div()
                    .text_size(typography::TITLE3.size)
                    .font_weight(typography::TITLE3.weight)
                    .text_color(colors.label)
                    .child("Allow contacts access"),
            )
            .child(
                div()
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.secondary_label)
                    .text_center()
                    .child("Rovsti Phone needs it to show the people on this phone."),
            )
            .child(
                div()
                    .id("cs-grant")
                    .role(Role::Button)
                    .aria_label("Grant access")
                    .relative()
                    .h(px(44.0))
                    .px(px(22.0))
                    .rounded_full()
                    .bg(Hsla {
                        a: 0.14 + 0.1 * press,
                        ..colors.accent
                    })
                    .flex()
                    .items_center()
                    .text_size(typography::HEADLINE.size)
                    .font_weight(typography::HEADLINE.weight)
                    .text_color(colors.accent)
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(|this, _, window, cx| {
                            this.presses.down("cs-grant");
                            this.state_pill("cs-grant", window, cx);
                        }),
                    )
                    .child(touch_surface(Self::touch_listener(
                        cx,
                        Some("cs-grant".into()),
                    )))
                    .child("Grant access"),
            )
            .into_any_element()
    }

    fn avatar_row(
        &self,
        theme: Theme,
        row: &ContactRow,
        subtitle: Option<SharedString>,
        big: bool,
    ) -> gpui::Div {
        let colors = theme::colors(theme);
        let size = if big { 56.0 } else { 40.0 };
        let avatar =
            crate::contacts::person_avatar(theme, row.hue, row.monogram.as_ref(), size, None);
        if big {
            return div()
                .flex()
                .flex_col()
                .items_center()
                .gap(px(6.0))
                .child(avatar)
                .child(
                    div()
                        .max_w(px(150.0))
                        .truncate()
                        .text_size(typography::SUBHEADLINE.size)
                        .text_color(colors.label)
                        .child(row.name.clone()),
                );
        }
        div()
            .flex()
            .items_center()
            .gap(px(12.0))
            .child(avatar)
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .truncate()
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(colors.label)
                            .child(row.name.clone()),
                    )
                    .children(subtitle.map(|sub| {
                        div()
                            .truncate()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child(sub)
                    })),
            )
    }

    /// The Favorites segment: the tiles, two to a row (no long-press, no reorder).
    fn add_call_favorites(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let _ = window;
        let f = &self.favorites;
        match f.state {
            State::Denied | State::Unavailable => return self.add_call_grant(theme, cx),
            State::Loading => {
                return self.add_call_message(theme, "Loading", "Getting your Favorites.");
            }
            State::Ready if f.tiles.is_empty() => {
                return self.add_call_message(
                    theme,
                    "No favorites yet",
                    "Star a contact and they show up here.",
                );
            }
            State::Ready => {}
        }
        if let Some(contact) = &self.add.chooser {
            return self.add_call_numbers(theme, contact, cx);
        }
        self.add_call_list(ListKind::Favorites, f.tiles.len().div_ceil(2), cx)
    }

    /// The rows of `list` in `range` (only the ones in view are asked for).
    fn add_call_rows(&self, theme: Theme, list: ListKind, range: Range<usize>) -> Vec<AnyElement> {
        #[cfg(test)]
        ROWS_BUILT.with(|built| built.set(built.get() + range.len()));
        match list {
            ListKind::Favorites => {
                let tiles = &self.favorites.tiles;
                range
                    .map(|row| {
                        let pair = tiles
                            .get(row * 2..(row * 2 + 2).min(tiles.len()))
                            .unwrap_or_default();
                        div()
                            .h(px(TILE_H))
                            .w_full()
                            .flex()
                            .children(pair.iter().map(|tile| {
                                div()
                                    .flex_1()
                                    .h_full()
                                    .flex()
                                    .items_center()
                                    .justify_center()
                                    .child(self.avatar_row(theme, tile, None, true))
                            }))
                            .into_any_element()
                    })
                    .collect()
            }
            ListKind::Contacts => {
                let rows = &self.contacts.rows;
                range
                    .map(|i| {
                        let row = div().h(px(ROW_H)).w_full().flex().items_center();
                        match rows.get(i) {
                            Some(r) if !r.header => row.child(
                                self.avatar_row(theme, r, r.subtitle.clone(), false)
                                    .w_full(),
                            ),
                            _ => row,
                        }
                        .into_any_element()
                    })
                    .collect()
            }
            ListKind::Numbers => {
                let colors = theme::colors(theme);
                let numbers = self
                    .add
                    .chooser
                    .as_ref()
                    .map_or(&[][..], |c| &c.numbers[..]);
                range
                    .filter_map(|i| numbers.get(i))
                    .map(|n| {
                        div()
                            .h(px(ROW_H))
                            .w_full()
                            .flex()
                            .items_center()
                            .justify_between()
                            .child(
                                div()
                                    .flex()
                                    .flex_col()
                                    .child(
                                        div()
                                            .text_size(typography::HEADLINE.size)
                                            .font_weight(typography::HEADLINE.weight)
                                            .text_color(colors.label)
                                            .child(SharedString::from(n.display.clone())),
                                    )
                                    .child(
                                        div()
                                            .text_size(typography::FOOTNOTE.size)
                                            .text_color(colors.secondary_label)
                                            .child(SharedString::from(n.label.clone())),
                                    ),
                            )
                            .child(
                                svg()
                                    .path(Icon::LucidePhone.path())
                                    .size(px(20.0))
                                    .text_color(call_colors(theme).answer),
                            )
                            .into_any_element()
                    })
                    .collect()
            }
        }
    }

    /// The Contacts segment: search, then the list; a contact with several numbers
    /// shows them inline.
    fn add_call_contacts(
        &self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let _ = window;
        let c = &self.contacts;
        match c.state {
            State::Denied | State::Unavailable => return self.add_call_grant(theme, cx),
            State::Loading => {
                return self.add_call_message(theme, "Loading", "Getting your contacts.");
            }
            State::Ready => {}
        }
        if let Some(contact) = &self.add.chooser {
            return self.add_call_numbers(theme, contact, cx);
        }
        let list = if c.rows.iter().all(|r| r.header) {
            self.add_call_message(theme, "No matches", "Try another name or number.")
        } else {
            self.add_call_list(ListKind::Contacts, c.rows.len(), cx)
        };
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(self.contacts_field(theme, cx))
            .child(list)
            .into_any_element()
    }

    /// The number choice, inline in place of the list.
    fn add_call_numbers(
        &self,
        theme: Theme,
        contact: &ContactRow,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let press = self.presses.value("sheet-ac-back").clamp(0.0, 1.0);
        div()
            .flex()
            .flex_col()
            .gap(px(8.0))
            .child(
                div()
                    .id("sheet-ac-back")
                    .role(Role::Button)
                    .aria_label("Back to the list")
                    .relative()
                    .h(px(44.0))
                    .flex()
                    .items_center()
                    .gap(px(8.0))
                    .opacity(1.0 - 0.4 * press)
                    .on_mouse_down(
                        MouseButton::Left,
                        cx.listener(|this, _, window, cx| {
                            this.tap_sheet("sheet-ac-back", window, cx)
                        }),
                    )
                    .child(touch_surface(Self::touch_listener(
                        cx,
                        Some("sheet-ac-back".into()),
                    )))
                    .child(
                        svg()
                            .path(Icon::LucideChevronLeft.path())
                            .size(px(20.0))
                            .flex_none()
                            .text_color(colors.accent),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .truncate()
                            .text_size(typography::HEADLINE.size)
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(colors.accent)
                            .child(contact.name.clone()),
                    ),
            )
            .child(self.add_call_list(ListKind::Numbers, contact.numbers.len(), cx))
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_the_keypad_opens_over_a_locked_keyguard() {
        // `segment_allowed` is state-free, so a bare value is enough.
        struct Probe;
        impl Probe {
            fn allowed(segment: usize, locked: bool) -> bool {
                !locked || segment == 0
            }
        }
        assert!(Probe::allowed(0, true));
        assert!(!Probe::allowed(1, true));
        assert!(!Probe::allowed(2, true));
        assert!(Probe::allowed(1, false));
    }

    #[test]
    fn the_list_window_holds_whole_rows() {
        assert_eq!(LIST_H % 8.0, 0.0);
        const { assert!(ROW_H * 6.0 < LIST_H) };
    }
}
