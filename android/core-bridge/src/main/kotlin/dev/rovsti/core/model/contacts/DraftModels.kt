@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.contacts

import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.model.search.WireContact
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// `contacts.draft_*`, dates, duplicates, cleanup, the card, avatar, stats and quick actions
// (`crates/rovsti_ffi/src/ops/contacts/draft.rs`).

/** A request that carries one draft. */
@Serializable
data class DraftRequest(
    val draft: ContactDraft,
)

/** A request that carries drafts. */
@Serializable
data class DraftsRequest(
    val drafts: List<ContactDraft>,
)

@Serializable
enum class DraftIssueKind {
    @SerialName("no_name")
    NoName,

    @SerialName("bad_number")
    BadNumber,

    @SerialName("bad_email")
    BadEmail,

    @SerialName("bad_date")
    BadDate,
}

/** Why a draft cannot be saved; [index] is the 0-based entry for the list kinds. */
@Serializable
data class DraftIssue(
    val kind: DraftIssueKind,
    val index: Int?,
    val message: String,
)

/** `contacts.draft_validate` answer: the draft cleaned the way a save would, and the first problem if any. */
@Serializable
data class ValidateAnswer(
    val draft: ContactDraft,
    val error: DraftIssue?,
)

@Serializable
data class DiffRequest(
    val before: ContactDraft,
    val after: ContactDraft,
)

/** [undo] is the draft that puts the old values back; null when nothing changed or the contact is new. */
@Serializable
data class DiffAnswer(
    val summary: String?,
    val notice: String,
    val undo: ContactDraft?,
)

@Serializable
data class DraftQueryRequest(
    val query: String,
)

/** Numbers and emails a search query holds, for "Add to an existing contact". */
@Serializable
data class Addition(
    val numbers: List<String>,
    val emails: List<String>,
)

@Serializable
data class DraftQueryAnswer(
    val draft: ContactDraft?,
    val addition: Addition?,
)

@Serializable
data class DraftAddRequest(
    val target: ContactDraft,
    val add: Addition,
)

@Serializable
data class DraftAddAnswer(
    val draft: ContactDraft?,
    val added: Int,
    val notice: String,
    val refused: Boolean,
)

@Serializable
enum class DraftEdit {
    @SerialName("set_primary")
    SetPrimary,

    @SerialName("retain_account_groups")
    RetainAccountGroups,

    @SerialName("clean")
    Clean,
}

@Serializable
enum class FieldList {
    @SerialName("numbers")
    Numbers,

    @SerialName("emails")
    Emails,

    @SerialName("addresses")
    Addresses,
}

/** `set_primary` takes [list] and [index] (absent clears the default); `retain_account_groups` takes [groups]. */
@Serializable
data class DraftEditRequest(
    val draft: ContactDraft,
    val edit: DraftEdit,
    @EncodeDefault(NEVER) val list: FieldList? = null,
    @EncodeDefault(NEVER) val index: Int? = null,
    @EncodeDefault(NEVER) val groups: List<Group> = emptyList(),
)

@Serializable
data class DraftEditAnswer(
    val draft: ContactDraft,
    @SerialName("primary_numbers") val primaryNumbers: Int?,
    @SerialName("primary_emails") val primaryEmails: Int?,
)

@Serializable
data class FieldMenuRequest(
    @SerialName("is_primary") val isPrimary: Boolean,
    val count: Int,
)

@Serializable
data class FieldAction(
    val label: String,
    val clears: Boolean,
)

@Serializable
data class FieldMenuAnswer(
    val action: FieldAction?,
)

@Serializable
data class WebUrlRequest(
    val text: String,
)

@Serializable
data class WebUrlAnswer(
    val url: String?,
)

@Serializable
data class RelationTapRequest(
    val name: String,
    val all: List<WireContact>,
)

@Serializable
enum class RelationAction {
    @SerialName("open")
    Open,

    @SerialName("search")
    Search,
}

@Serializable
data class RelationTapAnswer(
    val action: RelationAction,
    @SerialName("contact_id") val contactId: Long?,
    val query: String?,
)

@Serializable
data class AccountLabelRequest(
    val key: String,
)

@Serializable
data class LabelAnswer(
    val label: String,
)

// ---- dates ----

@Serializable
data class DatesRequest(
    val drafts: List<ContactDraft>,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    @SerialName("within_days") val withinDays: Long,
    @SerialName("favorites_limit") val favoritesLimit: Int,
)

/** One row of the upcoming list; [turningText] is "Turns 36" / "11 years", [whenText] "In 4 days". */
@Serializable
data class UpcomingEvent(
    @SerialName("contact_id") val contactId: Long,
    val name: String,
    val kind: EventKind,
    @SerialName("in_days") val inDays: Long,
    @SerialName("date_text") val dateText: String,
    val turning: Long?,
    @SerialName("turning_text") val turningText: String?,
    @SerialName("when_text") val whenText: String,
)

@Serializable
data class DateGroup(
    val title: String,
    val items: List<UpcomingEvent>,
)

@Serializable
data class DatesAnswer(
    val items: List<UpcomingEvent>,
    val favorites: List<UpcomingEvent>,
    val groups: List<DateGroup>,
)

@Serializable
data class DateParseRequest(
    val text: String,
)

/** A parsed date: [android] is `YYYY-MM-DD` or `--MM-DD`, [text] "7 Mar 1990". */
@Serializable
data class EventDate(
    val year: Int?,
    val month: Int,
    val day: Int,
    val android: String,
    val text: String,
)

@Serializable
data class DateParseAnswer(
    val strict: EventDate?,
    val input: EventDate?,
    val normalized: String,
    @SerialName("input_text") val inputText: String,
)

@Serializable
data class AgeRequest(
    val date: String,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class AgeAnswer(
    val valid: Boolean,
    val age: Long?,
    @SerialName("days_until") val daysUntil: Long?,
    val turning: Long?,
    @SerialName("when_text") val whenText: String?,
)

@Serializable
data class EventLineRequest(
    val event: DraftEvent,
    val name: String,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class EventLineAnswer(
    val line: String?,
    val turning: Long?,
    val wish: String,
    @SerialName("next_kind") val nextKind: EventKind,
)

@Serializable
data class MapsUrlRequest(
    val address: String,
)

@Serializable
data class MapsUrlAnswer(
    val url: String?,
)

@Serializable
data class LocalYmdRequest(
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

// ---- card ----

@Serializable
data class CardRequest(
    val contact: WireContact,
    @EncodeDefault(NEVER) val format: NameFormat? = null,
)

@Serializable
enum class CallTarget {
    @SerialName("direct")
    Direct,

    @SerialName("choose")
    Choose,

    @SerialName("nothing")
    Nothing,
}

/** One contact's page; [numbers] hold the default first, [callIndex] is the number a `direct` Call button dials. */
@Serializable
data class CardAnswer(
    val id: Long,
    val name: String,
    val monogram: String?,
    val hue: Int?,
    val numbers: List<NumberVm>,
    val starred: Boolean,
    @SerialName("call_target") val callTarget: CallTarget,
    @SerialName("call_index") val callIndex: Int?,
    @SerialName("share_text") val shareText: String,
)

// ---- duplicates / cleanup ----

@Serializable
enum class DupReason {
    @SerialName("same_number")
    SameNumber,

    @SerialName("same_email")
    SameEmail,

    @SerialName("same_name")
    SameName,
}

/** Contacts that look like the same person; the keeper is `ids[0]`. */
@Serializable
data class DupGroup(
    val ids: List<Long>,
    val reason: DupReason,
    val label: String,
)

@Serializable
data class DupesAnswer(
    val groups: List<DupGroup>,
    val summary: String,
)

@Serializable
data class MergeRequest(
    val keeper: ContactDraft,
    val others: List<ContactDraft>,
)

@Serializable
data class MergeAnswer(
    val draft: ContactDraft,
    val notice: String,
)

@Serializable
enum class CleanupId {
    @SerialName("no_number")
    NoNumber,

    @SerialName("no_name")
    NoName,

    @SerialName("no_photo")
    NoPhoto,

    @SerialName("no_group")
    NoGroup,
}

@Serializable
data class CleanupRequest(
    val drafts: List<ContactDraft>,
    /** Ids of the contacts that have a photo. */
    @SerialName("photo_ids") val photoIds: List<Long>,
    /** The filter whose ids to list. */
    @EncodeDefault(NEVER) val filter: CleanupId? = null,
)

@Serializable
data class CleanupFilter(
    val id: CleanupId,
    val label: String,
    val count: Int,
    @SerialName("empty_text") val emptyText: String,
)

@Serializable
data class CleanupAnswer(
    val filters: List<CleanupFilter>,
    val ids: List<Long>,
)

// ---- avatar / stats / quick ----

@Serializable
data class AvatarRequest(
    val name: String,
    @SerialName("contact_id") @EncodeDefault(NEVER) val contactId: Long? = null,
    /** The digits of an unsaved number, for a stable colour. */
    @SerialName("number_digits") @EncodeDefault(NEVER) val numberDigits: String? = null,
)

/** [hue] indexes the six hue pairs; null draws the neutral grey. */
@Serializable
data class AvatarAnswer(
    val monogram: String?,
    val initials: String,
    val hue: Int?,
)

@Serializable
data class AccountCount(
    val key: String,
    val label: String,
    val count: Int,
)

@Serializable
data class StatRow(
    val label: String,
    val value: String,
)

@Serializable
data class StatsAnswer(
    val total: Int,
    @SerialName("with_number") val withNumber: Int,
    @SerialName("with_email") val withEmail: Int,
    @SerialName("with_birthday") val withBirthday: Int,
    val starred: Int,
    val accounts: List<AccountCount>,
    @SerialName("total_text") val totalText: String,
    val rows: List<StatRow>,
)

@Serializable
data class QuickRequest(
    @SerialName("has_number") val hasNumber: Boolean,
    @SerialName("on_whatsapp") val onWhatsapp: Boolean,
    val starred: Boolean,
)

/** [id] is `call`, `message`, `whatsapp`, `copy_number`, `share`, `star`, `unstar` or `add_to_home`. */
@Serializable
data class QuickAction(
    val id: String,
    val label: String,
)

@Serializable
data class QuickAnswer(
    val actions: List<QuickAction>,
)
