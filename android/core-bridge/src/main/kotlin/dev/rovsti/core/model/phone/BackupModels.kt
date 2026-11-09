package dev.rovsti.core.model.phone

import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.model.search.WireContact
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The Phone backup file and the list name (`phone.backup_export`, `phone.backup_import`, `phone.display_name`), see
// `crates/rovsti_ffi/src/ops/phone/backup.rs`.

/** `phone.backup_export` request: the preferences to save, the time and the app version written into the file. */
@Serializable
data class BackupExportRequest(
    val prefs: PhonePrefs,
    @SerialName("created_ms") val createdMs: Long,
    @SerialName("app_version") val appVersion: String,
)

/** `phone.backup_export` answer: `rovsti-phone-backup-yyyy-mm-dd.json` and the file's text. */
@Serializable
data class BackupExportAnswer(
    @SerialName("file_name") val fileName: String,
    val text: String,
)

/** `phone.backup_import` request: the picked file's text and the preferences now. */
@Serializable
data class BackupImportRequest(
    val text: String,
    val current: PhonePrefs,
)

/** A speed-dial contact that appeared on two digits and kept the later one. */
@Serializable
data class MovedDigit(
    val to: Int,
    val from: Int,
)

/** What an import would change, for the confirmation sheet. */
@Serializable
data class BackupPreview(
    @SerialName("settings_changed") val settingsChanged: Int,
    @SerialName("quick_replies") val quickReplies: Int,
    @SerialName("speed_dial") val speedDial: Int,
    val favorites: Int,
    val moved: List<MovedDigit>,
    val clamped: Boolean,
)

/** `phone.backup_import` answer: the preview and the preferences once confirmed. A refusal is `RustError.Invalid`. */
@Serializable
data class BackupImportAnswer(
    val preview: BackupPreview,
    val prefs: PhonePrefs,
)

/** `phone.display_name` request. */
@Serializable
data class DisplayNameRequest(
    val contact: WireContact,
    val format: NameFormat,
)

/** `phone.display_name` answer: empty when the contact has nothing to show. */
@Serializable
data class DisplayNameAnswer(
    val name: String,
)
