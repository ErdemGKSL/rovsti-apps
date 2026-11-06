//! Saved stops: a stop is the pair of points A and B that define a gate.
//!
//! Crossing the segment AB rings the alarm, in either direction, so the order of
//! A and B never matters when comparing stops.

use rovsti_core::search::fold;
use serde::{Deserialize, Serialize};

use crate::{Coord, Diameter, DiameterError, Endpoint, Feed};

/// Version written to and required from `saved.json`.
pub const FORMAT_VERSION: u32 = 2;
/// Most stops kept.
pub const MAX_STOPS: usize = 200;
/// Longest stop name, in characters.
pub const MAX_NAME_CHARS: usize = 60;
/// A new stop whose points are both this close to a saved stop's points is the same stop.
pub const DUPLICATE_GATE_M: f64 = 25.0;
/// How far from the gate midpoint a GTFS stop may be to suggest its name.
pub const NAME_SEARCH_M: f64 = 300.0;

/// One saved stop: a name and the two points that define its gate.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct SavedStop {
    pub id: u64,
    pub name: String,
    pub a: Endpoint,
    pub b: Endpoint,
    pub created_ms: i64,
}

impl SavedStop {
    /// The validated gate, or why the stored points no longer form one.
    pub fn diameter(&self) -> Result<Diameter, DiameterError> {
        Diameter::new(self.a.coord, self.b.coord)
    }

    /// Midpoint of AB.
    pub fn center(&self) -> Coord {
        match self.diameter() {
            Ok(d) => d.center(),
            Err(_) => Coord::new(
                (self.a.coord.lat + self.b.coord.lat) / 2.0,
                (self.a.coord.lon + self.b.coord.lon) / 2.0,
            ),
        }
    }

    /// |AB| in metres.
    pub fn length_m(&self) -> f64 {
        match self.diameter() {
            Ok(d) => d.length_m(),
            Err(_) => self.a.coord.distance_m(self.b.coord),
        }
    }

    fn same_gate(&self, a: Coord, b: Coord) -> bool {
        let (sa, sb) = (self.a.coord, self.b.coord);
        let near = |p: Coord, q: Coord| p.distance_m(q) <= DUPLICATE_GATE_M;
        (near(sa, a) && near(sb, b)) || (near(sa, b) && near(sb, a))
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SavedStopError {
    /// The name is empty or longer than [`MAX_NAME_CHARS`].
    InvalidName,
    /// The two points cannot form a stop.
    InvalidGate(DiameterError),
    /// This stop (this id) is already saved.
    Duplicate(u64),
    /// [`MAX_STOPS`] reached.
    Full,
    NotFound,
}

impl SavedStopError {
    pub fn message(self) -> &'static str {
        match self {
            Self::InvalidName => "Stop names need 1 to 60 characters.",
            Self::InvalidGate(e) => e.message(),
            Self::Duplicate(_) => "You already saved this stop.",
            Self::Full => "You have saved 200 stops. Remove one first.",
            Self::NotFound => "That stop no longer exists.",
        }
    }
}

impl std::fmt::Display for SavedStopError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for SavedStopError {}

/// The saved stops, in list order.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct SavedStops {
    /// Required on load: files without it are old or foreign and get discarded.
    pub version: u32,
    pub next_id: u64,
    pub stops: Vec<SavedStop>,
}

impl Default for SavedStops {
    fn default() -> Self {
        Self {
            version: FORMAT_VERSION,
            next_id: 1,
            stops: Vec::new(),
        }
    }
}

fn valid_name(name: &str) -> Option<String> {
    let name = name.trim();
    let n = name.chars().count();
    (1..=MAX_NAME_CHARS).contains(&n).then(|| name.to_owned())
}

impl SavedStops {
    /// Saves a stop and returns its id.
    pub fn add(
        &mut self,
        name: &str,
        a: Endpoint,
        b: Endpoint,
        now_ms: i64,
    ) -> Result<u64, SavedStopError> {
        let name = valid_name(name).ok_or(SavedStopError::InvalidName)?;
        Diameter::new(a.coord, b.coord).map_err(SavedStopError::InvalidGate)?;
        if let Some(s) = self.find_gate(a.coord, b.coord) {
            return Err(SavedStopError::Duplicate(s.id));
        }
        if self.stops.len() >= MAX_STOPS {
            return Err(SavedStopError::Full);
        }
        // Robust against a hand-edited file: never reuse an id.
        let highest = self.stops.iter().map(|s| s.id).max().unwrap_or(0);
        let id = self.next_id.max(highest + 1).max(1);
        self.next_id = id + 1;
        self.stops.push(SavedStop {
            id,
            name,
            a,
            b,
            created_ms: now_ms,
        });
        Ok(id)
    }

    pub fn get(&self, id: u64) -> Option<&SavedStop> {
        self.stops.iter().find(|s| s.id == id)
    }

    pub fn rename(&mut self, id: u64, name: &str) -> Result<(), SavedStopError> {
        let name = valid_name(name).ok_or(SavedStopError::InvalidName)?;
        self.stops
            .iter_mut()
            .find(|s| s.id == id)
            .ok_or(SavedStopError::NotFound)?
            .name = name;
        Ok(())
    }

    /// Removes a stop; false when it did not exist.
    pub fn remove(&mut self, id: u64) -> bool {
        let before = self.stops.len();
        self.stops.retain(|s| s.id != id);
        self.stops.len() != before
    }

    /// Moves a stop `delta` positions (negative is up), clamped to the list.
    /// False when the id is missing or nothing moved.
    pub fn move_by(&mut self, id: u64, delta: isize) -> bool {
        let Some(from) = self.stops.iter().position(|s| s.id == id) else {
            return false;
        };
        let last = self.stops.len() - 1;
        let to = from.saturating_add_signed(delta).min(last);
        if to == from {
            return false;
        }
        let stop = self.stops.remove(from);
        self.stops.insert(to, stop);
        true
    }

    /// Puts a removed stop back at `index` (clamped), to undo a delete. A stop
    /// whose id is already present is ignored.
    pub fn restore(&mut self, stop: SavedStop, index: usize) {
        if self.get(stop.id).is_some() {
            return;
        }
        self.next_id = self.next_id.max(stop.id + 1);
        let index = index.min(self.stops.len());
        self.stops.insert(index, stop);
    }

    /// The saved stop with these ends, in either order.
    pub fn find_gate(&self, a: Coord, b: Coord) -> Option<&SavedStop> {
        self.stops.iter().find(|s| s.same_gate(a, b))
    }

    /// Stops whose name contains `query`, ignoring case and diacritics. An empty
    /// query lists everything. Saved order is kept.
    pub fn search(&self, query: &str) -> Vec<&SavedStop> {
        let q = fold(query.trim());
        self.stops
            .iter()
            .filter(|s| q.is_empty() || fold(&s.name).contains(&q))
            .collect()
    }
}

/// Result of [`load_saved_stops`].
#[derive(Clone, Debug, PartialEq)]
pub struct LoadedStops {
    pub stops: SavedStops,
    /// True when a file existed but was old or unreadable and was dropped, so the
    /// caller overwrites it.
    pub discarded: bool,
}

/// Reads the saved-stops file text. Only the current format is kept; anything
/// else (the old single-point places, garbage, another version) is discarded.
pub fn load_saved_stops(text: Option<&str>) -> LoadedStops {
    let Some(text) = text else {
        return LoadedStops {
            stops: SavedStops::default(),
            discarded: false,
        };
    };
    match serde_json::from_str::<SavedStops>(text) {
        Ok(stops) if stops.version == FORMAT_VERSION => LoadedStops {
            stops,
            discarded: false,
        },
        _ => LoadedStops {
            stops: SavedStops::default(),
            discarded: true,
        },
    }
}

fn is_generic(label: &str) -> bool {
    matches!(label.trim(), "" | "Point" | "Point A" | "Point B")
}

/// A name to prefill when saving the stop AB: the nearest GTFS stop to the gate
/// midpoint, else the ends' own label, else empty.
pub fn suggest_name(feed: Option<&Feed>, a: &Endpoint, b: &Endpoint) -> String {
    let mid = Diameter::new(a.coord, b.coord).map_or_else(
        |_| {
            Coord::new(
                (a.coord.lat + b.coord.lat) / 2.0,
                (a.coord.lon + b.coord.lon) / 2.0,
            )
        },
        |d| d.center(),
    );
    if let Some(feed) = feed
        && let Some(i) = feed.nearest(mid, NAME_SEARCH_M)
        && let Some(stop) = feed.stops.get(i as usize)
    {
        let name = stop.name.trim();
        if !name.is_empty() {
            return name.chars().take(MAX_NAME_CHARS).collect();
        }
    }
    let (la, lb) = (a.label.trim(), b.label.trim());
    // A shared non-generic label is also the first one.
    let label = if !is_generic(la) {
        la
    } else if !is_generic(lb) {
        lb
    } else {
        ""
    };
    label.chars().take(MAX_NAME_CHARS).collect()
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::Stop;

    fn ep(label: &str, lat: f64, lon: f64) -> Endpoint {
        Endpoint {
            label: label.into(),
            coord: Coord::new(lat, lon),
        }
    }

    // About 111 m apart north-south.
    fn pa() -> Endpoint {
        ep("Point A", 41.000, 29.0)
    }
    fn pb() -> Endpoint {
        ep("Point B", 41.001, 29.0)
    }
    fn gate(n: f64) -> (Endpoint, Endpoint) {
        (
            ep("Point A", 41.0 + n, 29.0),
            ep("Point B", 41.001 + n, 29.0),
        )
    }

    #[test]
    fn add_assigns_increasing_ids_never_reused() {
        let mut s = SavedStops::default();
        let (a1, b1) = gate(0.0);
        let (a2, b2) = gate(0.1);
        let x = s.add(" Home ", a1, b1, 5).unwrap();
        let y = s.add("Work", a2, b2, 6).unwrap();
        assert_eq!((x, y), (1, 2));
        assert_eq!(s.get(x).unwrap().name, "Home");
        assert_eq!(s.get(x).unwrap().created_ms, 5);
        assert!(s.remove(y));
        let (a3, b3) = gate(0.2);
        assert_eq!(s.add("Third", a3, b3, 7).unwrap(), 3);
    }

    #[test]
    fn name_limits() {
        let mut s = SavedStops::default();
        let (a, b) = gate(0.0);
        assert_eq!(
            s.add("   ", a.clone(), b.clone(), 0),
            Err(SavedStopError::InvalidName)
        );
        let long = "x".repeat(MAX_NAME_CHARS + 1);
        assert_eq!(
            s.add(&long, a.clone(), b.clone(), 0),
            Err(SavedStopError::InvalidName)
        );
        let ok = "ş".repeat(MAX_NAME_CHARS);
        assert!(s.add(&ok, a, b, 0).is_ok());
    }

    #[test]
    fn invalid_gates() {
        let mut s = SavedStops::default();
        let near = ep("B", 41.00005, 29.0);
        assert_eq!(
            s.add("n", pa(), near, 0),
            Err(SavedStopError::InvalidGate(DiameterError::TooShort))
        );
        assert_eq!(
            s.add("n", pa(), pa(), 0),
            Err(SavedStopError::InvalidGate(DiameterError::SamePoint))
        );
        assert_eq!(
            s.add("n", pa(), ep("B", 45.0, 29.0), 0),
            Err(SavedStopError::InvalidGate(DiameterError::TooLong))
        );
        assert!(s.stops.is_empty());
    }

    #[test]
    fn duplicates_in_both_orders() {
        let mut s = SavedStops::default();
        let id = s.add("Gate", pa(), pb(), 0).unwrap();
        assert_eq!(
            s.add("Again", pa(), pb(), 0),
            Err(SavedStopError::Duplicate(id))
        );
        assert_eq!(
            s.add("Swapped", pb(), pa(), 0),
            Err(SavedStopError::Duplicate(id))
        );
        // Within 25 m of both ends still counts.
        let a2 = ep("a", 41.00005, 29.0);
        let b2 = ep("b", 41.00105, 29.0);
        assert_eq!(s.add("Near", a2, b2, 0), Err(SavedStopError::Duplicate(id)));
        // Same A but a different B is a different stop.
        let b3 = ep("b", 41.002, 29.0);
        assert!(s.add("Longer", pa(), b3, 0).is_ok());
        assert_eq!(s.find_gate(pb().coord, pa().coord).unwrap().id, id);
    }

    #[test]
    fn full_list_refuses() {
        let mut s = SavedStops::default();
        for i in 0..MAX_STOPS {
            let (a, b) = gate(i as f64 * 0.01);
            s.add("s", a, b, 0).unwrap();
        }
        let (a, b) = gate(5.0);
        assert_eq!(s.add("s", a, b, 0), Err(SavedStopError::Full));
    }

    #[test]
    fn rename_remove_move_restore() {
        let mut s = SavedStops::default();
        let ids: Vec<u64> = (0..3)
            .map(|i| {
                let (a, b) = gate(i as f64 * 0.1);
                s.add(&format!("S{i}"), a, b, 0).unwrap()
            })
            .collect();
        s.rename(ids[1], " Renamed ").unwrap();
        assert_eq!(s.get(ids[1]).unwrap().name, "Renamed");
        assert_eq!(s.rename(ids[1], ""), Err(SavedStopError::InvalidName));
        assert_eq!(s.rename(99, "x"), Err(SavedStopError::NotFound));

        assert!(s.move_by(ids[0], 5));
        assert_eq!(s.stops.last().unwrap().id, ids[0]);
        assert!(!s.move_by(ids[0], 1));
        assert!(s.move_by(ids[0], -1));
        assert_eq!(s.stops[1].id, ids[0]);
        assert!(!s.move_by(99, 1));

        let removed = s.get(ids[1]).unwrap().clone();
        let index = s.stops.iter().position(|x| x.id == ids[1]).unwrap();
        assert!(s.remove(ids[1]));
        assert!(!s.remove(ids[1]));
        s.restore(removed.clone(), index);
        assert_eq!(s.stops[index].id, ids[1]);
        let n = s.stops.len();
        s.restore(removed, 0);
        assert_eq!(s.stops.len(), n);
    }

    #[test]
    fn search_ignores_case_and_diacritics() {
        let mut s = SavedStops::default();
        let (a, b) = gate(0.0);
        s.add("Kadıköy İskele", a, b, 0).unwrap();
        let (a, b) = gate(0.1);
        s.add("Work", a, b, 0).unwrap();
        assert_eq!(s.search("").len(), 2);
        assert_eq!(s.search("kadikoy").len(), 1);
        assert_eq!(s.search("zzz").len(), 0);
    }

    #[test]
    fn center_and_length() {
        let mut s = SavedStops::default();
        let id = s.add("g", pa(), pb(), 0).unwrap();
        let st = s.get(id).unwrap();
        assert!((st.length_m() - 111.2).abs() < 1.0);
        assert!((st.center().lat - 41.0005).abs() < 1e-9);
        assert!(st.diameter().is_ok());
    }

    #[test]
    fn load_none_and_round_trip() {
        let l = load_saved_stops(None);
        assert!(!l.discarded);
        assert!(l.stops.stops.is_empty());

        let mut s = SavedStops::default();
        s.add("Gate", pa(), pb(), 9).unwrap();
        let text = serde_json::to_string(&s).unwrap();
        let l = load_saved_stops(Some(&text));
        assert!(!l.discarded);
        assert_eq!(l.stops, s);
    }

    #[test]
    fn load_discards_old_and_broken_files() {
        let old = r#"{"next_id":2,"places":[{"id":1,"name":"Home",
            "coord":{"lat":41.0,"lon":29.0},"rule":{"Radius":{"metres":300.0}}}]}"#;
        let v1 = r#"{"version":1,"next_id":1,"stops":[]}"#;
        for text in [old, "{}", "\"garbage\"", "garbage", "", v1] {
            let l = load_saved_stops(Some(text));
            assert!(l.discarded, "{text}");
            assert!(l.stops.stops.is_empty());
            assert_eq!(l.stops.version, FORMAT_VERSION);
        }
    }

    fn feed() -> Feed {
        let mut f = Feed::default();
        f.stops.push(Stop {
            id: "S1".into(),
            name: "Taksim".into(),
            coord: Coord::new(41.0005, 29.0),
            parent: None,
        });
        f
    }

    #[test]
    fn suggest_uses_the_feed_stop_near_the_midpoint() {
        let f = feed();
        assert_eq!(suggest_name(Some(&f), &pa(), &pb()), "Taksim");
    }

    #[test]
    fn suggest_falls_back_to_labels_when_the_stop_is_far() {
        let f = feed();
        let a = ep("Point A", 41.1, 29.0);
        let b = ep("Kadıköy", 41.101, 29.0);
        assert_eq!(suggest_name(Some(&f), &a, &b), "Kadıköy");
        let a = ep("Pier", 41.1, 29.0);
        let b = ep("Pier", 41.101, 29.0);
        assert_eq!(suggest_name(Some(&f), &a, &b), "Pier");
        assert_eq!(suggest_name(None, &a, &ep("Other", 41.101, 29.0)), "Pier");
    }

    #[test]
    fn suggest_is_empty_for_generic_labels_without_a_feed() {
        assert_eq!(suggest_name(None, &pa(), &pb()), "");
        let a = ep("", 41.0, 29.0);
        let b = ep("Point", 41.001, 29.0);
        assert_eq!(suggest_name(None, &a, &b), "");
    }
}
