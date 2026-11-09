package dev.rovsti.platform.system.permissions

import android.Manifest
import android.content.Context
import android.content.Intent
import dev.rovsti.platform.PlatformEvents
import dev.rovsti.platform.PlatformPrefs
import dev.rovsti.platform.system.FakeSpecialAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PermissionControllerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val special = FakeSpecialAccess()
    private val prefs = context.getSharedPreferences(PlatformPrefs.BRIDGE, Context.MODE_PRIVATE)
    private var rationale = false
    private lateinit var controller: PermissionController

    @Before fun setUp() {
        prefs.edit().clear().commit()
        controller = PermissionController(context, AppPermissions.contacts, special, prefs)
    }

    private fun refresh() = controller.refreshNow { rationale }

    private fun state(g: PermissionGroup) = controller.state(g).value

    private fun grant(vararg p: String) = shadowOf(context as android.app.Application).grantPermissions(*p)

    private fun deny(vararg p: String) = shadowOf(context as android.app.Application).denyPermissions(*p)

    @Test fun aFreshGroupIsNeverAsked() {
        refresh()
        assertEquals(PermissionState.NeverAsked, state(PermissionGroup.CONTACTS))
    }

    @Test fun grantingBothContactPermissionsGrantsTheGroup() {
        grant(Manifest.permission.READ_CONTACTS)
        refresh()
        assertEquals("one of two is not enough", PermissionState.NeverAsked, state(PermissionGroup.CONTACTS))
        grant(Manifest.permission.WRITE_CONTACTS)
        refresh()
        assertEquals(PermissionState.Granted, state(PermissionGroup.CONTACTS))
    }

    @Test fun deniedOnceThenForGood() {
        prefs.edit().putBoolean("asked_contacts", true).commit()
        rationale = true
        refresh()
        assertEquals(PermissionState.DeniedOnce, state(PermissionGroup.CONTACTS))
        rationale = false
        refresh()
        assertEquals(PermissionState.DeniedForGood, state(PermissionGroup.CONTACTS))
    }

    @Test fun aRevocationBetweenResumesIsRevokedWhileRunning() {
        grant(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        refresh()
        assertEquals(PermissionState.Granted, state(PermissionGroup.CONTACTS))
        deny(Manifest.permission.WRITE_CONTACTS)
        refresh()
        assertEquals(PermissionState.RevokedWhileRunning, state(PermissionGroup.CONTACTS))
        assertEquals(NextAction.OpenSettings, nextAction(state(PermissionGroup.CONTACTS)))
        grant(Manifest.permission.WRITE_CONTACTS)
        refresh()
        assertEquals(PermissionState.Granted, state(PermissionGroup.CONTACTS))
    }

    @Test fun aNewControllerStartsWithoutTheGrantMemory() {
        grant(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        refresh()
        deny(Manifest.permission.WRITE_CONTACTS)
        prefs.edit().putBoolean("asked_contacts", true).commit()
        val restarted = PermissionController(context, AppPermissions.contacts, special, prefs)
        restarted.refreshNow { false }
        assertEquals(PermissionState.DeniedForGood, restarted.state(PermissionGroup.CONTACTS).value)
    }

    @Test fun theContractWritesTheAskedKeyAndAsksOnlyForWhatIsMissing() {
        grant(Manifest.permission.READ_CONTACTS)
        val contract = controller.contract(PermissionGroup.CONTACTS)
        assertFalse(prefs.contains("asked_contacts"))
        val intent: Intent = contract.createIntent(context, Unit)
        assertTrue(prefs.getBoolean("asked_contacts", false))
        assertEquals(
            listOf(Manifest.permission.WRITE_CONTACTS),
            intent.getStringArrayExtra("androidx.activity.result.contract.extra.PERMISSIONS")!!.toList(),
        )
    }

    @Test fun nothingMissingAnswersWithoutADialog() {
        grant(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        val sync = controller.contract(PermissionGroup.CONTACTS).getSynchronousResult(context, Unit)
        assertEquals(
            mapOf(Manifest.permission.READ_CONTACTS to true, Manifest.permission.WRITE_CONTACTS to true),
            sync!!.value,
        )
    }

    @Test fun refreshEmitsPermissionsChangedWithTodaysPayload() {
        grant(Manifest.permission.POST_NOTIFICATIONS)
        special.fullScreenIntent = false
        val seen = mutableListOf<String>()
        val job =
            CoroutineScope(Dispatchers.Unconfined).launch {
                PlatformEvents.events.collect { if (it.kind == "permissions_changed") seen += it.json }
            }
        refresh()
        job.cancel()
        val json = JSONObject(seen.single())
        assertEquals(1, json.getInt("v"))
        assertEquals("granted", json.getString("notifications"))
        assertEquals("denied", json.getString("contacts"))
        assertFalse(json.getBoolean("full_screen_intent"))
        val keys = json.keys().asSequence().toSet()
        assertEquals(
            setOf(
                "v",
                "phone",
                "contacts",
                "notifications",
                "call_log",
                "bluetooth",
                "location",
                "background_location",
                "microphone",
                "full_screen_intent",
            ),
            keys,
        )
    }
}
