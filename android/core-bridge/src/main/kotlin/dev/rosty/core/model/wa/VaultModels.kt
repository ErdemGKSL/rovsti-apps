@file:OptIn(ExperimentalSerializationApi::class)

package dev.rosty.core.model.wa

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `vault.*` (the WhatsApp vault, `_bmad-output/kotlin-ui/plans/wa-vault.md`), mirroring
// `crates/rosty_ffi/fixtures/vault.*`. Every request may name `dir` (fixtures, tests); apps leave it out and the core
// uses its data directory. Byte fields are standard base64 and hold ciphertext only: Kotlin seals and unseals them
// with its Keystore keys (K_hw for `seal`/`hwSealed`, K_daily for `dailySeal`/`sealed`) and never sees DK, the
// daily key or the connect key. The passphrase is the one secret that crosses, because the user reads and types it.

/** Why a daily (biometric) unlock is no longer honoured; the passphrase is needed again. */
@Serializable
enum class VaultDailyRefusal {
    @SerialName("rebooted")
    Rebooted,

    @SerialName("idle")
    Idle,

    @SerialName("clock_back")
    ClockBack,
}

/** `vault.state`. With [bootCount] and [nowMs] a lapsed daily unlock is deleted and named in `dailyDropped`. */
@Serializable
data class VaultStateRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("boot_count") @EncodeDefault(NEVER) val bootCount: Long? = null,
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long? = null,
)

/** The vault file: the K_hw alias and the K_hw-sealed key blob to decrypt before `vault.unlock`. */
@Serializable
data class VaultInfo(
    @SerialName("hw_alias") val hwAlias: String,
    @SerialName("hw_sealed") val hwSealed: String,
)

/** The daily unlock: the K_daily alias and the K_daily-sealed daily blob to decrypt before `vault.unlock_daily`. */
@Serializable
data class VaultDailyInfo(
    val alias: String,
    val sealed: String,
    @SerialName("boot_count") val bootCount: Long,
    @SerialName("unlocked_ms") val unlockedMs: Long,
)

/** The open session: its handle and the link it serves (no key). */
@Serializable
data class VaultUnlockedInfo(
    val handle: Long,
    @SerialName("gateway_url") val gatewayUrl: String,
    @SerialName("instance_id") val instanceId: String,
)

@Serializable
data class VaultState(
    @EncodeDefault(NEVER) val vault: VaultInfo? = null,
    @EncodeDefault(NEVER) val daily: VaultDailyInfo? = null,
    @SerialName("daily_dropped") @EncodeDefault(NEVER) val dailyDropped: VaultDailyRefusal? = null,
    /** A plaintext `whatsapp-link.json` is still there: run the migration (create, commit, wipe). */
    @SerialName("legacy_link") val legacyLink: Boolean,
    @EncodeDefault(NEVER) val unlocked: VaultUnlockedInfo? = null,
)

/** The link `vault.create` protects when it is not the plaintext `whatsapp-link.json`. */
@Serializable
data class VaultLink(
    @SerialName("gateway_url") val gatewayUrl: String,
    @SerialName("instance_id") val instanceId: String,
    val key: String,
) {
    override fun toString(): String = "VaultLink(gatewayUrl=$gatewayUrl, instanceId=$instanceId, key=rwk_…)"
}

/** `vault.create`: without [link], the plaintext `whatsapp-link.json` of the data directory (the migration). */
@Serializable
data class VaultCreateRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @EncodeDefault(NEVER) val link: VaultLink? = null,
)

/**
 * `vault.create` and `vault.change`: [passphrase] is shown once; [seal] is sealed with a new K_hw key and handed to
 * `vault.commit`. Nothing is on disk until then.
 */
@Serializable
data class VaultKeys(
    val handle: Long,
    val passphrase: String,
    val seal: String,
) {
    override fun toString(): String = "VaultKeys(handle=$handle, passphrase=…, seal=${seal.length} chars)"
}

/** `vault.commit`: the K_hw-sealed [VaultKeys.seal] and the alias of the key that sealed it. */
@Serializable
data class VaultCommitRequest(
    val handle: Long,
    @SerialName("hw_alias") val hwAlias: String,
    @SerialName("hw_sealed") val hwSealed: String,
)

/**
 * `vault.unlock`: [keyBlob] is [VaultInfo.hwSealed] decrypted with K_hw; [bootCount] (`Settings.Global.BOOT_COUNT`)
 * and [nowMs] go into the daily blob.
 */
@Serializable
data class VaultUnlockRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("key_blob") val keyBlob: String,
    val passphrase: String,
    @SerialName("boot_count") val bootCount: Long,
    @SerialName("now_ms") val nowMs: Long,
) {
    override fun toString(): String = "VaultUnlockRequest(dir=$dir, passphrase=…, bootCount=$bootCount, nowMs=$nowMs)"
}

/** `vault.unlock_daily`: [dailyBlob] is [VaultDailyInfo.sealed] decrypted with K_daily. */
@Serializable
data class VaultDailyUnlockRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("daily_blob") val dailyBlob: String,
    @SerialName("boot_count") val bootCount: Long,
    @SerialName("now_ms") val nowMs: Long,
)

/** An unlocked session; [dailySeal] is sealed with K_daily and handed to `vault.daily_store` (optional). */
@Serializable
data class VaultUnlocked(
    val handle: Long,
    @SerialName("daily_seal") val dailySeal: String,
)

/** `vault.daily_store`: the K_daily-sealed [VaultUnlocked.dailySeal]. */
@Serializable
data class VaultDailyStoreRequest(
    val handle: Long,
    val alias: String,
    val sealed: String,
)

/** `vault.daily_forget` and `vault.reset`. */
@Serializable
data class VaultDirRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
)

/** `vault.lock`: without [handle], the current session. */
@Serializable
data class VaultLockRequest(
    @EncodeDefault(NEVER) val handle: Long? = null,
    @EncodeDefault(NEVER) val dir: String? = null,
)

/** `vault.change`. */
@Serializable
data class VaultHandleRequest(
    val handle: Long,
)

/**
 * `vault.trip_hold` and `vault.trip_release`: whether the running Stops trip keeps a copy of the link in RAM (plan
 * 13.3) for its arriving-soon message. Nothing is written; the trip's end drops it.
 */
@Serializable
data class VaultTripLink(
    val held: Boolean,
)

/** `vault.reset`: the vault files deleted; the Keystore keys are Kotlin's to delete. */
@Serializable
data class VaultReset(
    val deleted: List<String>,
)

/** `vault.wipe_plaintext`: the app's `filesDir` (and `cacheDir` when it is not the core's). */
@Serializable
data class VaultWipeRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("files_dir") val filesDir: String,
    @SerialName("cache_dir") @EncodeDefault(NEVER) val cacheDir: String? = null,
)

@Serializable
data class VaultWipeFailure(
    val path: String,
    val error: String,
)

@Serializable
data class VaultWipeReport(
    val deleted: List<String>,
    /** Databases erased in place before they were deleted. */
    val scrubbed: List<String>,
    @SerialName("drafts_removed") val draftsRemoved: Int,
    val failed: List<VaultWipeFailure>,
)

/** `vault.pictures`: the profile pictures of the encrypted cache ([jids] null: every one). */
@Serializable
data class VaultPicturesRequest(
    @EncodeDefault(NEVER) val jids: List<String>? = null,
)

@Serializable
data class VaultPicture(
    val jid: String,
    @SerialName("picture_id") val pictureId: String,
    /** The JPEG, base64. */
    val jpeg: String,
)

@Serializable
data class VaultPictures(
    val pictures: List<VaultPicture>,
)

/** `vault.picture_put`: keeps [jpeg] as [jid]'s picture [pictureId]; both null: the person has no picture. */
@Serializable
data class VaultPicturePutRequest(
    val jid: String,
    @SerialName("picture_id") @EncodeDefault(NEVER) val pictureId: String? = null,
    @EncodeDefault(NEVER) val jpeg: String? = null,
)

@Serializable
data class VaultPicturePut(
    /** The vault session whose cache was written. */
    val handle: Long,
    val pictures: Int,
)
