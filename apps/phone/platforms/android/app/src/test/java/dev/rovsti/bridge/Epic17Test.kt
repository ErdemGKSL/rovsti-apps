package dev.rovsti.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

class Epic17Test {
    private class Fake(
        var can: Boolean = true,
        var rows: List<Blocklist.Row> = emptyList(),
        var denied: Boolean = false,
    ) : Blocklist.Provider {
        val inserted = ArrayList<String>()
        override fun canBlock() = can
        override fun rows(): List<Blocklist.Row> = if (denied) throw SecurityException() else rows
        override fun insert(raw: String): Boolean = if (denied) throw SecurityException() else inserted.add(raw)
        override fun unblock(raw: String): Int = if (denied) throw SecurityException() else 1
        override fun isBlocked(raw: String): Boolean = if (denied) throw SecurityException() else rows.any { it.numberRaw == raw }
    }

    // ---- Blocklist (17.1) -----------------------------------------------------------------

    @Test fun pullMapsRowsToTheWireShape() {
        val p = Fake(rows = listOf(Blocklist.Row(7, "0555 111 22 33", "+905551112233"), Blocklist.Row(8, "Spam", "")))
        val o = JSONObject(Blocklist.pullJson(p))
        assertTrue(o.getBoolean("ok"))
        val n = o.getJSONObject("data").getJSONArray("numbers")
        assertEquals(2, n.length())
        assertEquals("+905551112233", n.getJSONObject(0).getString("normalized"))
        assertEquals(7L, n.getJSONObject(0).getLong("id"))
    }

    @Test fun aSecurityExceptionIsUnavailableWhenBlockingIsOffAndPermissionOtherwise() {
        val unavailable = Fake(can = false, denied = true)
        assertEquals("unavailable", JSONObject(Blocklist.pullJson(unavailable)).getString("error"))
        val permission = Fake(can = true, denied = true)
        assertEquals("permission", JSONObject(Blocklist.pullJson(permission)).getString("error"))
        assertEquals(Blocklist.Result.UNAVAILABLE, Blocklist.block(unavailable, "0555", { false }))
        assertEquals(Blocklist.Result.PERMISSION, Blocklist.unblock(permission, "0555"))
        assertFalse(Blocklist.isBlocked(permission, "0555"))
    }

    @Test fun emergencyNumbersAndBlanksAreRefusedBeforeTheProvider() {
        val p = Fake()
        assertEquals(Blocklist.Result.REFUSED, Blocklist.block(p, "112", { it == "112" }))
        assertEquals(Blocklist.Result.REFUSED, Blocklist.block(p, "  ", { false }))
        assertTrue(p.inserted.isEmpty())
        assertEquals(Blocklist.Result.OK, Blocklist.block(p, " 0555 ", { false }))
        assertEquals(listOf("0555"), p.inserted)
    }

    @Test fun blockReasonsMapToUserSystemUnknown() {
        assertEquals("user", Blocklist.reasonName(3))
        for (code in listOf(1, 2, 4, 5, 6, 7)) assertEquals("system", Blocklist.reasonName(code))
        assertEquals("unknown", Blocklist.reasonName(0))
        assertEquals("unknown", Blocklist.reasonName(99))
    }

    @Test fun theGuardNeverDelaysTheNotificationAndHitsLandOnMain() {
        val queue = ArrayDeque<Runnable>()
        val lookup = Executor { queue.addLast(it) }
        val mainQueue = ArrayList<Runnable>()
        var asked = 0
        val guard = BlockGuard(lookup, { mainQueue.add(it) }, { asked++; true })
        var blocked = 0
        guard.onRinging("0555", true) { blocked++ }
        // A slow provider: nothing ran yet, onRinging already returned.
        assertEquals(0, asked)
        assertEquals(0, blocked)
        queue.removeFirst().run()
        assertEquals(1, asked)
        assertEquals(0, blocked)
        mainQueue.single().run()
        assertEquals(1, blocked)
    }

    @Test fun theGuardDoesNothingWhenOffOrWithoutANumberOrOnAMiss() {
        val queue = ArrayDeque<Runnable>()
        val guard = BlockGuard({ queue.addLast(it) }, { it.run() }, { false })
        var blocked = 0
        guard.onRinging("0555", false) { blocked++ }
        guard.onRinging("", true) { blocked++ }
        assertTrue(queue.isEmpty())
        guard.onRinging("0555", true) { blocked++ }
        queue.removeFirst().run()
        assertEquals(0, blocked)
        val boom = BlockGuard({ it.run() }, { it.run() }, { throw IllegalStateException() })
        boom.onRinging("0555", true) { blocked++ }
        assertEquals(0, blocked)
    }

    // ---- Reject (18.1) ---------------------------------------------------------------------

    private fun decide(text: String = "hi", ringing: Boolean = true, telecom: Boolean = false, respond: Boolean = true, number: Boolean = true) =
        RejectPolicy.decide(text, ringing, telecom, respond, number, true)

    @Test fun handoffIsTheDefaultAndOpensMessages() {
        val d = decide()
        assertEquals(RejectPolicy.OPENED, d.code)
        assertEquals(RejectPolicy.Action.HANDOFF, d.action)
    }

    @Test fun telecomNeedsTheCapabilityAndANumber() {
        assertEquals(RejectPolicy.SENT, decide(telecom = true).code)
        // Invalid capability for this call: falls back per call.
        assertEquals(RejectPolicy.OPENED, decide(telecom = true, respond = false).code)
        assertEquals(RejectPolicy.REJECTED_ONLY, decide(number = false).code)
        assertEquals(RejectPolicy.Action.REJECT_ONLY, decide(number = false).action)
    }

    @Test fun staleOrInvalidTextRejectsNothing() {
        assertEquals(RejectPolicy.STALE, decide(ringing = false).code)
        assertNull(decide(ringing = false).action)
        assertEquals(RejectPolicy.STALE, decide(text = "   ").code)
        assertEquals(RejectPolicy.STALE, decide(text = "x".repeat(161)).code)
        assertEquals(RejectPolicy.OPENED, decide(text = "x".repeat(160)).code)
    }

    // ---- Flip (18.4) -----------------------------------------------------------------------

    @Test fun flipNeedsTwoFaceDownSamplesAfterAnUpwardOne() {
        val r = FlipRule()
        assertFalse(r.onZ(9.8f))
        assertFalse(r.onZ(-8f))
        assertTrue(r.onZ(-8.5f))
        // Fires once; it must be armed again by an upward sample.
        assertFalse(r.onZ(-9f))
        assertFalse(r.onZ(-9f))
    }

    @Test fun aPhoneAlreadyFaceDownNeverSilences() {
        val r = FlipRule()
        repeat(6) { assertFalse(r.onZ(-9.8f)) }
        assertFalse(r.onZ(-2f))
        assertFalse(r.onZ(-8f))
        assertTrue(r.onZ(-8f))
    }

    @Test fun oneNoisySampleDoesNotTrigger() {
        val r = FlipRule()
        r.onZ(1f)
        assertFalse(r.onZ(-8f))
        assertFalse(r.onZ(0f))
        assertFalse(r.onZ(-8f))
    }

    // ---- Probes and notification (17.1, 18.1, 18.4, 18.7) ------------------------------------------

    @Test fun blockingProbeNeedsTheRoleThenTheOwner() {
        val off = Epic17Probes.blockingLines(Epic17Probes.Blocking(false, 0, null, 0, null, null, 0, true))
        assertTrue(off.any { it.startsWith("fail: this user may block") })
        val on = Epic17Probes.blockingLines(Epic17Probes.Blocking(true, 2, true, 1, 4, "user", 0, true))
        assertTrue(on.any { it.startsWith("pass: isBlocked") })
        assertTrue(on.any { it.startsWith("pass: a type-6") })
        assertTrue(on.none { it.contains("+90") })
    }

    @Test fun rejectProbeReportsTheCodeAndAsksTheOwner() {
        assertTrue(Epic17Probes.rejectLines(Epic17Probes.Reject(false, false, 0, false)).single().startsWith("unknown"))
        val lines = Epic17Probes.rejectLines(Epic17Probes.Reject(true, true, RejectPolicy.SENT, true))
        assertTrue(lines.any { it.startsWith("pass: reject(true") })
        assertTrue(lines.any { it.startsWith("unknown: the SMS arrived") })
    }

    @Test fun silenceProbePrintsEachAttemptAndWhoSilenced() {
        val lines = Epic17Probes.silenceLines(
            listOf(Epic17Probes.Attempt(true, "user"), Epic17Probes.Attempt(true, "volume"), Epic17Probes.Attempt(false, null)), 1,
        )
        assertEquals(3, lines.count { it.startsWith("info attempt") })
        assertTrue(lines.any { it.startsWith("pass: silenceRinger()") })
        assertTrue(lines.any { it.startsWith("pass: a volume key") })
        assertTrue(lines.any { it.startsWith("unknown: flipping") })
    }

    @Test fun theMessageActionAppearsOnlyWithRepliesAndANumber() {
        val ringing = CallInfo(id = 3, state = "ringing", numberRaw = "0555", displayName = "Ayse")
        assertFalse(IncomingCallOf(listOf(ringing), false))
        assertTrue(IncomingCallOf(listOf(ringing), true))
        assertFalse(IncomingCallOf(listOf(ringing.copy(numberRaw = "")), true))
    }

    private fun IncomingCallOf(calls: List<CallInfo>, replies: Boolean): Boolean =
        dev.rovsti.phone.IncomingCall.of(calls, replies)!!.canMessage

    @Test fun theSnapshotCarriesRingerSilenced() {
        val data = SnapshotData(seq = 1, nowMs = 2, keyguardLocked = false, ringerSilenced = true, silencedBy = "flip")
        val o = SnapshotJson.build(data)
        assertTrue(o.getBoolean("ringer_silenced"))
        assertEquals("flip", o.getString("silenced_by"))
        val back = SnapshotJson.parse(o)
        assertTrue(back.ringerSilenced)
    }
}
