package dev.rosty.core

import dev.rosty.core.model.wa.VaultCommitRequest
import dev.rosty.core.model.wa.VaultCreateRequest
import dev.rosty.core.model.wa.VaultDailyInfo
import dev.rosty.core.model.wa.VaultDailyRefusal
import dev.rosty.core.model.wa.VaultDailyStoreRequest
import dev.rosty.core.model.wa.VaultDailyUnlockRequest
import dev.rosty.core.model.wa.VaultDirRequest
import dev.rosty.core.model.wa.VaultHandleRequest
import dev.rosty.core.model.wa.VaultInfo
import dev.rosty.core.model.wa.VaultKeys
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.core.model.wa.VaultLockRequest
import dev.rosty.core.model.wa.VaultReset
import dev.rosty.core.model.wa.VaultState
import dev.rosty.core.model.wa.VaultStateRequest
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
 * daily unlock follows the core's rules: same boot, at most 72 hours since the last unlock.
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
    )

    private val lock = Any()
    private var nextHandle = 100L
    private var issued = 0
    private var vault: VaultInfo? = null
    private var link: VaultLink? = null
    private var passphrase: String? = null
    private var daily: Daily? = null
    private var session: Session? = null

    /** The ops called, in order (`vault.create`, ...). */
    val calls = mutableListOf<String>()

    /** What [wipePlaintext] answers as deleted. */
    var plaintextFiles: List<String> = listOf("whatsapp-link.json")

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

    private fun open(link: VaultLink): Long {
        session = null
        val handle = nextHandle++
        session = Session(handle, link, committed = true, pendingPassphrase = null)
        return handle
    }

    override suspend fun state(req: VaultStateRequest) =
        answer("vault.state") {
            var dropped: VaultDailyRefusal? = null
            val current = daily
            if (current != null && req.bootCount != null && req.nowMs != null) {
                dropped = refusal(current, req.bootCount, req.nowMs)
                if (dropped != null) daily = null
            }
            Outcome.Ok(stateNow().copy(dailyDropped = dropped))
        }

    private fun refusal(
        daily: Daily,
        bootCount: Long,
        nowMs: Long,
    ): VaultDailyRefusal? =
        when {
            bootCount != daily.bootCount -> VaultDailyRefusal.Rebooted
            nowMs < daily.unlockedMs - 5 * 60_000 -> VaultDailyRefusal.ClockBack
            nowMs - daily.unlockedMs > 72 * 3_600_000L -> VaultDailyRefusal.Idle
            else -> null
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
            daily = null
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
            val handle = open(checkNotNull(link))
            session?.pendingDaily = req.bootCount to req.nowMs
            Outcome.Ok(VaultUnlocked(handle, "daily-$handle"))
        }

    override suspend fun unlockDaily(req: VaultDailyUnlockRequest) =
        answer("vault.unlock_daily") {
            val current = daily ?: return@answer coded(RustError::NotFound, "no daily unlock", "no_daily")
            refusal(current, req.bootCount, req.nowMs)?.let { refused ->
                daily = null
                return@answer coded(RustError::Auth, "daily expired", "daily_expired", refused.name.lowercase())
            }
            val handle = open(checkNotNull(link))
            session?.pendingDaily = req.bootCount to req.nowMs
            Outcome.Ok(VaultUnlocked(handle, "daily-$handle"))
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

    override suspend fun reset(req: VaultDirRequest) =
        answer("vault.reset") {
            val deleted = buildList { if (vault != null) add("wa-vault.json") }
            vault = null
            link = null
            passphrase = null
            daily = null
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
}
