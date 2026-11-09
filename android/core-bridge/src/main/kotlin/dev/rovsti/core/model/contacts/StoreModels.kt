@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.contacts

import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.phone.SortBy
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.model.stops.Appearance
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The Trash and the preferences, files in the data directory (`crates/rovsti_ffi/src/ops/contacts/store.rs`).
// A request may name `dir` (tests); otherwise the data dir given to `core.init` is used.

@Serializable
data class TrashListRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("now_ms") val nowMs: Long,
)

/** A deleted contact: [leftText] is "Deleted today" or "12 days left". */
@Serializable
data class TrashEntry(
    val key: Long,
    @SerialName("deleted_ms") val deletedMs: Long,
    val draft: ContactDraft,
    @SerialName("left_text") val leftText: String,
    @SerialName("days_left") val daysLeft: Int,
)

@Serializable
data class TrashListAnswer(
    /** Newest first; entries past the retention period were dropped. */
    val entries: List<TrashEntry>,
    @SerialName("retention_days") val retentionDays: Int,
    @SerialName("max_entries") val maxEntries: Int,
    @SerialName("empty_confirm") val emptyConfirm: Confirm,
)

@Serializable
data class TrashPushRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    val drafts: List<ContactDraft>,
    @SerialName("now_ms") val nowMs: Long,
)

@Serializable
data class TrashPushAnswer(
    val keys: List<Long>,
    val count: Int,
    /** "Moved to Trash" / "Moved 3 contacts to Trash". */
    val notice: String,
)

@Serializable
data class TrashRestoreRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    val keys: List<Long>,
    val groups: List<Group>,
    val accounts: List<Account>,
    /** Where a contact whose account is gone goes. */
    @SerialName("fallback_account") val fallbackAccount: String,
    @SerialName("now_ms") val nowMs: Long,
)

/** New drafts (no id) ready to insert; the entries left the Trash. */
@Serializable
data class TrashRestoreAnswer(
    val drafts: List<ContactDraft>,
    val notice: String,
)

@Serializable
data class TrashDeleteRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @EncodeDefault(NEVER) val keys: List<Long> = emptyList(),
    @EncodeDefault(NEVER) val all: Boolean = false,
)

@Serializable
data class TrashDeleteAnswer(
    val removed: Int,
)

/** [kind] is `forever` (selected entries) or `empty` (the whole Trash). */
@Serializable
data class TrashConfirmRequest(
    val kind: String,
    val count: Int,
)

@Serializable
data class DirRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
)

/** `contacts-prefs.json` (`rovsti_core::contacts_prefs::ContactsPrefs`). */
@Serializable
data class ContactsPrefs(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("name_format") val nameFormat: NameFormat,
    @SerialName("sort_by") val sortBy: SortBy,
    /** The account key new contacts are saved to (empty: on this phone). */
    @SerialName("save_to") val saveTo: String,
    @SerialName("birthday_reminders") val birthdayReminders: Boolean,
    @SerialName("reminder_hour") val reminderHour: Int,
    /** Lookup keys of the favorites in the user's order. */
    @SerialName("favorites_order") val favoritesOrder: List<String>,
    @SerialName("my_card") val myCard: String?,
    val emergency: List<String>,
    @SerialName("known_max_id") val knownMaxId: Long?,
    val added: List<AddedMark>,
    val viewed: List<String>,
    /** Light, dark or follow the system; a file without it follows the system. */
    val appearance: Appearance = Appearance.Auto,
    /** How strong the haptics are; a file without it plays Normal. */
    val haptics: HapticsLevel = HapticsLevel.Normal,
)

/** The preferences plus the lists as the screens use them (no blanks or repeats) and the clamped reminder hour. */
@Serializable
data class PrefsLoadAnswer(
    val prefs: ContactsPrefs,
    @SerialName("favorites_order") val favoritesOrder: List<String>,
    val emergency: List<String>,
    @SerialName("reminder_hour") val reminderHour: Int,
)

@Serializable
data class PrefsSaveRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    val prefs: ContactsPrefs,
)
