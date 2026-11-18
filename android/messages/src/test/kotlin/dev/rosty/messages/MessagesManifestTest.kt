package dev.rosty.messages

import dev.rosty.testing.MergedManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ARCH 7.2 / E6.1 AC1: the merged manifest of the release variant of BOTH flavors keeps everything the GPUI one had.
 * The permission set is the one committed in `platform/src/test/resources/manifest/messages.permissions.txt`; every
 * component the GPUI manifest declares (the four SMS-role components among them) is here with the same class name,
 * export flag, permission and intent filters. Nothing of `dev.gpui.*` and no vulkan feature survives. The `firebase`
 * flavor adds the FCM service and exactly what the FCM library merges.
 */
class MessagesManifestTest {
    private val manifestFile = File(System.getProperty("rosty.mergedManifest")!!)
    private val manifest = MergedManifest.parse(manifestFile)
    private val flavor = System.getProperty("rosty.flavor")!!
    private val gpui = MergedManifest.parse(File(System.getProperty("rosty.gpuiManifest")!!))
    private val handoff = "dev.rosty.permission.HANDOFF"
    private val pkg = "dev.rosty.messages"

    /** What the FCM library itself merges into any app (ACCESS_NETWORK_STATE is in the list already). */
    private val firebaseExtras = setOf("uses-permission com.google.android.c2dm.permission.RECEIVE")

    // androidx.core adds <applicationId>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION to every app that uses it.
    private fun isAndroidxInternal(name: String) = name.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")

    private fun String.short() = if (startsWith("$pkg.")) removePrefix(pkg) else this

    @Test fun permissionsEqualTheCommittedList() {
        val expected =
            File(System.getProperty("rosty.manifestDir")!!, "messages.permissions.txt")
                .readLines()
                .filter { it.isNotBlank() }
                .toSet()
        val uses = manifest.usesPermissions.filterNot(::isAndroidxInternal).map { "uses-permission $it" }
        val declares =
            manifest.declaredPermissions
                .filterKeys { !isAndroidxInternal(it) }
                .map { (name, level) -> "declares $name $level" }
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
        assertEquals("$pkg.RostyMessagesApp", app["name"])
        assertEquals("false", app["allowBackup"])
        assertEquals("true", app["supportsRtl"])
        assertEquals("true", app["enableOnBackInvokedCallback"])
    }

    @Test fun mainActivityKeepsItsFiltersAndDocksToTheKeyboard() {
        val main = manifest.component("$pkg.MainActivity")
        assertEquals(true, main.exported)
        assertEquals("singleTop", main.launchMode)
        assertEquals("adjustResize", main.attributes["windowSoftInputMode"])
        assertEquals(gpui.component(".MainActivity").attributes["configChanges"], main.attributes["configChanges"])
        assertEquals(gpui.component(".MainActivity").filters.toSet(), main.filters.toSet() - sharesOf(main))
        // E6-WA R3: several photos or videos shared at once (to a WhatsApp chat's send preview).
        val several = sharesOf(main).single()
        assertEquals(setOf("image/*", "video/*"), several.mimeTypes.toSet())
        val sendTo = main.filters.single { "android.intent.action.SENDTO" in it.actions }
        assertEquals(setOf("sms", "smsto", "mms", "mmsto"), sendTo.schemes)
    }

    @Test fun theSmsRoleComponentsAreAllDeclared() {
        val deliver = manifest.component("$pkg.SmsDeliverReceiver")
        assertEquals("android.permission.BROADCAST_SMS", deliver.attributes["permission"])
        assertEquals(setOf("android.provider.Telephony.SMS_DELIVER"), deliver.filters.single().actions)
        val wap = manifest.component("$pkg.MmsWapPushReceiver")
        assertEquals("android.permission.BROADCAST_WAP_PUSH", wap.attributes["permission"])
        val respond = manifest.component("$pkg.HeadlessSmsSendService")
        assertEquals("android.permission.SEND_RESPOND_VIA_MESSAGE", respond.attributes["permission"])
        assertTrue(manifest.component("$pkg.MainActivity").filters.any { "android.intent.action.SENDTO" in it.actions })
    }

    @Test fun theWhatsAppHandoffAliasIsGuardedBySignature() {
        val alias = manifest.component("$pkg.WaHandoff")
        assertEquals("activity-alias", alias.kind)
        assertEquals("$pkg.MainActivity", alias.attributes["targetActivity"])
        assertEquals(handoff, alias.attributes["permission"])
        assertEquals(setOf("dev.rosty.action.WA_CHAT"), alias.filters.single().actions)
        assertEquals("signature", manifest.declaredPermissions[handoff])
    }

    /** WA-R2: the link hand-over. Exported, but unreachable without the signature permission, in both directions. */
    @Test fun theWhatsAppLinkProviderIsExportedBehindTheSignaturePermission() {
        val permission = "dev.rosty.permission.WA_LINK"
        val provider = manifest.component("dev.rosty.wa.share.WaLinkProvider")
        assertEquals("provider", provider.kind)
        assertEquals(true, provider.exported)
        assertEquals("dev.rosty.messages.walink", provider.attributes["authorities"])
        assertEquals(permission, provider.attributes["readPermission"])
        assertEquals(permission, provider.attributes["writePermission"])
        assertEquals(null, provider.attributes["grantUriPermissions"])
        assertEquals("signature", manifest.declaredPermissions[permission])
        assertTrue("this app asks its siblings too", permission in manifest.usesPermissions)
        assertTrue(
            manifest.queriedProviders.containsAll(
                setOf("dev.rosty.phone.walink", "dev.rosty.contacts.walink", "dev.rosty.stops.walink"),
            ),
        )
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
            // The one filter added since (E6-WA R3) is checked in mainActivityKeepsItsFiltersAndDocksToTheKeyboard.
            assertEquals(name, old.filters.toSet(), now.filters.toSet() - sharesOf(now))
        }
        assertEquals("$pkg.mmsfiles", manifest.component("dev.rosty.bridge.MmsFileProvider").authorities)
    }

    @Test fun onlyTheFirebaseFlavorHasTheMessagingService() {
        val service = manifest.components.firstOrNull { it.name == "$pkg.RostyMessagingService" }
        if (flavor == "firebase") {
            assertEquals(false, service?.exported)
            assertEquals(setOf("com.google.firebase.MESSAGING_EVENT"), service?.filters?.single()?.actions)
        } else {
            assertNull(service)
        }
    }

    @Test fun noGpuiPieceAndNoVulkanFeatureRemains() {
        val names = manifest.components.map { it.name } + manifest.application.values
        assertFalse(names.any { it.startsWith("dev.gpui") })
        val text = manifestFile.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertFalse("dev.gpui" in text)
        assertFalse("vulkan" in text)
    }

    /** The SEND_MULTIPLE filters of [component], which the GPUI app did not have. */
    private fun sharesOf(component: MergedManifest.Component) =
        component.filters.filter { "android.intent.action.SEND_MULTIPLE" in it.actions }.toSet()
}
