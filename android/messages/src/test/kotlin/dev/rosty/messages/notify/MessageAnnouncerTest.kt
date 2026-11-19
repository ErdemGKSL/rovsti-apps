package dev.rosty.messages.notify

import dev.rosty.core.FakeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.model.messages.MessagesPrefs
import dev.rosty.core.model.messages.NotificationSpec
import dev.rosty.core.model.messages.NotifyAnswer
import dev.rosty.core.model.messages.NotifyRequest
import dev.rosty.core.model.messages.OtpAnswer
import dev.rosty.core.model.messages.OtpHit
import dev.rosty.core.model.messages.OtpRequest
import dev.rosty.core.model.wa.ChatMeta
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.MessageBody
import dev.rosty.core.model.wa.MessageMeta
import dev.rosty.core.model.wa.Quoted
import dev.rosty.core.model.wa.RichChat
import dev.rosty.core.ops.NativeMessagesOps
import dev.rosty.messages.names.FakeLiveNames
import dev.rosty.messages.names.PersonNames
import dev.rosty.platform.PlatformEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** E6.9: the notifications the app makes itself from the WhatsApp event stream (GPUI `notify_whatsapp`). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessageAnnouncerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val rust = FakeRustCore()
    private val posted = ArrayList<Digest>()
    private val chats = HashMap<String, RichChat>()
    private val names = HashMap<String, String>()
    private val bookChanges = MutableStateFlow(0)
    private val live = FakeLiveNames()
    private val now = 1_800_000_000_000L
    private val ada = "905550000002@s.whatsapp.net"
    private val club = "120363000000000001@g.us"

    @Before fun setUp() {
        PlatformEvents.uiAttached = true
        NotifyPolicy.current = NotifyPolicy()
        names["+905550000002"] = "Ada Lovelace"
        names["+905550000003"] = "Deniz Aydın"
        chats[club] = RichChat(club, "Book club", null, 0, true)
    }

    @After fun tearDown() {
        PlatformEvents.uiAttached = false
        OpenConversation.tag?.let(OpenConversation::leave)
        MessagesNotifier.onDismissed = null
    }

    /** Rust's `notify.build` and `otp.find`, in miniature. */
    private fun scriptRust() {
        rust.onTyped("otp.find", OtpRequest.serializer(), OtpAnswer.serializer()) { req ->
            val code = Regex("\\d{6}").find(req.body)
            Outcome.Ok(OtpAnswer(code?.let { OtpHit(it.value, it.range.first, it.range.last + 1) }))
        }
        rust.onTyped("notify.build", NotifyRequest.serializer(), NotifyAnswer.serializer()) { req ->
            val lines = req.recent.map { if (req.isGroup) "${it.senderTitle}: ${it.body}" else it.body }
            val spec =
                NotificationSpec(
                    req.recent.last().thread,
                    req.threadTitle,
                    lines,
                    req.otpCode,
                    req.otpCode == null,
                    false,
                )
            Outcome.Ok(NotifyAnswer(spec, Digests.MAX_LINES))
        }
    }

    private fun announcer(
        scope: CoroutineScope,
        pushWake: Boolean = false,
        own: List<String> = emptyList(),
    ) = MessageAnnouncer(
        context,
        scope,
        AnnouncerSources(
            ops = NativeMessagesOps(rust),
            prefs = MutableStateFlow<MessagesPrefs?>(null),
            loadPrefs = {},
            live = emptyFlow(),
            chatOf = { chats[it] },
            names = PersonNames({ names[it] }, bookChanges, live),
            preview = { (it as? MessageBody.Text)?.text ?: "Photo" },
            pushWake = pushWake,
            now = { now },
            ownIds = { own },
        ),
        post = { _, digest -> posted.add(digest) },
        shown = { _, tag -> posted.lastOrNull { it.spec.tag == tag } },
    )

    private fun message(
        id: String,
        chat: String = ada,
        text: String = "Are you coming?",
        sender: String? = null,
        fromMe: Boolean = false,
        at: Long = now - 1_000,
    ) = GatewayEvent.Message(id, chat, sender, fromMe, at, MessageBody.Text(text), emptyList())

    @Test fun aLiveMessageIsDigestedByRustAndPosted() =
        runTest(UnconfinedTestDispatcher()) {
            scriptRust()
            val announcer = announcer(this)

            assertTrue(announcer.onWhatsApp(message("m1")))
            assertTrue(announcer.onWhatsApp(message("m2", text = "We start at six")))

            val spec = posted.last().spec
            assertEquals("wa:$ada", spec.tag)
            assertEquals("Ada Lovelace", spec.title)
            assertEquals(listOf("Are you coming?", "We start at six"), spec.lines)
            assertTrue(spec.reply)
        }

    @Test fun theTitleFollowsTheNameOrder() =
        runTest(UnconfinedTestDispatcher()) {
            scriptRust()
            val announcer = announcer(this)
            names.remove("+905550000002")
            chats[ada] = RichChat(ada, "ada.l", null, 0, false, ChatMeta(pushName = "Ada"))

            // No contact: WhatsApp's push name comes before the stored chat name.
            announcer.onWhatsApp(message("m1"))
            assertEquals("Ada", posted.last().spec.title)

            // A saved contact beats both.
            names["+905550000002"] = "Ada Lovelace"
            announcer.onWhatsApp(message("m2"))
            assertEquals("Ada Lovelace", posted.last().spec.title)
            assertTrue(live.asked.isEmpty())
        }

    @Test fun anUnknownNumberIsNotifiedAtOnceAndRenamedInPlaceWhenWhatsAppNamesIt() =
        runTest(UnconfinedTestDispatcher()) {
            scriptRust()
            names.clear()
            val announcer = announcer(this)

            assertTrue(announcer.onWhatsApp(message("m1")))
            assertEquals("+905550000002", posted.single().spec.title)
            assertEquals(listOf("+905550000002"), live.asked.distinct())

            live.land("+905550000002", "Ada")
            // The same notification, renamed without a second sound.
            assertEquals(2, posted.size)
            assertEquals("Ada", posted.last().spec.title)
            assertTrue(posted.last().spec.silent)
            assertEquals(posted.first().spec.lines, posted.last().spec.lines)

            // A group sender nobody names is named by WhatsApp's answer too.
            announcer.onWhatsApp(message("g1", chat = club, text = "Hi all", sender = ada))
            assertEquals(listOf("Ada: Hi all"), posted.last().spec.lines)
        }

    @Test fun aGroupMessageNamesItsSender() =
        runTest(UnconfinedTestDispatcher()) {
            scriptRust()
            val announcer = announcer(this)

            announcer.onWhatsApp(message("m1", chat = club, text = "Hi all", sender = "905550000003@s.whatsapp.net"))

            val spec = posted.single().spec
            assertEquals("Book club", spec.title)
            assertEquals(listOf("Deniz Aydın: Hi all"), spec.lines)
        }

    @Test fun aPasscodeTurnsTheReplyOff() =
        runTest(UnconfinedTestDispatcher()) {
            scriptRust()
            announcer(this).onWhatsApp(message("m1", text = "Your code is 482913"))

            assertEquals("482913", posted.single().spec.otp)
            assertFalse(posted.single().spec.reply)
        }

    @Test fun myOwnOldRepeatedAndStatusMessagesStayQuiet() =
        runTest(UnconfinedTestDispatcher()) {
            scriptRust()
            val announcer = announcer(this)

            assertFalse(announcer.onWhatsApp(message("mine", fromMe = true)))
            assertFalse(announcer.onWhatsApp(message("old", at = now - MessageAnnouncer.RECENT_MS - 1)))
            assertFalse(announcer.onWhatsApp(message("status", chat = "status@broadcast")))
            assertTrue(announcer.onWhatsApp(message("m1")))
            assertFalse("a replayed event is not announced twice", announcer.onWhatsApp(message("m1")))
            assertEquals(1, posted.size)
        }

    @Test fun theOpenChatAndAChatMutedInWhatsAppStayQuiet() =
        runTest(UnconfinedTestDispatcher()) {
            scriptRust()
            val announcer = announcer(this)
            OpenConversation.enter("wa:$ada")
            assertFalse(announcer.onWhatsApp(message("m1")))
            OpenConversation.leave("wa:$ada")

            chats[ada] = RichChat(ada, null, null, 0, false, ChatMeta(mutedUntilMs = -1))
            assertFalse(announcer.onWhatsApp(message("m2")))
            chats[ada] = RichChat(ada, null, null, 0, false, ChatMeta(mutedUntilMs = now - 1))
            assertTrue("a mute that ended no longer silences", announcer.onWhatsApp(message("m3")))
            // The open chat's message never entered the digest; the muted one did not either.
            assertEquals(listOf("Are you coming?"), posted.single().spec.lines)
        }

    @Test fun aMentionOfMeOrAReplyToMeGetsThroughAWhatsAppMute() =
        runTest(UnconfinedTestDispatcher()) {
            scriptRust()
            val me = "100000000001@s.whatsapp.net"
            val myLid = "123456789012001@lid"
            val sender = "100000000012@s.whatsapp.net"
            val announcer = announcer(this, own = listOf(me, myLid))
            chats[club] = RichChat(club, "Club", null, 0, true, ChatMeta(mutedUntilMs = -1))

            // Held while its details are on their way; details that do not name me keep it quiet.
            assertFalse(announcer.onWhatsApp(message("g1", chat = club, text = "Hi all", sender = sender)))
            assertFalse(announcer.onMeta(GatewayEvent.MessageMetaChanged(club, "g1", meta = MessageMeta())))

            // A mention of my LID (a group that addresses by LID) is a mention of me.
            assertFalse(announcer.onWhatsApp(message("g2", chat = club, text = "@you look", sender = sender)))
            assertTrue(
                announcer.onMeta(
                    GatewayEvent.MessageMetaChanged(club, "g2", meta = MessageMeta(mentions = listOf(myLid))),
                ),
            )
            // A reply to a message of mine too, once.
            assertFalse(announcer.onWhatsApp(message("g3", chat = club, text = "Agreed", sender = sender)))
            val reply = MessageMeta(quoted = Quoted("x1", sender = "100000000001:3@s.whatsapp.net"))
            assertTrue(announcer.onMeta(GatewayEvent.MessageMetaChanged(club, "g3", meta = reply)))
            assertFalse("announced once", announcer.onMeta(GatewayEvent.MessageMetaChanged(club, "g3", meta = reply)))
            assertEquals(2, posted.size)
        }

    @Test fun withNoUiAttachedThePushServicePostsWhenThereIsOne() =
        runTest(UnconfinedTestDispatcher()) {
            scriptRust()
            PlatformEvents.uiAttached = false

            assertFalse(announcer(this, pushWake = true).onWhatsApp(message("m1")))
            assertTrue(
                "the plain flavor has only the stream",
                announcer(this, pushWake = false).onWhatsApp(message("m2")),
            )
        }

    @Test fun withoutRustAPlainNotificationIsPosted() =
        runTest(UnconfinedTestDispatcher()) {
            // Nothing scripted: every op answers Unsupported, as when the native library is missing.
            val announcer = announcer(this)

            assertTrue(announcer.onWhatsApp(message("m1")))
            assertTrue(
                announcer.onWhatsApp(message("m2", chat = club, text = "Hi", sender = "905550000003@s.whatsapp.net")),
            )

            val plain = posted.first().spec
            assertEquals("Ada Lovelace", plain.title)
            assertEquals(listOf("Are you coming?"), plain.lines)
            assertTrue(plain.reply)
            assertNull(plain.otp)
            assertEquals(listOf("Deniz Aydın: Hi"), posted.last().spec.lines)
        }

    @Test fun aDismissedConversationStartsItsDigestAgain() =
        runTest(UnconfinedTestDispatcher()) {
            scriptRust()
            val announcer = announcer(backgroundScope)
            announcer.start()
            announcer.onWhatsApp(message("m1"))

            MessagesNotifier.dismiss(context, "wa:$ada")
            announcer.onWhatsApp(message("m2", text = "Hello?"))

            assertEquals(listOf("Hello?"), posted.last().spec.lines)
        }

    @Test fun theDigestKeepsTheNewestLines() {
        val digests = Digests(maxLines = 2)
        val lines =
            (1..3).map {
                digests.push(
                    dev.rosty.core.model.messages
                        .Incoming("sms:1", "", "m$it", it.toLong(), false),
                )
            }
        assertEquals(listOf("m2", "m3"), lines.last().map { it.body })
        digests.clear("sms:1")
        assertEquals(
            1,
            digests
                .push(
                    dev.rosty.core.model.messages
                        .Incoming("sms:1", "", "new", 9, false),
                ).size,
        )
    }
}
