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

/// Decodes a full Open Location Code to the centre of its area.
///
/// Accepts 8 digits, `+`, then 0, 2 or 3 more digits (case-insensitive), or a
/// padded code such as `8FVC0000+`. Short codes (fewer than 8 digits before
/// the `+`) and malformed text give `None`. A single digit after the `+` is
/// not valid Open Location Code and is refused.
pub fn plus_code_decode(code: &str) -> Option<Coord> {
    let code = code.trim().to_ascii_uppercase();
    let (head, tail) = code.split_once('+')?;
    if head.len() != 8 || tail.len() == 1 || tail.len() > 3 || !code.is_ascii() {
        return None;
    }
    let index = |b: u8| PLUS_ALPHABET.iter().position(|&a| a == b);
    let head = head.as_bytes();
    // Padding: '0' pairs at the end of the head, only with an empty tail.
    let pad = head.iter().position(|&b| b == b'0');
    let real = match pad {
        Some(p) => {
            if p == 0 || p % 2 != 0 || !tail.is_empty() || head[p..].iter().any(|&b| b != b'0') {
                return None;
            }
            p
        }
        None => 8,
    };
    let mut digits = Vec::with_capacity(10);
    for &b in head[..real].iter().chain(tail.as_bytes().iter().take(2)) {
        digits.push(index(b)? as u64);
    }
    let pairs = digits.len() / 2;
    let (mut lat, mut lon) = (0u64, 0u64);
    for p in 0..pairs {
        let place = 20u64.pow((4 - p) as u32);
        lat += digits[p * 2] * place;
        lon += digits[p * 2 + 1] * place;
    }
    if lat >= 180 * 8000 || lon >= 360 * 8000 {
        return None;
    }
    // Cell size in 1/8000 degree units, and the centre offset inside it.
    let size = 20f64.powi(5 - pairs as i32);
    let (mut lat_off, mut lon_off) = (size / 2.0, size / 2.0);
    if tail.len() == 3 {
        let g = index(tail.as_bytes()[2])? as f64;
        let (row, col) = ((g / 4.0).floor(), g % 4.0);
        lat_off = (row + 0.5) / 5.0;
        lon_off = (col + 0.5) / 4.0;
    }
    Some(Coord::new(
        (lat as f64 + lat_off) / PLUS_CELLS_PER_DEG - 90.0,
        (lon as f64 + lon_off) / PLUS_CELLS_PER_DEG - 180.0,
    ))
}

#[derive(Clone, Copy, Debug, PartialEq)]
enum Tok {
    /// Magnitude and whether it carried a minus sign.
    Num(f64, bool),
    Hem(char),
    Deg,
    Min,
    Sec,
    Space,
    Comma,
}

fn tokenize(text: &str) -> Option<Vec<Tok>> {
    let c: Vec<char> = text.chars().collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < c.len() {
        let ch = c[i];
        match ch {
            ',' | ';' => out.push(Tok::Comma),
            _ if ch.is_whitespace() => {
                if !matches!(out.last(), Some(Tok::Space)) {
                    out.push(Tok::Space);
                }
            }
            '°' | 'º' | '˚' => out.push(Tok::Deg),
            '′' | '’' | '\'' => {
                if matches!(c.get(i + 1), Some('\'' | '’')) {
                    i += 1;
                    out.push(Tok::Sec);
                } else {
                    out.push(Tok::Min);
                }
            }
            '″' | '"' | '”' => out.push(Tok::Sec),
            'N' | 'S' | 'E' | 'W' | 'n' | 's' | 'e' | 'w' => {
                out.push(Tok::Hem(ch.to_ascii_uppercase()));
            }
            '-' | '−' | '+' | '.' | '0'..='9' => {
                let neg = matches!(ch, '-' | '−');
                if matches!(ch, '-' | '−' | '+') {
                    i += 1;
                }
                let start = i;
                while i < c.len() && (c[i].is_ascii_digit() || c[i] == '.') {
                    i += 1;
                }
                let s: String = c[start..i].iter().collect();
                if !s.bytes().any(|b| b.is_ascii_digit()) || s.matches('.').count() > 1 {
                    return None;
                }
                out.push(Tok::Num(s.parse().ok()?, neg));
                continue;
            }
            _ => return None,
        }
        i += 1;
    }
    Some(out)
}

struct Comp {
    value: f64,
    neg: bool,
    hem: Option<char>,
}

fn skip_space(t: &[Tok], i: &mut usize) {
    while matches!(t.get(*i), Some(Tok::Space)) {
        *i += 1;
    }
}

/// One latitude or longitude: `[H] D[°] [M['] [S["]] [H]`.
fn comp(t: &[Tok], i: &mut usize) -> Option<Comp> {
    skip_space(t, i);
    while matches!(t.get(*i), Some(Tok::Comma)) {
        *i += 1;
        skip_space(t, i);
    }
    let pre = match t.get(*i) {
        Some(Tok::Hem(h)) => {
            *i += 1;
            skip_space(t, i);
            Some(*h)
        }
        _ => None,
    };
    let Some(Tok::Num(first, neg)) = t.get(*i).copied() else {
        return None;
    };
    *i += 1;
    let mut parts = vec![first];
    loop {
        let pos = parts.len() - 1;
        let unit = [Tok::Deg, Tok::Min, Tok::Sec][pos];
        if t.get(*i) == Some(&unit) {
            *i += 1;
        }
        if pos == 2 {
            break;
        }
        // Look for the next part after plain spaces (a comma ends the value).
        let mut j = *i;
        skip_space(t, &mut j);
        let Some(Tok::Num(v, false)) = t.get(j).copied() else {
            break;
        };
        let next_unit = [Tok::Deg, Tok::Min, Tok::Sec][pos + 1];
        match t.get(j + 1) {
            Some(u) if *u == next_unit => {}
            Some(Tok::Deg | Tok::Min | Tok::Sec) => break,
            // Bare numbers continue only next to a hemisphere letter.
            _ if pre.is_some() || has_hem_ahead(t, j + 1) => {}
            _ => break,
        }
        parts.push(v);
        *i = j + 1;
    }
    let mut hem = pre;
    if hem.is_none() {
        let mut j = *i;
        skip_space(t, &mut j);
        if let Some(Tok::Hem(h)) = t.get(j) {
            hem = Some(*h);
            *i = j + 1;
        }
    }
    if hem.is_some() && neg {
        return None;
    }
    let mut value = 0.0;
    for (k, p) in parts.iter().enumerate() {
        if *p >= 60.0 && k > 0 {
            return None;
        }
        if k + 1 < parts.len() && p.fract() != 0.0 {
            return None;
        }
        value += p / 60f64.powi(k as i32);
    }
    Some(Comp {
        value,
        neg: neg || matches!(hem, Some('S' | 'W')),
        hem,
    })
}

/// True when a hemisphere letter follows before the next comma or number.
fn has_hem_ahead(t: &[Tok], from: usize) -> bool {
    for tok in &t[from..] {
        match tok {
            Tok::Space => {}
            Tok::Hem(_) => return true,
            _ => return false,
        }
    }
    false
}

/// Reads a coordinate typed or pasted in a common notation: DMS
/// (`41°01'32.2"N 28°58'36.0"E`, also `′ ″ ''`), degrees and decimal minutes
/// (`41° 1.537' N, 28° 58.600' E`), letter-first (`N 41.0256 E 28.9767`,
/// `S 33 52.1 E 151 12.5`), signed degrees with `°` (`-33.87° 151.21°`) or a
/// full Plus Code. Minutes and seconds must be under 60, latitude within 90
/// and longitude within 180; anything else gives `None`.
pub fn parse_coord(text: &str) -> Option<Coord> {
    let text = text.trim();
    if text.is_empty() {
        return None;
    }
    let by_code = text
        .split(|c: char| c.is_whitespace() || c == ',' || c == ';')
        .filter(|w| !w.is_empty())
        .find_map(plus_code_decode);
    if by_code.is_some() {
        return by_code;
    }
    let toks = tokenize(text)?;
    // Plain "12 34" is too easily a stray number pair: need a mark or letter.
    if !toks
        .iter()
        .any(|t| matches!(t, Tok::Hem(_) | Tok::Deg | Tok::Min | Tok::Sec))
    {
        return None;
    }
    let mut i = 0;
    let a = comp(&toks, &mut i)?;
    let b = comp(&toks, &mut i)?;
    if !toks[i..]
        .iter()
        .all(|t| matches!(t, Tok::Space | Tok::Comma))
    {
        return None;
    }
    let is_lon = |h: Option<char>| matches!(h, Some('E' | 'W'));
    let is_lat = |h: Option<char>| matches!(h, Some('N' | 'S'));
    let (lat, lon) = if is_lon(a.hem) || is_lat(b.hem) {
        (b, a)
    } else {
        (a, b)
    };
    if is_lon(lat.hem) || is_lat(lon.hem) {
        return None;
    }
    let sign = |c: &Comp| if c.neg { -c.value } else { c.value };
    let (lat, lon) = (sign(&lat), sign(&lon));
    (lat.abs() <= 90.0 && lon.abs() <= 180.0).then(|| Coord::new(lat, lon))
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

/// "1.2 km from you, NE": distance and compass point of `to` seen from `here`.
/// Under 10 m it is "You are here".
pub fn from_you_text(here: Coord, to: Coord) -> String {
    let d = here.distance_m(to);
    if d < 10.0 {
        return "You are here".to_string();
    }
    format!(
        "{} from you, {}",
        crate::session::format_distance(d),
        cardinal(here.bearing_deg(to))
    )
}

/// OpenStreetMap link to a map view (`#map=z/lat/lon`), zoom rounded to 0..=19.
pub fn osm_view_url(center: Coord, zoom: f64) -> String {
    let z = zoom.round().clamp(0.0, 19.0) as u8;
    format!(
        "https://www.openstreetmap.org/#map={z}/{:.5}/{:.5}",
        center.lat, center.lon
    )
}

/// `geo:` URI for a map view, zoom rounded to 1..=19.
pub fn geo_view_url(center: Coord, zoom: f64) -> String {
    let z = zoom.round().clamp(1.0, 19.0) as u8;
    format!("geo:{:.5},{:.5}?z={z}", center.lat, center.lon)
}

/// Share text for the current map view: a title line, then both links.
pub fn map_view_share_text(center: Coord, zoom: f64) -> String {
    format!(
        "Map view\n{}\n{}",
        osm_view_url(center, zoom),
        geo_view_url(center, zoom)
    )
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn view_urls_round_and_clamp() {
        let c = Coord::new(41.0082, 28.9784);
        assert_eq!(
            osm_view_url(c, 14.6),
            "https://www.openstreetmap.org/#map=15/41.00820/28.97840"
        );
        assert_eq!(geo_view_url(c, 14.6), "geo:41.00820,28.97840?z=15");
        assert!(osm_view_url(c, 25.0).contains("#map=19/"));
        assert!(geo_view_url(c, 25.0).ends_with("?z=19"));
        assert!(osm_view_url(c, -3.0).contains("#map=0/"));
        assert!(geo_view_url(c, -3.0).ends_with("?z=1"));
        let s = Coord::new(-33.8688, -70.5);
        assert_eq!(geo_view_url(s, 10.2), "geo:-33.86880,-70.50000?z=10");
    }

    #[test]
    fn map_view_share_text_exact() {
        let c = Coord::new(41.0082, 28.9784);
        assert_eq!(
            map_view_share_text(c, 12.0),
            "Map view\nhttps://www.openstreetmap.org/#map=12/41.00820/28.97840\ngeo:41.00820,28.97840?z=12"
        );
    }

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

    fn near(a: Coord, b: Coord, m: f64) {
        assert!(a.distance_m(b) < m, "{a:?} vs {b:?}: {} m", a.distance_m(b));
    }

    const CITIES: [(f64, f64); 4] = [
        (41.008198, 28.978389),
        (-33.8568, 151.2153),
        (-12.0464, -77.0428),
        (64.1466, -21.9426),
    ];

    #[test]
    fn plus_code_decode_spec_example() {
        let c = plus_code_decode("8FVC9G8F+6X").unwrap();
        // Centre of a ~14 m cell around the encoded point.
        near(c, Coord::new(47.365590, 8.524997), 10.0);
        assert_eq!(plus_code(c), "8FVC9G8F+6X");
        // Lowercase, 8 digits and padding.
        assert_eq!(plus_code_decode("8fvc9g8f+6x"), Some(c));
        let big = plus_code_decode("8FVC9G8F+").unwrap();
        assert!(big.distance_m(c) < 20.0 * 14.0);
        let padded = plus_code_decode("8FVC0000+").unwrap();
        assert!(padded.distance_m(c) < 20_000.0);
        // 11 digits refine the 10-digit cell.
        let fine = plus_code_decode("8FVC9G8F+6X9").unwrap();
        near(fine, c, 10.0);
    }

    #[test]
    fn plus_code_decode_rejects() {
        for bad in [
            "9G8F+6X",
            "",
            "8FVC9G8F",
            "8FVC9G8F+6",
            "8FVC9G8F+6XXX9",
            "8FVC9G8FA6X",
            "8FVC9G0F+6X",
            "8FVC0G00+",
            "8FVC0000+22",
            "ZZVC9G8F+6X",
            "8FVC9G8F+6I",
            "0000000+",
        ] {
            assert_eq!(plus_code_decode(bad), None, "{bad}");
        }
    }

    #[test]
    fn round_trip_formats_within_a_metre() {
        for (lat, lon) in CITIES {
            for f in [CoordFormat::Dms, CoordFormat::Ddm, CoordFormat::PlusCode] {
                // Snap to what the format can express, then it must be stable.
                let snapped = parse_coord(&format(Coord::new(lat, lon), f, None)).unwrap();
                near(snapped, Coord::new(lat, lon), 16.0);
                let again = parse_coord(&format(snapped, f, None)).unwrap();
                near(again, snapped, 1.0);
            }
        }
    }

    #[test]
    fn parses_dms_variants() {
        let want = Coord::new(
            41.0 + 1.0 / 60.0 + 32.2 / 3600.0,
            28.0 + 58.0 / 60.0 + 36.0 / 3600.0,
        );
        for s in [
            "41°01'32.2\"N 28°58'36.0\"E",
            "41°01′32.2″N 28°58′36.0″E",
            "41°01'32.2''N, 28°58'36.0''E",
            "41° 1' 32.2\" n 28° 58' 36\" e",
            "E 28°58'36\" N 41°01'32.2\"",
        ] {
            near(parse_coord(s).unwrap(), want, 0.1);
        }
        let sw = parse_coord("33°51'24.5\"S 151°12'55.1\"W").unwrap();
        assert!(sw.lat < 0.0 && sw.lon < 0.0);
    }

    #[test]
    fn parses_ddm_letter_first_and_signed() {
        near(
            parse_coord("41° 1.537' N, 28° 58.600' E").unwrap(),
            Coord::new(41.0 + 1.537 / 60.0, 28.0 + 58.6 / 60.0),
            0.1,
        );
        near(
            parse_coord("N 41.0256 E 28.9767").unwrap(),
            Coord::new(41.0256, 28.9767),
            0.1,
        );
        near(
            parse_coord("41.0256 N 28.9767 E").unwrap(),
            Coord::new(41.0256, 28.9767),
            0.1,
        );
        near(
            parse_coord("S 33 52.1 E 151 12.5").unwrap(),
            Coord::new(-(33.0 + 52.1 / 60.0), 151.0 + 12.5 / 60.0),
            0.1,
        );
        near(
            parse_coord("-33.8568° 151.2153°").unwrap(),
            Coord::new(-33.8568, 151.2153),
            0.1,
        );
        near(
            parse_coord("-33.8568°, -151.2153°").unwrap(),
            Coord::new(-33.8568, -151.2153),
            0.1,
        );
        near(
            parse_coord("see 8FVC9G8F+6X").unwrap(),
            plus_code_decode("8FVC9G8F+6X").unwrap(),
            0.1,
        );
    }

    #[test]
    fn parse_coord_rejects() {
        for bad in [
            "",
            "   ",
            "hello world",
            "North pole",
            "41°61'00\"N 28°00'00\"E",
            "41°00'61\"N 28°00'00\"E",
            "91°N 28°E",
            "41°N 181°E",
            "91°00'00\"N 10°E",
            "41 28",
            "41°N 28°N",
            "41°E 28°E",
            "-41°N 28°E",
            "41°",
            "9G8F+6X",
            "41.5°30'N 28°E",
        ] {
            assert_eq!(parse_coord(bad), None, "{bad}");
        }
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

    #[test]
    fn from_you_text_cases() {
        let here = Coord::new(41.0, 29.0);
        assert_eq!(
            from_you_text(here, Coord::new(41.007631, 29.010111)),
            "1.2 km from you, NE"
        );
        assert_eq!(
            from_you_text(here, Coord::new(41.00005, 29.0)),
            "You are here"
        );
        assert_eq!(
            from_you_text(here, Coord::new(40.995953, 29.0)),
            "450 m from you, S"
        );
    }
}
