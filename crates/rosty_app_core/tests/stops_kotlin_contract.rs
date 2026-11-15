//! E7.1 AC4 (E3 AC4): the manifest and `MainActivity` assertions that stayed in
//! `apps/stops/src/kotlin_contract.rs`, re-targeted at the Kotlin Stops app
//! (`android/stops/src/main/AndroidManifest.xml` and `dev.rosty.stops.MainActivity`).
//! No device is needed: the sources are read as text. The trip service, alarm and
//! location seams themselves are guarded by `kotlin_contract.rs` beside this file.

macro_rules! stops {
    ($path:literal) => {
        include_str!(concat!("../../../android/stops/src/main/", $path))
    };
}

const MANIFEST: &str = stops!("AndroidManifest.xml");
const MAIN_ACTIVITY: &str = stops!("kotlin/dev/rosty/stops/MainActivity.kt");
const ROUTES: &str = stops!("kotlin/dev/rosty/stops/StopsRoute.kt");
const BOOT_RECEIVER: &str = stops!("kotlin/dev/rosty/stops/BootReceiver.kt");
const SHORTCUTS: &str = include_str!(
    "../../../android/platform/src/main/kotlin/dev/rosty/platform/contacts/Shortcuts.kt"
);
const SHARE_PROVIDER: &str = include_str!(
    "../../../android/platform/src/main/kotlin/dev/rosty/platform/files/RostyShareProvider.kt"
);
const MEDIA_SHARE: &str = include_str!(
    "../../../android/platform/src/main/kotlin/dev/rosty/platform/files/MediaShare.kt"
);
const PERMISSION_GROUP: &str = include_str!(
    "../../../android/platform/src/main/kotlin/dev/rosty/platform/system/permissions/PermissionGroup.kt"
);

#[test]
fn service_is_a_location_foreground_service() {
    for token in [
        "android:name=\"dev.rosty.bridge.TripService\"",
        "android:foregroundServiceType=\"location\"",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_LOCATION",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.POST_NOTIFICATIONS",
    ] {
        assert!(MANIFEST.contains(token), "manifest lacks {token}");
    }
}

#[test]
fn background_location_is_declared_and_mapped() {
    assert!(MANIFEST.contains("android.permission.ACCESS_BACKGROUND_LOCATION"));
    // The group key the GPUI bridge used (`rosty_android::permissions::Group::BackgroundLocation`).
    assert!(
        PERMISSION_GROUP.contains("BACKGROUND_LOCATION(\"background_location\""),
        "PermissionGroup.kt lacks the background_location group"
    );
}

#[test]
fn the_trip_components_keep_their_bridge_names() {
    for token in [
        "android:name=\"dev.rosty.bridge.TripWatchdogReceiver\"",
        "android:name=\"dev.rosty.bridge.AlarmActivity\"",
        "android:name=\"dev.rosty.bridge.TripAlarmReceiver\"",
        "android:showWhenLocked=\"true\"",
        "android:turnScreenOn=\"true\"",
        "android:launchMode=\"singleInstance\"",
        "android:name=\".BootReceiver\"",
        "android.intent.action.BOOT_COMPLETED",
        "android.permission.USE_EXACT_ALARM",
        "android.permission.USE_FULL_SCREEN_INTENT",
        "android.permission.RECEIVE_BOOT_COMPLETED",
    ] {
        assert!(MANIFEST.contains(token), "manifest lacks {token}");
    }
    // A reboot ends the stale trip the way the GPUI receiver did.
    for token in [
        "Intent.ACTION_BOOT_COMPLETED",
        "\"trip_end\"",
        "\"reason\", \"cancelled\"",
        "goAsync()",
    ] {
        assert!(
            BOOT_RECEIVER.contains(token),
            "BootReceiver.kt lacks {token}"
        );
    }
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
    assert!(SHARE_PROVIDER.contains("class RostyShareProvider"));
    assert!(MEDIA_SHARE.contains(".rostyshare"));
}

#[test]
fn geo_map_link_and_share_intents_reach_the_activity() {
    for token in [
        "android:scheme=\"geo\"",
        "android:host=\"maps.google.com\"",
        "android:host=\"www.google.com\"",
        "android:pathPrefix=\"/maps\"",
        "android:host=\"www.openstreetmap.org\"",
        "android:host=\"maps.apple.com\"",
        "android.intent.action.SEND",
        "android:mimeType=\"text/plain\"",
        "android.intent.category.BROWSABLE",
    ] {
        assert!(MANIFEST.contains(token), "manifest lacks {token}");
    }
    assert!(
        !MANIFEST.contains("android:autoVerify"),
        "the user picks Stops in the chooser"
    );
    for host in [
        "\"maps.google.com\"",
        "\"www.openstreetmap.org\"",
        "\"maps.apple.com\"",
        "\"www.google.com\"",
        "\"/maps\"",
        "scheme == \"geo\"",
        "Intent.EXTRA_TEXT",
    ] {
        assert!(ROUTES.contains(host), "StopsRoute.kt lacks {host}");
    }
}

#[test]
fn shortcut_names_match_the_gpui_ones() {
    for token in [
        "const val EXTRA_STOP_ID = \"dev.rosty.stops\" + \".STOP_ID\"",
        "fun pinStop(",
    ] {
        assert!(SHORTCUTS.contains(token), "Shortcuts.kt lacks {token}");
    }
    // The GPUI `deliverOpenStop`: the extra becomes the map's link and is removed so it is used once.
    for token in [
        "class MainActivity : RostyShellActivity<StopsRoute>()",
        "override fun routeFor(intent: Intent)",
        "stopsLinkFor(intent.action, intent.dataString, intent.extras)",
        "container.pendingLink.value",
        "intent.removeExtra(Shortcuts.EXTRA_STOP_ID)",
    ] {
        assert!(
            MAIN_ACTIVITY.contains(token),
            "MainActivity.kt lacks {token}"
        );
    }
    assert!(ROUTES.contains("Shortcuts.EXTRA_STOP_ID"));
}

#[test]
fn the_activity_is_the_launcher_and_has_no_gpui_host() {
    for token in [
        "android:name=\".MainActivity\"",
        "android.intent.category.LAUNCHER",
        "android:launchMode=\"singleTop\"",
        "android:enableOnBackInvokedCallback=\"true\"",
        "android:windowSoftInputMode=\"adjustResize\"",
        "android:allowBackup=\"false\"",
        "android:label=\"@string/launcher_name\"",
    ] {
        assert!(MANIFEST.contains(token), "manifest lacks {token}");
    }
    assert!(!MANIFEST.contains("android:name=\"dev.gpui"));
    assert!(!MANIFEST.contains("android.hardware.vulkan"));
    assert!(!MAIN_ACTIVITY.contains("GpuiActivity"));
}
