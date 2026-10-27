//! Offline geometry and the arrival-alarm state machine behind Rovsti Stops.
//!
//! The Android side feeds GPS fixes in through [`Trip::update`]; the trip
//! answers whether to ring and how soon it wants the next fix, so the
//! location service can sleep while the destination is far away.

mod coord;
mod trip;

pub use coord::{Coord, EARTH_RADIUS_M};
pub use trip::{AlarmRule, Fix, Trip, TripState, TripUpdate};
