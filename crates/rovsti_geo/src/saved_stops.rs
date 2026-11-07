//! Saved stops: a stop is the pair of points A and B that define a gate.
//!
//! Crossing the segment AB rings the alarm, in either direction, so the order of
//! A and B never matters when comparing stops.

use rovsti_core::search::fold;
use serde::{Deserialize, Serialize};

use crate::coord_format::{CoordFormat, format};
use crate::session::format_distance;
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

/// Longest launcher shortcut labels, in characters.
pub const SHORTCUT_SHORT_CHARS: usize = 10;
pub const SHORTCUT_LONG_CHARS: usize = 25;

/// What the launcher needs to pin a shortcut that starts a saved stop's trip.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct StopShortcut {
    pub id: String,
    pub short_label: String,
    pub long_label: String,
    pub stop_id: u64,
}

/// Shortcut description for `stop`; labels are cut on character boundaries.
pub fn shortcut_spec(stop: &SavedStop) -> StopShortcut {
    let name = stop.name.trim();
    let name = if name.is_empty() { "Stop" } else { name };
    StopShortcut {
        id: format!("stop-{}", stop.id),
        short_label: name.chars().take(SHORTCUT_SHORT_CHARS).collect(),
        long_label: name.chars().take(SHORTCUT_LONG_CHARS).collect(),
        stop_id: stop.id,
    }
}

/// The colour a saved stop is drawn in on the map.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum StopColour {
    #[default]
    Orange,
    Red,
    Green,
    Blue,
}

impl StopColour {
    pub const ALL: [StopColour; 4] = [Self::Orange, Self::Red, Self::Green, Self::Blue];

    pub fn label(self) -> &'static str {
        match self {
            Self::Orange => "Orange",
            Self::Red => "Red",
            Self::Green => "Green",
            Self::Blue => "Blue",
        }
    }

    /// The next colour, wrapping from the last back to the first.
    pub fn next(self) -> Self {
        let i = Self::ALL.iter().position(|c| *c == self).unwrap_or(0);
        Self::ALL[(i + 1) % Self::ALL.len()]
    }

    /// Whether this is the colour a stop has until the user picks one.
    pub fn is_default(&self) -> bool {
        *self == Self::default()
    }
}

/// One saved stop: a name and the two points that define its gate.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct SavedStop {
    pub id: u64,
    pub name: String,
    pub a: Endpoint,
    pub b: Endpoint,
    pub created_ms: i64,
    /// Starred stops are listed first. Absent from the file when false.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub favourite: bool,
    /// Map colour. Absent from the file when orange.
    #[serde(default, skip_serializing_if = "StopColour::is_default")]
    pub colour: StopColour,
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
            favourite: false,
            colour: StopColour::default(),
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

    /// Flips the favourite flag and returns the new value; `None` when the id is missing.
    pub fn toggle_favourite(&mut self, id: u64) -> Option<bool> {
        let stop = self.stops.iter_mut().find(|s| s.id == id)?;
        stop.favourite = !stop.favourite;
        Some(stop.favourite)
    }

    /// Moves the stop to its next colour and returns it; `None` when the id is missing.
    pub fn cycle_colour(&mut self, id: u64) -> Option<StopColour> {
        let stop = self.stops.iter_mut().find(|s| s.id == id)?;
        stop.colour = stop.colour.next();
        Some(stop.colour)
    }

    /// Sets the colour; false when the id is missing.
    pub fn set_colour(&mut self, id: u64, colour: StopColour) -> bool {
        match self.stops.iter_mut().find(|s| s.id == id) {
            Some(stop) => {
                stop.colour = colour;
                true
            }
            None => false,
        }
    }

    /// Moves a stop `delta` positions (negative is up) among the stops with the
    /// same favourite flag, clamped to that group. False when the id is missing
    /// or nothing moved.
    pub fn move_by(&mut self, id: u64, delta: isize) -> bool {
        let Some(from) = self.stops.iter().position(|s| s.id == id) else {
            return false;
        };
        let flag = self.stops[from].favourite;
        let group: Vec<usize> = (0..self.stops.len())
            .filter(|&i| self.stops[i].favourite == flag)
            .collect();
        let at = group.iter().position(|&i| i == from).unwrap_or(0);
        let to_at = at.saturating_add_signed(delta).min(group.len() - 1);
        if to_at == at {
            return false;
        }
        let to = group[to_at];
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

    /// The saved stop whose centre is closest to `here`, with that distance in
    /// metres, if within `max_m`. Stops without a valid gate are skipped. Ties
    /// go to a favourite, then to the earlier one.
    pub fn nearest(&self, here: Coord, max_m: f64) -> Option<(&SavedStop, f64)> {
        let mut best: Option<(&SavedStop, f64)> = None;
        for stop in &self.stops {
            if stop.diameter().is_err() {
                continue;
            }
            let d = here.distance_m(stop.center());
            if !d.is_finite() || d > max_m {
                continue;
            }
            let better = match best {
                None => true,
                Some((b, bd)) => d < bd || (d == bd && stop.favourite && !b.favourite),
            };
            if better {
                best = Some((stop, d));
            }
        }
        best
    }
}

/// Nearest-stop chip shows only within this distance, metres.
pub const NEAREST_CHIP_M: f64 = 2_000.0;

/// Order of the Saved list.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum SavedSort {
    /// The order the user arranged.
    #[default]
    Saved,
    /// Alphabetical, ignoring case and diacritics.
    Name,
    /// Closest gate centre first.
    Nearest,
}

/// `hits` in the requested order, favourites first in every order (stable inside
/// each group). `Nearest` without a position keeps saved order.
pub fn sorted(mut hits: Vec<&SavedStop>, sort: SavedSort, here: Option<Coord>) -> Vec<&SavedStop> {
    match (sort, here) {
        (SavedSort::Name, _) => hits.sort_by_cached_key(|s| fold(&s.name)),
        (SavedSort::Nearest, Some(here)) => {
            hits.sort_by(|x, y| {
                here.distance_m(x.center())
                    .total_cmp(&here.distance_m(y.center()))
            });
        }
        _ => {}
    }
    hits.sort_by_key(|s| !s.favourite);
    hits
}

/// The list as plain text for sharing: a header, then per stop its name, centre,
/// distance from `here` when known, and a map link. Empty for no stops.
pub fn list_text(stops: &[&SavedStop], here: Option<Coord>) -> String {
    if stops.is_empty() {
        return String::new();
    }
    let mut out = format!("Saved stops ({})", stops.len());
    for s in stops {
        let c = s.center();
        let star = if s.favourite { "\u{2605} " } else { "" };
        out.push_str(&format!(
            "\n{star}{}: {}",
            s.name,
            format(c, CoordFormat::Decimal, None)
        ));
        if let Some(h) = here {
            out.push_str(&format!(" ({} away)", format_distance(h.distance_m(c))));
        }
        out.push_str(&format!("\n  {}", format(c, CoordFormat::OsmLink, None)));
    }
    out
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

    #[test]
    fn colour_round_trips_and_legacy_files_are_orange() {
        let mut s = SavedStops::default();
        let (a, b) = gate(0.01);
        let id = s.add("X", a, b, 1).unwrap();
        assert!(!serde_json::to_string(&s).unwrap().contains("colour"));
        assert!(s.set_colour(id, StopColour::Red));
        let json = serde_json::to_string(&s).unwrap();
        assert!(json.contains("\"colour\":\"red\""));
        let back: SavedStops = serde_json::from_str(&json).unwrap();
        assert_eq!(back.stops[0].colour, StopColour::Red);
        let legacy = json.replace(",\"colour\":\"red\"", "");
        assert!(!legacy.contains("colour"));
        let old: SavedStops = serde_json::from_str(&legacy).unwrap();
        assert_eq!(old.stops[0].colour, StopColour::Orange);
        assert!(!serde_json::to_string(&old).unwrap().contains("colour"));
        assert!(!s.set_colour(99, StopColour::Blue));
    }

    #[test]
    fn colour_next_cycles_all_four() {
        let mut c = StopColour::default();
        let mut seen = Vec::new();
        for _ in 0..4 {
            seen.push(c);
            c = c.next();
        }
        assert_eq!(seen, StopColour::ALL);
        assert_eq!(c, StopColour::Orange);
        assert_eq!(StopColour::Green.label(), "Green");
    }

    #[test]
    fn cycle_colour_moves_the_stop_and_misses_unknown_ids() {
        let mut s = SavedStops::default();
        let (a, b) = gate(0.01);
        let id = s.add("X", a, b, 1).unwrap();
        assert_eq!(s.cycle_colour(id), Some(StopColour::Red));
        assert_eq!(s.get(id).unwrap().colour, StopColour::Red);
        assert_eq!(s.cycle_colour(99), None);
    }

    #[test]
    fn shortcut_spec_truncates_on_chars() {
        let stop = SavedStop {
            id: 7,
            name: "Kadıköy İskele Çarşı Meydanı Durağı".into(),
            a: ep("A", 41.0, 29.0),
            b: ep("B", 41.001, 29.0),
            created_ms: 0,
            favourite: false,
            colour: StopColour::default(),
        };
        let sp = shortcut_spec(&stop);
        assert_eq!(sp.id, "stop-7");
        assert_eq!(sp.short_label.chars().count(), 10);
        assert_eq!(sp.long_label.chars().count(), 25);
        assert!(stop.name.starts_with(&sp.short_label));
        let v = serde_json::to_value(&sp).unwrap();
        for k in ["id", "short_label", "long_label", "stop_id"] {
            assert!(v.get(k).is_some(), "{k}");
        }
        let empty = SavedStop {
            name: "  ".into(),
            ..stop
        };
        assert_eq!(shortcut_spec(&empty).short_label, "Stop");
    }

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
    fn sort_orders() {
        let mut s = SavedStops::default();
        for (name, n) in [("Zeta", 0.0), ("çay", 0.2), ("Alpha", 0.1)] {
            let (a, b) = gate(n);
            s.add(name, a, b, 0).unwrap();
        }
        let names =
            |v: Vec<&SavedStop>| -> Vec<String> { v.into_iter().map(|x| x.name.clone()).collect() };
        let all = || s.search("");
        assert_eq!(
            names(sorted(all(), SavedSort::Saved, None)),
            ["Zeta", "çay", "Alpha"]
        );
        assert_eq!(
            names(sorted(all(), SavedSort::Name, None)),
            ["Alpha", "çay", "Zeta"]
        );
        let here = Some(Coord::new(41.2, 29.0));
        assert_eq!(
            names(sorted(all(), SavedSort::Nearest, here)),
            ["çay", "Alpha", "Zeta"]
        );
        assert_eq!(
            names(sorted(all(), SavedSort::Nearest, None)),
            ["Zeta", "çay", "Alpha"]
        );
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

    fn three() -> SavedStops {
        let mut s = SavedStops::default();
        for (n, k) in [
            ("Charlie", 0.0),
            ("alpha", 0.1),
            ("Bravo", 0.2),
            ("Delta", 0.3),
        ] {
            let (a, b) = gate(k);
            s.add(n, a, b, 0).unwrap();
        }
        s
    }

    fn names(v: &[&SavedStop]) -> Vec<String> {
        v.iter().map(|s| s.name.clone()).collect()
    }

    #[test]
    fn old_json_loads_and_plain_stops_omit_the_key() {
        let old = r#"{"version":2,"next_id":2,"stops":[{"id":1,"name":"X",
            "a":{"label":"","coord":{"lat":41.0,"lon":29.0}},
            "b":{"label":"","coord":{"lat":41.001,"lon":29.0}},"created_ms":3}]}"#;
        let l = load_saved_stops(Some(old));
        assert!(!l.discarded);
        assert!(!l.stops.stops[0].favourite);
        let mut s = l.stops;
        assert!(!serde_json::to_string(&s).unwrap().contains("favourite"));
        s.toggle_favourite(1);
        assert!(
            serde_json::to_string(&s)
                .unwrap()
                .contains("\"favourite\":true")
        );
    }

    #[test]
    fn toggle_twice() {
        let mut s = three();
        assert_eq!(s.toggle_favourite(2), Some(true));
        assert_eq!(s.toggle_favourite(2), Some(false));
        assert_eq!(s.toggle_favourite(99), None);
    }

    #[test]
    fn favourites_first_in_every_sort() {
        let mut s = three();
        s.toggle_favourite(3); // Bravo
        s.toggle_favourite(4); // Delta
        let all = || s.stops.iter().collect::<Vec<_>>();
        assert_eq!(
            names(&sorted(all(), SavedSort::Saved, None)),
            ["Bravo", "Delta", "Charlie", "alpha"]
        );
        assert_eq!(
            names(&sorted(all(), SavedSort::Name, None)),
            ["Bravo", "Delta", "alpha", "Charlie"]
        );
        let here = Some(Coord::new(41.3, 29.0));
        assert_eq!(
            names(&sorted(all(), SavedSort::Nearest, here)),
            ["Delta", "Bravo", "alpha", "Charlie"]
        );
    }

    #[test]
    fn move_by_stays_in_group() {
        let mut s = three();
        s.toggle_favourite(3); // order: Charlie alpha Bravo* Delta
        let order = |s: &SavedStops| s.stops.iter().map(|x| x.id).collect::<Vec<_>>();
        assert!(!s.move_by(3, -1)); // only favourite: at the edge
        assert!(s.move_by(1, 1)); // Charlie swaps past alpha only
        assert_eq!(order(&s), [2, 1, 3, 4]);
    }

    #[test]
    fn move_by_edges() {
        let mut s = three();
        s.toggle_favourite(1);
        s.toggle_favourite(2); // Charlie* alpha* Bravo Delta
        assert!(!s.move_by(2, 1)); // last favourite cannot enter the others
        assert!(!s.move_by(3, -1)); // first non-favourite cannot enter favourites
        assert!(s.move_by(2, -1));
        assert_eq!(
            s.stops.iter().map(|x| x.id).collect::<Vec<_>>(),
            [2, 1, 3, 4]
        );
    }

    #[test]
    fn list_text_shapes() {
        assert_eq!(list_text(&[], None), "");
        let mut s = SavedStops::default();
        let (a, b) = gate(0.0);
        s.add("Kadıköy İskele", a, b, 0).unwrap();
        let (a, b) = gate(0.1);
        s.add("Pier", a, b, 0).unwrap();
        s.toggle_favourite(1);
        let v: Vec<&SavedStop> = s.stops.iter().collect();
        let c0 = format(v[0].center(), CoordFormat::Decimal, None);
        let l0 = format(v[0].center(), CoordFormat::OsmLink, None);
        let plain = list_text(&v, None);
        let lines: Vec<&str> = plain.lines().collect();
        assert_eq!(lines.len(), 5);
        assert_eq!(lines[0], "Saved stops (2)");
        assert_eq!(lines[1], format!("\u{2605} Kadıköy İskele: {c0}"));
        assert_eq!(lines[2], format!("  {l0}"));
        assert!(lines[3].starts_with("Pier: "));
        assert!(!lines[3].contains("away"));
        let here = v[0].center();
        let near = list_text(&v, Some(here));
        assert!(near.contains(&format!("{c0} (0 m away)")));
        assert!(near.lines().nth(3).unwrap().ends_with(" km away)"));
    }

    #[test]
    fn nearest_empty_and_out_of_range() {
        let mut s = SavedStops::default();
        let here = Coord::new(41.0, 29.0);
        assert!(s.nearest(here, NEAREST_CHIP_M).is_none());
        let (a, b) = gate(0.1);
        s.add("Far", a, b, 1).unwrap();
        assert!(s.nearest(here, NEAREST_CHIP_M).is_none());
    }

    #[test]
    fn nearest_picks_closest() {
        let mut s = SavedStops::default();
        let (a, b) = gate(0.01);
        s.add("Mid", a, b, 1).unwrap();
        let (a, b) = gate(0.002);
        let near = s.add("Near", a, b, 2).unwrap();
        let (a, b) = gate(0.005);
        s.add("Other", a, b, 3).unwrap();
        let (stop, d) = s.nearest(Coord::new(41.0, 29.0), NEAREST_CHIP_M).unwrap();
        assert_eq!(stop.id, near);
        assert!(d > 200.0 && d < 400.0, "{d}");
    }

    #[test]
    fn nearest_tie_goes_to_favourite_then_earlier() {
        let mut s = SavedStops::default();
        let (a, b) = gate(0.002);
        let first = s.add("First", a.clone(), b.clone(), 1).unwrap();
        let second = s.add(
            "Second",
            ep("A2", a.coord.lat, 29.0),
            ep("B2", b.coord.lat, 29.0),
            2,
        );
        // Same gate is a duplicate, so build the tie by hand.
        assert!(second.is_err());
        let mut twin = s.get(first).unwrap().clone();
        twin.id = 2;
        twin.name = "Twin".into();
        s.stops.push(twin);
        let here = Coord::new(41.0, 29.0);
        assert_eq!(s.nearest(here, NEAREST_CHIP_M).unwrap().0.id, first);
        s.toggle_favourite(2);
        assert_eq!(s.nearest(here, NEAREST_CHIP_M).unwrap().0.id, 2);
    }

    #[test]
    fn nearest_skips_invalid_gate() {
        let mut s = SavedStops::default();
        s.stops.push(SavedStop {
            id: 1,
            name: "Broken".into(),
            a: pa(),
            b: pa(),
            created_ms: 0,
            favourite: true,
            colour: StopColour::default(),
        });
        assert!(s.nearest(Coord::new(41.0, 29.0), NEAREST_CHIP_M).is_none());
        let (a, b) = gate(0.01);
        let ok = s.add("Ok", a, b, 1).unwrap();
        let (stop, _) = s.nearest(Coord::new(41.0, 29.0), NEAREST_CHIP_M).unwrap();
        assert_eq!(stop.id, ok);
    }
}
