package dev.rovsti.platform

import dev.rovsti.core.FakeRustCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The headless seam of the old `MessagesSeamTest`, retargeted to the new [Headless] over `FakeRustCore`. The SMS, MMS,
 * notification and scheduled-SMS cases move with their classes (E3.4).
 */
class MessagesSeamTest {
    private val core = FakeRustCore()

    @Before fun install() = Headless.useCore(core)

    @After fun reset() = Headless.useCore(null)

    @Test fun anEmptyHeadlessAnswerIsNoDecision() {
        assertNull(Headless.answer(null))
        assertNull(Headless.answer("  "))
        assertEquals("{}", Headless.answer("{}"))
    }

    @Test fun anErrorEnvelopeIsNoDecision() {
        assertNull(Headless.answer("""{"v":1,"ok":false,"error":"bad"}"""))
        assertEquals("""{"v":1,"ok":true}""", Headless.answer("""{"v":1,"ok":true}"""))
        assertEquals("not json", Headless.answer("not json"))
    }

    @Test fun aScriptedAnswerComesBack() {
        core.onHeadless("sms_classify") { """{"v":1,"ok":true,"data":{"spam":false}}""" }
        assertEquals("""{"v":1,"ok":true,"data":{"spam":false}}""", Headless.call("sms_classify", "{}"))
    }

    @Test fun blankErrorAndUnknownKindsGiveNull() {
        core.onHeadless("blank") { "   " }
        core.onHeadless("err") { """{"v":1,"ok":false,"error":"x"}""" }
        assertNull(Headless.call("blank", "{}"))
        assertNull(Headless.call("err", "{}"))
        assertNull(Headless.call("never_scripted", "{}"))
    }

    @Test fun theMainThreadCheckIsOptIn() {
        Headless.assertOffMain = false
        assertNull(Headless.call("never_scripted", "{}"))
    }
}
