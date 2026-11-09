package dev.rovsti.platform.system.permissions

import dev.rovsti.platform.system.permissions.PermissionState.DeniedForGood
import dev.rovsti.platform.system.permissions.PermissionState.DeniedOnce
import dev.rovsti.platform.system.permissions.PermissionState.Granted
import dev.rovsti.platform.system.permissions.PermissionState.NeverAsked
import dev.rovsti.platform.system.permissions.PermissionState.RevokedWhileRunning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every combination of the four inputs, so the table cannot lose a row (ARCH 7.5). */
class PermissionStateTest {
    private data class Row(
        val granted: Boolean,
        val asked: Boolean,
        val rationale: Boolean,
        val grantedBefore: Boolean,
        val state: PermissionState,
        val next: NextAction,
    )

    private val table =
        listOf(
            // granted wins over everything
            Row(true, false, false, false, Granted, NextAction.None),
            Row(true, false, false, true, Granted, NextAction.None),
            Row(true, false, true, false, Granted, NextAction.None),
            Row(true, false, true, true, Granted, NextAction.None),
            Row(true, true, false, false, Granted, NextAction.None),
            Row(true, true, false, true, Granted, NextAction.None),
            Row(true, true, true, false, Granted, NextAction.None),
            Row(true, true, true, true, Granted, NextAction.None),
            // not granted, a grant seen earlier in this process: revoked while running
            Row(false, false, false, true, RevokedWhileRunning, NextAction.OpenSettings),
            Row(false, false, true, true, RevokedWhileRunning, NextAction.OpenSettings),
            Row(false, true, false, true, RevokedWhileRunning, NextAction.OpenSettings),
            Row(false, true, true, true, RevokedWhileRunning, NextAction.OpenSettings),
            // not granted, never seen granted
            Row(false, false, false, false, NeverAsked, NextAction.Ask),
            Row(false, false, true, false, DeniedOnce, NextAction.Ask),
            Row(false, true, true, false, DeniedOnce, NextAction.Ask),
            Row(false, true, false, false, DeniedForGood, NextAction.OpenSettings),
        )

    @Test fun theTableCoversAllSixteenCombinations() {
        assertEquals(16, table.size)
        assertEquals(16, table.map { listOf(it.granted, it.asked, it.rationale, it.grantedBefore) }.toSet().size)
    }

    @Test fun everyRowDecidesAsExpected() {
        for (r in table) {
            val got = decide(r.granted, r.asked, r.rationale, r.grantedBefore)
            assertEquals("$r", r.state, got)
            assertEquals("$r", r.next, nextAction(got))
        }
    }

    @Test fun allFiveStatesAreReachable() {
        val reached = table.map { decide(it.granted, it.asked, it.rationale, it.grantedBefore) }.toSet()
        assertEquals(setOf(NeverAsked, Granted, DeniedOnce, DeniedForGood, RevokedWhileRunning), reached)
    }

    @Test fun askedAndNoRationaleIsDeniedForGood() {
        assertEquals(DeniedForGood, decide(false, true, false, false))
    }

    @Test fun grantedThenNotGrantedInOneProcessIsRevoked() {
        assertEquals(RevokedWhileRunning, decide(false, true, false, true))
    }

    @Test fun theWireWordsMatchTheRustAccessEnum() {
        assertEquals("granted", Granted.wire)
        assertEquals("permanently_denied", DeniedForGood.wire)
        for (s in listOf(NeverAsked, DeniedOnce, RevokedWhileRunning)) assertEquals("denied", s.wire)
    }

    @Test fun onlyAskableStatesOfferTheDialog() {
        assertTrue(nextAction(NeverAsked) == NextAction.Ask && nextAction(DeniedOnce) == NextAction.Ask)
    }
}
