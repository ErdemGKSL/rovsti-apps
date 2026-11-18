package dev.rosty.platform.wa

import android.app.Notification
import android.app.NotificationManager
import dev.rosty.platform.PlatformEvent
import dev.rosty.platform.PlatformEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.After
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

/**
 * A WhatsApp call while the app's vault is locked (`plans/wa-vault.md` 13.3): shown from the push alone, content-free,
 * with nothing looked up and no wake of the event stream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaLockedPushTest {
    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After fun unlock() {
        WaPush.locked = { false }
        WaPush.lidNumber = { null }
    }

    private fun ringing(callId: String): Notification? =
        manager.activeNotifications.firstOrNull { it.id == WaIncoming.notificationId(callId) }?.notification

    @Test fun aLockedCallNamesNobodyAndAsksNothing() {
        var lookedUp = 0
        WaPush.lidNumber = {
            lookedUp += 1
            "+100000000001"
        }
        WaPush.locked = { true }
        val seen = mutableListOf<PlatformEvent>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { PlatformEvents.events.collect { seen += it } }
        WaPush.onMessage(
            context,
            mapOf(
                "kind" to "call",
                "call_id" to "locked-1",
                "sender" to "100000000001@s.whatsapp.net",
                "name" to "Ayse",
            ),
        )
        job.cancel()
        val shown = ringing("locked-1")!!
        val person = shown.extras.getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
        // The generic title in the phone's language: no name and no number.
        assertEquals(context.getString(dev.rosty.platform.R.string.wa_call_title), person?.name.toString())
        val text = shown.extras.toString()
        assertFalse(text, "Ayse" in text || "100000000001" in text || "555" in text)
        // Nothing can be answered over the lock screen: no full-screen intent; Answer opens the app.
        assertNull(shown.fullScreenIntent)
        assertEquals(Notification.VISIBILITY_PUBLIC, shown.visibility)
        assertEquals(0, lookedUp)
        assertTrue("no wake while locked", seen.none { it.kind == WaPush.EVENT })
        WaIncoming.cancel(context, "locked-1")
    }

    @Test fun aLockedAnswerOpensTheAppInsteadOfReachingTheGateway() {
        WaPush.locked = { true }
        WaPush.onMessage(context, mapOf("kind" to "call", "call_id" to "locked-2", "name" to "Ayse"))
        val shown = ringing("locked-2")!!
        val answer =
            shown.actions
                .map { shadowOf(it.actionIntent) }
                .single { it.savedIntent.getStringExtra(WaIncoming.EXTRA_ACTION) == WaIncoming.ANSWER }
        // Answer opens the app (the screen unlock, then the vault's); it never goes to the receiver that answers.
        assertTrue(answer.isActivityIntent)
        assertEquals("locked-2", answer.savedIntent.getStringExtra(WaIncoming.EXTRA_CALL_ID))
        WaIncoming.cancel(context, "locked-2")
    }

    @Test fun aLockedMissedCallIsContentFreeToo() {
        WaPush.locked = { true }
        WaPush.onMessage(
            context,
            mapOf("kind" to "call_end", "call_id" to "locked-3", "missed" to "1", "name" to "Ayse"),
        )
        val missed =
            manager.activeNotifications
                .first { it.id == WaIncoming.missedNotificationId("locked-3") }
                .notification
        assertEquals(
            context.getString(dev.rosty.platform.R.string.wa_call_title),
            missed.extras.getCharSequence(Notification.EXTRA_TITLE).toString(),
        )
        assertFalse("Ayse" in missed.extras.toString())
    }

    @Test fun theContentFreeNoticesKeepOnlyTheCallId() {
        val data = mapOf("kind" to "call", "call_id" to "c9", "sender" to "100000000001@s.whatsapp.net", "name" to "A")
        assertEquals(WaIncomingNotice("c9", WaIncomingNotice.FALLBACK_TITLE), WaIncomingNotice.contentFree(data))
        assertNull(WaIncomingNotice.contentFree(mapOf("kind" to "message", "call_id" to "c9")))
        val end = mapOf("kind" to "call_end", "call_id" to "c9", "missed" to "1", "name" to "A")
        assertEquals(WaMissedNotice("c9", WaIncomingNotice.FALLBACK_TITLE), WaMissedNotice.contentFree(end))
        assertNull(WaMissedNotice.contentFree(end - "missed"))
    }
}
