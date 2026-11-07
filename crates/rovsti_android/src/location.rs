//! One-shot device location for Rovsti Stops while no trip runs (the idle map).
//!
//! The trip service owns location during a trip ([`crate::trip`]). This module
//! only asks for the newest known fix, or for one fresh fix that arrives as a
//! `stops_location` event ([`crate::events::kind::STOPS_LOCATION`]). Kotlin side:
//! `StopsLocation`. On desktop every function returns [`crate::Error::Unsupported`].

use crate::{Result, bridge};

/// A device location fix.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct LocationFix {
    pub lat: f64,
    pub lon: f64,
    /// Horizontal accuracy radius in metres.
    pub accuracy_m: f64,
    /// When the fix was taken, epoch milliseconds.
    pub time_ms: i64,
}

/// The newest location Android knows, or `None` when there is none or the
/// location permission is missing.
pub fn last_location() -> Result<Option<LocationFix>> {
    let json = bridge::call_string("lastLocation", &[])?;
    Ok(parse_location_fix(&json))
}

/// Asks for one fresh fix. The answer is a `stops_location` event; `false`
/// when there is no permission or no provider.
pub fn request_location() -> Result<bool> {
    bridge::call_bool("locateOnce", &[])
}

/// Starts (`true`) or stops (`false`) live fixes, each delivered as a
/// `stops_location` event (every 2 s or 5 m). Idempotent. `false` when there is
/// no permission or no provider.
pub fn watch_location(on: bool) -> Result<bool> {
    bridge::call_bool("watchLocation", &[bridge::Arg::Bool(on)])
}

/// Decodes `{"lat":..,"lon":..,"accuracy_m":..,"time_ms":..}`. `None` for
/// malformed JSON, a missing field, a non-finite or out-of-range coordinate, or
/// a negative accuracy. An empty string (no fix) is `None` too.
pub fn parse_location_fix(json: &str) -> Option<LocationFix> {
    let value: serde_json::Value = serde_json::from_str(json).ok()?;
    let lat = value.get("lat")?.as_f64().filter(|v| v.is_finite())?;
    let lon = value.get("lon")?.as_f64().filter(|v| v.is_finite())?;
    let accuracy_m = value
        .get("accuracy_m")?
        .as_f64()
        .filter(|v| v.is_finite())?;
    let time_ms = value.get("time_ms")?.as_i64()?;
    if !(-90.0..=90.0).contains(&lat) || !(-180.0..=180.0).contains(&lon) || accuracy_m < 0.0 {
        return None;
    }
    Some(LocationFix {
        lat,
        lon,
        accuracy_m,
        time_ms,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn good_fix_parses() {
        assert_eq!(
            parse_location_fix(
                r#"{"lat":41.0082,"lon":28.9784,"accuracy_m":12.5,"time_ms":1700000000000}"#
            ),
            Some(LocationFix {
                lat: 41.0082,
                lon: 28.9784,
                accuracy_m: 12.5,
                time_ms: 1_700_000_000_000,
            })
        );
        assert!(
            parse_location_fix(r#"{"lat":-90,"lon":180,"accuracy_m":0,"time_ms":1}"#).is_some()
        );
    }

    #[test]
    fn bad_fixes_are_rejected() {
        for bad in [
            "",
            "nope",
            "{}",
            r#"{"lat":41,"lon":29,"accuracy_m":5}"#,
            r#"{"lat":41,"lon":29,"time_ms":1}"#,
            r#"{"lon":29,"accuracy_m":5,"time_ms":1}"#,
            r#"{"lat":91,"lon":29,"accuracy_m":5,"time_ms":1}"#,
            r#"{"lat":41,"lon":-181,"accuracy_m":5,"time_ms":1}"#,
            r#"{"lat":41,"lon":29,"accuracy_m":-1,"time_ms":1}"#,
            r#"{"lat":"41","lon":29,"accuracy_m":5,"time_ms":1}"#,
            r#"{"lat":null,"lon":29,"accuracy_m":5,"time_ms":1}"#,
            r#"{"lat":1e999,"lon":29,"accuracy_m":5,"time_ms":1}"#,
        ] {
            assert_eq!(parse_location_fix(bad), None, "{bad}");
        }
    }

    #[cfg(not(target_os = "android"))]
    #[test]
    fn desktop_is_unsupported() {
        assert!(matches!(last_location(), Err(crate::Error::Unsupported)));
        assert!(matches!(request_location(), Err(crate::Error::Unsupported)));
        assert!(matches!(
            watch_location(true),
            Err(crate::Error::Unsupported)
        ));
        assert!(matches!(
            watch_location(false),
            Err(crate::Error::Unsupported)
        ));
    }
}
