package dev.rosty.messages.wa

import android.app.NotificationManager
import android.graphics.Bitmap
import dev.rosty.messages.TestMessagesApp
import dev.rosty.messages.TestMessagesContainer
import dev.rosty.messages.notify.ContactNotificationAvatars
import dev.rosty.messages.notify.Digest
import dev.rosty.messages.notify.MessagesNotifier
import dev.rosty.messages.notify.NotificationAvatars
import dev.rosty.messages.notify.NotifyPolicy
import dev.rosty.messages.spam.prefsWith
import dev.rosty.platform.PlatformEvents
import dev.rosty.platform.notify.MessageReminders
import dev.rosty.platform.notify.NotificationSpec
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the app keeps for a WhatsApp link outside the store ([messagesLinkCleanup]), on the test container: another
 * account, or an unlink, drops the drafts, mutes, notifications and reminders of the `wa:` chats and leaves
 * everything of SMS threads alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestMessagesApp::class)
class WaLinkCleanupTest {
    private val app = RuntimeEnvironment.getApplication() as TestMessagesApp
    private val container: TestMessagesContainer get() = app.container
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val ada = WaRekeys.tagOf("905550000001@s.whatsapp.net")
    private val group = WaRekeys.tagOf("120363000000000001@g.us")
    private val sms = "sms:7"

    @Before fun setUp() {
        PlatformEvents.uiAttached = false
        NotifyPolicy.current = NotifyPolicy()
        MessagesNotifier.avatars = NotificationAvatars { _, _ -> Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888) }
    }

    @After fun tearDown() {
        NotifyPolicy.current = NotifyPolicy()
        MessagesNotifier.avatars = ContactNotificationAvatars
        MessagesNotifier.onDismissed = null
        MessageReminders.armed(app).forEach { MessageReminders.disarm(app, it.id) }
        nm.cancelAll()
    }

    private fun notify(tag: String) =
        MessagesNotifier.show(app, Digest(NotificationSpec(tag, "ada", listOf("Are you coming?"), null, true, false)))

    private fun remind(
        id: Long,
        tag: String,
        messageId: String,
    ) = MessageReminders.arm(
        app,
        JSONObject()
            .put("id", id)
            .put("tag", tag)
            .put("message_id", messageId)
            .put("message_at_ms", 1_789_999_000_000L)
            .put("at_ms", 4_102_444_800_000L)
            .put("notif_title", "Reminder: ada")
            .put("notif_body", "Are you coming?")
            .toString(),
    )

    private fun tags(): Set<String> = nm.activeNotifications.map { it.tag }.toSet() - MessagesNotifier.SUMMARY_TAG

    @Test fun everythingKeptForTheWhatsAppChatsGoesAndSmsStays() {
        val core =
            RekeyPrefsCore(
                prefs =
                    prefsWith().copy(
                        muted = listOf(group, sms),
                        muteUntil =
                            mapOf(
                                ada to 4_102_444_800_000L,
                                sms to 9L,
                            ),
                    ),
                drafts = mapOf(ada to "half a thought", group to "for everyone", sms to "ok"),
                core = container.fakeRust,
            )
        runBlocking { container.store.load() }
        // The place cards' tile cache (R6 owner decision): a bounded disk cache that goes with the link.
        val tiles = java.io.File(app.cacheDir, dev.rosty.messages.wa.rich.PlaceMaps.DIR)
        java.io
            .File(tiles, "osm/14/1/2.pbf")
            .apply { parentFile!!.mkdirs() }
            .writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(notify(ada))
        assertTrue(notify(sms))
        assertTrue(remind(41, ada, "A1"))
        assertTrue(remind(42, sms, "sms:12"))

        runBlocking { messagesLinkCleanup(container).forget("inst1") }

        assertEquals(mapOf(sms to "ok"), container.store.drafts.value)
        val prefs = container.store.prefs.value!!
        assertEquals(listOf(sms), prefs.muted)
        assertEquals(mapOf(sms to 9L), prefs.muteUntil)
        // The notifier judges by the mutes that are left.
        assertFalse(NotifyPolicy.current.isMuted(group, nowMs = 0))
        assertEquals(setOf(sms), tags())
        assertEquals(mapOf(42L to sms), MessageReminders.armed(app).associate { it.id to it.tag })
        assertTrue(runBlocking { container.store.flush() })
        assertEquals(mapOf(sms to "ok"), core.drafts)
        assertEquals(listOf(sms), core.prefs.muted)
        assertFalse(tiles.exists())

        // Again (the store may say it twice): nothing more changes.
        runBlocking { messagesLinkCleanup(container).forget("inst1") }
        assertEquals(mapOf(sms to "ok"), container.store.drafts.value)
        assertEquals(setOf(sms), tags())
    }

    @Test fun placeTilesOpenAsABoundedDiskCacheUnderTheCacheDirectory() {
        val dir = java.io.File(app.cacheDir, dev.rosty.messages.wa.rich.PlaceMaps.DIR)
        val maps =
            dev.rosty.messages.wa.rich.PlaceMaps(dir, staticMap = {
                dev.rosty.core.Outcome
                    .Err(
                        dev.rosty.core.RustError
                            .NotFound("map"),
                    )
            })
        val request = maps.openRequest()
        assertEquals(dir.absolutePath, request.dir)
        assertFalse(request.memory)
        assertEquals(20L * 1024 * 1024, request.cacheCap)
        // Without a directory (tests) nothing is written.
        val memory =
            dev.rosty.messages.wa.rich.PlaceMaps(null, staticMap = {
                dev.rosty.core.Outcome
                    .Err(
                        dev.rosty.core.RustError
                            .NotFound("map"),
                    )
            })
        assertTrue(memory.openRequest().memory)
    }
}
