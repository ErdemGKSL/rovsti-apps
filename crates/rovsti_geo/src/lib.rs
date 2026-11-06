//! Offline geometry and the arrival-alarm state machine behind Rovsti Stops.
//!
//! The Android side feeds GPS fixes in through [`ActiveTrip::on_fix`] (or
//! [`CrossingTrip::update`] directly); the trip answers whether to ring and how soon
//! it wants the next fix, so the location service can sleep while the
//! diameter is far away.

mod coord;
mod crossing;
pub mod csv;
pub mod dest;
pub mod gtfs;
pub mod history;
pub mod notice;
pub mod places;
pub mod prefs;
mod session;
mod trip;
pub mod view;

pub use coord::{Coord, EARTH_RADIUS_M};
pub use crossing::{
    Crossing, CrossingTrip, CrossingUpdate, CrossingWatch, DEAD_BAND_M, Diameter, DiameterError,
    Endpoint, MAX_DIAMETER_M, MIN_DIAMETER_M, NoticeRule, Side, SideReading,
};
pub use dest::{DestError, SharedPlace, parse_location};
pub use gtfs::{Feed, FeedBuilder, FeedReport, GtfsError, Pattern, Route, Stop, read_zip};
pub use history::{History, Outcome, Stats, TripRecord};
pub use notice::{DEFAULT_TEMPLATE, TemplateError};
pub use places::{Place, PlaceError, SavedPlaces};
pub use prefs::{NOTICE_MINUTES, NoticePrefs, NoticeWhen, SNOOZE_CHOICES, StopsPrefs};
pub use session::{
    ActiveTrip, FixIn, FixOut, FixOutcome, FixPos, NoticeDelivery, NoticeState, Track,
};
pub use trip::{Fix, TripState};
pub use view::Viewport;
