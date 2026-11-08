//! `view.*`: the map maths of `rovsti_geo::view` and `rovsti_geo::tiles` (slippy tiles) that the Kotlin
//! `MapCamera` must agree with. The fixtures double as its cross-check data (E7): the same centre, scale and
//! pixel in, the same coordinate or tile set out.

use super::coord;
use crate::envelope::FfiError;
use crate::op;
use crate::ops::registry::{Case, OpSpec};
use rovsti_geo::tiles::{
    MAX_MERCATOR_LAT, TILE_DISPLAY_PX, display_zoom, fetch_zoom, frac_to_coord, tile_containing,
    tile_frac, tiles_for_view,
};
use rovsti_geo::view::{MAX_METRES_PER_PX, MIN_METRES_PER_PX};
use rovsti_geo::{Coord, FollowMode, Viewport};
use serde::Deserialize;
use serde_json::{Value, json};

pub(super) const OPS: &[OpSpec] = &[
    op!("view.project", ProjectReq, Value, project, project_cases),
    op!("view.step", StepReq, Value, step, step_cases),
    op!("view.tiles", TilesReq, Value, tiles, tiles_cases),
    op!("view.frac_to_coord", FracReq, Value, frac, frac_cases),
    op!("view.follow", FollowReq, Value, follow, follow_cases),
];

#[derive(Deserialize, Clone, Copy)]
struct Vp {
    center: Coord,
    metres_per_px: f64,
}

impl Vp {
    fn viewport(self) -> Viewport {
        Viewport::new(self.center, self.metres_per_px)
    }
}

fn vp_json(vp: &Viewport) -> Value {
    json!({"center": vp.center, "metres_per_px": vp.metres_per_px})
}

#[derive(Deserialize)]
struct ProjectReq {
    viewport: Vp,
    #[serde(default)]
    points: Vec<Coord>,
    /// Pixels relative to the viewport centre, x right, y down.
    #[serde(default)]
    pixels: Vec<[f32; 2]>,
}

/// Coordinates to pixels and back, and the zoom the scale stands for.
fn project(req: ProjectReq) -> Result<Value, FfiError> {
    let vp = req.viewport.viewport();
    Ok(json!({
        "viewport": vp_json(&vp),
        "display_zoom": display_zoom(&vp),
        "fetch_zoom": fetch_zoom(display_zoom(&vp)),
        "projected": req.points.iter().map(|&c| { let (x, y) = vp.project(c); [x, y] }).collect::<Vec<_>>(),
        "unprojected": req.pixels.iter().map(|p| vp.unproject((p[0], p[1]))).collect::<Vec<_>>(),
        "min_metres_per_px": MIN_METRES_PER_PX,
        "max_metres_per_px": MAX_METRES_PER_PX,
        "tile_display_px": TILE_DISPLAY_PX,
    }))
}

fn istanbul() -> Value {
    json!({"center": {"lat": 41.0082, "lon": 28.9784}, "metres_per_px": 4.0})
}

fn project_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"viewport": istanbul(),
            "points": [{"lat": 41.0082, "lon": 28.9784}, {"lat": 41.0172, "lon": 28.9884}, {"lat": 40.99, "lon": 28.95}],
            "pixels": [[0.0, 0.0], [120.0, -80.0], [-300.0, 250.0]]})),
        Case::named(
            "clamped_scale",
            json!({"viewport": {"center": {"lat": 0.0, "lon": 0.0}, "metres_per_px": 99999.0},
                                            "points": [{"lat": 1.0, "lon": 1.0}]}),
        ),
        Case::named(
            "high_latitude",
            json!({"viewport": {"center": {"lat": 70.0, "lon": 25.0}, "metres_per_px": 10.0},
                                            "points": [{"lat": 70.001, "lon": 25.002}], "pixels": [[100.0, 100.0]]}),
        ),
    ]
}

#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Step {
    Fit {
        points: Vec<Coord>,
        width: f32,
        height: f32,
        padding: f32,
    },
    ZoomAbout {
        factor: f64,
        focal: [f32; 2],
    },
    Pan {
        dx: f32,
        dy: f32,
    },
    Nearest {
        points: Vec<Coord>,
        at: [f32; 2],
        max_px: f32,
    },
}

#[derive(Deserialize)]
struct StepReq {
    viewport: Vp,
    step: Step,
}

/// One camera gesture: fit to points, pinch about a pixel, pan, or pick the point nearest a tap.
fn step(req: StepReq) -> Result<Value, FfiError> {
    let mut vp = req.viewport.viewport();
    let mut nearest = None;
    match req.step {
        Step::Fit {
            points,
            width,
            height,
            padding,
        } => vp = Viewport::fit(&points, width, height, padding),
        Step::ZoomAbout { factor, focal } => vp.zoom_about(factor, (focal[0], focal[1])),
        Step::Pan { dx, dy } => vp.pan(dx, dy),
        Step::Nearest { points, at, max_px } => {
            nearest = vp.nearest_on_screen(&points, (at[0], at[1]), max_px)
        }
    }
    Ok(json!({"viewport": vp_json(&vp), "display_zoom": display_zoom(&vp), "nearest": nearest}))
}

fn step_cases() -> Vec<Case> {
    let pts = json!([{"lat": 41.0, "lon": 28.99}, {"lat": 41.02, "lon": 29.03}]);
    vec![
        Case::new(
            json!({"viewport": istanbul(), "step": {"kind": "fit", "points": pts, "width": 390.0, "height": 780.0, "padding": 48.0}}),
        ),
        Case::named(
            "zoom_about",
            json!({"viewport": istanbul(), "step": {"kind": "zoom_about", "factor": 2.0, "focal": [100.0, -60.0]}}),
        ),
        Case::named(
            "zoom_nan",
            json!({"viewport": istanbul(), "step": {"kind": "zoom_about", "factor": -1.0, "focal": [0.0, 0.0]}}),
        ),
        Case::named(
            "pan",
            json!({"viewport": istanbul(), "step": {"kind": "pan", "dx": 80.0, "dy": -40.0}}),
        ),
        Case::named(
            "nearest",
            json!({"viewport": istanbul(), "step": {"kind": "nearest", "points": pts, "at": [-10.0, -10.0], "max_px": 5000.0}}),
        ),
        Case::named(
            "fit_nothing",
            json!({"viewport": istanbul(), "step": {"kind": "fit", "points": [], "width": 390.0, "height": 780.0, "padding": 0.0}}),
        ),
    ]
}

#[derive(Deserialize)]
struct TilesReq {
    viewport: Vp,
    width: f32,
    height: f32,
    max_tiles: usize,
}

/// The tiles to fetch for a widget, nearest the centre first, with the corner of each.
fn tiles(req: TilesReq) -> Result<Value, FfiError> {
    let vp = req.viewport.viewport();
    let cover = tiles_for_view(&vp, (req.width, req.height), req.max_tiles);
    let rows: Vec<Value> = cover
        .tiles
        .iter()
        .map(|t| json!({"z": t.z, "x": t.x, "y": t.y, "nw": t.nw(), "se": t.se()}))
        .collect();
    Ok(json!({
        "zoom": cover.zoom,
        "display_zoom": display_zoom(&vp),
        "count": rows.len(),
        "tiles": rows,
        "centre_tile": tile_containing(vp.center, cover.zoom),
    }))
}

fn tiles_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"viewport": istanbul(), "width": 390.0, "height": 780.0, "max_tiles": 64}),
        ),
        Case::named(
            "over_budget",
            json!({"viewport": istanbul(), "width": 1080.0, "height": 2400.0, "max_tiles": 4}),
        ),
        Case::named(
            "world",
            json!({"viewport": {"center": {"lat": 0.0, "lon": 0.0}, "metres_per_px": 2000.0},
                                    "width": 390.0, "height": 780.0, "max_tiles": 64}),
        ),
    ]
}

#[derive(Deserialize)]
struct FracReq {
    z: u8,
    fx: f64,
    fy: f64,
    /// Coordinates to turn into fractional tile positions at `z` (the inverse).
    #[serde(default)]
    coords: Vec<Coord>,
}

fn frac(req: FracReq) -> Result<Value, FfiError> {
    Ok(json!({
        "coord": coord(frac_to_coord(req.z, req.fx, req.fy)),
        "fracs": req.coords.iter().map(|&c| { let (x, y) = tile_frac(c, req.z); [x, y] }).collect::<Vec<_>>(),
        "max_lat": MAX_MERCATOR_LAT,
    }))
}

fn frac_cases() -> Vec<Case> {
    vec![
        Case::new(
            json!({"z": 14, "fx": 9520.5, "fy": 6130.5, "coords": [{"lat": 41.0082, "lon": 28.9784}]}),
        ),
        Case::named("world", json!({"z": 0, "fx": 0.5, "fy": 0.5})),
        Case::named(
            "polar",
            json!({"z": 3, "fx": 4.0, "fy": 0.0, "coords": [{"lat": 89.0, "lon": 180.0}]}),
        ),
    ]
}

#[derive(Deserialize)]
struct FollowReq {
    /// `off`, `centred` or `following`.
    mode: String,
    /// `me_tap` or `pan`.
    event: String,
    #[serde(default)]
    has_fix: bool,
}

fn follow_name(m: FollowMode) -> &'static str {
    match m {
        FollowMode::Off => "off",
        FollowMode::Centred => "centred",
        FollowMode::Following => "following",
    }
}

fn follow(req: FollowReq) -> Result<Value, FfiError> {
    let mode = match req.mode.as_str() {
        "off" => FollowMode::Off,
        "centred" => FollowMode::Centred,
        "following" => FollowMode::Following,
        other => return Err(FfiError::invalid(format!("unknown follow mode {other:?}"))),
    };
    let next = match req.event.as_str() {
        "me_tap" => mode.on_me_tap(req.has_fix),
        "pan" => mode.on_pan(),
        other => return Err(FfiError::invalid(format!("unknown event {other:?}"))),
    };
    Ok(json!({"mode": follow_name(next), "watches": next.watches()}))
}

fn follow_cases() -> Vec<Case> {
    vec![
        Case::new(json!({"mode": "centred", "event": "me_tap", "has_fix": true})),
        Case::named(
            "following_off",
            json!({"mode": "following", "event": "me_tap", "has_fix": true}),
        ),
        Case::named(
            "no_fix",
            json!({"mode": "following", "event": "me_tap", "has_fix": false}),
        ),
        Case::named("pan", json!({"mode": "following", "event": "pan"})),
        Case::named("bad_mode", json!({"mode": "x", "event": "pan"})),
    ]
}
