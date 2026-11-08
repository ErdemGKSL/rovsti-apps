@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.contacts

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Types several `contacts.*` ops share (E2.10), mirroring `rovsti_core::contact_edit` and the DTOs of
// `crates/rovsti_ffi/src/ops/contacts`. Stability: `compose_stability.conf`.

/** What a number, email or address is (`FieldKind`); `Custom` takes the text from [DraftField.label]. */
@Serializable
enum class FieldKind {
    @SerialName("mobile")
    Mobile,

    @SerialName("home")
    Home,

    @SerialName("work")
    Work,

    @SerialName("custom")
    Custom,

    @SerialName("other")
    Other,
}

/** One number, email or address of a draft. At most one entry per list is the default ([primary]). */
@Serializable
data class DraftField(
    val value: String = "",
    val kind: FieldKind = FieldKind.Other,
    @EncodeDefault(NEVER) val label: String? = null,
    @EncodeDefault(NEVER) val primary: Boolean = false,
)

@Serializable
enum class EventKind {
    @SerialName("birthday")
    Birthday,

    @SerialName("anniversary")
    Anniversary,

    @SerialName("custom")
    Custom,

    @SerialName("other")
    Other,
}

/** A dated event; [date] is `YYYY-MM-DD` or `--MM-DD`. */
@Serializable
data class DraftEvent(
    val date: String = "",
    val kind: EventKind = EventKind.Birthday,
    @EncodeDefault(NEVER) val label: String? = null,
)

@Serializable
enum class RelationKind {
    @SerialName("spouse")
    Spouse,

    @SerialName("partner")
    Partner,

    @SerialName("child")
    Child,

    @SerialName("mother")
    Mother,

    @SerialName("father")
    Father,

    @SerialName("parent")
    Parent,

    @SerialName("brother")
    Brother,

    @SerialName("sister")
    Sister,

    @SerialName("relative")
    Relative,

    @SerialName("friend")
    Friend,

    @SerialName("manager")
    Manager,

    @SerialName("assistant")
    Assistant,

    @SerialName("custom")
    Custom,
}

@Serializable
data class DraftRelation(
    val name: String = "",
    val kind: RelationKind = RelationKind.Custom,
    @EncodeDefault(NEVER) val label: String = "",
)

/**
 * A contact being created, edited, imported or exported (`ContactDraft`). Every field has a default so a screen can
 * start from `ContactDraft()`; empty optional lists and texts are left out of the JSON, as Rust does.
 */
@Serializable
data class ContactDraft(
    @EncodeDefault(NEVER) val id: Long? = null,
    val given: String = "",
    val family: String = "",
    val org: String = "",
    @EncodeDefault(NEVER) val nickname: String = "",
    @EncodeDefault(NEVER) val title: String = "",
    @SerialName("phonetic_given") @EncodeDefault(NEVER) val phoneticGiven: String = "",
    @SerialName("phonetic_family") @EncodeDefault(NEVER) val phoneticFamily: String = "",
    val note: String = "",
    val numbers: List<DraftField> = emptyList(),
    val emails: List<DraftField> = emptyList(),
    @EncodeDefault(NEVER) val addresses: List<DraftField> = emptyList(),
    @EncodeDefault(NEVER) val websites: List<String> = emptyList(),
    @EncodeDefault(NEVER) val events: List<DraftEvent> = emptyList(),
    @EncodeDefault(NEVER) val relations: List<DraftRelation> = emptyList(),
    val groups: List<Long> = emptyList(),
    val starred: Boolean = false,
    @EncodeDefault(NEVER) val account: String = "",
)

/** A contact group and its members. */
@Serializable
data class Group(
    val id: Long = 0,
    val title: String = "",
    val members: List<Long> = emptyList(),
    @EncodeDefault(NEVER) val account: String = "",
)

/** One account contacts can be stored in; [key] is `type|name`, empty for "on this phone". */
@Serializable
data class Account(
    val key: String = "",
    val name: String = "",
    val kind: String = "",
)

/** A local calendar day. */
@Serializable
data class Day(
    val year: Int,
    val month: Int,
    val day: Int,
)

/** One number of a contact, ready to draw and to dial. */
@Serializable
data class NumberVm(
    val raw: String,
    val display: String,
    val label: String,
    val default: Boolean,
)

/** `{ "text": ... }`: the sentence a toast or a row shows. */
@Serializable
data class Notice(
    val text: String,
)

/** A confirmation dialog's words. */
@Serializable
data class Confirm(
    val title: String,
    val body: String,
)

/** A two-line row. */
@Serializable
data class TitleDetail(
    val title: String,
    val detail: String,
)

/** The answer of an op that opens a native object: its handle (close it with `RustNative.release`). */
@Serializable
data class HandleAnswer(
    val handle: Long,
    val count: Int,
)

/** Embedded card photo: [format] is `jpeg` or `png`, [base64] the standard base64 of the bytes. */
@Serializable
data class CardPhoto(
    val format: String,
    val mime: String,
    val base64: String,
)
