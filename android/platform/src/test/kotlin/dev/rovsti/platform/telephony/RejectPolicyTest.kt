package dev.rovsti.platform.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Reject cases of the GPUI build's Epic17Test (18.1): the pure policy table. */
class RejectPolicyTest {
    private fun decide(
        text: String = "hi",
        ringing: Boolean = true,
        telecom: Boolean = false,
        respond: Boolean = true,
        number: Boolean = true,
        compose: Boolean = true,
    ) = RejectPolicy.decide(text, ringing, telecom, respond, number, compose)

    @Test fun handoffIsTheDefaultAndOpensMessages() {
        val d = decide()
        assertEquals(RejectPolicy.OPENED, d.code)
        assertEquals(RejectPolicy.Action.HANDOFF, d.action)
    }

    @Test fun telecomNeedsTheCapabilityAndANumber() {
        assertEquals(RejectPolicy.SENT, decide(telecom = true).code)
        assertEquals(RejectPolicy.Action.TELECOM, decide(telecom = true).action)
        // Invalid capability for this call: falls back per call.
        assertEquals(RejectPolicy.OPENED, decide(telecom = true, respond = false).code)
        assertEquals(RejectPolicy.REJECTED_ONLY, decide(number = false).code)
        assertEquals(RejectPolicy.Action.REJECT_ONLY, decide(number = false).action)
        assertEquals(RejectPolicy.REJECTED_ONLY, decide(telecom = true, number = false).code)
    }

    @Test fun withoutAMessagesAppTheCallIsOnlyRejected() {
        assertEquals(RejectPolicy.REJECTED_ONLY, decide(compose = false).code)
        // Telecom does not need an SMS app.
        assertEquals(RejectPolicy.SENT, decide(telecom = true, compose = false).code)
    }

    @Test fun staleOrInvalidTextRejectsNothing() {
        assertEquals(RejectPolicy.STALE, decide(ringing = false).code)
        assertNull(decide(ringing = false).action)
        assertEquals(RejectPolicy.STALE, decide(text = "   ").code)
        assertEquals(RejectPolicy.STALE, decide(text = "x".repeat(161)).code)
        assertEquals(RejectPolicy.OPENED, decide(text = "x".repeat(160)).code)
    }

    @Test fun theLimitCountsCodePointsNotUtf16Units() {
        // 160 emoji are 320 UTF-16 units but 160 characters.
        assertEquals(RejectPolicy.OPENED, decide(text = "😀".repeat(160)).code)
        assertEquals(RejectPolicy.STALE, decide(text = "😀".repeat(161)).code)
    }

    @Test fun theResultCodesAreTheOnesRustReads() {
        assertEquals(
            listOf(0, 1, 2, 3),
            listOf(RejectPolicy.STALE, RejectPolicy.SENT, RejectPolicy.OPENED, RejectPolicy.REJECTED_ONLY),
        )
    }
}
