//! The audio route list: the Audio control's face, the route popover that
//! opens from it, and the signature moments S17 (route bloom) and S18 (device
//! arrival). Stories 14.5 to 14.8, FR-41 to FR-43, AD-23.
//!
//! The list itself (`rovsti_core::audio::EndpointList`) is decided by the
//! core and held by the backend; this file only draws it. Everything the
//! views show is built when data arrives ([`RouteUi::sync`]): rows and the
//! control's caption are `SharedString`s, so rendering never formats. The
//! popover is an overlay; the control grid and the End button never move.

use std::{cell::Cell, rc::Rc, time::Duration};

use gpui::{
    AnyElement, Bounds, Context, HapticFeedback, Hsla, InteractiveElement, IntoElement,
    ParentElement, Pixels, Point, Role, ScrollHandle, SharedString, StatefulInteractiveElement,
    Styled, TouchPhase, Window, div, point, px, svg,
};
use rovsti_core::{
    audio::{Decision, EndpointList, Row, RowKind},
    wire::AudioRoute,
};
use rovsti_ui::{
    Icon,
    glass::{GlassSpec, glass},
    moments::{Fade, RippleRing},
    motion::press_scale,
    motion_tokens::{self, scale},
    spring::Spring,
    theme,
    tokens::{Theme, mix, shadow, typography},
    touch::{Pointer, TapGuard, pointer_surface},
};

use crate::{PhoneApp, backend, fx::A_AUDIO, keypad::HoldAction};

// ---- Slot constants (AD-35): nothing here depends on the data ---------------

/// The popover's width, corner radius and the gap above the control row.
pub(crate) const POP_W: f32 = 288.0;
const POP_RADIUS: f32 = 20.0;
const ABOVE_CONTROL: f32 = 8.0;
const SIDE_INSET: f32 = 16.0;
/// Fixed row heights.
const ROW_H: f32 = 52.0;
const LINE_H: f32 = 44.0;
const PAD: f32 = 6.0;
const DIVIDER: f32 = 7.0;
/// The device list shows this many rows, then scrolls inside the window.
const WINDOW_ROWS: usize = 5;
/// Where the control's caption lives (fixed 96 px, one line).
pub(crate) const CAPTION_W: f32 = 96.0;
/// A long press on the Audio control opens the list after this long.
pub(crate) const LONG_PRESS_MS: u64 = 450;
/// A requested route that Android has not reported by now is given up.
const REVERT_AFTER: Duration = Duration::from_secs(1);
/// The arrival dot stays this long.
const DOT_SECS: f32 = 3.0;
/// Popover opens from 0.86.
const OPEN_FROM: f32 = 0.86;
const STAGGER_S: f32 = 0.024;

// ---- What the control and the popover show ----------------------------------

/// The Audio control's face, built when the route or the list changes.
#[derive(Clone, Debug, PartialEq)]
pub(crate) struct AudioFace {
    pub icon: Icon,
    pub caption: SharedString,
    pub aria: SharedString,
    /// Tapping toggles Phone/Speaker (only those two exist).
    pub toggle: bool,
    /// The route is not the phone's earpiece: the control reads as on.
    pub on: bool,
    pub speaker: bool,
}

impl Default for AudioFace {
    fn default() -> Self {
        Self {
            icon: Icon::LucideSmartphone,
            caption: SharedString::new_static("Audio"),
            aria: SharedString::new_static("Audio"),
            toggle: true,
            on: false,
            speaker: false,
        }
    }
}

fn route_icon(route: AudioRoute) -> Icon {
    match route {
        AudioRoute::Bluetooth => Icon::LucideBluetoothConnected,
        AudioRoute::WiredHeadset => Icon::LucideHeadphones,
        AudioRoute::Speaker => Icon::LucideVolume2,
        AudioRoute::Earpiece => Icon::LucideSmartphone,
        AudioRoute::Unknown => Icon::LucideVolume2,
    }
}

fn route_noun(route: AudioRoute) -> &'static str {
    match route {
        AudioRoute::Earpiece => "Phone",
        AudioRoute::Speaker => "Speaker",
        AudioRoute::Bluetooth => "Bluetooth",
        AudioRoute::WiredHeadset => "Headphones",
        AudioRoute::Unknown => "Audio",
    }
}

/// The control's face for `route` and the list. Pure.
pub(crate) fn face_for(route: AudioRoute, list: &EndpointList) -> AudioFace {
    let selected = list.selected();
    let kind_route = selected.map_or(route, |row| match row.kind {
        RowKind::Bluetooth => AudioRoute::Bluetooth,
        RowKind::WiredHeadset => AudioRoute::WiredHeadset,
        RowKind::Speaker => AudioRoute::Speaker,
        RowKind::Phone => AudioRoute::Earpiece,
        RowKind::Unknown => AudioRoute::Unknown,
    });
    let name: String = match selected {
        Some(row) => row.label.clone(),
        None => route_noun(kind_route).to_owned(),
    };
    let toggle = list.decision() == Decision::Toggle;
    let aria = if toggle {
        if kind_route == AudioRoute::Speaker {
            "Audio, Speaker on. Double tap for phone".to_owned()
        } else {
            "Audio, Speaker off. Double tap for speaker".to_owned()
        }
    } else {
        format!("Audio, {name}. Double tap to choose audio output")
    };
    AudioFace {
        icon: route_icon(kind_route),
        caption: if kind_route == AudioRoute::Unknown {
            SharedString::new_static("Audio")
        } else {
            name.into()
        },
        aria: aria.into(),
        toggle,
        on: !matches!(kind_route, AudioRoute::Earpiece | AudioRoute::Unknown),
        speaker: kind_route == AudioRoute::Speaker,
    }
}

/// One popover row, ready to draw.
#[derive(Clone, Debug)]
struct RowView {
    id: SharedString,
    press: SharedString,
    label: SharedString,
    subtitle: &'static str,
    icon: Icon,
    aria: SharedString,
    selected: bool,
    ghost: bool,
    unavailable: bool,
    nameless: bool,
}

impl RowView {
    fn of(index: usize, row: &Row) -> Self {
        let icon = match row.kind {
            RowKind::Bluetooth if row.hearing_aid => Icon::LucideEar,
            RowKind::Bluetooth => Icon::LucideBluetooth,
            RowKind::WiredHeadset => Icon::LucideHeadphones,
            RowKind::Speaker | RowKind::Unknown => Icon::LucideVolume2,
            RowKind::Phone => Icon::LucideSmartphone,
        };
        let aria = if row.unavailable || row.ghost {
            format!("{}, unavailable", row.label)
        } else if row.selected {
            format!("{}, selected", row.label)
        } else {
            format!("{}. Double tap to switch", row.label)
        };
        Self {
            id: row.id.clone().into(),
            press: format!("rp-row-{index}").into(),
            label: row.label.clone().into(),
            subtitle: row.subtitle(),
            icon,
            aria: aria.into(),
            selected: row.selected,
            ghost: row.ghost,
            unavailable: row.unavailable,
            nameless: row.nameless,
        }
    }

    fn tappable(&self) -> bool {
        !self.unavailable && !self.ghost
    }
}

// ---- Layout arithmetic (pure, so the hit test is tested) ---------------------

/// What a position inside the popover content means.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum Hit {
    Names,
    Row(usize),
    Footer(usize),
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub(crate) struct Layout {
    pub names: bool,
    pub hint: bool,
    pub rows: usize,
}

impl Layout {
    fn header(self) -> f32 {
        LINE_H * f32::from(u8::from(self.names)) + LINE_H * f32::from(u8::from(self.hint))
    }

    pub fn window(self) -> f32 {
        self.rows.min(WINDOW_ROWS) as f32 * ROW_H
    }

    pub fn height(self) -> f32 {
        PAD + self.header() + self.window() + DIVIDER + 2.0 * LINE_H + PAD
    }

    /// `y` is measured from the top of the content, `scrolled` is how far the
    /// row window is scrolled (px, >= 0).
    pub fn hit(self, x: f32, y: f32, scrolled: f32) -> Option<Hit> {
        if !(0.0..=POP_W).contains(&x) || y < PAD {
            return None;
        }
        let mut top = PAD;
        if self.names {
            if y < top + LINE_H {
                return Some(Hit::Names);
            }
            top += LINE_H;
        }
        if self.hint {
            if y < top + LINE_H {
                return None;
            }
            top += LINE_H;
        }
        let window = self.window();
        if y < top + window {
            let index = ((y - top + scrolled) / ROW_H).floor();
            return (index >= 0.0 && (index as usize) < self.rows)
                .then_some(Hit::Row(index as usize));
        }
        top += window + DIVIDER;
        if y < top {
            return None;
        }
        let k = ((y - top) / LINE_H).floor();
        (0.0..2.0).contains(&k).then_some(Hit::Footer(k as usize))
    }
}

// ---- State -----------------------------------------------------------------

struct PendingRoute {
    id: SharedString,
    name: SharedString,
}

pub(crate) struct RouteUi {
    pub(crate) open: bool,
    appear: Spring,
    since_open: f32,
    fade: Fade,
    rows: Vec<RowView>,
    synced_rev: Option<u64>,
    face_key: Option<(u64, AudioRoute)>,
    pub(crate) face: AudioFace,
    /// S17: the control's icon and caption cross-fade (0 = old, 1 = new).
    swap: Spring,
    prev_icon: Icon,
    prev_caption: SharedString,
    /// S17: the check circle travelling from its row to the control.
    flight: Spring,
    flight_from: Point<Pixels>,
    ripple: RippleRing,
    /// S18: the 6 px dot at the icon, a halo on the arriving row.
    dot: Spring,
    dot_left: f32,
    halo: RippleRing,
    halo_id: Option<SharedString>,
    fresh: Spring,
    fresh_id: Option<SharedString>,
    pending: Option<PendingRoute>,
    pending_gen: u64,
    announce: Option<SharedString>,
    scroll: ScrollHandle,
    bounds: Rc<Cell<Bounds<Pixels>>>,
    pub(crate) control_bounds: Rc<Cell<Bounds<Pixels>>>,
    tap: TapGuard,
    /// A long press just opened the list: the lift's click adds nothing.
    long_fired: bool,
    /// The Audio control's own pointer bounds.
    control_tap: TapGuard,
    names_denials: u8,
    names_asked: bool,
    reduced: bool,
}

impl Default for RouteUi {
    fn default() -> Self {
        let sp = |v: f32, p| Spring::new(v, p).with_epsilon(0.002);
        Self {
            open: false,
            appear: sp(0.0, motion_tokens::appear()),
            since_open: 1.0,
            fade: Fade::default(),
            rows: Vec::new(),
            synced_rev: None,
            face_key: None,
            face: AudioFace::default(),
            swap: sp(1.0, motion_tokens::select()),
            prev_icon: Icon::LucideSmartphone,
            prev_caption: SharedString::new_static("Audio"),
            flight: sp(1.0, motion_tokens::appear()),
            flight_from: point(px(0.0), px(0.0)),
            ripple: RippleRing::new(Duration::from_millis(420), 1.0, 1.7, 0.45),
            dot: sp(0.0, motion_tokens::select()),
            dot_left: 0.0,
            halo: RippleRing::new(Duration::from_millis(380), 1.0, 1.6, 0.5),
            halo_id: None,
            fresh: sp(1.0, motion_tokens::appear()),
            fresh_id: None,
            pending: None,
            pending_gen: 0,
            announce: None,
            scroll: ScrollHandle::new(),
            bounds: Rc::default(),
            control_bounds: Rc::default(),
            tap: TapGuard::default(),
            long_fired: false,
            control_tap: TapGuard::default(),
            names_denials: 0,
            names_asked: false,
            reduced: false,
        }
    }
}

impl RouteUi {
    pub(crate) fn is_open(&self) -> bool {
        self.open
    }

    /// Something of the popover is on screen (open or leaving).
    pub(crate) fn visible(&self) -> bool {
        self.open || self.appear.value > 0.002 || !self.appear.is_settled()
    }

    /// Starts the popover's leave (or Hold / the end of the call closing it).
    pub(crate) fn close(&mut self) {
        self.open = false;
        self.appear.set_target(0.0);
    }

    pub(crate) fn set_reduced(&mut self, reduced: bool) {
        self.reduced = reduced;
    }

    pub(crate) fn has_pending(&self) -> bool {
        self.pending.is_some()
    }

    pub(crate) fn dot_on(&self) -> f32 {
        self.dot.value.clamp(0.0, 1.0)
    }

    fn layout(&self) -> Layout {
        Layout {
            names: self.names_row(),
            hint: !self
                .rows
                .iter()
                .any(|r| r.icon == Icon::LucideBluetooth || r.icon == Icon::LucideEar)
                && !self.rows.is_empty(),
            rows: self.rows.len(),
        }
    }

    fn names_row(&self) -> bool {
        self.rows.iter().any(|r| r.nameless && !r.ghost)
    }

    pub(crate) fn tick(&mut self, dt: Duration) -> bool {
        let mut moving = self.appear.tick(dt);
        moving |= self.swap.tick(dt);
        moving |= self.flight.tick(dt);
        moving |= self.dot.tick(dt);
        moving |= self.fresh.tick(dt);
        moving |= self.fade.tick(dt);
        moving |= self.ripple.tick(dt);
        moving |= self.halo.tick(dt);
        if self.open && self.since_open < 1.0 {
            self.since_open += dt.as_secs_f32();
            moving = true;
        }
        if self.dot_left > 0.0 {
            self.dot_left -= dt.as_secs_f32();
            if self.dot_left <= 0.0 {
                self.dot.set_target(0.0);
            }
            moving = true;
        }
        moving
    }

    /// Rebuilds rows and the control face when the list (or the route) changed.
    /// Returns whether the face changed (S17's cross-fade then plays).
    pub(crate) fn sync(&mut self, list: &EndpointList, rev: u64, route: AudioRoute) -> bool {
        if self.synced_rev != Some(rev) {
            self.synced_rev = Some(rev);
            self.rows = list
                .iter()
                .enumerate()
                .map(|(i, row)| RowView::of(i, row))
                .collect();
        }
        if self.face_key == Some((rev, route)) {
            return false;
        }
        self.face_key = Some((rev, route));
        let face = face_for(route, list);
        let changed = face.icon != self.face.icon || face.caption != self.face.caption;
        if changed {
            self.prev_icon = self.face.icon;
            self.prev_caption = self.face.caption.clone();
            if !self.reduced {
                self.swap.snap(0.0);
                self.swap.set_target(1.0);
            }
        }
        self.face = face;
        changed
    }

    /// The call ended: everything resets.
    pub(crate) fn reset(&mut self) {
        let reduced = self.reduced;
        *self = Self::default();
        self.reduced = reduced;
    }
}

// ---- Behaviour on PhoneApp ---------------------------------------------------

impl PhoneApp {
    /// Called by `sync_calls` after a snapshot: keeps the control's face and
    /// the rows current, and plays S18 for devices that arrived.
    pub(crate) fn route_sync(&mut self, route: AudioRoute, cx: &mut Context<Self>) {
        let (rev, diff) = self
            .backend
            .update(cx, |backend, _| backend.take_route_changes());
        let selected_id = {
            let backend = self.backend.read(cx);
            let list = backend.endpoints();
            let ui = &mut self.route;
            ui.sync(list, rev, route);
            list.selected().map(|r| r.id.clone())
        };
        let ui = &mut self.route;
        // A pending request ends when Android reports the new route.
        if let Some(pending) = &ui.pending
            && selected_id.as_deref() == Some(&*pending.id)
        {
            ui.announce = Some(format!("Now using {}", pending.name).into());
            ui.pending = None;
            ui.pending_gen += 1;
        }
        if !diff.added.is_empty() {
            if ui.open {
                ui.halo_id = diff.added.first().map(|id| SharedString::from(id.clone()));
                ui.fresh_id = ui.halo_id.clone();
                ui.fresh.snap(0.0);
                ui.fresh.set_target(1.0);
                ui.halo.start();
            } else {
                ui.dot.snap(if ui.reduced { 1.0 } else { 0.0 });
                ui.dot.set_target(1.0);
                ui.dot_left = DOT_SECS;
            }
        }
        // The control follows the list: tinted while the popover is open or
        // the route is not the phone's earpiece.
        self.sync_audio_on();
    }

    fn sync_audio_on(&mut self) {
        let on = self.route.face.on || self.route.open;
        let reduced = self.reduced;
        self.incall.set_audio_on(on, reduced);
    }

    /// A finger on the Audio control: press feedback now, a long press opens
    /// the list, a tap acts on finger-up through the tap guard.
    pub(crate) fn audio_pointer(
        &mut self,
        pointer: Pointer,
        position: Point<Pixels>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.incoming.covers() || !self.incall.audio_usable() {
            return;
        }
        let (x, y) = (f32::from(position.x), f32::from(position.y));
        match pointer {
            Pointer::Touch(TouchPhase::Started) => {
                if self.route.open {
                    return;
                }
                self.route.control_tap.begin(x, y, 0.0);
                self.route.long_fired = false;
                self.presses.touch_down("ic-audio");
                self.arm_hold(LONG_PRESS_MS, HoldAction::RouteList, window, cx);
                cx.notify();
            }
            Pointer::Touch(TouchPhase::Moved) => {
                if self.route.control_tap.moved(x, y) {
                    self.hold_gen += 1;
                    self.presses.release_all();
                    cx.notify();
                }
            }
            Pointer::Touch(_) => {
                self.route.control_tap.finish(x, y);
                self.hold_gen += 1;
                self.presses.release_all();
                cx.notify();
            }
            Pointer::Click => {
                if self.route.open {
                    return;
                }
                let tapped = self.route.control_tap.allow_click(0.0);
                if std::mem::take(&mut self.route.long_fired) || !tapped {
                    return;
                }
                self.presses.down("ic-audio");
                self.audio_tap(window, cx);
            }
        }
    }

    /// Finger-up on the Audio control: toggle Phone/Speaker, or open the list.
    fn audio_tap(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.incall.audio_usable() {
            return;
        }
        window.perform_haptic_feedback(HapticFeedback::Selection);
        if self.route.face.toggle || self.route.rows.is_empty() {
            self.incall_toggle_speaker(window, cx);
        } else {
            self.route_open(window, cx);
        }
    }

    pub(crate) fn route_long_press(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.incall.audio_usable() || self.route.open {
            return;
        }
        window.perform_haptic_feedback(HapticFeedback::LongPress);
        self.route.long_fired = true;
        self.route.control_tap.spoil();
        self.presses.release_all();
        self.route_open(window, cx);
    }

    /// Opens the popover (a long press opens it even with only two routes).
    pub(crate) fn route_open(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.route.open {
            return;
        }
        // Vanished devices leave the list now.
        self.backend
            .update(cx, |backend, cx| backend.open_route_list(cx));
        let route = self.incall.route();
        let rev = self.backend.read(cx).endpoint_rev();
        {
            let backend = self.backend.read(cx);
            self.route.sync(backend.endpoints(), rev, route);
        }
        let reduced = window.prefers_reduced_motion();
        let ui = &mut self.route;
        ui.reduced = reduced;
        ui.open = true;
        ui.since_open = 0.0;
        ui.appear.snap(if reduced { 1.0 } else { 0.0 });
        ui.appear.set_target(1.0);
        ui.dot.set_target(0.0);
        ui.dot_left = 0.0;
        if reduced {
            ui.fade.restart();
        }
        self.sync_audio_on();
        self.update_back(window);
        cx.notify();
    }

    pub(crate) fn route_close(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.route.open {
            return;
        }
        self.route.close();
        self.presses.release_all();
        self.sync_audio_on();
        self.update_back(window);
        cx.notify();
    }

    fn route_pointer(
        &mut self,
        pointer: Pointer,
        position: Point<Pixels>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !self.route.open {
            return;
        }
        let origin = self.route.bounds.get().origin;
        let x = f32::from(position.x - origin.x);
        let y = f32::from(position.y - origin.y);
        let scrolled = -f32::from(self.route.scroll.offset().y).min(0.0);
        let layout = self.route.layout();
        let press_id = |hit: Hit, ui: &RouteUi| -> Option<SharedString> {
            match hit {
                Hit::Names => Some(SharedString::new_static("rp-names")),
                Hit::Row(i) => ui
                    .rows
                    .get(i)
                    .filter(|r| r.tappable())
                    .map(|r| r.press.clone()),
                Hit::Footer(k) => Some(SharedString::new_static(if k == 0 {
                    "rp-foot-0"
                } else {
                    "rp-foot-1"
                })),
            }
        };
        match pointer {
            Pointer::Touch(TouchPhase::Started) => {
                self.route
                    .tap
                    .begin(f32::from(position.x), f32::from(position.y), scrolled);
                if let Some(hit) = layout.hit(x, y, scrolled)
                    && let Some(id) = press_id(hit, &self.route)
                {
                    self.presses.touch_down(id);
                    window.perform_haptic_feedback(HapticFeedback::Selection);
                    cx.notify();
                }
            }
            Pointer::Touch(TouchPhase::Moved) => {
                if self
                    .route
                    .tap
                    .moved(f32::from(position.x), f32::from(position.y))
                {
                    self.presses.release_all();
                    cx.notify();
                }
            }
            Pointer::Touch(_) => {
                self.route
                    .tap
                    .finish(f32::from(position.x), f32::from(position.y));
                self.presses.release_all();
                cx.notify();
            }
            Pointer::Click => {
                // A flick or a scrolled window never taps, whatever the host synthesises.
                if !self.route.tap.allow_click(scrolled) {
                    return;
                }
                let Some(hit) = layout.hit(x, y, scrolled) else {
                    return;
                };
                if let Some(id) = press_id(hit, &self.route) {
                    self.presses.down(id);
                }
                match hit {
                    Hit::Row(i) => self.route_pick(i, position, window, cx),
                    Hit::Names => self.route_ask_names(window, cx),
                    Hit::Footer(k) => self.route_footer(k, window, cx),
                }
            }
        }
    }

    /// A row was tapped (finger-up): route by identifier and play S17.
    fn route_pick(
        &mut self,
        index: usize,
        _position: Point<Pixels>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(row) = self.route.rows.get(index).cloned() else {
            return;
        };
        if !row.tappable() {
            return;
        }
        if row.selected {
            self.route_close(window, cx);
            return;
        }
        // S17 starts where the row's check is.
        let from = self.row_check_centre(index);
        let id = row.id.clone();
        let outcome = self
            .backend
            .update(cx, |backend, cx| backend.route_endpoint(&id, cx));
        self.route_close(window, cx);
        window.perform_haptic_feedback(HapticFeedback::Confirm);
        if outcome.failed() {
            self.show_notice_pill(format!("Couldn't switch to {}", row.label), None, false, cx);
            return;
        }
        let ui = &mut self.route;
        ui.pending = Some(PendingRoute {
            id: row.id.clone(),
            name: row.label.clone(),
        });
        ui.pending_gen += 1;
        if !ui.reduced {
            ui.flight_from = from;
            ui.flight.snap(0.0);
            ui.flight.set_target(1.0);
            if matches!(
                row.icon,
                Icon::LucideBluetooth | Icon::LucideEar | Icon::LucideHeadphones
            ) {
                ui.ripple.start();
            }
        }
        // The pending look, until Android reports the route or a second passes.
        let generation = ui.pending_gen;
        let name = row.label;
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(REVERT_AFTER).await;
            this.update(cx, |this, cx| {
                if this.route.pending_gen == generation && this.route.pending.is_some() {
                    this.route.pending = None;
                    this.show_notice_pill(format!("Couldn't switch to {name}"), None, false, cx);
                }
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn route_ask_names(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        // Once per session through the permission bridge; after two refusals
        // the app permission page opens instead. Routing works regardless.
        let denials = self.route.names_denials;
        let outcome = if denials >= 2 {
            backend::open_app_details()
        } else {
            backend::request_permission(backend::Group::Bluetooth)
        };
        if !self.route.names_asked {
            self.route.names_asked = true;
        } else if denials < 2 {
            self.route.names_denials += 1;
        }
        if outcome == backend::Outcome::NotAvailable {
            self.show_notice_pill("Not available on this platform", None, false, cx);
        }
        let _ = window;
        cx.notify();
    }

    /// "Bluetooth settings" / "Hearing aids": the popover closes first, then
    /// the system page opens. The call is never affected.
    fn route_footer(&mut self, k: usize, window: &mut Window, cx: &mut Context<Self>) {
        self.route_close(window, cx);
        let page = if k == 0 {
            rovsti_android::telecom::SystemPage::Bluetooth
        } else {
            rovsti_android::telecom::SystemPage::HearingDevices
        };
        if let Some(text) = backend::open_system(page).notice() {
            self.show_notice_pill(text, None, false, cx);
        }
    }

    fn row_check_centre(&self, index: usize) -> Point<Pixels> {
        let b = self.route.bounds.get();
        let layout = self.route.layout();
        let header = layout.header();
        let scrolled = -f32::from(self.route.scroll.offset().y).min(0.0);
        let y = PAD + header + index as f32 * ROW_H + ROW_H / 2.0 - scrolled;
        point(b.origin.x + px(POP_W - 14.0 - 12.0), b.origin.y + px(y))
    }

    /// Desktop preview: pick row `index` of the open popover.
    #[cfg(not(target_os = "android"))]
    pub(crate) fn preview_route_pick(
        &mut self,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.route.open {
            self.route_pick(index, point(px(0.0), px(0.0)), window, cx);
        }
    }

    /// Desktop preview: a tap on the Audio control.
    #[cfg(not(target_os = "android"))]
    pub(crate) fn preview_audio_tap(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.audio_tap(window, cx);
    }

    // ---- Rendering ----------------------------------------------------------

    /// The Audio control's 6 px arrival dot (S18, popover closed).
    pub(crate) fn audio_dot(&self, theme: Theme) -> Option<AnyElement> {
        let a = self.route.dot_on();
        (a > 0.01).then(|| {
            div()
                .absolute()
                .left(px(crate::incall::CONTROL_SIZE / 2.0 + 11.0))
                .top(px(crate::incall::CONTROL_SIZE / 2.0 - 17.0))
                .size(px(6.0))
                .rounded_full()
                .bg(Hsla {
                    a,
                    ..theme::colors(theme).accent
                })
                .into_any_element()
        })
    }

    /// The popover, its scrim, and the S17 flight; `None` when nothing shows.
    pub(crate) fn render_route(
        &mut self,
        theme: Theme,
        window: &Window,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let flight_on = self.route.flight.value < 0.995 || self.route.ripple.running();
        if !self.route.visible() && !flight_on {
            return None;
        }
        let mut layer = div().absolute().inset_0();
        if self.route.visible() {
            layer = layer.children(self.popover(theme, window, cx));
        }
        layer = layer.children(self.route_flight(theme));
        Some(layer.into_any_element())
    }

    fn popover(&self, theme: Theme, window: &Window, cx: &mut Context<Self>) -> Option<AnyElement> {
        let ui = &self.route;
        let colors = theme::colors(theme);
        let t = if ui.reduced {
            if ui.open {
                ui.fade.value()
            } else {
                ui.appear.value.clamp(0.0, 1.0)
            }
        } else {
            ui.appear.value.clamp(0.0, 1.0)
        };
        let k = if ui.reduced {
            1.0
        } else {
            OPEN_FROM + (1.0 - OPEN_FROM) * t.min(1.0)
        };
        let layout = ui.layout();
        let viewport = window.viewport_size();
        let max_h = f32::from(viewport.height) * 0.7;
        let full_h = layout.height().min(max_h);
        let control = self.marks.get(A_AUDIO)?;
        let bottom = f32::from(viewport.height) - (f32::from(control.origin.y) - ABOVE_CONTROL);
        let w = POP_W * k;
        let h = full_h * k;

        // Flat glass until within 2% of rest, then the refractive pass in one frame.
        let settled = t >= 0.98 || ui.reduced;
        let mut spec = GlassSpec::sheet(theme);
        if !settled {
            spec.lens_amount = px(0.0);
            spec.lens_height = px(0.0);
        }
        let entity = cx.entity();
        let rows_dim = |i: usize| -> (f32, f32) {
            if ui.reduced {
                return (1.0, 0.0);
            }
            let local = ((ui.since_open - STAGGER_S * i as f32) / 0.2).clamp(0.0, 1.0);
            let eased = 1.0 - (1.0 - local).powi(3);
            (eased, (1.0 - eased) * 8.0)
        };

        let mut content = div()
            .id("route-content")
            .role(Role::Menu)
            .aria_label(SharedString::from(format!(
                "Audio output, {} options",
                ui.rows.len()
            )))
            .absolute()
            .top_0()
            .right_0()
            .w(px(POP_W))
            .h(px(full_h))
            .pt(px(PAD))
            .pb(px(PAD))
            .flex()
            .flex_col();
        let mut index = 0;
        if layout.names {
            let (a, lift) = rows_dim(index);
            index += 1;
            content =
                content.child(self.line_row(theme, "rp-names", "Show device names", None, a, lift));
        }
        if layout.hint {
            let (a, lift) = rows_dim(index);
            index += 1;
            content = content.child(
                div()
                    .h(px(LINE_H))
                    .px(px(16.0))
                    .flex()
                    .items_center()
                    .opacity(a * 0.6)
                    .relative()
                    .top(px(lift))
                    .text_size(typography::SUBHEADLINE.size)
                    .text_color(colors.secondary_label)
                    .child("No Bluetooth device connected"),
            );
        }
        let mut rows = div()
            .id("route-rows")
            .h(px(layout.window()))
            .overflow_y_scroll()
            .track_scroll(&ui.scroll)
            .flex()
            .flex_col();
        for (i, row) in ui.rows.iter().enumerate() {
            let (a, lift) = rows_dim(index + i);
            rows = rows.child(self.device_row(theme, row, a, lift));
        }
        content = content.child(rows);
        content = content.child(
            div()
                .h(px(DIVIDER))
                .flex()
                .items_center()
                .px(px(16.0))
                .child(div().h(px(1.0)).w_full().bg(Hsla {
                    a: 0.5,
                    ..colors.separator
                })),
        );
        content = content
            .child(self.line_row(
                theme,
                "rp-foot-0",
                "Bluetooth settings",
                Some(Icon::LucideExternalLink),
                1.0,
                0.0,
            ))
            .child(self.line_row(
                theme,
                "rp-foot-1",
                "Hearing aids",
                Some(Icon::LucideExternalLink),
                1.0,
                0.0,
            ));
        content = content.child(pointer_surface(
            Rc::clone(&ui.bounds),
            move |pointer, position, window, cx| {
                entity.update(cx, |this, cx| {
                    this.route_pointer(pointer, position, window, cx)
                });
            },
        ));
        let scrim_entity = cx.entity();
        // Taps outside the popover only dismiss it: the End button stays free.
        let scrim = div()
            .id("route-scrim")
            .absolute()
            .top_0()
            .left_0()
            .right_0()
            .bottom(px(136.0))
            .occlude()
            .on_mouse_down(gpui::MouseButton::Left, move |_, window, cx| {
                scrim_entity.update(cx, |this, cx| this.route_close(window, cx));
            });
        let dim = div().absolute().inset_0().bg(Hsla {
            a: 0.30 * t,
            ..gpui::black()
        });
        let panel = glass(spec)
            .id("route-popover")
            .occlude()
            .absolute()
            .right(px(SIDE_INSET))
            .bottom(px(bottom))
            .w(px(w))
            .h(px(h))
            .rounded(px(POP_RADIUS))
            .shadow(shadow::glass(theme.is_dark()))
            .overflow_hidden()
            .opacity(t.min(1.0))
            .child(content);
        let announce = ui.announce.clone().map(|text| {
            div()
                .id("route-announce")
                .absolute()
                .size(px(1.0))
                .role(Role::Status)
                .aria_label(text)
        });
        Some(
            div()
                .absolute()
                .inset_0()
                .child(dim)
                .child(scrim)
                .child(panel)
                .children(announce)
                .into_any_element(),
        )
    }

    fn line_row(
        &self,
        theme: Theme,
        id: &'static str,
        label: &'static str,
        trailing: Option<Icon>,
        alpha: f32,
        lift: f32,
    ) -> AnyElement {
        let colors = theme::colors(theme);
        let press = self.presses.value(id);
        div()
            .id(id)
            .role(Role::Button)
            .aria_label(label)
            .relative()
            .top(px(lift))
            .h(px(LINE_H))
            .mx(px(6.0))
            .px(px(10.0))
            .rounded(px(12.0))
            .bg(Hsla {
                a: 0.14 * press.clamp(0.0, 1.0),
                ..colors.label
            })
            .opacity(alpha)
            .flex()
            .items_center()
            .justify_between()
            .text_size(px(15.0 * press_scale(press, scale::PRESS)))
            .text_color(colors.accent)
            .child(label)
            .children(trailing.map(|icon| {
                svg()
                    .path(icon.path())
                    .size(px(16.0))
                    .text_color(colors.accent)
            }))
            .into_any_element()
    }

    fn device_row(&self, theme: Theme, row: &RowView, alpha: f32, lift: f32) -> AnyElement {
        let ui = &self.route;
        let colors = theme::colors(theme);
        let press = self.presses.value(&row.press).clamp(0.0, 1.0);
        let dimmed = row.ghost || row.unavailable;
        let fresh = if ui.fresh_id.as_deref() == Some(&*row.id) && !row.id.is_empty() {
            ui.fresh.value.clamp(0.0, 1.0)
        } else {
            1.0
        };
        let tile_tint = if row.selected {
            Hsla {
                a: 0.18,
                ..colors.accent
            }
        } else {
            colors.fill
        };
        let halo = (ui.halo_id.as_deref() == Some(&*row.id))
            .then(|| ui.halo.state())
            .flatten();
        let mut tile = div()
            .relative()
            .size(px(36.0))
            .flex_none()
            .rounded(px(10.0))
            .bg(tile_tint)
            .flex()
            .items_center()
            .justify_center()
            .child(
                svg()
                    .path(row.icon.path())
                    .size(px(20.0))
                    .text_color(if row.selected {
                        colors.accent
                    } else {
                        colors.label
                    }),
            );
        if let Some((scale, a)) = halo {
            // S18: one halo ring on the arriving row's tile, accent glow 0.5.
            tile = tile.child(
                div()
                    .absolute()
                    .size(px(36.0 * scale))
                    .rounded(px(10.0 * scale))
                    .border_2()
                    .border_color(Hsla {
                        a,
                        ..theme::accent_glow(theme)
                    }),
            );
        }
        div()
            .id(row.press.clone())
            .role(Role::MenuItem)
            .aria_label(row.aria.clone())
            .aria_selected(row.selected)
            .aria_disabled(!row.tappable())
            .relative()
            .top(px(lift))
            .h(px(ROW_H))
            .flex_none()
            .mx(px(6.0))
            .px(px(8.0))
            .rounded(px(14.0))
            .bg(mix(
                Hsla {
                    a: 0.0,
                    ..colors.label
                },
                colors.label,
                0.10 * press,
            ))
            .opacity(alpha * fresh * if dimmed { 0.45 } else { 1.0 })
            .flex()
            .items_center()
            .gap(px(12.0))
            .child(tile)
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .flex()
                    .flex_col()
                    .child(
                        div()
                            .truncate()
                            .text_size(px(16.0 * press_scale(press, scale::PRESS)))
                            .font_weight(typography::HEADLINE.weight)
                            .text_color(colors.label)
                            .child(row.label.clone()),
                    )
                    .children((!row.subtitle.is_empty()).then(|| {
                        div()
                            .truncate()
                            .text_size(typography::FOOTNOTE.size)
                            .text_color(colors.secondary_label)
                            .child(row.subtitle)
                    })),
            )
            .child(
                div()
                    .size(px(24.0))
                    .flex_none()
                    .flex()
                    .items_center()
                    .justify_center()
                    .children(row.selected.then(|| {
                        svg()
                            .path(Icon::LucideCheck.path())
                            .size(px(20.0))
                            .text_color(colors.accent)
                    })),
            )
            .into_any_element()
    }

    /// S17: the check circle travels from its row to the control; one accent
    /// ripple for Bluetooth and wired.
    fn route_flight(&self, theme: Theme) -> Option<AnyElement> {
        let ui = &self.route;
        let control = self.marks.get(A_AUDIO)?;
        let colors = theme::colors(theme);
        let t = ui.flight.value;
        let centre = control.center();
        let mut layer = div().absolute().inset_0();
        if t < 0.995 {
            let p = t.clamp(0.0, 1.0);
            let x = f32::from(ui.flight_from.x)
                + (f32::from(centre.x) - f32::from(ui.flight_from.x)) * p;
            let y = f32::from(ui.flight_from.y)
                + (f32::from(centre.y) - f32::from(ui.flight_from.y)) * p;
            let size = 24.0 * (1.0 - 0.35 * p);
            layer = layer.child(
                div()
                    .absolute()
                    .left(px(x - size / 2.0))
                    .top(px(y - size / 2.0))
                    .size(px(size))
                    .rounded_full()
                    .bg(colors.accent)
                    .opacity((1.0 - p).powf(0.6).clamp(0.0, 1.0))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(
                        svg()
                            .path(Icon::LucideCheck.path())
                            .size(px(size * 0.7))
                            .text_color(gpui::white()),
                    ),
            );
        }
        if let Some((scale, a)) = ui.ripple.state() {
            let size = crate::incall::CONTROL_SIZE * scale;
            layer = layer.child(
                div()
                    .absolute()
                    .left(centre.x - px(size / 2.0))
                    .top(centre.y - px(size / 2.0))
                    .size(px(size))
                    .rounded_full()
                    .border_2()
                    .border_color(Hsla {
                        a,
                        ..theme::accent_glow(theme)
                    }),
            );
        }
        Some(layer.into_any_element())
    }

    /// The caption and icon of the Audio control with the S17 cross-fade:
    /// `(old icon, old caption, new icon, new caption, t)`.
    pub(crate) fn audio_swap(&self) -> (Icon, &SharedString, f32) {
        (
            self.route.prev_icon,
            &self.route.prev_caption,
            self.route.swap.value.clamp(0.0, 1.0),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_core::{
        audio::MAX_ROWS,
        wire::{Endpoint, EndpointType},
    };

    fn ep(id: &str, kind: EndpointType, name: Option<&str>, selected: bool) -> Endpoint {
        Endpoint {
            id: id.into(),
            kind,
            name: name.map(str::to_owned),
            selected,
            hint: None,
        }
    }

    #[test]
    fn the_face_follows_the_selected_endpoint() {
        let list = EndpointList::from_snapshot(
            &[
                ep("p", EndpointType::Earpiece, None, false),
                ep("b", EndpointType::Bluetooth, Some("Galaxy Buds"), true),
                ep("s", EndpointType::Speaker, None, false),
            ],
            None,
        );
        let face = face_for(AudioRoute::Bluetooth, &list);
        assert_eq!(face.icon, Icon::LucideBluetoothConnected);
        assert_eq!(face.caption, "Galaxy Buds");
        assert!(!face.toggle && face.on);
        assert_eq!(
            face.aria,
            "Audio, Galaxy Buds. Double tap to choose audio output"
        );
    }

    #[test]
    fn two_routes_toggle_and_read_speaker_state() {
        let list = EndpointList::from_snapshot(
            &[
                ep("p", EndpointType::Earpiece, None, true),
                ep("s", EndpointType::Speaker, None, false),
            ],
            None,
        );
        let off = face_for(AudioRoute::Earpiece, &list);
        assert!(off.toggle && !off.on);
        assert_eq!(off.icon, Icon::LucideSmartphone);
        assert_eq!(off.aria, "Audio, Speaker off. Double tap for speaker");
        let none = face_for(AudioRoute::Speaker, &EndpointList::default());
        assert!(none.toggle && none.speaker && none.on);
        assert_eq!(none.caption, "Speaker");
        assert_eq!(none.aria, "Audio, Speaker on. Double tap for phone");
    }

    #[test]
    fn an_unknown_route_without_endpoints_reads_audio() {
        let face = face_for(AudioRoute::Unknown, &EndpointList::default());
        assert_eq!(face.caption, "Audio");
    }

    #[test]
    fn the_hit_test_maps_positions_to_rows_and_footers() {
        let l = Layout {
            names: false,
            hint: false,
            rows: 3,
        };
        // PAD 6 + three rows of 52, divider 7, two 44 lines.
        assert_eq!(l.hit(10.0, 7.0, 0.0), Some(Hit::Row(0)));
        assert_eq!(l.hit(10.0, 6.0 + 52.0 + 1.0, 0.0), Some(Hit::Row(1)));
        assert_eq!(l.hit(10.0, 6.0 + 156.0 + 3.0, 0.0), None, "the divider");
        assert_eq!(
            l.hit(10.0, 6.0 + 156.0 + 7.0 + 1.0, 0.0),
            Some(Hit::Footer(0))
        );
        assert_eq!(
            l.hit(10.0, 6.0 + 156.0 + 7.0 + 45.0, 0.0),
            Some(Hit::Footer(1))
        );
        assert_eq!(l.hit(10.0, l.height() - 1.0, 0.0), None, "the bottom pad");
        assert_eq!(l.hit(-1.0, 20.0, 0.0), None);
        assert_eq!(l.hit(POP_W + 1.0, 20.0, 0.0), None);
    }

    #[test]
    fn the_header_rows_shift_the_list_and_the_hint_is_inert() {
        let l = Layout {
            names: true,
            hint: true,
            rows: 2,
        };
        assert_eq!(l.hit(10.0, 10.0, 0.0), Some(Hit::Names));
        assert_eq!(l.hit(10.0, 6.0 + 44.0 + 10.0, 0.0), None, "the hint row");
        assert_eq!(l.hit(10.0, 6.0 + 88.0 + 1.0, 0.0), Some(Hit::Row(0)));
    }

    #[test]
    fn more_than_five_rows_scroll_inside_a_fixed_window() {
        let l = Layout {
            names: false,
            hint: false,
            rows: MAX_ROWS,
        };
        assert_eq!(l.window(), 5.0 * ROW_H);
        assert_eq!(l.hit(10.0, 7.0, 0.0), Some(Hit::Row(0)));
        // Scrolled by two rows, the first visible row is the third.
        assert_eq!(l.hit(10.0, 7.0, 2.0 * ROW_H), Some(Hit::Row(2)));
        // The footer does not move with the scroll.
        let footer_y = 6.0 + 5.0 * ROW_H + 7.0 + 1.0;
        assert_eq!(l.hit(10.0, footer_y, 3.0 * ROW_H), Some(Hit::Footer(0)));
        assert!(l.height() < 520.0);
    }

    #[test]
    fn rows_say_what_they_are_to_talkback() {
        let list = EndpointList::from_snapshot(
            &[
                ep("b", EndpointType::Bluetooth, Some("Buds"), true),
                ep("w", EndpointType::WiredHeadset, Some("Headset"), false),
            ],
            None,
        );
        let rows: Vec<RowView> = list
            .iter()
            .enumerate()
            .map(|(i, r)| RowView::of(i, r))
            .collect();
        assert_eq!(rows[0].aria, "Buds, selected");
        assert_eq!(rows[1].aria, "Headset. Double tap to switch");
        let phone = rows.last().expect("placeholder");
        assert_eq!(phone.aria, "Phone, unavailable");
        assert!(!phone.tappable());
    }
}
