//! The map: a vector "radar" drawn with `canvas` and `PathBuilder`, over vector
//! map tiles where they are loaded (the radar look shows where they are not).
//!
//! It shows where you are with the accuracy of the fix, the circle on AB,
//! the breadcrumb of this trip, the A and B pins, your saved stops as
//! orange rings and, after a transit import, the stops in view and the chosen line. A finger drags to pan, two
//! fingers (or a double tap) zoom, a tap picks the stop under it.
//!
//! Everything that decides something is pure and tested: which stops are in
//! view, how a polyline is thinned, how touches become gestures, what a scene
//! looks like in pixels. The painting and the element glue are thin.

use std::{
    cell::{Cell, RefCell},
    collections::VecDeque,
    hash::{DefaultHasher, Hash, Hasher},
    rc::Rc,
    sync::Arc,
    time::Duration,
};

use gpui::prelude::FluentBuilder as _;
use gpui::{
    AnyElement, App, BorderStyle, Bounds, Context, Corners, Edges, HapticFeedback, HitboxBehavior,
    Hsla, InteractiveElement, IntoElement, MouseButton, MouseDownEvent, MouseMoveEvent,
    MouseUpEvent, ParentElement, Path, PathBuilder, Pixels, Point, StatefulInteractiveElement,
    Styled, Task, TouchEvent, TouchPhase, Window, canvas, div, point, px, quad,
};
use rovsti_android::location;
use rovsti_geo::{
    Coord, Diameter, EARTH_RADIUS_M, Feed, History, Measure, SavedStop, SavedStops, Side, Stop,
    tiles::{
        ATTRIBUTION, ClassGeometry, DrawClass, MapStyle, Rgba8, Shape, TileCover, TileGeometry,
        TileLayer, build_layer, line_width, tiles_for_view,
    },
    view::Viewport,
};
use rovsti_ui::{
    components::{flat_glass, glass_pill},
    haptics, motion_tokens,
    spring::Spring,
    theme,
    tokens::{Theme, radius, typography},
    touch::TAP_SLOP,
};

use crate::{
    StopsApp,
    backend::{Preflight, Step},
    pick::{self, TapOutcome},
    picker::{Destination, Ends, SheetMode, Slot},
    snap,
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
/// A mouse sequence starting this soon after a touch is that touch's echo.
const CLICK_ECHO_MS: u64 = 700;
/// A finger that starts this close to a pin grabs it.
const PIN_GRAB_PX: f32 = 32.0;
/// The touch id the mouse uses.
const MOUSE_ID: u64 = u64::MAX;
/// Two fingers closer than this give no usable zoom ratio.
const MIN_PINCH_PX: f32 = 12.0;
/// Rings and dots beyond this many pixels are not worth tessellating.
const MAX_RADIUS_PX: f32 = 4_000.0;

pub const MAP_HEIGHT: f32 = 340.0;
/// The map is the main surface while no trip runs.
pub const IDLE_MAP_HEIGHT: f32 = 460.0;
const DEFAULT_SIZE: (f32, f32) = (370.0, MAP_HEIGHT);
const CONTROL_SIZE: f32 = 40.0;
const CONTROL_GAP: f32 = 8.0;
const CONTROL_MARGIN: f32 = 10.0;
const CONTROL_COUNT: f32 = 4.0;

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

/// A fingerprint of the saved stops (id and both ends), to tell when the
/// drawn rings need rebuilding.
pub fn saved_key(saved: &SavedStops) -> u64 {
    let mut hasher = DefaultHasher::new();
    for stop in &saved.stops {
        stop.id.hash(&mut hasher);
        for end in [&stop.a, &stop.b] {
            end.coord.lat.to_bits().hash(&mut hasher);
            end.coord.lon.to_bits().hash(&mut hasher);
        }
    }
    hasher.finish()
}

/// What a tap or a dropped pin at `at` (pixels from the view centre) means:
/// the GTFS stop within [`PICK_RADIUS_PX`] (its name, id and place), else the
/// raw point under the finger. Saved stops are not targets: a saved stop is a
/// pair of points, and neither end is a stop of its own.
pub fn snap_destination(
    feed: Option<&Feed>,
    viewport: &Viewport,
    size: (f32, f32),
    at: (f32, f32),
) -> Destination {
    let stop = feed.and_then(|feed| {
        let index = pick_stop(feed, viewport, size, at, PICK_RADIUS_PX)?;
        feed.stops.get(index as usize)
    });
    match stop {
        Some(stop) => Destination {
            label: stop.name.clone(),
            coord: stop.coord,
            gtfs_stop: Some(stop.id.clone()),
            pattern: None,
        },
        None => Destination::dropped(viewport.unproject(at)),
    }
}

/// Where a pin let go at `at` ends up. `input` carries the release speed, peak
/// speed and travel; its candidate fields are filled in here from the nearest
/// GTFS stop within [`snap::FAST_RADIUS_PX`]. `origin` is the pin's value
/// before the drag, so a snapped pin nudged slowly stays on its stop.
pub fn drop_destination(
    feed: Option<&Feed>,
    viewport: &Viewport,
    size: (f32, f32),
    at: (f32, f32),
    input: &snap::DropInput,
    origin: Option<&Destination>,
) -> Destination {
    let stop = feed.and_then(|feed| {
        let index = pick_stop(feed, viewport, size, at, snap::FAST_RADIUS_PX)?;
        feed.stops.get(index as usize)
    });
    if let Some(stop) = stop {
        let decision = snap::decide(&snap::DropInput {
            candidate_px: Some(dist(viewport.project(stop.coord), at)),
            started_on_candidate: origin
                .and_then(|o| o.gtfs_stop.as_deref())
                .is_some_and(|id| id == stop.id),
            ..*input
        });
        if decision == snap::DropSnap::Snap {
            return Destination {
                label: stop.name.clone(),
                coord: stop.coord,
                gtfs_stop: Some(stop.id.clone()),
                pattern: None,
            };
        }
    }
    Destination::dropped(viewport.unproject(at))
}

/// Index of the GTFS stop `id` in the feed, for the pin menu's "Lines" row.
pub fn stop_index(feed: Option<&Feed>, id: &str) -> Option<u32> {
    let index = feed?.stops.iter().position(|s| s.id == id)?;
    u32::try_from(index).ok()
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

/// The view that shows you and the whole circle on AB.
pub fn fit_view(
    position: Option<Coord>,
    diameter: Option<&Diameter>,
    size: (f32, f32),
) -> Option<Viewport> {
    let mut points: Vec<Coord> = Vec::with_capacity(3);
    points.extend(position);
    if let Some(d) = diameter {
        points.extend(extent(d.center(), d.radius_m()));
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

/// Metres per pixel at street level: tiles show streets and names.
pub const STREET_MPP: f64 = 1.5;
/// Metres per pixel for the last-resort city view.
pub const CITY_MPP: f64 = 12.0;
/// Where the map opens with no position, no ends and nothing remembered.
pub const DEFAULT_CENTRE: Coord = Coord::new(41.0082, 28.9784);

/// Where the idle map opens: the pair if there is one, else the one end, else
/// you, else somewhere you used before, else the default city.
pub fn idle_view(
    ends: &Ends,
    position: Option<Coord>,
    fallback: Option<Coord>,
    size: (f32, f32),
) -> Viewport {
    let a = ends.a.as_ref().map(|d| d.coord);
    let b = ends.b.as_ref().map(|d| d.coord);
    match (a, b) {
        (Some(a), Some(b)) => match Diameter::new(a, b) {
            Ok(d) => fit_view(None, Some(&d), size).unwrap_or_else(|| Viewport::new(a, STREET_MPP)),
            Err(_) => {
                let fitted = Viewport::fit(&[a, b], size.0, size.1, FIT_PADDING_PX);
                Viewport::new(fitted.center, fitted.metres_per_px.max(STREET_MPP))
            }
        },
        (Some(end), None) | (None, Some(end)) => Viewport::new(end, STREET_MPP),
        (None, None) => match position.or(fallback) {
            Some(at) => Viewport::new(at, STREET_MPP),
            None => Viewport::new(DEFAULT_CENTRE, CITY_MPP),
        },
    }
}

/// Where to open when there is no fix: the middle of the last trip, else the
/// first saved stop.
pub fn fallback_centre(history: &History, saved: &SavedStops) -> Option<Coord> {
    if let Some((a, b)) = history.recent_pairs(1).into_iter().next() {
        return Some(Coord::new(
            (a.coord.lat + b.coord.lat) / 2.0,
            (a.coord.lon + b.coord.lon) / 2.0,
        ));
    }
    saved.stops.first().map(SavedStop::center)
}

/// The `geo:` link that opens a point in any maps app.
pub fn maps_url(coord: Coord, label: &str) -> String {
    let label = label.trim();
    rovsti_geo::dest::point_qr_payload(coord, (!label.is_empty()).then_some(label))
}

/// Number of rows of the pin menu: Share, Copy, QR code, Open in maps, Move,
/// Remove and, for a transit stop, Lines.
#[cfg(test)]
const fn pin_menu_rows(is_stop: bool) -> usize {
    if is_stop { 7 } else { 6 }
}

/// Width and height of the small menu over a tapped pin.
pub const PIN_MENU_W: f32 = 180.0;
pub const PIN_MENU_H: f32 = 288.0;
/// Height of one row of the pin menu.
const PIN_MENU_ROW_H: f32 = 36.0;
const PIN_MENU_MARGIN: f32 = 8.0;
/// Gap between the pin and its menu, and the pin's own height above its point.
const PIN_MENU_GAP: f32 = 14.0;

/// The pin menu box `(x, y, w, h)` in map-local pixels from the top-left: above
/// `pin`, below it where there is no room, kept inside `size` with a margin.
pub fn pin_menu_rect(pin: (f32, f32), size: (f32, f32)) -> (f32, f32, f32, f32) {
    let (w, h) = (PIN_MENU_W, PIN_MENU_H);
    let above = pin.1 - PIN_MENU_GAP - h;
    let y = if above >= PIN_MENU_MARGIN {
        above
    } else {
        pin.1 + PIN_MENU_GAP
    };
    let clamp = |v: f32, len: f32, bound: f32| {
        let hi = (bound - len - PIN_MENU_MARGIN).max(PIN_MENU_MARGIN);
        v.clamp(PIN_MENU_MARGIN, hi)
    };
    (clamp(pin.0 - w / 2.0, w, size.0), clamp(y, h, size.1), w, h)
}

/// Whether `at` is inside `rect` `(x, y, w, h)`.
pub fn over_rect(rect: (f32, f32, f32, f32), at: (f32, f32)) -> bool {
    at.0 >= rect.0 && at.0 <= rect.0 + rect.2 && at.1 >= rect.1 && at.1 <= rect.1 + rect.3
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
    /// Bumped on every change, so the map knows when to rebuild its scene.
    revision: u64,
}

impl Breadcrumb {
    /// Adds a fix of trip `trip`. A fix of another trip starts a new trail;
    /// poor fixes and ones that barely moved are left out.
    pub fn record(&mut self, trip: Option<u64>, at: Coord, accuracy_m: f64) {
        if self.trip != trip {
            self.points.clear();
            self.trip = trip;
            self.revision += 1;
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
        self.revision += 1;
    }

    pub fn clear(&mut self) {
        self.points.clear();
        self.trip = None;
        self.revision += 1;
    }

    /// Changes whenever the trail does.
    pub fn revision(&self) -> u64 {
        self.revision
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
    /// A finger touched a pin without moving.
    PinTap {
        slot: Slot,
    },
    /// The grabbed pin goes to `at` (the finger minus the grab offset).
    PinDrag {
        slot: Slot,
        at: (f32, f32),
    },
    /// The grabbed pin was let go at `at`.
    PinDrop {
        slot: Slot,
        at: (f32, f32),
        /// Finger speed at release (px/s, smoothed over the last ~90 ms).
        release_px_s: f32,
        /// Highest smoothed speed in the last 300 ms before release.
        peak_px_s: f32,
        /// Finger travel since the grab.
        travel_px: f32,
    },
    /// The grab was interrupted: the pin goes back.
    PinCancel {
        slot: Slot,
    },
}

/// A pin held by the first finger.
#[derive(Clone, Debug)]
struct Grab {
    slot: Slot,
    tracker: snap::VelocityTracker,
    /// Where the finger touched down.
    start_at: (f32, f32),
    /// Finger minus pin at the start, so the pin does not jump.
    offset: (f32, f32),
    moved: bool,
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
    /// Last real (non-mouse) touch.
    last_touch_ms: Option<u64>,
    /// Where the pins are, in the same space as the touches.
    handles: Vec<(Slot, (f32, f32))>,
    grab: Option<Grab>,
    /// The running mouse sequence is a touch's echo.
    mouse_ignored: bool,
}

impl Gestures {
    /// The pins a finger can grab, in the touches' space.
    pub fn set_handles(&mut self, handles: &[(Slot, (f32, f32))]) {
        self.handles.clear();
        self.handles.extend_from_slice(handles);
    }

    pub fn touch(&mut self, id: u64, phase: Phase, at: (f32, f32), now_ms: u64) -> Option<Gesture> {
        if id != MOUSE_ID {
            self.last_touch_ms = Some(now_ms);
        }
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
                    self.grab = self
                        .handles
                        .iter()
                        .map(|&(slot, h)| (slot, h, dist(at, h)))
                        .filter(|t| t.2 <= PIN_GRAB_PX)
                        .min_by(|a, b| a.2.total_cmp(&b.2))
                        .map(|(slot, h, _)| Grab {
                            slot,
                            tracker: {
                                let mut tracker = snap::VelocityTracker::default();
                                tracker.reset();
                                tracker.push(now_ms, at);
                                tracker
                            },
                            start_at: at,
                            offset: (at.0 - h.0, at.1 - h.1),
                            moved: false,
                        });
                    None
                } else {
                    self.dragging = true;
                    // A second finger turns the grab into a pinch.
                    self.grab
                        .take()
                        .map(|grab| Gesture::PinCancel { slot: grab.slot })
                }
            }
            Phase::Move => {
                let i = self.touches.iter().position(|t| t.0 == id)?;
                if let Some(grab) = &mut self.grab {
                    self.touches[i].1 = at;
                    grab.tracker.push(now_ms, at);
                    if !grab.moved && dist(at, self.start) > TAP_SLOP {
                        grab.moved = true;
                    }
                    return grab.moved.then_some(Gesture::PinDrag {
                        slot: grab.slot,
                        at: (at.0 - grab.offset.0, at.1 - grab.offset.1),
                    });
                }
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
                    return self
                        .grab
                        .take()
                        .map(|grab| Gesture::PinCancel { slot: grab.slot });
                }
                if let Some(mut grab) = self.grab.take() {
                    self.touches.remove(i);
                    // The lift itself is a sample: a pause before it reads as slow.
                    grab.tracker.push(now_ms, at);
                    return Some(if grab.moved {
                        Gesture::PinDrop {
                            slot: grab.slot,
                            at: (at.0 - grab.offset.0, at.1 - grab.offset.1),
                            release_px_s: grab.tracker.speed_px_s(now_ms),
                            peak_px_s: grab.tracker.peak_px_s(now_ms),
                            travel_px: dist(at, grab.start_at),
                        }
                    } else {
                        Gesture::PinTap { slot: grab.slot }
                    });
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

    /// The left mouse button (desktop) as a one-finger touch. A sequence
    /// that starts right after a real touch is that touch's echo and is
    /// ignored to its end.
    pub fn mouse(&mut self, phase: Phase, at: (f32, f32), now_ms: u64) -> Option<Gesture> {
        if phase == Phase::Start {
            self.mouse_ignored = self
                .last_touch_ms
                .is_some_and(|t| now_ms.saturating_sub(t) < CLICK_ECHO_MS);
        }
        if self.mouse_ignored {
            if matches!(phase, Phase::End | Phase::Cancel) {
                self.mouse_ignored = false;
            }
            return None;
        }
        self.touch(MOUSE_ID, phase, at, now_ms)
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
    /// The circle on AB with its two halves.
    pub diameter: Option<DiameterScene>,
    /// The pins of A and B, for every end that is set.
    pub pins: Vec<PinScene>,
    /// AB drawn as a plain warning line when both ends are set but make no valid circle.
    pub segment: Option<((f32, f32), (f32, f32))>,
    pub trail: Vec<(f32, f32)>,
    pub stops: Vec<(f32, f32)>,
    /// The middles of the saved stops in view (plus a margin), drawn as rings.
    pub saved: Vec<(f32, f32)>,
    pub line: Vec<(f32, f32)>,
    /// The measure tool's points, in tap order.
    pub measure: Vec<(f32, f32)>,
    /// Map tiles under everything else; empty where none are loaded.
    pub tiles: TileLayer,
}

/// One pin, in pixels from the view centre.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PinScene {
    pub slot: Slot,
    pub at: (f32, f32),
    /// The pin sits on a GTFS stop.
    pub snapped: bool,
}

/// The circle with AB as its diameter, in pixels from the view centre.
#[derive(Debug, Default, PartialEq)]
pub struct DiameterScene {
    pub a: (f32, f32),
    pub b: (f32, f32),
    pub centre: (f32, f32),
    pub radius_px: f32,
    /// The left half-disc (facing A -> B) as a polygon.
    pub left: Vec<(f32, f32)>,
    /// The right half-disc as a polygon.
    pub right: Vec<(f32, f32)>,
    /// Where the "L" and "R" labels sit: halfway out in each half.
    pub left_label: (f32, f32),
    pub right_label: (f32, f32),
}

/// Segments in the arc of each half-disc polygon.
const HALF_DISC_SEGMENTS: usize = 48;

impl DiameterScene {
    pub fn build(d: &Diameter, vp: &Viewport) -> Self {
        let project_all = |side: Side| -> Vec<(f32, f32)> {
            d.half_disc(side, HALF_DISC_SEGMENTS)
                .into_iter()
                .map(|c| vp.project(c))
                .collect()
        };
        // Local metres are east/north around the centre; "left" of A -> B is
        // the direction turned a quarter turn counter-clockwise.
        let (ax, ay) = d.local(d.a());
        let (bx, by) = d.local(d.b());
        let len = (bx - ax).hypot(by - ay);
        let left = (-(by - ay) / len, (bx - ax) / len);
        let half = d.radius_m() / 2.0;
        let label = |s: f64| vp.project(d.from_local((left.0 * half * s, left.1 * half * s)));
        Self {
            a: vp.project(d.a()),
            b: vp.project(d.b()),
            centre: vp.project(d.center()),
            radius_px: ((d.radius_m() / vp.metres_per_px) as f32).clamp(0.0, MAX_RADIUS_PX),
            left: project_all(Side::Left),
            right: project_all(Side::Right),
            left_label: label(1.0),
            right_label: label(-1.0),
        }
    }
}

pub struct SceneInput<'a> {
    pub viewport: Viewport,
    pub size: (f32, f32),
    pub position: Option<(Coord, f64)>,
    pub diameter: Option<Diameter>,
    /// A and B with whether each snapped to a stop.
    pub pins: [Option<(Coord, bool)>; 2],
    pub trail: &'a Breadcrumb,
    pub feed: Option<&'a Feed>,
    pub pattern: Option<u32>,
    /// The middles of the saved stops.
    pub saved: &'a [Coord],
    /// The measure tool's points.
    pub measure: &'a [Coord],
    /// Loaded map tiles under the scene.
    pub tiles: &'a [&'a TileGeometry],
}

/// How far outside the view a saved stop is still projected, in px.
const SAVED_MARGIN_PX: f32 = 24.0;

/// Whether a point (pixels from the view centre) lies within the view of
/// `size` grown by `margin` on every side.
fn in_view(p: (f32, f32), size: (f32, f32), margin: f32) -> bool {
    p.0.abs() <= size.0 / 2.0 + margin && p.1.abs() <= size.1 / 2.0 + margin
}

/// Vertices of tile geometry kept per frame; the rest is dropped by priority.
const TILE_MAX_VERTICES: usize = 60_000;
/// Tiles asked for per view.
const VIEW_TILE_CAP: usize = 24;
/// Points in one painted path; the GPU index buffer holds 16 bits.
const PATH_POINT_CAP: usize = 8_000;
/// How much wider than the line its casing is, in px.
const CASING_EXTRA_PX: f32 = 1.5;

impl Scene {
    pub fn build(input: &SceneInput<'_>) -> Self {
        let vp = &input.viewport;
        let mpp = vp.metres_per_px;
        let metres = |m: f64| ((m / mpp) as f32).clamp(0.0, MAX_RADIUS_PX);
        let diameter = input.diameter.map(|d| DiameterScene::build(&d, vp));
        let pins: Vec<PinScene> = [Slot::A, Slot::B]
            .into_iter()
            .zip(input.pins)
            .filter_map(|(slot, pin)| {
                let (coord, snapped) = pin?;
                Some(PinScene {
                    slot,
                    at: vp.project(coord),
                    snapped,
                })
            })
            .collect();
        let segment = match (input.pins[0], input.pins[1], &diameter) {
            (Some((a, _)), Some((b, _)), None) => Some((vp.project(a), vp.project(b))),
            _ => None,
        };
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
        let saved: Vec<(f32, f32)> = input
            .saved
            .iter()
            .map(|&c| vp.project(c))
            .filter(|&p| in_view(p, input.size, SAVED_MARGIN_PX))
            .collect();
        Self {
            position: input
                .position
                .map(|(c, accuracy)| (vp.project(c), metres(accuracy))),
            diameter,
            pins,
            segment,
            trail: thin_polyline(&trail, TRAIL_MIN_PX),
            stops,
            saved,
            line: thin_polyline(&line, LINE_MIN_PX),
            measure: input.measure.iter().map(|&c| vp.project(c)).collect(),
            tiles: build_layer(input.tiles, vp, input.size, TILE_MAX_VERTICES),
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
    ring: Hsla,
    left: Hsla,
    right: Hsla,
    left_wash: Hsla,
    right_wash: Hsla,
    halo: Hsla,
    saved: Hsla,
    measure: Hsla,
    /// The map tile colours: light is "colorful", dark is "eclipse".
    style: MapStyle,
}

/// The colour of a half of the circle; the live card's side chip uses it too.
/// Unknown (on the line) is neutral.
pub fn side_color(theme: Theme, side: Option<Side>) -> Hsla {
    let c = theme::colors(theme);
    match side {
        Some(Side::Left) => c.accent,
        Some(Side::Right) => c.green,
        None => c.secondary_label,
    }
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
            ring: c.red,
            left: side_color(theme, Some(Side::Left)),
            right: side_color(theme, Some(Side::Right)),
            left_wash: with_alpha(c.accent, 0.18),
            right_wash: with_alpha(c.green, 0.18),
            halo: gpui::white(),
            saved: c.orange,
            measure: c.label,
            style: match theme {
                Theme::Light => MapStyle::Colorful,
                Theme::Dark => MapStyle::Eclipse,
            },
        }
    }
}

/// A style-table colour as a gpui colour.
fn style_color(c: Rgba8) -> Hsla {
    gpui::rgba(c.to_u32()).into()
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

fn fill_circle(out: &mut Paths, at: (f32, f32), r: f32, color: Hsla) {
    if r < 0.25 {
        return;
    }
    let mut path = PathBuilder::fill();
    add_circle(&mut path, at, r, true);
    if let Ok(path) = path.build() {
        out.push((path, color));
    }
}

fn stroke_line(out: &mut Paths, points: &[(f32, f32)], width: f32, color: Hsla) {
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
        out.push((path, color));
    }
}

fn fill_polygon(out: &mut Paths, points: &[(f32, f32)], color: Hsla) {
    if points.len() < 3 {
        return;
    }
    let pts: Vec<_> = points.iter().map(|&(x, y)| point(px(x), px(y))).collect();
    let mut path = PathBuilder::fill();
    path.add_polygon(&pts, true);
    if let Ok(path) = path.build() {
        out.push((path, color));
    }
}

/// Bounding box of a polygon's rings: (min x, min y, max x, max y).
fn polygon_bbox(polygon: &[Vec<(f32, f32)>]) -> (f32, f32, f32, f32) {
    let mut b = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
    for &(x, y) in polygon.iter().flatten() {
        b = (b.0.min(x), b.1.min(y), b.2.max(x), b.3.max(y));
    }
    b
}

fn bboxes_meet(a: (f32, f32, f32, f32), b: (f32, f32, f32, f32)) -> bool {
    a.0 <= b.2 && b.0 <= a.2 && a.1 <= b.3 && b.1 <= a.3
}

/// Fills `polygons` (each a list of rings: outline first, then holes) in
/// `color`. A path fills even-odd, so two overlapping polygons in one path
/// would cancel out (tiles overlap by their buffer, and a coarse stand-in tile
/// overlaps finer ones). Polygons whose boxes meet therefore never share a
/// path; the rest are batched, at most `PATH_POINT_CAP` points per path.
fn fill_polygons(
    out: &mut Paths,
    polygons: &[Vec<Vec<(f32, f32)>>],
    to_window: &impl Fn((f32, f32)) -> (f32, f32),
    color: Hsla,
) {
    let mut path = PathBuilder::fill();
    let mut points = 0;
    let mut boxes: Vec<(f32, f32, f32, f32)> = Vec::new();
    let flush = |path: &mut PathBuilder,
                 points: &mut usize,
                 boxes: &mut Vec<(f32, f32, f32, f32)>,
                 out: &mut Paths| {
        let full = std::mem::replace(path, PathBuilder::fill());
        *points = 0;
        boxes.clear();
        if let Ok(path) = full.build() {
            out.push((path, color));
        }
    };
    for polygon in polygons {
        let bbox = polygon_bbox(polygon);
        if !boxes.is_empty() && boxes.iter().any(|&b| bboxes_meet(b, bbox)) {
            flush(&mut path, &mut points, &mut boxes, out);
        }
        for ring in polygon.iter().filter(|r| r.len() >= 3) {
            let pts: Vec<_> = ring
                .iter()
                .map(|&p| {
                    let (x, y) = to_window(p);
                    point(px(x), px(y))
                })
                .collect();
            path.add_polygon(&pts, true);
            points += pts.len();
        }
        boxes.push(bbox);
        if points >= PATH_POINT_CAP {
            flush(&mut path, &mut points, &mut boxes, out);
        }
    }
    if !boxes.is_empty() {
        flush(&mut path, &mut points, &mut boxes, out);
    }
}

/// Strokes `lines` as one path per `PATH_POINT_CAP` points.
fn stroke_lines(
    out: &mut Paths,
    lines: &[Vec<(f32, f32)>],
    to_window: &impl Fn((f32, f32)) -> (f32, f32),
    width: f32,
    color: Hsla,
) {
    let mut path = PathBuilder::stroke(px(width));
    let mut points = 0;
    for (i, line) in lines.iter().enumerate() {
        if let Some((first, rest)) = line.split_first().filter(|(_, r)| !r.is_empty()) {
            let (x, y) = to_window(*first);
            path.move_to(point(px(x), px(y)));
            for &p in rest {
                let (x, y) = to_window(p);
                path.line_to(point(px(x), px(y)));
            }
            points += line.len();
        }
        if points >= PATH_POINT_CAP || i + 1 == lines.len() {
            let full = std::mem::replace(&mut path, PathBuilder::stroke(px(width)));
            points = 0;
            if let Ok(path) = full.build() {
                out.push((path, color));
            }
        }
    }
}

/// One class of the tile layer: fills (with an outline when the style has
/// one) or lines (casing first, then the line).
fn paint_class(
    class: DrawClass,
    geometry: &ClassGeometry,
    display_zoom: f64,
    to_window: &impl Fn((f32, f32)) -> (f32, f32),
    pal: &Palette,
    out: &mut Paths,
) {
    let paint = pal.style.paint(class);
    match class.shape() {
        Shape::Fill => {
            fill_polygons(out, &geometry.polygons, to_window, style_color(paint.color));
            if let Some(casing) = paint.casing {
                let rings: Vec<Vec<(f32, f32)>> = geometry
                    .polygons
                    .iter()
                    .flatten()
                    .map(|r| r.iter().copied().chain(r.first().copied()).collect())
                    .collect();
                stroke_lines(out, &rings, to_window, 1.0, style_color(casing));
            }
        }
        Shape::Line => {
            let width = line_width(paint.width14, display_zoom);
            if let Some(casing) = paint.casing {
                let wide = width + CASING_EXTRA_PX;
                stroke_lines(out, &geometry.lines, to_window, wide, style_color(casing));
            }
            stroke_lines(
                out,
                &geometry.lines,
                to_window,
                width,
                style_color(paint.color),
            );
        }
    }
}

/// The map tiles: backgrounds, then each class from the bottom up.
fn paint_tiles(
    layer: &TileLayer,
    to_window: &impl Fn((f32, f32)) -> (f32, f32),
    pal: &Palette,
    out: &mut Paths,
) {
    if layer.backgrounds.is_empty() {
        return;
    }
    let mut quads = PathBuilder::fill();
    for quad in &layer.backgrounds {
        let pts: Vec<_> = quad
            .iter()
            .map(|&p| {
                let (x, y) = to_window(p);
                point(px(x), px(y))
            })
            .collect();
        quads.add_polygon(&pts, true);
    }
    if let Ok(path) = quads.build() {
        out.push((path, style_color(pal.style.background())));
    }
    for (class, geometry) in &layer.groups {
        paint_class(*class, geometry, layer.display_zoom, to_window, pal, out);
    }
}

/// The half-discs, the circle, the diameter and its two ends.
fn paint_diameter(
    d: &DiameterScene,
    to_window: &impl Fn((f32, f32)) -> (f32, f32),
    pal: &Palette,
    out: &mut Paths,
) {
    let all = |points: &[(f32, f32)]| -> Vec<(f32, f32)> {
        points.iter().map(|&p| to_window(p)).collect()
    };
    fill_polygon(out, &all(&d.left), pal.left_wash);
    fill_polygon(out, &all(&d.right), pal.right_wash);
    if d.radius_px >= 2.0 {
        let mut ring = PathBuilder::stroke(px(2.0)).dash_array(&[px(8.0), px(6.0)]);
        add_circle(&mut ring, to_window(d.centre), d.radius_px, false);
        if let Ok(path) = ring.build() {
            out.push((path, pal.ring));
        }
    }
    let (a, b) = (to_window(d.a), to_window(d.b));
    stroke_line(out, &[a, b], 4.5, pal.ring);
    for end in [a, b] {
        fill_circle(out, end, 8.0, pal.halo);
        fill_circle(out, end, 5.5, pal.ring);
    }
}

/// Painted paths with their colours, around the view centre at (0, 0).
type Paths = Vec<(Path<Pixels>, Hsla)>;

/// `path` moved by `by`: tessellated once, painted wherever the map is now.
fn shifted(path: &Path<Pixels>, by: Point<Pixels>) -> Path<Pixels> {
    let mut path = path.clone();
    path.bounds.origin += by;
    for vertex in &mut path.vertices {
        vertex.xy_position += by;
    }
    path
}

/// The tessellated scene and what it was built from. Tessellating tens of
/// thousands of tile vertices is the costly part of drawing the map, so it
/// only runs again when the scene, the theme or the dragged pin change, not
/// on every frame of a sheet, pill or tab animation over a still map.
#[derive(Default)]
pub struct PaintCache {
    key: Option<(u64, Theme, Option<Slot>)>,
    paths: Paths,
}

/// Paints the plate and the cached paths of scene `serial` into `bounds`.
fn paint_map(
    cache: &mut PaintCache,
    (serial, scene): (u64, &Scene),
    dragging: Option<Slot>,
    theme: Theme,
    bounds: Bounds<Pixels>,
    window: &mut Window,
) {
    let pal = Palette::new(theme);
    window.paint_quad(quad(
        bounds,
        Corners::all(radius::CARD),
        pal.plate,
        Edges::all(px(1.0)),
        pal.border,
        BorderStyle::default(),
    ));
    let key = (serial, theme, dragging);
    if cache.key != Some(key) {
        cache.paths = scene_paths(scene, dragging, &pal);
        cache.key = Some(key);
    }
    let centre = bounds.center();
    for (path, color) in &cache.paths {
        window.paint_path(shifted(path, centre), *color);
    }
}

/// Tessellates `scene` into paths around the view centre.
fn scene_paths(scene: &Scene, dragging: Option<Slot>, pal: &Palette) -> Paths {
    let mut paths = Paths::new();
    let out = &mut paths;
    let to_window = |p: (f32, f32)| p;

    // The map tiles under everything else; where they are missing the plate shows.
    paint_tiles(&scene.tiles, &to_window, pal, out);

    // The chosen line, then the stops on it.
    stroke_line(out, &scene.line, 3.5, pal.line);

    if !scene.stops.is_empty() {
        let mut dots = PathBuilder::fill();
        for &p in &scene.stops {
            add_circle(&mut dots, to_window(p), 3.5, true);
        }
        if let Ok(path) = dots.build() {
            out.push((path, pal.stop));
        }
    }

    // Saved stops: hollow rings at their middle, so they read as neither a stop dot nor the line.
    if !scene.saved.is_empty() {
        for &p in &scene.saved {
            fill_circle(out, to_window(p), 6.5, pal.halo);
        }
        let mut rings = PathBuilder::stroke(px(2.5));
        for &p in &scene.saved {
            add_circle(&mut rings, to_window(p), 5.0, false);
        }
        if let Ok(path) = rings.build() {
            out.push((path, pal.saved));
        }
    }

    if let Some(d) = &scene.diameter {
        paint_diameter(d, &to_window, pal, out);
    }
    if let Some((a, b)) = scene.segment {
        stroke_line(out, &[to_window(a), to_window(b)], 3.5, pal.ring);
    }

    stroke_line(out, &scene.trail, 3.0, pal.trail);

    // The measure tool: a polyline with a dot on every tapped point.
    stroke_line(out, &scene.measure, 6.0, pal.halo);
    stroke_line(out, &scene.measure, 3.0, pal.measure);
    for &p in &scene.measure {
        fill_circle(out, to_window(p), 6.5, pal.halo);
        fill_circle(out, to_window(p), 4.5, pal.measure);
    }

    if let Some((at, accuracy)) = scene.position {
        let at = to_window(at);
        fill_circle(out, at, accuracy, pal.accuracy);
        fill_circle(out, at, 9.0, pal.halo);
        fill_circle(out, at, 6.5, pal.you);
    }

    for pin in &scene.pins {
        let at = to_window(pin.at);
        let r = pin_radius(dragging == Some(pin.slot));
        let color = pin_color(pal, pin.slot);
        fill_circle(out, at, r + PIN_RIM_PX, pal.halo);
        fill_circle(out, at, r, color);
        if pin.snapped {
            let mut ring = PathBuilder::stroke(px(2.0));
            add_circle(&mut ring, at, r + PIN_RIM_PX + 3.5, false);
            if let Ok(path) = ring.build() {
                out.push((path, color));
            }
        }
    }
    paths
}

/// Radius of a pin disc; the pin in the finger is drawn larger.
const PIN_RADIUS_PX: f32 = 12.0;
const PIN_RIM_PX: f32 = 2.5;
const PIN_DRAG_SCALE: f32 = 1.2;
/// Widest a pin's name caption grows.
const PIN_CAPTION_W: f32 = 160.0;

fn pin_radius(dragged: bool) -> f32 {
    if dragged {
        PIN_RADIUS_PX * PIN_DRAG_SCALE
    } else {
        PIN_RADIUS_PX
    }
}

fn pin_color(pal: &Palette, slot: Slot) -> Hsla {
    match slot {
        Slot::A => pal.left,
        Slot::B => pal.right,
    }
}

/// The letter on a pin and the name under it, as elements over the canvas.
fn pin_labels(
    theme: Theme,
    scene: &Scene,
    dragging: Option<Slot>,
    ends: &Ends,
    size: (f32, f32),
) -> Vec<AnyElement> {
    let colors = theme::colors(theme);
    let mut out = Vec::with_capacity(scene.pins.len() * 2);
    for pin in &scene.pins {
        let r = pin_radius(dragging == Some(pin.slot));
        let (x, y) = (size.0 / 2.0 + pin.at.0, size.1 / 2.0 + pin.at.1);
        out.push(
            div()
                .absolute()
                .left(px(x - r))
                .top(px(y - r))
                .size(px(r * 2.0))
                .flex()
                .items_center()
                .justify_center()
                .text_size(typography::FOOTNOTE.size)
                .font_weight(typography::HEADLINE.weight)
                .text_color(gpui::white())
                .child(pin.slot.label())
                .into_any_element(),
        );
        let name = pick::point_label(pin.slot, ends.get(pin.slot));
        out.push(
            div()
                .absolute()
                .left(px(x - PIN_CAPTION_W / 2.0))
                .top(px(y + r + PIN_RIM_PX + 4.0))
                .w(px(PIN_CAPTION_W))
                .flex()
                .justify_center()
                .child(
                    div()
                        .max_w(px(PIN_CAPTION_W))
                        .px(px(8.0))
                        .py(px(2.0))
                        .rounded_full()
                        .bg(with_alpha(colors.secondary_background, 0.88))
                        .truncate()
                        .text_size(typography::CAPTION.size)
                        .text_color(colors.label)
                        .child(name),
                )
                .into_any_element(),
        );
    }
    out
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
    /// When the my-location button was pressed, while its fix has not arrived.
    locate_pending: Option<i64>,
    /// "Me" opened the location prompt: try again once it is answered.
    locate_after_grant: bool,
    /// The last scene and what it was built from: a render with the same
    /// inputs (a sheet sliding over the map, the pill, a keystroke) reuses it.
    scene: Option<CachedScene>,
    /// Bumped for every scene built, to key the tessellated paths.
    scene_serial: u64,
    /// The tessellated paths of the scene, shared with the paint closure.
    paint: Rc<RefCell<PaintCache>>,
    /// A pin easing into the stop it snapped to.
    pub settle: Option<PinSettle>,
    /// The measure tool: `Some` while it is on, and the points tapped so far.
    pub measure: Option<Measure>,
    /// The first tap on "Clear" has armed the second.
    pub measure_armed: bool,
}

/// What a scene is built from: equal inputs give an equal scene.
#[derive(Clone, Debug, PartialEq)]
struct SceneKey {
    viewport: Viewport,
    size: (f32, f32),
    position: Option<(Coord, f64)>,
    diameter: Option<Diameter>,
    pins: [Option<(Coord, bool)>; 2],
    trail: u64,
    measure: Vec<Coord>,
    /// [`saved_key`] of the saved stops.
    saved: u64,
    /// The feed's address; a new feed also forgets the scene.
    feed: usize,
    pattern: Option<u32>,
    cover: TileCover,
    /// [`crate::tiles::MapTiles::revision`].
    tiles: u64,
}

struct CachedScene {
    key: SceneKey,
    serial: u64,
    scene: Rc<Scene>,
}

/// How long after "Me" its fix still moves the view.
pub const LOCATE_WAIT_MS: i64 = 60_000;

/// How old a device fix may be and still centre the idle map.
pub const FIX_MAX_AGE_MS: i64 = 10 * 60 * 1000;

/// Whether a fix taken at `time_ms` is recent enough at `now_ms` (a fix from the
/// future, within clock skew, counts as fresh).
pub fn fresh_enough(time_ms: i64, now_ms: i64) -> bool {
    time_ms > 0 && now_ms.saturating_sub(time_ms) <= FIX_MAX_AGE_MS
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
            locate_pending: None,
            locate_after_grant: false,
            scene: None,
            scene_serial: 0,
            paint: Rc::default(),
            settle: None,
            measure: None,
            measure_armed: false,
        }
    }
}

/// A dropped pin gliding from where the finger let go to the stop it snapped to.
#[derive(Clone, Debug)]
pub struct PinSettle {
    pub slot: Slot,
    pub from: Coord,
    pub spring: Spring,
}

impl PinSettle {
    pub fn new(slot: Slot, from: Coord) -> Self {
        Self {
            slot,
            from,
            spring: settle_spring(),
        }
    }

    /// Where the pin is drawn while it settles onto `to`.
    pub fn coord(&self, to: Coord) -> Coord {
        lerp_coord(self.from, to, self.spring.value)
    }
}

fn settle_spring() -> Spring {
    let mut spring = Spring::new(0.0, motion_tokens::release()).with_epsilon(0.002);
    spring.set_target(1.0);
    spring
}

/// Linear blend of two points: `a` at 0, `b` at 1 (not clamped, so a springy
/// overshoot passes the target a little).
pub fn lerp_coord(a: Coord, b: Coord, t: f32) -> Coord {
    let t = f64::from(t);
    Coord::new(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
}

/// The settle for a pin dropped at `released` and resolved to `dest`: only a
/// snap (the destination is a GTFS stop) glides, and never under reduced motion.
pub fn settle_for_drop(
    slot: Slot,
    released: Coord,
    dest: &Destination,
    reduced_motion: bool,
) -> Option<PinSettle> {
    (dest.gtfs_stop.is_some() && !reduced_motion && released != dest.coord)
        .then(|| PinSettle::new(slot, released))
}

/// What a tap on the measure tool did.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MeasureTap {
    /// The tool is off: the tap is an ordinary map tap.
    Off,
    Added,
    /// The point cap was reached; nothing was added.
    Full,
}

/// What a tap on "Clear" does: a longer measurement asks for a second tap.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ClearStep {
    Arm,
    Clear,
}

/// More points than this ask before "Clear" wipes them.
pub const CLEAR_CONFIRM_ABOVE: usize = 2;

pub fn clear_step(points: usize, armed: bool) -> ClearStep {
    if points > CLEAR_CONFIRM_ABOVE && !armed {
        ClearStep::Arm
    } else {
        ClearStep::Clear
    }
}

/// The measure card's headline: "Total 1.4 km, 3 legs".
pub fn measure_summary(m: &Measure) -> String {
    match m.points().len() {
        0 => "Tap the map to add points".to_string(),
        1 => "Tap a second point".to_string(),
        n => {
            let legs = n - 1;
            format!(
                "Total {}, {legs} {}",
                crate::settings_ui::distance_text(m.total_m()),
                if legs == 1 { "leg" } else { "legs" }
            )
        }
    }
}

/// Whether the pins can be grabbed: only while the pair can be edited and the
/// measure tool is not taking the taps.
pub fn pins_grabbable(trip: bool, alarm: bool, measuring: bool) -> bool {
    !trip && !alarm && !measuring
}

impl MapState {
    /// The measure tool's points (empty while it is off).
    pub fn measure_points(&self) -> Vec<Coord> {
        self.measure
            .as_ref()
            .map(|m| m.points().to_vec())
            .unwrap_or_default()
    }

    pub fn start_measure(&mut self) {
        self.measure = Some(Measure::new());
        self.measure_armed = false;
    }

    /// Turns the tool off and forgets its points; true when it was on.
    pub fn end_measure(&mut self) -> bool {
        self.measure_armed = false;
        self.measure.take().is_some()
    }

    /// A tap at `at` (pixels from the view centre) while measuring adds a point.
    pub fn measure_tap(&mut self, at: (f32, f32)) -> MeasureTap {
        let coord = self.viewport.unproject(at);
        let Some(m) = self.measure.as_mut() else {
            return MeasureTap::Off;
        };
        self.measure_armed = false;
        if m.push(coord) {
            MeasureTap::Added
        } else {
            MeasureTap::Full
        }
    }

    /// Advances the settle; returns whether it is still moving.
    pub fn tick_settle(&mut self, dt: Duration) -> bool {
        let Some(settle) = &mut self.settle else {
            return false;
        };
        if settle.spring.tick(dt) {
            true
        } else {
            self.settle = None;
            false
        }
    }

    /// The next device fix, within [`LOCATE_WAIT_MS`] of `now_ms`, should
    /// recentre the view (the my-location button).
    pub fn ask_locate(&mut self, now_ms: i64) {
        self.locate_pending = Some(now_ms);
    }

    /// The asked-for fix will not come (no permission, location off, error).
    pub fn cancel_locate(&mut self) {
        self.locate_pending = None;
    }

    /// Records a device fix at `now_ms`. The view moves to it at street level
    /// only when the my-location button asked for it recently; otherwise the
    /// view is left alone (a following idle view already centres on
    /// `position`), so a fix long after the press does not yank the map. The
    /// trail is left alone.
    pub fn set_device_fix(&mut self, at: Coord, accuracy_m: f64, now_ms: i64) {
        self.position = Some((at, accuracy_m));
        if let Some(asked) = self.locate_pending.take()
            && now_ms.saturating_sub(asked) <= LOCATE_WAIT_MS
        {
            self.viewport = Viewport::new(at, STREET_MPP);
            self.follow = false;
        }
    }

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

    /// Drops the cached scene (the feed it points into is gone).
    pub fn forget_scene(&mut self) {
        self.scene = None;
    }

    /// The scene built for `key` with its serial, when nothing changed since.
    fn cached_scene(&self, key: &SceneKey) -> Option<(u64, Rc<Scene>)> {
        let cached = self.scene.as_ref().filter(|c| c.key == *key)?;
        Some((cached.serial, Rc::clone(&cached.scene)))
    }

    /// Keeps `scene`, built for `key`, and returns it with its new serial.
    fn store_scene(&mut self, key: SceneKey, scene: Scene) -> (u64, Rc<Scene>) {
        self.scene_serial += 1;
        let scene = Rc::new(scene);
        self.scene = Some(CachedScene {
            key,
            serial: self.scene_serial,
            scene: Rc::clone(&scene),
        });
        (self.scene_serial, scene)
    }
}

/// One thing the surface saw, in window coordinates.
enum MapInput {
    Touch {
        id: u64,
        phase: Phase,
        at: (f32, f32),
    },
    Mouse {
        phase: Phase,
        at: (f32, f32),
    },
}

/// The invisible layer that reports touches (with their ids, for pinch) and
/// mouse clicks, and keeps the page from scrolling under a finger on the map.
///
/// The listeners are window-wide, so a gesture only starts where the map's own
/// hitbox is hovered: the sheet, the tab bar, the pill and the alarm screen
/// occlude it (a touch moves the hit test before it is dispatched). `blocked`
/// (a sheet or the alarm is up) refuses every start as well.
fn input_surface(
    bounds_out: Rc<Cell<Bounds<Pixels>>>,
    menu_rect: Option<(f32, f32, f32, f32)>,
    blocked: bool,
    on_input: impl Fn(MapInput, &mut Window, &mut App) + 'static,
) -> impl IntoElement {
    let on_input = Rc::new(on_input);
    canvas(
        |bounds, window, _| window.insert_hitbox(bounds, HitboxBehavior::Normal),
        move |bounds, hitbox, window, _| {
            bounds_out.set(bounds);
            let size = (f32::from(bounds.size.width), f32::from(bounds.size.height));
            // The controls and the pin menu are not the map's; neither is
            // anything drawn over the map.
            let refuses = move |p: Point<Pixels>, window: &Window| {
                let local = (
                    f32::from(p.x - bounds.origin.x),
                    f32::from(p.y - bounds.origin.y),
                );
                blocked
                    || !bounds.contains(&p)
                    || !hitbox.is_hovered(window)
                    || over_controls(size, local)
                    || menu_rect.is_some_and(|r| over_rect(r, local))
            };
            let refuses = Rc::new(refuses);
            let touch = Rc::clone(&on_input);
            let touch_refuses = Rc::clone(&refuses);
            window.on_mouse_event(move |event: &TouchEvent, phase, window, cx| {
                if !phase.bubble() {
                    return;
                }
                if event.phase == TouchPhase::Started {
                    if touch_refuses(event.position, window) {
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
                    window,
                    cx,
                );
            });
            let mouse = Rc::clone(&on_input);
            let pressed = Rc::new(Cell::new(false));
            let down = Rc::clone(&pressed);
            let down_input = Rc::clone(&mouse);
            window.on_mouse_event(move |event: &MouseDownEvent, phase, window, cx| {
                if phase.bubble()
                    && event.button == MouseButton::Left
                    && !refuses(event.position, window)
                {
                    down.set(true);
                    down_input(
                        MapInput::Mouse {
                            phase: Phase::Start,
                            at: (f32::from(event.position.x), f32::from(event.position.y)),
                        },
                        window,
                        cx,
                    );
                }
            });
            let moved = Rc::clone(&pressed);
            let move_input = Rc::clone(&mouse);
            window.on_mouse_event(move |event: &MouseMoveEvent, phase, window, cx| {
                if phase.bubble() && moved.get() && event.pressed_button == Some(MouseButton::Left)
                {
                    move_input(
                        MapInput::Mouse {
                            phase: Phase::Move,
                            at: (f32::from(event.position.x), f32::from(event.position.y)),
                        },
                        window,
                        cx,
                    );
                }
            });
            window.on_mouse_event(move |event: &MouseUpEvent, phase, window, cx| {
                if phase.bubble() && event.button == MouseButton::Left && pressed.replace(false) {
                    mouse(
                        MapInput::Mouse {
                            phase: Phase::End,
                            at: (f32::from(event.position.x), f32::from(event.position.y)),
                        },
                        window,
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

/// A one-letter label at `at` (pixels from the view centre) on a map of `size`.
fn map_label(
    text: &'static str,
    at: (f32, f32),
    size: (f32, f32),
    color: Hsla,
    (dx, dy): (f32, f32),
) -> AnyElement {
    div()
        .absolute()
        .left(px(size.0 / 2.0 + at.0 + dx))
        .top(px(size.1 / 2.0 + at.1 + dy))
        .text_size(typography::FOOTNOTE.size)
        .font_weight(typography::HEADLINE.weight)
        .text_color(color)
        .child(text)
        .into_any_element()
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
    /// The circle the map shows: the running trip's, or the chosen pair's
    /// when it is valid.
    fn map_diameter(&self) -> Option<Diameter> {
        match &self.trip {
            Some(trip) => Some(trip.trip.diameter),
            None => self.pick.ends.diameter().and_then(Result::ok),
        }
    }

    /// The map card for the Trip tab, or `None` while there is nothing to show.
    pub(crate) fn map_card(&mut self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        // A running trip ends the ruler.
        if self.trip.is_some() {
            self.map.end_measure();
        }
        let size = self.map.size();
        let diameter = self.map_diameter();
        let position = self.map.position;
        if self.map.follow {
            self.map.viewport = if self.trip.is_some() {
                fit_view(position.map(|p| p.0), diameter.as_ref(), size)
                    .unwrap_or(self.map.viewport)
            } else {
                // The fallback is only looked up when nothing else places the view.
                let fallback = (self.pick.ends.is_empty() && position.is_none())
                    .then(|| fallback_centre(&self.history, &self.saved))
                    .flatten();
                idle_view(&self.pick.ends, position.map(|p| p.0), fallback, size)
            };
        }
        let viewport = self.map.viewport;
        let centre = self.map.bounds.get().center();
        // Pins are only grabbable while the pair can be edited.
        let editable = pins_grabbable(
            self.trip.is_some(),
            self.alarm.is_some(),
            self.map.measure.is_some(),
        );
        let handles: Vec<(Slot, (f32, f32))> = [Slot::A, Slot::B]
            .into_iter()
            .filter(|_| editable)
            .filter_map(|slot| {
                let (x, y) = viewport.project(self.pick.ends.get(slot)?.coord);
                Some((slot, (f32::from(centre.x) + x, f32::from(centre.y) + y)))
            })
            .collect();
        self.map.gestures.set_handles(&handles);
        let cover = tiles_for_view(&viewport, size, VIEW_TILE_CAP);
        self.map_tiles.request_view(&cover, cx);
        // A pair picked for the next trip (Saved, History, a shared link) is
        // not drawn over the running one.
        let pins = [Slot::A, Slot::B].map(|slot| {
            self.pick
                .ends
                .get(slot)
                .filter(|_| self.trip.is_none())
                .map(|d| {
                    let coord = match &self.map.settle {
                        Some(s) if s.slot == slot => s.coord(d.coord),
                        _ => d.coord,
                    };
                    (coord, d.gtfs_stop.is_some())
                })
        });
        let saved_coords: Vec<Coord> = self.saved.stops.iter().map(SavedStop::center).collect();
        let key = SceneKey {
            viewport,
            size,
            position,
            diameter,
            pins,
            trail: self.map.trail.revision(),
            measure: self.map.measure_points(),
            saved: saved_key(&self.saved),
            feed: self.gtfs.as_ref().map_or(0, |f| Arc::as_ptr(f) as usize),
            pattern: self.map.pattern,
            cover,
            tiles: self.map_tiles.revision(),
        };
        // Projecting and clipping the tiles, the stops in view and the line is
        // only redone when one of the inputs changed.
        let (serial, scene) = match self.map.cached_scene(&key) {
            Some(cached) => cached,
            None => {
                let loaded = self.map_tiles.gather(&key.cover);
                let loaded: Vec<&TileGeometry> = loaded.iter().map(|t| &**t).collect();
                let scene = Scene::build(&SceneInput {
                    viewport,
                    size,
                    position,
                    diameter,
                    pins,
                    trail: &self.map.trail,
                    feed: self.gtfs.as_deref(),
                    pattern: self.map.pattern,
                    saved: &saved_coords,
                    measure: &key.measure,
                    tiles: &loaded,
                });
                self.map.store_scene(key, scene)
            }
        };
        let palette = Palette::new(theme);
        let colors = theme::colors(theme);
        let (bar_px, bar_label) = scale_bar(viewport.metres_per_px);
        let labels: Vec<AnyElement> = scene
            .diameter
            .as_ref()
            .map(|d| {
                [
                    ("L", d.left_label, palette.left, (-6.0, -9.0)),
                    ("R", d.right_label, palette.right, (-6.0, -9.0)),
                ]
                .into_iter()
                .map(|(text, at, color, (dx, dy))| map_label(text, at, size, color, (dx, dy)))
                .collect()
            })
            .unwrap_or_default();
        let dragging = self.pick.dragging();
        let pin_labels = pin_labels(theme, &scene, dragging, &self.pick.ends, size);
        let paint = Rc::clone(&self.map.paint);
        let idle = self.trip.is_none();

        let entity = cx.entity();
        let input = entity.clone();
        let menu = self.pin_menu(theme, &scene, size, cx);
        let menu_rect = menu.as_ref().map(|(rect, _)| *rect);
        let blocked = self.sheet.is_visible() || self.alarm.is_some();
        let surface = input_surface(
            self.map.bounds.clone(),
            menu_rect,
            blocked,
            move |event, window, cx| {
                input.update(cx, |this, cx| this.map_input(event, window, cx));
            },
        );
        let pinch = entity;
        let last_control = if idle {
            control_button(theme, "Me")
                .id("map-locate")
                .text_size(typography::FOOTNOTE.size)
                .on_click(cx.listener(|this, _, _, cx| this.locate_me(cx)))
        } else {
            control_button(theme, "Fit")
                .id("map-fit")
                .text_size(typography::FOOTNOTE.size)
                .on_click(cx.listener(|this, _, _, cx| {
                    this.map.follow = true;
                    cx.notify();
                }))
        };
        let ruler_on = self.map.measure.is_some();
        let ruler = control_button(theme, "Ruler")
            .id("map-ruler")
            .text_size(typography::CAPTION.size)
            .when(ruler_on, |b| b.bg(colors.accent.opacity(0.25)))
            .on_click(cx.listener(|this, _, window, cx| this.toggle_measure(window, cx)));
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
            .child(ruler)
            .child(last_control);
        let measure_card = self.measure_card(theme, cx);
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
        let attribution = div()
            .absolute()
            .right(px(10.0))
            .bottom(px(8.0))
            .text_size(typography::CAPTION.size)
            .text_color(colors.secondary_label)
            .child(ATTRIBUTION);
        Some(
            div()
                .px(px(16.0))
                .child(
                    div()
                        .id("map")
                        .relative()
                        .h(px(if idle { IDLE_MAP_HEIGHT } else { MAP_HEIGHT }))
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
                                    paint_map(
                                        &mut paint.borrow_mut(),
                                        (serial, &scene),
                                        dragging,
                                        theme,
                                        bounds,
                                        window,
                                    );
                                },
                            )
                            .size_full(),
                        )
                        .children(labels)
                        .children(pin_labels)
                        .child(surface)
                        .children(menu.map(|(_, card)| card))
                        .children(measure_card)
                        .child(scale)
                        .child(attribution)
                        .child(controls),
                )
                .into_any_element(),
        )
    }

    /// The small card over a tapped pin and its box in map-local pixels: Save
    /// or Rename, Share, Move, Remove, Lines for a stop, and the coordinates as
    /// a details line.
    fn pin_menu(
        &mut self,
        theme: Theme,
        scene: &Scene,
        size: (f32, f32),
        cx: &mut Context<Self>,
    ) -> Option<((f32, f32, f32, f32), AnyElement)> {
        let slot = self.pick.menu()?;
        let dest = self.pick.ends.get(slot)?;
        let pin = scene.pins.iter().find(|p| p.slot == slot)?;
        let rect = pin_menu_rect((size.0 / 2.0 + pin.at.0, size.1 / 2.0 + pin.at.1), size);
        let stop = dest
            .gtfs_stop
            .as_deref()
            .and_then(|id| stop_index(self.gtfs.as_deref(), id));
        let details = pick::details_text(dest);
        let share_dest = dest.clone();
        let copy_dest = dest.clone();
        let qr_dest = dest.clone();
        let open_coord = dest.coord;
        let open_label = dest.label.clone();
        let colors = theme::colors(theme);
        let row = |id: &'static str, label: &'static str, color: Hsla| {
            div()
                .id(id)
                .h(px(PIN_MENU_ROW_H))
                .w_full()
                .px(px(14.0))
                .flex()
                .items_center()
                .text_size(typography::BODY.size)
                .text_color(color)
                .child(label)
        };
        let mut card = flat_glass(theme, None)
            .absolute()
            .left(px(rect.0))
            .top(px(rect.1))
            .w(px(rect.2))
            .h(px(rect.3))
            .rounded(radius::CARD)
            .py(px(4.0))
            .flex()
            .flex_col()
            .child(
                row("pin-share", "Share", colors.label).on_click(cx.listener(
                    move |this, _, _, cx| {
                        this.pick.close_menu();
                        let name = pick::point_label(slot, Some(&share_dest));
                        this.share_point(name, share_dest.coord, &share_dest.label, cx);
                    },
                )),
            )
            .child(row("pin-copy", "Copy", colors.label).on_click(cx.listener(
                move |this, _, _, cx| {
                    this.pick.close_menu();
                    this.open_copy_as(copy_dest.label.clone(), copy_dest.coord, cx);
                },
            )))
            .child(row("pin-qr", "QR code", colors.label).on_click(cx.listener(
                move |this, _, _, cx| {
                    this.pick.close_menu();
                    this.open_qr(&qr_dest.label, qr_dest.coord, cx);
                },
            )))
            .child(
                row("pin-open", "Open in maps", colors.label).on_click(cx.listener(
                    move |this, _, _, cx| {
                        this.pick.close_menu();
                        cx.open_url(&maps_url(open_coord, &open_label));
                        cx.notify();
                    },
                )),
            )
            .child(row("pin-move", "Move", colors.label).on_click(cx.listener(
                move |this, _, _, cx| {
                    this.pick.begin_move(slot);
                    cx.notify();
                },
            )))
            .child(
                row("pin-remove", "Remove", colors.red).on_click(cx.listener(
                    move |this, _, _, cx| {
                        let ends = this.pick.ends.clone();
                        let label = pick::point_label(slot, ends.get(slot));
                        this.pick.remove(slot);
                        this.map.follow = true;
                        this.show_pill(
                            format!("Removed {label}"),
                            Some(crate::PillAction::RestoreEnds(ends)),
                            cx,
                        );
                    },
                )),
            );
        if let Some(stop) = stop {
            card = card.child(
                row("pin-lines", "Lines", colors.label).on_click(cx.listener(
                    move |this, _, _, cx| {
                        this.pick.close_menu();
                        this.open_stop(stop, cx);
                    },
                )),
            );
        }
        let card = card
            .child(div().flex_1())
            .child(
                div()
                    .px(px(14.0))
                    .pb(px(6.0))
                    .truncate()
                    .text_size(typography::CAPTION.size)
                    .text_color(colors.secondary_label)
                    .child(details),
            )
            .into_any_element();
        Some((rect, card))
    }

    /// The Ruler button: turn the tool on, or off (with its points).
    fn toggle_measure(&mut self, window: &Window, cx: &mut Context<Self>) {
        if self.trip.is_some() || self.alarm.is_some() {
            return;
        }
        if self.map.end_measure() {
            self.show_pill("Measuring done", None, cx);
            return;
        }
        self.pick.close_menu();
        self.map.start_measure();
        haptics::perform(window, HapticFeedback::Selection);
        cx.notify();
    }

    /// The glass card at the bottom of the map while measuring: the total and
    /// leg count, Undo, Clear (twice for a long measurement) and Done.
    fn measure_card(&mut self, theme: Theme, cx: &mut Context<Self>) -> Option<AnyElement> {
        let m = self.map.measure.as_ref()?;
        let summary = measure_summary(m);
        let armed = self.map.measure_armed;
        let colors = theme::colors(theme);
        let clear_label = if armed { "Tap to clear" } else { "Clear" };
        let action = |label: &'static str| {
            glass_pill(theme, label)
                .px(px(12.0))
                .text_size(typography::FOOTNOTE.size)
        };
        Some(
            flat_glass(theme, None)
                .absolute()
                .left(px(10.0))
                .right(px(CONTROL_MARGIN + CONTROL_SIZE + CONTROL_GAP + 2.0))
                .bottom(px(30.0))
                .rounded(radius::CARD)
                .p(px(10.0))
                .flex()
                .flex_col()
                .gap(px(8.0))
                .child(
                    div()
                        .text_size(typography::BODY.size)
                        .text_color(colors.label)
                        .child(summary),
                )
                .child(
                    div()
                        .flex()
                        .gap(px(8.0))
                        .child(action("Undo").id("measure-undo").on_click(cx.listener(
                            |this, _, _, cx| {
                                if let Some(m) = this.map.measure.as_mut() {
                                    m.undo();
                                }
                                this.map.measure_armed = false;
                                cx.notify();
                            },
                        )))
                        .child(
                            action(clear_label)
                                .id("measure-clear")
                                .when(armed, |b| b.text_color(colors.red))
                                .on_click(cx.listener(|this, _, _, cx| {
                                    let count =
                                        this.map.measure.as_ref().map_or(0, |m| m.points().len());
                                    if clear_step(count, this.map.measure_armed) == ClearStep::Arm {
                                        this.map.measure_armed = true;
                                        cx.notify();
                                        return;
                                    }
                                    this.map.measure_armed = false;
                                    if let Some(m) = this.map.measure.as_mut() {
                                        m.clear();
                                    }
                                    if count > 0 {
                                        this.show_pill("Measurement cleared", None, cx);
                                    } else {
                                        cx.notify();
                                    }
                                })),
                        )
                        .child(action("Done").id("measure-done").on_click(
                            cx.listener(|this, _, window, cx| this.toggle_measure(window, cx)),
                        )),
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

    fn map_input(&mut self, event: MapInput, window: &Window, cx: &mut Context<Self>) {
        let now = now_ms().max(0) as u64;
        let (gesture, touch_ended) = match event {
            MapInput::Touch { id, phase, at } => (
                self.map.gestures.touch(id, phase, at, now),
                phase == Phase::End,
            ),
            MapInput::Mouse { phase, at } => {
                (self.map.gestures.mouse(phase, at, now), phase == Phase::End)
            }
        };
        if let Some(gesture) = gesture {
            self.apply_gesture(gesture, window);
            cx.notify();
        }
        let pin_end = matches!(
            gesture,
            Some(Gesture::PinTap { .. } | Gesture::PinDrop { .. })
        );
        if touch_ended && !pin_end {
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

    fn apply_gesture(&mut self, gesture: Gesture, window: &Window) {
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
            Gesture::PinTap { slot } => self.pick.open_menu(slot),
            Gesture::PinDrag { slot, at } => {
                // The auto view would chase the pin under the finger.
                self.map.take_over();
                self.map.settle = None;
                if self.pick.dragging() != Some(slot) && !self.pick.drag_start(slot) {
                    return;
                }
                let coord = self.map.viewport.unproject(self.map.to_view(at));
                self.pick.drag_to(slot, coord);
            }
            Gesture::PinDrop {
                slot,
                at,
                release_px_s,
                peak_px_s,
                travel_px,
            } => {
                self.map.take_over();
                let at = self.map.to_view(at);
                let input = snap::DropInput {
                    release_px_s,
                    peak_px_s,
                    candidate_px: None,
                    travel_px,
                    started_on_candidate: false,
                };
                let origin = self.pick.drag_origin(slot).cloned();
                let released = self.map.viewport.unproject(at);
                let dest = drop_destination(
                    self.gtfs.as_deref(),
                    &self.map.viewport,
                    self.map.size(),
                    at,
                    &input,
                    origin.as_ref(),
                );
                let settle =
                    settle_for_drop(slot, released, &dest, window.prefers_reduced_motion());
                // Only a new snap buzzes, not a nudge that stays on its stop.
                let newly_snapped = dest.gtfs_stop.is_some()
                    && dest.gtfs_stop != origin.as_ref().and_then(|o| o.gtfs_stop.clone());
                if newly_snapped {
                    haptics::perform(window, HapticFeedback::Selection);
                }
                self.map.settle = settle;
                self.pick.drag_end(slot, dest);
            }
            Gesture::PinCancel { .. } => self.pick.drag_cancel(),
        }
    }

    /// A tap at `at` (pixels from the view centre) on the idle map drops the
    /// next pin: on the stop under it when there is one, else on the point
    /// itself. The view does not move. The map only looks while a trip runs.
    fn map_tap(&mut self, at: (f32, f32), cx: &mut Context<Self>) {
        // The tap is judged when it lands, after the double-tap window: by then
        // a sheet may have opened or another tab been chosen.
        if self.trip.is_some()
            || self.sheet.is_visible()
            || self.alarm.is_some()
            || self.tabs.selected() != 0
        {
            return;
        }
        // While measuring, a tap is a point on the ruler and never a pin.
        if self.map.measure.is_some() {
            self.map.take_over();
            if self.map.measure_tap(at) == MeasureTap::Full {
                self.show_pill(
                    format!("Up to {} points", rovsti_geo::measure::MAX_POINTS),
                    None,
                    cx,
                );
            } else {
                cx.notify();
            }
            return;
        }
        // Keep the view where the user tapped: the idle fit would otherwise
        // recentre on the new pin and move it from under the finger.
        self.map.take_over();
        let feed = self.gtfs.as_deref();
        let size = self.map.size();
        let near = feed.and_then(|feed| {
            let index = pick_stop(feed, &self.map.viewport, size, at, snap::TAP_RADIUS_PX)?;
            let stop = feed.stops.get(index as usize)?;
            Some(dist(self.map.viewport.project(stop.coord), at))
        });
        let dest = if snap::tap_snaps(near) {
            snap_destination(feed, &self.map.viewport, size, at)
        } else {
            Destination::dropped(self.map.viewport.unproject(at))
        };
        match self.pick.tap(dest) {
            TapOutcome::Placed(_) | TapOutcome::MenuClosed => cx.notify(),
            TapOutcome::Full => {
                self.show_pill("Drag a pin to adjust it, or tap Clear.", None, cx);
            }
        }
    }

    /// The my-location button: centre on the last known position at once, then
    /// ask for a fresh fix (the permission first when it is missing).
    pub(crate) fn locate_me(&mut self, cx: &mut Context<Self>) {
        self.map.ask_locate(now_ms());
        if let Some((at, _)) = self.map.position {
            self.map.take_over();
            self.map.viewport = Viewport::new(at, STREET_MPP);
            cx.notify();
        }
        cx.spawn(async move |this, cx| {
            let executor = cx.background_executor().clone();
            let pre = executor.spawn(async { Preflight::gather() }).await;
            let step = pre
                .next_step()
                .filter(|s| matches!(s, Step::RequestLocation | Step::LocationInSettings));
            if let Some(step) = step {
                executor.spawn(async move { step.perform() }).await;
            }
            let asked = match step {
                Some(_) => None,
                None => Some(executor.spawn(async { location::request_location() }).await),
            };
            this.update(cx, |this, cx| {
                this.preflight = pre;
                if step.is_some() || matches!(asked, Some(Ok(false) | Err(_))) {
                    this.map.cancel_locate();
                }
                // Granted in the dialog: carry on without a second tap on "Me".
                this.map.locate_after_grant = step == Some(Step::RequestLocation);
                let text = match (step, asked) {
                    (Some(step), _) => step.message(),
                    (None, Some(Ok(true))) => "Looking for your location...",
                    (None, Some(Ok(false))) => "Turn on location to see where you are.",
                    (None, Some(Err(rovsti_android::Error::Unsupported))) => {
                        "Location is not available here."
                    }
                    (None, _) => "Could not get your location.",
                };
                this.show_pill(text, None, cx);
            })
            .ok();
        })
        .detach();
    }

    /// The permissions changed (the app is back from a prompt): when "Me"
    /// asked for location and it is granted now, look for the fix.
    pub(crate) fn locate_after_permission(&mut self, cx: &mut Context<Self>) {
        if !std::mem::take(&mut self.map.locate_after_grant) {
            return;
        }
        cx.spawn(async move |this, cx| {
            let pre = cx
                .background_executor()
                .spawn(async { Preflight::gather() })
                .await;
            if pre.location == Some(rovsti_android::permissions::Access::Granted) {
                this.update(cx, |this, cx| this.locate_me(cx)).ok();
            }
        })
        .detach();
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

    #[test]
    fn a_startup_fix_does_not_move_a_view_the_user_took_over() {
        let mut m = MapState {
            follow: false,
            viewport: Viewport::new(IST, 40.0),
            ..MapState::default()
        };
        m.set_device_fix(Coord::new(40.0, 30.0), 12.0, 1_000);
        assert_eq!(m.viewport.center, IST);
        assert_eq!(m.viewport.metres_per_px, 40.0);
        assert_eq!(m.position, Some((Coord::new(40.0, 30.0), 12.0)));
    }

    #[test]
    fn an_asked_for_fix_centres_at_street_level_once() {
        let mut m = MapState::default();
        m.ask_locate(1_000);
        let fix = Coord::new(40.0, 30.0);
        m.set_device_fix(fix, 5.0, 1_000);
        assert_eq!(m.viewport.center, fix);
        assert_eq!(m.viewport.metres_per_px, STREET_MPP);
        assert!(!m.follow);
        m.set_device_fix(Coord::new(40.5, 30.5), 5.0, 1_000);
        assert_eq!(m.viewport.center, fix);
    }

    #[test]
    fn a_fix_long_after_the_press_leaves_the_view_alone() {
        let mut m = MapState {
            follow: false,
            viewport: Viewport::new(IST, 40.0),
            ..MapState::default()
        };
        m.ask_locate(1_000);
        m.set_device_fix(Coord::new(40.0, 30.0), 5.0, 1_000 + LOCATE_WAIT_MS + 1);
        assert_eq!(m.viewport.center, IST);
        // The stale request is spent: a later fix does not move it either.
        m.set_device_fix(Coord::new(40.0, 30.0), 5.0, 1_000 + LOCATE_WAIT_MS + 2);
        assert_eq!(m.viewport.center, IST);
    }

    #[test]
    fn a_following_idle_view_uses_the_fix() {
        let mut m = MapState {
            follow: true,
            ..MapState::default()
        };
        let fix = Coord::new(40.0, 30.0);
        m.set_device_fix(fix, 5.0, 1_000);
        let vp = idle_view(
            &Ends::default(),
            m.position.map(|p| p.0),
            None,
            (400.0, 800.0),
        );
        assert_eq!(vp.center, fix);
    }

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

    fn named_stop(id: &str, name: &str, east: f64) -> Stop {
        Stop {
            id: id.into(),
            name: name.into(),
            ..near(east, 0.0)
        }
    }

    #[test]
    fn a_tap_near_a_stop_snaps_to_it_and_far_away_stays_a_raw_point() {
        let feed = Feed::new(
            vec![
                named_stop("S1", "Kadikoy Pier", 0.0),
                named_stop("S2", "Moda", 1_000.0),
            ],
            vec![],
            vec![],
            0,
        );
        // 10 m/px: S2 sits 100 px right of the centre.
        let vp = Viewport::new(IST, 10.0);
        let size = (400.0, 300.0);

        let snapped = snap_destination(Some(&feed), &vp, size, (103.0, -4.0));
        assert_eq!(snapped.label, "Moda");
        assert_eq!(snapped.gtfs_stop.as_deref(), Some("S2"));
        assert_eq!(snapped.coord, feed.stops[1].coord);
        assert!(snapped.is_named());

        let raw = snap_destination(Some(&feed), &vp, size, (50.0, 0.0));
        assert!(!raw.is_named());
        assert_eq!(raw.gtfs_stop, None);
        assert_eq!(raw.coord, vp.unproject((50.0, 0.0)));

        let no_feed = snap_destination(None, &vp, size, (0.0, 0.0));
        assert!(!no_feed.is_named());
    }

    /// A saved stop whose gate runs from `a` to `b`.
    fn add_gate(saved: &mut SavedStops, name: &str, a: Coord, b: Coord) -> u64 {
        let end = |coord| rovsti_geo::Endpoint {
            label: String::new(),
            coord,
        };
        saved.add(name, end(a), end(b), 0).unwrap()
    }

    #[test]
    fn saved_key_changes_with_the_stops_and_either_end() {
        let vp = Viewport::new(IST, 10.0);
        let at = |x: f32, y: f32| vp.unproject((x, y));
        let mut a = SavedStops::default();
        let empty = saved_key(&a);
        add_gate(&mut a, "One", at(0.0, 0.0), at(0.0, 60.0));
        let one = saved_key(&a);
        assert_ne!(empty, one);
        let id = add_gate(&mut a, "Two", at(100.0, 0.0), at(100.0, 60.0));
        let two = saved_key(&a);
        assert_ne!(one, two);
        let mut b = SavedStops::default();
        add_gate(&mut b, "One", at(0.0, 0.0), at(0.0, 60.0));
        add_gate(&mut b, "Two", at(100.0, 0.0), at(100.0, 60.0));
        assert_eq!(saved_key(&b), two);
        // Moving only the B end changes the key too.
        let mut c = SavedStops::default();
        add_gate(&mut c, "One", at(0.0, 0.0), at(0.0, 60.0));
        add_gate(&mut c, "Two", at(100.0, 0.0), at(100.0, 80.0));
        assert_ne!(saved_key(&c), two);
        a.remove(id);
        assert_eq!(saved_key(&a), one);
    }

    // -- polyline thinning --

    // -- caching --

    fn scene_key(viewport: Viewport) -> SceneKey {
        SceneKey {
            viewport,
            size: (400.0, 300.0),
            position: None,
            diameter: None,
            pins: [None, None],
            trail: 0,
            measure: Vec::new(),
            saved: 0,
            feed: 0,
            pattern: None,
            cover: tiles_for_view(&viewport, (400.0, 300.0), VIEW_TILE_CAP),
            tiles: 0,
        }
    }

    #[test]
    fn the_same_inputs_reuse_the_scene_and_a_change_rebuilds_it() {
        let mut m = MapState::default();
        let key = scene_key(Viewport::new(IST, 10.0));
        assert!(m.cached_scene(&key).is_none());
        let (serial, first) = m.store_scene(key.clone(), Scene::default());
        let (again, cached) = m.cached_scene(&key).unwrap();
        assert_eq!(again, serial);
        assert!(Rc::ptr_eq(&first, &cached));
        // A pan, a loaded tile or a new trail point is a different key.
        let mut panned = key.clone();
        panned.viewport.pan(5.0, 0.0);
        assert!(m.cached_scene(&panned).is_none());
        let loaded = SceneKey {
            tiles: 1,
            ..key.clone()
        };
        assert!(m.cached_scene(&loaded).is_none());
        let (next, _) = m.store_scene(loaded, Scene::default());
        assert_ne!(next, serial);
        m.forget_scene();
        assert!(m.cached_scene(&key).is_none());
    }

    #[test]
    fn the_trail_revision_moves_with_the_trail() {
        let mut trail = Breadcrumb::default();
        let r0 = trail.revision();
        trail.record(Some(1), IST, 5.0);
        let r1 = trail.revision();
        assert_ne!(r0, r1);
        // A fix that barely moved adds nothing and keeps the revision.
        trail.record(Some(1), IST, 5.0);
        assert_eq!(trail.revision(), r1);
        trail.clear();
        assert_ne!(trail.revision(), r1);
    }

    #[test]
    fn a_shifted_path_moves_every_vertex() {
        let mut builder = PathBuilder::fill();
        add_circle(&mut builder, (0.0, 0.0), 10.0, true);
        let path = builder.build().unwrap();
        let by = point(px(100.0), px(50.0));
        let moved = shifted(&path, by);
        assert_eq!(moved.vertices.len(), path.vertices.len());
        for (a, b) in path.vertices.iter().zip(&moved.vertices) {
            assert_eq!(b.xy_position, a.xy_position + by);
        }
        assert_eq!(moved.bounds.origin, path.bounds.origin + by);
    }

    #[test]
    fn the_scene_tessellates_around_the_centre() {
        let scene = Scene {
            position: Some(((0.0, 0.0), 20.0)),
            pins: vec![PinScene {
                slot: Slot::A,
                at: (30.0, -10.0),
                snapped: true,
            }],
            ..Scene::default()
        };
        let paths = scene_paths(&scene, None, &Palette::new(Theme::Light));
        // Accuracy, halo and dot; the pin's rim, disc and snapped ring.
        assert_eq!(paths.len(), 6);
        let first = &paths[0].0.bounds;
        assert!(first.origin.x < px(0.0) && first.origin.y < px(0.0));
    }

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
    fn a_mouse_sequence_right_after_a_touch_is_its_echo() {
        let mut g = Gestures::default();
        tap(&mut g, (10.0, 10.0), 1_000);
        assert_eq!(mouse_click(&mut g, (10.0, 10.0), 1_100), None);
        // The echo did not turn the tap into a double tap.
        assert_eq!(g.due_tap(1_400), Some((10.0, 10.0)));
        // A desktop mouse never touches: clicks count, twice is a double click.
        let mut m = Gestures::default();
        assert_eq!(mouse_click(&mut m, (5.0, 5.0), 100), None);
        assert_eq!(
            mouse_click(&mut m, (5.0, 5.0), 250),
            Some(Gesture::DoubleTap { at: (5.0, 5.0) })
        );
    }

    fn mouse_click(g: &mut Gestures, at: (f32, f32), t: u64) -> Option<Gesture> {
        assert_eq!(g.mouse(Phase::Start, at, t), None);
        g.mouse(Phase::End, at, t + 20)
    }

    #[test]
    fn the_mouse_pans_and_drags_pins() {
        let mut g = Gestures::default();
        g.mouse(Phase::Start, (100.0, 100.0), 0);
        assert_eq!(
            g.mouse(Phase::Move, (120.0, 100.0), 10),
            Some(Gesture::Pan { dx: 20.0, dy: 0.0 })
        );
        assert_eq!(g.mouse(Phase::End, (120.0, 100.0), 20), None);
        assert_eq!(g.due_tap(10_000), None);
        g.set_handles(&[(Slot::A, (50.0, 50.0))]);
        g.mouse(Phase::Start, (52.0, 50.0), 5_000);
        assert_eq!(
            g.mouse(Phase::Move, (92.0, 50.0), 5_010),
            Some(Gesture::PinDrag {
                slot: Slot::A,
                at: (90.0, 50.0)
            })
        );
        assert_eq!(
            g.mouse(Phase::End, (92.0, 50.0), 5_020),
            Some(Gesture::PinDrop {
                slot: Slot::A,
                at: (90.0, 50.0),
                // 40 px from the grab sample (t=5000) to the release (t=5020).
                release_px_s: 2_000.0,
                peak_px_s: 4_000.0,
                travel_px: 40.0,
            })
        );
    }

    fn with_pin() -> Gestures {
        let mut g = Gestures::default();
        g.set_handles(&[(Slot::A, (100.0, 100.0))]);
        g
    }

    #[test]
    fn dragging_a_pin_never_pans_and_keeps_the_grab_offset() {
        let mut g = with_pin();
        g.touch(1, Phase::Start, (110.0, 95.0), 0);
        assert_eq!(g.touch(1, Phase::Move, (112.0, 95.0), 10), None);
        assert_eq!(
            g.touch(1, Phase::Move, (150.0, 95.0), 20),
            Some(Gesture::PinDrag {
                slot: Slot::A,
                at: (140.0, 100.0)
            })
        );
        assert_eq!(
            g.touch(1, Phase::Move, (150.0, 120.0), 30),
            Some(Gesture::PinDrag {
                slot: Slot::A,
                at: (140.0, 125.0)
            })
        );
        assert_eq!(
            g.touch(1, Phase::End, (150.0, 120.0), 40),
            Some(Gesture::PinDrop {
                slot: Slot::A,
                at: (140.0, 125.0),
                // Straight-line (40, 25) from the grab point over the 40 ms window.
                release_px_s: 1179.2477,
                peak_px_s: 2_000.0,
                travel_px: 47.169907,
            })
        );
        assert_eq!(g.due_tap(10_000), None);
    }

    fn drop_of(g: &mut Gestures, moves: &[((f32, f32), u64)], end: ((f32, f32), u64)) -> Gesture {
        g.touch(1, Phase::Start, (100.0, 100.0), 0);
        for &(at, t) in moves {
            g.touch(1, Phase::Move, at, t);
        }
        g.touch(1, Phase::End, end.0, end.1).expect("a drop")
    }

    #[test]
    fn a_fast_drag_reports_a_release_speed_above_the_fast_threshold() {
        let mut g = with_pin();
        let drop = drop_of(
            &mut g,
            &[
                ((140.0, 100.0), 20),
                ((180.0, 100.0), 40),
                ((220.0, 100.0), 60),
            ],
            ((220.0, 100.0), 62),
        );
        let Gesture::PinDrop {
            release_px_s,
            peak_px_s,
            travel_px,
            ..
        } = drop
        else {
            panic!("expected a drop, got {drop:?}");
        };
        assert!(release_px_s > snap::FAST_PX_S, "{release_px_s}");
        assert!(peak_px_s >= release_px_s);
        assert!((travel_px - 120.0).abs() < 0.01);
    }

    #[test]
    fn a_drag_that_pauses_before_lifting_reports_about_zero() {
        let mut g = with_pin();
        let drop = drop_of(
            &mut g,
            &[
                ((140.0, 100.0), 20),
                ((180.0, 100.0), 40),
                ((220.0, 100.0), 60),
            ],
            ((220.0, 100.0), 210),
        );
        let Gesture::PinDrop { release_px_s, .. } = drop else {
            panic!("expected a drop, got {drop:?}");
        };
        assert!(release_px_s < 1.0, "{release_px_s}");
    }

    fn drop_input(release: f32, peak: f32, travel: f32) -> snap::DropInput {
        snap::DropInput {
            release_px_s: release,
            peak_px_s: peak,
            candidate_px: None,
            travel_px: travel,
            started_on_candidate: false,
        }
    }

    fn drop_feed() -> Feed {
        Feed::new(vec![named_stop("S1", "Moda", 0.0)], vec![], vec![], 0)
    }

    #[test]
    fn the_settle_lerp_runs_from_release_to_stop() {
        let (from, to) = (Coord::new(10.0, 20.0), Coord::new(11.0, 22.0));
        assert_eq!(lerp_coord(from, to, 0.0), from);
        assert_eq!(lerp_coord(from, to, 1.0), to);
        let mid = lerp_coord(from, to, 0.5);
        assert!((mid.lat - 10.5).abs() < 1e-9 && (mid.lon - 21.0).abs() < 1e-9);
        let settle = PinSettle::new(Slot::A, from);
        assert_eq!(settle.coord(to), from, "starts where the finger let go");
    }

    #[test]
    fn only_a_snap_drop_starts_a_settle() {
        let released = Coord::new(1.0, 1.0);
        let free = Destination::dropped(released);
        assert!(settle_for_drop(Slot::A, released, &free, false).is_none());
        let stop = Destination {
            label: "Moda".into(),
            coord: Coord::new(1.001, 1.001),
            gtfs_stop: Some("S2".into()),
            pattern: None,
        };
        let settle = settle_for_drop(Slot::B, released, &stop, false).expect("snap settles");
        assert_eq!(settle.slot, Slot::B);
        assert_eq!(settle.from, released);
        assert!(settle_for_drop(Slot::B, released, &stop, true).is_none());
    }

    #[test]
    fn a_slow_drop_near_a_stop_stays_raw_and_a_fast_one_snaps() {
        let feed = drop_feed();
        // 10 m/px: the stop sits at the centre; the drop is 30 px right.
        let vp = Viewport::new(IST, 10.0);
        let size = (400.0, 300.0);
        let at = (30.0, 0.0);
        let slow = drop_destination(
            Some(&feed),
            &vp,
            size,
            at,
            &drop_input(50.0, 80.0, 200.0),
            None,
        );
        assert_eq!(slow.gtfs_stop, None);
        assert!(!slow.is_named());
        let fast = drop_destination(
            Some(&feed),
            &vp,
            size,
            at,
            &drop_input(1_200.0, 1_200.0, 200.0),
            None,
        );
        assert_eq!(fast.gtfs_stop.as_deref(), Some("S1"));
        assert_eq!(fast.coord, feed.stops[0].coord);
        // Without a feed there is nothing to snap to.
        let none = drop_destination(
            None,
            &vp,
            size,
            at,
            &drop_input(1_200.0, 1_200.0, 200.0),
            None,
        );
        assert_eq!(none.gtfs_stop, None);
    }

    #[test]
    fn a_small_slow_nudge_of_a_snapped_pin_stays_snapped() {
        let feed = drop_feed();
        let vp = Viewport::new(IST, 10.0);
        let size = (400.0, 300.0);
        let origin = snap_destination(Some(&feed), &vp, size, (0.0, 0.0));
        assert_eq!(origin.gtfs_stop.as_deref(), Some("S1"));
        let nudged = drop_destination(
            Some(&feed),
            &vp,
            size,
            (6.0, 0.0),
            &drop_input(20.0, 30.0, 6.0),
            Some(&origin),
        );
        assert_eq!(nudged.gtfs_stop.as_deref(), Some("S1"));
        // A slow drag away beyond the hold travel unsnaps.
        let away = drop_destination(
            Some(&feed),
            &vp,
            size,
            (30.0, 0.0),
            &drop_input(20.0, 30.0, 30.0),
            Some(&origin),
        );
        assert_eq!(away.gtfs_stop, None);
    }

    #[test]
    fn a_still_touch_on_a_pin_is_an_immediate_pin_tap() {
        let mut g = with_pin();
        assert_eq!(g.touch(1, Phase::Start, (105.0, 100.0), 0), None);
        assert_eq!(
            g.touch(1, Phase::End, (105.0, 100.0), 50),
            Some(Gesture::PinTap { slot: Slot::A })
        );
        assert_eq!(g.due_tap(10_000), None);
    }

    #[test]
    fn a_touch_40px_from_a_pin_pans() {
        let mut g = with_pin();
        g.touch(1, Phase::Start, (140.0, 100.0), 0);
        assert_eq!(
            g.touch(1, Phase::Move, (170.0, 100.0), 10),
            Some(Gesture::Pan { dx: 30.0, dy: 0.0 })
        );
    }

    #[test]
    fn a_second_finger_cancels_the_grab_and_pinches() {
        let mut g = with_pin();
        g.touch(1, Phase::Start, (100.0, 100.0), 0);
        assert_eq!(
            g.touch(2, Phase::Start, (200.0, 100.0), 5),
            Some(Gesture::PinCancel { slot: Slot::A })
        );
        assert_eq!(
            g.touch(2, Phase::Move, (250.0, 100.0), 10),
            Some(Gesture::Zoom {
                factor: 1.5,
                focal: (175.0, 100.0)
            })
        );
    }

    #[test]
    fn cancel_cancels_the_grab() {
        let mut g = with_pin();
        g.touch(1, Phase::Start, (100.0, 100.0), 0);
        g.touch(1, Phase::Move, (150.0, 100.0), 10);
        assert_eq!(
            g.touch(1, Phase::Cancel, (150.0, 100.0), 20),
            Some(Gesture::PinCancel { slot: Slot::A })
        );
        assert_eq!(g.due_tap(10_000), None);
    }

    #[test]
    fn the_nearest_of_two_handles_wins() {
        let mut g = Gestures::default();
        g.set_handles(&[(Slot::A, (100.0, 100.0)), (Slot::B, (120.0, 100.0))]);
        g.touch(1, Phase::Start, (112.0, 100.0), 0);
        assert_eq!(
            g.touch(1, Phase::End, (112.0, 100.0), 10),
            Some(Gesture::PinTap { slot: Slot::B })
        );
    }

    #[test]
    fn the_zoom_buttons_are_not_the_maps() {
        let size = (370.0, 340.0);
        assert!(over_controls(size, (350.0, 20.0)));
        assert!(over_controls(size, (330.0, 150.0)));
        assert!(!over_controls(size, (350.0, 240.0)));
        assert!(!over_controls(size, (100.0, 20.0)));
    }

    // -- the measure tool --

    #[test]
    fn a_tap_while_measuring_adds_a_point_and_is_no_pin() {
        let mut map = MapState::default();
        assert_eq!(map.measure_tap((10.0, 10.0)), MeasureTap::Off);
        map.start_measure();
        assert_eq!(map.measure_tap((10.0, 10.0)), MeasureTap::Added);
        assert_eq!(map.measure_tap((80.0, -20.0)), MeasureTap::Added);
        let points = map.measure_points();
        assert_eq!(points.len(), 2);
        assert_eq!(points[0], map.viewport.unproject((10.0, 10.0)));
        // Pins cannot be grabbed (so a touch on one pans or taps the ruler).
        assert!(!pins_grabbable(false, false, true));
        assert!(pins_grabbable(false, false, false));
        assert!(!pins_grabbable(true, false, false));
    }

    #[test]
    fn the_point_cap_stops_taps_and_ending_forgets_the_points() {
        let mut map = MapState::default();
        map.start_measure();
        for _ in 0..rovsti_geo::measure::MAX_POINTS {
            assert_eq!(map.measure_tap((0.0, 0.0)), MeasureTap::Added);
        }
        assert_eq!(map.measure_tap((0.0, 0.0)), MeasureTap::Full);
        assert!(map.end_measure());
        assert!(!map.end_measure());
        assert!(map.measure_points().is_empty());
    }

    #[test]
    fn clear_asks_twice_only_for_longer_measurements() {
        assert_eq!(clear_step(0, false), ClearStep::Clear);
        assert_eq!(clear_step(2, false), ClearStep::Clear);
        assert_eq!(clear_step(3, false), ClearStep::Arm);
        assert_eq!(clear_step(3, true), ClearStep::Clear);
    }

    #[test]
    fn the_summary_names_total_and_legs() {
        let mut m = Measure::new();
        assert_eq!(measure_summary(&m), "Tap the map to add points");
        m.push(IST);
        assert_eq!(measure_summary(&m), "Tap a second point");
        m.push(near(1_000.0, 0.0).coord);
        assert!(measure_summary(&m).ends_with(", 1 leg"));
        m.push(near(1_000.0, 1_000.0).coord);
        let text = measure_summary(&m);
        assert!(
            text.starts_with("Total 2.0 km") && text.ends_with(", 2 legs"),
            "{text}"
        );
    }

    #[test]
    fn the_scene_projects_the_measure_points_and_the_ruler_is_a_control() {
        let trail = Breadcrumb::default();
        let pts = [near(100.0, 0.0).coord, near(0.0, 100.0).coord];
        let mut input = scene_input(&trail, None);
        input.measure = &pts;
        let scene = Scene::build(&input);
        assert_eq!(
            scene.measure,
            pts.iter()
                .map(|&c| input.viewport.project(c))
                .collect::<Vec<_>>()
        );
        // The fourth control (the ruler) is still over the control column.
        assert!(over_controls((370.0, 460.0), (350.0, 175.0)));
    }

    // -- the scene --

    fn scene_input<'a>(trail: &'a Breadcrumb, feed: Option<&'a Feed>) -> SceneInput<'a> {
        SceneInput {
            viewport: Viewport::new(IST, 10.0),
            size: (400.0, 300.0),
            position: None,
            diameter: None,
            pins: [None, None],
            trail,
            feed,
            pattern: None,
            saved: &[],
            measure: &[],
            tiles: &[],
        }
    }

    #[test]
    fn saved_stop_rings_in_view_are_projected() {
        let trail = Breadcrumb::default();
        let near_ring = near(100.0, 50.0).coord;
        let far_ring = near(50_000.0, 0.0).coord;
        let rings = [near_ring, far_ring];
        let mut input = scene_input(&trail, None);
        input.saved = &rings;
        let scene = Scene::build(&input);
        assert_eq!(scene.saved, vec![input.viewport.project(near_ring)]);
        assert!(in_view((212.0, 0.0), (400.0, 300.0), 24.0));
        assert!(!in_view((225.0, 0.0), (400.0, 300.0), 24.0));
    }

    /// A 600 m east-west diameter centred 300 m north of IST.
    fn diameter_600() -> Diameter {
        Diameter::new(near(-300.0, 300.0).coord, near(300.0, 300.0).coord).unwrap()
    }

    #[test]
    fn the_scene_puts_things_where_the_viewport_does() {
        let mut trail = Breadcrumb::default();
        trail.record(Some(1), IST, 10.0);
        trail.record(Some(1), near(0.0, 100.0).coord, 10.0);
        let mut input = scene_input(&trail, None);
        input.position = Some((near(100.0, 0.0).coord, 25.0));
        input.diameter = Some(diameter_600());
        let scene = Scene::build(&input);

        let ((x, y), r) = scene.position.unwrap();
        assert!((x - 10.0).abs() < 0.01 && y.abs() < 0.01, "{x} {y}");
        assert!((r - 2.5).abs() < 1e-4);
        // 10 m per pixel: the centre is 30 px up, the radius 30 px.
        let d = scene.diameter.as_ref().unwrap();
        let ((cx, cy), ring_r) = (d.centre, d.radius_px);
        assert!(cx.abs() < 0.05 && (cy + 30.0).abs() < 0.05, "{cx} {cy}");
        assert!((ring_r - 30.0).abs() < 0.1, "{ring_r}");
        // A west, B east, on the circle's horizontal diameter.
        let ((ax, ay), (bx, by)) = (d.a, d.b);
        assert!((ax + 30.0).abs() < 0.1 && (bx - 30.0).abs() < 0.1);
        assert!((ay + 30.0).abs() < 0.1 && (by + 30.0).abs() < 0.1);
        assert_eq!(scene.trail.len(), 2);
        assert!(scene.stops.is_empty() && scene.line.is_empty());
        assert!(Scene::build(&scene_input(&trail, None)).diameter.is_none());
    }

    /// Which side of the screen vector `a -> b` the point `p` is on, in pixel
    /// space where y points down: positive is the viewer's left.
    fn screen_left(a: (f32, f32), b: (f32, f32), p: (f32, f32)) -> f32 {
        // With y down, the cross product's sign flips against the maths one.
        -((b.0 - a.0) * (p.1 - a.1) - (b.1 - a.1) * (p.0 - a.0))
    }

    #[test]
    fn the_left_half_is_on_the_left_of_a_to_b_on_screen() {
        let trail = Breadcrumb::default();
        // West to east: left is north, which is up on screen (negative y).
        let mut input = scene_input(&trail, None);
        input.diameter = Some(diameter_600());
        let scene = Scene::build(&input);
        let d = scene.diameter.unwrap();
        assert!(d.left_label.1 < d.centre.1 && d.right_label.1 > d.centre.1);
        // Half a radius out: 15 px.
        assert!((d.centre.1 - d.left_label.1 - 15.0).abs() < 0.2, "{d:?}");
        assert!((d.left_label.0 - d.centre.0).abs() < 0.2);
        for p in &d.left[1..d.left.len() - 1] {
            assert!(screen_left(d.a, d.b, *p) > 0.0, "{p:?}");
        }
        for p in &d.right[1..d.right.len() - 1] {
            assert!(screen_left(d.a, d.b, *p) < 0.0, "{p:?}");
        }
        assert!(screen_left(d.a, d.b, d.left_label) > 0.0);
        assert!(screen_left(d.a, d.b, d.right_label) < 0.0);
        // Both polygons run from A to B.
        for half in [&d.left, &d.right] {
            assert_eq!(half.len(), HALF_DISC_SEGMENTS + 1);
            assert_eq!(half[0], d.a);
            assert_eq!(*half.last().unwrap(), d.b);
        }

        // Swapping A and B swaps the halves; a north-south AB works too.
        let swapped = Diameter::new(near(300.0, 300.0).coord, near(-300.0, 300.0).coord).unwrap();
        input.diameter = Some(swapped);
        let d = Scene::build(&input).diameter.unwrap();
        assert!(d.left_label.1 > d.centre.1);
        assert!(screen_left(d.a, d.b, d.left_label) > 0.0);
        let ns = Diameter::new(near(0.0, 0.0).coord, near(0.0, 600.0).coord).unwrap();
        input.diameter = Some(ns);
        let d = Scene::build(&input).diameter.unwrap();
        // South to north: left is west, so the left label is at smaller x.
        assert!(d.left_label.0 < d.centre.0 && d.right_label.0 > d.centre.0);
        assert!(screen_left(d.a, d.b, d.left_label) > 0.0);
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
        input.diameter =
            Some(Diameter::new(near(0.0, 0.0).coord, near(0.0, 40_000.0).coord).unwrap());
        input.viewport = Viewport::new(IST, 0.001);
        let scene = Scene::build(&input);
        assert_eq!(scene.position.unwrap().1, MAX_RADIUS_PX);
        assert_eq!(scene.diameter.unwrap().radius_px, MAX_RADIUS_PX);
    }

    // -- pins, segment, opening view, menu --

    fn dest_at(coord: Coord) -> Destination {
        Destination::dropped(coord)
    }

    fn ends_of(a: Option<Coord>, b: Option<Coord>) -> Ends {
        Ends {
            a: a.map(dest_at),
            b: b.map(dest_at),
        }
    }

    #[test]
    fn a_pin_appears_for_every_end_that_is_set() {
        let trail = Breadcrumb::default();
        let mut input = scene_input(&trail, None);
        input.pins = [None, Some((near(100.0, 0.0).coord, true))];
        let scene = Scene::build(&input);
        assert_eq!(scene.pins.len(), 1);
        assert_eq!(scene.pins[0].slot, Slot::B);
        assert!(scene.pins[0].snapped);
        assert!(scene.segment.is_none());
        assert!(scene.diameter.is_none());
    }

    #[test]
    fn an_invalid_pair_shows_pins_and_a_segment_but_no_circle() {
        let trail = Breadcrumb::default();
        let mut input = scene_input(&trail, None);
        let (a, b) = (near(0.0, 0.0).coord, near(5.0, 0.0).coord);
        input.pins = [Some((a, false)), Some((b, false))];
        let scene = Scene::build(&input);
        assert_eq!(scene.pins.len(), 2);
        assert_eq!(scene.pins[0].slot, Slot::A);
        let (sa, sb) = scene.segment.unwrap();
        assert_eq!(sa, scene.pins[0].at);
        assert_eq!(sb, scene.pins[1].at);
    }

    #[test]
    fn a_valid_pair_has_a_circle_and_no_segment() {
        let trail = Breadcrumb::default();
        let mut input = scene_input(&trail, None);
        let d = diameter_600();
        input.diameter = Some(d);
        input.pins = [Some((d.a(), false)), Some((d.b(), false))];
        let scene = Scene::build(&input);
        assert!(scene.diameter.is_some());
        assert_eq!(scene.pins.len(), 2);
        assert!(scene.segment.is_none());
    }

    #[test]
    fn the_idle_view_follows_its_priority_chain() {
        let size = (370.0, 340.0);
        let pos = near(50.0, 50.0).coord;
        let fallback = near(900.0, 900.0).coord;
        let a = near(-300.0, 0.0).coord;
        let b = near(300.0, 0.0).coord;

        // Both ends valid: the whole circle fits.
        let both = ends_of(Some(a), Some(b));
        let vp = idle_view(&both, Some(pos), Some(fallback), size);
        let d = Diameter::new(a, b).unwrap();
        assert_eq!(vp, fit_view(None, Some(&d), size).unwrap());

        // Both ends invalid (5 m apart): the two points, never zoomed out past street level.
        let close = ends_of(Some(a), Some(near(-295.0, 0.0).coord));
        let vp = idle_view(&close, Some(pos), Some(fallback), size);
        assert_eq!(vp.metres_per_px, STREET_MPP);
        assert!(vp.project(a).0.abs() < 10.0);

        // One end: that end at street level, wherever you are.
        let one = ends_of(None, Some(b));
        let vp = idle_view(&one, Some(pos), Some(fallback), size);
        assert_eq!(vp.metres_per_px, STREET_MPP);
        assert!(vp.project(b).0.abs() < 0.01);

        // No ends: you, then the fallback, then the default city.
        let none = Ends::default();
        let vp = idle_view(&none, Some(pos), Some(fallback), size);
        assert_eq!(vp.metres_per_px, STREET_MPP);
        assert!(vp.project(pos).0.abs() < 0.01);
        let vp = idle_view(&none, None, Some(fallback), size);
        assert_eq!(vp.metres_per_px, STREET_MPP);
        assert!(vp.project(fallback).0.abs() < 0.01);
        let vp = idle_view(&none, None, None, size);
        assert_eq!(vp.metres_per_px, CITY_MPP);
        assert!(vp.project(DEFAULT_CENTRE).0.abs() < 0.01);
    }

    #[test]
    fn the_fallback_is_the_last_trip_then_the_first_saved_stop() {
        let mut history = History::default();
        let mut saved = SavedStops::default();
        assert_eq!(fallback_centre(&history, &saved), None);

        add_gate(
            &mut saved,
            "Home",
            Coord::new(40.0, 30.0),
            Coord::new(40.004, 30.0),
        );
        add_gate(
            &mut saved,
            "Work",
            Coord::new(41.0, 31.0),
            Coord::new(41.004, 31.0),
        );
        // The middle of the first gate.
        let first = fallback_centre(&history, &saved).unwrap();
        assert!((first.lat - 40.002).abs() < 1e-9 && (first.lon - 30.0).abs() < 1e-9);

        let ep = |lat, lon| rovsti_geo::Endpoint {
            label: String::new(),
            coord: Coord::new(lat, lon),
        };
        history.push(rovsti_geo::TripRecord {
            a: Some(ep(41.0, 29.0)),
            b: Some(ep(41.2, 29.2)),
            ..Default::default()
        });
        let c = fallback_centre(&history, &saved).unwrap();
        assert!((c.lat - 41.1).abs() < 1e-9 && (c.lon - 29.1).abs() < 1e-9);
    }

    #[test]
    fn the_pin_menu_stays_inside_the_map() {
        let size = (370.0, 340.0);
        let inside = |r: (f32, f32, f32, f32)| {
            r.0 >= 8.0 && r.1 >= 8.0 && r.0 + r.2 <= size.0 - 8.0 && r.1 + r.3 <= size.1 - 8.0
        };
        for pin in [
            (0.0, 0.0),
            (size.0, 0.0),
            (0.0, size.1),
            (size.0, size.1),
            (185.0, 170.0),
        ] {
            let r = pin_menu_rect(pin, size);
            assert!(inside(r), "{pin:?} -> {r:?}");
            assert_eq!((r.2, r.3), (PIN_MENU_W, PIN_MENU_H));
        }
    }

    #[test]
    fn a_stop_pin_finds_its_feed_index_for_the_lines_row() {
        let feed = Feed::new(
            vec![named_stop("S1", "One", 0.0), named_stop("S2", "Two", 50.0)],
            vec![],
            vec![],
            0,
        );
        assert_eq!(stop_index(Some(&feed), "S2"), Some(1));
        assert_eq!(stop_index(Some(&feed), "nope"), None);
        assert_eq!(stop_index(None, "S1"), None);
    }

    #[test]
    fn the_menu_rows_fit_the_menu_box() {
        // Every row of a transit stop's menu and the details line.
        const { assert!(pin_menu_rows(true) as f32 * PIN_MENU_ROW_H + 24.0 <= PIN_MENU_H) };
        const { assert!(pin_menu_rows(false) < pin_menu_rows(true)) };
    }

    #[test]
    fn open_in_maps_builds_a_geo_link() {
        let c = Coord::new(41.0, 28.5);
        let plain = maps_url(c, "  ");
        assert!(plain.starts_with("geo:41"), "{plain}");
        assert!(maps_url(c, "Pier").contains("Pier"));
    }

    #[test]
    fn the_pin_menu_opens_above_and_flips_below_near_the_top() {
        // Tall enough for the menu to fit above the pin (it is PIN_MENU_H high).
        let size = (370.0, 640.0);
        let pin = (185.0, 500.0);
        let above = pin_menu_rect(pin, size);
        assert!(above.1 + above.3 < pin.1);
        let top = (185.0, 40.0);
        let below = pin_menu_rect(top, size);
        assert!(below.1 > top.1);
        assert!(over_rect(below, (below.0 + 1.0, below.1 + 1.0)));
        assert!(!over_rect(below, (below.0 - 1.0, below.1 + 1.0)));
        assert!(!over_rect(below, (below.0 + 1.0, below.1 + below.3 + 1.0)));
    }

    // -- the view --

    #[test]
    fn the_follow_view_holds_you_and_the_whole_circle() {
        let size = (370.0, 340.0);
        let pos = near(-800.0, -600.0).coord;
        let d = Diameter::new(near(600.0, 700.0).coord, near(1_200.0, 700.0).coord).unwrap();
        let vp = fit_view(Some(pos), Some(&d), size).unwrap();
        let c = d.center();
        for p in [
            pos,
            d.a(),
            d.b(),
            d.from_local((0.0, d.radius_m())),
            d.from_local((0.0, -d.radius_m())),
            Coord::new(c.lat, d.b().lon),
        ] {
            let (x, y) = vp.project(p);
            assert!(
                x.abs() <= size.0 / 2.0 && y.abs() <= size.1 / 2.0,
                "{x} {y}"
            );
        }
        // The circle alone is centred; one lonely point sits at a sensible zoom.
        let ring_only = fit_view(None, Some(&d), size).unwrap();
        assert!(ring_only.project(c).0.abs() < 1.0);
        let alone = fit_view(Some(pos), None, size).unwrap();
        assert_eq!(alone.metres_per_px, MIN_FIT_MPP);
        assert!(alone.project(pos).0.abs() < 0.01);
        assert!(fit_view(None, None, size).is_none());
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

    // -- map tiles --

    const FIXTURE: &[u8] =
        include_bytes!("../../../crates/rovsti_geo/src/tiles/testdata/osm-14-9520-6130.mvt");

    fn fixture_tile() -> TileGeometry {
        TileGeometry::from_bytes(
            rovsti_geo::tiles::TileId::new(14, 9520, 6130).unwrap(),
            FIXTURE,
        )
        .unwrap()
    }

    #[test]
    fn overlapping_polygons_do_not_share_a_fill_path() {
        let a = vec![vec![(0.0, 0.0), (10.0, 0.0), (10.0, 10.0)]];
        let b = vec![vec![(5.0, 5.0), (20.0, 5.0), (20.0, 20.0)]];
        let c = vec![vec![(30.0, 30.0), (40.0, 30.0), (40.0, 40.0)]];
        assert!(bboxes_meet(polygon_bbox(&a), polygon_bbox(&b)));
        assert!(!bboxes_meet(polygon_bbox(&a), polygon_bbox(&c)));
    }

    #[test]
    fn the_map_style_follows_the_theme() {
        assert_eq!(Palette::new(Theme::Light).style, MapStyle::Colorful);
        assert_eq!(Palette::new(Theme::Dark).style, MapStyle::Eclipse);
    }

    #[test]
    fn style_colours_keep_their_alpha() {
        let c = Rgba8 {
            r: 255,
            g: 255,
            b: 255,
            a: 0.5,
        };
        let h = style_color(c);
        assert!(
            (h.a - 0.5).abs() < 0.01 && (h.l - 1.0).abs() < 0.01,
            "{h:?}"
        );
    }

    #[test]
    fn no_tiles_leave_the_scene_as_it_was() {
        let trail = Breadcrumb::default();
        let scene = Scene::build(&scene_input(&trail, None));
        assert!(scene.tiles.backgrounds.is_empty() && scene.tiles.groups.is_empty());
    }

    #[test]
    fn tile_features_land_where_the_tile_transform_puts_them() {
        let tile = fixture_tile();
        let centre = rovsti_geo::tiles::frac_to_coord(14, 9520.5, 6130.5);
        let vp = Viewport::new(centre, 3.6);
        let trail = Breadcrumb::default();
        let tiles = [&tile];
        let mut input = scene_input(&trail, None);
        input.viewport = vp;
        input.tiles = &tiles;
        let layer = Scene::build(&input).tiles;

        // The tile is bigger than the view: its background is the view plus the margin.
        assert_eq!(layer.backgrounds.len(), 1);
        let bg = layer.backgrounds[0];
        assert!(
            (bg[0].0 + 208.0).abs() < 0.5 && (bg[0].1 + 158.0).abs() < 0.5,
            "{bg:?}"
        );
        assert!(
            (bg[2].0 - 208.0).abs() < 0.5 && (bg[2].1 - 158.0).abs() < 0.5,
            "{bg:?}"
        );

        // A vertex of the tile that is inside the view is in the layer, same class,
        // at the transformed position.
        let tf = rovsti_geo::tiles::TileTransform::new(tile.id, tile.extent, &vp);
        let wanted = tile.groups.iter().find_map(|(class, g)| {
            let rings = g.lines.iter().chain(g.polygons.iter().flatten());
            rings
                .flatten()
                .map(|&p| tf.apply(p))
                .find_map(|p| (p.0.abs() < 150.0 && p.1.abs() < 100.0).then_some((*class, p)))
        });
        let (class, p) = wanted.expect("a vertex inside the view");
        let (_, g) = layer
            .groups
            .iter()
            .find(|(c, _)| *c == class)
            .expect("class kept");
        let rings = g.lines.iter().chain(g.polygons.iter().flatten());
        assert!(
            rings
                .flatten()
                .any(|q| (q.0 - p.0).abs() < 0.01 && (q.1 - p.1).abs() < 0.01),
            "{p:?} not in {class:?}"
        );
        assert!(layer.vertices() <= TILE_MAX_VERTICES);
    }
}
