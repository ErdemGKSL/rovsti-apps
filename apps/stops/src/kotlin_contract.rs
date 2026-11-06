//! Guards the Kotlin trip service against drifting from the Rust polling policy.
//! No device is needed: the Kotlin source and the manifest are read as text.

use rovsti_geo::poll;

const TRIP_SERVICE: &str =
    include_str!("../../../crates/rovsti_android/kotlin/dev/rovsti/bridge/TripService.kt");
const MANIFEST: &str = include_str!("../platforms/android/app/src/main/AndroidManifest.xml");

#[test]
fn service_is_a_location_foreground_service() {
    assert!(TRIP_SERVICE.contains("FOREGROUND_SERVICE_TYPE_LOCATION"));
    assert!(MANIFEST.contains("android:foregroundServiceType=\"location\""));
    assert!(MANIFEST.contains("android.permission.FOREGROUND_SERVICE_LOCATION"));
}

#[test]
fn updates_are_requested_on_both_api_paths() {
    // LocationRequest (Android 12+) and the legacy overload.
    assert_eq!(TRIP_SERVICE.matches("requestLocationUpdates(").count(), 2);
}

#[test]
fn watchdog_mirrors_the_rust_retry_window() {
    assert!(TRIP_SERVICE.contains("retry_ms"));
    let max = format!("TRIP_MAX_INTERVAL_MS = {}_000L", poll::MAX_POLL.as_secs());
    assert!(TRIP_SERVICE.contains(&max), "expected {max}");
    assert!(TRIP_SERVICE.contains("coerceIn(5_000L, 30_000L)"));
    // Rust side of the same window: interval / 4 clamped to 5..30 s.
    assert_eq!(
        poll::retry_after(poll::MAX_POLL) - poll::MAX_POLL,
        std::time::Duration::from_secs(30)
    );
    assert_eq!(
        poll::retry_after(poll::MIN_POLL) - poll::MIN_POLL,
        std::time::Duration::from_secs(5)
    );
}
