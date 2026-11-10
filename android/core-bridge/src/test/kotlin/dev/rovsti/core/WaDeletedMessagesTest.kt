package dev.rovsti.core

import dev.rovsti.core.model.wa.DELETED_BY_ME
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.MessageBody
import dev.rovsti.core.model.wa.MessageMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gateway keeps deleted messages and edit history (commit bea5509, schema v6) and adds optional fields. The bridge
 * decodes strictly (`ignoreUnknownKeys = false`), so the models must know them or a thread with one deleted message
 * would fail to load.
 */
class WaDeletedMessagesTest {
    private val json = Envelope.json

    @Test
    fun deletedMessageMetaWithKeptContentDecodes() {
        val meta =
            json.decodeFromString<MessageMeta>(
                """
                {"revoked":true,"deleted":true,"deleted_at":1762760000000,"deleted_by":"905551112233@s.whatsapp.net",
                 "original":{"body":{"text":"hello"},"meta":{"edited_ms":1762750000000}},
                 "edit_history":[{"text":"helo","edited_at":1762750000000}]}
                """.trimIndent(),
            )
        assertTrue(meta.deleted && meta.revoked)
        assertEquals(1762760000000L, meta.deletedAt)
        assertEquals("905551112233@s.whatsapp.net", meta.deletedBy)
        assertEquals(MessageBody.Text("hello"), meta.original?.body)
        assertEquals("helo", meta.editHistory.single().text)
    }

    @Test
    fun oldGatewayMetaStillDecodesWithDefaults() {
        val meta = json.decodeFromString<MessageMeta>("""{"revoked":true}""")
        assertTrue(meta.revoked && !meta.deleted && meta.original == null && meta.editHistory.isEmpty())
    }

    @Test
    fun messageDeletedEventCarriesWhoAndWhen() {
        val event =
            json.decodeFromString<GatewayEvent>(
                """{"type":"message_deleted","chat":"c@s.whatsapp.net","id":"A1","deleted_at":5,"deleted_by":"me"}""",
            ) as GatewayEvent.MessageDeleted
        assertEquals(5L, event.deletedAt)
        assertEquals(DELETED_BY_ME, event.deletedBy)
        val old =
            json.decodeFromString<GatewayEvent>("""{"type":"message_deleted","chat":"c@s.whatsapp.net","id":"A1"}""")
                as GatewayEvent.MessageDeleted
        assertEquals(null, old.deletedAt)
    }
}
