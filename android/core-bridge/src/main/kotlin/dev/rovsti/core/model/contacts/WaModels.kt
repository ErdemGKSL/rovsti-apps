@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.contacts

import dev.rovsti.core.model.search.WireContact
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The WhatsApp decisions of the contact list and card (AD-CT6), `crates/rovsti_ffi/src/ops/contacts/wa.rs`. Stateless:
// every request carries the chats, the lookup and the presence the app holds. Stability: `compose_stability.conf`.

/** `contacts.wa_marks` request: the book and the user parts of the 1:1 chats of the instance. */
@Serializable
data class WaMarksRequest(
    val contacts: List<WireContact>,
    @SerialName("chat_users") val chatUsers: List<String>,
    /** The home calling code (`"90"`): a national number is compared in its E.164 form (F39). */
    @SerialName("home_cc") @EncodeDefault(NEVER) val homeCc: String? = null,
)

/** `contacts.wa_marks` answer: contact id to the JID of its chat. */
@Serializable
data class WaMarksAnswer(
    val marks: Map<Long, String>,
)

/** One answer of a number lookup (`rovsti_wa_proto::WaUser`). */
@Serializable
data class WaLookupUser(
    val jid: String,
    @SerialName("on_whatsapp") val onWhatsapp: Boolean,
)

/** Where one contact's lookup stands: [state] is `checking`, `done` or `failed`. */
@Serializable
data class WaLookup(
    val state: String,
    @EncodeDefault(NEVER) val users: List<WaLookupUser> = emptyList(),
) {
    companion object {
        const val CHECKING = "checking"
        const val DONE = "done"
        const val FAILED = "failed"
    }
}

/** The latest presence of the contact's JID. */
@Serializable
data class WaPresence(
    val available: Boolean,
    @SerialName("last_seen_ms") @EncodeDefault(NEVER) val lastSeenMs: Long? = null,
)

/** `contacts.wa_card` request. */
@Serializable
data class WaCardRequest(
    val id: Long,
    /** The contact's dialable numbers, in the order of their rows. */
    val numbers: List<String>,
    @SerialName("chat_users") @EncodeDefault(NEVER) val chatUsers: List<String> = emptyList(),
    @EncodeDefault(NEVER) val lookup: WaLookup? = null,
    @EncodeDefault(NEVER) val presence: WaPresence? = null,
    @SerialName("now_ms") val nowMs: Long,
    /** The phone's offset from UTC, for "last seen" lines. */
    @SerialName("offset_s") @EncodeDefault(NEVER) val offsetS: Int = 0,
    /** The home calling code (`"90"`): a national number is looked up and matched in its E.164 form (F39). */
    @SerialName("home_cc") @EncodeDefault(NEVER) val homeCc: String? = null,
)

/** `contacts.wa_card` answer. */
@Serializable
data class WaCardAnswer(
    /** The JIDs to look up now; empty when an answer is held or the target is not a bare number. */
    @SerialName("lookup_request") val lookupRequest: List<String>,
    /** The JID chat and call go to, once known. */
    val jid: String?,
    /** One flag per number: that row carries a WhatsApp chip. */
    val chips: List<Boolean>,
    @SerialName("status_line") val statusLine: String?,
    /** One entry per number: its JID when it is on WhatsApp. */
    @SerialName("number_jids") val numberJids: List<String?>,
)

/** The parts of a WhatsApp profile the card's lines need. */
@Serializable
data class WaProfileInput(
    val jid: String,
    @SerialName("push_name") @EncodeDefault(NEVER) val pushName: String? = null,
    @EncodeDefault(NEVER) val about: String? = null,
    @EncodeDefault(NEVER) val business: Boolean = false,
    @SerialName("verified_name") @EncodeDefault(NEVER) val verifiedName: String? = null,
)

/** `contacts.wa_profile_lines` request. */
@Serializable
data class WaProfileLinesRequest(
    val profile: WaProfileInput,
    @SerialName("contact_name") val contactName: String,
)

/** One "label: value" line of the card (WhatsApp name, Business, About). */
@Serializable
data class WaProfileLine(
    val label: String,
    val value: String,
)

@Serializable
data class WaProfileLinesAnswer(
    val lines: List<WaProfileLine>,
)
