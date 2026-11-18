package dev.rosty.shell

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the activities tell the vault (round 7c): every start is a use when the app has a vault, the last stop with
 * WhatsApp open is the background, and nothing ever locks on a timer or on screen off.
 */
class VaultUseLifecycleTest {
    private val scope = TestScope(StandardTestDispatcher())
    private var hasVault = true
    private var unlocked = true
    private var prompting = false
    private var uses = 0
    private var backgrounds = 0
    private val lifecycle =
        VaultUseLifecycle(
            scope = scope,
            hasVault = { hasVault },
            unlocked = { unlocked },
            prompting = { prompting },
            use = { uses++ },
            background = { backgrounds++ },
        )

    private fun settle() = scope.runCurrent()

    @Test fun everyStartIsAUse() {
        lifecycle.onActivityStarted()
        lifecycle.onActivityStarted()
        settle()
        assertEquals(2, uses)
    }

    @Test fun anAppWithoutAVaultStartsNothing() {
        hasVault = false
        lifecycle.onActivityStarted()
        settle()
        assertEquals(0, uses)
    }

    @Test fun theLastStopWithWhatsAppOpenIsTheBackgroundAndNothingLocksLater() {
        lifecycle.onActivityStarted()
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = false)
        settle()
        assertEquals("another activity is still started", 0, backgrounds)
        lifecycle.onActivityStopped(changingConfigurations = false)
        settle()
        assertEquals(1, backgrounds)
        // Days in the background: no timer runs, the vault stays open.
        scope.advanceTimeBy(7 * 24 * 3_600_000L)
        settle()
        assertEquals(1, backgrounds)
    }

    @Test fun aRotationOrAPromptIsNotTheBackground() {
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = true)
        prompting = true
        lifecycle.onActivityStopped(changingConfigurations = false)
        settle()
        assertEquals(0, backgrounds)
    }

    @Test fun aLockedVaultHasNoBackgroundWork() {
        unlocked = false
        lifecycle.onActivityStarted()
        lifecycle.onActivityStopped(changingConfigurations = false)
        settle()
        assertEquals(0, backgrounds)
    }
}
