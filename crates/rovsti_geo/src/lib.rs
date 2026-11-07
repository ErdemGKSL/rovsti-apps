//! Offline geometry and the arrival-alarm state machine behind Rovsti Stops.
//!
//! The Android side feeds GPS fixes in through [`ActiveTrip::on_fix`] (or
//! [`CrossingTrip::update`] directly); the trip answers whether to ring and how soon
//! it wants the next fix, so the location service can sleep while the
//! diameter is far away.

pub mod alarm;
mod coord;
pub mod coord_format;
mod crossing;
pub mod csv;
pub mod dest;
pub mod gtfs;
pub mod history;
pub mod history_io;
pub mod measure;
pub mod notice;
pub mod places_io;
pub mod poll;
pub mod prefs;
pub mod saved_stops;
mod session;
pub mod tiles;
mod trip;
pub mod view;

pub use alarm::{AlarmPlan, SoundSource, VOLUME_CHOICES, VibrationPattern, next_volume};
pub use coord::{Coord, EARTH_RADIUS_M};
pub use crossing::{
    Crossing, CrossingTrip, CrossingUpdate, CrossingWatch, DEAD_BAND_M, Diameter, DiameterError,
    Endpoint, MAX_DIAMETER_M, MIN_DIAMETER_M, NoticeRule, Side, SideReading,
};
pub use dest::{
    DestError, SharedPlace, parse_location, parse_stop_share, point_qr_payload, share_text,
    stop_share_text,
};
pub use gtfs::{Feed, FeedBuilder, FeedReport, GtfsError, Pattern, Route, Stop, read_zip};
pub use history::{History, Outcome, OutcomeFilter, Stats, TripRecord};
pub use measure::Measure;
pub use notice::{DEFAULT_TEMPLATE, TemplateError};
pub use prefs::{
    AlarmSound, Appearance, NOTICE_MINUTES, NoticePrefs, NoticeWhen, SNOOZE_CHOICES, StopsPrefs,
};
pub use saved_stops::{LoadedStops, SavedStop, SavedStopError, SavedStops};
pub use session::{
    ActiveTrip, FixIn, FixOut, FixOutcome, FixPos, NoticeDelivery, NoticeState, Track,
};
pub use trip::{Fix, TripState};
pub use view::Viewport;
