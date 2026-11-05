//! Saved places: named destinations with an optional preferred alarm rule.

use rovsti_core::search::fold;
use serde::{Deserialize, Serialize};

use crate::{AlarmRule, Coord};

/// Most places kept.
pub const MAX_PLACES: usize = 200;
/// Longest place name, in characters.
pub const MAX_NAME_CHARS: usize = 60;
/// A new place this close to a saved one is the same place.
pub const DUPLICATE_RADIUS_M: f64 = 50.0;

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct Place {
    pub id: u64,
    pub name: String,
    pub coord: Coord,
    /// Overrides the default rule when this place is the destination.
    pub rule: Option<AlarmRule>,
    /// GTFS stop id when the place came from an imported feed.
    pub gtfs_stop: Option<String>,
}

impl Default for Place {
    fn default() -> Self {
        Self {
            id: 0,
            name: String::new(),
            coord: Coord::new(0.0, 0.0),
            rule: None,
            gtfs_stop: None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PlaceError {
    /// The name is empty or longer than [`MAX_NAME_CHARS`].
    InvalidName,
    /// The coordinates are not a position on Earth.
    InvalidCoord,
    /// A saved place (this id) is already within [`DUPLICATE_RADIUS_M`].
    Duplicate(u64),
    /// [`MAX_PLACES`] reached.
    Full,
    NotFound,
}

impl PlaceError {
    pub fn message(self) -> &'static str {
        match self {
            Self::InvalidName => "Names need 1 to 60 characters.",
            Self::InvalidCoord => "These coordinates are not on Earth.",
            Self::Duplicate(_) => "You already saved a place here.",
            Self::Full => "You have saved 200 places. Remove one first.",
            Self::NotFound => "That place no longer exists.",
        }
    }
}

impl std::fmt::Display for PlaceError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.message())
    }
}

impl std::error::Error for PlaceError {}

#[derive(Clone, Debug, Default, PartialEq, Serialize, Deserialize)]
#[serde(default)]
pub struct SavedPlaces {
    pub next_id: u64,
    pub places: Vec<Place>,
}

fn valid_name(name: &str) -> Option<String> {
    let name = name.trim();
    let n = name.chars().count();
    (1..=MAX_NAME_CHARS).contains(&n).then(|| name.to_owned())
}

impl SavedPlaces {
    /// Saves a place and returns its id.
    pub fn add(&mut self, name: &str, coord: Coord) -> Result<u64, PlaceError> {
        let name = valid_name(name).ok_or(PlaceError::InvalidName)?;
        if !coord.lat.is_finite()
            || !coord.lon.is_finite()
            || coord.lat.abs() > 90.0
            || coord.lon.abs() > 180.0
        {
            return Err(PlaceError::InvalidCoord);
        }
        if let Some(p) = self
            .places
            .iter()
            .find(|p| p.coord.distance_m(coord) <= DUPLICATE_RADIUS_M)
        {
            return Err(PlaceError::Duplicate(p.id));
        }
        if self.places.len() >= MAX_PLACES {
            return Err(PlaceError::Full);
        }
        // Robust against a hand-edited or older file: never reuse an id.
        let highest = self.places.iter().map(|p| p.id).max().unwrap_or(0);
        let id = self.next_id.max(highest + 1).max(1);
        self.next_id = id + 1;
        self.places.push(Place {
            id,
            name,
            coord,
            rule: None,
            gtfs_stop: None,
        });
        Ok(id)
    }

    pub fn get(&self, id: u64) -> Option<&Place> {
        self.places.iter().find(|p| p.id == id)
    }

    pub fn get_mut(&mut self, id: u64) -> Option<&mut Place> {
        self.places.iter_mut().find(|p| p.id == id)
    }

    pub fn rename(&mut self, id: u64, name: &str) -> Result<(), PlaceError> {
        let name = valid_name(name).ok_or(PlaceError::InvalidName)?;
        self.get_mut(id).ok_or(PlaceError::NotFound)?.name = name;
        Ok(())
    }

    /// Removes a place; false when it did not exist.
    pub fn remove(&mut self, id: u64) -> bool {
        let before = self.places.len();
        self.places.retain(|p| p.id != id);
        self.places.len() != before
    }

    /// Places whose name contains `query`, ignoring case and diacritics. An
    /// empty query lists everything. Saved order is kept.
    pub fn search(&self, query: &str) -> Vec<&Place> {
        let q = fold(query.trim());
        self.places
            .iter()
            .filter(|p| q.is_empty() || fold(&p.name).contains(&q))
            .collect()
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    const TAKSIM: Coord = Coord::new(41.0370, 28.9850);
    const KADIKOY: Coord = Coord::new(40.9917, 29.0233);

    #[test]
    fn add_assigns_increasing_ids() {
        let mut p = SavedPlaces::default();
        let a = p.add("Taksim", TAKSIM).unwrap();
        let b = p.add("  Kadıköy ", KADIKOY).unwrap();
        assert_eq!((a, b), (1, 2));
        assert_eq!(p.get(b).unwrap().name, "Kadıköy");
        p.remove(b);
        // Ids are not reused.
        assert_eq!(p.add("Again", KADIKOY).unwrap(), 3);
    }

    #[test]
    fn dedupes_within_fifty_metres() {
        let mut p = SavedPlaces::default();
        let id = p.add("Home", TAKSIM).unwrap();
        // About 33 m north.
        let near = Coord::new(TAKSIM.lat + 0.0003, TAKSIM.lon);
        assert_eq!(p.add("Home 2", near), Err(PlaceError::Duplicate(id)));
        // About 111 m north.
        let far = Coord::new(TAKSIM.lat + 0.001, TAKSIM.lon);
        assert!(p.add("Home 3", far).is_ok());
    }

    #[test]
    fn cap_and_name_limits() {
        let mut p = SavedPlaces::default();
        for i in 0..MAX_PLACES {
            // 0.01 degrees apart: well over 50 m.
            p.add("P", Coord::new(i as f64 * 0.01 - 80.0, 10.0))
                .unwrap();
        }
        assert_eq!(
            p.add("One more", Coord::new(89.0, 10.0)),
            Err(PlaceError::Full)
        );
        assert_eq!(p.add("", TAKSIM), Err(PlaceError::InvalidName));
        assert_eq!(p.add("   ", TAKSIM), Err(PlaceError::InvalidName));
        let mut q = SavedPlaces::default();
        assert_eq!(q.add(&"x".repeat(61), TAKSIM), Err(PlaceError::InvalidName));
        assert!(q.add(&"ı".repeat(60), TAKSIM).is_ok());
        assert_eq!(
            q.add("Bad", Coord::new(91.0, 0.0)),
            Err(PlaceError::InvalidCoord)
        );
    }

    #[test]
    fn rename_and_remove() {
        let mut p = SavedPlaces::default();
        let id = p.add("Work", TAKSIM).unwrap();
        p.rename(id, "Office").unwrap();
        assert_eq!(p.get(id).unwrap().name, "Office");
        assert_eq!(p.rename(id, ""), Err(PlaceError::InvalidName));
        assert_eq!(p.rename(99, "x"), Err(PlaceError::NotFound));
        assert!(p.remove(id));
        assert!(!p.remove(id));
    }

    #[test]
    fn search_folds_case_and_diacritics() {
        let mut p = SavedPlaces::default();
        p.add("Kadıköy Pier", KADIKOY).unwrap();
        p.add("Taksim", TAKSIM).unwrap();
        let hits = p.search("kadikoy");
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].name, "Kadıköy Pier");
        assert_eq!(p.search("TAK").len(), 1);
        assert_eq!(p.search("").len(), 2);
        assert!(p.search("zzz").is_empty());
    }

    #[test]
    fn empty_json_loads() {
        let p: SavedPlaces = serde_json::from_str("{}").unwrap();
        assert!(p.places.is_empty());
        let p: SavedPlaces = serde_json::from_str(r#"{"places":[{"id":4,"name":"A"}]}"#).unwrap();
        let mut p = p;
        assert_eq!(p.add("B", TAKSIM).unwrap(), 5);
    }
}
