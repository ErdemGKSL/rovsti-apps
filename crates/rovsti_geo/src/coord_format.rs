//! Coordinate text formats for copying and sharing: decimal, DMS, DDM,
//! Plus Code, `geo:` URI and OpenStreetMap link. Pure and platform-free.

use rovsti_core::location_link::{coords_text, geo_url, osm_url};

use crate::Coord;

/// A way to write a coordinate as text.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CoordFormat {
    /// `41.008200, 28.978400`
    Decimal,
    /// `41°00'29.5"N 28°58'42.2"E`
    Dms,
    /// `41°00.492'N 28°58.704'E`
    Ddm,
    /// Open Location Code, e.g. `8FVC9G8F+6X`.
    PlusCode,
    /// `geo:` URI for any maps app.
    GeoUri,
    /// OpenStreetMap link.
    OsmLink,
}

impl CoordFormat {
    /// Every format, in menu order.
    pub const ALL: [CoordFormat; 6] = [
        CoordFormat::Decimal,
        CoordFormat::Dms,
        CoordFormat::Ddm,
        CoordFormat::PlusCode,
        CoordFormat::GeoUri,
        CoordFormat::OsmLink,
    ];

    /// Short menu label.
    pub fn label(self) -> &'static str {
        match self {
            CoordFormat::Decimal => "Decimal",
            CoordFormat::Dms => "DMS",
            CoordFormat::Ddm => "DDM",
            CoordFormat::PlusCode => "Plus Code",
            CoordFormat::GeoUri => "geo: link",
            CoordFormat::OsmLink => "OpenStreetMap link",
        }
    }
}

/// Writes `c` in format `f`; `label` is only used by [`CoordFormat::GeoUri`].
pub fn format(c: Coord, f: CoordFormat, label: Option<&str>) -> String {
    match f {
        CoordFormat::Decimal => coords_text(c.lat, c.lon),
        CoordFormat::Dms => dms(c),
        CoordFormat::Ddm => ddm(c),
        CoordFormat::PlusCode => plus_code(c),
        CoordFormat::GeoUri => geo_url(c.lat, c.lon, label),
        CoordFormat::OsmLink => osm_url(c.lat, c.lon),
    }
}

fn finite(v: f64) -> f64 {
    if v.is_finite() { v } else { 0.0 }
}

fn hemisphere(v: f64, pos: char, neg: char) -> char {
    if v < 0.0 { neg } else { pos }
}

fn dms(c: Coord) -> String {
    fn part(v: f64, pos: char, neg: char) -> String {
        // Work in tenths of a second so rounding carries into minutes/degrees.
        let tenths = (v.abs() * 36_000.0).round() as u64;
        let (d, m, s) = (tenths / 36_000, (tenths / 600) % 60, tenths % 600);
        format!(
            "{d}°{m:02}'{}.{}\"{}",
            s / 10,
            s % 10,
            hemisphere(v, pos, neg)
        )
    }
    let (lat, lon) = (finite(c.lat), finite(c.lon));
    format!("{} {}", part(lat, 'N', 'S'), part(lon, 'E', 'W'))
}

fn ddm(c: Coord) -> String {
    fn part(v: f64, pos: char, neg: char) -> String {
        let thousandths = (v.abs() * 60_000.0).round() as u64;
        let (d, rest) = (thousandths / 60_000, thousandths % 60_000);
        format!(
            "{d}°{:02}.{:03}'{}",
            rest / 1000,
            rest % 1000,
            hemisphere(v, pos, neg)
        )
    }
    let (lat, lon) = (finite(c.lat), finite(c.lon));
    format!("{} {}", part(lat, 'N', 'S'), part(lon, 'E', 'W'))
}

const PLUS_ALPHABET: &[u8; 20] = b"23456789CFGHJMPQRVWX";
/// Grid cells per degree at 10 digits (1/8000 degree).
const PLUS_CELLS_PER_DEG: f64 = 8000.0;

/// Open Location Code with 10 digits: `8FVC9G8F+6X` (about 14 m precision).
/// Latitude is clamped to the poles and longitude wrapped.
pub fn plus_code(c: Coord) -> String {
    let lat_cells = 180 * 8000_u64;
    let lon_cells = 360 * 8000_u64;
    let lat = finite(c.lat).clamp(-90.0, 90.0);
    let lon = finite(c.lon);
    let lat_i = (((lat + 90.0) * PLUS_CELLS_PER_DEG).floor() as u64).min(lat_cells - 1);
    let lon_i = ((((lon + 180.0).rem_euclid(360.0)) * PLUS_CELLS_PER_DEG).floor() as u64)
        .min(lon_cells - 1);

    let (mut la, mut lo) = (lat_i, lon_i);
    let mut digits = [0u8; 10];
    for pair in (0..5).rev() {
        digits[pair * 2] = PLUS_ALPHABET[(la % 20) as usize];
        digits[pair * 2 + 1] = PLUS_ALPHABET[(lo % 20) as usize];
        la /= 20;
        lo /= 20;
    }
    let mut out = String::with_capacity(11);
    for (i, d) in digits.iter().enumerate() {
        if i == 8 {
            out.push('+');
        }
        out.push(*d as char);
    }
    out
}

/// Compass point (N, NE, E, SE, S, SW, W, NW) for a bearing in degrees.
pub fn cardinal(deg: f64) -> &'static str {
    const POINTS: [&str; 8] = ["N", "NE", "E", "SE", "S", "SW", "W", "NW"];
    let d = if deg.is_finite() {
        deg.rem_euclid(360.0)
    } else {
        0.0
    };
    POINTS[(((d + 22.5) / 45.0) as usize) % 8]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dms_north_east() {
        let c = Coord::new(41.008198, 28.978389);
        assert_eq!(dms(c), "41°00'29.5\"N 28°58'42.2\"E");
    }

    #[test]
    fn dms_south_west() {
        let c = Coord::new(-33.8568, -151.2153);
        assert_eq!(dms(c), "33°51'24.5\"S 151°12'55.1\"W");
    }

    #[test]
    fn dms_rounding_carries() {
        // 59.99999 s rounds up to the next minute, then the next degree.
        let c = Coord::new(10.0 + 59.0 / 60.0 + 59.99999 / 3600.0, 0.0);
        assert!(dms(c).starts_with("11°00'0.0\"N"), "{}", dms(c));
        let c = Coord::new(0.0, -(5.0 + 59.9999 / 3600.0));
        assert_eq!(dms(c), "0°00'0.0\"N 5°01'0.0\"W");
    }

    #[test]
    fn ddm_hemispheres_and_carry() {
        assert_eq!(
            ddm(Coord::new(41.008198, 28.978389)),
            "41°00.492'N 28°58.703'E"
        );
        assert_eq!(ddm(Coord::new(-33.5, -70.25)), "33°30.000'S 70°15.000'W");
        assert_eq!(ddm(Coord::new(9.9999999, 0.0)), "10°00.000'N 0°00.000'E");
    }

    #[test]
    fn plus_code_spec_example() {
        assert_eq!(plus_code(Coord::new(47.365590, 8.524997)), "8FVC9G8F+6X");
    }

    #[test]
    fn plus_code_south_west() {
        // Hand-computed cell digits; southern and western hemisphere sign handling.
        assert_eq!(plus_code(Coord::new(-33.8568, 151.2153)), "4RRH46V8+74");
        let code = plus_code(Coord::new(-34.6037, -58.3816));
        assert_eq!(code.len(), 11);
        assert_eq!(code.as_bytes()[8], b'+');
        assert!(code.starts_with("48Q3"), "{code}");
    }

    #[test]
    fn extremes_do_not_panic() {
        for (lat, lon) in [
            (90.0, 180.0),
            (-90.0, -180.0),
            (90.0, -180.0),
            (f64::NAN, f64::INFINITY),
            (123.0, 540.0),
        ] {
            let c = Coord::new(lat, lon);
            for f in CoordFormat::ALL {
                assert!(!format(c, f, None).is_empty());
            }
        }
        assert_eq!(plus_code(Coord::new(90.0, 180.0)).len(), 11);
        assert_eq!(plus_code(Coord::new(-90.0, -180.0)), "22222222+22");
    }

    #[test]
    fn format_reuses_core_links() {
        let c = Coord::new(41.0082, 28.9784);
        assert_eq!(
            format(c, CoordFormat::Decimal, None),
            "41.008200, 28.978400"
        );
        assert_eq!(
            format(c, CoordFormat::GeoUri, Some("A b")),
            "geo:41.008200,28.978400?q=41.008200,28.978400(A%20b)"
        );
        assert!(
            format(c, CoordFormat::OsmLink, None).starts_with("https://www.openstreetmap.org/")
        );
        assert_eq!(CoordFormat::ALL.len(), 6);
        assert!(CoordFormat::ALL.iter().all(|f| !f.label().is_empty()));
    }

    #[test]
    fn cardinal_boundaries() {
        assert_eq!(cardinal(0.0), "N");
        assert_eq!(cardinal(22.4), "N");
        assert_eq!(cardinal(22.5), "NE");
        assert_eq!(cardinal(90.0), "E");
        assert_eq!(cardinal(157.5), "S");
        assert_eq!(cardinal(180.0), "S");
        assert_eq!(cardinal(270.0), "W");
        assert_eq!(cardinal(337.4), "NW");
        assert_eq!(cardinal(337.5), "N");
        assert_eq!(cardinal(359.9), "N");
        assert_eq!(cardinal(-90.0), "W");
        assert_eq!(cardinal(f64::NAN), "N");
    }
}
