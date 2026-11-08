@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.contacts

import dev.rovsti.core.model.phone.HistoryLine
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.SortBy
import dev.rovsti.core.model.phone.StatCall
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.model.search.WireContact
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The lists and screens built from the contacts (`crates/rovsti_ffi/src/ops/contacts/lists.rs`).

// ---- view ----

@Serializable
data class ViewRequest(
    val contacts: List<WireContact>,
    @EncodeDefault(NEVER) val query: String = "",
    @SerialName("name_format") @EncodeDefault(NEVER) val nameFormat: NameFormat? = null,
    @SerialName("sort_by") @EncodeDefault(NEVER) val sortBy: SortBy? = null,
    /** Lookup keys of the favorites in the user's order. */
    @SerialName("favorites_order") @EncodeDefault(NEVER) val favoritesOrder: List<String> = emptyList(),
    /** Contacts hidden for now (selected for delete, just merged). */
    @EncodeDefault(NEVER) val hidden: List<Long> = emptyList(),
    /** Width of the favorites grid in dp. */
    @SerialName("width_dp") val widthDp: Double,
)

/** One row of the contact list; [header] marks the first row of its [letter] section (never while searching). */
@Serializable
data class ContactRow(
    val id: Long,
    val name: String,
    val subtitle: String?,
    val letter: String,
    val header: Boolean,
    val monogram: String?,
    val hue: Int?,
    val numbers: List<NumberVm>,
    val starred: Boolean,
    @SerialName("photo_uri") val photoUri: String?,
)

@Serializable
data class ViewAnswer(
    val rows: List<ContactRow>,
    /** The starred contacts in the user's order. */
    val favorites: List<Long>,
    val columns: Int,
    @SerialName("scrubber_letters") val scrubberLetters: List<String>,
    @SerialName("show_scrubber") val showScrubber: Boolean,
    /** The query as echoed in "No contact matches ...". */
    @SerialName("query_echo") val queryEcho: String,
    /** The number a query that looks like one stands for. */
    @SerialName("query_number") val queryNumber: String?,
)

/** Button mode (`index` and `up`) or drag mode (`from` and `to`). */
@Serializable
data class FavoritesMoveRequest(
    val shown: List<WireContact>,
    @EncodeDefault(NEVER) val index: Int? = null,
    @EncodeDefault(NEVER) val up: Boolean = false,
    @EncodeDefault(NEVER) val from: Int? = null,
    @EncodeDefault(NEVER) val to: Int? = null,
)

/** The new order as lookup keys; null when the move is not possible. */
@Serializable
data class FavoritesMoveAnswer(
    val keys: List<String>?,
)

@Serializable
data class ScrubberRequest(
    /** The section letter of every row, in list order. */
    val rows: List<String>,
    val y: Double,
    val height: Double,
)

@Serializable
data class ScrubberAnswer(
    val letters: List<String>,
    /** The letter under the touch. */
    val index: Int?,
    /** The first row of that letter. */
    val row: Int?,
)

// ---- selection ----

@Serializable
data class SelectBarRequest(
    val count: Int,
    @SerialName("has_groups") val hasGroups: Boolean,
)

@Serializable
enum class SelectAction {
    @SerialName("share")
    Share,

    @SerialName("save_to_file")
    SaveToFile,

    @SerialName("add_to_group")
    AddToGroup,

    @SerialName("star")
    Star,

    @SerialName("delete")
    Delete,
}

/** A way to share the selection: [id] is `vcf` or `text`. */
@Serializable
data class ShareFormat(
    val id: String,
    val label: String,
)

@Serializable
data class SelectBarAnswer(
    val title: String,
    val actions: List<SelectAction>,
    val cap: Int,
    @SerialName("cap_notice") val capNotice: String,
    @SerialName("delete_confirm") val deleteConfirm: Confirm,
    @SerialName("starred_notice") val starredNotice: String,
    @SerialName("unstarred_notice") val unstarredNotice: String,
    @SerialName("deleted_notice") val deletedNotice: String,
    val formats: List<ShareFormat>,
)

/** [format] is `vcf` or `text`. */
@Serializable
data class SelectExportRequest(
    val drafts: List<ContactDraft>,
    val selected: List<Long>,
    @EncodeDefault(NEVER) val hidden: List<Long> = emptyList(),
    val today: Day,
    val format: String,
)

@Serializable
data class SelectExportAnswer(
    val ids: List<Long>,
    val count: Int,
    @SerialName("file_name") val fileName: String,
    /** The vCard file or the plain text to share. */
    val text: String,
    val notice: String,
    @SerialName("copied_notice") val copiedNotice: String,
)

// ---- groups ----

@Serializable
data class GroupTitleRequest(
    val title: String,
    val groups: List<Group>,
    @EncodeDefault(NEVER) val editing: Long? = null,
)

@Serializable
data class GroupTitleAnswer(
    val title: String?,
    val error: String?,
)

@Serializable
data class GroupLabelsRequest(
    val members: Int,
    @EncodeDefault(NEVER) val account: String? = null,
)

@Serializable
data class GroupLabelsAnswer(
    val subtitle: String,
    @SerialName("join_hint") val joinHint: String,
)

@Serializable
data class GroupChangeRequest(
    /** The group's members now. */
    val initial: List<Long>,
    /** The members after the picker. */
    val chosen: List<Long>,
    /** Only add (the select bar's "Add to group"). */
    @SerialName("add_only") @EncodeDefault(NEVER) val addOnly: Boolean = false,
)

@Serializable
data class GroupChangeAnswer(
    val add: List<Long>,
    val remove: List<Long>,
    val empty: Boolean,
)

@Serializable
data class GroupSummaryRequest(
    val title: String,
    val added: Int,
    val removed: Int,
    val skipped: Int,
)

@Serializable
data class SelectedContact(
    val id: Long,
    val account: String,
)

@Serializable
data class GroupTargetsRequest(
    val groups: List<Group>,
    val selected: List<SelectedContact>,
)

/** A group the selection can join: [eligible] are the selected contacts of its account. */
@Serializable
data class GroupTarget(
    @SerialName("group_id") val groupId: Long,
    val title: String,
    val account: String,
    val eligible: List<Long>,
    val skipped: Int,
    val notice: String,
)

@Serializable
data class GroupTargetsAnswer(
    val targets: List<GroupTarget>,
)

/** [kind] is `sms` or `email`. */
@Serializable
data class RecipientsRequest(
    val members: List<ContactDraft>,
    val kind: String,
    val title: String,
    val today: Day,
)

@Serializable
data class RecipientsAnswer(
    val addresses: List<String>,
    /** Members without a usable number or email. */
    val missing: Int,
    val capped: Boolean,
    val cap: Int,
    /** The joined address for the intent (`;` for SMS, `,` for email). */
    val address: String,
    val notice: String?,
    @SerialName("file_name") val fileName: String,
)

// ---- recents ----

/** When a contact was first seen by the app. */
@Serializable
data class AddedMark(
    val id: Long,
    val ms: Long,
)

@Serializable
data class RecentPullRequest(
    @SerialName("known_max_id") @EncodeDefault(NEVER) val knownMaxId: Long? = null,
    val marks: List<AddedMark>,
    /** The ids of every contact in the last pull. */
    val ids: List<Long>,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class RecentPullAnswer(
    @SerialName("known_max_id") val knownMaxId: Long,
    val marks: List<AddedMark>,
)

@Serializable
data class RecentCreatedRequest(
    val marks: List<AddedMark>,
    val id: Long,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class RecentCreatedAnswer(
    val marks: List<AddedMark>,
)

@Serializable
data class RecentListsRequest(
    val contacts: List<WireContact>,
    val marks: List<AddedMark>,
    /** Lookup keys of the recently viewed contacts, newest first. */
    @SerialName("viewed_keys") val viewedKeys: List<String>,
    val hidden: List<Long>,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    @EncodeDefault(NEVER) val query: String = "",
)

/** [whenText] is "Today", "Yesterday", "3 days ago" or "12 May". */
@Serializable
data class RecentRow(
    val id: Long,
    val name: String,
    @SerialName("when") val whenText: String,
)

/** A tab of the Recents screen: [id] is `added` or `edited`. */
@Serializable
data class RecentTab(
    val id: String,
    val label: String,
    @SerialName("empty_title") val emptyTitle: String,
    @SerialName("empty_body") val emptyBody: String,
)

@Serializable
data class RecentListsAnswer(
    val added: List<RecentRow>,
    val edited: List<RecentRow>,
    val viewed: List<Long>,
    /** The row at the top of the list: "Recent" / "1 added - 2 edited". */
    @SerialName("entry_title") val entryTitle: String,
    @SerialName("entry_detail") val entryDetail: String,
    @SerialName("show_entry") val showEntry: Boolean,
    val tabs: List<RecentTab>,
    @SerialName("clear_viewed") val clearViewed: Confirm,
    @SerialName("window_days") val windowDays: Int,
)

/** [action] is `note` (opened a card) or `forget` (swiped away). */
@Serializable
data class RecentViewedRequest(
    val keys: List<String>,
    val key: String,
    val action: String,
)

@Serializable
data class RecentViewedAnswer(
    val keys: List<String>,
)

// ---- history / frequent ----

@Serializable
data class ContactHistoryRequest(
    val lines: List<HistoryLine>,
    @SerialName("now_ms") val nowMs: Long,
    val locale: LocaleTime,
)

@Serializable
data class ContactHistoryAnswer(
    /** "3 calls, last call 2 days ago". */
    val summary: String?,
    val rows: List<TitleDetail>,
    @SerialName("card_limit") val cardLimit: Int,
    @SerialName("pull_limit") val pullLimit: Int,
    val permission: TitleDetail,
)

@Serializable
data class FrequentContactsRequest(
    val calls: List<StatCall>,
    val contacts: List<WireContact>,
    @SerialName("now_ms") val nowMs: Long,
    @EncodeDefault(NEVER) val limit: Int? = null,
)

@Serializable
data class FrequentPerson(
    @SerialName("contact_id") val contactId: Long,
    val calls: Int,
    @SerialName("last_ms") val lastMs: Long,
    /** "5 calls in the last 30 days". */
    val subtitle: String,
)

@Serializable
data class FrequentContactsAnswer(
    val people: List<FrequentPerson>,
    @SerialName("window_days") val windowDays: Long,
)

// ---- my card / emergency ----

@Serializable
data class MeStateRequest(
    /** The lookup key of "my card". */
    @EncodeDefault(NEVER) val key: String? = null,
    val all: List<WireContact>,
    val emergency: List<String>,
)

@Serializable
data class EmergencyEntry(
    @SerialName("contact_id") val contactId: Long,
    val name: String,
    val number: String,
)

@Serializable
data class MeStateAnswer(
    @SerialName("my_card") val myCard: TitleDetail,
    @SerialName("my_card_contact") val myCardContact: Long?,
    val emergency: List<EmergencyEntry>,
    /** The text shared from the emergency list. */
    @SerialName("emergency_text") val emergencyText: String,
    val max: Int,
    @SerialName("my_card_set_notice") val myCardSetNotice: String,
    @SerialName("my_card_removed_notice") val myCardRemovedNotice: String,
)

@Serializable
data class MeToggleRequest(
    val keys: List<String>,
    val contact: WireContact,
)

/** [added] is true when the contact joined the list, false when it left, null when refused ([notice] says why). */
@Serializable
data class MeToggleAnswer(
    val keys: List<String>,
    val added: Boolean?,
    val notice: String,
)

// ---- birthday reminders ----

@Serializable
data class ReminderRequest(
    val drafts: List<ContactDraft>,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    val hour: Int,
    val hour12: Boolean,
    /** Alarm ids already armed. */
    val armed: List<Long>,
)

@Serializable
data class BirthdayAlarm(
    val id: Long,
    @SerialName("contact_id") val contactId: Long,
    @SerialName("at_ms") val atMs: Long,
    val title: String,
    val body: String,
)

@Serializable
data class HourChoice(
    val hour: Int,
    val label: String,
)

@Serializable
data class ReminderAnswer(
    val alarms: List<BirthdayAlarm>,
    /** Armed alarms to cancel. */
    val disarm: List<Long>,
    /** Ids of [alarms] to set. */
    val arm: List<Long>,
    val hours: List<HourChoice>,
    @SerialName("hour_label") val hourLabel: String,
    @SerialName("default_hour") val defaultHour: Int,
)

// ---- options / pin ----

/** Per-contact ringtone ([ringtoneUri] null: default, empty: silent) and "send to voicemail". */
@Serializable
data class ContactOptions(
    @SerialName("ringtone_uri") val ringtoneUri: String?,
    @SerialName("ringtone_title") val ringtoneTitle: String?,
    @SerialName("to_voicemail") val toVoicemail: Boolean,
)

/** Either [options] or the stored [json] text. */
@Serializable
data class OptionsRequest(
    @EncodeDefault(NEVER) val options: ContactOptions? = null,
    @EncodeDefault(NEVER) val json: String? = null,
    val name: String,
)

@Serializable
data class OptionsAnswer(
    val options: ContactOptions,
    @SerialName("ringtone_text") val ringtoneText: String,
    @SerialName("ringtone_notice") val ringtoneNotice: String,
    @SerialName("voicemail_confirm") val voicemailConfirm: Confirm,
    @SerialName("voicemail_on_notice") val voicemailOnNotice: String,
    @SerialName("voicemail_off_notice") val voicemailOffNotice: String,
    @SerialName("pin_ok_notice") val pinOkNotice: String,
    @SerialName("pin_failed_notice") val pinFailedNotice: String,
)

@Serializable
data class PinShortcutRequest(
    @SerialName("contact_id") val contactId: Long,
    @SerialName("lookup_key") @EncodeDefault(NEVER) val lookupKey: String? = null,
    val name: String,
    @SerialName("icon_path") @EncodeDefault(NEVER) val iconPath: String? = null,
)

/** The spec of a pinned Home-screen shortcut. */
@Serializable
data class PinShortcut(
    val id: String,
    @SerialName("short_label") val shortLabel: String,
    @SerialName("long_label") val longLabel: String,
    @SerialName("contact_id") val contactId: Long,
    @SerialName("icon_path") val iconPath: String?,
)

@Serializable
data class PinShortcutAnswer(
    val shortcut: PinShortcut,
)

// ---- deep find ----

@Serializable
data class DeepIndexNewRequest(
    val drafts: List<ContactDraft>,
)

@Serializable
data class DeepFindRequest(
    val handle: Long,
    val query: String,
    /** Ids already shown by name. */
    @EncodeDefault(NEVER) val skip: List<Long> = emptyList(),
    val limit: Int,
)

@Serializable
enum class DeepField {
    @SerialName("company")
    Company,

    @SerialName("job_title")
    JobTitle,

    @SerialName("nickname")
    Nickname,

    @SerialName("phonetic_name")
    PhoneticName,

    @SerialName("relation")
    Relation,

    @SerialName("note")
    Note,

    @SerialName("address")
    Address,

    @SerialName("email")
    Email,

    @SerialName("website")
    Website,
}

@Serializable
data class DeepHit(
    val id: Long,
    val field: DeepField,
    val label: String,
    val snippet: String,
)

@Serializable
data class DeepFindAnswer(
    val hits: List<DeepHit>,
)
