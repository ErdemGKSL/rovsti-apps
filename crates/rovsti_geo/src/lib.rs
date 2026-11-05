//! Offline geometry and the arrival-alarm state machine behind Rovsti Stops.
//!
//! The Android side feeds GPS fixes in through [`ActiveTrip::on_fix`] (or
//! [`Trip::update`] directly); the trip answers whether to ring and how soon
//! it wants the next fix, so the location service can sleep while the
//! destination is far away.

mod coord;
pub mod dest;
pub mod history;
pub mod notice;
pub mod places;
pub mod prefs;
mod session;
mod trip;

pub use coord::{Coord, EARTH_RADIUS_M};
pub use dest::{DestError, SharedPlace, parse_location};
pub use history::{History, Outcome, Stats, TripRecord};
pub use notice::{DEFAULT_TEMPLATE, TemplateError};
pub use places::{Place, PlaceError, SavedPlaces};
pub use prefs::{NoticePrefs, NoticeWhen, RuleChoice, StopsPrefs};
pub use session::{ActiveTrip, FixIn, FixOut, FixOutcome, NoticeDelivery, NoticeState, Track};
pub use trip::{AlarmRule, Fix, Trip, TripState, TripUpdate};
