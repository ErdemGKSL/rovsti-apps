@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.messages

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `msg_backup.*` (E2.11a), mirroring `crates/rovsti_ffi/fixtures/msg_backup.*`. Bytes travel as standard
// base64. Stability: `compose_stability.conf`.

/** What a backup holds once decrypted. */
@Serializable
data class BackupBody(
    @SerialName("created_ms") val createdMs: Long,
    @SerialName("app_version") val appVersion: String,
    val sms: List<SmsRow>,
    /** Text parts only: binary attachments live in files outside the archive. */
    val mms: List<MmsRow>,
    val spam: SpamRules,
    val scheduled: List<ScheduledSms>,
)

@Serializable
data class BackupExportRequest(
    val body: BackupBody,
    val passphrase: String,
    /** 16 random bytes from a `SecureRandom`, fresh for every backup. */
    val salt: String,
    /** 12 random bytes from a `SecureRandom`, fresh for every backup. */
    val nonce: String,
    /** Key derivation rounds; 600 000 when absent. */
    @EncodeDefault(NEVER) val iterations: Int? = null,
    /** Keep only the text parts of each MMS. */
    @SerialName("text_only_mms") @EncodeDefault(NEVER) val textOnlyMms: Boolean = true,
)

@Serializable
data class BackupExportAnswer(
    /** The backup file. */
    val bytes: String,
    val size: Int,
    val iterations: Long,
)

@Serializable
data class BackupParseRequest(
    val bytes: String,
    val passphrase: String,
)

@Serializable
data class BackupParseAnswer(
    val body: BackupBody,
)

@Serializable
data class RestorePlanRequest(
    val backup: BackupBody,
    @EncodeDefault(NEVER) val existing: List<SmsRow> = emptyList(),
)

/** Which backed-up SMS rows are missing from the device. */
@Serializable
data class RestorePlan(
    val insert: List<SmsRow>,
    @SerialName("skipped_duplicates") val skippedDuplicates: Int,
)

@Serializable
data class StrengthRequest(
    val passphrase: String,
)

@Serializable
enum class Strength {
    @SerialName("weak")
    Weak,

    @SerialName("fair")
    Fair,

    @SerialName("strong")
    Strong,
}

@Serializable
data class StrengthAnswer(
    val strength: Strength,
)
