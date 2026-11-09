package dev.rovsti.platform.telephony

import dev.rovsti.platform.wire.CallInfo
import dev.rovsti.platform.wire.Focus
import dev.rovsti.platform.wire.SnapshotJson
import dev.rovsti.platform.wire.Wire
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Story 15.1 / 15.2: the focus rules, `can_*` flags, the PendingOp machine and the S4 probe lines. */
class MultiCallTest {
    private fun c(
        id: Int,
        state: String,
        parent: Int? = null,
        caps: Int = 0,
        props: Int = 0,
        children: List<Int> = emptyList(),
        conf: Boolean = false,
    ) = CallInfo(
        id,
        state,
        parentId = parent,
        caps = caps,
        props = props,
        children = children,
        conferenceable = conf,
        canHold =
            caps and Wire.Caps.HOLD != 0,
    )

    private val dir: File =
        run {
            var d: File? = File(System.getProperty("user.dir")).absoluteFile
            while (d != null && !File(d, "crates/rovsti_core/tests/fixtures").isDirectory) d = d.parentFile
            File(requireNotNull(d), "crates/rovsti_core/tests/fixtures")
        }

    private fun fixture(name: String) = SnapshotJson.parse(JSONObject(File(dir, name).readText()))

    // ---- Focus ----

    @Test fun foregroundPrefersActiveThenDialingThenHolding() {
        assertEquals(2, Focus.of(listOf(c(1, "holding"), c(2, "active"))).foreground)
        assertEquals(2, Focus.of(listOf(c(1, "holding"), c(2, "dialing"))).foreground)
        assertEquals(1, Focus.of(listOf(c(1, "holding"), c(3, "holding"))).foreground)
        assertEquals(3, Focus.of(listOf(c(3, "ringing"))).foreground)
    }

    @Test fun backgroundIsTheOtherLiveCallAndWaitingIsTheLowestRinging() {
        val f = Focus.of(listOf(c(1, "holding"), c(2, "active"), c(3, "ringing"), c(4, "ringing")))
        assertEquals(Focus(2, 3, 1, null), f)
    }

    @Test fun childrenNeverTakeARole() {
        val calls =
            listOf(
                c(1, "active", parent = 3),
                c(2, "active", parent = 3),
                c(3, "active", props = Wire.Props.CONFERENCE, children = listOf(1, 2)),
            )
        assertEquals(Focus(3, null, null, null), Focus.of(calls))
    }

    @Test fun anEndedCallBesideALiveOneIsReportedNotShown() {
        val f = Focus.of(listOf(c(1, "disconnected"), c(2, "holding")))
        assertEquals(Focus(2, null, null, 1), f)
    }

    @Test fun theActivePartyHangsUpSoTheHeldCallBecomesForegroundInHolding() {
        // Nothing resumes by itself: the held call is the foreground and stays "holding".
        val f = Focus.of(listOf(c(2, "holding")))
        assertEquals(2, f.foreground)
        assertNull(f.background)
        val calls = listOf(c(1, "disconnected"), c(2, "holding"))
        assertEquals(2, Focus.of(calls).foreground)
        assertEquals(1, Focus.of(calls).ended)
    }

    @Test fun aLoneDisconnectedCallIsTheForegroundNotEnded() {
        assertEquals(Focus(4, null, null, null), Focus.of(listOf(c(4, "disconnected"))))
    }

    @Test fun whileAnOpRunsThePreviousForegroundStays() {
        val calls = listOf(c(1, "active"), c(2, "holding"))
        assertEquals(2, Focus.of(calls, keep = 2).foreground)
        assertEquals(1, Focus.of(calls, keep = 2).background)
        // A kept call that is gone is ignored.
        assertEquals(1, Focus.of(calls, keep = 9).foreground)
    }

    // ---- can_* ----

    @Test fun canAddCallFollowsThePlatformButNotForEmergencyOrTwoCalls() {
        val one = listOf(c(1, "active"))
        assertTrue(MultiCall.canAddCall(true, one))
        assertFalse(MultiCall.canAddCall(false, one))
        assertFalse(MultiCall.canAddCall(true, listOf(c(1, "active", props = Wire.Props.EMERGENCY))))
        assertFalse(MultiCall.canAddCall(true, listOf(c(1, "holding"), c(2, "active"))))
        // A ringing call is not counted: a waiting call does not block adding.
        assertTrue(MultiCall.canAddCall(true, listOf(c(1, "active"), c(2, "ringing"))))
    }

    @Test fun canMergeFromTheCapOrAConferenceableCall() {
        val calls = listOf(c(1, "holding"), c(2, "active", caps = Wire.Caps.MERGE))
        assertTrue(MultiCall.canMerge(calls, Focus.of(calls)))
        val plain = listOf(c(1, "holding"), c(2, "active"))
        assertFalse(MultiCall.canMerge(plain, Focus.of(plain)))
        val conf = listOf(c(1, "holding"), c(2, "active", conf = true))
        assertTrue(MultiCall.canMerge(conf, Focus.of(conf)))
    }

    @Test fun canSwapNeedsTwoCallsAndAHeldOneThatCanResume() {
        val ok = listOf(c(1, "holding", caps = Wire.Caps.HOLD), c(2, "active"))
        assertTrue(MultiCall.canSwap(ok, Focus.of(ok)))
        val stuck = listOf(c(1, "holding"), c(2, "active"))
        assertFalse(MultiCall.canSwap(stuck, Focus.of(stuck)))
        val one = listOf(c(2, "active", caps = Wire.Caps.HOLD))
        assertFalse(MultiCall.canSwap(one, Focus.of(one)))
        val conf = listOf(c(2, "active", caps = Wire.Caps.SWAP))
        assertTrue(MultiCall.canSwap(conf, Focus.of(conf)))
    }

    @Test fun holdAndAnswerIsRelabelledWithAHeldCallOrNoHoldCap() {
        val noHold = listOf(c(1, "active"), c(2, "ringing"))
        assertFalse(MultiCall.holdAndAnswerOffered(noHold, Focus.of(noHold)))
        val ok = listOf(c(1, "active", caps = Wire.Caps.HOLD), c(2, "ringing"))
        assertTrue(MultiCall.holdAndAnswerOffered(ok, Focus.of(ok)))
        val three =
            listOf(c(1, "holding", caps = Wire.Caps.HOLD), c(2, "active", caps = Wire.Caps.HOLD), c(3, "ringing"))
        assertFalse(MultiCall.holdAndAnswerOffered(three, Focus.of(three)))
    }

    // ---- Fixtures ----

    @Test fun twoCallsFixtureAgreesWithFocusAndCaps() {
        val snap = fixture("two_calls.json")
        val focus = Focus.of(snap.calls, keep = snap.foregroundCallId)
        assertEquals(snap.foregroundCallId, focus.foreground)
        assertEquals(snap.backgroundCallId, focus.background)
        assertEquals(snap.canMerge, MultiCall.canMerge(snap.calls, focus))
        assertEquals(snap.canSwap, MultiCall.canSwap(snap.calls, focus))
        // The sample carries can_add_call = true; with two live calls the rule says no (Rust shows "Two calls...").
        assertFalse(MultiCall.canAddCall(true, snap.calls))
        assertEquals("swap", snap.pending)
    }

    @Test fun conferenceFixtureHasOneTopLevelCallAndSplittableChildren() {
        val snap = fixture("conference.json")
        val focus = Focus.of(snap.calls)
        assertEquals(snap.foregroundCallId, focus.foreground)
        assertEquals(3, focus.foreground)
        val kids = snap.calls.filter { it.parentId == 3 }
        assertEquals(listOf(1, 2), snap.calls.first { it.id == 3 }.children)
        assertTrue(
            kids.all {
                MultiCall.has(it.caps, Wire.Caps.SPLIT) &&
                    MultiCall.has(it.caps, Wire.Caps.DISCONNECT_PARTICIPANT)
            },
        )
        assertTrue(MultiCall.has(snap.calls.first { it.id == 3 }.props, Wire.Props.CONFERENCE))
    }

    // ---- PendingOp machine ----

    private class Clock {
        var t = 0L

        fun now() = t
    }

    private fun machine(clock: Clock) = PendingMachine(clock::now)

    @Test fun swapSuccessClearsTheOp() {
        val clock = Clock()
        val m = machine(clock)
        assertTrue(m.start(PendingOp.Swap(1, 2)))
        assertEquals("swap" to null, m.flags())
        clock.t = 300
        assertEquals(Tick.Idle, m.tick())
        assertEquals(PendingOp.Swap(1, 2), m.finish(OpResult.DONE))
        assertEquals(null to null, m.flags())
    }

    @Test fun swapNeedingTheRetryThenFailing() {
        val clock = Clock()
        val m = machine(clock)
        m.start(PendingOp.Swap(1, 2))
        clock.t = 1_000
        assertEquals(Tick.Retry(PendingOp.Swap(1, 2)), m.tick())
        assertEquals("swap", m.flags().first)
        clock.t = 1_999
        assertEquals(Tick.Idle, m.tick())
        clock.t = 2_000
        assertEquals(Tick.Failed(PendingOp.Swap(1, 2)), m.tick())
        // pending_failed is on exactly one rebuild.
        assertEquals(null to "swap", m.flags())
        assertEquals(null to null, m.flags())
    }

    @Test fun swapSucceedingAfterTheRetryIsDone() {
        val clock = Clock()
        val m = machine(clock)
        m.start(PendingOp.Swap(1, 2))
        clock.t = 1_000
        m.tick()
        clock.t = 1_400
        assertEquals(PendingOp.Swap(1, 2), m.finish(OpResult.DONE))
        assertEquals(null to null, m.flags())
    }

    @Test fun mergeSuccessAndTimeoutClearingTheOp() {
        val clock = Clock()
        val m = machine(clock)
        assertTrue(m.start(PendingOp.Merge(0)))
        assertEquals(PendingOp.Merge(0), m.finish(OpResult.DONE))
        assertTrue(m.start(PendingOp.Merge(0)))
        clock.t = 1_999
        assertEquals(Tick.Idle, m.tick())
        clock.t = 2_000
        assertEquals(Tick.Failed(PendingOp.Merge(0)), m.tick())
        assertNull(m.current)
        assertEquals(null to "merge", m.flags())
        // The machine is free again.
        assertTrue(m.start(PendingOp.Merge(0)))
    }

    @Test fun answerReplacingSupersedesASwapOrMergeButASecondOpIsRefused() {
        val clock = Clock()
        val m = machine(clock)
        m.start(PendingOp.Swap(1, 2))
        assertFalse(m.start(PendingOp.Merge(0)))
        assertTrue(m.start(PendingOp.AnswerReplacing(3, 2)))
        assertEquals(PendingOp.AnswerReplacing(3, 2), m.current)
        assertEquals("answer", m.flags().first)
        // Another answer does not supersede an answer.
        assertFalse(m.start(PendingOp.AnswerReplacing(4, 2)))
        m.finish(OpResult.DONE)
        assertTrue(m.start(PendingOp.Merge(0)))
        assertTrue(m.start(PendingOp.AnswerReplacing(3, 2)))
    }

    @Test fun anAnswerDeadlineIsTwoSeconds() {
        val clock = Clock()
        val m = machine(clock)
        m.start(PendingOp.AnswerReplacing(3, 2))
        assertEquals(2_000L, m.nextDeadlineIn())
        clock.t = 2_000
        assertEquals(Tick.Failed(PendingOp.AnswerReplacing(3, 2)), m.tick())
    }

    // ---- S4 probe lines ----

    @Test fun probeVerdictsStartUnknownAndNeverFailWithoutData() {
        val v = MultiCallProbe.verdicts(emptyList(), MultiCallFacts())
        assertEquals(4, v.size)
        assertTrue(v.all { it.startsWith("unknown") })
    }

    @Test fun probeVerdictsPassAndFail() {
        val conf =
            listOf(
                c(1, "active", parent = 3, caps = Wire.Caps.SPLIT or Wire.Caps.DISCONNECT_PARTICIPANT),
                c(3, "active", props = Wire.Props.CONFERENCE, children = listOf(1)),
            )
        val ok =
            MultiCallProbe.verdicts(
                conf,
                MultiCallFacts(
                    swapLatencyMs = 420,
                    bothHeldSeen = false,
                    mergeLatencyMs = 900,
                    mergeProducedConference = true,
                    holdAnswerHeldFirst = true,
                ),
            )
        assertTrue(ok.all { it.startsWith("pass") })
        val bad =
            MultiCallProbe.verdicts(
                conf,
                MultiCallFacts(
                    swapLatencyMs = 1500,
                    bothHeldSeen = false,
                    mergeLatencyMs = 2500,
                    mergeProducedConference = true,
                    holdAnswerHeldFirst = false,
                ),
            )
        assertTrue(bad[0].startsWith("fail") && bad[1].startsWith("fail") && bad[3].startsWith("fail"))
        assertTrue(MultiCallProbe.masks(conf).first().contains("caps=96"))
    }
}
