package dev.rovsti.platform

import dev.rovsti.core.Envelope
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.search.WireContact
import dev.rovsti.platform.wire.ProviderJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rule 4 of the E3 plan: the wire JSON that `WireJson` builds decodes into the `:core-bridge` model, and so does the
 * `rovsti_core` golden fixture itself; both must give the same value. No second Kotlin model for the same row.
 */
class WireModelsTest {
    private val dir =
        File(requireNotNull(System.getProperty("rovsti.wire.fixtures")) { "rovsti.wire.fixtures is not set" })

    @Serializable
    private data class Contacts(
        val contacts: List<WireContact>,
    )

    @Serializable
    private data class Entries(
        val entries: List<CallLogEntry>,
    )

    private fun fixtureData(name: String): String {
        val root = Envelope.json.parseToJsonElement(File(dir, name).readText()) as JsonObject
        assertEquals(true, root["ok"]?.toString()?.toBooleanStrict())
        return root.getValue("data").toString()
    }

    private fun builtData(wire: String): String =
        (Envelope.json.parseToJsonElement(wire) as JsonObject).getValue("data").toString()

    private fun contactRows() =
        listOf(
            ProviderJson.PhoneRow(17, "Ayşe Yılmaz", "+90 555 111 22 33", "Mobile", starred = true, photoUri = PHOTO),
            ProviderJson.PhoneRow(17, "Ayşe Yılmaz", "0212 555 00 11", starred = true, photoUri = PHOTO),
            ProviderJson.PhoneRow(18, "Özgür Kaya", "0532 123 45 67"),
        )

    @Test fun contactsDecodeIntoWireContact() {
        val direct = Envelope.json.decodeFromString(Contacts.serializer(), fixtureData("contacts_ok.json"))
        val built =
            Envelope.json.decodeFromString(
                Contacts.serializer(),
                builtData(ProviderJson.contacts(contactRows())),
            )
        assertEquals(direct, built)
        assertEquals(2, direct.contacts.size)
        assertEquals("Ayşe Yılmaz", direct.contacts[0].displayName)
        assertEquals(listOf("Mobile", null), direct.contacts[0].numbers.map { it.label })
        assertTrue(direct.contacts[0].starred)
        assertEquals(PHOTO, direct.contacts[0].photoUri)
    }

    @Test fun favoritesDecodeIntoWireContact() {
        val rows = listOf(ProviderJson.PhoneRow(17, "Ayşe Yılmaz", "+90 555 111 22 33", "Mobile", starred = true))
        val direct = Envelope.json.decodeFromString(Contacts.serializer(), fixtureData("favorites_ok.json"))
        val built = Envelope.json.decodeFromString(Contacts.serializer(), builtData(ProviderJson.contacts(rows)))
        assertEquals(direct, built)
    }

    @Test fun callLogDecodesIntoCallLogEntry() {
        val rows =
            listOf(
                ProviderJson.LogRow(104, "+90 555 111 22 33", 3, 1790000000000, 0, "Ayşe Yılmaz", "Mobile", 17),
                ProviderJson.LogRow(103, "05551112233", 1, 1789990000000, 61),
                ProviderJson.LogRow(102, "+4915123456789", 5, 1789980000000),
                ProviderJson.LogRow(101, "", 0, 1789970000000),
            )
        val direct = Envelope.json.decodeFromString(Entries.serializer(), fixtureData("call_log_ok.json"))
        val built = Envelope.json.decodeFromString(Entries.serializer(), builtData(ProviderJson.callLog(rows)))
        assertEquals(direct, built)
        assertEquals(listOf(104L, 103L, 102L, 101L), direct.entries.map { it.id })
        assertEquals(CallLogKind.Missed, direct.entries[0].kind)
        assertEquals(61L, direct.entries[1].durationS)
        assertEquals(17L, direct.entries[0].contactId)
    }

    @Test fun theListSerializerRoundTripsTheModel() {
        val list = Envelope.json.decodeFromString(Contacts.serializer(), fixtureData("contacts_ok.json")).contacts
        val text = Envelope.json.encodeToString(ListSerializer(WireContact.serializer()), list)
        assertEquals(list, Envelope.json.decodeFromString(ListSerializer(WireContact.serializer()), text))
    }

    private companion object {
        const val PHOTO = "content://com.android.contacts/contacts/17/photo"
    }
}
