//! Turning shared text (a `geo:` URI, a maps link, bare coordinates) into a
//! destination. Pure string work: nothing is ever fetched, so short links and
//! address-only queries are refused rather than resolved.

use crate::Coord;
use crate::coord_format::parse_coord;

/// Longest label kept from a share, in characters.
const MAX_LABEL_CHARS: usize = 100;

/// A destination recovered from shared text.
#[derive(Clone, Debug, PartialEq)]
pub struct SharedPlace {
    pub coord: Coord,
    pub label: Option<String>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DestError {
    /// A location link, but it carries no coordinates (short link, address).
    NoCoordinates,
    /// Coordinates outside latitude -90..=90 or longitude -180..=180.
    OutOfRange,
    /// Nothing in the text looks like a location.
    NotALocation,
}

impl DestError {
    /// Text for the user.
    pub fn message(self) -> &'static str {
        match self {
            Self::NoCoordinates => {
                "This link has no coordinates. Share the pin from your maps app instead."
            }
            Self::OutOfRange => "These coordinates are not on Earth. Check the link.",
            Self::NotALocation => "This does not look like a location.",
        }
    }
}

impl std::fmt::Display for DestError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for DestError {}

/// Text to share a place: the name (if any) on its own line, then an
/// OpenStreetMap link that `parse_location` reads back.
pub fn share_text(label: &str, coord: Coord) -> String {
    let link = format!(
        "https://www.openstreetmap.org/?mlat={lat:.5}&mlon={lon:.5}#map=17/{lat:.5}/{lon:.5}",
        lat = coord.lat,
        lon = coord.lon
    );
    match label.trim() {
        "" => link,
        name => format!("{name}\n{link}"),
    }
}

/// Text to share a stop's two ends: the name on its own line, then an
/// `A:` and a `B:` OpenStreetMap link. `parse_stop_share` reads it back.
pub fn stop_share_text(name: &str, a: Coord, b: Coord) -> String {
    let link = |c: Coord| share_text("", c);
    let body = format!("A: {}\nB: {}", link(a), link(b));
    match name.trim() {
        "" => body,
        name => format!("{name}\n{body}"),
    }
}

/// Reads text from `stop_share_text`: the first location link is A, the
/// second B, and the name is the first line without a link (empty if none).
/// Fewer than two links gives `None`.
pub fn parse_stop_share(text: &str) -> Option<(String, SharedPlace, SharedPlace)> {
    let mut places = uri_tokens(text)
        .into_iter()
        .filter_map(|uri| parse_uri(uri).ok());
    let a = places.next()?;
    let b = places.next()?;
    let name = text
        .lines()
        .map(str::trim)
        .find(|l| !l.is_empty() && uri_tokens(l).is_empty())
        .unwrap_or("")
        .chars()
        .take(MAX_LABEL_CHARS)
        .collect();
    Some((name, a, b))
}

/// Payload for a QR code that opens a point in any maps app.
pub fn point_qr_payload(c: Coord, label: Option<&str>) -> String {
    rovsti_core::location_link::geo_url(c.lat, c.lon, label)
}

/// Finds a destination in free text. The first location link wins; a plain
/// `lat, lon` line is the fallback.
pub fn parse_location(text: &str) -> Result<SharedPlace, DestError> {
    let mut first_err = None;
    for uri in uri_tokens(text) {
        match parse_uri(uri) {
            Ok(place) => return Ok(place),
            Err(DestError::NotALocation) => {}
            Err(e) => {
                first_err.get_or_insert(e);
            }
        }
    }
    if let Some(e) = first_err {
        return Err(e);
    }
    let mut first_err = None;
    for line in text.lines() {
        if let Some((lat, lon, label)) = parse_pair(line) {
            match checked(lat, lon) {
                Ok(coord) => return Ok(SharedPlace { coord, label }),
                Err(e) => {
                    first_err.get_or_insert(e);
                }
            }
        }
    }
    for line in text.lines() {
        if let Some(c) = parse_coord(line) {
            match checked(c.lat, c.lon) {
                Ok(coord) => return Ok(SharedPlace { coord, label: None }),
                Err(e) => {
                    first_err.get_or_insert(e);
                }
            }
        }
    }
    Err(first_err.unwrap_or(DestError::NotALocation))
}

fn parse_uri(uri: &str) -> Result<SharedPlace, DestError> {
    if starts_with_ci(uri, "geo:") {
        return parse_geo(&uri[4..]);
    }
    let url = Url::parse(uri).ok_or(DestError::NotALocation)?;
    let host = url.host.as_str();
    let labels: Vec<&str> = host.split('.').collect();
    if host.ends_with("goo.gl") || host == "g.co" || host == "share.google" {
        return Err(DestError::NoCoordinates);
    }
    if labels.contains(&"google") {
        parse_google(&url)
    } else if host.ends_with("openstreetmap.org") || host == "osm.org" {
        parse_osm(&url)
    } else if host == "maps.apple.com" || host == "maps.apple" {
        parse_apple(&url)
    } else {
        Err(DestError::NotALocation)
    }
}

/// `geo:lat,lon[;u=..][?q=..&z=..]` (RFC 5870 plus the `q=` convention).
fn parse_geo(body: &str) -> Result<SharedPlace, DestError> {
    let (path, query) = body.split_once('?').unwrap_or((body, ""));
    let path = path.split(';').next().unwrap_or_default();
    let at_path = parse_pair(&percent_decode(path, false)).map(|(lat, lon, _)| (lat, lon));
    let q = query_pairs(query)
        .into_iter()
        .find(|(k, _)| k == "q")
        .map(|(_, v)| v);
    let q_pair = q.as_deref().and_then(parse_pair);

    let at_path_set = at_path.filter(|&p| p != (0.0, 0.0));
    if let Some((lat, lon)) = at_path_set {
        let label = match (&q_pair, &q) {
            (Some((_, _, label)), _) => label.clone(),
            (None, Some(text)) => clean_label(text),
            _ => None,
        };
        return Ok(SharedPlace {
            coord: checked(lat, lon)?,
            label,
        });
    }
    if let Some((lat, lon, label)) = q_pair {
        return Ok(SharedPlace {
            coord: checked(lat, lon)?,
            label,
        });
    }
    // geo:0,0 with or without an address query, or an unparsable path.
    if at_path.is_some() || q.is_some() {
        Err(DestError::NoCoordinates)
    } else {
        Err(DestError::NotALocation)
    }
}

fn parse_google(url: &Url) -> Result<SharedPlace, DestError> {
    let label = place_segment(&url.path)
        .map(|s| percent_decode(s, true))
        .and_then(|s| clean_label(&s));
    // The pin of a /place/ link. Preferred over `@`, which is the map centre.
    let data = format!("{}{}", url.path, url.fragment);
    let pin = num_after(&data, "!3d").zip(num_after(&data, "!4d"));
    if let Some((lat, lon)) = pin {
        return Ok(SharedPlace {
            coord: checked(lat, lon)?,
            label,
        });
    }
    const KEYS: [&str; 6] = ["q", "ll", "query", "destination", "daddr", "center"];
    if let Some((lat, lon, q_label)) = coords_from_params(&url.query, &KEYS) {
        return Ok(SharedPlace {
            coord: checked(lat, lon)?,
            label: q_label.or(label),
        });
    }
    if let Some(at) = url.path.find("/@") {
        let seg = url.path[at + 2..].split('/').next().unwrap_or_default();
        let mut it = seg.split(',');
        if let (Some(lat), Some(lon)) = (it.next().and_then(num), it.next().and_then(num)) {
            return Ok(SharedPlace {
                coord: checked(lat, lon)?,
                label,
            });
        }
    }
    Err(no_coords_or_not_location(&url.query, &KEYS, &url.path))
}

fn parse_osm(url: &Url) -> Result<SharedPlace, DestError> {
    let get = |key: &str| {
        url.query
            .iter()
            .find(|(k, _)| k == key)
            .and_then(|(_, v)| num(v.trim()))
    };
    if let (Some(lat), Some(lon)) = (get("mlat"), get("mlon")) {
        return Ok(place(checked(lat, lon)?, None));
    }
    // #map=zoom/lat/lon
    if let Some(map) = url.fragment.split('&').find_map(|p| p.strip_prefix("map=")) {
        let mut it = map.split('/');
        let _zoom = it.next();
        if let (Some(lat), Some(lon)) = (it.next().and_then(num), it.next().and_then(num)) {
            return Ok(place(checked(lat, lon)?, None));
        }
    }
    if let (Some(lat), Some(lon)) = (get("lat"), get("lon")) {
        return Ok(place(checked(lat, lon)?, None));
    }
    // /node/123, /go/xyz: a place, but no coordinates in the text.
    if url.path.trim_matches('/').is_empty() && url.query.is_empty() {
        Err(DestError::NotALocation)
    } else {
        Err(DestError::NoCoordinates)
    }
}

fn parse_apple(url: &Url) -> Result<SharedPlace, DestError> {
    const KEYS: [&str; 5] = ["ll", "daddr", "q", "coordinate", "sll"];
    let name = url
        .query
        .iter()
        .find(|(k, _)| k == "name")
        .and_then(|(_, v)| clean_label(v));
    if let Some((lat, lon, label)) = coords_from_params(&url.query, &KEYS) {
        // `q=Label` next to `ll=` is the usual shape of a shared pin.
        let text_q = url
            .query
            .iter()
            .find(|(k, v)| k == "q" && parse_pair(v).is_none())
            .and_then(|(_, v)| clean_label(v));
        return Ok(SharedPlace {
            coord: checked(lat, lon)?,
            label: label.or(name).or(text_q),
        });
    }
    let has_text = url
        .query
        .iter()
        .any(|(k, v)| (KEYS.contains(&k.as_str()) || k == "address") && !v.trim().is_empty());
    if has_text {
        Err(DestError::NoCoordinates)
    } else {
        Err(DestError::NotALocation)
    }
}

fn no_coords_or_not_location(query: &[(String, String)], keys: &[&str], path: &str) -> DestError {
    let has_text = query
        .iter()
        .any(|(k, v)| keys.contains(&k.as_str()) && !v.trim().is_empty());
    if has_text || path.contains("/place/") || path.contains("/search/") || path.contains("/dir/") {
        DestError::NoCoordinates
    } else {
        DestError::NotALocation
    }
}

fn place(coord: Coord, label: Option<String>) -> SharedPlace {
    SharedPlace { coord, label }
}

/// First parameter among `keys` (in that order) whose value is a coordinate
/// pair, with the optional `(Label)` suffix.
fn coords_from_params(
    query: &[(String, String)],
    keys: &[&str],
) -> Option<(f64, f64, Option<String>)> {
    keys.iter().find_map(|key| {
        query
            .iter()
            .filter(|(k, _)| k == key)
            .find_map(|(_, v)| parse_pair(v))
    })
}

fn checked(lat: f64, lon: f64) -> Result<Coord, DestError> {
    if !lat.is_finite() || !lon.is_finite() || lat.abs() > 90.0 || lon.abs() > 180.0 {
        return Err(DestError::OutOfRange);
    }
    if lat == 0.0 && lon == 0.0 {
        // Null Island is what apps emit for "no position".
        return Err(DestError::NoCoordinates);
    }
    Ok(Coord::new(lat, lon))
}

/// `lat,lon`, `lat, lon`, optionally `loc:`-prefixed and followed by
/// `(Label)`. Range is not checked here.
fn parse_pair(s: &str) -> Option<(f64, f64, Option<String>)> {
    let s = s.trim();
    let s = s.strip_prefix("loc:").unwrap_or(s);
    let (coords, label) = match s.find('(') {
        Some(i) => {
            let rest = s[i + 1..].trim_end();
            let inner = rest.strip_suffix(')')?;
            (&s[..i], clean_label(inner))
        }
        None => (s, None),
    };
    let (a, b) = coords.split_once(',')?;
    Some((num(a.trim())?, num(b.trim())?, label))
}

/// A plain decimal number (no `inf`, `nan` or exponents).
fn num(s: &str) -> Option<f64> {
    let ok = !s.is_empty()
        && s.bytes()
            .all(|b| b.is_ascii_digit() || matches!(b, b'.' | b'-' | b'+'))
        && s.bytes().any(|b| b.is_ascii_digit());
    if ok { s.parse().ok() } else { None }
}

/// The number right after `marker`, e.g. `!3d41.0256`.
fn num_after(s: &str, marker: &str) -> Option<f64> {
    let start = s.find(marker)? + marker.len();
    let rest = &s[start..];
    let end = rest
        .find(|c: char| !(c.is_ascii_digit() || matches!(c, '.' | '-')))
        .unwrap_or(rest.len());
    num(&rest[..end])
}

/// The `/place/<name>/` segment of a Google Maps path, still encoded.
fn place_segment(path: &str) -> Option<&str> {
    let mut segs = path.split('/');
    segs.find(|s| *s == "place")?;
    let name = segs.next()?;
    (!name.is_empty() && !name.starts_with('@')).then_some(name)
}

fn clean_label(s: &str) -> Option<String> {
    let s = s.trim();
    if s.is_empty() || parse_pair(s).is_some() {
        return None;
    }
    Some(s.chars().take(MAX_LABEL_CHARS).collect())
}

fn starts_with_ci(s: &str, prefix: &str) -> bool {
    s.get(..prefix.len())
        .is_some_and(|p| p.eq_ignore_ascii_case(prefix))
}

/// URI-like tokens in order. `geo:` runs to the end of the line (labels may
/// hold spaces); web links run to the next whitespace.
fn uri_tokens(text: &str) -> Vec<&str> {
    let lower = text.to_ascii_lowercase();
    let mut starts: Vec<usize> = ["geo:", "https://", "http://"]
        .iter()
        .flat_map(|needle| lower.match_indices(needle).map(|(i, _)| i))
        .collect();
    starts.sort_unstable();
    let mut out = Vec::new();
    let mut last_end = 0;
    for start in starts {
        if start < last_end {
            continue; // inside the previous token, e.g. a `geo:` in a query
        }
        let rest = &text[start..];
        let is_geo = starts_with_ci(rest, "geo:");
        let end = if is_geo {
            rest.find(['\n', '\r'])
        } else {
            rest.find(char::is_whitespace)
        }
        .unwrap_or(rest.len());
        let token = &rest[..end];
        last_end = start + end;
        let token = if is_geo {
            token.trim_end()
        } else {
            trim_url_end(token)
        };
        out.push(token);
    }
    out
}

/// Drops sentence punctuation stuck to the end of a pasted link.
fn trim_url_end(mut s: &str) -> &str {
    loop {
        let t = s.trim_end_matches(['.', ',', ';', ':', '!', '?', '"', '\'', '>', ']']);
        let t = match t.strip_suffix(')') {
            Some(inner) if !inner.contains('(') => inner,
            _ => t,
        };
        if t.len() == s.len() {
            return t;
        }
        s = t;
    }
}

/// An http(s) URL cut into the parts the parsers need.
struct Url {
    host: String,
    path: String,
    query: Vec<(String, String)>,
    fragment: String,
}

impl Url {
    fn parse(s: &str) -> Option<Self> {
        let rest = s.split_once("://")?.1;
        let (rest, fragment) = rest.split_once('#').unwrap_or((rest, ""));
        let (rest, query) = rest.split_once('?').unwrap_or((rest, ""));
        let (authority, path) = match rest.find('/') {
            Some(i) => (&rest[..i], &rest[i..]),
            None => (rest, ""),
        };
        let authority = authority.rsplit('@').next().unwrap_or_default();
        let host = authority.split(':').next().unwrap_or_default();
        if host.is_empty() {
            return None;
        }
        Some(Self {
            host: host.to_ascii_lowercase(),
            path: path.to_owned(),
            query: query_pairs(query),
            fragment: fragment.to_owned(),
        })
    }
}

fn query_pairs(query: &str) -> Vec<(String, String)> {
    query
        .split('&')
        .filter(|p| !p.is_empty())
        .map(|p| {
            let (k, v) = p.split_once('=').unwrap_or((p, ""));
            (percent_decode(k, true), percent_decode(v, true))
        })
        .collect()
}

/// Decodes `%XX` (UTF-8, invalid sequences kept as-is or replaced) and, with
/// `plus_is_space`, `+` as a space.
fn percent_decode(s: &str, plus_is_space: bool) -> String {
    let bytes = s.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        match bytes[i] {
            b'%' if i + 2 < bytes.len()
                && hex(bytes[i + 1]).is_some()
                && hex(bytes[i + 2]).is_some() =>
            {
                let hi = hex(bytes[i + 1]).unwrap_or(0);
                let lo = hex(bytes[i + 2]).unwrap_or(0);
                out.push(hi << 4 | lo);
                i += 3;
            }
            b'+' if plus_is_space => {
                out.push(b' ');
                i += 1;
            }
            b => {
                out.push(b);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    fn ok(text: &str) -> (f64, f64, Option<String>) {
        match parse_location(text) {
            Ok(p) => (p.coord.lat, p.coord.lon, p.label),
            Err(e) => panic!("{text:?} -> {e:?}"),
        }
    }

    fn close(a: (f64, f64, Option<String>), lat: f64, lon: f64, label: Option<&str>) {
        assert!((a.0 - lat).abs() < 1e-6, "lat {a:?}");
        assert!((a.1 - lon).abs() < 1e-6, "lon {a:?}");
        assert_eq!(a.2.as_deref(), label, "label {a:?}");
    }

    #[test]
    fn parse_location_takes_dms_and_plus_code() {
        close(
            ok("41°01'32.2\"N 28°58'36.0\"E"),
            41.025611,
            28.976667,
            None,
        );
        close(
            ok("Meet here\nS 33 52.1 E 151 12.5"),
            -33.868333,
            151.208333,
            None,
        );
        assert_eq!(
            parse_location("91°00'00\"N 10°00'00\"E"),
            Err(DestError::NotALocation)
        );
        assert!(parse_location("8FVC9G8F+6X").is_ok());
    }

    #[test]
    fn stop_share_round_trips() {
        let a = Coord::new(41.02567, 28.97413);
        let b = Coord::new(-33.86882, 151.20929);
        let text = stop_share_text("  Home stop ", a, b);
        let (name, pa, pb) = parse_stop_share(&text).unwrap();
        assert_eq!(name, "Home stop");
        assert!((pa.coord.lat - a.lat).abs() < 1e-5 && (pa.coord.lon - a.lon).abs() < 1e-5);
        assert!((pb.coord.lat - b.lat).abs() < 1e-5 && (pb.coord.lon - b.lon).abs() < 1e-5);
    }

    #[test]
    fn stop_share_blank_name() {
        let a = Coord::new(1.0, 2.0);
        let text = stop_share_text("  ", a, Coord::new(3.0, 4.0));
        assert!(text.starts_with("A: "));
        let (name, _, pb) = parse_stop_share(&text).unwrap();
        assert_eq!(name, "");
        assert!((pb.coord.lat - 3.0).abs() < 1e-5);
    }

    #[test]
    fn single_link_is_not_a_stop_share() {
        let text = share_text("Cafe", Coord::new(41.0, 29.0));
        assert!(parse_stop_share(&text).is_none());
        assert!(parse_location(&text).is_ok());
        assert!(parse_stop_share("no links here").is_none());
    }

    #[test]
    fn qr_payload_is_geo_url() {
        let c = Coord::new(41.0, 29.0);
        let p = point_qr_payload(c, None);
        assert!(p.starts_with("geo:"));
        assert_eq!(parse_location(&p).unwrap().coord, c);
    }

    #[test]
    fn share_text_round_trips() {
        let c = Coord::new(41.02567, 28.97413);
        let text = share_text(" Home ", c);
        assert!(text.starts_with("Home\nhttps://"));
        let back = parse_location(&text).unwrap().coord;
        assert!((back.lat - c.lat).abs() < 1e-5 && (back.lon - c.lon).abs() < 1e-5);
        assert!(share_text("  ", c).starts_with("https://"));
    }

    #[test]
    fn geo_uris() {
        close(ok("geo:41.0370,28.9850"), 41.037, 28.985, None);
        close(ok("geo:41.0370,28.9850;u=35?z=17"), 41.037, 28.985, None);
        close(
            ok("geo:0,0?q=41.0256,28.9741(Galata Tower)"),
            41.0256,
            28.9741,
            Some("Galata Tower"),
        );
        close(
            ok("geo:41.0256,28.9741?q=Galata%20Tower"),
            41.0256,
            28.9741,
            Some("Galata Tower"),
        );
        close(
            ok("geo:41.0256,28.9741?q=Galata+Tower&z=15"),
            41.0256,
            28.9741,
            Some("Galata Tower"),
        );
        close(ok("GEO:-33.8568,151.2153"), -33.8568, 151.2153, None);
    }

    #[test]
    fn geo_with_spaces_in_label_and_trailing_line() {
        close(
            ok("Shared pin\ngeo:0,0?q=40.9917,29.0233(Kadıköy Pier 2)\nsent from Maps"),
            40.9917,
            29.0233,
            Some("Kadıköy Pier 2"),
        );
    }

    #[test]
    fn google_maps_links() {
        // Two-line share: title, then link.
        close(
            ok(
                "Galata Tower\nhttps://www.google.com/maps/place/Galata+Tower/@41.0256,28.9744,17z/data=!3m1!4b1!4m6!3m5!1s0x0:0x1!8m2!3d41.0257!4d28.9741",
            ),
            41.0257,
            28.9741,
            Some("Galata Tower"),
        );
        close(
            ok("https://www.google.com/maps/@41.0370,28.9850,15z"),
            41.037,
            28.985,
            None,
        );
        close(
            ok("https://maps.google.com/?q=41.0370,28.9850"),
            41.037,
            28.985,
            None,
        );
        close(
            ok("https://maps.google.com/maps?ll=41.0370,28.9850&z=14"),
            41.037,
            28.985,
            None,
        );
        close(
            ok("https://www.google.com/maps/search/?api=1&query=41.0370%2C28.9850"),
            41.037,
            28.985,
            None,
        );
        close(
            ok("Meet me: https://www.google.com/maps/dir/?api=1&destination=40.9917,29.0233."),
            40.9917,
            29.0233,
            None,
        );
        close(
            ok("https://www.google.com/maps/place/41.0256,28.9741/@41.0256,28.9741,17z"),
            41.0256,
            28.9741,
            None,
        );
    }

    #[test]
    fn openstreetmap_links() {
        close(
            ok("https://www.openstreetmap.org/?mlat=41.0370&mlon=28.9850#map=16/41.0371/28.9851"),
            41.037,
            28.985,
            None,
        );
        close(
            ok("https://www.openstreetmap.org/#map=17/40.9917/29.0233"),
            40.9917,
            29.0233,
            None,
        );
        assert_eq!(
            parse_location("https://www.openstreetmap.org/node/123456"),
            Err(DestError::NoCoordinates)
        );
    }

    #[test]
    fn apple_maps_links() {
        close(
            ok("https://maps.apple.com/?ll=41.0370,28.9850&q=Taksim%20Square"),
            41.037,
            28.985,
            Some("Taksim Square"),
        );
        close(
            ok("https://maps.apple.com/?daddr=40.9917,29.0233"),
            40.9917,
            29.0233,
            None,
        );
        close(
            ok("http://maps.apple.com/?q=41.0370,28.9850"),
            41.037,
            28.985,
            None,
        );
        assert_eq!(
            parse_location("https://maps.apple.com/?q=Taksim+Square"),
            Err(DestError::NoCoordinates)
        );
    }

    #[test]
    fn plain_coordinates() {
        close(ok("41.0370, 28.9850"), 41.037, 28.985, None);
        close(
            ok("my spot:\n -33.8568,151.2153 "),
            -33.8568,
            151.2153,
            None,
        );
    }

    #[test]
    fn short_links_and_addresses_are_refused() {
        for text in [
            "https://maps.app.goo.gl/AbCdEf123456",
            "Look: https://goo.gl/maps/xYz123",
            "geo:0,0?q=1600+Amphitheatre+Parkway",
            "https://www.google.com/maps?q=Kadikoy+Pier",
            "https://www.google.com/maps/place/Kadikoy+Pier",
            "geo:0,0",
        ] {
            assert_eq!(
                parse_location(text),
                Err(DestError::NoCoordinates),
                "{text}"
            );
        }
    }

    #[test]
    fn out_of_range_is_refused() {
        for text in [
            "geo:91.0,10.0",
            "geo:10.0,181.0",
            "https://maps.google.com/?q=200,300",
            "https://www.google.com/maps/@95.0,10.0,15z",
            "100.5, 20.5",
        ] {
            assert_eq!(parse_location(text), Err(DestError::OutOfRange), "{text}");
        }
    }

    #[test]
    fn zero_zero_without_a_query_is_refused() {
        assert_eq!(parse_location("0,0"), Err(DestError::NoCoordinates));
        assert_eq!(
            parse_location("https://maps.google.com/?q=0,0"),
            Err(DestError::NoCoordinates)
        );
    }

    #[test]
    fn not_a_location() {
        for text in [
            "",
            "hello there",
            "https://example.com/article?id=5",
            "call me at 555-1234",
            "1.2.3, four",
        ] {
            assert_eq!(parse_location(text), Err(DestError::NotALocation), "{text}");
        }
    }

    #[test]
    fn first_location_link_wins() {
        close(
            ok("https://example.com/x\ngeo:1.5,2.5\ngeo:3,4"),
            1.5,
            2.5,
            None,
        );
    }

    #[test]
    fn percent_decoding() {
        assert_eq!(percent_decode("a%20b+c", false), "a b+c");
        assert_eq!(percent_decode("a%20b+c", true), "a b c");
        assert_eq!(percent_decode("Kad%C4%B1k%C3%B6y", true), "Kadıköy");
        assert_eq!(percent_decode("100%", true), "100%");
        assert_eq!(percent_decode("%zz%4", true), "%zz%4");
    }

    #[test]
    fn messages_are_not_empty() {
        for e in [
            DestError::NoCoordinates,
            DestError::OutOfRange,
            DestError::NotALocation,
        ] {
            assert!(!e.message().is_empty());
        }
    }
}
