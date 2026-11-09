//! Guards the Kotlin trip service, alarm and idle location against drifting from the Rust policy.
//! No device is needed: the Kotlin sources of `android/platform` are read as text.
//!
//! Moved from `apps/stops/src/kotlin_contract.rs` (E3 AC4). The assertions on the Stops manifest, on
//! `stops/MainActivity.kt` and on the GPUI `RovstiBridge` stay in that copy until E8.6; E7.1 re-targets the manifest
//! ones at `android/stops/src/main/AndroidManifest.xml`.

use rovsti_geo::poll;

const LOCATION: &str = "../../android/platform/src/main/kotlin/dev/rovsti/platform/location";

macro_rules! kotlin {
    ($name:literal) => {
        include_str!(concat!(
            "../../../android/platform/src/main/kotlin/dev/rovsti/platform/location/",
            $name
        ))
    };
}

/// The manifest components (`TripService`, `TripWatchdogReceiver`, `TripAlarmReceiver`, `AlarmActivity`).
const COMPONENTS: &str = kotlin!("BridgeTripComponents.kt");
/// Pure trip helpers: intervals, watchdog window, spec and answer parsing.
const TRIP_MODEL: &str = kotlin!("TripModel.kt");
const ALARM_SOUND: &str = kotlin!("AlarmSound.kt");
const ALARM_OPTIONS: &str = kotlin!("AlarmOptions.kt");
const ALARM_FILE: &str = kotlin!("TripAlarm.kt");
const LOCATION_REPOSITORY: &str = kotlin!("LocationRepository.kt");
const TRIP_REPOSITORY: &str = kotlin!("TripRepository.kt");
/// Where the ids the Kotlin code names by constant are spelled out.
const PLATFORM_PREFS: &str =
    include_str!("../../../android/platform/src/main/kotlin/dev/rovsti/platform/PlatformPrefs.kt");
const PLATFORM_CHANNELS: &str = include_str!(
    "../../../android/platform/src/main/kotlin/dev/rovsti/platform/notify/PlatformChannels.kt"
);

/// `TripService.kt` of the GPUI bridge, now `BridgeTripComponents.kt` plus `TripModel.kt`.
fn trip_service() -> String {
    format!("{COMPONENTS}\n{TRIP_MODEL}")
}

/// `TripAlarm.kt` of the GPUI bridge, now `TripAlarm.kt` plus the receiver in `BridgeTripComponents.kt`.
fn trip_alarm() -> String {
    format!("{ALARM_FILE}\n{COMPONENTS}")
}

#[test]
fn the_kotlin_sources_are_where_the_contract_reads_them() {
    assert!(LOCATION.ends_with("platform/location"));
    for (name, text) in [
        ("BridgeTripComponents.kt", COMPONENTS),
        ("TripModel.kt", TRIP_MODEL),
        ("TripAlarm.kt", ALARM_FILE),
        ("AlarmSound.kt", ALARM_SOUND),
        ("AlarmOptions.kt", ALARM_OPTIONS),
        ("LocationRepository.kt", LOCATION_REPOSITORY),
        ("TripRepository.kt", TRIP_REPOSITORY),
    ] {
        assert!(text.len() > 200, "{name} is empty");
    }
}

#[test]
fn service_is_a_location_foreground_service() {
    assert!(trip_service().contains("FOREGROUND_SERVICE_TYPE_LOCATION"));
}

#[test]
fn background_starts_never_clear_the_trip_and_tell_the_user() {
    let service = trip_service();
    for token in [
        "START_FLAG_REDELIVERY",
        "PlatformChannels.TRIP_PROBLEM",
        "\"restart\"",
        "postRestartNotice(",
        "if (!foreground)",
        "TripService.onWatchdog(",
    ] {
        assert!(service.contains(token), "the trip service lacks {token}");
    }
}

#[test]
fn updates_are_requested_on_both_api_paths() {
    // LocationRequest (Android 12+) and the legacy overload.
    assert_eq!(trip_service().matches("requestLocationUpdates(").count(), 2);
}

#[test]
fn watchdog_mirrors_the_rust_retry_window() {
    let service = trip_service();
    assert!(service.contains("retry_ms"));
    let max = format!("TRIP_MAX_INTERVAL_MS = {}_000L", poll::MAX_POLL.as_secs());
    assert!(service.contains(&max), "expected {max}");
    assert!(service.contains("coerceIn(5_000L, 30_000L)"));
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
fn a_refused_foreground_start_never_crashes() {
    let service = trip_service();
    for token in [
        "ForegroundServiceStartNotAllowedException",
        "StartResult.NotAllowed",
        "fun startResult(",
    ] {
        assert!(service.contains(token), "the trip service lacks {token}");
    }
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
        "PlatformPrefs.ALARM",
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
        assert!(!trip_alarm().contains(token), "TripAlarm.kt has {token}");
    }
}

#[test]
fn trip_alarm_delegates_to_alarm_sound_and_recovers_volume() {
    let alarm = trip_alarm();
    assert!(alarm.contains("AlarmSound("));
    assert!(alarm.contains("fun recover("));
    assert!(alarm.contains("AlarmSound.restoreVolume"));
    assert!(alarm.matches("TripAlarm.recover(context)").count() >= 1);
    assert!(trip_service().contains("TripAlarm.recover(this)"));
    // The old inline player is gone.
    assert!(!alarm.contains("MediaPlayer"));
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
        "PlatformChannels.TRIP_ALARM",
        "deleteNotificationChannel",
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
        assert!(trip_alarm().contains(token), "TripAlarm.kt lacks {token}");
    }
    let activity = COMPONENTS;
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
fn idle_location_seam_is_in_kotlin() {
    for token in [
        "getLastKnownLocation",
        "getCurrentLocation",
        "requestSingleUpdate",
        "SecurityException",
        "fun lastKnown(",
        "suspend fun fresh(",
        "fun updates(",
        "requestLocationUpdates(",
        "removeUpdates(",
        "2_000L",
        "5f",
    ] {
        assert!(
            LOCATION_REPOSITORY.contains(token),
            "LocationRepository.kt lacks {token}"
        );
    }
    // The old `stops_location` emits are replaced by the flow.
    assert!(!LOCATION_REPOSITORY.contains("emit("));
    assert!(!LOCATION_REPOSITORY.contains("stops_location"));
}

#[test]
fn the_alarm_sound_reads_the_users_choice() {
    assert!(ALARM_SOUND.contains("\"user_sound_uri\""));
    assert!(ALARM_SOUND.contains("plan.userSoundUri"));
    assert!(ALARM_SOUND.contains("fun preview(ms: Long)"));
    assert!(trip_alarm().contains("planOverride: String? = null"));
}

#[test]
fn the_alarm_picker_is_an_alarm_picker_and_answers_once() {
    assert!(ALARM_OPTIONS.contains("RingtoneManager.TYPE_ALARM"));
    assert!(ALARM_OPTIONS.contains("EXTRA_RINGTONE_SHOW_SILENT, false"));
    assert!(ALARM_OPTIONS.contains("REQUEST_ALARM_SOUND = 0x4153"));
    assert!(ALARM_OPTIONS.contains("\"title\""));
    // The picker answer is the `alarm_sound_picked` payload `{uri, title, cancelled}` handed to the caller.
    assert!(ALARM_OPTIONS.contains("fun handle("));
    assert!(ALARM_OPTIONS.contains("\"cancelled\""));
}

#[test]
fn the_ids_behind_the_constants_are_the_gpui_ids() {
    for token in ["ALARM = \"rovsti_alarm\"", "TRIP = \"rovsti_trip\""] {
        assert!(
            PLATFORM_PREFS.contains(token),
            "PlatformPrefs.kt lacks {token}"
        );
    }
    for token in [
        "TRIP_ALARM = \"trip_alarm_v2\"",
        "TRIP_ALARM_OLD = \"trip_alarm\"",
        "TRIP_PROBLEM = \"trip_problem\"",
        "TRIP = \"trip\"",
        "setBypassDnd(true)",
    ] {
        assert!(
            PLATFORM_CHANNELS.contains(token),
            "PlatformChannels.kt lacks {token}"
        );
    }
    assert!(ALARM_SOUND.contains("saved_volume"));
    assert!(trip_service().contains("\"spec\""));
}
