package dev.rosty.wa.vault

import android.content.Context
import androidx.compose.runtime.Immutable
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.VaultCommitRequest
import dev.rosty.core.model.wa.VaultDailyRefusal
import dev.rosty.core.model.wa.VaultDailyStoreRequest
import dev.rosty.core.model.wa.VaultDailyUnlockRequest
import dev.rosty.core.model.wa.VaultHandleRequest
import dev.rosty.core.model.wa.VaultLockRequest
import dev.rosty.core.model.wa.VaultState
import dev.rosty.core.model.wa.VaultStateRequest
import dev.rosty.core.model.wa.VaultUnlockRequest
import dev.rosty.core.model.wa.VaultUnlocked
import dev.rosty.core.ops.VaultOps
import dev.rosty.core.ops.vaultCode
import dev.rosty.platform.vault.VaultAlias
import dev.rosty.platform.vault.VaultCrypto
import dev.rosty.platform.vault.VaultKeyAuth
import dev.rosty.platform.vault.VaultKeyKind
import dev.rosty.platform.vault.VaultKeystore
import dev.rosty.platform.vault.VaultPrompt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random

/** Where WhatsApp stands with its vault (`_bmad-output/kotlin-ui/plans/wa-vault.md`). */
@Immutable
sealed interface WaVaultState {
    /** Not read yet. */
    data object Checking : WaVaultState

    /** No link and no vault: the link flow comes first, then the setup. */
    data object NoLink : WaVaultState

    /** A plaintext link waits for its vault (an upgrade, or a link just made): the setup. */
    data object NeedsSetup : WaVaultState

    /**
     * Locked. [biometric]: the daily unlock is there (plan 13.2); otherwise the passphrase is needed, and [reason]
     * says why when the app knows.
     */
    data class Locked(
        val biometric: Boolean,
        val reason: PassphraseReason? = null,
    ) : WaVaultState

    data object Unlocked : WaVaultState

    /**
     * The vault cannot be opened on this phone any more: its Keystore key is gone (the screen lock was removed; a new
     * biometric enrolment only drops the daily key) or the file is damaged. Only a reset and a new link help.
     */
    data object Broken : WaVaultState
}

/** Why the passphrase is asked for instead of the biometric (plan 13.2). */
enum class PassphraseReason {
    /** The phone restarted. */
    Rebooted,

    /** 72 hours went by without a passphrase unlock. */
    Idle,

    /** The clock was set back. */
    ClockBack,

    /** A fingerprint or face was added, or the biometric was removed. */
    BiometricChanged,

    /** Five failed biometric attempts. */
    TooManyAttempts,

    /** The passphrase was just changed. */
    NewPassphrase,
}

/** What an operation ended in. */
@Immutable
sealed interface VaultOutcome {
    data object Done : VaultOutcome

    /** The person closed the system prompt. */
    data object Cancelled : VaultOutcome

    data class Refused(
        val problem: VaultProblem,
    ) : VaultOutcome
}

/** Why an operation was refused; the UI words each one (en, tr). */
enum class VaultProblem {
    /** The passphrase is well formed but not this vault's. */
    WrongPassphrase,

    /** A character is wrong or two are swapped (the check characters caught it). */
    Typo,

    /** Too short, too long or a character no passphrase has. */
    Malformed,

    /** The two groups typed back do not match the passphrase. */
    GroupsDiffer,

    /** No screen lock: the Keystore cannot protect a key. */
    NoScreenLock,

    /** Too many attempts: the system paused the biometric. */
    LockedOut,

    /** The biometric unlock lapsed; the passphrase is needed. */
    NeedsPassphrase,

    /** The vault can no longer be opened here (see [WaVaultState.Broken]). */
    Broken,

    /** Anything else (the log has the details). */
    Failed,
}

/** A new passphrase on show (setup, change): its groups, and the blob to seal once it is confirmed. */
@Immutable
class PassphraseOffer internal constructor(
    internal val handle: Long,
    internal val passphrase: String,
    internal val seal: String,
    /** The two groups (0-based) the person types back. */
    val ask: List<Int>,
) {
    /** `XXXX` x 7, in order. */
    val groups: List<String> get() = passphrase.split('-')

    override fun toString(): String = "PassphraseOffer(handle=$handle, groups=…)"
}

/** The words of the system prompts, from the app's resources. */
data class VaultPrompts(
    val protect: VaultPrompt,
    val unlock: VaultPrompt,
    val daily: VaultPrompt,
    val enableDaily: VaultPrompt,
)

/** The device facts the vault stamps into the daily blob. */
class WaVaultEnv(
    /** `Settings.Global.BOOT_COUNT` (0 when unreadable). */
    val bootCount: () -> Long,
    val now: () -> Long,
)

/** What the vault keeps outside Rust: the failed biometric attempts and the auto-lock delay. No secret. */
interface WaVaultPrefs {
    var failedDaily: Int

    /** How long WhatsApp stays unlocked in the background; 0 locks at once. */
    var autoLockMs: Long
}

class MemoryWaVaultPrefs(
    override var failedDaily: Int = 0,
    override var autoLockMs: Long = WaVault.DEFAULT_AUTO_LOCK_MS,
) : WaVaultPrefs

/** [WaVaultPrefs] in the app's `rosty_wa_vault` preferences (not backed up: `allowBackup=false`). */
class SharedWaVaultPrefs(
    context: Context,
) : WaVaultPrefs {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    override var failedDaily: Int
        get() = prefs.getInt(KEY_FAILED, 0)
        set(value) {
            prefs.edit().putInt(KEY_FAILED, value).apply()
        }

    override var autoLockMs: Long
        get() = prefs.getLong(KEY_AUTO_LOCK, WaVault.DEFAULT_AUTO_LOCK_MS)
        set(value) {
            prefs.edit().putLong(KEY_AUTO_LOCK, value).apply()
        }

    private companion object {
        const val NAME = "rosty_wa_vault"
        const val KEY_FAILED = "failed_daily"
        const val KEY_AUTO_LOCK = "auto_lock_ms"
    }
}

/**
 * The WhatsApp vault of one app: setup, the daily (biometric) and the passphrase unlock, the passphrase change, the
 * reset, and the lock. Rust holds every key ([ops]); [keystore] seals and opens the opaque blobs; the app tears its
 * WhatsApp down in [onLock] and brings it up in [onUnlocked]. One operation at a time.
 *
 * Rules kept here (plan 13.2): the daily unlock is dropped after a reboot or 72 h (Rust checks the stamp in
 * [refresh]), after a biometric enrolment change (the Keystore invalidates K_daily), after [MAX_FAILED_DAILY] failed
 * attempts, and on [onBoot]; a screen-lock daily key is dropped once a Class 3 biometric is enrolled.
 */
class WaVault(
    private val ops: VaultOps,
    private val keystore: VaultKeystore,
    private val prefs: WaVaultPrefs,
    private val env: WaVaultEnv,
    private val prompts: VaultPrompts,
    private val onLock: suspend () -> Unit,
    private val onUnlocked: suspend () -> Unit,
    /** After [reset]: the app forgets its link and everything kept for it. */
    private val onReset: suspend () -> Unit = {},
    private val random: Random = Random.Default,
) {
    private val stateFlow = MutableStateFlow<WaVaultState>(WaVaultState.Checking)
    val state: StateFlow<WaVaultState> = stateFlow.asStateFlow()

    private val promptingFlow = MutableStateFlow(false)

    /** A system prompt is up (it may cover the app with another activity: the background timer waits). */
    val prompting: StateFlow<Boolean> = promptingFlow.asStateFlow()

    private val mutex = Mutex()

    /** Why the passphrase is needed, when this process dropped the daily unlock itself. */
    @Volatile private var reason: PassphraseReason? = null

    /** K_hw would not open: only a reset helps, whatever the files say. */
    @Volatile private var broken = false

    val unlocked: Boolean get() = stateFlow.value == WaVaultState.Unlocked

    var autoLockMs: Long
        get() = prefs.autoLockMs
        set(value) {
            prefs.autoLockMs = value.coerceAtLeast(0)
        }

    /** Reads the vault (and drops a lapsed daily unlock). */
    suspend fun refresh() = mutex.withLock { refreshLocked() }

    private suspend fun stateNow(): Outcome<VaultState> =
        ops.state(VaultStateRequest(bootCount = env.bootCount(), nowMs = env.now()))

    private suspend fun refreshLocked(): VaultState? {
        val st =
            when (val read = stateNow()) {
                is Outcome.Err -> {
                    if (read.error.vaultCode == DAMAGED) stateFlow.value = WaVaultState.Broken
                    return null
                }

                is Outcome.Ok -> {
                    read.value
                }
            }
        st.dailyDropped?.let { reason = it.reason() }
        tidyKeys(st)
        val daily = st.daily
        // A screen-lock daily key on a phone that has a Class 3 biometric now: the biometric is required (13.2).
        if (daily != null && VaultAlias.authOf(daily.alias) == VaultKeyAuth.Credential &&
            keystore.device().strongBiometric
        ) {
            return forgetDailyLocked(PassphraseReason.BiometricChanged)
        }
        if (daily != null && prefs.failedDaily >= MAX_FAILED_DAILY) {
            return forgetDailyLocked(PassphraseReason.TooManyAttempts)
        }
        publish(st)
        return st
    }

    private fun publish(st: VaultState) {
        stateFlow.value =
            when {
                st.unlocked != null -> WaVaultState.Unlocked
                st.vault != null && broken -> WaVaultState.Broken
                st.vault != null -> WaVaultState.Locked(biometric = st.daily != null, reason = reason)
                st.legacyLink -> WaVaultState.NeedsSetup
                else -> WaVaultState.NoLink
            }
    }

    /** Keystore keys the vault no longer names (an old passphrase, a dropped daily unlock) are deleted. */
    private fun tidyKeys(st: VaultState) {
        val keep = setOfNotNull(st.vault?.hwAlias, st.daily?.alias)
        for (alias in keystore.aliases()) if (alias !in keep) keystore.delete(alias)
    }

    private suspend fun forgetDailyLocked(why: PassphraseReason): VaultState? {
        reason = why
        prefs.failedDaily = 0
        ops.dailyForget()
        deleteKeys(VaultKeyKind.Daily)
        return refreshLocked()
    }

    private fun deleteKeys(kind: VaultKeyKind) {
        for (alias in keystore.aliases()) if (VaultAlias.kindOf(alias) == kind) keystore.delete(alias)
    }

    /** The phone booted (`BootReceiver`): the daily unlock ends (plan 13.2). */
    suspend fun onBoot() {
        mutex.withLock {
            reason = PassphraseReason.Rebooted
            ops.dailyForget()
            deleteKeys(VaultKeyKind.Daily)
            refreshLocked()
        }
    }

    private suspend fun <T> prompt(block: suspend () -> T): T {
        promptingFlow.value = true
        try {
            return block()
        } finally {
            promptingFlow.value = false
        }
    }

    // ---- unlock

    /** The daily unlock: the biometric opens K_daily, Rust opens the vault with what it decrypts. */
    suspend fun unlockWithBiometric(): VaultOutcome =
        mutex.withLock {
            val st = refreshLocked() ?: return@withLock refused(VaultProblem.Failed)
            if (st.unlocked != null) return@withLock VaultOutcome.Done
            val daily = st.daily ?: return@withLock refused(VaultProblem.NeedsPassphrase)
            val opened =
                prompt { keystore.open(daily.alias, daily.sealed, prompts.daily) { prefs.failedDaily += 1 } }
            when (opened) {
                is VaultCrypto.Done -> {
                    val request =
                        VaultDailyUnlockRequest(dailyBlob = opened.b64, bootCount = env.bootCount(), nowMs = env.now())
                    val unlocked = ops.unlockDaily(request)
                    when (unlocked) {
                        is Outcome.Ok -> {
                            becameUnlocked()
                            VaultOutcome.Done
                        }

                        is Outcome.Err -> {
                            forgetDailyLocked(dailyReason(unlocked.error))
                            refused(VaultProblem.NeedsPassphrase)
                        }
                    }
                }

                VaultCrypto.Cancelled -> {
                    afterFailures()
                    VaultOutcome.Cancelled
                }

                VaultCrypto.LockedOut -> {
                    forgetDailyLocked(PassphraseReason.TooManyAttempts)
                    refused(VaultProblem.NeedsPassphrase)
                }

                VaultCrypto.Invalidated, VaultCrypto.NoCredential -> {
                    forgetDailyLocked(PassphraseReason.BiometricChanged)
                    refused(VaultProblem.NeedsPassphrase)
                }

                is VaultCrypto.Failed -> {
                    afterFailures()
                    refused(VaultProblem.Failed)
                }
            }
        }

    /** Five wrong fingers end the daily unlock even when the prompt is then cancelled. */
    private suspend fun afterFailures() {
        if (prefs.failedDaily >= MAX_FAILED_DAILY) forgetDailyLocked(PassphraseReason.TooManyAttempts)
    }

    /**
     * The passphrase unlock: K_hw (biometric or screen lock) decrypts the key blob, Rust unwraps it with [passphrase].
     * Each try needs its own hardware authentication. Then the daily unlock is offered (a second prompt).
     */
    suspend fun unlockWithPassphrase(passphrase: String): VaultOutcome =
        mutex.withLock {
            if (!PassphraseText.wellFormed(passphrase)) return@withLock refused(VaultProblem.Malformed)
            val st = refreshLocked() ?: return@withLock refused(VaultProblem.Failed)
            if (st.unlocked != null) return@withLock VaultOutcome.Done
            val vault = st.vault ?: return@withLock refused(VaultProblem.Failed)
            val opened = prompt { keystore.open(vault.hwAlias, vault.hwSealed, prompts.unlock) }
            val keyBlob =
                when (opened) {
                    is VaultCrypto.Done -> opened.b64
                    else -> return@withLock hardwareProblem(opened)
                }
            val unlocked =
                ops.unlock(
                    VaultUnlockRequest(
                        keyBlob = keyBlob,
                        passphrase = passphrase,
                        bootCount = env.bootCount(),
                        nowMs = env.now(),
                    ),
                )
            when (unlocked) {
                is Outcome.Err -> {
                    refused(passphraseProblem(unlocked.error))
                }

                is Outcome.Ok -> {
                    prefs.failedDaily = 0
                    reason = null
                    offerDaily(unlocked.value)
                    becameUnlocked()
                    VaultOutcome.Done
                }
            }
        }

    private fun hardwareProblem(crypto: VaultCrypto): VaultOutcome =
        when (crypto) {
            VaultCrypto.Cancelled -> {
                VaultOutcome.Cancelled
            }

            VaultCrypto.LockedOut -> {
                refused(VaultProblem.LockedOut)
            }

            VaultCrypto.NoCredential -> {
                refused(VaultProblem.NoScreenLock)
            }

            VaultCrypto.Invalidated -> {
                broken = true
                stateFlow.value = WaVaultState.Broken
                refused(VaultProblem.Broken)
            }

            is VaultCrypto.Failed, is VaultCrypto.Done -> {
                refused(VaultProblem.Failed)
            }
        }

    /** Seals the fresh daily blob with a new K_daily; a cancelled prompt just leaves the daily unlock off. */
    private suspend fun offerDaily(unlocked: VaultUnlocked) {
        deleteKeys(VaultKeyKind.Daily)
        if (!keystore.device().secureLockScreen) return
        val alias = keystore.newAlias(VaultKeyKind.Daily)
        val sealed = prompt { keystore.seal(alias, unlocked.dailySeal, prompts.enableDaily) }
        val stored =
            sealed is VaultCrypto.Done &&
                ops.dailyStore(VaultDailyStoreRequest(unlocked.handle, alias, sealed.b64)) is Outcome.Ok
        if (!stored) keystore.delete(alias)
    }

    private suspend fun becameUnlocked() {
        prefs.failedDaily = 0
        stateFlow.value = WaVaultState.Unlocked
        onUnlocked()
    }

    // ---- setup and change

    /** A new vault for the plaintext link: the passphrase to show. Nothing is on disk until [confirm]. */
    suspend fun startSetup(): Outcome<PassphraseOffer> =
        mutex.withLock {
            when (val created = ops.create()) {
                is Outcome.Err -> created
                is Outcome.Ok -> Outcome.Ok(created.value.let { offerOf(it.handle, it.passphrase, it.seal) })
            }
        }

    /** A new passphrase for the open vault (plan 3: a new K_hw alias, the old one deleted). */
    suspend fun startChange(): Outcome<PassphraseOffer> =
        mutex.withLock {
            val handle =
                (stateNow() as? Outcome.Ok)?.value?.unlocked?.handle
                    ?: return@withLock Outcome.Err(RustError.NotFound("the vault is locked"))
            when (val changed = ops.change(VaultHandleRequest(handle))) {
                is Outcome.Err -> changed
                is Outcome.Ok -> Outcome.Ok(changed.value.let { offerOf(it.handle, it.passphrase, it.seal) })
            }
        }

    private fun offerOf(
        handle: Long,
        passphrase: String,
        seal: String,
    ): PassphraseOffer {
        val count = passphrase.split('-').size
        return PassphraseOffer(handle, passphrase, seal, PassphraseText.pickGroups(count, random))
    }

    /**
     * Seals the offer with a new K_hw and commits it once [typed] (the asked groups, in order) match. After a setup
     * the daily unlock is offered at once; after a change the next unlock asks for the new passphrase once.
     */
    suspend fun confirm(
        offer: PassphraseOffer,
        typed: List<String>,
    ): VaultOutcome =
        mutex.withLock {
            if (!PassphraseText.groupsMatch(offer, typed)) return@withLock refused(VaultProblem.GroupsDiffer)
            val before = (stateNow() as? Outcome.Ok)?.value
            val change = before?.unlocked?.handle == offer.handle
            if (!keystore.device().secureLockScreen) return@withLock refused(VaultProblem.NoScreenLock)
            val alias = keystore.newAlias(VaultKeyKind.Hardware)
            val sealed =
                when (val crypto = prompt { keystore.seal(alias, offer.seal, prompts.protect) }) {
                    is VaultCrypto.Done -> {
                        crypto.b64
                    }

                    else -> {
                        keystore.delete(alias)
                        return@withLock hardwareProblem(crypto)
                    }
                }
            if (ops.commit(VaultCommitRequest(offer.handle, alias, sealed)) !is Outcome.Ok) {
                keystore.delete(alias)
                return@withLock refused(VaultProblem.Failed)
            }
            before
                ?.vault
                ?.hwAlias
                ?.takeIf { it != alias }
                ?.let(keystore::delete)
            // The daily blob sealed the old data key: it is gone with the commit.
            deleteKeys(VaultKeyKind.Daily)
            prefs.failedDaily = 0
            if (change) {
                reason = PassphraseReason.NewPassphrase
                refreshLocked()
                return@withLock VaultOutcome.Done
            }
            // A new vault opens no gateway yet, so a fresh session (and with it a daily blob) costs nothing.
            val unlocked =
                ops.unlock(
                    VaultUnlockRequest(
                        keyBlob = offer.seal,
                        passphrase = offer.passphrase,
                        bootCount = env.bootCount(),
                        nowMs = env.now(),
                    ),
                )
            if (unlocked is Outcome.Ok) offerDaily(unlocked.value)
            reason = null
            becameUnlocked()
            VaultOutcome.Done
        }

    /** A setup the person left: the pending session is released (a change keeps its open session). */
    suspend fun abandon(offer: PassphraseOffer) {
        mutex.withLock {
            val open = (stateNow() as? Outcome.Ok)?.value?.unlocked?.handle
            if (open != offer.handle) ops.lock(VaultLockRequest(handle = offer.handle))
        }
    }

    // ---- lock and reset

    /** Locks WhatsApp now: the state turns at once, then the app tears down and Rust zeroizes the keys. */
    suspend fun lock() {
        val was = stateFlow.value
        if (was == WaVaultState.Unlocked) stateFlow.value = WaVaultState.Locked(biometric = true)
        onLock()
        mutex.withLock {
            ops.lock()
            refreshLocked()
        }
    }

    /**
     * "Forgot passphrase" (plan 5): the vault, its Keystore keys and (through [onReset]) the link go; the person links
     * again and the history comes back from the gateway.
     */
    suspend fun reset(): VaultOutcome {
        onLock()
        return mutex.withLock {
            ops.lock()
            val done = ops.reset() is Outcome.Ok
            for (alias in keystore.aliases()) keystore.delete(alias)
            prefs.failedDaily = 0
            reason = null
            broken = false
            onReset()
            refreshLocked()
            if (done) VaultOutcome.Done else refused(VaultProblem.Failed)
        }
    }

    private fun refused(problem: VaultProblem) = VaultOutcome.Refused(problem)

    companion object {
        const val MAX_FAILED_DAILY = 5
        const val DEFAULT_AUTO_LOCK_MS = 60_000L

        /** The auto-lock choices of the WhatsApp settings: at once, 30 s, 1 min, 5 min. */
        val AUTO_LOCK_CHOICES_MS = listOf(0L, 30_000L, 60_000L, 300_000L)

        private const val DAMAGED = "damaged"

        private fun VaultDailyRefusal.reason(): PassphraseReason =
            when (this) {
                VaultDailyRefusal.Rebooted -> PassphraseReason.Rebooted
                VaultDailyRefusal.Idle -> PassphraseReason.Idle
                VaultDailyRefusal.ClockBack -> PassphraseReason.ClockBack
            }

        private fun dailyReason(error: RustError): PassphraseReason {
            val why = ((error.detail as? JsonObject)?.get("reason") as? JsonPrimitive)?.content
            return when (why) {
                "rebooted" -> PassphraseReason.Rebooted
                "clock_back" -> PassphraseReason.ClockBack
                else -> PassphraseReason.Idle
            }
        }

        private fun passphraseProblem(error: RustError): VaultProblem =
            when (error.vaultCode) {
                "wrong_passphrase" -> VaultProblem.WrongPassphrase
                "typo" -> VaultProblem.Typo
                "length", "character" -> VaultProblem.Malformed
                DAMAGED -> VaultProblem.Broken
                else -> VaultProblem.Failed
            }
    }
}

/** The passphrase as people type it: pure rules, tested on the JVM. */
object PassphraseText {
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val LENGTH = 28

    /** What Rust reads (case, spaces and dashes ignored, I/L as 1, O as 0). */
    fun canonical(text: String): String =
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

    /** Long enough and only passphrase characters: worth a hardware prompt. Typos are caught by Rust's check. */
    fun wellFormed(text: String): Boolean {
        val c = canonical(text)
        return c.length == LENGTH && c.all { it in CROCKFORD }
    }

    /** Two distinct group indices out of [count], in order. */
    fun pickGroups(
        count: Int,
        random: Random,
    ): List<Int> {
        if (count < 2) return listOf(0)
        val first = random.nextInt(count)
        val second = (first + 1 + random.nextInt(count - 1)) % count
        return listOf(first, second).sorted()
    }

    fun groupsMatch(
        offer: PassphraseOffer,
        typed: List<String>,
    ): Boolean {
        val groups = offer.groups
        if (typed.size != offer.ask.size) return false
        return offer.ask.zip(typed).all { (index, text) ->
            canonical(text) == canonical(groups.getOrElse(index) { "" })
        }
    }
}
