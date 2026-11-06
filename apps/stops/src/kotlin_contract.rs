//! Guards the Kotlin trip service against drifting from the Rust polling policy.
//! No device is needed: the Kotlin source and the manifest are read as text.

use rovsti_geo::poll;

const TRIP_SERVICE: &str =
    include_str!("../../../crates/rovsti_android/kotlin/dev/rovsti/bridge/TripService.kt");
const ALARM_SOUND: &str =
    include_str!("../../../crates/rovsti_android/kotlin/dev/rovsti/bridge/AlarmSound.kt");
const TRIP_ALARM: &str =
    include_str!("../../../crates/rovsti_android/kotlin/dev/rovsti/bridge/TripAlarm.kt");
const BRIDGE: &str =
    include_str!("../../../crates/rovsti_android/kotlin/dev/rovsti/bridge/RovstiBridge.kt");
const TRIP_SERVICE_RECOVER: &str = "TripAlarm.recover(this)";
const MANIFEST: &str = include_str!("../platforms/android/app/src/main/AndroidManifest.xml");

#[test]
fn service_is_a_location_foreground_service() {
    assert!(TRIP_SERVICE.contains("FOREGROUND_SERVICE_TYPE_LOCATION"));
    assert!(MANIFEST.contains("android:foregroundServiceType=\"location\""));
    assert!(MANIFEST.contains("android.permission.FOREGROUND_SERVICE_LOCATION"));
}

#[test]
fn background_starts_never_clear_the_trip_and_tell_the_user() {
    for token in [
        "START_FLAG_REDELIVERY",
        "\"trip_problem\"",
        "\"restart\"",
        "postRestartNotice(",
        "if (!foreground)",
        "TripService.onWatchdog(",
    ] {
        assert!(TRIP_SERVICE.contains(token), "TripService.kt lacks {token}");
    }
    assert_eq!(
        crate::trip_ui::parse_status(r#"{"state":"error","error":"restart"}"#),
        Some(crate::trip_ui::Status::Error(
            "Android closed Stops and tracking paused. It resumes when Stops is open; allow location all the time to avoid this."
        ))
    );
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

#[test]
fn alarm_sound_uses_the_alarm_clock_audio_path() {
    for token in [
        "AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE",
        "abandonAudioFocusRequest",
        "USAGE_ALARM",
        "CONTENT_TYPE_SONIFICATION",
        "STREAM_ALARM",
        "setStreamVolume",
        "SecurityException",
        "setOnErrorListener",
        "ToneGenerator",
        "TONE_CDMA_ALERT_CALL_GUARD",
        "TYPE_ALARM",
        "TYPE_RINGTONE",
        "TYPE_NOTIFICATION",
        "getActualDefaultRingtoneUri",
        "VibrationAttributes.USAGE_ALARM",
        "rovsti_alarm",
        "saved_volume",
    ] {
        assert!(ALARM_SOUND.contains(token), "AlarmSound.kt lacks {token}");
    }
}

#[test]
fn alarm_never_routes_to_one_device_or_the_music_stream() {
    for token in [
        "setPreferredDevice",
        "setCommunicationDevice",
        "STREAM_MUSIC",
    ] {
        assert!(!ALARM_SOUND.contains(token), "AlarmSound.kt has {token}");
        assert!(!TRIP_ALARM.contains(token), "TripAlarm.kt has {token}");
    }
}

#[test]
fn trip_alarm_delegates_to_alarm_sound_and_recovers_volume() {
    assert!(TRIP_ALARM.contains("AlarmSound("));
    assert!(TRIP_ALARM.contains("fun recover("));
    assert!(TRIP_ALARM.contains("AlarmSound.restoreVolume"));
    assert!(TRIP_ALARM.matches("TripAlarm.recover(context)").count() >= 1);
    assert!(TRIP_SERVICE.contains(TRIP_SERVICE_RECOVER));
    let bridge =
        include_str!("../../../crates/rovsti_android/kotlin/dev/rovsti/bridge/RovstiBridge.kt");
    assert!(bridge.contains("TripAlarm.recover("));
    // The old inline player is gone.
    assert!(!TRIP_ALARM.contains("MediaPlayer"));
}

#[test]
fn kotlin_alarm_defaults_equal_the_rust_plan() {
    use rovsti_geo::AlarmPlan;
    let plan = AlarmPlan::default();
    assert!(ALARM_SOUND.contains(&format!("val volumePct: Int = {}", plan.volume_pct)));
    let ramp = format!("val rampMs: Long = {}L", group(plan.ramp_ms));
    assert!(ALARM_SOUND.contains(&ramp), "expected {ramp}");
    let ring = format!("val ringTimeoutMs: Long = {}L", group(plan.ring_timeout_ms));
    assert!(ALARM_SOUND.contains(&ring), "expected {ring}");
    let order: Vec<String> = plan
        .sound_order
        .iter()
        .map(|s| format!("\"{}\"", s.key()))
        .collect();
    assert!(ALARM_SOUND.contains(&format!("listOf({})", order.join(", "))));
    let pattern: Vec<String> = plan.vibrate_pattern_ms.iter().map(u64::to_string).collect();
    assert!(ALARM_SOUND.contains(&format!("longArrayOf({})", pattern.join(", "))));
}

#[test]
fn alarm_lifecycle_has_channel_timeout_guard_and_full_screen_check() {
    for token in [
        "trip_alarm_v2",
        "deleteNotificationChannel",
        "setBypassDnd(true)",
        "canUseFullScreenIntent",
        "\"timeout\"",
        "timed_out",
        "CATEGORY_ALARM",
        "VISIBILITY_PUBLIC",
        "setOnlyAlertOnce(true)",
        "full_screen",
        "guardRingAt",
        "onRingTimeout",
    ] {
        assert!(TRIP_ALARM.contains(token), "TripAlarm.kt lacks {token}");
    }
    let activity =
        include_str!("../../../crates/rovsti_android/kotlin/dev/rovsti/bridge/AlarmActivity.kt");
    for token in [
        "setShowWhenLocked",
        "setTurnScreenOn",
        "FLAG_KEEP_SCREEN_ON",
    ] {
        assert!(activity.contains(token), "AlarmActivity.kt lacks {token}");
    }
}

/// Kotlin digit grouping: 4000 -> "4_000", 600000 -> "600_000".
fn group(n: u64) -> String {
    let s = n.to_string();
    let mut out = String::new();
    for (i, c) in s.chars().enumerate() {
        if i > 0 && (s.len() - i).is_multiple_of(3) {
            out.push('_');
        }
        out.push(c);
    }
    out
}

#[test]
fn digit_grouping_matches_kotlin_literals() {
    assert_eq!(group(80), "80");
    assert_eq!(group(4_000), "4_000");
    assert_eq!(group(600_000), "600_000");
}

#[test]
fn background_location_is_declared_and_mapped() {
    use rovsti_android::permissions::Group;
    assert!(MANIFEST.contains("android.permission.ACCESS_BACKGROUND_LOCATION"));
    let key = format!("\"{}\"", Group::BackgroundLocation.key());
    assert!(BRIDGE.contains(&key), "RovstiBridge.kt lacks {key}");
}
