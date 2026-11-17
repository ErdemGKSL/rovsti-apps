package dev.rosty.core.ops

import dev.rosty.core.NativeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.RustError
import dev.rosty.core.call
import dev.rosty.core.model.wa.VaultCommitRequest
import dev.rosty.core.model.wa.VaultCreateRequest
import dev.rosty.core.model.wa.VaultDailyStoreRequest
import dev.rosty.core.model.wa.VaultDailyUnlockRequest
import dev.rosty.core.model.wa.VaultDirRequest
import dev.rosty.core.model.wa.VaultHandleRequest
import dev.rosty.core.model.wa.VaultKeys
import dev.rosty.core.model.wa.VaultLockRequest
import dev.rosty.core.model.wa.VaultReset
import dev.rosty.core.model.wa.VaultState
import dev.rosty.core.model.wa.VaultStateRequest
import dev.rosty.core.model.wa.VaultUnlockRequest
import dev.rosty.core.model.wa.VaultUnlocked
import dev.rosty.core.model.wa.VaultWipeReport
import dev.rosty.core.model.wa.VaultWipeRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The WhatsApp vault (`_bmad-output/kotlin-ui/plans/wa-vault.md`): the connect key sealed under a data key that needs
 * the passphrase and this device's Keystore. The Rust core holds the data key and the connect key in a session
 * handle; this facade only moves ciphertext between Rust and the Keystore (`:platform` VaultKeystore, round 3):
 *
 * - setup and migration: [create] -> seal [VaultKeys.seal] with a new K_hw key -> [commit] -> [wipePlaintext];
 * - passphrase unlock: [state] -> decrypt [dev.rosty.core.model.wa.VaultInfo.hwSealed] with K_hw -> [unlock] ->
 *   seal [VaultUnlocked.dailySeal] with K_daily -> [dailyStore];
 * - daily unlock: decrypt [dev.rosty.core.model.wa.VaultDailyInfo.sealed] with K_daily -> [unlockDaily];
 * - [change] -> seal under a new K_hw alias -> [commit] -> delete the old alias; [reset] -> delete both aliases.
 */
interface VaultOps {
    /** What is on disk and whether a session is open. */
    suspend fun state(req: VaultStateRequest = VaultStateRequest()): Outcome<VaultState>

    /** A new vault (nothing written yet): the passphrase to show and the key blob to seal with K_hw. */
    suspend fun create(req: VaultCreateRequest = VaultCreateRequest()): Outcome<VaultKeys>

    /** Writes the vault with the K_hw-sealed key blob; the session is unlocked. */
    suspend fun commit(req: VaultCommitRequest): Outcome<VaultState>

    /** Passphrase unlock. */
    suspend fun unlock(req: VaultUnlockRequest): Outcome<VaultUnlocked>

    /** Biometric unlock with the K_daily-decrypted daily blob. */
    suspend fun unlockDaily(req: VaultDailyUnlockRequest): Outcome<VaultUnlocked>

    /** Stores the K_daily-sealed daily blob. */
    suspend fun dailyStore(req: VaultDailyStoreRequest): Outcome<VaultState>

    /** Ends the daily unlock (reboot, biometric change, five failures). */
    suspend fun dailyForget(req: VaultDirRequest = VaultDirRequest()): Outcome<VaultState>

    /** Ends the session: the keys are zeroized and the gateways opened from it released. */
    suspend fun lock(req: VaultLockRequest = VaultLockRequest()): Outcome<VaultState>

    /** A new passphrase and data key for the open session, committed like [create]. */
    suspend fun change(req: VaultHandleRequest): Outcome<VaultKeys>

    /** Deletes the vault files (a forgotten passphrase); the Keystore keys are the caller's to delete. */
    suspend fun reset(req: VaultDirRequest = VaultDirRequest()): Outcome<VaultReset>

    /** The migration's wipe of every plaintext WhatsApp file. */
    suspend fun wipePlaintext(req: VaultWipeRequest): Outcome<VaultWipeReport>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "vault.state",
                "vault.create",
                "vault.commit",
                "vault.unlock",
                "vault.unlock_daily",
                "vault.daily_store",
                "vault.daily_forget",
                "vault.lock",
                "vault.change",
                "vault.reset",
                "vault.wipe_plaintext",
            )
    }
}

/**
 * The stable code of a vault failure (`detail.code`): `typo`, `length`, `character`, `wrong_passphrase`, `damaged`,
 * `no_vault`, `no_daily`, `no_link`, `locked`, `daily_expired` (with `detail.reason`), `vault_exists`,
 * `nothing_to_commit`, `nothing_to_store`, `not_committed`, `bad_url`, `bad_key`; also `vault_unlocked` and
 * `other_gateway` from `wa.open`. Null when Rust sent none.
 */
val RustError.vaultCode: String?
    get() = ((detail as? JsonObject)?.get("code") as? JsonPrimitive)?.takeIf { it.isString }?.content

/** [VaultOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeVaultOps(
    private val core: RustCore = NativeRustCore(),
) : VaultOps {
    override suspend fun state(req: VaultStateRequest) =
        core.call("vault.state", req, VaultStateRequest.serializer(), VaultState.serializer())

    override suspend fun create(req: VaultCreateRequest) =
        core.call("vault.create", req, VaultCreateRequest.serializer(), VaultKeys.serializer())

    override suspend fun commit(req: VaultCommitRequest) =
        core.call("vault.commit", req, VaultCommitRequest.serializer(), VaultState.serializer())

    override suspend fun unlock(req: VaultUnlockRequest) =
        core.call("vault.unlock", req, VaultUnlockRequest.serializer(), VaultUnlocked.serializer())

    override suspend fun unlockDaily(req: VaultDailyUnlockRequest) =
        core.call("vault.unlock_daily", req, VaultDailyUnlockRequest.serializer(), VaultUnlocked.serializer())

    override suspend fun dailyStore(req: VaultDailyStoreRequest) =
        core.call("vault.daily_store", req, VaultDailyStoreRequest.serializer(), VaultState.serializer())

    override suspend fun dailyForget(req: VaultDirRequest) =
        core.call("vault.daily_forget", req, VaultDirRequest.serializer(), VaultState.serializer())

    override suspend fun lock(req: VaultLockRequest) =
        core.call("vault.lock", req, VaultLockRequest.serializer(), VaultState.serializer())

    override suspend fun change(req: VaultHandleRequest) =
        core.call("vault.change", req, VaultHandleRequest.serializer(), VaultKeys.serializer())

    override suspend fun reset(req: VaultDirRequest) =
        core.call("vault.reset", req, VaultDirRequest.serializer(), VaultReset.serializer())

    override suspend fun wipePlaintext(req: VaultWipeRequest) =
        core.call("vault.wipe_plaintext", req, VaultWipeRequest.serializer(), VaultWipeReport.serializer())
}
