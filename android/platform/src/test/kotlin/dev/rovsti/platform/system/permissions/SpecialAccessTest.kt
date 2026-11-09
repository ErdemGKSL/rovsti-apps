package dev.rovsti.platform.system.permissions

import android.app.Activity
import android.app.Application
import android.app.role.RoleManager
import android.content.Intent
import android.provider.Settings
import dev.rovsti.platform.location.StartResult
import dev.rovsti.platform.system.FakeSpecialAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpecialAccessTest {
    private val app: Application = RuntimeEnvironment.getApplication()
    private val access = AndroidSpecialAccess(app)

    private fun pkgData() = "package:${app.packageName}"

    @Test fun appDetailsIsTheOnlyPageOfItsChain() {
        val chain = access.settingsChain(SettingsKind.AppDetails)
        assertEquals(1, chain.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, chain[0].action)
        assertEquals(pkgData(), chain[0].dataString)
    }

    @Test fun everyChainEndsInAppInfoWithThePackage() {
        for (kind in SettingsKind.entries) {
            val last = access.settingsChain(kind).last()
            assertEquals(kind.name, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, last.action)
            assertEquals(kind.name, pkgData(), last.dataString)
        }
    }

    @Test fun fullScreenIntentOpensItsOwnPage() {
        val i = access.settingsIntent(SettingsKind.FullScreenIntent)
        assertEquals(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, i.action)
        assertEquals(pkgData(), i.dataString)
    }

    @Test fun exactAlarmsOpensItsOwnPage() {
        val i = access.settingsIntent(SettingsKind.ExactAlarms)
        assertEquals(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, i.action)
        assertEquals(pkgData(), i.dataString)
    }

    @Test fun batteryAsksToIgnoreOptimisationsThenListsThem() {
        val chain = access.settingsChain(SettingsKind.Battery)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, chain[0].action)
        assertEquals(pkgData(), chain[0].dataString)
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, chain[1].action)
    }

    @Test fun defaultAppsHasNoData() {
        val i = access.settingsIntent(SettingsKind.DefaultApps)
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, i.action)
        assertNull(i.data)
    }

    @Test fun xiaomiPagesComeFromXiaomiOpsAndFallBackToAppInfo() {
        val auto = access.settingsChain(SettingsKind.XiaomiAutostart)
        assertEquals("miui.intent.action.OP_AUTO_START", auto[0].action)
        assertEquals(4, auto.size)
        val perms = access.settingsChain(SettingsKind.XiaomiPermissions)
        assertEquals("miui.intent.action.APP_PERM_EDITOR", perms[0].action)
        assertEquals(app.packageName, perms[0].getStringExtra("extra_pkgname"))
        assertEquals(
            "com.miui.powerkeeper",
            access.settingsChain(SettingsKind.XiaomiBattery)[0].component!!.packageName,
        )
    }

    @Test fun startFirstWalksTheChainUntilSomeActivityTakesIt() {
        val chain = access.settingsChain(SettingsKind.XiaomiAutostart)
        // Nothing resolves in Robolectric except what we register: only the generic App info page is "installed".
        assertTrue(app.startFirst(listOf(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, shadowOf(app).nextStartedActivity.action)
        assertTrue(chain.isNotEmpty())
    }

    @Test fun exactAlarmsFollowTheAlarmManager() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertFalse(access.exactAlarms())
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertTrue(access.exactAlarms())
    }

    @Test fun batteryFollowsThePowerManager() {
        assertFalse(access.ignoresBatteryOptimisation())
        shadowOf(
            app.getSystemService(android.os.PowerManager::class.java),
        ).setIgnoringBatteryOptimizations(app.packageName, true)
        assertTrue(access.ignoresBatteryOptimisation())
    }

    @Test fun rolesNeedAvailabilityAndHolding() {
        val shadow = shadowOf(app.getSystemService(RoleManager::class.java))
        assertFalse("not available", access.isRoleHeld(AppPermissions.ROLE_DIALER))
        shadow.addAvailableRole(AppPermissions.ROLE_DIALER)
        assertFalse("available, not held", access.isRoleHeld(AppPermissions.ROLE_DIALER))
        shadow.addHeldRole(AppPermissions.ROLE_DIALER)
        assertTrue(access.isRoleHeld(AppPermissions.ROLE_DIALER))
        assertFalse(access.isRoleHeld(AppPermissions.ROLE_SMS))
    }

    @Test fun theRoleContractRequestsTheRoleAndReadsTheOutcome() {
        val shadow = shadowOf(app.getSystemService(RoleManager::class.java))
        shadow.addAvailableRole(AppPermissions.ROLE_SMS)
        val contract = access.requestRoleContract(AppPermissions.ROLE_SMS)
        val intent = contract.createIntent(app, Unit)
        assertEquals("android.app.role.action.REQUEST_ROLE", intent.action)
        assertTrue(intent.extras!!.keySet().any { intent.getStringExtra(it) == AppPermissions.ROLE_SMS })
        assertFalse(contract.parseResult(Activity.RESULT_OK, null))
        shadow.addHeldRole(AppPermissions.ROLE_SMS)
        assertTrue(contract.parseResult(Activity.RESULT_OK, null))
        assertFalse(contract.parseResult(Activity.RESULT_CANCELED, null))
        assertEquals(true, contract.getSynchronousResult(app, Unit)!!.value)
    }

    @Test fun anUnavailableRoleOpensDefaultApps() {
        val intent = access.requestRoleContract(AppPermissions.ROLE_DIALER).createIntent(app, Unit)
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, intent.action)
    }

    @Test fun theFakeAnswersLikeTheRealThing() {
        val fake = FakeSpecialAccess()
        fake.heldRoles += AppPermissions.ROLE_SMS
        assertTrue(fake.isRoleHeld(AppPermissions.ROLE_SMS))
        fake.requestRoleContract(AppPermissions.ROLE_DIALER).createIntent(app, Unit)
        assertEquals(listOf(AppPermissions.ROLE_DIALER), fake.askedRoles)
        assertEquals("fake.settings.Battery", fake.settingsIntent(SettingsKind.Battery).action)
    }

    @Test fun refusedForegroundStartsAreNotAllowedNotCrashes() {
        assertEquals(StartResult.NotAllowed, ForegroundStart.map(SecurityException("no")))
        assertEquals(
            StartResult.NotAllowed,
            ForegroundStart.map(android.app.ForegroundServiceStartNotAllowedException("background")),
        )
        assertEquals(StartResult.Failed, ForegroundStart.map(IllegalStateException("other")))
    }
}
