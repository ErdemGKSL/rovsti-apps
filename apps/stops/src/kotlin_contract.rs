//! Guards the Kotlin trip service against drifting from the Rust polling policy.
//! No device is needed: the Kotlin source and the manifest are read as text.

const TRIP_SERVICE: &str =
    include_str!("../../../crates/rosty_android/kotlin/dev/rosty/bridge/TripService.kt");
const TRIP_ALARM: &str =
    include_str!("../../../crates/rosty_android/kotlin/dev/rosty/bridge/TripAlarm.kt");
const BRIDGE: &str =
    include_str!("../../../crates/rosty_android/kotlin/dev/rosty/bridge/RostyBridge.kt");
const ALARM_OPTIONS: &str =
    include_str!("../../../crates/rosty_android/kotlin/dev/rosty/bridge/AlarmOptions.kt");
const STOPS_LOCATION: &str =
    include_str!("../../../crates/rosty_android/kotlin/dev/rosty/bridge/StopsLocation.kt");
const SHORTCUTS: &str =
    include_str!("../../../crates/rosty_android/kotlin/dev/rosty/bridge/Shortcuts.kt");
const MAIN_ACTIVITY: &str =
    include_str!("../platforms/android/app/src/main/kotlin/dev/rosty/stops/MainActivity.kt");
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
fn trip_alarm_delegates_to_alarm_sound_and_recovers_volume() {
    assert!(TRIP_ALARM.contains("AlarmSound("));
    assert!(TRIP_ALARM.contains("fun recover("));
    assert!(TRIP_ALARM.contains("AlarmSound.restoreVolume"));
    assert!(TRIP_ALARM.matches("TripAlarm.recover(context)").count() >= 1);
    assert!(TRIP_SERVICE.contains(TRIP_SERVICE_RECOVER));
    let bridge =
        include_str!("../../../crates/rosty_android/kotlin/dev/rosty/bridge/RostyBridge.kt");
    assert!(bridge.contains("TripAlarm.recover("));
    // The old inline player is gone.
    assert!(!TRIP_ALARM.contains("MediaPlayer"));
}

#[test]
fn background_location_is_declared_and_mapped() {
    use rosty_android::permissions::Group;
    assert!(MANIFEST.contains("android.permission.ACCESS_BACKGROUND_LOCATION"));
    let key = format!("\"{}\"", Group::BackgroundLocation.key());
    assert!(BRIDGE.contains(&key), "RostyBridge.kt lacks {key}");
}

#[test]
fn idle_location_seam_is_in_kotlin() {
    for token in [
        "getLastKnownLocation",
        "getCurrentLocation",
        "requestSingleUpdate",
        "\"stops_location\"",
        "SecurityException",
    ] {
        assert!(
            STOPS_LOCATION.contains(token),
            "StopsLocation.kt lacks {token}"
        );
    }
    assert!(BRIDGE.contains("fun lastLocation(): String"));
    assert!(BRIDGE.contains("fun locateOnce(): Boolean"));
    assert_eq!(
        rosty_android::events::kind::STOPS_LOCATION,
        "stops_location"
    );
}

#[test]
fn share_provider_is_declared_like_the_phone_app() {
    for token in [
        "dev.rosty.bridge.RostyShareProvider",
        "${applicationId}.rostyshare",
        "android:exported=\"false\"",
        "android:grantUriPermissions=\"true\"",
    ] {
        assert!(MANIFEST.contains(token), "manifest lacks {token}");
    }
    let share = include_str!("../../../crates/rosty_android/kotlin/dev/rosty/bridge/MediaShare.kt");
    assert!(share.contains("\"${context.packageName}.rostyshare\""));
}

#[test]
fn the_bridge_has_the_alarm_option_functions() {
    for name in [
        "fun tripTestAlarm(",
        "fun pickAlarmSound(",
        "fun previewVibration(",
        "fun previewAlarmSound(",
    ] {
        assert!(BRIDGE.contains(name), "RostyBridge.kt lacks {name}");
    }
}

#[test]
fn the_alarm_picker_is_an_alarm_picker_and_answers_once() {
    assert!(ALARM_OPTIONS.contains("RingtoneManager.TYPE_ALARM"));
    assert!(ALARM_OPTIONS.contains("EXTRA_RINGTONE_SHOW_SILENT, false"));
    assert!(ALARM_OPTIONS.contains("Documents.REQUEST_ALARM_SOUND"));
    assert!(ALARM_OPTIONS.contains("\"title\""));
    assert!(BRIDGE.contains("AlarmOptions.handle("));
    assert!(BRIDGE.contains(&format!(
        "postOneShot(\"{}\"",
        rosty_android::events::kind::ALARM_SOUND_PICKED
    )));
}

#[test]
fn shortcut_and_live_location_names_match_rust() {
    for token in [
        "const val EXTRA_STOP_ID = \"dev.rosty.stops.STOP_ID\"",
        "fun pinStop(",
    ] {
        assert!(SHORTCUTS.contains(token), "Shortcuts.kt lacks {token}");
    }
    for token in [
        "fun pinStopShortcut(",
        "fun deliverOpenStop(",
        "fun watchLocation(",
        "postOneShot(\"open_stop\"",
    ] {
        assert!(BRIDGE.contains(token), "RostyBridge.kt lacks {token}");
    }
    assert_eq!(rosty_android::events::kind::OPEN_STOP, "open_stop");
    for token in [
        "fun watch(",
        "requestLocationUpdates(",
        "removeUpdates(",
        "2_000L",
        "5f",
    ] {
        assert!(
            STOPS_LOCATION.contains(token),
            "StopsLocation.kt lacks {token}"
        );
    }
    for token in [
        "RostyBridge.deliverOpenStop(intent)",
        "override fun onNewIntent",
        "RostyBridge.watchLocation(false)",
        "override fun onPause",
    ] {
        assert!(
            MAIN_ACTIVITY.contains(token),
            "MainActivity.kt lacks {token}"
        );
    }
}
