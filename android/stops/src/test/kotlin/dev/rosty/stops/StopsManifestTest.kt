package dev.rosty.stops

import dev.rosty.testing.MergedManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ARCH 7.2 / E7.1 AC1: the merged manifest of the release variant equals the GPUI one: the permission set committed in
 * `platform/src/test/resources/manifest/stops.permissions.txt`, the components with their FQCNs (the `dev.rosty.bridge`
 * trip classes keep their names, so alarms and the service armed by the GPUI build still resolve), the `geo:`, map-link
 * and SEND filters, and the application flags. Nothing of `dev.gpui.*` survives.
 */
class StopsManifestTest {
    private val manifest = MergedManifest.parse(File(System.getProperty("rosty.mergedManifest")!!))

    @Test fun permissionsEqualTheCommittedList() {
        val expected =
            File(System.getProperty("rosty.manifestDir")!!, "stops.permissions.txt")
                .readLines()
                .filter { it.isNotBlank() }
                .sorted()
        val uses = manifest.usesPermissions.filterNot(::isAndroidxInternal).map { "uses-permission $it" }
        val declares =
            manifest.declaredPermissions
                .filterKeys { !isAndroidxInternal(it) }
                .map { (name, level) -> "declares $name $level" }
        assertEquals(expected, (uses + declares).sorted())
    }

    // androidx.core adds <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION to every app that uses it.
    private fun isAndroidxInternal(name: String) = name.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

    @Test fun applicationFlags() {
        val app = manifest.application
        assertEquals("dev.rosty.stops.RostyStopsApp", app["name"])
        assertEquals("false", app["allowBackup"])
        assertEquals("true", app["supportsRtl"])
        assertEquals("true", app["enableOnBackInvokedCallback"])
        assertEquals("@string/launcher_name", app["label"])
        assertEquals("@mipmap/ic_launcher", app["icon"])
    }

    @Test fun mainActivityHasTheLauncherGeoMapLinkAndShareFilters() {
        val main = manifest.component("dev.rosty.stops.MainActivity")
        assertEquals("activity", main.kind)
        assertEquals(true, main.exported)
        assertEquals("singleTop", main.launchMode)
        assertEquals("adjustResize", main.attributes["windowSoftInputMode"])
        assertEquals(7, main.filters.size)

        val launcher = main.filters.single { "android.intent.action.MAIN" in it.actions }
        assertEquals(setOf("android.intent.category.LAUNCHER"), launcher.categories)

        val views = main.filters.filter { "android.intent.action.VIEW" in it.actions }
        assertEquals(5, views.size)
        for (view in views) {
            assertEquals(
                setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"),
                view.categories,
            )
        }
        assertEquals(1, views.count { it.schemes == setOf("geo") })
        assertEquals(4, views.count { it.schemes == setOf("https") })

        val send = main.filters.single { "android.intent.action.SEND" in it.actions }
        assertEquals(setOf("text/plain"), send.mimeTypes)
        assertEquals(setOf("android.intent.category.DEFAULT"), send.categories)
    }

    @Test fun theMapLinkHostsAreTheGpuiOnes() {
        val text = File(System.getProperty("rosty.mergedManifest")!!).readText()
        for (host in listOf("maps.google.com", "www.google.com", "www.openstreetmap.org", "maps.apple.com")) {
            assertTrue("$host filter", text.contains("android:host=\"$host\""))
        }
        assertTrue(text.contains("android:pathPrefix=\"/maps\""))
        assertFalse("no autoVerify: the user picks Stops", text.contains("android:autoVerify"))
    }

    @Test fun theTripRunsAsALocationForegroundService() {
        val service = manifest.component("dev.rosty.bridge.TripService")
        assertEquals("service", service.kind)
        assertEquals(false, service.exported)
        assertEquals("location", service.attributes["foregroundServiceType"])
        assertTrue("android.permission.FOREGROUND_SERVICE_LOCATION" in manifest.usesPermissions)
        assertTrue("android.permission.ACCESS_BACKGROUND_LOCATION" in manifest.usesPermissions)
        for (name in listOf("dev.rosty.bridge.TripWatchdogReceiver", "dev.rosty.bridge.TripAlarmReceiver")) {
            val receiver = manifest.component(name)
            assertEquals("receiver", receiver.kind)
            assertEquals(false, receiver.exported)
        }
    }

    /** E7.10 (S10): the offline area download runs in WorkManager's foreground service as dataSync. */
    @Test fun theOfflineDownloadRunsAsADataSyncForegroundService() {
        val service = manifest.component("androidx.work.impl.foreground.SystemForegroundService")
        assertEquals("dataSync", service.attributes["foregroundServiceType"])
        assertTrue("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in manifest.usesPermissions)
        val cancel = manifest.component("dev.rosty.stops.offline.OfflineCancelReceiver")
        assertEquals("receiver", cancel.kind)
        assertEquals(false, cancel.exported)
    }

    @Test fun theAlarmShowsOverTheLockScreen() {
        val alarm = manifest.component("dev.rosty.bridge.AlarmActivity")
        assertEquals("activity", alarm.kind)
        assertEquals(false, alarm.exported)
        assertEquals("true", alarm.attributes["showWhenLocked"])
        assertEquals("true", alarm.attributes["turnScreenOn"])
        assertEquals("true", alarm.attributes["excludeFromRecents"])
        assertEquals("singleInstance", alarm.launchMode)
    }

    @Test fun theBootReceiverListensForBootOnly() {
        val boot = manifest.component("dev.rosty.stops.BootReceiver")
        assertEquals("receiver", boot.kind)
        assertEquals(false, boot.exported)
        assertEquals(setOf("android.intent.action.BOOT_COMPLETED"), boot.filters.single().actions)
    }

    /** Round 7a: the link hand-over. Exported, but unreachable without the signature permission, in both directions. */
    @Test fun theWhatsAppLinkProviderIsExportedBehindTheSignaturePermission() {
        val permission = "dev.rosty.permission.WA_LINK"
        val provider = manifest.component("dev.rosty.wa.share.WaLinkProvider")
        assertEquals("provider", provider.kind)
        assertEquals(true, provider.exported)
        assertEquals("dev.rosty.stops.walink", provider.attributes["authorities"])
        assertEquals(permission, provider.attributes["readPermission"])
        assertEquals(permission, provider.attributes["writePermission"])
        assertEquals("signature", manifest.declaredPermissions[permission])
        assertTrue("this app asks its siblings too", permission in manifest.usesPermissions)
        assertTrue(
            manifest.queriedProviders.containsAll(
                setOf("dev.rosty.messages.walink", "dev.rosty.phone.walink", "dev.rosty.contacts.walink"),
            ),
        )
    }

    @Test fun theShareProviderKeepsItsBridgeName() {
        val provider = manifest.component("dev.rosty.bridge.RostyShareProvider")
        assertEquals("provider", provider.kind)
        assertEquals(false, provider.exported)
        assertEquals("dev.rosty.stops.rostyshare", provider.authorities)
        assertEquals("true", provider.attributes["grantUriPermissions"])
    }

    @Test fun noGpuiPieceRemains() {
        val names = manifest.components.map { it.name } + manifest.application.values
        assertFalse(names.any { it.startsWith("dev.gpui") })
        assertFalse(File(System.getProperty("rosty.mergedManifest")!!).readText().contains("android.hardware.vulkan"))
    }

    @Test fun onlyTheGpuiComponentsAreDeclared() {
        val ours =
            manifest.components
                .filter { it.name.startsWith("dev.rosty.") }
                .map { it.name }
                .toSet()
        assertEquals(
            setOf(
                "dev.rosty.stops.MainActivity",
                "dev.rosty.stops.BootReceiver",
                "dev.rosty.bridge.TripService",
                "dev.rosty.bridge.TripWatchdogReceiver",
                "dev.rosty.bridge.AlarmActivity",
                "dev.rosty.bridge.TripAlarmReceiver",
                "dev.rosty.bridge.RostyShareProvider",
                // E7.10 (S10): the offline download notification's Cancel.
                "dev.rosty.stops.offline.OfflineCancelReceiver",
                // Round 7a: the WhatsApp link hand-over (the one unlock prompt of the four apps).
                "dev.rosty.wa.share.WaLinkProvider",
            ),
            ours,
        )
    }
}
