//! Web-Mercator slippy-tile maths.

use serde::{Deserialize, Serialize};

use crate::coord::{Coord, EARTH_RADIUS_M};
use crate::view::Viewport;

/// Highest zoom the server has; beyond it the app overzooms.
pub const MAX_TILE_ZOOM: u8 = 14;
/// Vector tiles are designed for 512 px.
pub const TILE_DISPLAY_PX: f64 = 512.0;
pub const MAX_MERCATOR_LAT: f64 = 85.051_128_78;
const EQUATOR_M: f64 = 40_075_016.686;
const MAX_ID_ZOOM: u8 = 24;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
pub struct TileId {
    pub z: u8,
    pub x: u32,
    pub y: u32,
}

impl TileId {
    /// `None` when `z > 24` or `x`/`y` fall outside `0..2^z`.
    pub fn new(z: u8, x: u32, y: u32) -> Option<Self> {
        if z > MAX_ID_ZOOM {
            return None;
        }
        let n = 1u32 << z;
        (x < n && y < n).then_some(Self { z, x, y })
    }

    pub fn parent(self) -> Option<Self> {
        self.ancestor(self.z.checked_sub(1)?)
    }

    /// The tile at zoom `z` containing this one; `None` if `z` is finer.
    pub fn ancestor(self, z: u8) -> Option<Self> {
        let d = self.z.checked_sub(z)?;
        Some(Self {
            z,
            x: self.x >> d,
            y: self.y >> d,
        })
    }

    /// North-west corner.
    pub fn nw(self) -> Coord {
        frac_to_coord(self.z, f64::from(self.x), f64::from(self.y))
    }

    /// South-east corner.
    pub fn se(self) -> Coord {
        frac_to_coord(self.z, f64::from(self.x) + 1.0, f64::from(self.y) + 1.0)
    }
}

fn n_tiles(z: u8) -> f64 {
    f64::from(1u32 << z.min(MAX_ID_ZOOM))
}

/// Fractional tile position without wrapping longitude (continuous across
/// the antimeridian).
fn raw_frac(c: Coord, z: u8) -> (f64, f64) {
    let n = n_tiles(z);
    let lat = c
        .lat
        .clamp(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        .to_radians();
    let x = (c.lon + 180.0) / 360.0 * n;
    let y = (1.0 - lat.tan().asinh() / std::f64::consts::PI) / 2.0 * n;
    (x, y)
}

/// Fractional tile position at zoom `z`; latitude is clamped, longitude wraps.
pub fn tile_frac(c: Coord, z: u8) -> (f64, f64) {
    let n = n_tiles(z);
    let (x, y) = raw_frac(c, z);
    (x.rem_euclid(n), y)
}

/// Inverse of [`tile_frac`].
pub fn frac_to_coord(z: u8, fx: f64, fy: f64) -> Coord {
    let n = n_tiles(z);
    let lon = fx / n * 360.0 - 180.0;
    let lat = (std::f64::consts::PI * (1.0 - 2.0 * fy / n)).sinh().atan();
    Coord::new(lat.to_degrees(), lon)
}

pub fn tile_containing(c: Coord, z: u8) -> TileId {
    let z = z.min(MAX_ID_ZOOM);
    let max = (1u32 << z) - 1;
    let (fx, fy) = tile_frac(c, z);
    TileId {
        z,
        x: (fx.floor() as u32).min(max),
        y: (fy.floor().max(0.0) as u32).min(max),
    }
}

/// Fractional zoom at which one 512 px tile spans the viewport's scale.
pub fn display_zoom(vp: &Viewport) -> f64 {
    let cos = vp
        .center
        .lat
        .clamp(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        .to_radians()
        .cos()
        .max(1e-6);
    (EQUATOR_M * cos / (TILE_DISPLAY_PX * vp.metres_per_px)).log2()
}

/// The server zoom to fetch for a display zoom.
pub fn fetch_zoom(display_zoom: f64) -> u8 {
    if display_zoom.is_nan() {
        return 0;
    }
    display_zoom.round().clamp(0.0, f64::from(MAX_TILE_ZOOM)) as u8
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TileCover {
    pub zoom: u8,
    pub tiles: Vec<TileId>,
}

/// Inclusive tile range at one zoom; `x` is unwrapped (may leave `0..n`).
struct Span {
    x0: i64,
    x1: i64,
    y0: u32,
    y1: u32,
}

impl Span {
    fn around(points: &[Coord], z: u8, margin: i64) -> Self {
        let n = 1i64 << z;
        let max_y = n - 1;
        let (mut x0, mut x1, mut y0, mut y1) = (f64::MAX, f64::MIN, f64::MAX, f64::MIN);
        for p in points {
            let (x, y) = raw_frac(*p, z);
            x0 = x0.min(x);
            x1 = x1.max(x);
            y0 = y0.min(y);
            y1 = y1.max(y);
        }
        let x0 = x0.floor() as i64 - margin;
        let x1 = (x1.floor() as i64 + margin).min(x0 + n - 1);
        Self {
            x0,
            x1,
            y0: (y0.floor() as i64 - margin).clamp(0, max_y) as u32,
            y1: (y1.floor() as i64 + margin).clamp(0, max_y) as u32,
        }
    }

    fn count(&self) -> usize {
        (self.x1 - self.x0 + 1) as usize * (self.y1 - self.y0 + 1) as usize
    }

    /// Tiles nearest `centre` (fractional, unwrapped x) first.
    fn tiles(&self, z: u8, centre: (f64, f64)) -> Vec<TileId> {
        let n = 1i64 << z;
        let mut v: Vec<(f64, i64, u32)> = Vec::with_capacity(self.count());
        for ux in self.x0..=self.x1 {
            for y in self.y0..=self.y1 {
                let dx = ux as f64 + 0.5 - centre.0;
                let dy = f64::from(y) + 0.5 - centre.1;
                v.push((dx * dx + dy * dy, ux, y));
            }
        }
        v.sort_by(|a, b| a.0.total_cmp(&b.0).then(a.2.cmp(&b.2)).then(a.1.cmp(&b.1)));
        v.into_iter()
            .map(|(_, ux, y)| TileId {
                z,
                x: ux.rem_euclid(n) as u32,
                y,
            })
            .collect()
    }
}

/// Tiles at the fetch zoom covering a widget of `size` pixels (unprojected
/// corners plus a one-tile margin), nearest the centre first. Drops one zoom
/// level at a time while there are more than `max_tiles`.
pub fn tiles_for_view(vp: &Viewport, size: (f32, f32), max_tiles: usize) -> TileCover {
    let hw = if size.0.is_finite() {
        size.0.max(0.0) / 2.0
    } else {
        0.0
    };
    let hh = if size.1.is_finite() {
        size.1.max(0.0) / 2.0
    } else {
        0.0
    };
    let corners = [(-hw, -hh), (hw, -hh), (-hw, hh), (hw, hh)].map(|p| vp.unproject(p));
    let mut zoom = fetch_zoom(display_zoom(vp));
    loop {
        let span = Span::around(&corners, zoom, 1);
        if span.count() <= max_tiles || zoom == 0 {
            let centre = raw_frac(vp.center, zoom);
            let tiles = if span.count() <= max_tiles {
                span.tiles(zoom, centre)
            } else {
                Vec::new()
            };
            return TileCover { zoom, tiles };
        }
        zoom -= 1;
    }
}

/// Prefetch set: tiles covering the circle's box plus `margin_m` at every
/// zoom from `min_zoom` up to the highest zoom (at most 14) whose cumulative
/// count stays within `budget`; coarse first, nearest the centre first within
/// a zoom. If even `min_zoom` alone exceeds the budget, only its nearest
/// `budget` tiles are returned.
pub fn tiles_for_circle(
    center: Coord,
    radius_m: f64,
    margin_m: f64,
    min_zoom: u8,
    budget: usize,
) -> Vec<TileId> {
    let r = (radius_m.max(0.0) + margin_m.max(0.0)).min(EARTH_RADIUS_M);
    let dlat = (r / EARTH_RADIUS_M).to_degrees();
    let dlon = dlat / center.lat.to_radians().cos().max(1e-6);
    let corners = [
        Coord::new(center.lat - dlat, center.lon - dlon),
        Coord::new(center.lat + dlat, center.lon + dlon),
    ];
    let mut out = Vec::new();
    for z in min_zoom.min(MAX_TILE_ZOOM)..=MAX_TILE_ZOOM {
        let span = Span::around(&corners, z, 0);
        let mut tiles = span.tiles_if(z, raw_frac(center, z), budget - out.len().min(budget));
        match tiles.take() {
            Some(t) => out.extend(t),
            None => {
                if out.is_empty() {
                    let mut t = span.tiles(z, raw_frac(center, z));
                    t.truncate(budget);
                    return t;
                }
                break;
            }
        }
    }
    out
}

/// Lowest zoom of an offline map area download.
pub const AREA_MIN_ZOOM: u8 = 8;
/// Most tiles one offline area download may request.
pub const AREA_TILE_BUDGET: usize = 2500;
/// Rough average size of one cached vector tile, for estimates.
pub const AVG_TILE_BYTES: u64 = 30_000;

/// Estimated download size of `tiles` tiles in bytes.
pub fn estimate_bytes(tiles: usize) -> u64 {
    (tiles as u64).saturating_mul(AVG_TILE_BYTES)
}

/// Tiles of an offline map area, see [`tiles_for_area`].
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AreaPlan {
    /// Coarse zooms first, nearest the centre first within a zoom.
    pub tiles: Vec<TileId>,
    /// Highest zoom included (the lowest zoom when nothing fits).
    pub top_zoom: u8,
    /// Stopped below `min(max_zoom, MAX_TILE_ZOOM)` because of the budget.
    pub capped: bool,
}

/// Tiles covering the box around `corners` (longitude may be unwrapped across
/// the antimeridian) for every zoom from `min_zoom` up to the highest zoom
/// (at most 14, at most `max_zoom`) whose cumulative count fits `budget`. If
/// even the lowest zoom exceeds the budget, only its nearest `budget` tiles
/// are returned.
pub fn tiles_for_area(corners: &[Coord], min_zoom: u8, max_zoom: u8, budget: usize) -> AreaPlan {
    let hi = max_zoom.min(MAX_TILE_ZOOM);
    let lo = min_zoom.min(hi);
    let mut plan = AreaPlan {
        tiles: Vec::new(),
        top_zoom: lo,
        capped: false,
    };
    if corners.is_empty() {
        return plan;
    }
    let n = corners.len() as f64;
    let centre = Coord::new(
        corners.iter().map(|c| c.lat).sum::<f64>() / n,
        corners.iter().map(|c| c.lon).sum::<f64>() / n,
    );
    for z in lo..=hi {
        let span = Span::around(corners, z, 0);
        let c = raw_frac(centre, z);
        match span.tiles_if(z, c, budget - plan.tiles.len().min(budget)) {
            Some(t) => {
                plan.tiles.extend(t);
                plan.top_zoom = z;
            }
            None => {
                if plan.tiles.is_empty() {
                    let mut t = span.tiles(z, c);
                    t.truncate(budget);
                    plan.tiles = t;
                }
                plan.capped = true;
                break;
            }
        }
    }
    plan
}

impl Span {
    /// Like [`Self::tiles`] but `None` when more than `room` tiles.
    fn tiles_if(&self, z: u8, centre: (f64, f64), room: usize) -> Option<Vec<TileId>> {
        (self.count() <= room).then(|| self.tiles(z, centre))
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    const IST: Coord = Coord::new(41.0082, 28.9784);

    fn contains(c: Coord, t: TileId) -> bool {
        let (nw, se) = (t.nw(), t.se());
        c.lat <= nw.lat && c.lat >= se.lat && c.lon >= nw.lon && c.lon <= se.lon
    }

    #[test]
    fn known_tiles() {
        assert_eq!(
            tile_containing(IST, 14),
            TileId {
                z: 14,
                x: 9510,
                y: 6142
            }
        );
        assert_eq!(tile_containing(IST, 0), TileId { z: 0, x: 0, y: 0 });
        assert_eq!(
            tile_containing(Coord::new(0.0001, 0.0001), 1),
            TileId { z: 1, x: 1, y: 0 }
        );
    }

    #[test]
    fn frac_round_trip() {
        for z in [0u8, 5, 14, 20] {
            let (fx, fy) = tile_frac(IST, z);
            let c = frac_to_coord(z, fx, fy);
            assert!((c.lat - IST.lat).abs() < 1e-9 && (c.lon - IST.lon).abs() < 1e-9);
        }
    }

    #[test]
    fn clamps_latitude_and_wraps_longitude() {
        let t = tile_containing(Coord::new(89.0, 10.0), 5);
        assert_eq!(t.y, 0);
        let t = tile_containing(Coord::new(-89.0, 10.0), 5);
        assert_eq!(t.y, 31);
        assert_eq!(tile_containing(Coord::new(0.0, 180.0), 4).x, 0);
        assert_eq!(tile_containing(Coord::new(0.0, -180.0), 4).x, 0);
        assert_eq!(tile_containing(Coord::new(0.0, 190.0), 4).x, 0);
    }

    #[test]
    fn id_validation_and_ancestors() {
        assert!(TileId::new(25, 0, 0).is_none());
        assert!(TileId::new(2, 4, 0).is_none());
        assert!(TileId::new(2, 3, 3).is_some());
        let t = TileId::new(14, 9510, 6142).unwrap();
        assert_eq!(t.parent(), TileId::new(13, 4755, 3071));
        assert_eq!(t.ancestor(0), TileId::new(0, 0, 0));
        assert_eq!(t.ancestor(14), Some(t));
        assert_eq!(t.ancestor(15), None);
        assert_eq!(TileId::new(0, 0, 0).unwrap().parent(), None);
        assert!(contains(IST, t));
    }

    #[test]
    fn display_zoom_matches_scale() {
        let mpp = EQUATOR_M / (TILE_DISPLAY_PX * 16384.0);
        assert!((mpp - 4.777).abs() < 0.001, "{mpp}");
        let vp = Viewport {
            center: Coord::new(0.0, 0.0),
            metres_per_px: mpp,
        };
        assert!((display_zoom(&vp) - 14.0).abs() < 1e-9);
        assert_eq!(fetch_zoom(14.4), 14);
        assert_eq!(fetch_zoom(17.0), 14);
        assert_eq!(fetch_zoom(-3.0), 0);
        assert_eq!(fetch_zoom(f64::NAN), 0);
        assert_eq!(fetch_zoom(9.6), 10);
    }

    #[test]
    fn view_cover_is_nearby_and_sorted() {
        let vp = Viewport::new(IST, 2.0);
        let size = (370.0, 340.0);
        let cover = tiles_for_view(&vp, size, 64);
        assert_eq!(cover.zoom, 14);
        assert!(
            (4..=20).contains(&cover.tiles.len()),
            "{}",
            cover.tiles.len()
        );
        assert_eq!(cover.tiles[0], tile_containing(IST, 14));
        let (cx, cy) = tile_frac(IST, 14);
        let d = |t: &TileId| (f64::from(t.x) + 0.5 - cx).hypot(f64::from(t.y) + 0.5 - cy);
        assert!(cover.tiles.windows(2).all(|w| d(&w[0]) <= d(&w[1]) + 1e-12));
        // Every corner of the widget lies in a covered tile.
        for p in [
            (-185.0, -170.0),
            (185.0, 170.0),
            (185.0, -170.0),
            (-185.0, 170.0),
        ] {
            let t = tile_containing(vp.unproject(p), 14);
            assert!(cover.tiles.contains(&t));
        }
    }

    #[test]
    fn view_cover_respects_max_and_overzooms() {
        let vp = Viewport::new(IST, 2.0);
        let small = tiles_for_view(&vp, (370.0, 340.0), 4);
        assert!(small.zoom < 14 && small.tiles.len() <= 4);
        let over = tiles_for_view(&Viewport::new(IST, 0.5), (370.0, 340.0), 64);
        assert_eq!(over.zoom, 14);
        assert!(!over.tiles.is_empty());
        let none = tiles_for_view(&vp, (370.0, 340.0), 0);
        assert!(none.tiles.is_empty());
    }

    #[test]
    fn view_cover_wraps_antimeridian() {
        let vp = Viewport::new(Coord::new(0.0, 179.99), 600.0);
        let cover = tiles_for_view(&vp, (370.0, 340.0), 64);
        let n = 1u32 << cover.zoom;
        assert!(cover.tiles.iter().all(|t| t.x < n));
        assert!(cover.tiles.iter().any(|t| t.x == 0));
        assert!(cover.tiles.iter().any(|t| t.x == n - 1));
    }

    #[test]
    fn circle_cover_budget_and_order() {
        for (r, budget) in [(500.0, 300), (25_000.0, 300), (25_000.0, 2000)] {
            let tiles = tiles_for_circle(IST, r, 500.0, 8, budget);
            assert!(tiles.len() <= budget, "{r}: {}", tiles.len());
            assert!(tiles.windows(2).all(|w| w[0].z <= w[1].z));
            let top = tiles.last().unwrap().z;
            assert!(top >= 8);
            assert!(tiles.contains(&tile_containing(IST, top)));
            assert!(tiles.contains(&tile_containing(IST, 8)));
        }
        let small = tiles_for_circle(IST, 500.0, 500.0, 8, 300);
        assert_eq!(small.last().unwrap().z, 14);
        let big_few = tiles_for_circle(IST, 25_000.0, 500.0, 8, 300);
        let big_many = tiles_for_circle(IST, 25_000.0, 500.0, 8, 2000);
        assert!(big_few.last().unwrap().z < big_many.last().unwrap().z);
    }

    #[test]
    fn circle_cover_tiny_budget() {
        assert!(tiles_for_circle(IST, 500.0, 0.0, 8, 0).is_empty());
        let one = tiles_for_circle(IST, 50_000.0, 0.0, 10, 3);
        assert_eq!(one.len(), 3);
        assert_eq!(one[0], tile_containing(IST, 10));
    }

    fn bbox(lat0: f64, lon0: f64, lat1: f64, lon1: f64) -> [Coord; 4] {
        [
            Coord::new(lat0, lon0),
            Coord::new(lat0, lon1),
            Coord::new(lat1, lon0),
            Coord::new(lat1, lon1),
        ]
    }

    fn no_dupes(tiles: &[TileId]) -> bool {
        let set: std::collections::BTreeSet<_> = tiles.iter().collect();
        set.len() == tiles.len()
    }

    #[test]
    fn area_city_reaches_max_zoom() {
        let plan = tiles_for_area(
            &bbox(40.99, 28.95, 41.03, 29.01),
            AREA_MIN_ZOOM,
            MAX_TILE_ZOOM,
            AREA_TILE_BUDGET,
        );
        assert_eq!(plan.top_zoom, 14);
        assert!(!plan.capped);
        assert!(plan.tiles.len() <= AREA_TILE_BUDGET);
        assert!(no_dupes(&plan.tiles));
        assert!(plan.tiles.windows(2).all(|w| w[0].z <= w[1].z));
        assert_eq!(plan.tiles[0].z, AREA_MIN_ZOOM);
    }

    #[test]
    fn area_country_is_capped_within_budget() {
        let plan = tiles_for_area(
            &bbox(36.0, 26.0, 42.0, 45.0),
            AREA_MIN_ZOOM,
            MAX_TILE_ZOOM,
            AREA_TILE_BUDGET,
        );
        assert!(plan.capped);
        assert!(plan.top_zoom < 14);
        assert!(plan.tiles.len() <= AREA_TILE_BUDGET);
        assert!(no_dupes(&plan.tiles));
        let tiny = tiles_for_area(&bbox(0.0, 0.0, 40.0, 60.0), 8, 14, 50);
        assert!(tiny.capped && tiny.tiles.len() == 50);
        assert_eq!(tiny.top_zoom, 8);
        let none = tiles_for_area(&bbox(0.0, 0.0, 1.0, 1.0), 8, 14, 0);
        assert!(none.tiles.is_empty() && none.capped);
    }

    #[test]
    fn area_across_antimeridian_and_single_point() {
        let plan = tiles_for_area(&bbox(-0.05, 179.95, 0.05, 180.05), 8, 12, 2500);
        assert_eq!(plan.top_zoom, 12);
        assert!(no_dupes(&plan.tiles));
        let n = 1u32 << 12;
        let z12: Vec<_> = plan.tiles.iter().filter(|t| t.z == 12).collect();
        assert!(z12.iter().any(|t| t.x == 0) && z12.iter().any(|t| t.x == n - 1));
        assert!(plan.tiles.iter().all(|t| t.x < 1 << t.z));

        let one = tiles_for_area(&[IST], 8, 14, 2500);
        assert_eq!(one.tiles.len(), 7);
        assert_eq!(one.top_zoom, 14);
        assert!(!one.capped && no_dupes(&one.tiles));
        assert!(one.tiles.contains(&tile_containing(IST, 14)));
    }

    #[test]
    fn area_edge_inputs() {
        assert!(tiles_for_area(&[], 8, 14, 100).tiles.is_empty());
        let p = tiles_for_area(&[IST], 12, 9, 100);
        assert_eq!(p.top_zoom, 9);
        assert_eq!(p.tiles.len(), 1);
        assert_eq!(estimate_bytes(10), 300_000);
    }
}
