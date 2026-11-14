//! Compact, platform-free GTFS model built from the standard CSV files.
//!
//! Only what an arrival alarm needs is kept: stops, routes and *patterns*
//! (the distinct stop sequences of a route and headsign). `stop_times.txt`
//! is streamed and never held in memory as a whole.

use std::collections::{HashMap, HashSet};
use std::io::{self, BufRead, BufReader, Read, Seek};
use std::sync::OnceLock;

use serde::{Deserialize, Serialize};

use crate::Coord;
use crate::csv::CsvReader;

pub const MAX_STOPS: usize = 100_000;
pub const MAX_ROUTES: usize = 50_000;
pub const MAX_TRIPS: usize = 2_000_000;
pub const MAX_PATTERNS: usize = 20_000;
pub const MAX_STOP_TIMES: u64 = 8_000_000;

#[derive(Debug, thiserror::Error)]
pub enum GtfsError {
    /// A required file (`stops.txt`) or column (`stops.txt: stop_id`).
    #[error("missing {0}")]
    Missing(String),
    /// A cap was exceeded; the payload names what.
    #[error("feed too large: {0}")]
    TooLarge(&'static str),
    #[error("not a readable zip: {0}")]
    Zip(String),
    #[error(transparent)]
    Io(#[from] io::Error),
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Stop {
    pub id: String,
    pub name: String,
    pub coord: Coord,
    /// Index of the parent station in [`Feed::stops`].
    pub parent: Option<u32>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Route {
    pub id: String,
    pub short_name: String,
    pub long_name: String,
    /// GTFS `route_type` (basic 0..=12 or extended 100..=1700).
    pub kind: u16,
}

/// A distinct stop sequence served by a route under one headsign.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Pattern {
    /// Index into [`Feed::routes`].
    pub route: u32,
    pub headsign: String,
    /// Indices into [`Feed::stops`], in travel order.
    pub stops: Vec<u32>,
}

/// What an import saw, for showing the user and for diagnosing odd feeds.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FeedReport {
    pub stops: usize,
    pub routes: usize,
    pub trips: usize,
    pub stop_times: u64,
    pub patterns: usize,
    /// Trips whose rows were not contiguous in `stop_times.txt`; each extra
    /// run was handled as its own partial trip.
    pub ungrouped_trips: usize,
    /// Rows dropped for bad coordinates, duplicate ids, unknown references or
    /// unparsable sequence numbers.
    pub skipped_rows: u64,
}

#[derive(Clone, Debug, Default, Serialize, Deserialize)]
pub struct Feed {
    pub stops: Vec<Stop>,
    pub routes: Vec<Route>,
    pub patterns: Vec<Pattern>,
    /// Unix milliseconds when the feed was imported.
    pub imported_ms: i64,
    #[serde(skip)]
    index: OnceLock<Index>,
}

#[derive(Clone, Debug, Default)]
struct Index {
    folded: Vec<String>,
    stop_patterns: Vec<Vec<u32>>,
}

impl Feed {
    pub fn new(
        stops: Vec<Stop>,
        routes: Vec<Route>,
        patterns: Vec<Pattern>,
        imported_ms: i64,
    ) -> Self {
        Self {
            stops,
            routes,
            patterns,
            imported_ms,
            index: OnceLock::new(),
        }
    }

    fn index(&self) -> &Index {
        self.index.get_or_init(|| {
            let mut stop_patterns = vec![Vec::new(); self.stops.len()];
            for (pi, p) in self.patterns.iter().enumerate() {
                for &s in &p.stops {
                    if let Some(list) = stop_patterns.get_mut(s as usize)
                        && list.last() != Some(&(pi as u32))
                    {
                        list.push(pi as u32);
                    }
                }
            }
            Index {
                folded: self.stops.iter().map(|s| fold(&s.name)).collect(),
                stop_patterns,
            }
        })
    }

    /// Stops whose name contains every word of `query` (case, accent and
    /// Turkish dotted/dotless-i insensitive). Exact names rank first, then
    /// prefixes, then the rest; ties go to the stop nearest `near`, else to
    /// feed order.
    pub fn search(&self, query: &str, near: Option<Coord>, limit: usize) -> Vec<u32> {
        let q = fold(query);
        let words: Vec<&str> = q.split_whitespace().collect();
        if words.is_empty() || limit == 0 {
            return Vec::new();
        }
        let folded = &self.index().folded;
        let mut hits: Vec<(u8, f64, u32)> = Vec::new();
        for (i, name) in folded.iter().enumerate() {
            if !words.iter().all(|w| name.contains(w)) {
                continue;
            }
            let class = if *name == q {
                0
            } else if name.starts_with(&q) {
                1
            } else {
                2
            };
            let d = near.map_or(0.0, |c| c.distance_m(self.stops[i].coord));
            hits.push((class, d, i as u32));
        }
        hits.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.total_cmp(&b.1)).then(a.2.cmp(&b.2)));
        hits.truncate(limit);
        hits.into_iter().map(|h| h.2).collect()
    }

    /// Patterns (indices) that call at `stop`.
    pub fn patterns_through(&self, stop: u32) -> Vec<u32> {
        self.index()
            .stop_patterns
            .get(stop as usize)
            .cloned()
            .unwrap_or_default()
    }

    /// Indices of the stops inside the box `south..=north` by `west..=east`
    /// (degrees), at most `cap`, in ascending order. When more are inside,
    /// those nearest the middle of the box win, ties going to the lower
    /// index, so the same view always answers the same stops.
    ///
    /// `west > east` is a box across the antimeridian (from `west` east to
    /// 180 and on from -180 to `east`). A box 360 degrees wide or more takes
    /// every longitude. `south > north` is read the other way round. A box
    /// with a NaN or infinite edge holds nothing.
    pub fn stops_in_box(&self, bbox: BBox, cap: usize) -> Vec<u32> {
        let (hits, _) = self.box_hits(bbox, cap);
        hits
    }

    /// [`Feed::stops_in_box`] with how many stops the box holds before the cap.
    pub fn box_hits(&self, bbox: BBox, cap: usize) -> (Vec<u32>, usize) {
        let Some(bbox) = bbox.normalised() else {
            return (Vec::new(), 0);
        };
        let centre = bbox.centre();
        let lon_scale = centre.lat.to_radians().cos().max(1e-6);
        let mut hits: Vec<(f64, u32)> = self
            .stops
            .iter()
            .enumerate()
            .filter(|(_, s)| bbox.contains(s.coord))
            .map(|(i, s)| {
                let dy = s.coord.lat - centre.lat;
                let dx = lon_delta(s.coord.lon, centre.lon) * lon_scale;
                (dx * dx + dy * dy, i as u32)
            })
            .collect();
        let total = hits.len();
        if cap == 0 {
            return (Vec::new(), total);
        }
        if hits.len() > cap {
            hits.select_nth_unstable_by(cap - 1, |a, b| a.0.total_cmp(&b.0).then(a.1.cmp(&b.1)));
            hits.truncate(cap);
        }
        let mut out: Vec<u32> = hits.into_iter().map(|h| h.1).collect();
        out.sort_unstable();
        (out, total)
    }

    /// The places a pattern calls at, in travel order: the line to draw.
    /// `None` for a pattern the feed does not have; stops the pattern names
    /// but the feed lacks are left out.
    pub fn pattern_line(&self, pattern: u32) -> Option<Vec<Coord>> {
        let pattern = self.patterns.get(pattern as usize)?;
        Some(
            pattern
                .stops
                .iter()
                .filter_map(|&s| self.stops.get(s as usize))
                .map(|s| s.coord)
                .collect(),
        )
    }

    /// The stop closest to `coord` within `max_m` metres.
    pub fn nearest(&self, coord: Coord, max_m: f64) -> Option<u32> {
        // One degree of latitude is about 111 km; skip far rows cheaply.
        let dlat = max_m / 111_000.0;
        self.stops
            .iter()
            .enumerate()
            .filter(|(_, s)| (s.coord.lat - coord.lat).abs() <= dlat)
            .map(|(i, s)| (i as u32, coord.distance_m(s.coord)))
            .filter(|&(_, d)| d <= max_m)
            .min_by(|a, b| a.1.total_cmp(&b.1))
            .map(|(i, _)| i)
    }
}

/// A latitude and longitude box, in degrees. `west > east` crosses the
/// antimeridian.
#[derive(Clone, Copy, Debug, PartialEq, Serialize, Deserialize)]
pub struct BBox {
    pub south: f64,
    pub west: f64,
    pub north: f64,
    pub east: f64,
}

impl BBox {
    pub const fn new(south: f64, west: f64, north: f64, east: f64) -> Self {
        Self {
            south,
            west,
            north,
            east,
        }
    }

    /// The box with south below north and its longitudes within -180..=180,
    /// or `None` when an edge is not a finite number.
    fn normalised(self) -> Option<Self> {
        if ![self.south, self.west, self.north, self.east]
            .iter()
            .all(|v| v.is_finite())
        {
            return None;
        }
        let (south, north) = if self.south <= self.north {
            (self.south, self.north)
        } else {
            (self.north, self.south)
        };
        // As wide as the world (or wider): every longitude.
        if (self.east - self.west).abs() >= 360.0 {
            return Some(Self::new(south, -180.0, north, 180.0));
        }
        Some(Self::new(
            south,
            wrap_lon(self.west),
            north,
            wrap_lon(self.east),
        ))
    }

    fn crosses_antimeridian(self) -> bool {
        self.west > self.east
    }

    /// Whether `c` is inside, edges included. Call on a normalised box.
    fn contains(self, c: Coord) -> bool {
        if !(self.south..=self.north).contains(&c.lat) {
            return false;
        }
        let lon = wrap_lon(c.lon);
        // 180 and -180 are one meridian: a stop written either way is on it.
        self.holds_lon(lon) || (lon.abs() == 180.0 && self.holds_lon(-lon))
    }

    fn holds_lon(self, lon: f64) -> bool {
        if self.crosses_antimeridian() {
            lon >= self.west || lon <= self.east
        } else {
            (self.west..=self.east).contains(&lon)
        }
    }

    /// The middle of the box, on the box's side of the antimeridian.
    fn centre(self) -> Coord {
        let width = if self.crosses_antimeridian() {
            self.east + 360.0 - self.west
        } else {
            self.east - self.west
        };
        Coord::new(
            (self.south + self.north) / 2.0,
            wrap_lon(self.west + width / 2.0),
        )
    }
}

/// `lon` into -180..=180 (180 stays 180, so a box may end on either side).
fn wrap_lon(lon: f64) -> f64 {
    if (-180.0..=180.0).contains(&lon) {
        lon
    } else {
        (lon + 180.0).rem_euclid(360.0) - 180.0
    }
}

/// The signed difference `a - b` in degrees of longitude, the short way round.
fn lon_delta(a: f64, b: f64) -> f64 {
    (a - b + 180.0).rem_euclid(360.0) - 180.0
}

/// Lower-cases and strips accents, folding Turkish `İ ı ş ğ ç ö ü` to ASCII.
pub fn fold(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars().flat_map(char::to_lowercase) {
        match c {
            '\u{0300}'..='\u{036f}' => {}
            'ı' => out.push('i'),
            'à'..='å' => out.push('a'),
            'ç' | 'ć' | 'č' => out.push('c'),
            'è'..='ë' | 'ě' => out.push('e'),
            'ì'..='ï' => out.push('i'),
            'ğ' => out.push('g'),
            'ñ' | 'ń' => out.push('n'),
            'ò'..='ö' | 'ø' => out.push('o'),
            'ş' | 'ś' | 'š' => out.push('s'),
            'ß' => out.push_str("ss"),
            'ù'..='ü' => out.push('u'),
            'ý' | 'ÿ' => out.push('y'),
            'ž' | 'ź' | 'ż' => out.push('z'),
            c => out.push(c),
        }
    }
    out
}

#[derive(Debug, Default)]
pub struct FeedBuilder {
    stops: Vec<Stop>,
    stop_ix: HashMap<String, u32>,
    routes: Vec<Route>,
    route_ix: HashMap<String, u32>,
    trips: HashMap<String, (u32, String)>,
    patterns: Vec<Pattern>,
    pattern_ix: HashMap<(u32, String, Vec<u32>), u32>,
    report: FeedReport,
}

fn cell(rec: &[String], i: usize) -> &str {
    rec.get(i).map_or("", String::as_str)
}

fn open<R: BufRead>(r: R, file: &str) -> Result<CsvReader<R>, GtfsError> {
    let rd = CsvReader::new(r)?;
    if rd.header().is_empty() {
        return Err(GtfsError::Missing(format!("{file}: header row")));
    }
    Ok(rd)
}

fn need<R: BufRead>(rd: &CsvReader<R>, file: &str, col: &str) -> Result<usize, GtfsError> {
    rd.column(col)
        .ok_or_else(|| GtfsError::Missing(format!("{file}: {col}")))
}

impl FeedBuilder {
    pub fn new() -> Self {
        Self::default()
    }

    /// Reads `stops.txt`. Rows with unusable coordinates or repeated ids are
    /// skipped. Call before [`Self::stop_times`].
    pub fn stops(&mut self, r: impl BufRead) -> Result<(), GtfsError> {
        const F: &str = "stops.txt";
        let mut rd = open(r, F)?;
        let (id, name) = (need(&rd, F, "stop_id")?, need(&rd, F, "stop_name")?);
        let (lat, lon) = (need(&rd, F, "stop_lat")?, need(&rd, F, "stop_lon")?);
        let parent = rd.column("parent_station");
        let mut parents: Vec<(u32, String)> = Vec::new();
        while let Some(rec) = rd.next_record()? {
            let (la, lo) = (
                cell(rec, lat).trim().parse::<f64>(),
                cell(rec, lon).trim().parse::<f64>(),
            );
            let (Ok(la), Ok(lo)) = (la, lo) else {
                self.report.skipped_rows += 1;
                continue;
            };
            let sid = cell(rec, id);
            if !(-90.0..=90.0).contains(&la)
                || !(-180.0..=180.0).contains(&lo)
                || sid.is_empty()
                || self.stop_ix.contains_key(sid)
            {
                self.report.skipped_rows += 1;
                continue;
            }
            if self.stops.len() >= MAX_STOPS {
                return Err(GtfsError::TooLarge("stops"));
            }
            let ix = self.stops.len() as u32;
            self.stop_ix.insert(sid.to_owned(), ix);
            if let Some(p) = parent.map(|p| cell(rec, p)).filter(|p| !p.is_empty()) {
                parents.push((ix, p.to_owned()));
            }
            self.stops.push(Stop {
                id: sid.to_owned(),
                name: cell(rec, name).trim().to_owned(),
                coord: Coord::new(la, lo),
                parent: None,
            });
        }
        // A parent may be listed after its children, so resolve at the end.
        for (ix, p) in parents {
            self.stops[ix as usize].parent = self.stop_ix.get(&p).copied().filter(|&pi| pi != ix);
        }
        self.report.stops = self.stops.len();
        Ok(())
    }

    /// Reads `routes.txt`. Call before [`Self::trips`].
    pub fn routes(&mut self, r: impl BufRead) -> Result<(), GtfsError> {
        const F: &str = "routes.txt";
        let mut rd = open(r, F)?;
        let id = need(&rd, F, "route_id")?;
        let (short, long) = (rd.column("route_short_name"), rd.column("route_long_name"));
        let kind = rd.column("route_type");
        while let Some(rec) = rd.next_record()? {
            let rid = cell(rec, id);
            if rid.is_empty() || self.route_ix.contains_key(rid) {
                self.report.skipped_rows += 1;
                continue;
            }
            if self.routes.len() >= MAX_ROUTES {
                return Err(GtfsError::TooLarge("routes"));
            }
            self.route_ix
                .insert(rid.to_owned(), self.routes.len() as u32);
            self.routes.push(Route {
                id: rid.to_owned(),
                short_name: short.map_or("", |i| cell(rec, i)).trim().to_owned(),
                long_name: long.map_or("", |i| cell(rec, i)).trim().to_owned(),
                kind: kind
                    .and_then(|i| cell(rec, i).trim().parse().ok())
                    .unwrap_or(3),
            });
        }
        self.report.routes = self.routes.len();
        Ok(())
    }

    /// Reads `trips.txt`. Call before [`Self::stop_times`].
    pub fn trips(&mut self, r: impl BufRead) -> Result<(), GtfsError> {
        const F: &str = "trips.txt";
        let mut rd = open(r, F)?;
        let (trip, route) = (need(&rd, F, "trip_id")?, need(&rd, F, "route_id")?);
        let head = rd.column("trip_headsign");
        while let Some(rec) = rd.next_record()? {
            let Some(&ri) = self.route_ix.get(cell(rec, route)) else {
                self.report.skipped_rows += 1;
                continue;
            };
            if self.trips.len() >= MAX_TRIPS {
                return Err(GtfsError::TooLarge("trips"));
            }
            let headsign = head.map_or("", |i| cell(rec, i)).trim().to_owned();
            self.trips
                .insert(cell(rec, trip).to_owned(), (ri, headsign));
        }
        self.report.trips = self.trips.len();
        Ok(())
    }

    /// Streams `stop_times.txt`, grouping rows by `trip_id`.
    pub fn stop_times(&mut self, r: impl BufRead) -> Result<(), GtfsError> {
        const F: &str = "stop_times.txt";
        let mut rd = open(r, F)?;
        let (trip, stop) = (need(&rd, F, "trip_id")?, need(&rd, F, "stop_id")?);
        let seq = need(&rd, F, "stop_sequence")?;

        let mut done: HashSet<String> = HashSet::new();
        let mut cur_id = String::new();
        let mut cur: Option<(u32, String)> = None;
        let mut rows: Vec<(u32, u32)> = Vec::new();
        let mut started = false;

        while let Some(rec) = rd.next_record()? {
            self.report.stop_times += 1;
            if self.report.stop_times > MAX_STOP_TIMES {
                return Err(GtfsError::TooLarge("stop_times"));
            }
            let tid = cell(rec, trip);
            if !started || tid != cur_id {
                if started {
                    self.finish_trip(cur.take(), &mut rows)?;
                    done.insert(std::mem::take(&mut cur_id));
                }
                started = true;
                cur_id.push_str(tid);
                if done.contains(tid) {
                    self.report.ungrouped_trips += 1;
                }
                cur = self.trips.get(tid).cloned();
            }
            if cur.is_none() {
                self.report.skipped_rows += 1;
                continue;
            }
            let s = self.stop_ix.get(cell(rec, stop)).copied();
            let q = cell(rec, seq).trim().parse::<u32>().ok();
            match (s, q) {
                (Some(s), Some(q)) => rows.push((q, s)),
                _ => self.report.skipped_rows += 1,
            }
        }
        if started {
            self.finish_trip(cur.take(), &mut rows)?;
        }
        Ok(())
    }

    fn finish_trip(
        &mut self,
        trip: Option<(u32, String)>,
        rows: &mut Vec<(u32, u32)>,
    ) -> Result<(), GtfsError> {
        let Some((route, headsign)) = trip else {
            rows.clear();
            return Ok(());
        };
        rows.sort_by_key(|r| r.0);
        let stops: Vec<u32> = rows.drain(..).map(|r| r.1).collect();
        if stops.len() < 2 {
            return Ok(());
        }
        let key = (route, headsign, stops);
        if self.pattern_ix.contains_key(&key) {
            return Ok(());
        }
        if self.patterns.len() >= MAX_PATTERNS {
            return Err(GtfsError::TooLarge("patterns"));
        }
        let ix = self.patterns.len() as u32;
        self.patterns.push(Pattern {
            route,
            headsign: key.1.clone(),
            stops: key.2.clone(),
        });
        self.pattern_ix.insert(key, ix);
        Ok(())
    }

    pub fn finish(mut self) -> Result<(Feed, FeedReport), GtfsError> {
        self.report.patterns = self.patterns.len();
        let now = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(0, |d| i64::try_from(d.as_millis()).unwrap_or(i64::MAX));
        Ok((
            Feed::new(self.stops, self.routes, self.patterns, now),
            self.report,
        ))
    }
}

/// Imports a GTFS zip (`stops.txt`, `routes.txt`, `trips.txt` and
/// `stop_times.txt` at the archive root).
pub fn read_zip(r: impl Read + Seek) -> Result<(Feed, FeedReport), GtfsError> {
    let mut zip = zip::ZipArchive::new(r).map_err(|e| GtfsError::Zip(e.to_string()))?;
    let mut b = FeedBuilder::new();
    macro_rules! file {
        ($name:literal) => {
            match zip.by_name($name) {
                Ok(f) => BufReader::new(f),
                Err(zip::result::ZipError::FileNotFound) => {
                    return Err(GtfsError::Missing($name.to_owned()));
                }
                Err(e) => return Err(GtfsError::Zip(e.to_string())),
            }
        };
    }
    b.stops(file!("stops.txt"))?;
    b.routes(file!("routes.txt"))?;
    b.trips(file!("trips.txt"))?;
    b.stop_times(file!("stop_times.txt"))?;
    b.finish()
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::io::{Cursor, Write};

    const STOPS: &str = "stop_id,stop_name,stop_lat,stop_lon,parent_station\n\
        A,Alpha,41.00,29.00,\n\
        B,Beta,41.01,29.00,\n\
        C,Çarşı,41.02,29.00,\n\
        D,Delta,41.03,29.00,\n\
        E,Alpha Station,41.00,29.001,P\n\
        P,Alpha Hub,41.0,29.0,\n";
    const ROUTES: &str = "route_id,route_short_name,route_long_name,route_type\n\
        R1,1,One,3\nR2,2,Two,0\n";
    const TRIPS: &str = "trip_id,route_id,trip_headsign\n\
        t1,R1,Delta\nt2,R1,Delta\nt3,R2,Alpha\n";
    const TIMES: &str = "trip_id,stop_id,stop_sequence\n\
        t1,A,1\nt1,C,3\nt1,B,2\nt1,D,4\n\
        t2,A,1\nt2,B,2\nt2,C,3\nt2,D,4\n\
        t3,D,1\nt3,C,2\nt3,A,3\n\
        t1,D,9\n";

    fn zip_of(files: &[(&str, &str)]) -> Cursor<Vec<u8>> {
        let mut w = zip::ZipWriter::new(Cursor::new(Vec::new()));
        for (name, body) in files {
            w.start_file(*name, zip::write::SimpleFileOptions::default())
                .unwrap();
            w.write_all(body.as_bytes()).unwrap();
        }
        w.finish().unwrap()
    }

    fn feed() -> (Feed, FeedReport) {
        let z = zip_of(&[
            ("stops.txt", STOPS),
            ("routes.txt", ROUTES),
            ("trips.txt", TRIPS),
            ("stop_times.txt", TIMES),
        ]);
        read_zip(z).unwrap()
    }

    #[test]
    fn imports_and_dedupes_patterns() {
        let (f, rep) = feed();
        assert_eq!(rep.stops, 6);
        assert_eq!(rep.trips, 3);
        assert_eq!(rep.stop_times, 12);
        // t1 and t2 share a pattern; t3 is the reverse on R2; the lone
        // reappearing t1 row is a one-stop fragment and makes no pattern.
        assert_eq!(f.patterns.len(), 2);
        assert_eq!(rep.patterns, 2);
        assert_eq!(rep.ungrouped_trips, 1);
        assert_eq!(f.patterns[0].stops, [0, 1, 2, 3]);
        assert_eq!(f.patterns[0].headsign, "Delta");
        assert_eq!(f.routes[1].kind, 0);
        assert!(f.imported_ms > 0);
        // Parent resolved even though listed after its child.
        assert_eq!(f.stops[4].parent, Some(5));
    }

    #[test]
    fn patterns_through_a_stop() {
        let (f, _) = feed();
        assert_eq!(f.patterns_through(2), [0, 1]);
        assert_eq!(f.patterns_through(1), [0]);
        assert!(f.patterns_through(99).is_empty());
    }

    #[test]
    fn search_folds_and_ranks() {
        let (f, _) = feed();
        assert_eq!(f.search("CARSI", None, 5), [2]);
        assert_eq!(f.search("alpha", None, 5), [0, 4, 5]);
        // Exact first, then nearest.
        let near = Coord::new(41.0, 29.001);
        assert_eq!(f.search("alpha", Some(near), 5), [0, 4, 5]);
        assert_eq!(f.search("station alpha", Some(near), 5), [4]);
        assert_eq!(f.search("alpha", None, 1), [0]);
        assert!(f.search("  ", None, 5).is_empty());
        assert_eq!(fold("İSTANBUL Işık"), "istanbul isik");
    }

    #[test]
    fn nearest_within_radius() {
        let (f, _) = feed();
        assert_eq!(f.nearest(Coord::new(41.0101, 29.0), 50.0), Some(1));
        assert_eq!(f.nearest(Coord::new(41.005, 29.0), 50.0), None);
    }

    #[test]
    fn missing_file_and_column() {
        let z = zip_of(&[("routes.txt", ROUTES)]);
        match read_zip(z) {
            Err(GtfsError::Missing(m)) => assert_eq!(m, "stops.txt"),
            other => panic!("{other:?}"),
        }
        let mut b = FeedBuilder::new();
        match b.stops("stop_id,stop_name\nA,x\n".as_bytes()) {
            Err(GtfsError::Missing(m)) => assert!(m.contains("stop_lat"), "{m}"),
            other => panic!("{other:?}"),
        }
        assert!(matches!(
            read_zip(Cursor::new(b"nope".to_vec())),
            Err(GtfsError::Zip(_))
        ));
    }

    #[test]
    fn caps_are_enforced() {
        let mut csv = String::from("stop_id,stop_name,stop_lat,stop_lon\n");
        for i in 0..=MAX_STOPS {
            csv.push_str(&format!("s{i},n,1,1\n"));
        }
        let mut b = FeedBuilder::new();
        assert!(matches!(
            b.stops(csv.as_bytes()),
            Err(GtfsError::TooLarge("stops"))
        ));

        let mut b = FeedBuilder::new();
        let mut routes = String::from("route_id\n");
        for i in 0..=MAX_ROUTES {
            routes.push_str(&format!("r{i}\n"));
        }
        assert!(matches!(
            b.routes(routes.as_bytes()),
            Err(GtfsError::TooLarge("routes"))
        ));

        // Patterns: each trip a distinct two-stop sequence on one route.
        let mut b = FeedBuilder::new();
        b.stops("stop_id,stop_name,stop_lat,stop_lon\na,a,1,1\nb,b,1,1\n".as_bytes())
            .unwrap();
        b.routes("route_id\nr\n".as_bytes()).unwrap();
        let mut trips = String::from("trip_id,route_id,trip_headsign\n");
        let mut times = String::from("trip_id,stop_id,stop_sequence\n");
        for i in 0..=MAX_PATTERNS {
            trips.push_str(&format!("t{i},r,h{i}\n"));
            times.push_str(&format!("t{i},a,1\nt{i},b,2\n"));
        }
        b.trips(trips.as_bytes()).unwrap();
        assert!(matches!(
            b.stop_times(times.as_bytes()),
            Err(GtfsError::TooLarge("patterns"))
        ));
    }

    #[test]
    fn stop_time_row_cap() {
        let mut b = FeedBuilder::new();
        let header = "trip_id,stop_id,stop_sequence\n";
        let row = "t,a,1\n";
        // Use a generated reader instead of an 8M-row string.
        struct Rows(u64, &'static [u8], usize);
        impl Read for Rows {
            fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
                if self.0 == 0 {
                    return Ok(0);
                }
                let n = buf.len().min(self.1.len() - self.2);
                buf[..n].copy_from_slice(&self.1[self.2..self.2 + n]);
                self.2 += n;
                if self.2 == self.1.len() {
                    self.2 = 0;
                    self.0 -= 1;
                }
                Ok(n)
            }
        }
        let data = BufReader::new(io::Read::chain(
            header.as_bytes(),
            Rows(MAX_STOP_TIMES + 1, row.as_bytes(), 0),
        ));
        assert!(matches!(
            b.stop_times(data),
            Err(GtfsError::TooLarge("stop_times"))
        ));
    }

    // ---- stops in a box and the line of a pattern ----

    fn stop_at(id: &str, lat: f64, lon: f64) -> Stop {
        Stop {
            id: id.to_owned(),
            name: id.to_owned(),
            coord: Coord::new(lat, lon),
            parent: None,
        }
    }

    fn feed_of(stops: Vec<Stop>, patterns: Vec<Pattern>) -> Feed {
        Feed::new(stops, Vec::new(), patterns, 0)
    }

    #[test]
    fn only_stops_inside_the_box_are_answered_edges_included() {
        let feed = feed_of(
            vec![
                stop_at("in", 41.0, 29.0),
                stop_at("north edge", 41.1, 29.0),
                stop_at("west edge", 41.0, 28.9),
                stop_at("just north", 41.1001, 29.0),
                stop_at("just east", 41.0, 29.1001),
                stop_at("far", -30.0, 150.0),
            ],
            Vec::new(),
        );
        let bbox = BBox::new(40.9, 28.9, 41.1, 29.1);
        assert_eq!(feed.stops_in_box(bbox, 300), [0, 1, 2]);
        assert_eq!(feed.box_hits(bbox, 300).1, 3);
        // South and north the other way round are the same box.
        assert_eq!(
            feed.stops_in_box(BBox::new(41.1, 28.9, 40.9, 29.1), 300),
            [0, 1, 2]
        );
        // A box of one point.
        assert_eq!(
            feed.stops_in_box(BBox::new(41.0, 29.0, 41.0, 29.0), 300),
            [0]
        );
    }

    #[test]
    fn an_empty_feed_and_an_empty_box_answer_nothing() {
        let empty = feed_of(Vec::new(), Vec::new());
        assert!(
            empty
                .stops_in_box(BBox::new(-90.0, -180.0, 90.0, 180.0), 300)
                .is_empty()
        );
        assert_eq!(
            empty.box_hits(BBox::new(40.0, 28.0, 42.0, 30.0), 300),
            (Vec::new(), 0)
        );
        let feed = feed_of(vec![stop_at("a", 41.0, 29.0)], Vec::new());
        assert!(
            feed.stops_in_box(BBox::new(10.0, 10.0, 11.0, 11.0), 300)
                .is_empty()
        );
        // No room at all: nothing, but the count is still told.
        assert_eq!(
            feed.box_hits(BBox::new(40.0, 28.0, 42.0, 30.0), 0),
            (Vec::new(), 1)
        );
    }

    #[test]
    fn a_box_with_an_edge_that_is_not_a_number_holds_nothing() {
        let feed = feed_of(vec![stop_at("a", 41.0, 29.0)], Vec::new());
        for bad in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY] {
            assert!(
                feed.stops_in_box(BBox::new(bad, 28.0, 42.0, 30.0), 300)
                    .is_empty()
            );
            assert!(
                feed.stops_in_box(BBox::new(40.0, bad, 42.0, 30.0), 300)
                    .is_empty()
            );
            assert!(
                feed.stops_in_box(BBox::new(40.0, 28.0, bad, 30.0), 300)
                    .is_empty()
            );
            assert!(
                feed.stops_in_box(BBox::new(40.0, 28.0, 42.0, bad), 300)
                    .is_empty()
            );
        }
    }

    #[test]
    fn a_box_across_the_antimeridian_takes_both_sides() {
        let feed = feed_of(
            vec![
                stop_at("west side", 0.0, 179.5),
                stop_at("east side", 0.0, -179.5),
                stop_at("on the line", 0.0, 180.0),
                stop_at("on the line, other spelling", 0.0, -180.0),
                stop_at("outside west", 0.0, 178.0),
                stop_at("outside east", 0.0, -178.0),
                stop_at("other side of the world", 0.0, 0.0),
            ],
            Vec::new(),
        );
        // From 179 east to -179: two degrees wide.
        let bbox = BBox::new(-1.0, 179.0, 1.0, -179.0);
        assert_eq!(feed.stops_in_box(bbox, 300), [0, 1, 2, 3]);
        // The same box spelled with longitudes beyond 180.
        assert_eq!(
            feed.stops_in_box(BBox::new(-1.0, 179.0, 1.0, 181.0), 300),
            [0, 1, 2, 3]
        );
        assert_eq!(
            feed.stops_in_box(BBox::new(-1.0, -181.0, 1.0, -179.0), 300),
            [0, 1, 2, 3]
        );
        // The cap keeps those nearest the middle (the antimeridian itself), measured the short way round.
        assert_eq!(
            feed.stops_in_box(BBox::new(-1.0, 177.0, 1.0, -177.0), 2),
            [2, 3]
        );
        assert_eq!(
            feed.stops_in_box(BBox::new(-1.0, 177.0, 1.0, -177.0), 4),
            [0, 1, 2, 3]
        );
        // A box that does not cross does not take the far side.
        assert_eq!(
            feed.stops_in_box(BBox::new(-1.0, 177.0, 1.0, 180.0), 300),
            [0, 2, 3, 4]
        );
    }

    #[test]
    fn a_box_as_wide_as_the_world_takes_every_longitude() {
        let feed = feed_of(
            vec![
                stop_at("a", 10.0, -170.0),
                stop_at("b", 10.0, 0.0),
                stop_at("c", 10.0, 170.0),
                stop_at("south", -80.0, 0.0),
            ],
            Vec::new(),
        );
        assert_eq!(
            feed.stops_in_box(BBox::new(0.0, -180.0, 20.0, 180.0), 300),
            [0, 1, 2]
        );
        assert_eq!(
            feed.stops_in_box(BBox::new(0.0, -300.0, 20.0, 400.0), 300),
            [0, 1, 2]
        );
        assert_eq!(
            feed.stops_in_box(BBox::new(-90.0, -180.0, 90.0, 180.0), 300),
            [0, 1, 2, 3]
        );
    }

    #[test]
    fn stops_at_the_poles_are_found() {
        let feed = feed_of(
            vec![
                stop_at("north pole", 90.0, 0.0),
                stop_at("south pole", -90.0, 123.0),
            ],
            Vec::new(),
        );
        assert_eq!(
            feed.stops_in_box(BBox::new(89.0, -180.0, 90.0, 180.0), 300),
            [0]
        );
        assert_eq!(
            feed.stops_in_box(BBox::new(-90.0, 100.0, -89.0, 130.0), 1),
            [1]
        );
    }

    #[test]
    fn the_cap_keeps_the_stops_nearest_the_middle_in_a_fixed_order() {
        // Fifty stops in a row going east from the middle of the box, 0.001 degrees apart.
        let stops: Vec<Stop> = (0..50)
            .map(|i| stop_at(&format!("s{i}"), 41.0, 29.0 + f64::from(i) * 0.001))
            .collect();
        let feed = feed_of(stops, Vec::new());
        let bbox = BBox::new(40.9, 28.9, 41.1, 29.1);
        assert_eq!(feed.stops_in_box(bbox, 5), [0, 1, 2, 3, 4]);
        assert_eq!(feed.box_hits(bbox, 5), (vec![0, 1, 2, 3, 4], 50));
        assert_eq!(feed.stops_in_box(bbox, 300).len(), 50);
        // Two stops equally far from the middle: the lower index wins, every time.
        let pair = feed_of(
            vec![
                stop_at("east", 41.0, 29.01),
                stop_at("west", 41.0, 28.99),
                stop_at("centre", 41.0, 29.0),
            ],
            Vec::new(),
        );
        for _ in 0..20 {
            assert_eq!(pair.stops_in_box(bbox, 2), [0, 2]);
        }
        // A big feed: the cap holds and the answer is sorted.
        let many: Vec<Stop> = (0..2_000)
            .map(|i| {
                stop_at(
                    "m",
                    41.0 + f64::from(i / 40) * 0.0001,
                    29.0 + f64::from(i % 40) * 0.0001,
                )
            })
            .collect();
        let hits = feed_of(many, Vec::new()).stops_in_box(bbox, 300);
        assert_eq!(hits.len(), 300);
        assert!(hits.windows(2).all(|w| w[0] < w[1]));
    }

    #[test]
    fn the_line_of_a_pattern_is_its_stops_in_travel_order() {
        let (f, _) = feed();
        // t2 of the test feed: A, B, C, D going north.
        let pattern = f.patterns.iter().position(|p| p.stops.len() == 4).unwrap() as u32;
        let line = f.pattern_line(pattern).unwrap();
        assert_eq!(line.len(), 4);
        assert!(line.windows(2).all(|w| w[0].lat < w[1].lat));
        // A pattern the feed does not have.
        assert_eq!(f.pattern_line(f.patterns.len() as u32), None);
        assert_eq!(f.pattern_line(u32::MAX), None);
        assert_eq!(feed_of(Vec::new(), Vec::new()).pattern_line(0), None);
    }

    #[test]
    fn a_pattern_that_names_a_missing_stop_skips_it_and_an_empty_one_is_empty() {
        let feed = feed_of(
            vec![stop_at("a", 41.0, 29.0), stop_at("b", 41.1, 29.0)],
            vec![
                Pattern {
                    route: 0,
                    headsign: String::new(),
                    stops: vec![1, 7, 0],
                },
                Pattern {
                    route: 0,
                    headsign: String::new(),
                    stops: Vec::new(),
                },
            ],
        );
        assert_eq!(
            feed.pattern_line(0),
            Some(vec![Coord::new(41.1, 29.0), Coord::new(41.0, 29.0)])
        );
        assert_eq!(feed.pattern_line(1), Some(Vec::new()));
    }
}
