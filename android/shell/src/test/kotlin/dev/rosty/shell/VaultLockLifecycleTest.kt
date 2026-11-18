package dev.rosty.shell

import android.content.Intent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The lock timers of the WhatsApp vault (plan 6): screen off, time in the background, a frozen process, prompts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VaultLockLifecycleTest {
    private val scope = TestScope(StandardTestDispatcher())
    private var unlocked = true
    private var prompting = false
    private var timeout = 60_000L
    private var clock = 0L
    private var locks = 0
    private var backgrounds = 0
    private val lifecycle =
        VaultLockLifecycle(
            scope = scope,
            now = { clock },
            timeoutMs = { timeout },
            unlocked = { unlocked },
            prompting = { prompting },
            lock = {
                locks++
                unlocked = false
            },
            background = { backgrounds++ },
        )

    private fun away(ms: Long) {
        clock += ms
        scope.advanceTimeBy(ms)
        scope.runCurrent()
    }

    @Test fun theRule() {
        assertFalse(VaultLockRules.expired(null, 100, 0))
        assertFalse(VaultLockRules.expired(0, 59_999, 60_000))
        assertTrue(VaultLockRules.expired(0, 60_000, 60_000))
        assertTrue(VaultLockRules.expired(5, 5, 0))
    }

    @Test fun aMinuteInTheBackgroundLocks() {
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = false)
        away(59_000)
        assertEquals(0, locks)
        away(1_000)
        assertEquals(1, locks)
    }

    @Test fun comingBackInTimeKeepsItOpen() {
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = false)
        away(30_000)
        lifecycle.onActivityStarted()
        away(120_000)
        assertEquals(0, locks)
    }

    @Test fun aFrozenProcessLocksWhenItComesBack() {
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = false)
        // The process was frozen: the clock moved, the timer did not run.
        clock += 10 * 60_000L
        lifecycle.onActivityStarted()
        scope.runCurrent()
        assertEquals(1, locks)
    }

    @Test fun immediatelyLocksAtTheFirstStop() {
        timeout = 0
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = false)
        scope.runCurrent()
        assertEquals(1, locks)
    }

    @Test fun aConfigurationChangeIsNotTheBackground() {
        timeout = 0
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = true)
        scope.runCurrent()
        assertEquals(0, locks)
    }

    @Test fun aSystemPromptOverTheAppIsNotTheBackground() {
        timeout = 0
        lifecycle.onActivityStarted()
        prompting = true
        lifecycle.onActivityStopped(changingConfigurations = false)
        scope.runCurrent()
        prompting = false
        lifecycle.onActivityStarted()
        scope.runCurrent()
        assertEquals(0, locks)
    }

    @Test fun aLockedVaultIsNotLockedAgain() {
        unlocked = false
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = false)
        away(120_000)
        lifecycle.onScreenOff()
        scope.runCurrent()
        assertEquals(0, locks)
    }

    @Test fun theScreenTurningOffLocks() {
        val app = RuntimeEnvironment.getApplication()
        lifecycle.watchScreen(app)
        lifecycle.onActivityStarted()
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        scope.runCurrent()
        assertEquals(1, locks)
    }

    @Test fun lockNowLocks() {
        lifecycle.lockNow()
        scope.runCurrent()
        assertEquals(1, locks)
    }

    /** Round 7a: going to the background while unlocked is reported once (Messages writes its history file then). */
    @Test fun goingToTheBackgroundUnlockedIsReported() {
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = false)
        scope.runCurrent()
        assertEquals(1, backgrounds)
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = true)
        scope.runCurrent()
        assertEquals("a rotation is no background", 1, backgrounds)
        timeout = 0
        lifecycle.onActivityStopped(changingConfigurations = false)
        scope.runCurrent()
        assertEquals("locking at once writes it in the lock instead", 1, backgrounds)
    }
}
