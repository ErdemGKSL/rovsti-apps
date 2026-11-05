//! Offline geometry and the arrival-alarm state machine behind Rovsti Stops.
//!
//! The Android side feeds GPS fixes in through [`ActiveTrip::on_fix`] (or
//! [`Trip::update`] directly); the trip answers whether to ring and how soon
//! it wants the next fix, so the location service can sleep while the
//! destination is far away.

mod coord;
pub mod notice;
mod session;
mod trip;

pub use coord::{Coord, EARTH_RADIUS_M};
pub use notice::{DEFAULT_TEMPLATE, TemplateError};
pub use session::{ActiveTrip, FixIn, FixOut, FixOutcome, NoticeDelivery, NoticeState, Track};
pub use trip::{AlarmRule, Fix, Trip, TripState, TripUpdate};
