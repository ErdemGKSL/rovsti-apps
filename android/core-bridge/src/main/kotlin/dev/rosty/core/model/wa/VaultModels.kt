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
// with its Keystore keys (K_hw for `seal`/`hwSealed`, K_daily for `dailySeal`/`daily.sealed`, K_stay for
// `staySeal`/`stay.sealed`) and never sees DK, the daily or stay key, or the connect key. The passphrase is the one
// secret that crosses, because the user reads and types it.

/**
 * Why the vault no longer opens without the passphrase (round 7c): 90 days without a use, or the clock set back before
 * the last use. Every wrap that opened it without the passphrase (stay, daily) was deleted.
 */
@Serializable
enum class VaultIdleRefusal {
    @SerialName("idle")
    Idle,

    @SerialName("clock_back")
    ClockBack,
}

/**
 * `vault.state`. With [nowMs], wraps whose use clock lapsed are deleted and named in [VaultState.expired]. [bootCount]
 * is read and ignored by the core since round 7c (a reboot ends nothing); apps leave it out.
 */
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

/** The biometric unlock: the K_daily alias and the K_daily-sealed daily blob to decrypt before `vault.unlock_daily`. */
@Serializable
data class VaultDailyInfo(
    val alias: String,
    val sealed: String,
    @SerialName("boot_count") val bootCount: Long,
    @SerialName("unlocked_ms") val unlockedMs: Long,
)

/**
 * The silent unlock (round 7c): the K_stay alias and the K_stay-sealed stay blob to decrypt (no prompt, the phone must
 * be unlocked) before `vault.unlock_stay`.
 */
@Serializable
data class VaultStayInfo(
    val alias: String,
    val sealed: String,
    @SerialName("created_ms") val createdMs: Long,
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
    @EncodeDefault(NEVER) val stay: VaultStayInfo? = null,
    /** The wraps were dropped by this read: 90 days without a use, or the clock set back. */
    @EncodeDefault(NEVER) val expired: VaultIdleRefusal? = null,
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
 * `vault.unlock`: [keyBlob] is [VaultInfo.hwSealed] decrypted with K_hw; [bootCount] (`Settings.Global.BOOT_COUNT`,
 * kept in the daily blob's format, not checked) and [nowMs] go into the daily and stay blobs and the use clock.
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

/**
 * `vault.unlock_daily`: [dailyBlob] is [VaultDailyInfo.sealed] decrypted with K_daily. [bootCount] is read and ignored
 * by the core since round 7c; apps leave it out.
 */
@Serializable
data class VaultDailyUnlockRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("daily_blob") val dailyBlob: String,
    @SerialName("boot_count") @EncodeDefault(NEVER) val bootCount: Long? = null,
    @SerialName("now_ms") val nowMs: Long,
)

/** `vault.unlock_stay` (round 7c): [stayBlob] is [VaultStayInfo.sealed] decrypted with K_stay (no prompt). */
@Serializable
data class VaultStayUnlockRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    @SerialName("stay_blob") val stayBlob: String,
    @SerialName("now_ms") val nowMs: Long,
)

/** `vault.touch` (round 7c): the app is in use at [nowMs]; the core writes it at most once an hour. */
@Serializable
data class VaultTouchRequest(
    @SerialName("now_ms") val nowMs: Long,
)

/** Whether `vault.touch` wrote the use now. */
@Serializable
data class VaultTouched(
    val recorded: Boolean,
)

/**
 * `vault.unlock_link` (round 7a): opens this app's own vault with the [link] a sibling Rosty app handed over from its
 * unlocked vault (`WaLinkProvider`), without a prompt. Carries the connect key: memory only, never logged.
 */
@Serializable
data class VaultLinkUnlockRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    val link: VaultLink,
    @SerialName("now_ms") val nowMs: Long,
) {
    override fun toString(): String = "VaultLinkUnlockRequest(dir=$dir, link=…, nowMs=$nowMs)"
}

/**
 * An unlocked session. [dailySeal] (a passphrase unlock only) is sealed with K_daily and handed to `vault.daily_store`;
 * [staySeal] (every unlock but the silent one, round 7c) is sealed with K_stay and handed to `vault.stay_store`. Both
 * optional: without them the next unlock needs the biometric or the passphrase.
 */
@Serializable
data class VaultUnlocked(
    val handle: Long,
    @SerialName("daily_seal") @EncodeDefault(NEVER) val dailySeal: String? = null,
    @SerialName("stay_seal") @EncodeDefault(NEVER) val staySeal: String? = null,
)

/** `vault.daily_store` and `vault.stay_store`: the Keystore-sealed blob of the unlock and the key's alias. */
@Serializable
data class VaultDailyStoreRequest(
    val handle: Long,
    val alias: String,
    val sealed: String,
)

/** `vault.daily_forget`, `vault.stay_forget` and `vault.reset`. */
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
