//! A small hand-written Mapbox Vector Tile decoder (protobuf wire format, no prost).
//!
//! Only what Shortbread tiles need: layers, keys/values, features and their
//! command-encoded geometry. Unknown fields are skipped. Every read is bounds
//! checked, so malformed input returns an [`MvtError`] and never panics.

use std::io::Read;

/// Largest tile accepted, before and after gunzip.
pub const MAX_TILE_BYTES: usize = 16 * 1024 * 1024;
const DEFAULT_EXTENT: u32 = 4096;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum MvtError {
    #[error("tile data ends too early")]
    Truncated,
    #[error("invalid varint")]
    Varint,
    #[error("unsupported protobuf wire type {0}")]
    WireType(u8),
    #[error("text is not valid UTF-8")]
    Utf8,
    #[error("invalid geometry")]
    Geometry,
    #[error("tile is too large")]
    TooLarge,
}

type Result<T> = std::result::Result<T, MvtError>;

/// A decoded tile attribute value.
#[derive(Clone, Debug, PartialEq)]
pub enum MvtValue {
    String(String),
    Float(f32),
    Double(f64),
    Int(i64),
    UInt(u64),
    SInt(i64),
    Bool(bool),
}

impl MvtValue {
    pub fn as_str(&self) -> Option<&str> {
        match self {
            Self::String(s) => Some(s),
            _ => None,
        }
    }

    /// Integer kinds only (a `UInt` above `i64::MAX` gives `None`).
    pub fn as_i64(&self) -> Option<i64> {
        match self {
            Self::Int(v) | Self::SInt(v) => Some(*v),
            Self::UInt(v) => i64::try_from(*v).ok(),
            _ => None,
        }
    }

    pub fn as_bool(&self) -> Option<bool> {
        match self {
            Self::Bool(b) => Some(*b),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum GeomType {
    #[default]
    Unknown,
    Point,
    LineString,
    Polygon,
}

impl GeomType {
    fn from_code(code: u64) -> Self {
        match code {
            1 => Self::Point,
            2 => Self::LineString,
            3 => Self::Polygon,
            _ => Self::Unknown,
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct MvtFeature {
    pub id: Option<u64>,
    /// Alternating key and value indices into the layer tables.
    pub tags: Vec<u32>,
    pub geom_type: GeomType,
    /// Raw command stream; see [`MvtFeature::decode_geometry`].
    pub geometry: Vec<u32>,
}

impl MvtFeature {
    /// Decodes the command stream into parts: one per MoveTo. Polygon rings are
    /// closed (ClosePath repeats the first point). Points come out as one-vertex
    /// parts.
    pub fn decode_geometry(&self) -> Result<Vec<Vec<(i32, i32)>>> {
        let mut parts: Vec<Vec<(i32, i32)>> = Vec::new();
        let (mut x, mut y) = (0i64, 0i64);
        let mut i = 0;
        let g = &self.geometry;
        while i < g.len() {
            let cmd = g[i];
            i += 1;
            let id = cmd & 7;
            let count = (cmd >> 3) as usize;
            match id {
                1 | 2 => {
                    // Each repetition takes two parameters; reject counts that
                    // overrun the stream before allocating anything.
                    let needed = count.checked_mul(2).ok_or(MvtError::Geometry)?;
                    if needed > g.len() - i || (id == 1 && count == 0) {
                        return Err(MvtError::Geometry);
                    }
                    for _ in 0..count {
                        x += unzigzag(g[i]);
                        y += unzigzag(g[i + 1]);
                        i += 2;
                        let p = (
                            i32::try_from(x).map_err(|_| MvtError::Geometry)?,
                            i32::try_from(y).map_err(|_| MvtError::Geometry)?,
                        );
                        if id == 1 {
                            parts.push(vec![p]);
                        } else {
                            parts.last_mut().ok_or(MvtError::Geometry)?.push(p);
                        }
                    }
                }
                7 => {
                    if count != 1 {
                        return Err(MvtError::Geometry);
                    }
                    let part = parts.last_mut().ok_or(MvtError::Geometry)?;
                    let first = part[0];
                    part.push(first);
                }
                _ => return Err(MvtError::Geometry),
            }
        }
        Ok(parts)
    }
}

fn unzigzag(v: u32) -> i64 {
    i64::from(v >> 1) ^ -i64::from(v & 1)
}

/// Doubled signed area of a ring (shoelace). In tile coordinates (y down) a
/// positive value is an exterior ring and a negative one a hole.
pub fn ring_area2(ring: &[(i32, i32)]) -> i64 {
    let n = ring.len();
    if n < 3 {
        return 0;
    }
    let mut sum = 0i64;
    for k in 0..n {
        let (x1, y1) = ring[k];
        let (x2, y2) = ring[(k + 1) % n];
        sum += i64::from(x1) * i64::from(y2) - i64::from(x2) * i64::from(y1);
    }
    sum
}

#[derive(Clone, Debug, PartialEq)]
pub struct MvtLayer {
    pub name: String,
    pub extent: u32,
    pub keys: Vec<String>,
    pub values: Vec<MvtValue>,
    pub features: Vec<MvtFeature>,
}

impl MvtLayer {
    fn empty() -> Self {
        Self {
            name: String::new(),
            extent: DEFAULT_EXTENT,
            keys: Vec::new(),
            values: Vec::new(),
            features: Vec::new(),
        }
    }

    /// The value of attribute `key` on `feature`, if set.
    pub fn get(&self, feature: &MvtFeature, key: &str) -> Option<&MvtValue> {
        let ki = self.keys.iter().position(|k| k == key)?;
        feature
            .tags
            .as_chunks::<2>()
            .0
            .iter()
            .find(|t| t[0] as usize == ki)
            .and_then(|t| self.values.get(t[1] as usize))
    }

    pub fn str_of(&self, feature: &MvtFeature, key: &str) -> Option<&str> {
        self.get(feature, key).and_then(MvtValue::as_str)
    }
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct MvtTile {
    pub layers: Vec<MvtLayer>,
}

impl MvtTile {
    pub fn layer(&self, name: &str) -> Option<&MvtLayer> {
        self.layers.iter().find(|l| l.name == name)
    }
}

struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    fn done(&self) -> bool {
        self.pos >= self.buf.len()
    }

    fn varint(&mut self) -> Result<u64> {
        let mut v = 0u64;
        for i in 0..10 {
            let b = *self.buf.get(self.pos).ok_or(MvtError::Truncated)?;
            self.pos += 1;
            v |= u64::from(b & 0x7f) << (7 * i);
            if b & 0x80 == 0 {
                return Ok(v);
            }
        }
        Err(MvtError::Varint)
    }

    fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        let end = self.pos.checked_add(n).ok_or(MvtError::Truncated)?;
        let s = self.buf.get(self.pos..end).ok_or(MvtError::Truncated)?;
        self.pos = end;
        Ok(s)
    }

    fn bytes(&mut self) -> Result<&'a [u8]> {
        let n = usize::try_from(self.varint()?).map_err(|_| MvtError::Truncated)?;
        self.take(n)
    }

    fn string(&mut self) -> Result<String> {
        let b = self.bytes()?;
        std::str::from_utf8(b)
            .map(str::to_owned)
            .map_err(|_| MvtError::Utf8)
    }

    /// Reads a field header: (field number, wire type).
    fn tag(&mut self) -> Result<(u64, u8)> {
        let t = self.varint()?;
        Ok((t >> 3, (t & 7) as u8))
    }

    fn skip(&mut self, wire: u8) -> Result<()> {
        match wire {
            0 => self.varint().map(drop),
            1 => self.take(8).map(drop),
            2 => self.bytes().map(drop),
            5 => self.take(4).map(drop),
            w => Err(MvtError::WireType(w)),
        }
    }

    /// Appends a repeated varint field that may be packed (wire 2) or not (wire 0).
    fn repeated_u32(&mut self, wire: u8, out: &mut Vec<u32>) -> Result<()> {
        match wire {
            0 => out.push(self.varint()? as u32),
            2 => {
                let mut sub = Reader::new(self.bytes()?);
                while !sub.done() {
                    out.push(sub.varint()? as u32);
                }
            }
            w => return Err(MvtError::WireType(w)),
        }
        Ok(())
    }
}

/// Decodes an uncompressed tile.
pub fn decode(bytes: &[u8]) -> Result<MvtTile> {
    if bytes.len() > MAX_TILE_BYTES {
        return Err(MvtError::TooLarge);
    }
    let mut r = Reader::new(bytes);
    let mut tile = MvtTile::default();
    while !r.done() {
        match r.tag()? {
            (3, 2) => tile.layers.push(decode_layer(r.bytes()?)?),
            (_, w) => r.skip(w)?,
        }
    }
    Ok(tile)
}

/// Decodes a tile that may be gzip-compressed (detected by the `1f 8b` magic).
pub fn decode_any(bytes: &[u8]) -> Result<MvtTile> {
    if bytes.starts_with(&[0x1f, 0x8b]) {
        let mut out = Vec::new();
        flate2::read::GzDecoder::new(bytes)
            .take(MAX_TILE_BYTES as u64 + 1)
            .read_to_end(&mut out)
            .map_err(|_| MvtError::Truncated)?;
        decode(&out)
    } else {
        decode(bytes)
    }
}

fn decode_layer(buf: &[u8]) -> Result<MvtLayer> {
    let mut r = Reader::new(buf);
    let mut layer = MvtLayer::empty();
    while !r.done() {
        match r.tag()? {
            (1, 2) => layer.name = r.string()?,
            (2, 2) => layer.features.push(decode_feature(r.bytes()?)?),
            (3, 2) => layer.keys.push(r.string()?),
            (4, 2) => layer.values.push(decode_value(r.bytes()?)?),
            (5, 0) => layer.extent = r.varint()? as u32,
            (_, w) => r.skip(w)?,
        }
    }
    Ok(layer)
}

fn decode_feature(buf: &[u8]) -> Result<MvtFeature> {
    let mut r = Reader::new(buf);
    let mut f = MvtFeature::default();
    while !r.done() {
        match r.tag()? {
            (1, 0) => f.id = Some(r.varint()?),
            (2, w) if w == 0 || w == 2 => r.repeated_u32(w, &mut f.tags)?,
            (3, 0) => f.geom_type = GeomType::from_code(r.varint()?),
            (4, w) if w == 0 || w == 2 => r.repeated_u32(w, &mut f.geometry)?,
            (_, w) => r.skip(w)?,
        }
    }
    Ok(f)
}

fn decode_value(buf: &[u8]) -> Result<MvtValue> {
    let mut r = Reader::new(buf);
    let mut value = None;
    while !r.done() {
        value = Some(match r.tag()? {
            (1, 2) => MvtValue::String(r.string()?),
            (2, 5) => {
                let b = r.take(4)?;
                MvtValue::Float(f32::from_le_bytes([b[0], b[1], b[2], b[3]]))
            }
            (3, 1) => {
                let b = r.take(8)?;
                let mut a = [0u8; 8];
                a.copy_from_slice(b);
                MvtValue::Double(f64::from_le_bytes(a))
            }
            (4, 0) => MvtValue::Int(r.varint()? as i64),
            (5, 0) => MvtValue::UInt(r.varint()?),
            (6, 0) => {
                let v = r.varint()?;
                MvtValue::SInt(((v >> 1) as i64) ^ -((v & 1) as i64))
            }
            (7, 0) => MvtValue::Bool(r.varint()? != 0),
            (_, w) => {
                r.skip(w)?;
                continue;
            }
        });
    }
    // An empty value message carries nothing; keep indices aligned anyway.
    Ok(value.unwrap_or(MvtValue::Bool(false)))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    const FIXTURE: &[u8] = include_bytes!("testdata/osm-14-9520-6130.mvt");

    // ---- test-only encoder ----
    fn varint(out: &mut Vec<u8>, mut v: u64) {
        loop {
            let b = (v & 0x7f) as u8;
            v >>= 7;
            if v == 0 {
                out.push(b);
                return;
            }
            out.push(b | 0x80);
        }
    }
    fn field(out: &mut Vec<u8>, num: u64, wire: u8, body: &[u8]) {
        varint(out, (num << 3) | u64::from(wire));
        if wire == 2 {
            varint(out, body.len() as u64);
        }
        out.extend_from_slice(body);
    }
    fn vfield(out: &mut Vec<u8>, num: u64, v: u64) {
        varint(out, num << 3);
        varint(out, v);
    }
    fn zz(v: i32) -> u32 {
        ((v << 1) ^ (v >> 31)) as u32
    }
    fn packed(vals: &[u32]) -> Vec<u8> {
        let mut b = Vec::new();
        for v in vals {
            varint(&mut b, u64::from(*v));
        }
        b
    }
    fn move_to(pts: &[(i32, i32)]) -> Vec<u32> {
        let mut g = vec![(pts.len() as u32) << 3 | 1];
        for p in pts {
            g.extend([zz(p.0), zz(p.1)]);
        }
        g
    }
    /// Deltas are taken from the previous absolute point.
    fn encode_geom(parts: &[(Vec<(i32, i32)>, bool)]) -> Vec<u32> {
        let (mut cx, mut cy) = (0, 0);
        let mut g = Vec::new();
        for (pts, close) in parts {
            g.push(1 << 3 | 1);
            g.extend([zz(pts[0].0 - cx), zz(pts[0].1 - cy)]);
            (cx, cy) = pts[0];
            if pts.len() > 1 {
                g.push(((pts.len() - 1) as u32) << 3 | 2);
                for p in &pts[1..] {
                    g.extend([zz(p.0 - cx), zz(p.1 - cy)]);
                    (cx, cy) = *p;
                }
            }
            if *close {
                g.push(1 << 3 | 7);
            }
        }
        g
    }
    fn feature(id: u64, tags: &[u32], ty: u64, geom: &[u32]) -> Vec<u8> {
        let mut f = Vec::new();
        vfield(&mut f, 1, id);
        field(&mut f, 2, 2, &packed(tags));
        vfield(&mut f, 3, ty);
        field(&mut f, 4, 2, &packed(geom));
        vfield(&mut f, 99, 7); // unknown varint
        field(&mut f, 98, 2, b"zz"); // unknown bytes
        field(&mut f, 97, 1, &[0; 8]); // unknown fixed64
        field(&mut f, 96, 5, &[0; 4]); // unknown fixed32
        f
    }
    fn value(kind: u64, wire: u8, body: &[u8]) -> Vec<u8> {
        let mut v = Vec::new();
        field(&mut v, kind, wire, body);
        v
    }
    fn layer_bytes(name: &str, extent: Option<u64>, feats: &[Vec<u8>]) -> Vec<u8> {
        let mut l = Vec::new();
        field(&mut l, 1, 2, name.as_bytes());
        for f in feats {
            field(&mut l, 2, 2, f);
        }
        field(&mut l, 3, 2, b"kind");
        field(&mut l, 3, 2, b"n");
        let mut vals = vec![
            value(1, 2, b"park"),
            value(2, 5, &1.5f32.to_le_bytes()),
            value(3, 1, &2.25f64.to_le_bytes()),
            value(4, 0, &{
                let mut b = Vec::new();
                varint(&mut b, (-5i64) as u64);
                b
            }),
            value(5, 0, &[42]),
            value(6, 0, &[9]), // zigzag 9 = -5
            value(7, 0, &[1]),
        ];
        // Unknown field inside a value, before the real one.
        let mut weird = Vec::new();
        vfield(&mut weird, 50, 1);
        weird.extend(value(1, 2, b"x"));
        vals.push(weird);
        for v in &vals {
            field(&mut l, 4, 2, v);
        }
        if let Some(e) = extent {
            vfield(&mut l, 5, e);
        }
        vfield(&mut l, 15, 2); // version
        l
    }

    #[test]
    fn fixture_layers_and_counts() {
        let t = decode(FIXTURE).expect("fixture decodes");
        let counts: Vec<(&str, usize)> = t
            .layers
            .iter()
            .map(|l| (l.name.as_str(), l.features.len()))
            .collect();
        let get = |n: &str| counts.iter().find(|c| c.0 == n).map(|c| c.1);
        assert_eq!(get("buildings"), Some(124));
        assert_eq!(get("land"), Some(3));
        assert_eq!(get("mountain_peaks"), Some(1));
        assert_eq!(get("pois"), Some(3));
        assert_eq!(get("street_labels"), Some(1));
        assert_eq!(get("streets"), Some(6));
        assert_eq!(get("water_lines"), Some(1));
        assert_eq!(counts.len(), 7);
        assert!(t.layers.iter().all(|l| l.extent == 4096));
    }

    #[test]
    fn fixture_streets_have_kind() {
        let t = decode(FIXTURE).expect("decodes");
        let streets = t.layer("streets").expect("streets");
        assert!(streets.keys.iter().any(|k| k == "kind"));
        assert!(
            streets
                .features
                .iter()
                .all(|f| streets.str_of(f, "kind").is_some())
        );
    }

    #[test]
    fn fixture_buildings_are_positive_rings_in_range() {
        let t = decode(FIXTURE).expect("decodes");
        let b = t.layer("buildings").expect("buildings");
        let e = b.extent as i32;
        for f in &b.features {
            assert_eq!(f.geom_type, GeomType::Polygon);
            let parts = f.decode_geometry().expect("geometry");
            assert!(ring_area2(&parts[0]) > 0, "first ring is exterior");
            assert_eq!(parts[0].first(), parts[0].last());
            for &(x, y) in parts.iter().flatten() {
                assert!((-e..2 * e).contains(&x) && (-e..2 * e).contains(&y));
            }
        }
    }

    #[test]
    fn fixture_all_geometry_decodes() {
        let t = decode(FIXTURE).expect("decodes");
        for l in &t.layers {
            for f in &l.features {
                assert!(!f.decode_geometry().expect("geometry").is_empty());
            }
        }
    }

    #[test]
    fn gzip_decodes_identically() {
        let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        enc.write_all(FIXTURE).expect("write");
        let gz = enc.finish().expect("finish");
        assert_eq!(&gz[..2], &[0x1f, 0x8b]);
        assert_eq!(decode_any(&gz), decode(FIXTURE));
        assert_eq!(decode_any(FIXTURE), decode(FIXTURE));
    }

    #[test]
    fn gzip_bomb_is_too_large() {
        let mut enc = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::fast());
        enc.write_all(&vec![0u8; MAX_TILE_BYTES + 10])
            .expect("write");
        let gz = enc.finish().expect("finish");
        assert_eq!(decode_any(&gz), Err(MvtError::TooLarge));
    }

    #[test]
    fn synthetic_tile_with_all_features() {
        let outer = vec![(0, 0), (100, 0), (100, 100), (0, 100)];
        let hole = vec![(20, 20), (20, 80), (80, 80), (80, 20)];
        let poly = encode_geom(&[(outer.clone(), true), (hole.clone(), true)]);
        let multi_line = encode_geom(&[
            (vec![(1, 1), (5, 5), (-3, 9)], false),
            (vec![(200, 200), (300, 250)], false),
        ]);
        let point = move_to(&[(7, 8), (9, 10)]);
        let feats = [
            feature(10, &[0, 0, 1, 6], 3, &poly),
            feature(11, &[0, 0], 2, &multi_line),
            feature(12, &[], 1, &point),
        ];
        let mut tile = Vec::new();
        field(&mut tile, 3, 2, &layer_bytes("test", Some(512), &feats));
        field(&mut tile, 3, 2, &layer_bytes("other", None, &[]));
        vfield(&mut tile, 77, 1); // unknown top-level field

        let t = decode(&tile).expect("decodes");
        assert_eq!(t.layers.len(), 2);
        assert_eq!(t.layers[1].extent, 4096);
        let l = &t.layers[0];
        assert_eq!((l.name.as_str(), l.extent), ("test", 512));
        assert_eq!(l.keys, ["kind", "n"]);
        assert_eq!(
            l.values,
            vec![
                MvtValue::String("park".into()),
                MvtValue::Float(1.5),
                MvtValue::Double(2.25),
                MvtValue::Int(-5),
                MvtValue::UInt(42),
                MvtValue::SInt(-5),
                MvtValue::Bool(true),
                MvtValue::String("x".into()),
            ]
        );
        let (p, ml, pt) = (&l.features[0], &l.features[1], &l.features[2]);
        assert_eq!(p.id, Some(10));
        assert_eq!(l.str_of(p, "kind"), Some("park"));
        assert_eq!(l.get(p, "n"), Some(&MvtValue::Bool(true)));
        assert_eq!(l.get(p, "n").and_then(MvtValue::as_bool), Some(true));
        assert_eq!(l.get(p, "missing"), None);
        assert_eq!(l.str_of(pt, "kind"), None);

        let parts = p.decode_geometry().expect("poly");
        assert_eq!(parts.len(), 2);
        let mut closed = outer.clone();
        closed.push(outer[0]);
        assert_eq!(parts[0], closed);
        assert!(ring_area2(&parts[0]) > 0);
        assert!(ring_area2(&parts[1]) < 0, "hole winds the other way");
        assert_eq!(ring_area2(&parts[0]), 2 * 100 * 100);

        let lines = ml.decode_geometry().expect("lines");
        assert_eq!(lines[0], vec![(1, 1), (5, 5), (-3, 9)]);
        assert_eq!(lines[1], vec![(200, 200), (300, 250)]);
        assert_eq!(
            pt.decode_geometry().expect("points"),
            vec![vec![(7, 8)], vec![(16, 18)]]
        );
    }

    #[test]
    fn value_accessors() {
        assert_eq!(MvtValue::Int(3).as_i64(), Some(3));
        assert_eq!(MvtValue::SInt(-3).as_i64(), Some(-3));
        assert_eq!(MvtValue::UInt(3).as_i64(), Some(3));
        assert_eq!(MvtValue::UInt(u64::MAX).as_i64(), None);
        assert_eq!(MvtValue::Bool(true).as_i64(), None);
        assert_eq!(MvtValue::String("a".into()).as_str(), Some("a"));
        assert_eq!(MvtValue::Int(1).as_str(), None);
    }

    /// Byte offsets where the top-level layer messages end.
    fn layer_boundaries(bytes: &[u8]) -> Vec<usize> {
        let mut r = Reader::new(bytes);
        let mut ends = vec![0];
        while !r.done() {
            let (_, w) = r.tag().expect("tag");
            r.skip(w).expect("skip");
            ends.push(r.pos);
        }
        ends
    }

    #[test]
    fn every_fixture_prefix_is_safe() {
        let ends = layer_boundaries(FIXTURE);
        for n in 0..FIXTURE.len() {
            let r = decode(&FIXTURE[..n]);
            // A cut exactly between two layers is a valid, shorter tile.
            if ends.contains(&n) {
                assert!(r.is_ok(), "prefix {n} at a layer boundary");
            } else {
                assert!(r.is_err(), "prefix {n} should fail");
            }
        }
        assert!(decode(FIXTURE).is_ok());
    }

    #[test]
    fn eleven_byte_varint_is_rejected() {
        let mut b = vec![0xff; 10];
        b.push(0x01);
        assert_eq!(decode(&b), Err(MvtError::Varint));
    }

    #[test]
    fn bad_wire_type_and_utf8() {
        assert_eq!(decode(&[0x03]), Err(MvtError::WireType(3)));
        let mut layer = Vec::new();
        field(&mut layer, 1, 2, &[0xff, 0xfe]);
        let mut tile = Vec::new();
        field(&mut tile, 3, 2, &layer);
        assert_eq!(decode(&tile), Err(MvtError::Utf8));
    }

    #[test]
    fn oversize_input_is_too_large() {
        assert_eq!(
            decode(&vec![0u8; MAX_TILE_BYTES + 1]),
            Err(MvtError::TooLarge)
        );
    }

    fn feature_with_geom(geom: Vec<u32>) -> MvtFeature {
        MvtFeature {
            geom_type: GeomType::LineString,
            geometry: geom,
            ..MvtFeature::default()
        }
    }

    #[test]
    fn overlong_command_count_is_geometry_error() {
        // MoveTo(1) then LineTo claiming a million vertices with one pair.
        let g = vec![1 << 3 | 1, 0, 0, 1_000_000 << 3 | 2, 2, 2];
        assert_eq!(
            feature_with_geom(g).decode_geometry(),
            Err(MvtError::Geometry)
        );
        let huge = vec![!7u32 | 1, 0, 0];
        assert_eq!(
            feature_with_geom(huge).decode_geometry(),
            Err(MvtError::Geometry)
        );
    }

    #[test]
    fn other_bad_geometry() {
        for g in [
            vec![1 << 3 | 2, 2, 2],             // LineTo with no MoveTo
            vec![1 << 3 | 7],                   // ClosePath with no ring
            vec![1 << 3 | 1, 0, 0, 2 << 3 | 7], // ClosePath count 2
            vec![1 << 3 | 3, 0, 0],             // unknown command
            vec![1],                            // MoveTo count 0
            vec![1 << 3 | 1, u32::MAX - 1, 0, 1 << 3 | 1, u32::MAX - 1, 0], // i32 overflow
        ] {
            assert_eq!(
                feature_with_geom(g.clone()).decode_geometry(),
                Err(MvtError::Geometry),
                "{g:?}"
            );
        }
        assert_eq!(feature_with_geom(vec![]).decode_geometry(), Ok(vec![]));
    }

    #[test]
    fn ring_area_degenerate() {
        assert_eq!(ring_area2(&[]), 0);
        assert_eq!(ring_area2(&[(0, 0), (5, 5)]), 0);
    }
}
