@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.search

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `search.*` and `t9.*` (E2.9), mirroring `crates/rovsti_ffi/fixtures/{search,t9}.*`. The contact index lives
// in Rust behind a handle: a request names it, `search.index_new` opens it. Stability: `compose_stability.conf`.

/** Name order setting: "Ayse Yilmaz" or "Yilmaz, Ayse". */
@Serializable
enum class NameFormat {
    @SerialName("first_last")
    FirstLast,

    @SerialName("last_first")
    LastFirst,
}

/** One number of a contact (`rovsti_core::wire::WireNumber`). */
@Serializable
data class WireNumber(
    val raw: String,
    @EncodeDefault(NEVER) val label: String? = null,
    /** The contact's default (super-primary) number. */
    @EncodeDefault(NEVER) val default: Boolean = false,
)

/** A contact as pulled from `ContactsContract` (`rovsti_core::wire::WireContact`). */
@Serializable
data class WireContact(
    val id: Long,
    @SerialName("display_name") val displayName: String,
    val numbers: List<WireNumber>,
    @EncodeDefault(NEVER) val starred: Boolean = false,
    @SerialName("photo_uri") @EncodeDefault(NEVER) val photoUri: String? = null,
    @SerialName("lookup_key") @EncodeDefault(NEVER) val lookupKey: String? = null,
    @SerialName("display_name_alt") @EncodeDefault(NEVER) val displayNameAlt: String? = null,
    @SerialName("sort_key") @EncodeDefault(NEVER) val sortKey: String? = null,
    @SerialName("sort_key_alt") @EncodeDefault(NEVER) val sortKeyAlt: String? = null,
    @EncodeDefault(NEVER) val email: String? = null,
    @SerialName("updated_ms") @EncodeDefault(NEVER) val updatedMs: Long? = null,
)

/** `search.index_new` request. */
@Serializable
data class IndexNewRequest(
    val contacts: List<WireContact>,
    @SerialName("name_format") @EncodeDefault(NEVER) val nameFormat: NameFormat? = null,
)

/** `search.index_new` and `search.index_replace` answer. */
@Serializable
data class IndexAnswer(
    val handle: Long,
    val count: Int,
)

/** `search.index_replace` request: new contacts for an open index. */
@Serializable
data class IndexReplaceRequest(
    val handle: Long,
    val contacts: List<WireContact>,
)

@Serializable
data class CallCount(
    val id: Long,
    val count: Int,
)

/** `search.set_call_counts` request: calls per contact (see `call_log.counts`); the T9 strip ranks callers first. */
@Serializable
data class CallCountsRequest(
    val handle: Long,
    val counts: List<CallCount>,
)

/** `search.query` and `search.query_loose` request. A null [limit] answers every match. */
@Serializable
data class QueryRequest(
    val handle: Long,
    val query: String,
    @EncodeDefault(NEVER) val limit: Int? = null,
)

/** The matches in answer order: [ids] and [rows] line up; [total] counts before [limit] cut the list. */
@Serializable
data class QueryAnswer(
    val ids: List<Long>,
    val rows: List<WireContact>,
    val total: Int,
)

@Serializable
data class MatchNumberRequest(
    val handle: Long,
    val number: String,
)

/** The contact owning a number, or both null. */
@Serializable
data class MatchNumberAnswer(
    val id: Long?,
    val row: WireContact?,
)

@Serializable
data class FoldRequest(
    val text: String,
)

@Serializable
data class FoldAnswer(
    val folded: String,
)

/** `t9.query` request: [digits] are ASCII `0`-`9`, anything else answers no hits. */
@Serializable
data class T9Request(
    val handle: Long,
    val digits: String,
    @SerialName("name_format") @EncodeDefault(NEVER) val nameFormat: NameFormat? = null,
)

/** One T9 suggestion. [nameSpan] is the characters `start..end` of the name that the digits spell. */
@Serializable
data class T9Row(
    @SerialName("contact_idx") val contactIdx: Int,
    val id: Long,
    val name: String,
    @SerialName("number_idx") val numberIdx: Int,
    val number: String,
    @SerialName("name_span") val nameSpan: List<Int>?,
    /** 0 exact number, 1 name prefix, 2 word start, 3 number contains. */
    val rank: Int,
)

@Serializable
data class T9Answer(
    val hits: List<T9Row>,
)
