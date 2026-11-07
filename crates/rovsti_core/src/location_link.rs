//! OpenStreetMap links, share text and place detection for locations.

use std::{collections::HashSet, ops::Range};

/// Whether the coordinates are finite, in range and not the (0, 0) placeholder.
pub fn is_valid(lat: f64, lon: f64) -> bool {
    lat.is_finite()
        && lon.is_finite()
        && (-90.0..=90.0).contains(&lat)
        && (-180.0..=180.0).contains(&lon)
        && !(lat == 0.0 && lon == 0.0)
}

fn wrap_lon(lon: f64) -> f64 {
    if !lon.is_finite() {
        return 0.0;
    }
    if (-180.0..=180.0).contains(&lon) {
        return lon;
    }
    let w = (lon + 180.0).rem_euclid(360.0) - 180.0;
    if w == -180.0 { 180.0 } else { w }
}

/// OpenStreetMap link centred on the point (6 decimals).
pub fn osm_url(lat: f64, lon: f64) -> String {
    let lat = if lat.is_finite() {
        lat.clamp(-90.0, 90.0)
    } else {
        0.0
    };
    let lon = wrap_lon(lon);
    format!("https://www.openstreetmap.org/?mlat={lat:.6}&mlon={lon:.6}#map=17/{lat:.6}/{lon:.6}")
}

/// Name, address (each only when not blank) and the link, one per line.
pub fn share_text(name: Option<&str>, address: Option<&str>, lat: f64, lon: f64) -> String {
    let mut lines: Vec<String> = [name, address]
        .into_iter()
        .flatten()
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_owned)
        .collect();
    lines.push(osm_url(lat, lon));
    lines.join("\n")
}

/// Percent-encodes everything but ASCII alphanumerics and `-._~`.
fn encode_query(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || matches!(b, b'-' | b'.' | b'_' | b'~') {
            out.push(b as char);
        } else {
            out.push_str(&format!("%{b:02X}"));
        }
    }
    out
}

/// `geo:41.008200,28.978400?q=41.008200,28.978400(Name)` for a maps app; the
/// label is trimmed, percent-encoded and left out when blank.
pub fn geo_url(lat: f64, lon: f64, label: Option<&str>) -> String {
    let point = format!("{lat:.6},{lon:.6}");
    let mut url = format!("geo:{point}?q={point}");
    if let Some(name) = label.map(str::trim)
        && !name.is_empty()
    {
        url.push('(');
        url.push_str(&encode_query(name));
        url.push(')');
    }
    url
}

/// `41.008200, 28.978400`, for the clipboard.
pub fn coords_text(lat: f64, lon: f64) -> String {
    format!("{lat:.6}, {lon:.6}")
}

/// A coordinate found inside a message body.
#[derive(Clone, Debug, PartialEq)]
pub struct Place {
    /// Latitude in degrees; always passes [`is_valid`] together with `lon`.
    pub lat: f64,
    /// Longitude in degrees.
    pub lon: f64,
    /// Name carried by the link, when there is one.
    pub label: Option<String>,
    /// Byte range of the match in the searched text.
    pub span: Range<usize>,
}

/// Percent-decodes `s` (`+` becomes a space when `plus`).
fn decode(s: &str, plus: bool) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() && hex(b[i + 1]).is_some() && hex(b[i + 2]).is_some() => {
                out.push(hex(b[i + 1]).unwrap_or(0) * 16 + hex(b[i + 2]).unwrap_or(0));
                i += 3;
            }
            b'+' if plus => {
                out.push(b' ');
                i += 1;
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex(b: u8) -> Option<u8> {
    char::from(b)
        .to_digit(16)
        .and_then(|d| u8::try_from(d).ok())
}

/// A plain decimal number: optional sign, digits, optional fraction.
fn number(s: &str) -> Option<f64> {
    let t = s.trim();
    let digits = t.strip_prefix(['+', '-']).unwrap_or(t);
    let mut parts = digits.splitn(2, '.');
    let int = parts.next()?;
    let frac = parts.next().unwrap_or("");
    if int.is_empty() && frac.is_empty()
        || !int.bytes().all(|b| b.is_ascii_digit())
        || !frac.bytes().all(|b| b.is_ascii_digit())
    {
        return None;
    }
    t.parse().ok()
}

/// `lat,lon` (extra comma parts such as an altitude are ignored).
fn pair(s: &str) -> Option<(f64, f64)> {
    let mut it = s.split(',');
    let lat = number(it.next()?)?;
    let lon = number(it.next()?)?;
    is_valid(lat, lon).then_some((lat, lon))
}

fn clean_label(s: &str) -> Option<String> {
    let t = s.trim();
    (!t.is_empty()).then(|| t.chars().take(120).collect())
}

/// What a `q=` style value holds: coordinates with an optional `(Label)`, or
/// just a text to use as label.
fn query_value(raw: &str) -> (Option<(f64, f64)>, Option<String>) {
    let v = decode(raw, true);
    let v = v.trim();
    let v = v.strip_prefix("loc:").unwrap_or(v);
    if let Some(open) = v.find('(')
        && let Some(inner) = v.strip_suffix(')')
        && let Some(p) = pair(&v[..open])
    {
        return (Some(p), clean_label(&inner[open + 1..]));
    }
    match pair(v) {
        Some(p) => (Some(p), None),
        None => (None, clean_label(v)),
    }
}

fn param<'a>(query: &'a str, key: &str) -> Option<&'a str> {
    query
        .split('&')
        .filter_map(|kv| kv.split_once('='))
        .find(|(k, _)| k.eq_ignore_ascii_case(key))
        .map(|(_, v)| v)
}

/// Coordinates and label from the `q`, `query` and `ll` style parameters.
fn params_place(query: &str, keys: &[&str]) -> Option<(f64, f64, Option<String>)> {
    let mut label = None;
    let mut coords = None;
    for key in keys {
        let Some(raw) = param(query, key) else {
            continue;
        };
        if *key == "ll" {
            coords = coords.or_else(|| pair(&decode(raw, false)).map(|p| (p, None)));
        } else {
            let (p, l) = query_value(raw);
            label = label.or(l.clone());
            coords = coords.or_else(|| p.map(|p| (p, l)));
        }
    }
    let ((lat, lon), own) = coords?;
    Some((lat, lon, own.or(label)))
}

fn http_place(url: &str) -> Option<(f64, f64, Option<String>)> {
    let lower = url.to_ascii_lowercase();
    let rest = &url[lower.find("://")? + 3..];
    let host_end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let host = rest[..host_end].to_ascii_lowercase();
    let host = host.rsplit('@').next().unwrap_or("");
    let host = host.split(':').next().unwrap_or("");
    let tail = &rest[host_end..];
    let (tail, fragment) = tail.split_once('#').unwrap_or((tail, ""));
    let (path, query) = tail.split_once('?').unwrap_or((tail, ""));
    let on = |d: &str| host == d || host.ends_with(&format!(".{d}"));

    if host.starts_with("google.") || host.contains(".google.") {
        if !(host.starts_with("maps.") || path.starts_with("/maps")) {
            return None;
        }
        let label = path
            .split_once("/place/")
            .and_then(|(_, r)| r.split('/').next())
            .filter(|n| !n.starts_with('@'))
            .and_then(|n| clean_label(&decode(n, true)));
        let at = path
            .split_once('@')
            .and_then(|(_, r)| pair(r.split('/').next().unwrap_or("")));
        let from_params = params_place(query, &["q", "query", "ll"]);
        return match (at, from_params) {
            (Some((lat, lon)), p) => Some((lat, lon, label.or(p.and_then(|p| p.2)))),
            (None, Some((lat, lon, l))) => Some((lat, lon, label.or(l))),
            (None, None) => None,
        };
    }
    if on("openstreetmap.org") || on("osm.org") {
        if let (Some(a), Some(o)) = (param(query, "mlat"), param(query, "mlon"))
            && let (Some(lat), Some(lon)) = (number(a), number(o))
            && is_valid(lat, lon)
        {
            return Some((lat, lon, None));
        }
        let map = fragment.split('&').find_map(|kv| kv.strip_prefix("map="))?;
        let mut it = map.split('/');
        let _zoom = it.next()?;
        let lat = number(it.next()?)?;
        let lon = number(it.next()?)?;
        return is_valid(lat, lon).then_some((lat, lon, None));
    }
    if on("maps.apple.com") {
        return params_place(query, &["ll", "q"]);
    }
    None
}

fn geo_place(url: &str) -> Option<(f64, f64, Option<String>)> {
    let body = url.get(4..)?;
    let (head, query) = body.split_once('?').unwrap_or((body, ""));
    let path = head.split(';').next().unwrap_or("");
    let from_path = pair(path);
    let q = param(query, "q").map(query_value);
    let label = q.as_ref().and_then(|(_, l)| l.clone());
    let (lat, lon) = from_path
        .filter(|&(la, lo)| !(la == 0.0 && lo == 0.0))
        .or(q.and_then(|(p, _)| p))?;
    Some((lat, lon, label))
}

/// End of the URL-ish token that starts at `start`, without trailing
/// punctuation.
fn token_end(text: &str, start: usize) -> usize {
    let rest = &text[start..];
    let mut end = rest
        .find(|c: char| c.is_whitespace() || matches!(c, '<' | '>' | '"' | '[' | ']'))
        .unwrap_or(rest.len());
    loop {
        let t = &rest[..end];
        let Some(last) = t.chars().next_back() else {
            break;
        };
        let strip = matches!(last, '.' | ',' | ';' | ':' | '!' | '?' | '\'')
            || (last == ')' && t.matches('(').count() < t.matches(')').count());
        if !strip {
            break;
        }
        end -= last.len_utf8();
    }
    start + end
}

/// Link tokens (`http(s)://` and `geo:`) with their byte ranges.
fn link_tokens(text: &str) -> Vec<Range<usize>> {
    let lower = text.to_ascii_lowercase();
    let mut out: Vec<Range<usize>> = Vec::new();
    let mut from = 0;
    while from < lower.len() {
        let next = ["https://", "http://", "geo:"]
            .iter()
            .filter_map(|p| lower[from..].find(p).map(|i| i + from))
            .min();
        let Some(start) = next else { break };
        let boundary = lower[..start]
            .chars()
            .next_back()
            .is_none_or(|c| !c.is_alphanumeric());
        if !boundary {
            from = start + 1;
            continue;
        }
        let end = token_end(text, start).max(start + 1);
        out.push(start..end);
        from = end;
    }
    out
}

struct Scan<'a> {
    s: &'a str,
    pos: usize,
}

impl Scan<'_> {
    fn peek(&self) -> Option<char> {
        self.s[self.pos..].chars().next()
    }

    fn eat(&mut self, f: impl Fn(char) -> bool) -> bool {
        match self.peek() {
            Some(c) if f(c) => {
                self.pos += c.len_utf8();
                true
            }
            _ => false,
        }
    }

    fn skip_ws(&mut self) {
        while self.eat(char::is_whitespace) {}
    }

    /// Unsigned decimal; returns the value and the fraction digit count.
    fn unsigned(&mut self) -> Option<(f64, usize)> {
        let start = self.pos;
        while self.eat(|c| c.is_ascii_digit()) {}
        if self.pos == start {
            return None;
        }
        let mut decimals = 0;
        if self.peek() == Some('.') {
            let save = self.pos;
            self.pos += 1;
            let frac = self.pos;
            while self.eat(|c| c.is_ascii_digit()) {}
            decimals = self.pos - frac;
            if decimals == 0 {
                self.pos = save;
            }
        }
        let value = self.s[start..self.pos].parse().ok()?;
        Some((value, decimals))
    }
}

/// Whether a match starting at `at` begins a fresh token.
fn starts_token(text: &str, at: usize) -> bool {
    text[..at]
        .chars()
        .next_back()
        .is_none_or(|c| !(c.is_alphanumeric() || matches!(c, '.' | '_' | '/' | '@' | '#' | '$')))
}

/// Whether a match ending at `end` stops cleanly (not inside `1.2.3`, a
/// longer number or a word).
fn ends_token(text: &str, end: usize) -> bool {
    let mut it = text[end..].chars();
    match it.next() {
        None => true,
        Some(c) if c.is_alphanumeric() || c == '_' => false,
        Some('.') => it.next().is_none_or(|n| !n.is_ascii_digit()),
        Some(_) => true,
    }
}

/// `41.0082,28.9784` style pair with at least three decimals each.
fn decimal_pair(text: &str, at: usize) -> Option<(f64, f64, usize)> {
    let mut sc = Scan { s: text, pos: at };
    let one = |sc: &mut Scan| {
        let neg = sc.eat(|c| c == '-');
        if !neg {
            sc.eat(|c| c == '+');
        }
        let (v, d) = sc.unsigned()?;
        (d >= 3).then_some(if neg { -v } else { v })
    };
    let lat = one(&mut sc)?;
    sc.skip_ws();
    if !sc.eat(|c| c == ',' || c == ';') {
        return None;
    }
    sc.skip_ws();
    let lon = one(&mut sc)?;
    Some((lat, lon, sc.pos))
}

/// One degrees/minutes/seconds part such as `41°00'29.5"N`.
fn dms_part(sc: &mut Scan) -> Option<(f64, char)> {
    let (deg, _) = sc.unsigned()?;
    if !sc.eat(|c| matches!(c, '°' | 'º' | '˚')) {
        return None;
    }
    let mut value = deg;
    sc.skip_ws();
    let save = sc.pos;
    if let Some((min, _)) = sc.unsigned()
        && sc.eat(|c| matches!(c, '\'' | '′' | '’'))
    {
        value += min / 60.0;
        sc.skip_ws();
        let save = sc.pos;
        if let Some((sec, _)) = sc.unsigned()
            && (sc.eat(|c| matches!(c, '"' | '″' | '”'))
                || (sc.eat(|c| matches!(c, '\'' | '′' | '’'))
                    && sc.eat(|c| matches!(c, '\'' | '′' | '’'))))
        {
            value += sec / 3600.0;
        } else {
            sc.pos = save;
        }
    } else {
        sc.pos = save;
    }
    sc.skip_ws();
    let hemi = sc
        .peek()
        .filter(|c| matches!(c, 'N' | 'S' | 'E' | 'W' | 'n' | 's' | 'e' | 'w'))?;
    sc.pos += 1;
    Some((value, hemi.to_ascii_uppercase()))
}

fn dms_pair(text: &str, at: usize) -> Option<(f64, f64, usize)> {
    let mut sc = Scan { s: text, pos: at };
    let a = dms_part(&mut sc)?;
    sc.skip_ws();
    sc.eat(|c| c == ',' || c == ';');
    sc.skip_ws();
    let b = dms_part(&mut sc)?;
    let signed = |(v, h): (f64, char)| if matches!(h, 'S' | 'W') { -v } else { v };
    let (lat, lon) = match (a.1, b.1) {
        ('N' | 'S', 'E' | 'W') => (signed(a), signed(b)),
        ('E' | 'W', 'N' | 'S') => (signed(b), signed(a)),
        _ => return None,
    };
    ends_token(text, sc.pos).then_some((lat, lon, sc.pos))
}

fn key(lat: f64, lon: f64) -> (i64, i64) {
    // Rounded to 5 decimals (about a metre); the values are range-checked.
    ((lat * 1e5).round() as i64, (lon * 1e5).round() as i64)
}

/// Coordinates written in `text`, in order of appearance, at most `max`.
///
/// Understands `geo:` URIs, Google Maps (`@lat,lon`, `q=`, `query=`, `ll=`),
/// OpenStreetMap (`mlat`/`mlon`, `#map=z/lat/lon`) and Apple Maps (`ll=`,
/// `q=`) links, bare decimal pairs with at least three decimals each and
/// degrees/minutes/seconds with N/S/E/W. Short links (`goo.gl/maps`,
/// `maps.app.goo.gl`) are not resolved. Every result passes [`is_valid`] and
/// places within 5 decimals of an earlier one are dropped.
pub fn find_places(text: &str, max: usize) -> Vec<Place> {
    if max == 0 {
        return Vec::new();
    }
    let links = link_tokens(text);
    let mut found: Vec<Place> = Vec::new();
    for span in &links {
        let url = &text[span.clone()];
        let got = if url.len() >= 4 && url[..4].eq_ignore_ascii_case("geo:") {
            geo_place(url)
        } else {
            http_place(url)
        };
        if let Some((lat, lon, label)) = got
            && is_valid(lat, lon)
        {
            found.push(Place {
                lat,
                lon,
                label,
                span: span.clone(),
            });
        }
    }
    let mut i = 0;
    while i < text.len() {
        if let Some(l) = links.iter().find(|l| l.contains(&i)) {
            i = l.end;
            continue;
        }
        let Some(c) = text[i..].chars().next() else {
            break;
        };
        let step = c.len_utf8();
        if (c.is_ascii_digit() || c == '-' || c == '+') && starts_token(text, i) {
            let hit = dms_pair(text, i)
                .or_else(|| decimal_pair(text, i).filter(|&(_, _, end)| ends_token(text, end)));
            if let Some((lat, lon, end)) = hit
                && is_valid(lat, lon)
            {
                found.push(Place {
                    lat,
                    lon,
                    label: None,
                    span: i..end,
                });
                i = end;
                continue;
            }
        }
        i += step;
    }
    found.sort_by_key(|p| p.span.start);
    let mut seen = HashSet::new();
    found.retain(|p| seen.insert(key(p.lat, p.lon)));
    found.truncate(max);
    found
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn url_format() {
        assert_eq!(
            osm_url(41.0082, 28.9784),
            "https://www.openstreetmap.org/?mlat=41.008200&mlon=28.978400#map=17/41.008200/28.978400"
        );
    }

    #[test]
    fn url_clamps_and_wraps() {
        assert!(osm_url(95.0, 0.5).contains("mlat=90.000000"));
        assert!(osm_url(-120.0, 0.5).contains("mlat=-90.000000"));
        assert!(osm_url(10.0, 190.0).contains("mlon=-170.000000"));
        assert!(osm_url(10.0, -190.0).contains("mlon=170.000000"));
        assert!(osm_url(10.0, 540.0).contains("mlon=180.000000"));
        assert!(osm_url(10.0, 180.0).contains("mlon=180.000000"));
        assert!(osm_url(f64::NAN, f64::INFINITY).contains("mlat=0.000000&mlon=0.000000"));
    }

    #[test]
    fn share_skips_blanks() {
        let url = osm_url(1.0, 2.0);
        assert_eq!(share_text(None, None, 1.0, 2.0), url);
        assert_eq!(share_text(Some("  "), Some(""), 1.0, 2.0), url);
        assert_eq!(
            share_text(Some(" Cafe "), Some("Main St 1"), 1.0, 2.0),
            format!("Cafe\nMain St 1\n{url}")
        );
        assert_eq!(
            share_text(None, Some("Main St"), 1.0, 2.0),
            format!("Main St\n{url}")
        );
    }

    #[test]
    fn validity() {
        assert!(is_valid(41.0, 29.0));
        assert!(is_valid(90.0, -180.0));
        assert!(!is_valid(0.0, 0.0));
        assert!(!is_valid(90.1, 0.0));
        assert!(!is_valid(0.0, 180.1));
        assert!(!is_valid(f64::NAN, 1.0));
        assert!(!is_valid(1.0, f64::INFINITY));
    }

    fn one(text: &str) -> Place {
        let mut v = find_places(text, 10);
        assert_eq!(v.len(), 1, "{text}: {v:?}");
        v.remove(0)
    }

    fn at(text: &str, lat: f64, lon: f64) {
        let p = one(text);
        assert!(
            (p.lat - lat).abs() < 1e-6 && (p.lon - lon).abs() < 1e-6,
            "{text}: {p:?}"
        );
    }

    #[test]
    fn geo_url_encodes_the_name() {
        assert_eq!(
            geo_url(41.0082, 28.9784, Some("Ayasofya Müzesi")),
            "geo:41.008200,28.978400?q=41.008200,28.978400(Ayasofya%20M%C3%BCzesi)"
        );
        assert_eq!(
            geo_url(41.0082, 28.9784, Some("  ")),
            "geo:41.008200,28.978400?q=41.008200,28.978400"
        );
        assert!(!geo_url(41.0082, 28.9784, None).contains('('));
    }

    #[test]
    fn coords_format() {
        assert_eq!(coords_text(41.0082, 28.9784), "41.008200, 28.978400");
        assert_eq!(coords_text(-1.5, -2.25), "-1.500000, -2.250000");
    }

    #[test]
    fn geo_uris() {
        at("see geo:41.0082,28.9784 ok", 41.0082, 28.9784);
        at("geo:41.0082,28.9784,12;u=30", 41.0082, 28.9784);
        let p = one("GEO:0,0?q=41.5,29.5(Caf%C3%A9%20Nero)");
        assert_eq!((p.lat, p.lon), (41.5, 29.5));
        assert_eq!(p.label.as_deref(), Some("Café Nero"));
        let p = one(&geo_url(41.0082, 28.9784, Some("Ayasofya Müzesi")));
        assert_eq!(p.label.as_deref(), Some("Ayasofya Müzesi"));
        assert!(find_places("geo:0,0", 5).is_empty());
        assert!(find_places("geo:0,0?q=Cafe", 5).is_empty());
    }

    #[test]
    fn google_maps() {
        let t = "https://www.google.com/maps/@41.0082,28.9784,15z";
        let p = one(t);
        assert_eq!(p.span, 0..t.len());
        at(
            "https://www.google.com/maps?q=41.0082,28.9784",
            41.0082,
            28.9784,
        );
        at(
            "https://maps.google.com/?q=loc:41.0082,28.9784",
            41.0082,
            28.9784,
        );
        at(
            "https://www.google.com/maps/search/?api=1&query=41.0082%2C28.9784",
            41.0082,
            28.9784,
        );
        at(
            "https://maps.google.com/maps?ll=41.0082,28.9784&z=12",
            41.0082,
            28.9784,
        );
        let p = one("https://www.google.com/maps/place/Hagia+Sophia/@41.0082,28.9784,17z/data=x");
        assert_eq!(p.label.as_deref(), Some("Hagia Sophia"));
        let p = one("https://maps.google.com/?q=41.0082,28.9784(Home)");
        assert_eq!(p.label.as_deref(), Some("Home"));
        // The address-only query has no coordinates.
        assert!(find_places("https://www.google.com/maps?q=Taksim+Square", 5).is_empty());
    }

    #[test]
    fn short_links_are_not_resolved() {
        assert!(find_places("https://maps.app.goo.gl/AbCd1234", 5).is_empty());
        assert!(find_places("https://goo.gl/maps/41.0082,28.9784", 5).is_empty());
    }

    #[test]
    fn openstreetmap() {
        at(
            "https://www.openstreetmap.org/?mlat=41.0082&mlon=28.9784#map=17/41.0082/28.9784",
            41.0082,
            28.9784,
        );
        at(
            "https://www.openstreetmap.org/#map=12/48.8566/2.3522&layers=N",
            48.8566,
            2.3522,
        );
        at(&osm_url(41.0082, 28.9784), 41.0082, 28.9784);
        assert!(find_places("https://www.openstreetmap.org/#map=12/95.0/2.0", 5).is_empty());
    }

    #[test]
    fn apple_maps() {
        at(
            "https://maps.apple.com/?ll=41.0082,28.9784&z=10",
            41.0082,
            28.9784,
        );
        at(
            "https://maps.apple.com/?q=41.0082,28.9784",
            41.0082,
            28.9784,
        );
        let p = one("https://maps.apple.com/?q=Home&ll=41.0082,28.9784");
        assert_eq!(p.label.as_deref(), Some("Home"));
    }

    #[test]
    fn bare_pairs() {
        let p = one("meet at 41.0082, 28.9784.");
        assert_eq!(p.span, 8..24);
        at("-33.8688,151.2093", -33.8688, 151.2093);
        at("41.008200;28.978400 bye", 41.0082, 28.9784);
        at("(41.0082, -28.9784)", 41.0082, -28.9784);
    }

    #[test]
    fn dms() {
        at(
            "41°00'29.5\"N 28°58'42.2\"E",
            41.0 + 29.5 / 3600.0,
            28.0 + 58.0 / 60.0 + 42.2 / 3600.0,
        );
        at(
            "33°52'S, 151°12'E",
            -(33.0 + 52.0 / 60.0),
            151.0 + 12.0 / 60.0,
        );
        at("41.0082°N 28.9784°E", 41.0082, 28.9784);
        at("28.9784° E 41.0082° N", 41.0082, 28.9784);
        at("40°N 75°W", 40.0, -75.0);
        assert!(find_places("40°N 75°N", 5).is_empty());
        assert!(find_places("95°N 10°E", 5).is_empty());
    }

    #[test]
    fn rejects_non_places() {
        for t in [
            "0.000, 0.000",
            "91.0000, 10.0000",
            "10.0000, 181.0000",
            "call +90 532 123 4567",
            "0532.123.4567",
            "total 1.250,00 TL",
            "price 12.50, 14.75",
            "at 12.30, 14.45",
            "meet 12.30 14.45",
            "version 1.2.3, 4.5.6",
            "1.234 5.678",
            "41.0082",
            "id a41.0082,28.9784",
            "https://example.com/41.0082,28.9784",
            "40°N",
        ] {
            assert!(find_places(t, 5).is_empty(), "{t}: {:?}", find_places(t, 5));
        }
    }

    #[test]
    fn dedup_max_and_order() {
        let t =
            "41.00820, 28.97840 and geo:41.0082,28.9784 and 48.8566, 2.3522 and 35.6762, 139.6503";
        let all = find_places(t, 10);
        assert_eq!(all.len(), 3);
        assert_eq!(all[0].span.start, 0);
        assert!(all.windows(2).all(|w| w[0].span.end <= w[1].span.start));
        assert_eq!(find_places(t, 2).len(), 2);
        assert!(find_places(t, 0).is_empty());
        // Differs at the 5th decimal, so it is another place.
        assert_eq!(
            find_places("41.00820, 28.97840 41.00830, 28.97840", 5).len(),
            2
        );
        assert!(find_places(t, 10).iter().all(|p| is_valid(p.lat, p.lon)));
    }

    #[test]
    fn link_and_text_mix() {
        let t = "https://www.openstreetmap.org/?mlat=41.5&mlon=29.5 then 48.8566, 2.3522";
        let v = find_places(t, 5);
        assert_eq!(v.len(), 2);
        assert_eq!((v[1].lat, v[1].lon), (48.8566, 2.3522));
    }
}
