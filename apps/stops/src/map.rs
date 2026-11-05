//! The map: a vector "radar" drawn with `canvas` and `PathBuilder` (no tiles).
//!
//! It shows where you are with the accuracy of the fix, the dashed alarm ring,
//! the breadcrumb of this trip, the destination pin and, after a transit
//! import, the stops in view and the chosen line. A finger drags to pan, two
//! fingers (or a double tap) zoom, a tap picks the stop under it.
//!
//! Everything that decides something is pure and tested: which stops are in
//! view, how a polyline is thinned, how touches become gestures, what a scene
//! looks like in pixels. The painting and the element glue are thin.

use std::{cell::Cell, collections::VecDeque, rc::Rc, time::Duration};

use gpui::{
    AnyElement, App, BorderStyle, Bounds, Context, Corners, Edges, Hsla, InteractiveElement,
    IntoElement, MouseButton, MouseDownEvent, ParentElement, PathBuilder, Pixels, Point,
    StatefulInteractiveElement, Styled, Task, TouchEvent, TouchPhase, Window, canvas, div, point,
    px, quad,
};
use rovsti_geo::{AlarmRule, Coord, EARTH_RADIUS_M, Feed, Stop, view::Viewport};
use rovsti_ui::{
    components::flat_glass,
    theme,
    tokens::{Theme, radius, typography},
    touch::TAP_SLOP,
};

use crate::{
    StopsApp,
    picker::{Destination, SheetMode, coord_text},
    store::now_ms,
};

/// Fixes kept for the breadcrumb.
pub const BREADCRUMB_CAP: usize = 500;
/// Stops drawn per frame.
pub const STOP_CAP: usize = 300;
/// A tap this close (pixels) to a stop picks it.
pub const PICK_RADIUS_PX: f32 = 40.0;
/// Fixes worse than this are not worth a dot on the trail.
const TRAIL_MAX_ACCURACY_M: f64 = 150.0;
/// A fix closer than this to the previous one adds nothing to the trail.
const TRAIL_MIN_STEP_M: f64 = 3.0;
/// Trail and line vertices closer than this on screen are merged.
const TRAIL_MIN_PX: f32 = 3.0;
const LINE_MIN_PX: f32 = 4.0;
/// The follow view never zooms in past this, so a short trip is not a blur.
const MIN_FIT_MPP: f64 = 4.0;
const FIT_PADDING_PX: f32 = 48.0;
/// Two taps this close in time and space are a double tap.
const DOUBLE_TAP_MS: u64 = 300;
const DOUBLE_TAP_SLOP_PX: f32 = 40.0;
/// A mouse click this soon after a touch is that touch's echo.
const CLICK_ECHO_MS: u64 = 700;
/// Two fingers closer than this give no usable zoom ratio.
const MIN_PINCH_PX: f32 = 12.0;
/// Rings and dots beyond this many pixels are not worth tessellating.
const MAX_RADIUS_PX: f32 = 4_000.0;

pub const MAP_HEIGHT: f32 = 340.0;
const DEFAULT_SIZE: (f32, f32) = (370.0, MAP_HEIGHT);
const CONTROL_SIZE: f32 = 40.0;
const CONTROL_GAP: f32 = 8.0;
const CONTROL_MARGIN: f32 = 10.0;
const CONTROL_COUNT: f32 = 3.0;

// ---- pure: what is in view ----------------------------------------------------------

/// Indices of the stops inside the `width` x `height` view (plus a small
/// margin so dots do not pop at the edge), at most `cap`. When more are
/// inside, those nearest the centre win. Ascending order.
pub fn stops_in_view(
    stops: &[Stop],
    viewport: &Viewport,
    width: f32,
    height: f32,
    cap: usize,
) -> Vec<u32> {
    const MARGIN_PX: f32 = 8.0;
    if cap == 0 {
        return Vec::new();
    }
    let (hw, hh) = (width / 2.0 + MARGIN_PX, height / 2.0 + MARGIN_PX);
    let south_west = viewport.unproject((-hw, hh));
    let north_east = viewport.unproject((hw, -hh));
    let centre = viewport.center;
    let lon_scale = centre.lat.to_radians().cos().max(1e-6);
    let mut hits: Vec<(f64, u32)> = stops
        .iter()
        .enumerate()
        .filter(|(_, s)| {
            (south_west.lat..=north_east.lat).contains(&s.coord.lat)
                && (south_west.lon..=north_east.lon).contains(&s.coord.lon)
        })
        .map(|(i, s)| {
            let dy = s.coord.lat - centre.lat;
            let dx = (s.coord.lon - centre.lon) * lon_scale;
            (dx * dx + dy * dy, i as u32)
        })
        .collect();
    if hits.len() > cap {
        hits.select_nth_unstable_by(cap - 1, |a, b| a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)));
        hits.truncate(cap);
    }
    let mut indices: Vec<u32> = hits.into_iter().map(|h| h.1).collect();
    indices.sort_unstable();
    indices
}

/// Drops vertices closer than `min_px` to the last kept one. The first and the
/// last vertex always stay, so the line still ends where it ends.
pub fn thin_polyline(points: &[(f32, f32)], min_px: f32) -> Vec<(f32, f32)> {
    let (Some(&first), Some(&last)) = (points.first(), points.last()) else {
        return Vec::new();
    };
    if points.len() < 2 {
        return vec![first];
    }
    let mut out = vec![first];
    for &p in &points[1..points.len() - 1] {
        if let Some(&kept) = out.last()
            && dist(kept, p) >= min_px
        {
            out.push(p);
        }
    }
    // A kept vertex that crowds the end gives way to it.
    if out.len() > 1 && out.last().is_some_and(|&k| dist(k, last) < min_px) {
        out.pop();
    }
    out.push(last);
    out
}

/// The stop under a tap at `at` (pixels from the view centre): the nearest
/// drawn stop within `max_px`, else the feed's nearest stop within the same
/// distance on the ground (stops past the draw cap are still pickable).
pub fn pick_stop(
    feed: &Feed,
    viewport: &Viewport,
    size: (f32, f32),
    at: (f32, f32),
    max_px: f32,
) -> Option<u32> {
    let visible = stops_in_view(&feed.stops, viewport, size.0, size.1, STOP_CAP);
    let coords: Vec<Coord> = visible
        .iter()
        .map(|&i| feed.stops[i as usize].coord)
        .collect();
    if let Some(k) = viewport.nearest_on_screen(&coords, at, max_px) {
        return Some(visible[k]);
    }
    feed.nearest(
        viewport.unproject(at),
        f64::from(max_px) * viewport.metres_per_px,
    )
}

/// Where the alarm ring is drawn and how big: around the destination for a
/// radius or ETA rule, around the trigger stop for "N stops before".
pub fn alarm_ring(destination: Coord, rule: AlarmRule) -> (Coord, f64) {
    match rule {
        AlarmRule::Radius { metres } => (destination, metres),
        AlarmRule::Eta { min_metres, .. } => (destination, min_metres),
        AlarmRule::BeforeStop { trigger, metres } => (trigger, metres),
    }
}

/// The corners of the box around `centre` that holds a circle of `metres`.
fn extent(centre: Coord, metres: f64) -> [Coord; 2] {
    let dlat = (metres / EARTH_RADIUS_M).to_degrees();
    let dlon = dlat / centre.lat.to_radians().cos().max(1e-6);
    [
        Coord::new(centre.lat - dlat, centre.lon - dlon),
        Coord::new(centre.lat + dlat, centre.lon + dlon),
    ]
}

/// The view that shows you, the destination and the alarm ring.
pub fn fit_view(
    position: Option<Coord>,
    destination: Option<Coord>,
    ring: Option<(Coord, f64)>,
    size: (f32, f32),
) -> Option<Viewport> {
    let mut points: Vec<Coord> = Vec::with_capacity(4);
    points.extend(position);
    points.extend(destination);
    if let Some((centre, metres)) = ring
        && metres.is_finite()
        && metres > 0.0
    {
        points.extend(extent(centre, metres));
    }
    if points.is_empty() {
        return None;
    }
    let fitted = Viewport::fit(&points, size.0, size.1, FIT_PADDING_PX);
    Some(Viewport::new(
        fitted.center,
        fitted.metres_per_px.max(MIN_FIT_MPP),
    ))
}

/// A scale bar for `metres_per_px`: its length in pixels and its label.
pub fn scale_bar(metres_per_px: f64) -> (f32, String) {
    const STEPS: [u32; 13] = [
        10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000, 100_000,
    ];
    const MAX_PX: f64 = 110.0;
    let metres = STEPS
        .into_iter()
        .rev()
        .find(|&m| f64::from(m) / metres_per_px <= MAX_PX)
        .unwrap_or(STEPS[0]);
    let label = if metres >= 1_000 {
        format!("{} km", metres / 1_000)
    } else {
        format!("{metres} m")
    };
    ((f64::from(metres) / metres_per_px) as f32, label)
}

fn dist(a: (f32, f32), b: (f32, f32)) -> f32 {
    (a.0 - b.0).hypot(a.1 - b.1)
}

// ---- pure: the breadcrumb -------------------------------------------------------------

/// The last [`BREADCRUMB_CAP`] usable fixes of the trip in progress. Kept in
/// memory only: the trip file holds the distance, not the path.
#[derive(Default)]
pub struct Breadcrumb {
    trip: Option<u64>,
    points: VecDeque<Coord>,
}

impl Breadcrumb {
    /// Adds a fix of trip `trip`. A fix of another trip starts a new trail;
    /// poor fixes and ones that barely moved are left out.
    pub fn record(&mut self, trip: Option<u64>, at: Coord, accuracy_m: f64) {
        if self.trip != trip {
            self.points.clear();
            self.trip = trip;
        }
        // `NaN` is not usable either.
        if accuracy_m.is_nan() || accuracy_m > TRAIL_MAX_ACCURACY_M {
            return;
        }
        if self
            .points
            .back()
            .is_some_and(|&last| last.distance_m(at) < TRAIL_MIN_STEP_M)
        {
            return;
        }
        if self.points.len() == BREADCRUMB_CAP {
            self.points.pop_front();
        }
        self.points.push_back(at);
    }

    pub fn clear(&mut self) {
        self.points.clear();
        self.trip = None;
    }

    pub fn iter(&self) -> impl Iterator<Item = Coord> + '_ {
        self.points.iter().copied()
    }

    #[cfg(test)]
    pub fn len(&self) -> usize {
        self.points.len()
    }
}

// ---- pure: touches to gestures ----------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Phase {
    Start,
    Move,
    End,
    Cancel,
}

impl From<TouchPhase> for Phase {
    fn from(phase: TouchPhase) -> Self {
        match phase {
            TouchPhase::Started => Self::Start,
            TouchPhase::Moved => Self::Move,
            TouchPhase::Ended => Self::End,
            TouchPhase::Cancelled => Self::Cancel,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Gesture {
    /// The content follows the finger by this much.
    Pan {
        dx: f32,
        dy: f32,
    },
    /// Scale by `factor` (above 1 zooms in) about `focal`.
    Zoom {
        factor: f64,
        focal: (f32, f32),
    },
    DoubleTap {
        at: (f32, f32),
    },
}

/// Turns raw touches into gestures. Positions are whatever space the caller
/// uses, as long as it is the same throughout; times are milliseconds.
///
/// A single tap is not reported by [`Gestures::touch`]: it could still be the
/// first half of a double tap. [`Gestures::due_tap`] hands it over once the
/// double-tap window has passed.
#[derive(Default)]
pub struct Gestures {
    touches: Vec<(u64, (f32, f32))>,
    start: (f32, f32),
    /// Where the last pan delta ended.
    anchor: (f32, f32),
    /// The sequence moved or used two fingers: it is not a tap.
    dragging: bool,
    pending: Option<((f32, f32), u64)>,
    last_touch_ms: Option<u64>,
}

impl Gestures {
    pub fn touch(&mut self, id: u64, phase: Phase, at: (f32, f32), now_ms: u64) -> Option<Gesture> {
        self.last_touch_ms = Some(now_ms);
        match phase {
            Phase::Start => {
                if self.touches.len() >= 2 || self.touches.iter().any(|t| t.0 == id) {
                    return None;
                }
                self.touches.push((id, at));
                if self.touches.len() == 1 {
                    self.start = at;
                    self.anchor = at;
                    self.dragging = false;
                } else {
                    self.dragging = true;
                }
                None
            }
            Phase::Move => {
                let i = self.touches.iter().position(|t| t.0 == id)?;
                let old = std::mem::replace(&mut self.touches[i].1, at);
                if self.touches.len() == 2 {
                    let other = self.touches[1 - i].1;
                    let before = dist(old, other);
                    if before < MIN_PINCH_PX {
                        return None;
                    }
                    return Some(Gesture::Zoom {
                        factor: f64::from(dist(at, other) / before),
                        focal: ((at.0 + other.0) / 2.0, (at.1 + other.1) / 2.0),
                    });
                }
                if !self.dragging && dist(at, self.start) > TAP_SLOP {
                    self.dragging = true;
                }
                if !self.dragging {
                    return None;
                }
                let (dx, dy) = (at.0 - self.anchor.0, at.1 - self.anchor.1);
                self.anchor = at;
                (dx != 0.0 || dy != 0.0).then_some(Gesture::Pan { dx, dy })
            }
            Phase::End | Phase::Cancel => {
                let i = self.touches.iter().position(|t| t.0 == id)?;
                if phase == Phase::Cancel {
                    self.touches.clear();
                    self.dragging = false;
                    return None;
                }
                let tap =
                    self.touches.len() == 1 && !self.dragging && dist(at, self.start) <= TAP_SLOP;
                self.touches.remove(i);
                if let Some(&(_, left)) = self.touches.first() {
                    // One finger of a pinch lifted: the other one pans on.
                    self.anchor = left;
                    self.start = left;
                    self.dragging = true;
                }
                if tap {
                    self.register_tap(at, now_ms)
                } else {
                    None
                }
            }
        }
    }

    /// A mouse click (desktop). The click that follows a touch is that
    /// touch's echo and is ignored.
    pub fn click(&mut self, at: (f32, f32), now_ms: u64) -> Option<Gesture> {
        if self
            .last_touch_ms
            .is_some_and(|t| now_ms.saturating_sub(t) < CLICK_ECHO_MS)
        {
            return None;
        }
        self.register_tap(at, now_ms)
    }

    fn register_tap(&mut self, at: (f32, f32), now_ms: u64) -> Option<Gesture> {
        if let Some((first, when)) = self.pending
            && now_ms.saturating_sub(when) <= DOUBLE_TAP_MS
            && dist(first, at) <= DOUBLE_TAP_SLOP_PX
        {
            self.pending = None;
            return Some(Gesture::DoubleTap { at });
        }
        self.pending = Some((at, now_ms));
        None
    }

    /// The single tap whose double-tap window has run out, once.
    pub fn due_tap(&mut self, now_ms: u64) -> Option<(f32, f32)> {
        let (at, when) = self.pending?;
        if now_ms.saturating_sub(when) < DOUBLE_TAP_MS {
            return None;
        }
        self.pending = None;
        Some(at)
    }
}

/// Whether a point `(x, y)` from the map's top-left corner is on the zoom
/// buttons (with a little slop), which handle their own taps.
pub fn over_controls(size: (f32, f32), (x, y): (f32, f32)) -> bool {
    const SLOP: f32 = 6.0;
    let column = CONTROL_COUNT * CONTROL_SIZE + (CONTROL_COUNT - 1.0) * CONTROL_GAP;
    x >= size.0 - CONTROL_MARGIN - CONTROL_SIZE - SLOP && y <= CONTROL_MARGIN + column + SLOP
}

// ---- pure: the scene ---------------------------------------------------------------------

/// Everything the map shows, in pixels from the view centre.
#[derive(Debug, Default, PartialEq)]
pub struct Scene {
    /// Your position and the radius of its accuracy disc.
    pub position: Option<((f32, f32), f32)>,
    pub destination: Option<(f32, f32)>,
    pub ring: Option<((f32, f32), f32)>,
    pub trail: Vec<(f32, f32)>,
    pub stops: Vec<(f32, f32)>,
    pub line: Vec<(f32, f32)>,
}

pub struct SceneInput<'a> {
    pub viewport: Viewport,
    pub size: (f32, f32),
    pub position: Option<(Coord, f64)>,
    pub destination: Option<Coord>,
    pub rule: Option<AlarmRule>,
    pub trail: &'a Breadcrumb,
    pub feed: Option<&'a Feed>,
    pub pattern: Option<u32>,
}

impl Scene {
    pub fn build(input: &SceneInput<'_>) -> Self {
        let vp = &input.viewport;
        let mpp = vp.metres_per_px;
        let metres = |m: f64| ((m / mpp) as f32).clamp(0.0, MAX_RADIUS_PX);
        let ring = input.destination.zip(input.rule).map(|(dest, rule)| {
            let (centre, m) = alarm_ring(dest, rule);
            (vp.project(centre), metres(m))
        });
        let trail: Vec<_> = input.trail.iter().map(|c| vp.project(c)).collect();
        let (stops, line) = match input.feed {
            Some(feed) => {
                let stops = stops_in_view(&feed.stops, vp, input.size.0, input.size.1, STOP_CAP)
                    .into_iter()
                    .map(|i| vp.project(feed.stops[i as usize].coord))
                    .collect();
                let line: Vec<_> = input
                    .pattern
                    .and_then(|p| feed.patterns.get(p as usize))
                    .map(|p| {
                        p.stops
                            .iter()
                            .filter_map(|&s| feed.stops.get(s as usize))
                            .map(|s| vp.project(s.coord))
                            .collect()
                    })
                    .unwrap_or_default();
                (stops, line)
            }
            None => (Vec::new(), Vec::new()),
        };
        Self {
            position: input
                .position
                .map(|(c, accuracy)| (vp.project(c), metres(accuracy))),
            destination: input.destination.map(|c| vp.project(c)),
            ring,
            trail: thin_polyline(&trail, TRAIL_MIN_PX),
            stops,
            line: thin_polyline(&line, LINE_MIN_PX),
        }
    }
}

// ---- painting ----------------------------------------------------------------------------

/// The colours the map uses, from the theme tokens.
#[derive(Clone, Copy)]
pub struct Palette {
    plate: Hsla,
    border: Hsla,
    you: Hsla,
    accuracy: Hsla,
    trail: Hsla,
    line: Hsla,
    stop: Hsla,
    destination: Hsla,
    ring: Hsla,
    halo: Hsla,
}

fn with_alpha(color: Hsla, a: f32) -> Hsla {
    Hsla { a, ..color }
}

impl Palette {
    pub fn new(theme: Theme) -> Self {
        let c = theme::colors(theme);
        Self {
            plate: c.secondary_background,
            border: c.hairline,
            you: c.accent,
            accuracy: with_alpha(c.accent, 0.16),
            trail: with_alpha(c.accent, 0.6),
            line: c.orange,
            stop: with_alpha(c.secondary_label, 0.85),
            destination: c.red,
            ring: c.red,
            halo: gpui::white(),
        }
    }
}

/// Adds a circle to `path` as two half arcs.
fn add_circle(path: &mut PathBuilder, (x, y): (f32, f32), r: f32, close: bool) {
    let radii = point(px(r), px(r));
    path.move_to(point(px(x - r), px(y)));
    path.arc_to(radii, px(0.0), false, true, point(px(x + r), px(y)));
    path.arc_to(radii, px(0.0), false, true, point(px(x - r), px(y)));
    if close {
        path.close();
    }
}

fn fill_circle(window: &mut Window, at: (f32, f32), r: f32, color: Hsla) {
    if r < 0.25 {
        return;
    }
    let mut path = PathBuilder::fill();
    add_circle(&mut path, at, r, true);
    if let Ok(path) = path.build() {
        window.paint_path(path, color);
    }
}

fn stroke_line(window: &mut Window, points: &[(f32, f32)], width: f32, color: Hsla) {
    let Some((first, rest)) = points.split_first() else {
        return;
    };
    if rest.is_empty() {
        return;
    }
    let mut path = PathBuilder::stroke(px(width));
    path.move_to(point(px(first.0), px(first.1)));
    for p in rest {
        path.line_to(point(px(p.0), px(p.1)));
    }
    if let Ok(path) = path.build() {
        window.paint_path(path, color);
    }
}

/// A teardrop pin whose tip is at `tip`.
fn paint_pin(window: &mut Window, tip: (f32, f32), pal: &Palette) {
    let head = (tip.0, tip.1 - 17.0);
    let mut spike = PathBuilder::fill();
    spike.add_polygon(
        &[
            point(px(head.0 - 7.0), px(head.1 + 4.0)),
            point(px(tip.0), px(tip.1)),
            point(px(head.0 + 7.0), px(head.1 + 4.0)),
        ],
        true,
    );
    if let Ok(path) = spike.build() {
        window.paint_path(path, pal.destination);
    }
    fill_circle(window, head, 9.0, pal.destination);
    fill_circle(window, head, 3.5, pal.halo);
}

/// Paints `scene` into `bounds`. Scene points are from the view centre.
fn paint_scene(scene: &Scene, bounds: Bounds<Pixels>, pal: &Palette, window: &mut Window) {
    window.paint_quad(quad(
        bounds,
        Corners::all(radius::CARD),
        pal.plate,
        Edges::all(px(1.0)),
        pal.border,
        BorderStyle::default(),
    ));
    let centre = bounds.center();
    let to_window = |p: (f32, f32)| (f32::from(centre.x) + p.0, f32::from(centre.y) + p.1);
    let to_window_all = |points: &[(f32, f32)]| -> Vec<(f32, f32)> {
        points.iter().map(|&p| to_window(p)).collect()
    };

    // The chosen line under everything else, then the stops on it.
    stroke_line(window, &to_window_all(&scene.line), 3.5, pal.line);

    if !scene.stops.is_empty() {
        let mut dots = PathBuilder::fill();
        for &p in &scene.stops {
            add_circle(&mut dots, to_window(p), 3.5, true);
        }
        if let Ok(path) = dots.build() {
            window.paint_path(path, pal.stop);
        }
    }

    if let Some((at, r)) = scene.ring
        && r >= 2.0
    {
        let (x, y) = to_window(at);
        let dash = px(8.0);
        let mut ring = PathBuilder::stroke(px(2.0)).dash_array(&[dash, px(6.0)]);
        add_circle(&mut ring, (x, y), r, false);
        if let Ok(path) = ring.build() {
            window.paint_path(path, pal.ring);
        }
    }

    stroke_line(window, &to_window_all(&scene.trail), 3.0, pal.trail);

    if let Some(at) = scene.destination {
        paint_pin(window, to_window(at), pal);
    }

    if let Some((at, accuracy)) = scene.position {
        let at = to_window(at);
        fill_circle(window, at, accuracy, pal.accuracy);
        fill_circle(window, at, 9.0, pal.halo);
        fill_circle(window, at, 6.5, pal.you);
    }
}

// ---- state and element glue --------------------------------------------------------------

/// What the map remembers between frames.
pub struct MapState {
    /// The view. While `follow` is set it is recomputed every frame.
    pub viewport: Viewport,
    /// The view fits you, the destination and the ring until the user moves it.
    pub follow: bool,
    pub gestures: Gestures,
    pub trail: Breadcrumb,
    /// The last fix, kept after the trip for the stop search.
    pub position: Option<(Coord, f64)>,
    /// The chosen line, drawn as a polyline.
    pub pattern: Option<u32>,
    /// Where the surface was last painted, in window coordinates.
    pub bounds: Rc<Cell<Bounds<Pixels>>>,
    tap_timer: Option<Task<()>>,
}

impl Default for MapState {
    fn default() -> Self {
        Self {
            viewport: Viewport::new(Coord::new(0.0, 0.0), 2_000.0),
            follow: true,
            gestures: Gestures::default(),
            trail: Breadcrumb::default(),
            position: None,
            pattern: None,
            bounds: Rc::default(),
            tap_timer: None,
        }
    }
}

impl MapState {
    fn size(&self) -> (f32, f32) {
        let b = self.bounds.get();
        let (w, h) = (f32::from(b.size.width), f32::from(b.size.height));
        if w > 1.0 && h > 1.0 {
            (w, h)
        } else {
            DEFAULT_SIZE
        }
    }

    /// Window position to pixels from the view centre.
    fn to_view(&self, window_pos: (f32, f32)) -> (f32, f32) {
        let c = self.bounds.get().center();
        (window_pos.0 - f32::from(c.x), window_pos.1 - f32::from(c.y))
    }

    /// The user takes the view over from the automatic fit.
    fn take_over(&mut self) {
        self.follow = false;
    }

    pub fn reset(&mut self) {
        self.follow = true;
        self.trail.clear();
        self.pattern = None;
    }
}

/// One thing the surface saw, in window coordinates.
enum MapInput {
    Touch {
        id: u64,
        phase: Phase,
        at: (f32, f32),
    },
    Click {
        at: (f32, f32),
    },
}

/// The invisible layer that reports touches (with their ids, for pinch) and
/// mouse clicks, and keeps the page from scrolling under a finger on the map.
fn input_surface(
    bounds_out: Rc<Cell<Bounds<Pixels>>>,
    on_input: impl Fn(MapInput, &mut App) + 'static,
) -> impl IntoElement {
    let on_input = Rc::new(on_input);
    canvas(
        |_, _, _| (),
        move |bounds, (), window, _| {
            bounds_out.set(bounds);
            let size = (f32::from(bounds.size.width), f32::from(bounds.size.height));
            let on_controls = move |p: Point<Pixels>| {
                over_controls(
                    size,
                    (
                        f32::from(p.x - bounds.origin.x),
                        f32::from(p.y - bounds.origin.y),
                    ),
                )
            };
            let touch = Rc::clone(&on_input);
            window.on_mouse_event(move |event: &TouchEvent, phase, window, cx| {
                if !phase.bubble() {
                    return;
                }
                if event.phase == TouchPhase::Started {
                    if !bounds.contains(&event.position) || on_controls(event.position) {
                        return;
                    }
                    // The finger is the map's, not the page's.
                    window.prevent_default();
                }
                touch(
                    MapInput::Touch {
                        id: event.id.0,
                        phase: event.phase.into(),
                        at: (f32::from(event.position.x), f32::from(event.position.y)),
                    },
                    cx,
                );
            });
            let click = Rc::clone(&on_input);
            window.on_mouse_event(move |event: &MouseDownEvent, phase, _, cx| {
                if phase.bubble()
                    && event.button == MouseButton::Left
                    && bounds.contains(&event.position)
                    && !on_controls(event.position)
                {
                    click(
                        MapInput::Click {
                            at: (f32::from(event.position.x), f32::from(event.position.y)),
                        },
                        cx,
                    );
                }
            });
        },
    )
    .absolute()
    .top_0()
    .left_0()
    .size_full()
}

fn control_button(theme: Theme, label: &'static str) -> gpui::Div {
    let colors = theme::colors(theme);
    flat_glass(theme, None)
        .size(px(CONTROL_SIZE))
        .rounded_full()
        .flex()
        .items_center()
        .justify_center()
        .text_size(typography::HEADLINE.size)
        .font_weight(typography::HEADLINE.weight)
        .text_color(colors.accent)
        .child(label)
}

impl StopsApp {
    /// What the map is about: your position, where you are going, and the
    /// rule whose ring to draw.
    fn map_subjects(&self) -> (Option<Coord>, Option<AlarmRule>) {
        match (&self.trip, &self.dest) {
            (Some(trip), _) => (Some(trip.trip.destination), Some(trip.trip.rule)),
            (None, Some(dest)) => (
                Some(dest.coord),
                Some(dest.rule.unwrap_or_else(|| self.rule.to_rule())),
            ),
            (None, None) => (None, None),
        }
    }

    /// Something to show: a fix, a destination, or a feed to browse.
    fn map_has_content(&self) -> bool {
        self.map.position.is_some()
            || self.trip.is_some()
            || self.dest.is_some()
            || self.gtfs.is_some()
    }

    /// The map card for the Trip tab, or `None` while there is nothing to show.
    pub(crate) fn map_card(&mut self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        if !self.map_has_content() {
            return None;
        }
        let size = self.map.size();
        let (destination, rule) = self.map_subjects();
        let position = self.map.position;
        if self.map.follow {
            let ring = destination.zip(rule).map(|(d, r)| alarm_ring(d, r));
            let fitted = fit_view(position.map(|p| p.0), destination, ring, size).or_else(|| {
                // Browsing a feed with no fix and no destination: start at its first stop.
                self.gtfs
                    .as_ref()
                    .and_then(|f| f.stops.first())
                    .map(|s| Viewport::new(s.coord, 12.0))
            });
            if let Some(view) = fitted {
                self.map.viewport = view;
            }
        }
        let viewport = self.map.viewport;
        let scene = Scene::build(&SceneInput {
            viewport,
            size,
            position,
            destination,
            rule,
            trail: &self.map.trail,
            feed: self.gtfs.as_deref(),
            pattern: self.map.pattern,
        });
        let palette = Palette::new(theme);
        let colors = theme::colors(theme);
        let (bar_px, bar_label) = scale_bar(viewport.metres_per_px);

        let entity = cx.entity();
        let input = entity.clone();
        let surface = input_surface(self.map.bounds.clone(), move |event, cx| {
            input.update(cx, |this, cx| this.map_input(event, cx));
        });
        let pinch = entity;
        let controls = div()
            .absolute()
            .top(px(CONTROL_MARGIN))
            .right(px(CONTROL_MARGIN))
            .flex()
            .flex_col()
            .gap(px(CONTROL_GAP))
            .child(
                control_button(theme, "+")
                    .id("map-zoom-in")
                    .on_click(cx.listener(|this, _, _, cx| this.map_zoom(2.0, cx))),
            )
            .child(
                control_button(theme, "-")
                    .id("map-zoom-out")
                    .on_click(cx.listener(|this, _, _, cx| this.map_zoom(0.5, cx))),
            )
            .child(
                control_button(theme, "Fit")
                    .id("map-fit")
                    .text_size(typography::FOOTNOTE.size)
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.map.follow = true;
                        cx.notify();
                    })),
            );
        let scale = div()
            .absolute()
            .left(px(14.0))
            .bottom(px(10.0))
            .flex()
            .flex_col()
            .gap(px(2.0))
            .child(
                div()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child(bar_label),
            )
            .child(
                div()
                    .w(px(bar_px))
                    .h(px(2.0))
                    .rounded_full()
                    .bg(colors.secondary_label),
            );
        Some(
            div()
                .px(px(16.0))
                .child(
                    div()
                        .id("map")
                        .relative()
                        .h(px(MAP_HEIGHT))
                        .w_full()
                        .rounded(radius::CARD)
                        .overflow_hidden()
                        .on_pinch(move |event, _, cx| {
                            pinch.update(cx, |this, cx| {
                                this.map_zoom(1.0 + f64::from(event.delta), cx);
                            });
                        })
                        .child(
                            canvas(
                                |_, _, _| (),
                                move |bounds, (), window, _| {
                                    paint_scene(&scene, bounds, &palette, window);
                                },
                            )
                            .size_full(),
                        )
                        .child(surface)
                        .child(scale)
                        .child(controls),
                )
                .into_any_element(),
        )
    }

    fn map_zoom(&mut self, factor: f64, cx: &mut Context<Self>) {
        if !factor.is_finite() || factor <= 0.0 {
            return;
        }
        self.map.take_over();
        self.map.viewport.zoom_about(factor, (0.0, 0.0));
        cx.notify();
    }

    fn map_input(&mut self, event: MapInput, cx: &mut Context<Self>) {
        let now = now_ms().max(0) as u64;
        let (gesture, touch_ended) = match event {
            MapInput::Touch { id, phase, at } => (
                self.map.gestures.touch(id, phase, at, now),
                phase == Phase::End,
            ),
            MapInput::Click { at } => (self.map.gestures.click(at, now), true),
        };
        if let Some(gesture) = gesture {
            self.apply_gesture(gesture);
            cx.notify();
        }
        if touch_ended {
            // A single tap waits out the double-tap window before it acts.
            self.map.tap_timer = Some(cx.spawn(async move |this, cx| {
                cx.background_executor()
                    .timer(Duration::from_millis(DOUBLE_TAP_MS + 30))
                    .await;
                this.update(cx, |this, cx| {
                    if let Some(at) = this.map.gestures.due_tap(now_ms().max(0) as u64) {
                        let at = this.map.to_view(at);
                        this.map_tap(at, cx);
                    }
                })
                .ok();
            }));
        }
    }

    fn apply_gesture(&mut self, gesture: Gesture) {
        match gesture {
            Gesture::Pan { dx, dy } => {
                self.map.take_over();
                self.map.viewport.pan(dx, dy);
            }
            Gesture::Zoom { factor, focal } => {
                self.map.take_over();
                let focal = self.map.to_view(focal);
                self.map.viewport.zoom_about(factor, focal);
            }
            Gesture::DoubleTap { at } => {
                self.map.take_over();
                let at = self.map.to_view(at);
                self.map.viewport.zoom_about(2.0, at);
            }
        }
    }

    /// A tap at `at` (pixels from the view centre): the stop under it opens
    /// in the sheet; on empty ground the tapped point is offered as the
    /// destination. The map only looks while a trip runs.
    fn map_tap(&mut self, at: (f32, f32), cx: &mut Context<Self>) {
        if self.trip.is_some() || self.sheet.is_open() || self.alarm.is_some() {
            return;
        }
        let viewport = self.map.viewport;
        let size = self.map.size();
        let stop = self
            .gtfs
            .as_ref()
            .and_then(|feed| pick_stop(feed, &viewport, size, at, PICK_RADIUS_PX));
        match stop {
            Some(stop) => self.open_stop(stop, cx),
            None => {
                let coord = viewport.unproject(at);
                self.confirm_destination(Destination::new(coord_text(coord), coord), cx);
            }
        }
    }

    /// Opens the sheet on a stop: its lines and "N stops before".
    pub(crate) fn open_stop(&mut self, stop: u32, cx: &mut Context<Self>) {
        if self
            .gtfs
            .as_ref()
            .is_none_or(|feed| feed.stops.get(stop as usize).is_none())
        {
            return;
        }
        self.stop_pattern = None;
        self.sheet_mode = SheetMode::Stop(stop);
        self.sheet.open();
        cx.notify();
    }

    /// A fix arrived: move the dot and extend the trail.
    pub(crate) fn map_fix(&mut self, at: rovsti_geo::FixPos) {
        let coord = Coord::new(at.lat, at.lon);
        self.map.position = Some((coord, at.accuracy_m));
        let trip = self.trip.as_ref().map(|t| t.id);
        self.map.trail.record(trip, coord, at.accuracy_m);
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use rovsti_geo::{Pattern, Route};

    use super::*;

    const IST: Coord = Coord::new(41.0, 29.0);

    fn stop_at(lat: f64, lon: f64) -> Stop {
        Stop {
            id: String::new(),
            name: String::new(),
            coord: Coord::new(lat, lon),
            parent: None,
        }
    }

    /// A stop `east`/`north` metres from `IST`.
    fn near(east: f64, north: f64) -> Stop {
        let dlat = (north / EARTH_RADIUS_M).to_degrees();
        let dlon = (east / (EARTH_RADIUS_M * IST.lat.to_radians().cos())).to_degrees();
        stop_at(IST.lat + dlat, IST.lon + dlon)
    }

    // -- which stops are in view --

    #[test]
    fn only_stops_inside_the_box_are_in_view() {
        // 10 m/px on a 400 x 300 box: +-2 km east-west, +-1.5 km north-south.
        let vp = Viewport::new(IST, 10.0);
        let stops = vec![
            near(0.0, 0.0),
            near(1_900.0, 1_400.0),
            near(-1_900.0, -1_400.0),
            near(2_300.0, 0.0),
            near(0.0, 1_700.0),
            near(-5_000.0, 5_000.0),
        ];
        assert_eq!(stops_in_view(&stops, &vp, 400.0, 300.0, 300), [0, 1, 2]);
        // A bigger box takes more in; panning moves the window.
        assert_eq!(
            stops_in_view(&stops, &vp, 500.0, 400.0, 300),
            [0, 1, 2, 3, 4]
        );
        let mut panned = vp;
        panned.pan(-100.0, 0.0);
        assert_eq!(stops_in_view(&stops, &panned, 400.0, 300.0, 300), [0, 1, 3]);
    }

    #[test]
    fn the_cap_keeps_the_stops_nearest_the_centre() {
        let vp = Viewport::new(IST, 10.0);
        let stops: Vec<Stop> = (0..50).map(|i| near(f64::from(i) * 20.0, 0.0)).collect();
        // Stop 0 is at the centre; the nearest five are 0..5 (all to the east).
        assert_eq!(
            stops_in_view(&stops, &vp, 2_000.0, 300.0, 5),
            [0, 1, 2, 3, 4]
        );
        assert_eq!(
            stops_in_view(&stops, &vp, 2_000.0, 300.0, 0),
            Vec::<u32>::new()
        );
        let all = stops_in_view(&stops, &vp, 2_000.0, 300.0, STOP_CAP);
        assert_eq!(all.len(), 50);
        // The cap holds on a big feed, and the result stays sorted.
        let many: Vec<Stop> = (0..2_000)
            .map(|i| near(f64::from(i % 40) * 10.0, f64::from(i / 40) * 10.0))
            .collect();
        let hits = stops_in_view(&many, &vp, 2_000.0, 1_000.0, STOP_CAP);
        assert_eq!(hits.len(), STOP_CAP);
        assert!(hits.windows(2).all(|w| w[0] < w[1]));
    }

    #[test]
    fn picking_prefers_the_nearest_drawn_stop_then_the_feed() {
        let feed = Feed::new(
            vec![near(0.0, 0.0), near(100.0, 0.0), near(130.0, 0.0)],
            vec![],
            vec![],
            0,
        );
        // 10 m/px: stops sit 0, 10 and 13 px right of the centre.
        let vp = Viewport::new(IST, 10.0);
        let size = (400.0, 300.0);
        assert_eq!(pick_stop(&feed, &vp, size, (11.0, 0.0), 40.0), Some(1));
        assert_eq!(pick_stop(&feed, &vp, size, (14.0, 2.0), 40.0), Some(2));
        assert_eq!(pick_stop(&feed, &vp, size, (-100.0, 0.0), 40.0), None);
    }

    // -- polyline thinning --

    #[test]
    fn thinning_drops_crowded_vertices_and_keeps_the_ends() {
        let line: Vec<(f32, f32)> = (0..=20).map(|i| (i as f32, 0.0)).collect();
        let thin = thin_polyline(&line, 5.0);
        assert_eq!(thin.first(), Some(&(0.0, 0.0)));
        assert_eq!(thin.last(), Some(&(20.0, 0.0)));
        assert_eq!(
            thin,
            [
                (0.0, 0.0),
                (5.0, 0.0),
                (10.0, 0.0),
                (15.0, 0.0),
                (20.0, 0.0)
            ]
        );
        // Nothing to thin when spaced wide enough.
        assert_eq!(
            thin_polyline(&[(0.0, 0.0), (10.0, 0.0), (20.0, 0.0)], 5.0).len(),
            3
        );
        // An end that crowds its neighbour replaces it rather than duplicating.
        let thin = thin_polyline(&[(0.0, 0.0), (10.0, 0.0), (11.0, 0.0)], 5.0);
        assert_eq!(thin, [(0.0, 0.0), (11.0, 0.0)]);
    }

    #[test]
    fn thinning_handles_short_input() {
        assert!(thin_polyline(&[], 5.0).is_empty());
        assert_eq!(thin_polyline(&[(1.0, 2.0)], 5.0), [(1.0, 2.0)]);
        assert_eq!(
            thin_polyline(&[(1.0, 2.0), (1.0, 2.0)], 5.0),
            [(1.0, 2.0), (1.0, 2.0)]
        );
    }

    // -- the breadcrumb --

    #[test]
    fn the_breadcrumb_keeps_the_last_500_usable_fixes() {
        let mut trail = Breadcrumb::default();
        for i in 0..(BREADCRUMB_CAP + 40) {
            trail.record(Some(1), Coord::new(41.0 + i as f64 * 0.001, 29.0), 10.0);
        }
        assert_eq!(trail.len(), BREADCRUMB_CAP);
        assert_eq!(trail.iter().last().unwrap().lat, 41.0 + 539.0 * 0.001);
        assert_eq!(trail.iter().next().unwrap().lat, 41.0 + 40.0 * 0.001);
    }

    #[test]
    fn poor_and_idle_fixes_leave_no_mark() {
        let mut trail = Breadcrumb::default();
        trail.record(Some(1), IST, 10.0);
        trail.record(Some(1), Coord::new(41.5, 29.0), 900.0);
        trail.record(Some(1), Coord::new(41.5, 29.0), f64::NAN);
        trail.record(Some(1), Coord::new(IST.lat + 0.00001, IST.lon), 10.0);
        assert_eq!(trail.len(), 1);
        trail.record(Some(1), Coord::new(41.001, 29.0), 10.0);
        assert_eq!(trail.len(), 2);
    }

    #[test]
    fn a_new_trip_starts_a_new_trail() {
        let mut trail = Breadcrumb::default();
        trail.record(Some(1), IST, 10.0);
        trail.record(Some(1), Coord::new(41.001, 29.0), 10.0);
        trail.record(Some(2), Coord::new(41.002, 29.0), 10.0);
        assert_eq!(trail.len(), 1);
        trail.clear();
        assert_eq!(trail.len(), 0);
    }

    // -- gestures --

    fn tap(g: &mut Gestures, at: (f32, f32), t: u64) -> Option<Gesture> {
        assert_eq!(g.touch(1, Phase::Start, at, t), None);
        g.touch(1, Phase::End, at, t + 60)
    }

    #[test]
    fn a_single_tap_waits_for_the_double_tap_window() {
        let mut g = Gestures::default();
        assert_eq!(tap(&mut g, (50.0, 60.0), 1_000), None);
        assert_eq!(g.due_tap(1_100), None);
        assert_eq!(g.due_tap(1_400), Some((50.0, 60.0)));
        // Delivered once.
        assert_eq!(g.due_tap(1_500), None);
    }

    #[test]
    fn two_quick_taps_are_a_double_tap_and_no_single_tap() {
        let mut g = Gestures::default();
        assert_eq!(tap(&mut g, (50.0, 60.0), 1_000), None);
        assert_eq!(
            tap(&mut g, (55.0, 62.0), 1_200),
            Some(Gesture::DoubleTap { at: (55.0, 62.0) })
        );
        assert_eq!(g.due_tap(2_000), None);
        // Too slow, or too far away: two single taps.
        assert_eq!(tap(&mut g, (50.0, 60.0), 5_000), None);
        assert_eq!(tap(&mut g, (50.0, 60.0), 5_600), None);
        assert_eq!(tap(&mut g, (300.0, 60.0), 5_700), None);
    }

    #[test]
    fn dragging_pans_by_the_finger_travel_and_is_no_tap() {
        let mut g = Gestures::default();
        g.touch(1, Phase::Start, (100.0, 100.0), 0);
        // Within the slop nothing moves.
        assert_eq!(g.touch(1, Phase::Move, (104.0, 100.0), 10), None);
        // Past the slop the whole travel so far is applied at once...
        assert_eq!(
            g.touch(1, Phase::Move, (120.0, 110.0), 20),
            Some(Gesture::Pan { dx: 20.0, dy: 10.0 })
        );
        // ...then each step.
        assert_eq!(
            g.touch(1, Phase::Move, (125.0, 110.0), 30),
            Some(Gesture::Pan { dx: 5.0, dy: 0.0 })
        );
        assert_eq!(g.touch(1, Phase::End, (125.0, 110.0), 40), None);
        assert_eq!(g.due_tap(10_000), None);
    }

    #[test]
    fn two_fingers_zoom_about_their_midpoint() {
        let mut g = Gestures::default();
        g.touch(1, Phase::Start, (100.0, 100.0), 0);
        g.touch(2, Phase::Start, (200.0, 100.0), 5);
        // The second finger moves out: the gap goes from 100 to 150.
        assert_eq!(
            g.touch(2, Phase::Move, (250.0, 100.0), 10),
            Some(Gesture::Zoom {
                factor: 1.5,
                focal: (175.0, 100.0)
            })
        );
        // Pinching in.
        let Some(Gesture::Zoom { factor, .. }) = g.touch(1, Phase::Move, (150.0, 100.0), 20) else {
            panic!("expected a zoom");
        };
        assert!((factor - 100.0 / 150.0).abs() < 1e-6);
        // Lifting one finger is no tap; the other one pans on from where it is.
        assert_eq!(g.touch(1, Phase::End, (150.0, 100.0), 30), None);
        assert_eq!(
            g.touch(2, Phase::Move, (260.0, 100.0), 40),
            Some(Gesture::Pan { dx: 10.0, dy: 0.0 })
        );
        assert_eq!(g.touch(2, Phase::End, (260.0, 100.0), 50), None);
        assert_eq!(g.due_tap(10_000), None);
    }

    #[test]
    fn a_third_finger_and_unknown_touches_are_ignored() {
        let mut g = Gestures::default();
        g.touch(1, Phase::Start, (0.0, 0.0), 0);
        g.touch(2, Phase::Start, (100.0, 0.0), 0);
        assert_eq!(g.touch(3, Phase::Start, (50.0, 50.0), 0), None);
        assert_eq!(g.touch(3, Phase::Move, (60.0, 50.0), 1), None);
        assert_eq!(g.touch(3, Phase::End, (60.0, 50.0), 2), None);
        assert_eq!(g.touch(9, Phase::Move, (1.0, 1.0), 3), None);
        // Fingers on top of each other give no zoom ratio.
        let mut g = Gestures::default();
        g.touch(1, Phase::Start, (0.0, 0.0), 0);
        g.touch(2, Phase::Start, (4.0, 0.0), 0);
        assert_eq!(g.touch(2, Phase::Move, (40.0, 0.0), 1), None);
    }

    #[test]
    fn a_cancelled_touch_is_never_a_tap() {
        let mut g = Gestures::default();
        g.touch(1, Phase::Start, (10.0, 10.0), 0);
        assert_eq!(g.touch(1, Phase::Cancel, (10.0, 10.0), 5), None);
        assert_eq!(g.due_tap(10_000), None);
        // The next sequence starts clean.
        assert_eq!(tap(&mut g, (10.0, 10.0), 100), None);
        assert_eq!(g.due_tap(1_000), Some((10.0, 10.0)));
    }

    #[test]
    fn a_click_right_after_a_touch_is_its_echo() {
        let mut g = Gestures::default();
        tap(&mut g, (10.0, 10.0), 1_000);
        assert_eq!(g.click((10.0, 10.0), 1_100), None);
        // The echo did not turn the tap into a double tap.
        assert_eq!(g.due_tap(1_400), Some((10.0, 10.0)));
        // A desktop mouse never touches: clicks count, twice is a double click.
        let mut mouse = Gestures::default();
        assert_eq!(mouse.click((5.0, 5.0), 100), None);
        assert_eq!(
            mouse.click((5.0, 5.0), 250),
            Some(Gesture::DoubleTap { at: (5.0, 5.0) })
        );
    }

    #[test]
    fn the_zoom_buttons_are_not_the_maps() {
        let size = (370.0, 340.0);
        assert!(over_controls(size, (350.0, 20.0)));
        assert!(over_controls(size, (330.0, 150.0)));
        assert!(!over_controls(size, (350.0, 200.0)));
        assert!(!over_controls(size, (100.0, 20.0)));
    }

    // -- the scene --

    fn scene_input<'a>(trail: &'a Breadcrumb, feed: Option<&'a Feed>) -> SceneInput<'a> {
        SceneInput {
            viewport: Viewport::new(IST, 10.0),
            size: (400.0, 300.0),
            position: None,
            destination: None,
            rule: None,
            trail,
            feed,
            pattern: None,
        }
    }

    #[test]
    fn the_scene_puts_things_where_the_viewport_does() {
        let mut trail = Breadcrumb::default();
        trail.record(Some(1), IST, 10.0);
        trail.record(Some(1), near(0.0, 100.0).coord, 10.0);
        let rule = AlarmRule::Radius { metres: 500.0 };
        let mut input = scene_input(&trail, None);
        input.position = Some((near(100.0, 0.0).coord, 25.0));
        input.destination = Some(near(0.0, 300.0).coord);
        input.rule = Some(rule);
        let scene = Scene::build(&input);

        let ((x, y), r) = scene.position.unwrap();
        assert!((x - 10.0).abs() < 0.01 && y.abs() < 0.01, "{x} {y}");
        assert!((r - 2.5).abs() < 1e-4);
        let (dx, dy) = scene.destination.unwrap();
        assert!(dx.abs() < 0.01 && (dy + 30.0).abs() < 0.01);
        let (ring_at, ring_r) = scene.ring.unwrap();
        assert_eq!(ring_at, (dx, dy));
        assert!((ring_r - 50.0).abs() < 1e-4);
        assert_eq!(scene.trail.len(), 2);
        assert!(scene.stops.is_empty() && scene.line.is_empty());
    }

    #[test]
    fn the_ring_of_a_stop_rule_sits_on_the_trigger_stop() {
        let trigger = near(200.0, 0.0).coord;
        let (at, metres) = alarm_ring(
            IST,
            AlarmRule::BeforeStop {
                trigger,
                metres: 150.0,
            },
        );
        assert_eq!((at, metres), (trigger, 150.0));
        assert_eq!(
            alarm_ring(IST, AlarmRule::Radius { metres: 300.0 }),
            (IST, 300.0)
        );
        assert_eq!(
            alarm_ring(
                IST,
                AlarmRule::Eta {
                    before: Duration::from_secs(120),
                    min_metres: 400.0
                }
            ),
            (IST, 400.0)
        );
    }

    #[test]
    fn feed_stops_and_the_chosen_line_are_drawn() {
        let feed = Feed::new(
            vec![
                near(0.0, 0.0),
                near(100.0, 0.0),
                near(9_000.0, 0.0),
                near(200.0, 0.0),
            ],
            vec![Route {
                id: "r".into(),
                short_name: "34".into(),
                long_name: String::new(),
                kind: 3,
            }],
            vec![Pattern {
                route: 0,
                headsign: "x".into(),
                stops: vec![0, 1, 3],
            }],
            0,
        );
        let trail = Breadcrumb::default();
        let mut input = scene_input(&trail, Some(&feed));
        let without = Scene::build(&input);
        // The far stop is out of view; the line is off until a pattern is chosen.
        assert_eq!(without.stops.len(), 3);
        assert!(without.line.is_empty());
        input.pattern = Some(0);
        let with = Scene::build(&input);
        assert_eq!(with.line.len(), 3);
        assert!((with.line[2].0 - 20.0).abs() < 0.01);
        // A pattern that is not in the feed draws nothing.
        input.pattern = Some(5);
        assert!(Scene::build(&input).line.is_empty());
    }

    #[test]
    fn huge_rings_and_discs_are_clamped() {
        let trail = Breadcrumb::default();
        let mut input = scene_input(&trail, None);
        input.position = Some((IST, 1.0e9));
        input.destination = Some(IST);
        input.rule = Some(AlarmRule::Radius { metres: 1.0e12 });
        let scene = Scene::build(&input);
        assert_eq!(scene.position.unwrap().1, MAX_RADIUS_PX);
        assert_eq!(scene.ring.unwrap().1, MAX_RADIUS_PX);
    }

    // -- the view --

    #[test]
    fn the_follow_view_holds_you_the_pin_and_the_ring() {
        let size = (370.0, 340.0);
        let pos = near(-800.0, -600.0).coord;
        let dest = near(900.0, 700.0).coord;
        let ring = Some((dest, 300.0));
        let vp = fit_view(Some(pos), Some(dest), ring, size).unwrap();
        for c in [pos, dest, near(900.0 + 300.0, 700.0 + 300.0).coord] {
            let (x, y) = vp.project(c);
            assert!(
                x.abs() <= size.0 / 2.0 && y.abs() <= size.1 / 2.0,
                "{x} {y}"
            );
        }
        // One lonely point is centred at a sensible zoom, nothing gives no view.
        let alone = fit_view(Some(pos), None, None, size).unwrap();
        assert_eq!(alone.metres_per_px, MIN_FIT_MPP);
        assert!(alone.project(pos).0.abs() < 0.01);
        assert!(fit_view(None, None, None, size).is_none());
        // A zero or bad ring adds nothing.
        let same = fit_view(Some(pos), None, Some((pos, f64::NAN)), size).unwrap();
        assert_eq!(same, alone);
    }

    #[test]
    fn the_scale_bar_uses_round_lengths() {
        let (px, label) = scale_bar(10.0);
        assert_eq!(label, "1 km");
        assert!((px - 100.0).abs() < 1e-3);
        let (px, label) = scale_bar(0.5);
        assert_eq!(label, "50 m");
        assert!((px - 100.0).abs() < 1e-3);
        let (_, label) = scale_bar(2_000.0);
        assert_eq!(label, "100 km");
        // Always short enough to fit.
        for mpp in [0.5, 1.7, 33.0, 400.0, 2_000.0] {
            assert!(scale_bar(mpp).0 <= 110.0, "{mpp}");
        }
    }
}
