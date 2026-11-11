package dev.rovsti.phone.wa

import android.content.Intent
import android.os.Bundle
import dev.rovsti.phone.PhoneRoute
import dev.rovsti.phone.consumePhoneExtras
import dev.rovsti.phone.phoneLinkFor
import dev.rovsti.platform.wa.WaCallService
import dev.rovsti.platform.wa.WaIncoming
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Answer, Decline and Hang up of the WhatsApp call notifications reach `WaCallActions` through the receiver (no unlock),
 * Answer opens the call screen, and the ongoing notice's tap opens the In-Call screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaCallActionReceiverTest {
    private val taken = mutableListOf<Intent>()
    private val submitted = mutableListOf<String>()
    private val hungUp = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val job = Job()

    private fun handle(
        intent: Intent,
        payload: String? = "payload",
    ) = WaCallActionReceiver.handle(
        intent,
        take = {
            taken += it
            payload
        },
        submit = {
            submitted += it
            job
        },
        hangUp = {
            hungUp += it
            job
        },
        open = { opened += it },
    )

    private fun action(
        id: String,
        what: String,
    ) = Intent(WaIncoming.ACTION_CALL)
        .putExtra(WaIncoming.EXTRA_CALL_ID, id)
        .putExtra(WaIncoming.EXTRA_ACTION, what)

    @Test fun answerIsTakenHandedOnAndOpensTheCallScreen() {
        assertSame(job, handle(action("c1", WaIncoming.ANSWER)))
        assertEquals(1, taken.size)
        assertEquals(listOf("payload"), submitted)
        assertEquals(listOf("c1"), opened)
    }

    @Test fun declineIsHandedOnWithoutOpeningAnything() {
        assertSame(job, handle(action("c1", WaIncoming.DECLINE)))
        assertEquals(listOf("payload"), submitted)
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun anActionAlreadyTakenDoesNothing() {
        assertNull(handle(action("c1", WaIncoming.ANSWER), payload = null))
        assertEquals(emptyList<String>(), submitted)
        assertEquals(emptyList<String>(), opened)
    }

    @Test fun hangUpEndsTheNamedCall() {
        val intent = Intent(WaCallService.ACTION_HANG_UP).putExtra(WaIncoming.EXTRA_CALL_ID, "c2")
        assertSame(job, handle(intent))
        assertEquals(listOf("c2"), hungUp)
        assertNull(handle(Intent(WaCallService.ACTION_HANG_UP)))
        assertEquals(emptyList<Intent>(), taken)
    }

    @Test fun otherIntentsAreIgnored() {
        assertNull(handle(Intent("something.else").putExtra(WaIncoming.EXTRA_CALL_ID, "c1")))
        assertEquals(emptyList<Intent>(), taken)
    }

    @Test fun theOngoingNoticesTapOpensTheInCallScreenOnce() {
        val extras = Bundle().apply { putString(WaCallService.EXTRA_OPEN_CALL, "c1") }
        assertEquals(listOf(PhoneRoute.InCall), phoneLinkFor(Intent.ACTION_MAIN, null, extras)?.routes)
        assertNull("no WhatsApp call named", phoneLinkFor(Intent.ACTION_MAIN, null, Bundle()))
        val intent = Intent(Intent.ACTION_MAIN).putExtra(WaCallService.EXTRA_OPEN_CALL, "c1")
        intent.consumePhoneExtras()
        assertNull(intent.getStringExtra(WaCallService.EXTRA_OPEN_CALL))
    }
}
