//! Per-tile draw geometry: decoded once into tile units, then transformed to
//! the screen per frame. Pure.

use std::collections::BTreeMap;

use super::clip::{Rect, clip_polygon, clip_polyline, simplify};
use super::mvt::{GeomType, MvtError, MvtTile, decode_any, ring_area2};
use super::shortbread::{DrawClass, Shape, StreetKind, classify};
use super::slippy::{TILE_DISPLAY_PX, TileId, display_zoom, frac_to_coord};
use crate::view::Viewport;

const DEFAULT_EXTENT: u32 = 4096;
/// Extra margin around the tile, as a fraction of the extent, so clipped edges
/// (and stroke caps) do not show seams.
const BUFFER_DIV: f32 = 16.0;
/// Margin around the widget rect in `build_layer`, in px.
const CLIP_MARGIN_PX: f32 = 8.0;

type Ring = Vec<(f32, f32)>;

#[derive(Clone, Debug, Default, PartialEq)]
pub struct ClassGeometry {
    /// Each polygon is its exterior ring followed by its holes (open rings).
    pub polygons: Vec<Vec<Ring>>,
    pub lines: Vec<Ring>,
}

impl ClassGeometry {
    pub fn is_empty(&self) -> bool {
        self.polygons.is_empty() && self.lines.is_empty()
    }

    pub fn vertices(&self) -> usize {
        self.polygons.iter().flatten().map(Vec::len).sum::<usize>()
            + self.lines.iter().map(Vec::len).sum::<usize>()
    }
}

/// One tile's drawable features in tile units (`0..extent`).
#[derive(Clone, Debug, PartialEq)]
pub struct TileGeometry {
    pub id: TileId,
    pub extent: u32,
    pub groups: Vec<(DrawClass, ClassGeometry)>,
    pub vertices: usize,
}

fn bbox(ring: &[(f32, f32)]) -> (f32, f32, f32, f32) {
    ring.iter().fold(
        (f32::MAX, f32::MAX, f32::MIN, f32::MIN),
        |(x0, y0, x1, y1), &(x, y)| (x0.min(x), y0.min(y), x1.max(x), y1.max(y)),
    )
}

fn open_ring(raw: &[(i32, i32)], scale: f32) -> Ring {
    let mut r: Ring = raw
        .iter()
        .map(|&(x, y)| (x as f32 * scale, y as f32 * scale))
        .collect();
    if r.len() > 1 && r.first() == r.last() {
        r.pop();
    }
    r
}

impl TileGeometry {
    pub fn empty(id: TileId) -> Self {
        Self {
            id,
            extent: DEFAULT_EXTENT,
            groups: Vec::new(),
            vertices: 0,
        }
    }

    pub fn from_bytes(id: TileId, bytes: &[u8]) -> Result<Self, MvtError> {
        Ok(Self::from_mvt(id, &decode_any(bytes)?))
    }

    /// Classify, decode, clip to the tile plus a 1/16 buffer, simplify with a
    /// tolerance of `extent / 1024`, drop polygons under 1 px at the tile's own
    /// zoom and skip classes whose `min_zoom` is above `id.z + 1`.
    pub fn from_mvt(id: TileId, tile: &MvtTile) -> Self {
        let extent = tile
            .layers
            .first()
            .map_or(DEFAULT_EXTENT, |l| l.extent.max(1));
        let ext = extent as f32;
        let buf = ext / BUFFER_DIV;
        let clip = Rect::new((-buf, -buf), (ext + buf, ext + buf));
        let tol = ext / 1024.0;
        let min_units = ext / TILE_DISPLAY_PX as f32; // 1 px
        let mut map: BTreeMap<DrawClass, ClassGeometry> = BTreeMap::new();

        for layer in &tile.layers {
            let scale = ext / layer.extent.max(1) as f32;
            for f in &layer.features {
                let Some(class) = classify(layer, f) else {
                    continue;
                };
                if u32::from(class.min_zoom()) > u32::from(id.z) + 1 {
                    continue;
                }
                let want = match class.shape() {
                    Shape::Fill => GeomType::Polygon,
                    Shape::Line => GeomType::LineString,
                };
                if f.geom_type != want {
                    continue;
                }
                let Ok(parts) = f.decode_geometry() else {
                    continue;
                };
                let entry = map.entry(class).or_default();
                match want {
                    GeomType::LineString => {
                        for p in &parts {
                            let pts: Ring = p
                                .iter()
                                .map(|&(x, y)| (x as f32 * scale, y as f32 * scale))
                                .collect();
                            for part in clip_polyline(&pts, &clip) {
                                let s = simplify(&part, tol, false);
                                if s.len() >= 2 {
                                    entry.lines.push(s);
                                }
                            }
                        }
                    }
                    _ => {
                        let mut poly: Vec<Ring> = Vec::new();
                        let flush = |poly: &mut Vec<Ring>, entry: &mut ClassGeometry| {
                            if !poly.is_empty() {
                                entry.polygons.push(std::mem::take(poly));
                            }
                        };
                        for raw in &parts {
                            let exterior = ring_area2(raw) > 0;
                            let ring = open_ring(raw, scale);
                            if ring.len() < 3 {
                                continue;
                            }
                            if exterior {
                                flush(&mut poly, entry);
                                let (x0, y0, x1, y1) = bbox(&ring);
                                if (x1 - x0).max(y1 - y0) < min_units {
                                    continue;
                                }
                            } else if poly.is_empty() {
                                continue; // hole of a dropped polygon
                            }
                            let clipped = clip_polygon(&ring, &clip);
                            let s = simplify(&clipped, tol, true);
                            if s.len() >= 3 {
                                poly.push(s);
                            } else if exterior {
                                // Exterior vanished: keep `poly` empty so holes are skipped.
                            }
                        }
                        flush(&mut poly, entry);
                    }
                }
            }
        }

        let groups: Vec<(DrawClass, ClassGeometry)> =
            map.into_iter().filter(|(_, g)| !g.is_empty()).collect();
        let vertices = groups.iter().map(|(_, g)| g.vertices()).sum();
        Self {
            id,
            extent,
            groups,
            vertices,
        }
    }
}

/// Tile units -> pixels from the view centre (the map's Scene space).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct TileTransform {
    pub origin: (f32, f32),
    pub scale: (f32, f32),
}

impl TileTransform {
    /// Built from projecting the tile's NW and SE corners through the viewport.
    pub fn new(id: TileId, extent: u32, vp: &Viewport) -> Self {
        let nw = vp.project(id.nw());
        let se = vp.project(id.se());
        let e = extent.max(1) as f32;
        // Web Mercator y is not linear in latitude, so the corner-to-corner
        // line is off by `d` at the tile's middle. Shifting it by `d / 2`
        // halves the worst error (minimax fit of a parabola by a line).
        let mid = vp.project(frac_to_coord(
            id.z,
            f64::from(id.x) + 0.5,
            f64::from(id.y) + 0.5,
        ));
        let d = mid.1 - (nw.1 + se.1) / 2.0;
        Self {
            origin: (nw.0, nw.1 + d / 2.0),
            scale: ((se.0 - nw.0) / e, (se.1 - nw.1) / e),
        }
    }

    pub fn apply(&self, p: (f32, f32)) -> (f32, f32) {
        (
            self.origin.0 + p.0 * self.scale.0,
            self.origin.1 + p.1 * self.scale.1,
        )
    }

    pub fn screen_rect(&self, extent: u32) -> Rect {
        let a = self.apply((0.0, 0.0));
        let b = self.apply((extent as f32, extent as f32));
        Rect::new((a.0.min(b.0), a.1.min(b.1)), (a.0.max(b.0), a.1.max(b.1)))
    }
}

/// What to draw this frame, in Scene space (px from the view centre).
#[derive(Clone, Debug, PartialEq)]
pub struct TileLayer {
    /// Each tile's rect clipped to the widget, as a quad.
    pub backgrounds: Vec<[(f32, f32); 4]>,
    /// Merged across tiles, in paint order.
    pub groups: Vec<(DrawClass, ClassGeometry)>,
    pub display_zoom: f64,
}

impl TileLayer {
    pub fn vertices(&self) -> usize {
        self.groups.iter().map(|(_, g)| g.vertices()).sum()
    }
}

fn low_priority_stage(class: DrawClass) -> Option<u8> {
    match class {
        DrawClass::Building => Some(0),
        DrawClass::Street {
            kind: StreetKind::Path | StreetKind::Cycleway | StreetKind::Track | StreetKind::Service,
            ..
        } => Some(1),
        DrawClass::Land(_) => Some(2),
        _ => None,
    }
}

/// Transform the tiles to Scene space, clip to the widget rect (+8 px), filter
/// by `min_zoom <= display_zoom + 0.5`, merge by class in paint order and stay
/// within `max_vertices` (dropping Building, then Path/Cycleway/Track/Service,
/// then Land, then trimming from the top of the paint order).
pub fn build_layer(
    tiles: &[&TileGeometry],
    vp: &Viewport,
    size: (f32, f32),
    max_vertices: usize,
) -> TileLayer {
    let dz = display_zoom(vp);
    let (hw, hh) = (size.0 / 2.0 + CLIP_MARGIN_PX, size.1 / 2.0 + CLIP_MARGIN_PX);
    let view = Rect::new((-hw, -hh), (hw, hh));
    let mut backgrounds = Vec::new();
    let mut merged: BTreeMap<DrawClass, ClassGeometry> = BTreeMap::new();

    for t in tiles {
        let tf = TileTransform::new(t.id, t.extent, vp);
        let r = tf.screen_rect(t.extent);
        if !r.intersects(&view) {
            continue;
        }
        let (x0, y0) = (r.min.0.max(view.min.0), r.min.1.max(view.min.1));
        let (x1, y1) = (r.max.0.min(view.max.0), r.max.1.min(view.max.1));
        backgrounds.push([(x0, y0), (x1, y0), (x1, y1), (x0, y1)]);
        for (class, g) in &t.groups {
            if f64::from(class.min_zoom()) > dz + 0.5 {
                continue;
            }
            let out = merged.entry(*class).or_default();
            for poly in &g.polygons {
                let mut rings = Vec::new();
                for (i, ring) in poly.iter().enumerate() {
                    let scr: Ring = ring.iter().map(|&p| tf.apply(p)).collect();
                    let c = clip_polygon(&scr, &view);
                    if !c.is_empty() {
                        rings.push(c);
                    } else if i == 0 {
                        break;
                    }
                }
                if !rings.is_empty() {
                    out.polygons.push(rings);
                }
            }
            for line in &g.lines {
                let scr: Ring = line.iter().map(|&p| tf.apply(p)).collect();
                out.lines.extend(clip_polyline(&scr, &view));
            }
        }
    }

    let mut groups: Vec<(DrawClass, ClassGeometry)> =
        merged.into_iter().filter(|(_, g)| !g.is_empty()).collect();
    let mut total: usize = groups.iter().map(|(_, g)| g.vertices()).sum();
    for stage in 0..3u8 {
        if total <= max_vertices {
            break;
        }
        groups.retain(|(c, g)| {
            let drop = low_priority_stage(*c) == Some(stage);
            if drop {
                total -= g.vertices();
            }
            !drop
        });
    }
    // Last resort: trim whole features from the top of the paint order.
    while total > max_vertices {
        let Some((_, g)) = groups.last_mut() else {
            break;
        };
        let removed = if let Some(l) = g.lines.pop() {
            l.len()
        } else if let Some(p) = g.polygons.pop() {
            p.iter().map(Vec::len).sum()
        } else {
            0
        };
        total -= removed;
        if g.is_empty() {
            groups.pop();
        }
    }
    TileLayer {
        backgrounds,
        groups,
        display_zoom: dz,
    }
}

/// Nearest loaded stand-in for a wanted tile: itself, else its ancestors up to
/// 4 levels up.
pub fn stand_in(want: TileId, have: impl Fn(TileId) -> bool) -> Option<TileId> {
    (0..=4u8).find_map(|d| {
        let id = want.ancestor(want.z.checked_sub(d)?)?;
        have(id).then_some(id)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::coord::Coord;

    const FIXTURE: &[u8] = include_bytes!("testdata/osm-14-9520-6130.mvt");

    fn fixture_id() -> TileId {
        TileId::new(14, 9520, 6130).expect("id")
    }

    fn fixture() -> TileGeometry {
        TileGeometry::from_bytes(fixture_id(), FIXTURE).expect("fixture")
    }

    fn centre_vp(id: TileId, mpp: f64) -> Viewport {
        let c = frac_to_coord(id.z, f64::from(id.x) + 0.5, f64::from(id.y) + 0.5);
        Viewport::new(c, mpp)
    }

    fn max_err(id: TileId, vp: &Viewport) -> f32 {
        let tf = TileTransform::new(id, 4096, vp);
        let mut worst = 0.0f32;
        for i in 0..3 {
            for j in 0..3 {
                let (fx, fy) = (i as f64 / 2.0, j as f64 / 2.0);
                let exact = vp.project(frac_to_coord(
                    id.z,
                    f64::from(id.x) + fx,
                    f64::from(id.y) + fy,
                ));
                let got = tf.apply((fx as f32 * 4096.0, fy as f32 * 4096.0));
                worst = worst
                    .max((exact.0 - got.0).abs())
                    .max((exact.1 - got.1).abs());
            }
        }
        worst
    }

    #[test]
    fn transform_matches_exact_projection() {
        let id = TileId::new(14, 9510, 6140).expect("id");
        let vp = Viewport::new(Coord::new(41.0082, 28.9784), 1.0);
        let e = max_err(id, &vp);
        assert!(e < 0.5, "z14 error {e}");
        let id10 = TileId::new(10, 595, 383).expect("id");
        let vp10 = Viewport::new(Coord::new(41.0082, 28.9784), 16.0);
        let e = max_err(id10, &vp10);
        assert!(e < 0.5, "z10 error {e}");
    }

    #[test]
    fn fixture_groups() {
        let g = fixture();
        let has = |f: fn(&DrawClass) -> bool| g.groups.iter().any(|(c, _)| f(c));
        assert!(has(|c| *c == DrawClass::Building));
        assert!(has(|c| matches!(c, DrawClass::Street { .. })));
        assert!(has(|c| matches!(c, DrawClass::Waterway { .. })));
        assert!(g.groups.windows(2).all(|w| w[0].0 < w[1].0));
        let sum: usize = g.groups.iter().map(|(_, c)| c.vertices()).sum();
        assert_eq!(g.vertices, sum);
        assert!(g.vertices > 0);
        let lim = g.extent as f32 * (1.0 + 1.0 / BUFFER_DIV) + 0.01;
        let lo = -(g.extent as f32) / BUFFER_DIV - 0.01;
        let ok = |p: &(f32, f32)| p.0 >= lo && p.0 <= lim && p.1 >= lo && p.1 <= lim;
        for (_, c) in &g.groups {
            assert!(c.polygons.iter().flatten().flatten().all(ok));
            assert!(c.lines.iter().flatten().all(ok));
            assert!(c.polygons.iter().all(|p| p[0].len() >= 3));
        }
    }

    #[test]
    fn empty_and_garbage() {
        let e = TileGeometry::empty(fixture_id());
        assert!(e.groups.is_empty() && e.vertices == 0);
        assert!(TileGeometry::from_bytes(fixture_id(), &[0xff, 0xff, 0xff]).is_err());
    }

    #[test]
    fn low_zoom_skips_detail() {
        let tile = decode_any(FIXTURE).expect("decode");
        // Pretend the tile is z5: buildings (min zoom 14) must vanish.
        let g = TileGeometry::from_mvt(TileId::new(5, 0, 0).expect("id"), &tile);
        assert!(!g.groups.iter().any(|(c, _)| *c == DrawClass::Building));
    }

    const SIZE: (f32, f32) = (370.0, 340.0);

    /// A viewport centred on the first building, so buildings are in view.
    fn building_vp(g: &TileGeometry, mpp: f64) -> Viewport {
        let (_, b) = g
            .groups
            .iter()
            .find(|(c, _)| *c == DrawClass::Building)
            .expect("fixture has buildings");
        let p = b.polygons[0][0][0];
        let e = g.extent as f64;
        Viewport::new(
            frac_to_coord(
                g.id.z,
                f64::from(g.id.x) + f64::from(p.0) / e,
                f64::from(g.id.y) + f64::from(p.1) / e,
            ),
            mpp,
        )
    }

    fn all_points(l: &TileLayer) -> Vec<(f32, f32)> {
        let mut v = Vec::new();
        for (_, g) in &l.groups {
            v.extend(g.polygons.iter().flatten().flatten().copied());
            v.extend(g.lines.iter().flatten().copied());
        }
        v
    }

    #[test]
    fn layer_clips_and_orders() {
        let g = fixture();
        let vp = building_vp(&g, 2.0);
        let l = build_layer(&[&g], &vp, SIZE, usize::MAX);
        assert_eq!(l.backgrounds.len(), 1);
        assert!(!l.groups.is_empty());
        let (hw, hh) = (SIZE.0 / 2.0 + 8.0 + 0.01, SIZE.1 / 2.0 + 8.0 + 0.01);
        for p in all_points(&l) {
            assert!(p.0.abs() <= hw && p.1.abs() <= hh, "{p:?}");
        }
        for q in l.backgrounds[0] {
            assert!(q.0.abs() <= hw && q.1.abs() <= hh);
        }
        assert!(l.groups.windows(2).all(|w| w[0].0 < w[1].0));
    }

    #[test]
    fn layer_honours_budget() {
        let g = fixture();
        let vp = building_vp(&g, 2.0);
        let full = build_layer(&[&g], &vp, SIZE, usize::MAX);
        let total = full.vertices();
        assert!(full.groups.iter().any(|(c, _)| *c == DrawClass::Building));
        let b = build_layer(&[&g], &vp, SIZE, total - 1);
        assert!(b.vertices() < total);
        assert!(!b.groups.iter().any(|(c, _)| *c == DrawClass::Building));
        let tiny = build_layer(&[&g], &vp, SIZE, 10);
        assert!(tiny.vertices() <= 10);
        assert!(build_layer(&[&g], &vp, SIZE, 0).groups.is_empty());
    }

    #[test]
    fn far_view_is_empty() {
        let g = fixture();
        let vp = Viewport::new(Coord::new(0.0, 0.0), 2.0);
        let l = build_layer(&[&g], &vp, SIZE, usize::MAX);
        assert!(l.backgrounds.is_empty() && l.groups.is_empty());
    }

    #[test]
    fn zoomed_out_hides_detail() {
        let g = fixture();
        let vp = centre_vp(g.id, 64.0);
        let l = build_layer(&[&g], &vp, SIZE, usize::MAX);
        assert!(!l.groups.iter().any(|(c, _)| *c == DrawClass::Building));
    }

    #[test]
    fn stand_in_nearest_ancestor() {
        let want = fixture_id();
        let p2 = want.ancestor(12).expect("anc");
        let p4 = want.ancestor(10).expect("anc");
        let p5 = want.ancestor(9).expect("anc");
        assert_eq!(stand_in(want, |t| t == want || t == p2), Some(want));
        assert_eq!(stand_in(want, |t| t == p2 || t == p4), Some(p2));
        assert_eq!(stand_in(want, |t| t == p4), Some(p4));
        assert_eq!(stand_in(want, |t| t == p5), None);
        assert_eq!(stand_in(want, |_| false), None);
        let root = TileId::new(0, 0, 0).expect("id");
        assert_eq!(stand_in(root, |t| t == root), Some(root));
    }
}
