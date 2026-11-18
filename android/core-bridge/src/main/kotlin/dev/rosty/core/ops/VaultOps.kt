package dev.rosty.core.ops

import dev.rosty.core.NativeRustCore
import dev.rosty.core.Outcome
import dev.rosty.core.RustCore
import dev.rosty.core.RustError
import dev.rosty.core.call
import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.wa.VaultCommitRequest
import dev.rosty.core.model.wa.VaultCreateRequest
import dev.rosty.core.model.wa.VaultDailyStoreRequest
import dev.rosty.core.model.wa.VaultDailyUnlockRequest
import dev.rosty.core.model.wa.VaultDirRequest
import dev.rosty.core.model.wa.VaultHandleRequest
import dev.rosty.core.model.wa.VaultKeys
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.core.model.wa.VaultLinkUnlockRequest
import dev.rosty.core.model.wa.VaultLockRequest
import dev.rosty.core.model.wa.VaultPicturePut
import dev.rosty.core.model.wa.VaultPicturePutRequest
import dev.rosty.core.model.wa.VaultPictures
import dev.rosty.core.model.wa.VaultPicturesRequest
import dev.rosty.core.model.wa.VaultReset
import dev.rosty.core.model.wa.VaultState
import dev.rosty.core.model.wa.VaultStateRequest
import dev.rosty.core.model.wa.VaultStayUnlockRequest
import dev.rosty.core.model.wa.VaultTouchRequest
import dev.rosty.core.model.wa.VaultTouched
import dev.rosty.core.model.wa.VaultTripLink
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
 *   seal [VaultUnlocked.dailySeal] with K_daily -> [dailyStore], and [VaultUnlocked.staySeal] with K_stay ->
 *   [stayStore];
 * - biometric unlock: decrypt [dev.rosty.core.model.wa.VaultDailyInfo.sealed] with K_daily -> [unlockDaily] -> the
 *   stay seal as above;
 * - silent unlock (round 7c): decrypt [dev.rosty.core.model.wa.VaultStayInfo.sealed] with K_stay (no prompt) ->
 *   [unlockStay]; while open, [touch] keeps the 90-day use clock moving;
 * - hand-over unlock (round 7a): a sibling's [exportLink] -> [unlockLink], no prompt, then the stay seal as above;
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

    /** Biometric unlock with the K_daily-decrypted daily blob; `expired` after 90 days without a use. */
    suspend fun unlockDaily(req: VaultDailyUnlockRequest): Outcome<VaultUnlocked>

    /** Silent unlock with the K_stay-decrypted stay blob (round 7c); `expired` after 90 days without a use. */
    suspend fun unlockStay(req: VaultStayUnlockRequest): Outcome<VaultUnlocked>

    /**
     * Opens this app's own vault with the link a sibling Rosty app handed over from its unlocked vault (round 7a, the
     * one prompt for the four apps): `no_link_wrap` until the vault was unlocked once in this build, `other_link` for
     * a link that is not this vault's.
     */
    suspend fun unlockLink(req: VaultLinkUnlockRequest): Outcome<VaultUnlocked>

    /** Stores the K_daily-sealed daily blob. */
    suspend fun dailyStore(req: VaultDailyStoreRequest): Outcome<VaultState>

    /** Ends the biometric unlock (biometric change, five failures). */
    suspend fun dailyForget(req: VaultDirRequest = VaultDirRequest()): Outcome<VaultState>

    /** Stores the K_stay-sealed stay blob (round 7c). */
    suspend fun stayStore(req: VaultDailyStoreRequest): Outcome<VaultState>

    /** Ends the silent unlock ("Lock now", a dead K_stay). */
    suspend fun stayForget(req: VaultDirRequest = VaultDirRequest()): Outcome<VaultState>

    /**
     * A fresh stay blob for the open vault (round 7c), for [stayStore]: after a passphrase change, whose commit dropped
     * the wrap of the old data key. `locked` without an open vault.
     */
    suspend fun stayPrepare(req: VaultTouchRequest): Outcome<VaultUnlocked>

    /**
     * The open vault is in use (round 7c): the use clock moves on, written at most once an hour. `expired` after 90
     * days without a use (every wrap is gone then: lock), `locked` without an open vault.
     */
    suspend fun touch(req: VaultTouchRequest): Outcome<VaultTouched>

    /** Ends the session: the keys are zeroized and the gateways opened from it released. */
    suspend fun lock(req: VaultLockRequest = VaultLockRequest()): Outcome<VaultState>

    /** A new passphrase and data key for the open session, committed like [create]. */
    suspend fun change(req: VaultHandleRequest): Outcome<VaultKeys>

    /**
     * The link of the open session, for the hand-over to a sibling Rosty app that is setting up its own vault
     * (`WaLinkProvider`, plan 13.3): only the current, committed session answers, a locked vault is `locked`. The
     * answer carries the connect key: keep it in memory only and never log it.
     */
    suspend fun exportLink(req: VaultHandleRequest): Outcome<VaultLink>

    /**
     * A Stops trip with an arriving-soon message starts while the vault is open: the trip keeps a copy of the link in
     * RAM (Rust, never on disk) so the message can go out after the vault locks again (plan 13.3). `locked` while
     * the vault is.
     */
    suspend fun tripHold(req: VaultHandleRequest): Outcome<VaultTripLink>

    /** The trip ended: its copy of the link is dropped (also done by Rust when the trip ends). */
    suspend fun tripRelease(req: Empty = Empty): Outcome<VaultTripLink>

    /** Deletes the vault files (a forgotten passphrase); the Keystore keys are the caller's to delete. */
    suspend fun reset(req: VaultDirRequest = VaultDirRequest()): Outcome<VaultReset>

    /** The migration's wipe of every plaintext WhatsApp file. */
    suspend fun wipePlaintext(req: VaultWipeRequest): Outcome<VaultWipeReport>

    /** The profile pictures of the encrypted cache (plan 13.5); `locked` while the vault is. */
    suspend fun pictures(req: VaultPicturesRequest = VaultPicturesRequest()): Outcome<VaultPictures>

    /** Keeps (or forgets) one profile picture in the encrypted cache. */
    suspend fun picturePut(req: VaultPicturePutRequest): Outcome<VaultPicturePut>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "vault.state",
                "vault.create",
                "vault.commit",
                "vault.unlock",
                "vault.unlock_daily",
                "vault.unlock_link",
                "vault.unlock_stay",
                "vault.daily_store",
                "vault.daily_forget",
                "vault.stay_store",
                "vault.stay_forget",
                "vault.stay_prepare",
                "vault.touch",
                "vault.lock",
                "vault.change",
                "vault.export_link",
                "vault.reset",
                "vault.trip_hold",
                "vault.trip_release",
                "vault.wipe_plaintext",
                "vault.pictures",
                "vault.picture_put",
            )
    }
}

/**
 * The stable code of a vault failure (`detail.code`): `typo`, `length`, `character`, `wrong_passphrase`, `damaged`,
 * `no_vault`, `no_daily`, `no_stay`, `no_link`, `locked`, `expired` (with `detail.reason`, round 7c), `vault_exists`,
 * `nothing_to_commit`, `nothing_to_store`, `not_committed`, `bad_url`, `bad_key`, `no_link_wrap`, `other_link`; also
 * `vault_unlocked` and `other_gateway` from `wa.open`. Null when Rust sent none.
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

    override suspend fun unlockLink(req: VaultLinkUnlockRequest) =
        core.call("vault.unlock_link", req, VaultLinkUnlockRequest.serializer(), VaultUnlocked.serializer())

    override suspend fun unlockStay(req: VaultStayUnlockRequest) =
        core.call("vault.unlock_stay", req, VaultStayUnlockRequest.serializer(), VaultUnlocked.serializer())

    override suspend fun stayStore(req: VaultDailyStoreRequest) =
        core.call("vault.stay_store", req, VaultDailyStoreRequest.serializer(), VaultState.serializer())

    override suspend fun stayForget(req: VaultDirRequest) =
        core.call("vault.stay_forget", req, VaultDirRequest.serializer(), VaultState.serializer())

    override suspend fun stayPrepare(req: VaultTouchRequest) =
        core.call("vault.stay_prepare", req, VaultTouchRequest.serializer(), VaultUnlocked.serializer())

    override suspend fun touch(req: VaultTouchRequest) =
        core.call("vault.touch", req, VaultTouchRequest.serializer(), VaultTouched.serializer())

    override suspend fun dailyStore(req: VaultDailyStoreRequest) =
        core.call("vault.daily_store", req, VaultDailyStoreRequest.serializer(), VaultState.serializer())

    override suspend fun dailyForget(req: VaultDirRequest) =
        core.call("vault.daily_forget", req, VaultDirRequest.serializer(), VaultState.serializer())

    override suspend fun lock(req: VaultLockRequest) =
        core.call("vault.lock", req, VaultLockRequest.serializer(), VaultState.serializer())

    override suspend fun change(req: VaultHandleRequest) =
        core.call("vault.change", req, VaultHandleRequest.serializer(), VaultKeys.serializer())

    override suspend fun exportLink(req: VaultHandleRequest) =
        core.call("vault.export_link", req, VaultHandleRequest.serializer(), VaultLink.serializer())

    override suspend fun tripHold(req: VaultHandleRequest) =
        core.call("vault.trip_hold", req, VaultHandleRequest.serializer(), VaultTripLink.serializer())

    override suspend fun tripRelease(req: Empty) =
        core.call("vault.trip_release", req, Empty.serializer(), VaultTripLink.serializer())

    override suspend fun reset(req: VaultDirRequest) =
        core.call("vault.reset", req, VaultDirRequest.serializer(), VaultReset.serializer())

    override suspend fun wipePlaintext(req: VaultWipeRequest) =
        core.call("vault.wipe_plaintext", req, VaultWipeRequest.serializer(), VaultWipeReport.serializer())

    override suspend fun pictures(req: VaultPicturesRequest) =
        core.call("vault.pictures", req, VaultPicturesRequest.serializer(), VaultPictures.serializer())

    override suspend fun picturePut(req: VaultPicturePutRequest) =
        core.call("vault.picture_put", req, VaultPicturePutRequest.serializer(), VaultPicturePut.serializer())
}
