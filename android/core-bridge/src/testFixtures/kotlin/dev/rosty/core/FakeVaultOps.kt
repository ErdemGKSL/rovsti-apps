package dev.rosty.core

import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.wa.VaultCommitRequest
import dev.rosty.core.model.wa.VaultCreateRequest
import dev.rosty.core.model.wa.VaultDailyInfo
import dev.rosty.core.model.wa.VaultDailyStoreRequest
import dev.rosty.core.model.wa.VaultDailyUnlockRequest
import dev.rosty.core.model.wa.VaultDirRequest
import dev.rosty.core.model.wa.VaultHandleRequest
import dev.rosty.core.model.wa.VaultIdleRefusal
import dev.rosty.core.model.wa.VaultInfo
import dev.rosty.core.model.wa.VaultKeys
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.core.model.wa.VaultLinkUnlockRequest
import dev.rosty.core.model.wa.VaultLockRequest
import dev.rosty.core.model.wa.VaultPicture
import dev.rosty.core.model.wa.VaultPicturePut
import dev.rosty.core.model.wa.VaultPicturePutRequest
import dev.rosty.core.model.wa.VaultPictures
import dev.rosty.core.model.wa.VaultPicturesRequest
import dev.rosty.core.model.wa.VaultReset
import dev.rosty.core.model.wa.VaultState
import dev.rosty.core.model.wa.VaultStateRequest
import dev.rosty.core.model.wa.VaultStayInfo
import dev.rosty.core.model.wa.VaultStayUnlockRequest
import dev.rosty.core.model.wa.VaultTouchRequest
import dev.rosty.core.model.wa.VaultTouched
import dev.rosty.core.model.wa.VaultTripLink
import dev.rosty.core.model.wa.VaultUnlockRequest
import dev.rosty.core.model.wa.VaultUnlocked
import dev.rosty.core.model.wa.VaultUnlockedInfo
import dev.rosty.core.model.wa.VaultWipeReport
import dev.rosty.core.model.wa.VaultWipeRequest
import dev.rosty.core.ops.VaultOps
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * [VaultOps] in memory, for JVM tests: the protocol and the error codes of `vault.*` without crypto. The "key blob" a
 * create answers is `seal-<n>`; a test's Keystore stand-in seals and unseals it however it likes, and [unlock] only
 * checks the passphrase (the real core also authenticates the blob). Passphrases are 28 characters in groups of four;
 * [unlock] reads them like the core (case, spaces and dashes do not matter) and calls anything else a `typo`. The
 * biometric and the silent (stay) unlock follow the core's use clock (round 7c): at most 90 days since the last use
 * (every unlock and [touch] is one), and the clock not set back; a reboot ends nothing. [unlockLink] opens the vault
 * with its own link, as the core's link wrap does once the vault was committed or unlocked ([linkWrapped]).
 */
class FakeVaultOps(
    /** A plaintext link the migration would read (`whatsapp-link.json`). */
    var legacyLink: VaultLink? = null,
) : VaultOps {
    private class Daily(
        val alias: String,
        val sealed: String,
        val bootCount: Long,
        val unlockedMs: Long,
    )

    private class Session(
        val handle: Long,
        val link: VaultLink,
        var committed: Boolean,
        var pendingPassphrase: String?,
        var pendingDaily: Pair<Long, Long>? = null,
        var pendingStay: Long? = null,
        var recordedMs: Long? = null,
    )

    private val lock = Any()
    private var nextHandle = 100L
    private var issued = 0
    private var vault: VaultInfo? = null
    private var link: VaultLink? = null
    private var passphrase: String? = null
    private var daily: Daily? = null
    private var stay: VaultStayInfo? = null

    /** The last use the core knows (round 7c), or null. */
    @Volatile var lastUseMs: Long? = null
        private set
    private var session: Session? = null

    /** The ops called, in order (`vault.create`, ...). */
    val calls = mutableListOf<String>()

    /** What [wipePlaintext] answers as deleted. */
    var plaintextFiles: List<String> = listOf("whatsapp-link.json")

    /** Whether the vault holds its link wrap (`vault.unlock_link`); a vault of an earlier build does not. */
    @Volatile var linkWrapped: Boolean = true

    /** Fails every call with this error while set. */
    @Volatile var failure: RustError? = null

    /** The session handle the core holds, or null while locked. */
    val openHandle: Long? get() = synchronized(lock) { session?.takeIf { it.committed }?.handle }

    private fun <T> answer(
        op: String,
        body: () -> Outcome<T>,
    ): Outcome<T> =
        synchronized(lock) {
            calls += op
            failure?.let { Outcome.Err(it) } ?: body()
        }

    private fun coded(
        kind: (String, kotlinx.serialization.json.JsonElement) -> RustError,
        message: String,
        code: String,
        reason: String? = null,
    ): Outcome.Err =
        Outcome.Err(
            kind(
                message,
                buildJsonObject {
                    put("code", JsonPrimitive(code))
                    if (reason != null) put("reason", JsonPrimitive(reason))
                },
            ),
        )

    private fun locked(handle: Long) = coded(RustError::NotFound, "vault handle $handle is not open", "locked")

    private fun stateNow(): VaultState =
        VaultState(
            vault = vault,
            daily = daily?.let { VaultDailyInfo(it.alias, it.sealed, it.bootCount, it.unlockedMs) },
            stay = stay,
            legacyLink = legacyLink != null,
            unlocked =
                session?.takeIf { it.committed }?.let {
                    VaultUnlockedInfo(it.handle, it.link.gatewayUrl, it.link.instanceId)
                },
        )

    private fun newPassphrase(): String {
        issued += 1
        val symbol = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"[issued % 32]
        return List(7) { "$symbol$symbol$symbol$symbol" }.joinToString("-")
    }

    private fun canonical(text: String): String? {
        val kept =
            text
                .uppercase()
                .filter { !it.isWhitespace() && it != '-' }
                .map {
                    when (it) {
                        'I', 'L' -> '1'
                        'O' -> '0'
                        else -> it
                    }
                }.joinToString("")
        return kept.takeIf { it.length == 28 }
    }

    /**
     * A committed vault for [link], open: what a test of an app whose WhatsApp vault is unlocked starts from (its
     * passphrase is all zeros).
     */
    fun openVault(link: VaultLink = VaultLink("https://rosty.erdemdev.tr", "inst1", "rwk_" + "A".repeat(43))) {
        synchronized(lock) {
            vault = VaultInfo("rosty.wa.k.test", "AQ==")
            this.link = link
            passphrase = List(7) { "0000" }.joinToString("-")
            open(link)
        }
    }

    /** Locks the open vault, as `vault.lock` does. */
    fun lockVault() {
        synchronized(lock) { session = null }
    }

    private fun open(
        link: VaultLink,
        nowMs: Long? = null,
    ): Long {
        session = null
        val handle = nextHandle++
        session = Session(handle, link, committed = true, pendingPassphrase = null)
        if (nowMs != null) used(nowMs)
        return handle
    }

    private fun used(nowMs: Long) {
        lastUseMs = maxOf(nowMs, lastUseMs ?: nowMs)
    }

    /** The use clock of the stay and daily wraps (round 7c), null while it holds. */
    private fun refusal(nowMs: Long): VaultIdleRefusal? {
        val last =
            listOfNotNull(lastUseMs, stay?.createdMs, daily?.unlockedMs).maxOrNull() ?: return null
        return when {
            nowMs < last - CLOCK_SKEW_MS -> VaultIdleRefusal.ClockBack
            nowMs - last > MAX_IDLE_MS -> VaultIdleRefusal.Idle
            else -> null
        }
    }

    /** Every wrap that opens without the passphrase goes, with the use record. */
    private fun expire() {
        stay = null
        daily = null
        lastUseMs = null
    }

    private fun expired(refused: VaultIdleRefusal): Outcome.Err {
        expire()
        val reason = if (refused == VaultIdleRefusal.ClockBack) "clock_back" else "idle"
        return coded(RustError::Auth, "expired", "expired", reason)
    }

    override suspend fun state(req: VaultStateRequest) =
        answer("vault.state") {
            var dropped: VaultIdleRefusal? = null
            if ((daily != null || stay != null) && req.nowMs != null) {
                dropped = refusal(req.nowMs)
                if (dropped != null) expire()
            }
            Outcome.Ok(stateNow().copy(expired = dropped))
        }

    override suspend fun create(req: VaultCreateRequest) =
        answer("vault.create") {
            if (vault != null) return@answer coded(RustError::Invalid, "a vault exists", "vault_exists")
            val source =
                req.link ?: legacyLink
                    ?: return@answer coded(RustError::NotFound, "there is no WhatsApp link to protect", "no_link")
            if (!source.key.startsWith("rwk_")) return@answer coded(RustError::Invalid, "bad key", "bad_key")
            val pass = newPassphrase()
            val handle = nextHandle++
            session = Session(handle, source, committed = false, pendingPassphrase = pass)
            Outcome.Ok(VaultKeys(handle, pass, "seal-$issued"))
        }

    override suspend fun commit(req: VaultCommitRequest) =
        answer("vault.commit") {
            val s = session?.takeIf { it.handle == req.handle } ?: return@answer locked(req.handle)
            val pass =
                s.pendingPassphrase
                    ?: return@answer coded(RustError::Invalid, "nothing to commit", "nothing_to_commit")
            vault = VaultInfo(req.hwAlias, req.hwSealed)
            link = s.link
            passphrase = pass
            expire()
            s.pendingPassphrase = null
            s.committed = true
            Outcome.Ok(stateNow())
        }

    override suspend fun unlock(req: VaultUnlockRequest) =
        answer("vault.unlock") {
            val typed = canonical(req.passphrase) ?: return@answer coded(RustError::Invalid, "typo", "typo")
            val stored = passphrase ?: return@answer coded(RustError::NotFound, "no vault", "no_vault")
            if (typed != canonical(stored)) {
                return@answer coded(RustError::Auth, "wrong passphrase", "wrong_passphrase")
            }
            lastUseMs = null
            val handle = open(checkNotNull(link), req.nowMs)
            session?.pendingDaily = req.bootCount to req.nowMs
            session?.pendingStay = req.nowMs
            Outcome.Ok(VaultUnlocked(handle, "daily-$handle", "stay-$handle"))
        }

    override suspend fun unlockDaily(req: VaultDailyUnlockRequest) =
        answer("vault.unlock_daily") {
            if (daily == null) return@answer coded(RustError::NotFound, "no daily unlock", "no_daily")
            refusal(req.nowMs)?.let { return@answer expired(it) }
            val handle = open(checkNotNull(link), req.nowMs)
            session?.pendingStay = req.nowMs
            Outcome.Ok(VaultUnlocked(handle, staySeal = "stay-$handle"))
        }

    override suspend fun unlockStay(req: VaultStayUnlockRequest) =
        answer("vault.unlock_stay") {
            if (stay == null) return@answer coded(RustError::NotFound, "no stay wrap", "no_stay")
            refusal(req.nowMs)?.let { return@answer expired(it) }
            Outcome.Ok(VaultUnlocked(open(checkNotNull(link), req.nowMs)))
        }

    override suspend fun unlockLink(req: VaultLinkUnlockRequest) =
        answer("vault.unlock_link") {
            val own = link ?: return@answer coded(RustError::NotFound, "no vault", "no_vault")
            if (vault == null) return@answer coded(RustError::NotFound, "no vault", "no_vault")
            if (!linkWrapped) return@answer coded(RustError::NotFound, "no link wrap", "no_link_wrap")
            if (req.link != own) return@answer coded(RustError::Auth, "not this vault's link", "other_link")
            val handle = open(own, req.nowMs)
            session?.pendingStay = req.nowMs
            Outcome.Ok(VaultUnlocked(handle, staySeal = "stay-$handle"))
        }

    override suspend fun dailyStore(req: VaultDailyStoreRequest) =
        answer("vault.daily_store") {
            val s = session?.takeIf { it.handle == req.handle } ?: return@answer locked(req.handle)
            val (boot, at) =
                s.pendingDaily
                    ?: return@answer coded(RustError::Invalid, "nothing to store", "nothing_to_store")
            daily = Daily(req.alias, req.sealed, boot, at)
            s.pendingDaily = null
            Outcome.Ok(stateNow())
        }

    override suspend fun dailyForget(req: VaultDirRequest) =
        answer("vault.daily_forget") {
            daily = null
            Outcome.Ok(stateNow())
        }

    override suspend fun stayStore(req: VaultDailyStoreRequest) =
        answer("vault.stay_store") {
            val s = session?.takeIf { it.handle == req.handle } ?: return@answer locked(req.handle)
            val at = s.pendingStay ?: return@answer coded(RustError::Invalid, "nothing to store", "nothing_to_store")
            stay = VaultStayInfo(req.alias, req.sealed, at)
            s.pendingStay = null
            Outcome.Ok(stateNow())
        }

    override suspend fun stayForget(req: VaultDirRequest) =
        answer("vault.stay_forget") {
            stay = null
            Outcome.Ok(stateNow())
        }

    override suspend fun stayPrepare(req: VaultTouchRequest) =
        answer("vault.stay_prepare") {
            val s = session?.takeIf { it.committed } ?: return@answer locked(0)
            s.pendingStay = req.nowMs
            Outcome.Ok(VaultUnlocked(s.handle, staySeal = "stay-${s.handle}"))
        }

    override suspend fun touch(req: VaultTouchRequest) =
        answer("vault.touch") {
            val s = session?.takeIf { it.committed } ?: return@answer locked(0)
            val last = lastUseMs
            if (last != null) {
                when {
                    req.nowMs < last - CLOCK_SKEW_MS -> return@answer expired(VaultIdleRefusal.ClockBack)
                    req.nowMs - last > MAX_IDLE_MS -> return@answer expired(VaultIdleRefusal.Idle)
                }
            }
            used(req.nowMs)
            val due = s.recordedMs.let { it == null || req.nowMs - it >= HOUR_MS }
            if (due) s.recordedMs = req.nowMs
            Outcome.Ok(VaultTouched(recorded = due))
        }

    override suspend fun lock(req: VaultLockRequest) =
        answer("vault.lock") {
            if (req.handle == null || session?.handle == req.handle) session = null
            Outcome.Ok(stateNow())
        }

    override suspend fun change(req: VaultHandleRequest) =
        answer("vault.change") {
            val s = session?.takeIf { it.handle == req.handle && it.committed } ?: return@answer locked(req.handle)
            val pass = newPassphrase()
            s.pendingPassphrase = pass
            Outcome.Ok(VaultKeys(s.handle, pass, "seal-$issued"))
        }

    override suspend fun exportLink(req: VaultHandleRequest) =
        answer("vault.export_link") {
            val s = session?.takeIf { it.handle == req.handle && it.committed } ?: return@answer locked(req.handle)
            Outcome.Ok(s.link)
        }

    /** The link a Stops trip keeps in RAM (`vault.trip_hold`), or null. */
    @Volatile var tripLink: VaultLink? = null
        private set

    override suspend fun tripHold(req: VaultHandleRequest) =
        answer("vault.trip_hold") {
            val s = session?.takeIf { it.handle == req.handle && it.committed } ?: return@answer locked(req.handle)
            tripLink = s.link
            Outcome.Ok(VaultTripLink(held = true))
        }

    override suspend fun tripRelease(req: Empty) =
        answer("vault.trip_release") {
            tripLink = null
            Outcome.Ok(VaultTripLink(held = false))
        }

    override suspend fun reset(req: VaultDirRequest) =
        answer("vault.reset") {
            val deleted = buildList { if (vault != null) add("wa-vault.json") }
            vault = null
            link = null
            passphrase = null
            expire()
            session = null
            Outcome.Ok(VaultReset(deleted))
        }

    override suspend fun wipePlaintext(req: VaultWipeRequest) =
        answer("vault.wipe_plaintext") {
            if (legacyLink != null && vault == null) {
                return@answer coded(RustError::Invalid, "create the vault first", "no_vault")
            }
            legacyLink = null
            Outcome.Ok(VaultWipeReport(plaintextFiles, emptyList(), 0, emptyList()))
        }

    /** The encrypted picture cache by jid; it outlives a lock, as the file does. */
    val pictureCache = linkedMapOf<String, VaultPicture>()

    override suspend fun pictures(req: VaultPicturesRequest) =
        answer("vault.pictures") {
            if (sessionHandle() == null) return@answer coded(RustError::NotFound, "the vault is locked", "locked")
            Outcome.Ok(VaultPictures(pictureCache.values.filter { req.jids == null || it.jid in req.jids }))
        }

    override suspend fun picturePut(req: VaultPicturePutRequest) =
        answer("vault.picture_put") {
            val handle =
                sessionHandle() ?: return@answer coded(RustError::NotFound, "the vault is locked", "locked")
            pictureCache.remove(req.jid)
            val id = req.pictureId
            val jpeg = req.jpeg
            if (id != null && jpeg != null) pictureCache[req.jid] = VaultPicture(req.jid, id, jpeg)
            Outcome.Ok(VaultPicturePut(handle, pictureCache.size))
        }

    private fun sessionHandle(): Long? = session?.takeIf { it.committed }?.handle

    companion object {
        /** The core's use clock (`rosty_core::vault::UseClock`): 90 days, 5 min of skew, a write an hour. */
        const val MAX_IDLE_MS = 90 * 24 * 3_600_000L
        const val CLOCK_SKEW_MS = 5 * 60_000L
        const val HOUR_MS = 3_600_000L
    }
}
