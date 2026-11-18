package dev.rosty.platform.wa

import android.app.Notification
import android.app.NotificationManager
import dev.rosty.platform.PlatformComponents
import dev.rosty.platform.PlatformEvent
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.identity.CallerIdentity
import dev.rosty.platform.identity.CallerLookup
import dev.rosty.platform.wa.push.PushKeyAgreement
import dev.rosty.platform.wa.push.PushKeyStore
import dev.rosty.platform.wa.push.PushKeys
import dev.rosty.platform.wa.push.SoftPushKeys
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
        PushKeys.install(null)
        CallerLookup.install(null)
        PlatformComponents.install(PlatformComponents(Main::class.java))
    }

    class Main : android.app.Activity()

    class LockedCall : android.app.Activity()

    private fun title(notice: Notification): String =
        notice.extras
            .getParcelable(Notification.EXTRA_CALL_PERSON, android.app.Person::class.java)
            ?.name
            .toString()

    @Test fun aSealedLockedCallNamesTheCallerAndRingsOverTheLockScreen() {
        val keys = SoftPushKeys.generate()
        PushKeys.install(FakeStore(keys))
        PlatformComponents.install(PlatformComponents(Main::class.java, waLockedCall = LockedCall::class.java))
        WaPush.locked = { true }
        val data =
            keys.seal(
                """{"sender":"100000000001@s.whatsapp.net","name":"Ayse"}""",
                mapOf("kind" to "call", "call_id" to "sealed-1"),
            )
        val opened = WaPush.onMessage(context, data)
        assertTrue(opened.content != null)
        val shown = ringing("sealed-1")!!
        assertEquals("Ayse", title(shown))
        // The full-screen intent and Answer open the locked-call page, never the app's pages.
        val full = shadowOf(shown.fullScreenIntent)
        assertEquals(LockedCall::class.java.name, full.savedIntent.component?.className)
        assertEquals("sealed-1", full.savedIntent.getStringExtra(WaIncoming.EXTRA_CALL_ID))
        val answer =
            shown.actions
                .map { shadowOf(it.actionIntent) }
                .single { it.savedIntent.getStringExtra(WaIncoming.EXTRA_ACTION) == WaIncoming.ANSWER }
        assertEquals(LockedCall::class.java.name, answer.savedIntent.component?.className)
        assertEquals("Ayse", answer.savedIntent.getStringExtra(WaIncoming.EXTRA_TITLE))
        WaIncoming.cancel(context, "sealed-1")
    }

    @Test fun aDeviceContactNamesALockedCallThatIsNotSealed() {
        CallerLookup.install { number ->
            if (number ==
                "+100000000001"
            ) {
                CallerIdentity(name = "Ayse Contact")
            } else {
                null
            }
        }
        WaPush.locked = { true }
        WaPush.onMessage(
            context,
            mapOf(
                "kind" to "call",
                "call_id" to "clear-1",
                "sender" to "100000000001@s.whatsapp.net",
                "name" to "Ayse",
            ),
        )
        assertEquals("Ayse Contact", title(ringing("clear-1")!!))
        WaIncoming.cancel(context, "clear-1")
    }

    @Test fun aSealedCallThatDoesNotOpenIsContentFree() {
        val keys = SoftPushKeys.generate()
        PushKeys.install(FakeStore(SoftPushKeys.generate()))
        WaPush.locked = { true }
        val data =
            keys.seal("""{"name":"Ayse"}""", mapOf("kind" to "call", "call_id" to "sealed-2"))
        WaPush.onMessage(context, data)
        assertEquals(context.getString(dev.rosty.platform.R.string.wa_call_title), title(ringing("sealed-2")!!))
        WaIncoming.cancel(context, "sealed-2")
    }

    @Test fun theLockedCallerTitleRules() {
        val contact = { n: String -> if (n == "+100000000001") "Contact" else null }
        val content =
            dev.rosty.platform.wa.push
                .PushContent(sender = "100000000009@s.whatsapp.net", name = "WA name")

        fun opened(
            data: Map<String, String>,
            c: dev.rosty.platform.wa.push.PushContent?,
            sealed: Boolean,
        ) = OpenedPush(data, c, sealed)
        val call = mapOf("kind" to "call", "call_id" to "x")
        assertEquals(
            "WA name",
            waLockedCallerTitle(
                opened(call + ("sender" to content.sender!!) + ("name" to "WA name"), content, true),
                contact,
            ),
        )
        assertEquals(
            "Contact",
            waLockedCallerTitle(opened(call + ("sender" to "100000000001@s.whatsapp.net"), content, true), contact),
        )
        assertEquals(WaIncomingNotice.FALLBACK_TITLE, waLockedCallerTitle(opened(call, null, true), contact))
        // Not sealed: the name in clear is not shown, a device contact is.
        val clear = call + ("sender" to "100000000009@s.whatsapp.net") + ("name" to "WA name")
        assertEquals(WaIncomingNotice.FALLBACK_TITLE, waLockedCallerTitle(opened(clear, null, false), contact))
    }

    /** A [PushKeyStore] over the software key of a test. */
    private class FakeStore(
        private val keys: SoftPushKeys,
    ) : PushKeyStore,
        PushKeyAgreement by keys {
        override fun ensure() = null

        override fun currentId() = "k1"

        override fun rotate() = Unit
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
        // Not "555": an object hash in the extras may hold it.
        assertFalse(text, "Ayse" in text || "100000000001" in text)
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
