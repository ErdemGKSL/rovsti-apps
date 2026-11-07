//! Export and import of saved stops: our JSON file, GPX 1.1, and a tolerant GPX
//! reader for files written by other apps.

use rovsti_core::location_link::is_valid;

use crate::saved_stops::{DUPLICATE_GATE_M, MAX_NAME_CHARS, MAX_STOPS, SavedStop, SavedStops};
use crate::{Coord, Diameter};

/// Largest import text accepted, in bytes.
pub const MAX_IMPORT_BYTES: usize = 2 * 1024 * 1024;
const DEFAULT_NAME: &str = "Imported stop";

/// One stop read from a file, not yet checked against the saved list.
#[derive(Clone, Debug, PartialEq)]
pub struct Incoming {
    pub name: String,
    pub a: Coord,
    pub b: Coord,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ImportError {
    /// The file has no text.
    Empty,
    /// Neither our JSON nor GPX.
    NotRecognised,
    /// Over [`MAX_IMPORT_BYTES`].
    TooLarge,
}

impl ImportError {
    pub fn message(self) -> &'static str {
        match self {
            Self::Empty => "That file is empty.",
            Self::NotRecognised => "That file is not a GPX or Rovsti stops file.",
            Self::TooLarge => "That file is too large (2 MB at most).",
        }
    }
}

impl std::fmt::Display for ImportError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for ImportError {}

/// The v2 saved-stops file, pretty printed.
pub fn to_json(stops: &SavedStops) -> String {
    serde_json::to_string_pretty(stops).unwrap_or_default()
}

pub(crate) fn escape(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            c if c.is_control() && !matches!(c, '\t' | '\n' | '\r') => {}
            c => out.push(c),
        }
    }
    out
}

fn unescape(s: &str) -> String {
    if !s.contains('&') {
        return s.to_owned();
    }
    s.replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

/// GPX 1.1 with one route per stop; points A and B are its route points.
pub fn to_gpx(stops: &SavedStops) -> String {
    let mut out = String::from(
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n\
         <gpx version=\"1.1\" creator=\"Rovsti Stops\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n",
    );
    for s in &stops.stops {
        out.push_str("  <rte>\n");
        out.push_str(&format!("    <name>{}</name>\n", escape(&s.name)));
        for (tag, c) in [("A", s.a.coord), ("B", s.b.coord)] {
            out.push_str(&format!(
                "    <rtept lat=\"{}\" lon=\"{}\"><name>{tag}</name></rtept>\n",
                c.lat, c.lon
            ));
        }
        out.push_str("  </rte>\n");
    }
    out.push_str("</gpx>\n");
    out
}

fn clean_name(raw: &str) -> String {
    let name: String = raw.trim().chars().take(MAX_NAME_CHARS).collect();
    let name = name.trim();
    if name.is_empty() {
        DEFAULT_NAME.to_owned()
    } else {
        name.to_owned()
    }
}

/// Reads an import file: our JSON (v2 file or a bare array of stops) or GPX.
pub fn parse_import(text: &str) -> Result<Vec<Incoming>, ImportError> {
    if text.len() > MAX_IMPORT_BYTES {
        return Err(ImportError::TooLarge);
    }
    let text = text.trim_matches(|c: char| c.is_whitespace() || c == '\u{feff}');
    if text.is_empty() {
        return Err(ImportError::Empty);
    }
    if text.starts_with('{') || text.starts_with('[') {
        return parse_json(text);
    }
    parse_gpx(text)
}

fn parse_json(text: &str) -> Result<Vec<Incoming>, ImportError> {
    let stops: Vec<SavedStop> = if let Ok(file) = serde_json::from_str::<SavedStops>(text) {
        if file.version != crate::saved_stops::FORMAT_VERSION {
            return Err(ImportError::NotRecognised);
        }
        file.stops
    } else if let Ok(list) = serde_json::from_str::<Vec<SavedStop>>(text) {
        list
    } else {
        return Err(ImportError::NotRecognised);
    };
    Ok(stops
        .into_iter()
        .filter(|s| ok(s.a.coord) && ok(s.b.coord))
        .map(|s| Incoming {
            name: clean_name(&s.name),
            a: s.a.coord,
            b: s.b.coord,
        })
        .collect())
}

fn ok(c: Coord) -> bool {
    is_valid(c.lat, c.lon)
}

#[derive(Debug)]
enum Token<'a> {
    Open {
        name: &'a str,
        attrs: &'a str,
        empty: bool,
    },
    Close(&'a str),
    Text(&'a str),
}

fn tokens(text: &str) -> Vec<Token<'_>> {
    let mut out = Vec::new();
    let mut rest = text;
    while !rest.is_empty() {
        let Some(lt) = rest.find('<') else {
            out.push(Token::Text(rest));
            break;
        };
        if lt > 0 {
            out.push(Token::Text(&rest[..lt]));
        }
        rest = &rest[lt..];
        if let Some(r) = rest.strip_prefix("<!--") {
            rest = r.find("-->").map_or("", |i| &r[i + 3..]);
            continue;
        }
        if let Some(r) = rest.strip_prefix("<![CDATA[") {
            let end = r.find("]]>").unwrap_or(r.len());
            // Keep CDATA as text; it is not entity-decoded, which is close enough.
            out.push(Token::Text(&r[..end]));
            rest = r.get(end + 3..).unwrap_or("");
            continue;
        }
        let Some(gt) = find_tag_end(rest) else {
            break;
        };
        let inner = &rest[1..gt];
        rest = &rest[gt + 1..];
        if inner.starts_with('?') || inner.starts_with('!') {
            continue;
        }
        if let Some(name) = inner.strip_prefix('/') {
            out.push(Token::Close(local(name.trim())));
            continue;
        }
        let empty = inner.ends_with('/');
        let inner = inner.trim_end_matches('/');
        let split = inner
            .find(|c: char| c.is_whitespace())
            .unwrap_or(inner.len());
        out.push(Token::Open {
            name: local(&inner[..split]),
            attrs: &inner[split..],
            empty,
        });
    }
    out
}

/// Index of the `>` closing the tag at the start of `s`, skipping quoted values.
fn find_tag_end(s: &str) -> Option<usize> {
    let mut quote: Option<char> = None;
    for (i, c) in s.char_indices() {
        match (quote, c) {
            (Some(q), c) if c == q => quote = None,
            (Some(_), _) => {}
            (None, '"' | '\'') => quote = Some(c),
            (None, '>') => return Some(i),
            _ => {}
        }
    }
    None
}

/// Element name without a namespace prefix.
fn local(name: &str) -> &str {
    name.rsplit(':').next().unwrap_or(name)
}

/// Value of attribute `key`, with either quote style.
fn attr(attrs: &str, key: &str) -> Option<String> {
    let mut rest = attrs;
    while let Some(eq) = rest.find('=') {
        let name = rest[..eq].trim().rsplit(char::is_whitespace).next()?;
        let after = rest[eq + 1..].trim_start();
        let q = after.chars().next().filter(|c| matches!(c, '"' | '\''))?;
        let body = &after[1..];
        let end = body.find(q)?;
        if local(name) == key {
            return Some(unescape(&body[..end]));
        }
        rest = &body[end + 1..];
    }
    None
}

fn point(attrs: &str) -> Option<Coord> {
    let lat: f64 = attr(attrs, "lat")?.trim().parse().ok()?;
    let lon: f64 = attr(attrs, "lon")?.trim().parse().ok()?;
    is_valid(lat, lon).then(|| Coord::new(lat, lon))
}

#[derive(Default)]
struct Group {
    name: String,
    pts: Vec<Coord>,
}

fn parse_gpx(text: &str) -> Result<Vec<Incoming>, ImportError> {
    let mut seen_gpx = false;
    let mut out = Vec::new();
    let mut wpts: Vec<(String, Coord)> = Vec::new();
    let mut stack: Vec<&str> = Vec::new();
    let mut group: Option<Group> = None;
    // Name of the wpt being read.
    let mut wpt: Option<(String, Option<Coord>)> = None;

    for tok in tokens(text) {
        match tok {
            Token::Open { name, attrs, empty } => {
                match name {
                    "gpx" => seen_gpx = true,
                    "rte" | "trk" if group.is_none() => group = Some(Group::default()),
                    "rtept" | "trkpt" => {
                        if let (Some(g), Some(c)) = (group.as_mut(), point(attrs)) {
                            g.pts.push(c);
                        }
                    }
                    "wpt" if group.is_none() => wpt = Some((String::new(), point(attrs))),
                    _ => {}
                }
                if empty {
                    if name == "wpt"
                        && let Some((n, Some(c))) = wpt.take()
                    {
                        wpts.push((n, c));
                    }
                } else {
                    stack.push(name);
                }
            }
            Token::Close(name) => {
                if stack.last() == Some(&name) {
                    stack.pop();
                }
                match name {
                    "rte" | "trk" => {
                        if let Some(g) = group.take()
                            && g.pts.len() >= 2
                            && let (Some(&a), Some(&b)) = (g.pts.first(), g.pts.last())
                        {
                            out.push(Incoming {
                                name: clean_name(&g.name),
                                a,
                                b,
                            });
                        }
                    }
                    "wpt" => {
                        if let Some((n, Some(c))) = wpt.take() {
                            wpts.push((n, c));
                        }
                    }
                    _ => {}
                }
            }
            Token::Text(t) => {
                let n = stack.len();
                if n >= 2 && stack[n - 1] == "name" {
                    let parent = stack[n - 2];
                    if matches!(parent, "rte" | "trk")
                        && let Some(g) = group.as_mut()
                        && g.name.is_empty()
                    {
                        g.name = unescape(t.trim());
                    } else if parent == "wpt"
                        && let Some((name, _)) = wpt.as_mut()
                    {
                        *name = unescape(t.trim());
                    }
                }
            }
        }
    }
    if !seen_gpx {
        return Err(ImportError::NotRecognised);
    }
    for pair in wpts.as_chunks::<2>().0 {
        let (na, a) = (&pair[0].0, pair[0].1);
        let (nb, b) = (&pair[1].0, pair[1].1);
        out.push(Incoming {
            name: clean_name(&pair_name(na, nb)),
            a,
            b,
        });
    }
    Ok(out)
}

/// Stop name from two waypoint names: "Pier A" + "Pier B" is "Pier"; otherwise
/// the first name.
fn pair_name(a: &str, b: &str) -> String {
    let strip = |s: &str, c: char| -> Option<String> {
        let t = s.trim().strip_suffix(c)?;
        Some(t.trim_end_matches([' ', '-', '_', ':']).to_owned())
    };
    match (strip(a, 'A'), strip(b, 'B')) {
        (Some(x), _) if !x.is_empty() => x,
        (Some(_), Some(y)) if !y.is_empty() => y,
        _ => a.to_owned(),
    }
}

/// What importing would do, computed before anything is added.
#[derive(Clone, Debug, PartialEq, Default)]
pub struct MergePlan {
    pub add: Vec<Incoming>,
    /// Same gate as a saved stop, or repeated in the file.
    pub duplicates: usize,
    /// Points that cannot form a stop.
    pub invalid: usize,
    /// Dropped because the list is full.
    pub over_cap: usize,
}

fn near_gate(x: &Incoming, a: Coord, b: Coord) -> bool {
    let near = |p: Coord, q: Coord| p.distance_m(q) <= DUPLICATE_GATE_M;
    (near(x.a, a) && near(x.b, b)) || (near(x.a, b) && near(x.b, a))
}

pub fn merge_plan(existing: &SavedStops, incoming: Vec<Incoming>) -> MergePlan {
    let mut plan = MergePlan::default();
    for item in incoming {
        if Diameter::new(item.a, item.b).is_err() {
            plan.invalid += 1;
        } else if existing.find_gate(item.a, item.b).is_some()
            || plan.add.iter().any(|x| near_gate(x, item.a, item.b))
        {
            plan.duplicates += 1;
        } else if existing.stops.len() + plan.add.len() >= MAX_STOPS {
            plan.over_cap += 1;
        } else {
            plan.add.push(item);
        }
    }
    plan
}

fn stops_word(n: usize) -> String {
    if n == 1 {
        "1 stop".to_owned()
    } else {
        format!("{n} stops")
    }
}

/// One-line description for the confirm sheet.
pub fn summary(plan: &MergePlan) -> String {
    let mut out = if plan.add.is_empty() {
        "Nothing new to add.".to_owned()
    } else {
        format!("Add {}.", stops_word(plan.add.len()))
    };
    if plan.duplicates > 0 {
        out.push_str(&format!(" {} already saved.", plan.duplicates));
    }
    if plan.invalid > 0 {
        out.push_str(&format!(" {} not usable.", plan.invalid));
    }
    if plan.over_cap > 0 {
        out.push_str(&format!(
            " {} over the {MAX_STOPS} stop limit.",
            plan.over_cap
        ));
    }
    out
}

/// `rovsti-stops-2026-11-07.gpx` for `("gpx", "2026-11-07")`.
pub fn export_file_name(ext: &str, today: &str) -> String {
    format!("rovsti-stops-{today}.{}", ext.trim_start_matches('.'))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::Endpoint;

    fn ep(lat: f64, lon: f64) -> Endpoint {
        Endpoint {
            label: "Point".into(),
            coord: Coord::new(lat, lon),
        }
    }

    fn sample() -> SavedStops {
        let mut s = SavedStops::default();
        s.add("Tom & Jerry <pier>", ep(41.0, 29.0), ep(41.001, 29.0), 1)
            .unwrap();
        s.add(
            "Kadıköy \"İskele\" 'x'",
            ep(41.1, 29.1),
            ep(41.102, 29.1),
            2,
        )
        .unwrap();
        s.add("東京駅", ep(35.68, 139.76), ep(35.682, 139.76), 3)
            .unwrap();
        s
    }

    fn check(got: &[Incoming], s: &SavedStops) {
        assert_eq!(got.len(), s.stops.len());
        for (g, st) in got.iter().zip(&s.stops) {
            assert_eq!(g.name, st.name);
            assert_eq!(g.a, st.a.coord);
            assert_eq!(g.b, st.b.coord);
        }
    }

    #[test]
    fn json_round_trip() {
        let s = sample();
        check(&parse_import(&to_json(&s)).unwrap(), &s);
    }

    #[test]
    fn gpx_round_trip() {
        let s = sample();
        let gpx = to_gpx(&s);
        assert!(gpx.contains("Tom &amp; Jerry &lt;pier&gt;"));
        check(&parse_import(&gpx).unwrap(), &s);
    }

    #[test]
    fn bare_array_is_accepted() {
        let s = sample();
        let text = serde_json::to_string(&s.stops).unwrap();
        check(&parse_import(&text).unwrap(), &s);
    }

    #[test]
    fn wrong_json_version_is_rejected() {
        let text = r#"{"version":1,"next_id":1,"stops":[]}"#;
        assert_eq!(parse_import(text), Err(ImportError::NotRecognised));
    }

    #[test]
    fn foreign_gpx_with_track() {
        let text = r#"<?xml version='1.0'?>
<gpx version='1.0' xmlns:x='urn:x'><!-- hi -->
<trk><name>Ferry &amp; back</name><trkseg>
<trkpt lat='41.0' lon='29.0'><ele>1</ele></trkpt>
<trkpt lat="41.0005" lon="29.0"/>
<trkpt lat="41.002" lon="29.0"></trkpt>
</trkseg></trk></gpx>"#;
        let got = parse_import(text).unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].name, "Ferry & back");
        assert_eq!(got[0].a, Coord::new(41.0, 29.0));
        assert_eq!(got[0].b, Coord::new(41.002, 29.0));
    }

    #[test]
    fn foreign_gpx_with_waypoint_pairs() {
        let text = r#"<gpx>
<wpt lat="41.0" lon="29.0"><name>Pier A</name></wpt>
<wpt lat="41.001" lon="29.0"><name>Pier B</name></wpt>
<wpt lat="40.0" lon="28.0"><name>Home</name></wpt>
<wpt lat="40.002" lon="28.0"/>
<wpt lat="99" lon="28.0"><name>Bad</name></wpt>
<wpt lat="10" lon="10"><name>Odd</name></wpt>
</gpx>"#;
        let got = parse_import(text).unwrap();
        // The empty wpt with no name counts; the invalid one is skipped.
        assert_eq!(got.len(), 2);
        assert_eq!(got[0].name, "Pier");
        assert_eq!(got[1].name, "Home");
    }

    #[test]
    fn invalid_coords_skipped_in_routes() {
        let text = r#"<gpx><rte><name>R</name>
<rtept lat="0" lon="0"/><rtept lat="41" lon="29"/><rtept lat="41.001" lon="29"/>
<rtept lat="abc" lon="29"/></rte><rte><rtept lat="41" lon="29"/></rte></gpx>"#;
        let got = parse_import(text).unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].a, Coord::new(41.0, 29.0));
    }

    #[test]
    fn garbage_empty_and_large() {
        assert_eq!(parse_import(""), Err(ImportError::Empty));
        assert_eq!(parse_import("  \n\u{feff}"), Err(ImportError::Empty));
        for t in ["hello", "{\"a\":1}", "[1,2]", "<html></html>", "<gpx"] {
            let r = parse_import(t);
            assert!(
                matches!(r, Err(ImportError::NotRecognised)) || r == Ok(Vec::new()),
                "{t}"
            );
        }
        assert_eq!(parse_import("hello"), Err(ImportError::NotRecognised));
        let big = "x".repeat(MAX_IMPORT_BYTES + 1);
        assert_eq!(parse_import(&big), Err(ImportError::TooLarge));
        assert!(!ImportError::Empty.message().is_empty());
    }

    fn inc(name: &str, a: (f64, f64), b: (f64, f64)) -> Incoming {
        Incoming {
            name: name.into(),
            a: Coord::new(a.0, a.1),
            b: Coord::new(b.0, b.1),
        }
    }

    #[test]
    fn merge_rules() {
        let mut existing = SavedStops::default();
        existing
            .add("Home", ep(41.0, 29.0), ep(41.001, 29.0), 0)
            .unwrap();
        let plan = merge_plan(
            &existing,
            vec![
                inc("same", (41.0, 29.0), (41.001, 29.0)),
                inc("swapped", (41.001, 29.0), (41.0, 29.0)),
                inc("new", (42.0, 29.0), (42.001, 29.0)),
                inc("repeat", (42.001, 29.0), (42.0, 29.0)),
                inc("short", (43.0, 29.0), (43.00001, 29.0)),
            ],
        );
        assert_eq!(plan.add.len(), 1);
        assert_eq!(plan.add[0].name, "new");
        assert_eq!(plan.duplicates, 3);
        assert_eq!(plan.invalid, 1);
        assert_eq!(plan.over_cap, 0);
        assert_eq!(summary(&plan), "Add 1 stop. 3 already saved. 1 not usable.");
    }

    #[test]
    fn merge_respects_cap() {
        let mut existing = SavedStops::default();
        for i in 0..MAX_STOPS - 1 {
            let n = i as f64 * 0.01;
            existing
                .add("s", ep(10.0 + n, 29.0), ep(10.001 + n, 29.0), 0)
                .unwrap();
        }
        let plan = merge_plan(
            &existing,
            vec![
                inc("a", (60.0, 29.0), (60.001, 29.0)),
                inc("b", (61.0, 29.0), (61.001, 29.0)),
                inc("c", (62.0, 29.0), (62.001, 29.0)),
            ],
        );
        assert_eq!(plan.add.len(), 1);
        assert_eq!(plan.over_cap, 2);
        assert_eq!(summary(&plan), "Add 1 stop. 2 over the 200 stop limit.");
    }

    #[test]
    fn summaries() {
        assert_eq!(summary(&MergePlan::default()), "Nothing new to add.");
        let two = MergePlan {
            add: vec![inc("a", (1.0, 1.0), (1.0, 1.01)); 3],
            duplicates: 1,
            ..MergePlan::default()
        };
        assert_eq!(summary(&two), "Add 3 stops. 1 already saved.");
        let none = MergePlan {
            duplicates: 2,
            ..MergePlan::default()
        };
        assert_eq!(summary(&none), "Nothing new to add. 2 already saved.");
    }

    #[test]
    fn file_names() {
        assert_eq!(
            export_file_name("gpx", "2026-11-07"),
            "rovsti-stops-2026-11-07.gpx"
        );
        assert_eq!(
            export_file_name(".json", "2026-11-07"),
            "rovsti-stops-2026-11-07.json"
        );
    }

    #[test]
    fn long_names_are_trimmed() {
        let text = format!(
            "<gpx><rte><name>{}</name><rtept lat=\"41\" lon=\"29\"/><rtept lat=\"41.001\" lon=\"29\"/></rte></gpx>",
            "x".repeat(100)
        );
        assert_eq!(
            parse_import(&text).unwrap()[0].name.chars().count(),
            MAX_NAME_CHARS
        );
    }
}
