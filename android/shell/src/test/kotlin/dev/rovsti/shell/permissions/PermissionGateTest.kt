package dev.rovsti.shell.permissions

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rovsti.platform.PlatformPrefs
import dev.rovsti.platform.system.FakeSpecialAccess
import dev.rovsti.platform.system.permissions.AppPermissions
import dev.rovsti.platform.system.permissions.PermissionController
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * R9-7 / E3.11: the rationale sheet and the denied row as a user meets them, with the manual clock stopped
 * (`autoAdvance = false`, set by [ShotTest]). The controller is the real one over the real prefs and Robolectric's
 * permission table; the activity registry is a recording fake.
 */
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [35])
class PermissionGateTest : ShotTest() {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val prefs = app.getSharedPreferences(PlatformPrefs.BRIDGE, Context.MODE_PRIVATE)
    private val special = FakeSpecialAccess()
    private val group = PermissionGroup.CONTACTS
    private lateinit var controller: PermissionController
    private lateinit var registry: RecordingRegistry
    private var rationale = false
    private var seen: PermissionState? = null

    @Before fun setUp() {
        prefs.edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        controller = PermissionController(app, AppPermissions.contacts, special, prefs)
        registry = RecordingRegistry(app)
    }

    private fun refresh() = controller.refreshNow { rationale }

    private fun show() {
        composeFrame { GatedPage(group, controller, special, registry, onState = { seen = it }) }
        advance(600)
    }

    private fun sheetShown(): Boolean =
        compose
            .onAllNodes(
                androidx.compose.ui.test
                    .hasTestTag("permission-rationale"),
            ).fetchSemanticsNodes()
            .isNotEmpty()

    @Test fun neverAskedShowsTheRationaleSheetAndContinueLaunchesTheDialog() {
        refresh()
        show()
        assertEquals(PermissionState.NeverAsked, seen)
        compose.onNodeWithTag("permission-rationale").assertIsDisplayed()
        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        assertTrue("nothing is asked before the user agrees", registry.intents.isEmpty())

        compose.onNodeWithTag("permission-continue").performClick()
        advance(800)

        assertEquals(1, registry.intents.size)
        val asked = registry.intents.single().getStringArrayExtra("androidx.activity.result.contract.extra.PERMISSIONS")
        assertEquals(setOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS), asked!!.toSet())
        assertTrue("the dialog being shown is remembered for DeniedForGood", prefs.getBoolean("asked_contacts", false))
        assertFalse("the sheet closes", sheetShown())
    }

    @Test fun notNowClosesTheSheetAndAsksNothing() {
        refresh()
        show()
        compose.onNodeWithTag("permission-not-now").performClick()
        advance(800)
        assertFalse(sheetShown())
        assertTrue(registry.intents.isEmpty())
        assertEquals("the caller still learns the state", PermissionState.NeverAsked, seen)
        // The refusal is not nagged: a refresh (the next resume) does not bring the sheet back this visit.
        refresh()
        advance(600)
        assertFalse(sheetShown())
    }

    @Test fun deniedOnceAlsoExplainsBeforeAsking() {
        prefs.edit().putBoolean("asked_contacts", true).commit()
        rationale = true
        refresh()
        show()
        assertEquals(PermissionState.DeniedOnce, seen)
        compose.onNodeWithTag("permission-rationale").assertIsDisplayed()
    }

    @Test fun deniedForGoodHasNoSheetAndTheRowOpensSettings() {
        prefs.edit().putBoolean("asked_contacts", true).commit()
        rationale = false
        refresh()
        show()
        assertEquals(PermissionState.DeniedForGood, seen)
        assertFalse("Android would not show the dialog, so a rationale would promise nothing", sheetShown())
        compose.onNodeWithText("Open Settings").assertIsDisplayed()
        compose.onNodeWithTag(permissionActionTag(group), useUnmergedTree = true).performClick()
        advance(200)
        val started = shadowOf(compose.activity).nextStartedActivity
        assertEquals("fake.settings.AppDetails", started.action)
        assertTrue("it never launches the permission dialog", registry.intents.isEmpty())
    }

    @Test fun aGrantMadeInSettingsWhileTheSheetIsOpenClosesIt() {
        refresh()
        show()
        assertTrue(sheetShown())
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        refresh()
        advance(800)
        assertEquals(PermissionState.Granted, seen)
        assertFalse(sheetShown())
        compose.onNodeWithText("Allowed").assertIsDisplayed()
    }

    @Test fun revokedWhileRunningShowsTheRowAgainAfterAResume() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        refresh()
        show()
        assertEquals(PermissionState.Granted, seen)
        compose.onNodeWithText("Allowed").assertIsDisplayed()
        assertFalse(sheetShown())

        // The user turns it off in Settings; coming back runs onResume -> refresh.
        shadowOf(app).denyPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        refresh()
        advance(600)

        assertEquals(PermissionState.RevokedWhileRunning, seen)
        assertFalse("only Settings can fix it, so no rationale", sheetShown())
        compose.onNodeWithText("Turned off in Settings").assertIsDisplayed()
        compose.onNodeWithText("Open Settings").assertIsDisplayed()
        compose.onNodeWithTag(permissionActionTag(group), useUnmergedTree = true).performClick()
        advance(200)
        assertEquals("fake.settings.AppDetails", shadowOf(compose.activity).nextStartedActivity.action)
    }

    @Test fun theRowAsksWhileAndroidStillShowsTheDialog() {
        refresh()
        composeFrame { GatedPage(group, controller, special, registry, withRow = true) }
        advance(600)
        compose.onNodeWithTag("permission-not-now").performClick()
        advance(800)
        compose.onNodeWithText("Allow").assertIsDisplayed()
        compose.onNodeWithTag(permissionActionTag(group), useUnmergedTree = true).performClick()
        advance(200)
        assertEquals(1, registry.intents.size)
    }
}
