package dev.rovsti.core.model.wa

import dev.rovsti.core.Envelope
import dev.rovsti.core.model.phone.CallDirection
import dev.rovsti.core.model.phone.CallPhase
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The wire models of the gateway: shapes Rust writes that no fixture pins on their own. Plain JVM. */
class WaModelsTest {
    private val json = Envelope.json

    @Test
    fun messageBodiesUseTheExternalTagSerdeWrites() {
        val text = json.decodeFromString(MessageBody.serializer(), """{"text":"selam"}""")
        assertEquals(MessageBody.Text("selam"), text)
        val media =
            json.decodeFromString(
                MessageBody.serializer(),
                """{"media":{"kind":"image","caption":null,"mime":"image/jpeg","size":9}}""",
            )
        assertEquals(MessageBody.Media(MediaKind.Image, null, "image/jpeg", 9), media)
        assertEquals(MessageBody.Unsupported, json.decodeFromString(MessageBody.serializer(), "\"unsupported\""))
        for (body in listOf(text, media, MessageBody.Unsupported)) {
            assertEquals(
                body,
                json.decodeFromString(MessageBody.serializer(), json.encodeToString(MessageBody.serializer(), body)),
            )
        }
        try {
            json.decodeFromString(MessageBody.serializer(), """{"sticker":1}""")
            fail("an unknown variant must not decode")
        } catch (e: SerializationException) {
            assertTrue(e.message.orEmpty().contains("sticker"))
        }
    }

    @Test
    fun gatewayEventsKeepTheirRecordFieldsFlatNextToTheType() {
        val state =
            json.decodeFromString(
                GatewayEvent.serializer(),
                """{"type":"state","instance_id":"i1","state":"logged_out","own_jid":null,"push_name":null}""",
            ) as GatewayEvent.State
        assertEquals(InstanceStatus("i1", InstanceState.LoggedOut), state.status())
        val call =
            json.decodeFromString(
                GatewayEvent.serializer(),
                """{"type":"call","call_id":"c","peer":"1@s.whatsapp.net","peer_name":"Ada","direction":"out","phase":{"ended":{"failed":"no answer"}},"is_video":true}""",
            ) as GatewayEvent.Call
        assertEquals(CallDirection.Out, call.direction)
        assertEquals(CallPhase.Ended("no answer", failed = true), call.phase)
        val scheduled =
            json.decodeFromString(
                GatewayEvent.serializer(),
                """{"type":"scheduled","id":"s","chat":"c","text":"t","send_at_ms":1,"created_ms":0,"state":{"status":"failed","reason":"offline"}}""",
            ) as GatewayEvent.Scheduled
        assertEquals(ScheduledState.Failed("offline"), scheduled.state)
        val meta =
            json.decodeFromString(
                GatewayEvent.serializer(),
                """{"type":"message_meta","chat":"c","id":"m","meta":{"edited_ms":5,"status":"read"}}""",
            ) as GatewayEvent.MessageMetaChanged
        assertNull(meta.body)
        assertEquals(DeliveryStatus.Read, meta.meta.status)
        assertEquals(GatewayEvent.Resync, json.decodeFromString(GatewayEvent.serializer(), """{"type":"resync"}"""))
        assertEquals(
            GatewayEvent.StatusChanged(null),
            json.decodeFromString(GatewayEvent.serializer(), """{"type":"status_changed"}"""),
        )
    }

    @Test
    fun anUnknownEventTypeIsASerializationErrorNotACrash() {
        try {
            json.decodeFromString(GatewayEvent.serializer(), """{"type":"from_the_future"}""")
            fail("an unknown event must not decode")
        } catch (e: SerializationException) {
            assertTrue(e.message.orEmpty().contains("from_the_future"))
        }
    }

    @Test
    fun requestsAreFlatWithTheHandleNextToTheirFields() {
        val wire =
            Json {
                explicitNulls = true
                encodeDefaults = true
            }
        val serializer = WithHandleSerializer(SendText.serializer())
        val request = WithHandle(42L, SendText("c", "hi"))
        assertEquals(
            """{"chat":"c","text":"hi","quoted_id":null,"handle":42}""",
            wire.encodeToString(serializer, request),
        )
        assertEquals(
            request,
            json.decodeFromString(serializer, """{"chat":"c","text":"hi","quoted_id":null,"handle":42}"""),
        )
        try {
            json.decodeFromString(serializer, """{"chat":"c","text":"hi"}""")
            fail("a request without a handle must not decode")
        } catch (e: SerializationException) {
            assertTrue(e.message.orEmpty().contains("handle"))
        }
        val link = WithHandle<LinkRequest>(1L, LinkRequest.PhoneNumber("+905321234567"))
        val text = json.encodeToString(WithHandleSerializer(LinkRequest.serializer()), link)
        assertTrue(text, """"type":"phone_number"""" in text && """"handle":1""" in text)
    }

    @Test
    fun theStoredLinkNeverPrintsItsKey() {
        val link = StoredLink("https://wa.example.com", "i1", "rwk_secretsecret")
        assertFalse(link.toString().contains("secretsecret"))
        assertTrue(link.toString().contains("wa.example.com"))
    }

    @Test
    fun aProfileShowsTheSavedNameBeforeTheVerifiedAndPushName() {
        val profile = WaProfile("j", pushName = "ayse", contactName = " ", verifiedName = "Ayse Ltd")
        assertEquals("Ayse Ltd", profile.displayName())
        assertEquals("ayse", WaProfile("j", pushName = "ayse").displayName())
        assertNull(WaProfile("j").displayName())
    }

    @Test
    fun aRichChatAndMessageKeepTheirSummaryFieldsFlat() {
        val chat =
            json.decodeFromString(
                RichChat.serializer(),
                """{"jid":"j","name":null,"last":null,"unread":2,"is_group":true,"meta":{"pinned":true},"last_meta":{"forwarded":true}}""",
            )
        assertEquals(ChatSummary("j", null, null, 2, true), chat.summary())
        assertTrue(chat.meta.pinned)
        assertTrue(chat.lastMeta!!.forwarded)
        val plain =
            json.decodeFromString(
                RichMessage.serializer(),
                """{"id":"m","chat":"c","sender":null,"from_me":true,"timestamp_ms":3,"body":"unsupported","reactions":[]}""",
            )
        assertEquals(MessageMeta(), plain.meta)
        assertEquals(WaMessage("m", "c", null, true, 3, MessageBody.Unsupported, emptyList()), plain.message())
        // An empty meta is left out, as Rust leaves it out.
        assertFalse(json.encodeToString(RichMessage.serializer(), plain).contains("meta"))
    }
}
