//! The binary tile buffer `RVT1` that the `tile` export answers (format: `docs/kotlin-ui/tile-buffer.md`).
//!
//! The encoder takes the clipped, simplified [`TileGeometry`] of `rovsti_geo` and the colours of its
//! [`MapStyle`], and writes one flat little-endian buffer the Kotlin map view draws without any parsing
//! beyond [`TileBuffer`] reads. [`decode`] is the reference reader the tests (and the format document) hold
//! the encoder to; Kotlin has its own `TileBuffer.decode` over the same bytes.

use rovsti_geo::tiles::{DrawClass, MapStyle, Shape, TileGeometry};

pub const MAGIC: [u8; 4] = *b"RVT1";
pub const VERSION: u16 = 1;
/// Flag bit 0: the dark (eclipse) style was used.
pub const FLAG_DARK: u16 = 1;
/// Flag bit 1: the tile came from the disk cache only (the caller asked for no network).
pub const FLAG_CACHE_ONLY: u16 = 2;
/// Bit 15 of a `paint_id`: the record is the casing (outline) pass of that paint class.
pub const CASING_BIT: u16 = 0x8000;

/// Casing of a line is this much wider than the line, in px at display zoom 14.
const CASING_EXTRA: f32 = 1.5;
/// Outline of a filled class (building walls), in px at display zoom 14.
const OUTLINE_WIDTH: f32 = 1.0;

/// The stable number of a draw class: the position of the class in `DrawClass` declaration order
/// (which is paint order, bottom to top), with every land and street kind and every tunnel/size flag
/// counted. Never reused: a new class takes the next free number.
pub fn paint_id(class: DrawClass) -> u16 {
    match class {
        DrawClass::Ocean => 0,
        DrawClass::Water => 1,
        DrawClass::Glacier => 2,
        DrawClass::Land(kind) => 3 + kind as u16,
        DrawClass::PierArea => 18,
        DrawClass::PedestrianArea => 19,
        DrawClass::Building => 20,
        DrawClass::Waterway { small } => 21 + u16::from(small),
        DrawClass::Street { kind, tunnel } => 23 + 2 * kind as u16 + u16::from(tunnel),
        DrawClass::Rail { tunnel } => 41 + u16::from(tunnel),
        DrawClass::Boundary { country } => 43 + u16::from(country),
    }
}

/// Twice the signed area of a ring (shoelace), y down: positive is an exterior ring, negative a hole.
pub fn ring_area2(ring: &[(f32, f32)]) -> f64 {
    let n = ring.len();
    if n < 3 {
        return 0.0;
    }
    let mut sum = 0.0f64;
    for k in 0..n {
        let (x1, y1) = ring[k];
        let (x2, y2) = ring[(k + 1) % n];
        sum += f64::from(x1) * f64::from(y2) - f64::from(x2) * f64::from(y1);
    }
    sum
}

/// One record of a class: a filled shape or a stroked line.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Kind {
    Polygon,
    Line,
}

/// A decoded record.
#[derive(Clone, Debug, PartialEq)]
pub struct Class {
    /// With [`CASING_BIT`] when the record is a casing pass.
    pub paint_id: u16,
    pub rgba: u32,
    pub width14: f32,
    pub kind: Kind,
    pub parts: Vec<Vec<(f32, f32)>>,
}

impl Class {
    pub fn is_casing(&self) -> bool {
        self.paint_id & CASING_BIT != 0
    }

    pub fn points(&self) -> usize {
        self.parts.iter().map(Vec::len).sum()
    }
}

/// A decoded buffer.
#[derive(Clone, Debug, PartialEq)]
pub struct TileBuffer {
    pub flags: u16,
    pub z: u32,
    pub x: u32,
    pub y: u32,
    pub extent: u32,
    pub background: u32,
    pub classes: Vec<Class>,
}

#[derive(Debug, PartialEq, Eq)]
pub enum DecodeError {
    BadMagic,
    BadVersion(u16),
    Truncated,
    BadKind(u8),
}

impl std::fmt::Display for DecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::BadMagic => f.write_str("not a tile buffer (magic is not RVT1)"),
            Self::BadVersion(v) => write!(f, "unsupported tile buffer version {v}"),
            Self::Truncated => f.write_str("the tile buffer is cut short"),
            Self::BadKind(k) => write!(f, "unknown record kind {k}"),
        }
    }
}

impl std::error::Error for DecodeError {}

/// Whether `bytes` starts with the buffer magic (an error envelope starts with `{`).
pub fn is_buffer(bytes: &[u8]) -> bool {
    bytes.starts_with(&MAGIC)
}

/// Encodes `geometry` with the style `flags` select (bit 0 = dark).
pub fn encode(geometry: &TileGeometry, flags: u16) -> Vec<u8> {
    let style = MapStyle::for_dark(flags & FLAG_DARK != 0);
    let mut records: Vec<Record> = Vec::new();
    for (class, group) in &geometry.groups {
        let paint = style.paint(*class);
        let id = paint_id(*class);
        match class.shape() {
            Shape::Fill => {
                let polygons = fill_parts(&group.polygons);
                if polygons.is_empty() {
                    continue;
                }
                records.push(Record::new(
                    id,
                    paint.color.to_u32(),
                    0.0,
                    Kind::Polygon,
                    polygons,
                ));
                if let Some(casing) = paint.casing {
                    let outline: Vec<Vec<(f32, f32)>> = group
                        .polygons
                        .iter()
                        .flatten()
                        .map(|ring| ring.iter().copied().chain(ring.first().copied()).collect())
                        .collect();
                    records.push(Record::new(
                        id | CASING_BIT,
                        casing.to_u32(),
                        OUTLINE_WIDTH,
                        Kind::Line,
                        outline,
                    ));
                }
            }
            Shape::Line => {
                let lines: Vec<Vec<(f32, f32)>> = group.lines.clone();
                if lines.is_empty() {
                    continue;
                }
                if let Some(casing) = paint.casing {
                    records.push(Record::new(
                        id | CASING_BIT,
                        casing.to_u32(),
                        paint.width14 + CASING_EXTRA,
                        Kind::Line,
                        lines.clone(),
                    ));
                }
                records.push(Record::new(
                    id,
                    paint.color.to_u32(),
                    paint.width14,
                    Kind::Line,
                    lines,
                ));
            }
        }
    }

    let mut out = Vec::with_capacity(32 + records.iter().map(Record::size).sum::<usize>());
    out.extend_from_slice(&MAGIC);
    out.extend_from_slice(&VERSION.to_le_bytes());
    out.extend_from_slice(&flags.to_le_bytes());
    for v in [
        u32::from(geometry.id.z),
        geometry.id.x,
        geometry.id.y,
        geometry.extent,
        style.background().to_u32(),
    ] {
        out.extend_from_slice(&v.to_le_bytes());
    }
    out.extend_from_slice(&(records.len().min(usize::from(u16::MAX)) as u16).to_le_bytes());
    for record in records.iter().take(usize::from(u16::MAX)) {
        record.write(&mut out);
    }
    out
}

/// Polygons as parts, each polygon its exterior then its holes, orientation normalised so that the
/// sign of [`ring_area2`] tells exterior from hole (a polygon whose exterior has no area is dropped).
fn fill_parts(polygons: &[Vec<Vec<(f32, f32)>>]) -> Vec<Vec<(f32, f32)>> {
    let mut parts = Vec::new();
    for polygon in polygons {
        let Some((exterior, holes)) = polygon.split_first() else {
            continue;
        };
        let Some(exterior) = oriented(exterior, true) else {
            continue;
        };
        parts.push(exterior);
        parts.extend(holes.iter().filter_map(|hole| oriented(hole, false)));
    }
    parts
}

fn oriented(ring: &[(f32, f32)], exterior: bool) -> Option<Vec<(f32, f32)>> {
    let area = ring_area2(ring);
    if area == 0.0 || !area.is_finite() {
        return None;
    }
    let mut ring = ring.to_vec();
    if (area > 0.0) != exterior {
        ring.reverse();
    }
    Some(ring)
}

struct Record {
    paint_id: u16,
    rgba: u32,
    width14: f32,
    kind: Kind,
    parts: Vec<Vec<(f32, f32)>>,
}

impl Record {
    fn new(
        paint_id: u16,
        rgba: u32,
        width14: f32,
        kind: Kind,
        parts: Vec<Vec<(f32, f32)>>,
    ) -> Self {
        Self {
            paint_id,
            rgba,
            width14,
            kind,
            parts,
        }
    }

    fn size(&self) -> usize {
        2 + 4 + 4 + 1 + 4 + self.parts.iter().map(|p| 4 + 8 * p.len()).sum::<usize>()
    }

    fn write(&self, out: &mut Vec<u8>) {
        out.extend_from_slice(&self.paint_id.to_le_bytes());
        out.extend_from_slice(&self.rgba.to_le_bytes());
        out.extend_from_slice(&self.width14.to_le_bytes());
        out.push(match self.kind {
            Kind::Polygon => 0,
            Kind::Line => 1,
        });
        out.extend_from_slice(&(self.parts.len() as u32).to_le_bytes());
        for part in &self.parts {
            out.extend_from_slice(&(part.len() as u32).to_le_bytes());
            for &(x, y) in part {
                out.extend_from_slice(&x.to_le_bytes());
                out.extend_from_slice(&y.to_le_bytes());
            }
        }
    }
}

struct Reader<'a> {
    bytes: &'a [u8],
    at: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], DecodeError> {
        let end = self.at.checked_add(n).ok_or(DecodeError::Truncated)?;
        let slice = self.bytes.get(self.at..end).ok_or(DecodeError::Truncated)?;
        self.at = end;
        Ok(slice)
    }

    fn u8(&mut self) -> Result<u8, DecodeError> {
        Ok(self.take(1)?[0])
    }

    fn u16(&mut self) -> Result<u16, DecodeError> {
        Ok(u16::from_le_bytes(
            self.take(2)?.try_into().unwrap_or([0; 2]),
        ))
    }

    fn u32(&mut self) -> Result<u32, DecodeError> {
        Ok(u32::from_le_bytes(
            self.take(4)?.try_into().unwrap_or([0; 4]),
        ))
    }

    fn f32(&mut self) -> Result<f32, DecodeError> {
        Ok(f32::from_bits(self.u32()?))
    }

    fn remaining(&self) -> usize {
        self.bytes.len() - self.at
    }
}

/// Reads a buffer written by [`encode`].
pub fn decode(bytes: &[u8]) -> Result<TileBuffer, DecodeError> {
    if !is_buffer(bytes) {
        return Err(DecodeError::BadMagic);
    }
    let mut r = Reader { bytes, at: 4 };
    let version = r.u16()?;
    if version != VERSION {
        return Err(DecodeError::BadVersion(version));
    }
    let flags = r.u16()?;
    let (z, x, y, extent, background) = (r.u32()?, r.u32()?, r.u32()?, r.u32()?, r.u32()?);
    let class_count = r.u16()?;
    let mut classes = Vec::new();
    for _ in 0..class_count {
        let paint_id = r.u16()?;
        let rgba = r.u32()?;
        let width14 = r.f32()?;
        let kind = match r.u8()? {
            0 => Kind::Polygon,
            1 => Kind::Line,
            other => return Err(DecodeError::BadKind(other)),
        };
        let part_count = r.u32()? as usize;
        let mut parts = Vec::new();
        for _ in 0..part_count {
            let n = r.u32()? as usize;
            if n.checked_mul(8).is_none_or(|need| need > r.remaining()) {
                return Err(DecodeError::Truncated);
            }
            let mut part = Vec::with_capacity(n);
            for _ in 0..n {
                part.push((r.f32()?, r.f32()?));
            }
            parts.push(part);
        }
        classes.push(Class {
            paint_id,
            rgba,
            width14,
            kind,
            parts,
        });
    }
    Ok(TileBuffer {
        flags,
        z,
        x,
        y,
        extent,
        background,
        classes,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use rovsti_geo::tiles::{LandKind, StreetKind, TileId};

    const FIXTURE: &[u8] =
        include_bytes!("../../rovsti_geo/src/tiles/testdata/osm-14-9520-6130.mvt");

    fn geometry() -> TileGeometry {
        let id = TileId::new(14, 9520, 6130).unwrap();
        TileGeometry::from_bytes(id, FIXTURE).unwrap()
    }

    #[test]
    fn the_fixture_round_trips_with_every_vertex() {
        let g = geometry();
        let bytes = encode(&g, 0);
        assert!(is_buffer(&bytes));
        let tile = decode(&bytes).unwrap();
        assert_eq!((tile.z, tile.x, tile.y), (14, 9520, 6130));
        assert_eq!(tile.extent, g.extent);
        assert_eq!(tile.flags, 0);
        assert_eq!(
            tile.background,
            MapStyle::Colorful.background().to_u32(),
            "light background"
        );
        let drawn: usize = tile
            .classes
            .iter()
            .filter(|c| !c.is_casing())
            .map(Class::points)
            .sum();
        assert_eq!(drawn, g.vertices, "every vertex of the geometry, once");
        assert!(g.vertices > 100);
        // One record per class, plus a casing record for each class whose style has a casing.
        let casings = tile.classes.iter().filter(|c| c.is_casing()).count();
        let plain = tile.classes.len() - casings;
        assert_eq!(plain, g.groups.len());
        assert!(casings > 0, "streets and buildings have casings");
        // Points are tile-local.
        let e = tile.extent as f32;
        assert!(
            tile.classes
                .iter()
                .flat_map(|c| c.parts.iter().flatten())
                .all(|&(x, y)| (-e / 8.0..=e + e / 8.0).contains(&x)
                    && (-e / 8.0..=e + e / 8.0).contains(&y))
        );
    }

    #[test]
    fn records_follow_paint_order_and_casing_comes_around_the_main_pass() {
        let tile = decode(&encode(&geometry(), 0)).unwrap();
        let mut last = 0u16;
        for c in &tile.classes {
            let id = c.paint_id & !CASING_BIT;
            assert!(id >= last, "paint ids never go back");
            last = id;
        }
        for (i, c) in tile.classes.iter().enumerate() {
            if c.is_casing() && c.kind == Kind::Line && c.width14 > OUTLINE_WIDTH {
                let main = &tile.classes[i + 1];
                assert_eq!(main.paint_id, c.paint_id & !CASING_BIT, "line casing first");
                assert!(main.width14 < c.width14);
            }
        }
    }

    #[test]
    fn polygons_are_exterior_then_holes_by_orientation() {
        let tile = decode(&encode(&geometry(), 0)).unwrap();
        let mut polygons = 0;
        let mut holes = 0;
        for c in tile.classes.iter().filter(|c| c.kind == Kind::Polygon) {
            assert!(!c.is_casing());
            let mut open = false;
            for part in &c.parts {
                let area = ring_area2(part);
                if area > 0.0 {
                    polygons += 1;
                    open = true;
                } else {
                    assert!(area < 0.0 && open, "a hole follows an exterior");
                    holes += 1;
                }
            }
        }
        assert!(polygons > 0);
        let g = geometry();
        let expected: usize = g.groups.iter().map(|(_, c)| c.polygons.len()).sum();
        assert_eq!(polygons, expected, "one exterior per polygon");
        let _ = holes;
    }

    #[test]
    fn dark_flag_switches_the_palette() {
        let g = geometry();
        let light = decode(&encode(&g, 0)).unwrap();
        let dark = decode(&encode(&g, FLAG_DARK | FLAG_CACHE_ONLY)).unwrap();
        assert_eq!(dark.flags, FLAG_DARK | FLAG_CACHE_ONLY);
        assert_ne!(light.background, dark.background);
        assert_eq!(dark.background, MapStyle::Eclipse.background().to_u32());
        assert_eq!(light.classes.len(), dark.classes.len());
        assert_ne!(light.classes[0].rgba, dark.classes[0].rgba);
    }

    #[test]
    fn an_empty_tile_is_header_and_background_only() {
        let id = TileId::new(3, 1, 2).unwrap();
        let bytes = encode(&TileGeometry::empty(id), 0);
        assert_eq!(bytes.len(), 4 + 2 + 2 + 5 * 4 + 2);
        let tile = decode(&bytes).unwrap();
        assert!(tile.classes.is_empty());
        assert_eq!((tile.z, tile.x, tile.y, tile.extent), (3, 1, 2, 4096));
    }

    #[test]
    fn paint_ids_are_dense_unique_and_in_paint_order() {
        let mut classes = vec![
            DrawClass::Ocean,
            DrawClass::Water,
            DrawClass::Glacier,
            DrawClass::PierArea,
            DrawClass::PedestrianArea,
            DrawClass::Building,
        ];
        use LandKind::*;
        for k in [
            Commercial,
            Industrial,
            Residential,
            Agriculture,
            Waste,
            Park,
            Garden,
            Burial,
            Leisure,
            Rock,
            Forest,
            Grass,
            Vegetation,
            Sand,
            Wetland,
        ] {
            classes.push(DrawClass::Land(k));
        }
        for small in [false, true] {
            classes.push(DrawClass::Waterway { small });
        }
        use StreetKind::*;
        for kind in [
            Path, Cycleway, Track, Pedestrian, Service, Minor, Tertiary, Arterial, Motorway,
        ] {
            for tunnel in [false, true] {
                classes.push(DrawClass::Street { kind, tunnel });
            }
        }
        for tunnel in [false, true] {
            classes.push(DrawClass::Rail { tunnel });
        }
        for country in [false, true] {
            classes.push(DrawClass::Boundary { country });
        }
        classes.sort();
        let ids: Vec<u16> = classes.iter().map(|c| paint_id(*c)).collect();
        assert_eq!(
            ids,
            (0..45).collect::<Vec<u16>>(),
            "sorted classes are ids 0..45"
        );
    }

    #[test]
    fn damaged_buffers_are_refused_not_trusted() {
        let bytes = encode(&geometry(), 0);
        assert_eq!(decode(b"{\"err\":1}"), Err(DecodeError::BadMagic));
        assert_eq!(
            decode(&bytes[..bytes.len() - 3]),
            Err(DecodeError::Truncated)
        );
        let mut bad = bytes.clone();
        bad[4] = 9;
        assert_eq!(decode(&bad), Err(DecodeError::BadVersion(9)));
        // A part that claims more points than the buffer holds must not allocate them.
        let mut huge = encode(&geometry(), 0);
        let first_part_at = 4 + 2 + 2 + 20 + 2 + (2 + 4 + 4 + 1 + 4);
        huge[first_part_at..first_part_at + 4].copy_from_slice(&u32::MAX.to_le_bytes());
        assert_eq!(decode(&huge), Err(DecodeError::Truncated));
    }
}
