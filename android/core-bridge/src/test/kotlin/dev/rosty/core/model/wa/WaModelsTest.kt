package dev.rosty.core.model.wa

import dev.rosty.core.Envelope
import dev.rosty.core.model.phone.CallDirection
import dev.rosty.core.model.phone.CallPhase
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
        assertEquals(GatewayEvent.Resync(), json.decodeFromString(GatewayEvent.serializer(), """{"type":"resync"}"""))
        assertEquals(
            GatewayEvent.StatusChanged(null),
            json.decodeFromString(GatewayEvent.serializer(), """{"type":"status_changed"}"""),
        )
    }

    /** The JSON pinned in `rosty_wa_proto` (`epoch_account_and_history_events_are_pinned`). */
    @Test
    fun theEpochAccountAndHistoryFramesDecodeAndEncodeBackAsTheGatewayWroteThem() {
        val wire =
            Json {
                explicitNulls = true
                encodeDefaults = true
            }
        val history = HistoryStatus(HistoryStatus.SYNCING, 55, 120, 48_000, 1_500_000_000_000)
        val frames =
            listOf(
                Pair(
                    """{"type":"hello","epoch":"9f2c41d07ab35e68"}""",
                    GatewayEvent.Hello("9f2c41d07ab35e68"),
                ),
                Pair(
                    """{"type":"resync","epoch":"9f2c41d07ab35e68"}""",
                    GatewayEvent.Resync("9f2c41d07ab35e68"),
                ),
                Pair(
                    """{"type":"resync"}""",
                    GatewayEvent.Resync(),
                ),
                Pair(
                    """{"type":"account_changed","old_pn":"100000000001@s.whatsapp.net","new_pn":"100000000002@s.whatsapp.net"}""",
                    GatewayEvent.AccountChanged("100000000001@s.whatsapp.net", "100000000002@s.whatsapp.net"),
                ),
                Pair(
                    """{"type":"account_changed","new_pn":"100000000002@s.whatsapp.net"}""",
                    GatewayEvent.AccountChanged(newPn = "100000000002@s.whatsapp.net"),
                ),
                Pair(
                    """{"type":"history_synced","chats":12,"progress":40,"sync_type":"full","messages":3500,"oldest_ms":1500000000000}""",
                    GatewayEvent.HistorySynced(12, 40, "full", 3_500, 1_500_000_000_000),
                ),
                Pair(
                    """{"type":"history_synced","chats":0,"progress":null,"sync_type":"on_demand","messages":0}""",
                    GatewayEvent.HistorySynced(0, null, "on_demand"),
                ),
                Pair(
                    """{"type":"state","instance_id":"i","state":"connected","own_jid":"100000000001@s.whatsapp.net","push_name":null,"epoch":"9f2c41d07ab35e68","account_epoch":2,"history":{"state":"syncing","progress":55,"chats":120,"messages":48000,"oldest_ms":1500000000000}}""",
                    GatewayEvent.State(
                        "i",
                        InstanceState.Connected,
                        "100000000001@s.whatsapp.net",
                        epoch = "9f2c41d07ab35e68",
                        accountEpoch = 2,
                        history = history,
                    ),
                ),
            )
        for ((text, event) in frames) {
            assertEquals(event, json.decodeFromString(GatewayEvent.serializer(), text))
            assertEquals(text, wire.encodeToString(GatewayEvent.serializer(), event))
        }
        val state = frames.last().second as GatewayEvent.State
        assertEquals(
            InstanceStatus(
                "i",
                InstanceState.Connected,
                "100000000001@s.whatsapp.net",
                epoch = "9f2c41d07ab35e68",
                accountEpoch = 2,
                history = history,
            ),
            state.status(),
        )
        val status =
            """{"instance_id":"i","state":"connected","own_jid":null,"push_name":null,"account_epoch":3,"history":{"state":"done","progress":100,"chats":412,"messages":5000000000,"oldest_ms":1400000000000}}"""
        val decoded = json.decodeFromString(InstanceStatus.serializer(), status)
        assertEquals(HistoryStatus(HistoryStatus.DONE, 100, 412, 5_000_000_000, 1_400_000_000_000), decoded.history)
        assertEquals(status, wire.encodeToString(InstanceStatus.serializer(), decoded))
    }

    /** What a gateway from before the round sends, and a kind or state a newer one adds. */
    @Test
    fun framesOfOlderAndNewerGatewaysDecode() {
        assertEquals(
            GatewayEvent.HistorySynced(3),
            json.decodeFromString(GatewayEvent.serializer(), """{"type":"history_synced","chats":3,"progress":null}"""),
        )
        val old =
            json.decodeFromString(
                InstanceStatus.serializer(),
                """{"instance_id":"i","state":"connected","own_jid":null,"push_name":null}""",
            )
        assertNull(old.epoch)
        assertNull(old.accountEpoch)
        assertNull(old.history)
        val newer =
            json.decodeFromString(
                GatewayEvent.serializer(),
                """{"type":"history_synced","chats":1,"progress":7,"sync_type":"from_the_future","messages":2}""",
            ) as GatewayEvent.HistorySynced
        assertEquals("from_the_future", newer.syncType)
        val later =
            json.decodeFromString(
                HistoryStatus.serializer(),
                """{"state":"later_state","progress":null,"chats":0,"messages":0,"oldest_ms":null}""",
            )
        assertEquals("later_state", later.state)
        // Handed on to the store, the counts are written even when they are zero: Rust requires them.
        assertEquals(
            """{"state":"idle","chats":0,"messages":0}""",
            json.encodeToString(HistoryStatus.serializer(), HistoryStatus(HistoryStatus.IDLE)),
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
