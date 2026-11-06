//! Shared trip vocabulary: location fixes, trip state and polling constants.

use crate::Coord;

/// One location fix from the platform.
#[derive(Clone, Copy, Debug, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct Fix {
    pub at: Coord,
    /// Horizontal accuracy radius, metres (Android `Location.getAccuracy`).
    pub accuracy_m: f64,
    /// Ground speed, m/s, when the provider reports one.
    pub speed_mps: Option<f64>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum TripState {
    Tracking,
    /// The alarm fired; the UI keeps ringing until the user dismisses it.
    Arrived,
    Dismissed,
}

/// Fixes worse than this are ignored: a 2 km cell-tower fix must never ring.
pub(crate) const MAX_ACCURACY_M: f64 = 150.0;
/// Below this speed the ETA is meaningless (waiting at a stop, traffic jam).
pub(crate) const MIN_SPEED_MPS: f64 = 1.5;
/// Assumed top speed for scheduling fixes: a fast intercity train.
pub(crate) const MAX_SPEED_MPS: f64 = 60.0;
