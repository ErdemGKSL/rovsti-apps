package dev.rosty.phone

import dev.rosty.testing.MergedManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ARCH 7.2 / E5.1: the merged manifest of the debug variant of BOTH flavors equals the GPUI one. The permission set is
 * the one committed in `platform/src/test/resources/manifest/phone.permissions.txt`; every component the GPUI manifest
 * declares is here with the same class name, export flag, permission and intent filters. Nothing of `dev.gpui.*` and
 * no vulkan feature survives. The `firebase` flavor adds the FCM service and exactly what the FCM library merges.
 */
class PhoneManifestTest {
    private val manifestFile = File(System.getProperty("rosty.mergedManifest")!!)
    private val manifest = MergedManifest.parse(manifestFile)
    private val flavor = System.getProperty("rosty.flavor")!!
    private val gpui = MergedManifest.parse(File(System.getProperty("rosty.gpuiManifest")!!))
    private val handoff = "dev.rosty.permission.HANDOFF"
    private val pkg = "dev.rosty.phone"

    /** What the FCM library itself merges into any app; the GPUI build got the same when `google-services.json` existed. */
    private val firebaseExtras =
        setOf(
            "uses-permission android.permission.ACCESS_NETWORK_STATE",
            "uses-permission com.google.android.c2dm.permission.RECEIVE",
        )

    // androidx.core adds <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION to every app that uses it.
    private fun isAndroidxInternal(name: String) = name.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

    private fun String.short() =
        if (startsWith(pkg) && length > pkg.length &&
            this[pkg.length] == '.'
        ) {
            removePrefix(pkg)
        } else {
            this
        }

    @Test fun permissionsEqualTheCommittedList() {
        val expected =
            File(System.getProperty("rosty.manifestDir")!!, "phone.permissions.txt")
                .readLines()
                .filter { it.isNotBlank() }
                .toSet()
        val uses = manifest.usesPermissions.filterNot(::isAndroidxInternal).map { "uses-permission $it" }
        val declares =
            manifest.declaredPermissions
                .filterKeys { !isAndroidxInternal(it) }
                .map { (name, level) -> "declares $name $level" }
        // The alias is written relative (".WaHandoff") in the committed list; the merge makes it fully qualified.
        val protects =
            manifest.components
                .filter { it.attributes["permission"] == handoff }
                .map { "protects ${it.kind} ${it.name.short()} $handoff" }
        val actual = (uses + declares + protects).toSet()
        val extra = if (flavor == "firebase") firebaseExtras else emptySet()
        assertEquals(expected + extra, actual)
    }

    @Test fun applicationFlags() {
        val app = manifest.application
        assertEquals("$pkg.RostyPhoneApp", app["name"])
        assertEquals("false", app["allowBackup"])
        assertEquals("true", app["supportsRtl"])
        assertEquals("true", app["enableOnBackInvokedCallback"])
    }

    @Test fun mainActivityKeepsItsFiltersAndFlags() {
        val main = manifest.component("$pkg.MainActivity")
        assertEquals("activity", main.kind)
        assertEquals(true, main.exported)
        assertEquals("singleTop", main.launchMode)
        assertEquals(gpui.component(".MainActivity").attributes["configChanges"], main.attributes["configChanges"])
        assertEquals(4, main.filters.size)

        val launcher = main.filters.single { "android.intent.action.MAIN" in it.actions }
        assertEquals(setOf("android.intent.category.LAUNCHER"), launcher.categories)

        val dials = main.filters.filter { "android.intent.action.DIAL" in it.actions }
        assertEquals(2, dials.size)
        assertEquals(setOf(emptySet(), setOf("tel")), dials.map { it.schemes }.toSet())

        val view = main.filters.single { "android.intent.action.VIEW" in it.actions }
        assertEquals(setOf("tel"), view.schemes)
        assertEquals(setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"), view.categories)
    }

    @Test fun theWhatsAppHandoffAliasIsGuardedBySignature() {
        val alias = manifest.component("$pkg.WaHandoff")
        assertEquals("activity-alias", alias.kind)
        assertEquals("$pkg.MainActivity", alias.attributes["targetActivity"])
        assertEquals(handoff, alias.attributes["permission"])
        assertEquals(true, alias.exported)
        assertEquals(setOf("dev.rosty.action.WA_CALL"), alias.filters.single().actions)
        assertEquals("signature", manifest.declaredPermissions[handoff])
        // WA-R2: Phone also sends one ("Message" opens the chat in Rosty Messages), so it holds the permission.
        assertTrue(handoff in manifest.usesPermissions)
    }

    /** WA-R2: the link hand-over. Exported, but unreachable without the signature permission, in both directions. */
    @Test fun theWhatsAppLinkProviderIsExportedBehindTheSignaturePermission() {
        val permission = "dev.rosty.permission.WA_LINK"
        val provider = manifest.component("dev.rosty.wa.share.WaLinkProvider")
        assertEquals("provider", provider.kind)
        assertEquals(true, provider.exported)
        assertEquals("dev.rosty.phone.walink", provider.attributes["authorities"])
        assertEquals(permission, provider.attributes["readPermission"])
        assertEquals(permission, provider.attributes["writePermission"])
        assertEquals(null, provider.attributes["grantUriPermissions"])
        assertEquals("signature", manifest.declaredPermissions[permission])
        assertTrue("this app asks its siblings too", permission in manifest.usesPermissions)
        assertTrue(
            manifest.queriedProviders.containsAll(
                setOf("dev.rosty.messages.walink", "dev.rosty.contacts.walink", "dev.rosty.stops.walink"),
            ),
        )
    }

    @Test fun theInCallServiceIsTheCallUiOfTheSystem() {
        val service = manifest.component("$pkg.RostyInCallService")
        assertEquals("service", service.kind)
        assertEquals(true, service.exported)
        assertEquals("android.permission.BIND_INCALL_SERVICE", service.attributes["permission"])
        assertEquals("phoneCall", service.attributes["foregroundServiceType"])
        assertEquals("true", service.metaData["android.telecom.IN_CALL_SERVICE_UI"])
        assertEquals(setOf("android.telecom.InCallService"), service.filters.single().actions)
    }

    @Test fun theWhatsAppCallServiceHoldsTheMicrophoneInTheBackground() {
        val service = manifest.component("dev.rosty.platform.wa.WaCallService")
        assertEquals("service", service.kind)
        assertEquals(false, service.exported)
        // Android 14 stops background capture without a foreground service of type microphone.
        assertEquals("microphone|phoneCall", service.attributes["foregroundServiceType"])
        assertTrue("android.permission.FOREGROUND_SERVICE_MICROPHONE" in manifest.usesPermissions)
        assertTrue("android.permission.FOREGROUND_SERVICE_PHONE_CALL" in manifest.usesPermissions)
        assertTrue("android.permission.RECORD_AUDIO" in manifest.usesPermissions)
    }

    /** People sync v2 S11: the sync runs as dataSync next to the call services, and WorkManager starts on demand. */
    @Test fun thePeopleSyncRunsAsADataSyncForegroundServiceBesideTheCallServices() {
        val service = manifest.component("androidx.work.impl.foreground.SystemForegroundService")
        assertEquals("dataSync", service.attributes["foregroundServiceType"])
        assertEquals("phoneCall", manifest.component("$pkg.RostyInCallService").attributes["foregroundServiceType"])
        assertEquals(false, manifest.component("dev.rosty.people.sync.PeopleSyncStopReceiver").exported)
        val startup = manifest.components.firstOrNull { it.name == "androidx.startup.InitializationProvider" }
        assertFalse(
            "WorkManager is not initialised at every process start",
            startup?.metaData?.containsKey("androidx.work.WorkManagerInitializer") == true,
        )
    }

    @Test fun receiversAndTheShareProviderKeepTheirNames() {
        val action = manifest.component("$pkg.CallActionReceiver")
        assertEquals(false, action.exported)

        // The WhatsApp call actions (Answer, Decline, Hang up): only the app's own PendingIntents reach it.
        val waAction = manifest.component("$pkg.wa.WaCallActionReceiver")
        assertEquals("receiver", waAction.kind)
        assertEquals(false, waAction.exported)
        assertTrue(waAction.filters.isEmpty())

        val voicemail = manifest.component("$pkg.VoicemailReceiver")
        assertEquals("false", voicemail.attributes["enabled"])
        assertEquals(true, voicemail.exported)
        assertEquals("android.permission.READ_PHONE_STATE", voicemail.attributes["permission"])
        assertEquals(
            setOf("android.telephony.action.SHOW_VOICEMAIL_NOTIFICATION"),
            voicemail.filters.single().actions,
        )

        val reminders = manifest.component("dev.rosty.bridge.CallReminderReceiver")
        assertEquals(false, reminders.exported)

        // E5.12: the boot receiver puts the reminders back after a reboot, an update or an exact-alarm change.
        val boot = manifest.component("$pkg.BootReceiver")
        assertEquals(false, boot.exported)
        assertEquals(
            setOf(
                "android.intent.action.BOOT_COMPLETED",
                "android.intent.action.MY_PACKAGE_REPLACED",
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            ),
            boot.filters.single().actions,
        )

        val provider = manifest.component("dev.rosty.bridge.RostyShareProvider")
        assertEquals("provider", provider.kind)
        assertEquals(false, provider.exported)
        assertEquals("$pkg.rostyshare", provider.authorities)
        assertEquals("true", provider.attributes["grantUriPermissions"])
    }

    @Test fun packageVisibilityIsExactlyTheThreeIntentsOfTheGpuiApp() {
        val actions = manifest.queries.flatMap { it.actions }.toSet()
        assertEquals(
            setOf(
                "android.telephony.action.EMERGENCY_ASSISTANCE",
                "android.settings.HEARING_DEVICES_SETTINGS",
                "android.telecom.action.SHOW_CALL_SETTINGS",
            ),
            actions,
        )
        assertEquals(gpui.queries.flatMap { it.actions }.toSet(), actions)
    }

    @Test fun everyComponentOfTheGpuiManifestSurvives() {
        for (old in gpui.components) {
            val name = if (old.name.startsWith(".")) pkg + old.name else old.name
            val now = manifest.components.singleOrNull { it.name == name }
            assertEquals("$name is missing", true, now != null)
            now!!
            assertEquals(name, old.kind, now.kind)
            assertEquals(name, old.exported, now.exported)
            assertEquals(name, old.attributes["permission"], now.attributes["permission"])
            assertEquals(name, old.attributes["enabled"], now.attributes["enabled"])
            assertEquals(name, old.filters.toSet(), now.filters.toSet())
        }
    }

    @Test fun onlyTheFirebaseFlavorHasTheMessagingService() {
        val service = manifest.components.firstOrNull { it.name == "$pkg.RostyMessagingService" }
        if (flavor == "firebase") {
            assertEquals("service", service?.kind)
            assertEquals(false, service?.exported)
            assertEquals(setOf("com.google.firebase.MESSAGING_EVENT"), service?.filters?.single()?.actions)
        } else {
            assertNull(service)
        }
    }

    @Test fun noGpuiPieceAndNoVulkanFeatureRemains() {
        val names = manifest.components.map { it.name } + manifest.application.values
        assertFalse(names.any { it.startsWith("dev.gpui") })
        // Comments (the source manifest explains what is gone) are not components.
        val text = manifestFile.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse("dev.gpui" in text)
        assertFalse("vulkan" in text)
    }

    @Test fun theAppDeclaresExactlyItsOwnComponents() {
        val ours =
            manifest.components
                .filter { it.name.startsWith("dev.rosty.") }
                .map { it.name }
                .toSet()
        val expected =
            setOf(
                "$pkg.MainActivity",
                "$pkg.WaHandoff",
                // Round 7b: a WhatsApp call ringing while the vault is locked, over the lock screen.
                "$pkg.wa.WaLockedCallActivity",
                "$pkg.RostyInCallService",
                "dev.rosty.platform.wa.WaCallService",
                "$pkg.CallActionReceiver",
                "$pkg.wa.WaCallActionReceiver",
                "$pkg.VoicemailReceiver",
                "dev.rosty.bridge.CallReminderReceiver",
                "$pkg.BootReceiver",
                "dev.rosty.bridge.RostyShareProvider",
                "dev.rosty.people.sync.PeopleSyncStopReceiver",
                // WA-R2: the link hand-over between Rosty apps.
                "dev.rosty.wa.share.WaLinkProvider",
                // Round 7c: a sibling's "Lock now" reaches this app when it is not running too.
                "dev.rosty.wa.share.WaLockAllReceiver",
            )
        assertEquals(if (flavor == "firebase") expected + "$pkg.RostyMessagingService" else expected, ours)
        assertTrue(manifest.component("$pkg.MainActivity").filters.isNotEmpty())
    }

    /** Round 7c: "Lock now" in a sibling reaches this app even when it is not running, and only from a sibling. */
    @Test fun theLockAllReceiverIsExportedBehindTheSignaturePermission() {
        val receiver = manifest.component("dev.rosty.wa.share.WaLockAllReceiver")
        assertEquals("receiver", receiver.kind)
        assertEquals(true, receiver.exported)
        assertEquals("dev.rosty.permission.WA_LINK", receiver.attributes["permission"])
        assertEquals(setOf("dev.rosty.action.WA_LOCK_ALL"), receiver.filters.single().actions)
    }
}
