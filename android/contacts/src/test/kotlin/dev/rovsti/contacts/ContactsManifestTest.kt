package dev.rovsti.contacts

import dev.rovsti.testing.MergedManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ARCH 7.2 / E4.1: the merged manifest of the debug variant equals the GPUI one: the permission set committed in
 * `platform/src/test/resources/manifest/contacts.permissions.txt`, the components with their FQCNs, the vCard filters,
 * the WhatsApp `<queries>` and the application flags. Nothing of `dev.gpui.*` survives.
 */
class ContactsManifestTest {
    private val manifest = MergedManifest.parse(File(System.getProperty("rovsti.mergedManifest")!!))
    private val handoff = "dev.rovsti.permission.HANDOFF"

    private val vcardTypes = setOf("text/x-vcard", "text/vcard", "text/directory")

    @Test fun permissionsEqualTheCommittedList() {
        val expected =
            File(System.getProperty("rovsti.manifestDir")!!, "contacts.permissions.txt")
                .readLines()
                .filter { it.isNotBlank() }
                .sorted()
        val uses = manifest.usesPermissions.filterNot(::isAndroidxInternal).map { "uses-permission $it" }
        val declares =
            manifest.declaredPermissions
                .filterKeys { !isAndroidxInternal(it) }
                .map { (name, level) -> "declares $name $level" }
        val protects =
            manifest.components
                .filter { it.attributes["permission"] == handoff }
                .map { "protects ${it.kind} ${it.name} $handoff" }
        assertEquals(expected, (uses + declares + protects).sorted())
    }

    // The four WorkManager adds (ACCESS_NETWORK_STATE, FOREGROUND_SERVICE, RECEIVE_BOOT_COMPLETED, WAKE_LOCK) come from
    // work-runtime for the people sync (X.1b) and are in the committed list.
    // androidx.core adds <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION to every app that uses it.
    private fun isAndroidxInternal(name: String) = name.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

    @Test fun thePeopleProviderIsExportedBehindTheSignaturePermission() {
        val permission = "dev.rovsti.permission.READ_PEOPLE"
        val provider = manifest.component("dev.rovsti.contacts.people.PeopleProvider")
        assertEquals("provider", provider.kind)
        assertEquals(true, provider.exported)
        assertEquals("dev.rovsti.contacts.people", provider.attributes["authorities"])
        assertEquals(permission, provider.attributes["readPermission"])
        assertEquals(permission, provider.attributes["writePermission"])
        assertEquals("signature", manifest.declaredPermissions[permission])
        assertFalse("the owner of the provider needs no uses-permission", permission in manifest.usesPermissions)
    }

    @Test fun workManagerHasItsComponentsAndTheSyncWorkerNeedsNoneOfItsOwn() {
        // The worker is created by name from WorkManager's database, not declared; WorkManager's own services merge in.
        assertTrue(manifest.components.any { it.name == "androidx.work.impl.background.systemjob.SystemJobService" })
        assertFalse(manifest.components.any { it.name.endsWith("PeopleSyncWorker") })
    }

    @Test fun applicationFlags() {
        val app = manifest.application
        assertEquals("dev.rovsti.contacts.RovstiContactsApp", app["name"])
        assertEquals("false", app["allowBackup"])
        assertEquals("true", app["supportsRtl"])
        assertEquals("true", app["enableOnBackInvokedCallback"])
    }

    @Test fun mainActivityIsSingleTopWithLauncherAndTheTwoVcardFilters() {
        val main = manifest.component("dev.rovsti.contacts.MainActivity")
        assertEquals("activity", main.kind)
        assertEquals(true, main.exported)
        assertEquals("singleTop", main.launchMode)
        assertEquals("adjustResize", main.attributes["windowSoftInputMode"])
        assertEquals(3, main.filters.size)

        val launcher = main.filters.single { "android.intent.action.MAIN" in it.actions }
        assertEquals(setOf("android.intent.category.LAUNCHER"), launcher.categories)

        val send = main.filters.single { "android.intent.action.SEND" in it.actions }
        assertEquals(vcardTypes, send.mimeTypes)
        assertEquals(setOf("android.intent.category.DEFAULT"), send.categories)

        val view = main.filters.single { "android.intent.action.VIEW" in it.actions }
        assertEquals(vcardTypes, view.mimeTypes)
        assertEquals(setOf("content"), view.schemes)
    }

    @Test fun receiverAndShareProviderKeepTheirBridgeNames() {
        val receiver = manifest.component("dev.rovsti.bridge.BirthdayReminderReceiver")
        assertEquals("receiver", receiver.kind)
        assertEquals(false, receiver.exported)

        val provider = manifest.component("dev.rovsti.bridge.RovstiShareProvider")
        assertEquals("provider", provider.kind)
        assertEquals(false, provider.exported)
        assertEquals("dev.rovsti.contacts.rovstishare", provider.authorities)
        assertEquals("true", provider.attributes["grantUriPermissions"])
    }

    @Test fun handoffIsASignaturePermissionAndWhatsAppIntentsAreQueried() {
        assertEquals("signature", manifest.declaredPermissions[handoff])
        assertTrue(handoff in manifest.usesPermissions)
        val actions = manifest.queries.flatMap { it.actions }.toSet()
        assertEquals(setOf("dev.rovsti.action.WA_CALL", "dev.rovsti.action.WA_CHAT"), actions)
    }

    @Test fun noGpuiPieceRemains() {
        val names = manifest.components.map { it.name } + manifest.application.values
        assertFalse(names.any { it.startsWith("dev.gpui") })
        assertNull("no vulkan feature is declared any more", manifest.application["vulkan"])
    }

    @Test fun onlyTheOneActivityIsDeclaredByTheApp() {
        val ours =
            manifest.components
                .filter { it.name.startsWith("dev.rovsti.") }
                .map { it.name }
                .toSet()
        assertEquals(
            setOf(
                "dev.rovsti.contacts.MainActivity",
                "dev.rovsti.contacts.people.PeopleProvider",
                "dev.rovsti.bridge.BirthdayReminderReceiver",
                "dev.rovsti.bridge.RovstiShareProvider",
            ),
            ours,
        )
    }
}
