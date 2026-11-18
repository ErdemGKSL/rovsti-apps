package dev.rosty.wa.vault

import android.content.Context
import androidx.compose.runtime.Immutable
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.core.model.wa.VaultCommitRequest
import dev.rosty.core.model.wa.VaultCreateRequest
import dev.rosty.core.model.wa.VaultDailyStoreRequest
import dev.rosty.core.model.wa.VaultDailyUnlockRequest
import dev.rosty.core.model.wa.VaultHandleRequest
import dev.rosty.core.model.wa.VaultIdleRefusal
import dev.rosty.core.model.wa.VaultLink
import dev.rosty.core.model.wa.VaultLinkUnlockRequest
import dev.rosty.core.model.wa.VaultLockRequest
import dev.rosty.core.model.wa.VaultState
import dev.rosty.core.model.wa.VaultStateRequest
import dev.rosty.core.model.wa.VaultStayUnlockRequest
import dev.rosty.core.model.wa.VaultTouchRequest
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
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * A link waits for its vault: a link just made or handed over by a sibling app (held by Rust in memory only,
     * [WaVault.adopt]), or a plaintext `whatsapp-link.json` of an earlier build (the migration). The setup.
     */
    data object NeedsSetup : WaVaultState

    /**
     * Locked. [silent]: the stay wrap is there and the phone is unlocked, so the vault reopens with no prompt (round
     * 7c; the page waits for it). [biometric]: the biometric unlock is there (plan 13.2); otherwise the passphrase is
     * needed, and [reason] says why when the app knows.
     */
    data class Locked(
        val biometric: Boolean,
        val reason: PassphraseReason? = null,
        val silent: Boolean = false,
    ) : WaVaultState

    data object Unlocked : WaVaultState

    /**
     * The vault cannot be opened on this phone any more: its Keystore key is gone (the screen lock was removed; a new
     * biometric enrolment only drops the daily key) or the file is damaged. Only a reset and a new link help.
     */
    data object Broken : WaVaultState
}

/** Why the passphrase is asked for instead of the biometric (plan 13.2, round 7c). */
enum class PassphraseReason {
    /** 90 days went by without a use (round 7c: any use while unlocked resets the count). */
    Idle,

    /** The clock was set back before the last use. */
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

/** What [WaVault.unlockInline] did. */
enum class InlineUnlock {
    /** The vault is open. */
    Opened,

    /** The person cancelled the prompt. */
    Cancelled,

    /** Only the unlock page can open it (the passphrase is needed, or no prompt could show). */
    NeedsPage,
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

/** The device facts the vault needs: the clock of the use rule, and the boot count the daily blob's format keeps. */
class WaVaultEnv(
    /** `Settings.Global.BOOT_COUNT` (0 when unreadable); stamped, not checked since round 7c. */
    val bootCount: () -> Long,
    val now: () -> Long,
)

/** What the vault keeps outside Rust: the failed biometric attempts. No secret. */
interface WaVaultPrefs {
    var failedDaily: Int
}

class MemoryWaVaultPrefs(
    override var failedDaily: Int = 0,
) : WaVaultPrefs

/** [WaVaultPrefs] in the app's `rosty_wa_vault` preferences (not backed up: `allowBackup=false`). */
class SharedWaVaultPrefs(
    context: Context,
) : WaVaultPrefs {
    private val prefs = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    init {
        // Round 7c removed the auto-lock delay: an earlier build's choice is dropped.
        if (prefs.contains(KEY_AUTO_LOCK_REMOVED)) prefs.edit().remove(KEY_AUTO_LOCK_REMOVED).apply()
    }

    override var failedDaily: Int
        get() = prefs.getInt(KEY_FAILED, 0)
        set(value) {
            prefs.edit().putInt(KEY_FAILED, value).apply()
        }

    private companion object {
        const val NAME = "rosty_wa_vault"
        const val KEY_FAILED = "failed_daily"
        const val KEY_AUTO_LOCK_REMOVED = "auto_lock_ms"
    }
}

/**
 * The WhatsApp vault of one app: setup, the silent, biometric and passphrase unlocks, the passphrase change, the
 * reset, and the lock. Rust holds every key ([ops]); [keystore] seals and opens the opaque blobs; the app tears its
 * WhatsApp down in [onLock] and brings it up in [onUnlocked]. One operation at a time.
 *
 * The vault stays unlocked (round 7c). Every unlock also seals a stay wrap under K_stay, a Keystore key with no
 * prompt that works only while the phone is unlocked: [unlockSilently] reopens the vault with it, also after a
 * reboot once the person has unlocked the phone. There is no auto-lock and no screen-off lock. The only time rule is
 * the use clock: [used] (an activity start) and every unlock move it on; after 90 days without a use Rust deletes the
 * stay and biometric wraps and the passphrase is needed ([PassphraseReason.Idle]). [lock] ("Lock now") deletes the
 * stay wrap; the biometric unlock then reopens it (and seals a new stay wrap).
 *
 * The biometric unlock (plan 13.2) is dropped after a biometric enrolment change (the Keystore invalidates K_daily),
 * after [MAX_FAILED_DAILY] failed attempts, and with the stay wrap after 90 days without a use; a screen-lock daily
 * key is dropped once a Class 3 biometric is enrolled. A reboot ends nothing any more.
 *
 * One prompt for the four apps (round 7a, [unlockFromSibling]): while a sibling Rosty app has its vault unlocked, it
 * hands its link over in memory ([siblingLink], `WaLinkProvider`) and this app opens its *own* vault with it (Rust's
 * link wrap), with no prompt; this app then seals its own stay wrap, so it needs no sibling after a reboot.
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
    /**
     * The link of a sibling Rosty app whose vault is unlocked now, or null (`WaLinkProvider`, memory only; never
     * logged). Asked only by [unlockFromSibling], on the locked page the person opened.
     */
    private val siblingLink: suspend () -> StoredLink? = { null },
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

    /** The stay wrap could not open because the phone was locked; it is kept and tried again at the next use. */
    @Volatile private var stayWaits = false

    /**
     * A link waiting for its vault ([adopt]): Rust holds it in the uncommitted session of this offer, in memory only.
     * Nothing about it is on disk until [confirm] commits the vault; a process death loses it (link again).
     */
    @Volatile private var pending: PassphraseOffer? = null

    val unlocked: Boolean get() = stateFlow.value == WaVaultState.Unlocked

    /** Reads the vault (and drops the wraps after 90 days without a use). */
    suspend fun refresh() = mutex.withLock { refreshLocked() }

    private suspend fun stateNow(): Outcome<VaultState> = ops.state(VaultStateRequest(nowMs = env.now()))

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
        st.expired?.let { reason = it.reason() }
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
                st.unlocked != null -> {
                    WaVaultState.Unlocked
                }

                st.vault != null && broken -> {
                    WaVaultState.Broken
                }

                st.vault != null -> {
                    WaVaultState.Locked(
                        biometric = st.daily != null,
                        reason = reason,
                        silent = st.stay != null && !stayWaits,
                    )
                }

                pending != null || st.legacyLink -> {
                    WaVaultState.NeedsSetup
                }

                else -> {
                    WaVaultState.NoLink
                }
            }
    }

    /** Keystore keys the vault no longer names (an old passphrase, a dropped daily or stay unlock) are deleted. */
    private fun tidyKeys(st: VaultState) {
        val keep = setOfNotNull(st.vault?.hwAlias, st.daily?.alias, st.stay?.alias)
        for (alias in keystore.aliases()) if (alias !in keep) keystore.delete(alias)
    }

    private suspend fun forgetDailyLocked(why: PassphraseReason?): VaultState? {
        reason = why
        prefs.failedDaily = 0
        ops.dailyForget()
        deleteKeys(VaultKeyKind.Daily)
        return refreshLocked()
    }

    private fun deleteKeys(kind: VaultKeyKind) {
        for (alias in keystore.aliases()) if (VaultAlias.kindOf(alias) == kind) keystore.delete(alias)
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

    /**
     * The silent unlock (round 7c): K_stay opens the stay wrap with no prompt and Rust opens the vault with it, after
     * checking the 90-day use clock. False when there is no stay wrap, the phone is locked (the wrap is kept and
     * tried again at the next use), the clock lapsed (the wraps are gone: the passphrase is needed) or the key is dead
     * (a removed screen lock, a Keystore that dropped it: the wrap is deleted and the biometric or the passphrase
     * takes over).
     */
    suspend fun unlockSilently(): Boolean = mutex.withLock { silentLocked() }

    private suspend fun silentLocked(): Boolean {
        val st = refreshLocked() ?: return false
        if (st.unlocked != null) return true
        val stay = st.stay ?: return false
        if (broken) return false
        if (keystore.device().locked) return waitForUnlockedPhone(st)
        // K_stay needs no prompt: the words are never shown.
        return when (val opened = keystore.open(stay.alias, stay.sealed, prompts.unlock)) {
            is VaultCrypto.Done -> {
                when (val unlocked = ops.unlockStay(VaultStayUnlockRequest(stayBlob = opened.b64, nowMs = env.now()))) {
                    is Outcome.Ok -> {
                        reason = null
                        stayWaits = false
                        becameUnlocked()
                        true
                    }

                    is Outcome.Err -> {
                        if (unlocked.error.vaultCode == EXPIRED) {
                            expiredLocked(unlocked.error)
                        } else {
                            forgetStayLocked()
                        }
                        false
                    }
                }
            }

            VaultCrypto.DeviceLocked -> {
                waitForUnlockedPhone(st)
            }

            else -> {
                forgetStayLocked()
                false
            }
        }
    }

    private fun waitForUnlockedPhone(st: VaultState): Boolean {
        stayWaits = true
        publish(st)
        return false
    }

    private suspend fun forgetStayLocked() {
        stayWaits = false
        ops.stayForget()
        deleteKeys(VaultKeyKind.Stay)
        refreshLocked()
    }

    /** Rust found 90 days without a use (or the clock set back) and deleted every wrap: the passphrase is next. */
    private suspend fun expiredLocked(error: RustError) {
        reason = idleReason(error)
        stayWaits = false
        prefs.failedDaily = 0
        deleteKeys(VaultKeyKind.Stay)
        deleteKeys(VaultKeyKind.Daily)
        refreshLocked()
    }

    /**
     * Seals the stay wrap of the unlock that just happened ([seal], or a fresh one for the open session), with no
     * prompt. Without a screen lock no stay wrap is made; a failure only means the next start asks for the biometric
     * or the passphrase.
     */
    private suspend fun offerStay(
        handle: Long,
        seal: String?,
    ) {
        if (!keystore.device().secureLockScreen) return
        val blob =
            seal ?: when (val prepared = ops.stayPrepare(VaultTouchRequest(env.now()))) {
                is Outcome.Ok -> prepared.value.staySeal
                is Outcome.Err -> null
            } ?: return
        deleteKeys(VaultKeyKind.Stay)
        val alias = keystore.newAlias(VaultKeyKind.Stay)
        val sealed = keystore.seal(alias, blob, prompts.unlock)
        val stored =
            sealed is VaultCrypto.Done &&
                ops.stayStore(VaultDailyStoreRequest(handle, alias, sealed.b64)) is Outcome.Ok
        if (!stored) keystore.delete(alias)
    }

    /**
     * The app is in use (round 7c: an activity started). An open vault moves its use clock on (Rust writes it at most
     * once an hour; after 90 days without a use it refuses and the vault locks); a locked one with its stay wrap
     * reopens with no prompt.
     */
    suspend fun used() {
        if (!unlocked) {
            unlockSilently()
            return
        }
        val touched = ops.touch(VaultTouchRequest(env.now()))
        if (touched is Outcome.Err && touched.error.vaultCode == EXPIRED) {
            lock()
            mutex.withLock { expiredLocked(touched.error) }
        }
    }

    /** The biometric unlock: the biometric opens K_daily, Rust opens the vault with what it decrypts. */
    suspend fun unlockWithBiometric(): VaultOutcome =
        mutex.withLock {
            val st = refreshLocked() ?: return@withLock refused(VaultProblem.Failed)
            if (st.unlocked != null) return@withLock VaultOutcome.Done
            val daily = st.daily ?: return@withLock refused(VaultProblem.NeedsPassphrase)
            val opened =
                prompt { keystore.open(daily.alias, daily.sealed, prompts.daily) { prefs.failedDaily += 1 } }
            when (opened) {
                is VaultCrypto.Done -> {
                    val unlocked = ops.unlockDaily(VaultDailyUnlockRequest(dailyBlob = opened.b64, nowMs = env.now()))
                    when (unlocked) {
                        is Outcome.Ok -> {
                            offerStay(unlocked.value.handle, unlocked.value.staySeal)
                            becameUnlocked()
                            VaultOutcome.Done
                        }

                        is Outcome.Err -> {
                            if (unlocked.error.vaultCode == EXPIRED) {
                                expiredLocked(unlocked.error)
                            } else {
                                forgetDailyLocked(null)
                            }
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

                is VaultCrypto.Failed, VaultCrypto.DeviceLocked -> {
                    afterFailures()
                    refused(VaultProblem.Failed)
                }
            }
        }

    /**
     * The hand-over unlock (round 7a): a sibling Rosty app whose vault is unlocked hands its link over and Rust opens
     * this app's own vault with it. No prompt. False when no sibling gives a link, when the link is not this vault's,
     * or when this vault has no link wrap yet (it was not unlocked since the update): the caller then prompts. This
     * app then seals its own stay wrap (round 7c), so it reopens by itself next time.
     */
    suspend fun unlockFromSibling(askBudgetMs: Long? = null): Boolean {
        val st = mutex.withLock { refreshLocked() } ?: return false
        if (st.unlocked != null) return true
        if (st.vault == null || broken) return false
        // Asked outside the lock: a sibling's provider may take a moment, and nothing here waits on it.
        // Only the asking is bounded: the unlock itself, once a link is in hand, always completes.
        val link =
            (if (askBudgetMs == null) siblingLink() else withTimeoutOrNull(askBudgetMs) { siblingLink() })
                ?: return false
        return mutex.withLock {
            if (stateFlow.value == WaVaultState.Unlocked) return@withLock true
            val request =
                VaultLinkUnlockRequest(
                    link = VaultLink(link.gatewayUrl, link.instanceId, link.key),
                    nowMs = env.now(),
                )
            when (val unlocked = ops.unlockLink(request)) {
                is Outcome.Err -> {
                    false
                }

                is Outcome.Ok -> {
                    reason = null
                    offerStay(unlocked.value.handle, unlocked.value.staySeal)
                    becameUnlocked()
                    true
                }
            }
        }
    }

    /**
     * Every unlock with no prompt, in order: the app's own stay wrap (round 7c), then an unlocked sibling (round 7a).
     * [siblingBudgetMs] bounds the wait for the siblings.
     */
    suspend fun unlockWithoutPrompt(siblingBudgetMs: Long? = null): Boolean =
        unlockSilently() || unlockFromSibling(siblingBudgetMs)

    /**
     * The unlock without a page (round 7a's Settings row, round 7b's locked call): the stay wrap or an unlocked sibling
     * opens this vault with no prompt, else the biometric prompt opens over whatever the app shows. [siblingBudgetMs]
     * bounds the wait for the siblings (a cold sibling process takes a moment to say no; a ringing call should not wait
     * for it), null for no bound. [InlineUnlock.NeedsPage] when only the passphrase page can open it.
     */
    suspend fun unlockInline(siblingBudgetMs: Long? = null): InlineUnlock {
        if (unlockWithoutPrompt(siblingBudgetMs) || unlocked) return InlineUnlock.Opened
        val state = stateFlow.value as? WaVaultState.Locked ?: return InlineUnlock.NeedsPage
        if (!state.biometric) return InlineUnlock.NeedsPage
        return when (unlockWithBiometric()) {
            VaultOutcome.Done -> InlineUnlock.Opened
            VaultOutcome.Cancelled -> InlineUnlock.Cancelled
            is VaultOutcome.Refused -> InlineUnlock.NeedsPage
        }
    }

    /** Five wrong fingers end the daily unlock even when the prompt is then cancelled. */
    private suspend fun afterFailures() {
        if (prefs.failedDaily >= MAX_FAILED_DAILY) forgetDailyLocked(PassphraseReason.TooManyAttempts)
    }

    /**
     * The passphrase unlock: K_hw (biometric or screen lock) decrypts the key blob, Rust unwraps it with [passphrase].
     * Each try needs its own hardware authentication. Then the stay wrap is sealed (no prompt) and the biometric
     * unlock is offered (a second prompt).
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
                    stayWaits = false
                    offerStay(unlocked.value.handle, unlocked.value.staySeal)
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

            VaultCrypto.DeviceLocked -> {
                refused(VaultProblem.Failed)
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
        val seal = unlocked.dailySeal ?: return
        deleteKeys(VaultKeyKind.Daily)
        if (!keystore.device().secureLockScreen) return
        val alias = keystore.newAlias(VaultKeyKind.Daily)
        val sealed = prompt { keystore.seal(alias, seal, prompts.enableDaily) }
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

    /**
     * A new vault for the link waiting for one ([adopt]), else for the plaintext link of an earlier build: the
     * passphrase to show. Nothing is on disk until [confirm].
     */
    suspend fun startSetup(): Outcome<PassphraseOffer> =
        mutex.withLock {
            pending?.let { return@withLock Outcome.Ok(it) }
            when (val created = ops.create()) {
                is Outcome.Err -> created
                is Outcome.Ok -> Outcome.Ok(created.value.let { offerOf(it.handle, it.passphrase, it.seal) })
            }
        }

    /**
     * Takes [link] (pasted, made by the link flow, or handed over by a sibling Rosty app) for a new vault: Rust
     * checks it and keeps it in an uncommitted session, the state turns [WaVaultState.NeedsSetup] and the setup
     * screens follow. The link is never written in plaintext (`whatsapp-link.json` stays untouched); only the
     * committed vault holds it. Refused while a vault exists (reset it first).
     */
    suspend fun adopt(link: StoredLink): Outcome<Unit> =
        mutex.withLock {
            val request = VaultCreateRequest(link = VaultLink(link.gatewayUrl, link.instanceId, link.key))
            when (val created = ops.create(request)) {
                is Outcome.Err -> {
                    created
                }

                is Outcome.Ok -> {
                    pending?.let { old -> ops.lock(VaultLockRequest(handle = old.handle)) }
                    pending = created.value.let { offerOf(it.handle, it.passphrase, it.seal) }
                    refreshLocked()
                    Outcome.Ok(Unit)
                }
            }
        }

    /**
     * The link of the open vault, for the hand-over to a sibling Rosty app that is setting up its own vault
     * (`WaLinkProvider`). Null unless this process has the vault unlocked. In memory only: never log or store it.
     */
    suspend fun exportLink(): StoredLink? =
        mutex.withLock {
            if (stateFlow.value != WaVaultState.Unlocked) return@withLock null
            val handle = (stateNow() as? Outcome.Ok)?.value?.unlocked?.handle ?: return@withLock null
            when (val out = ops.exportLink(VaultHandleRequest(handle))) {
                is Outcome.Ok -> out.value.let { StoredLink(it.gatewayUrl, it.instanceId, it.key) }
                is Outcome.Err -> null
            }
        }

    /**
     * A Stops trip with an arriving-soon message starts (plan 13.3): Rust copies the link of the open vault into the
     * trip's RAM, where it stays until the trip ends ([releaseTrip], or Rust's own end of the trip). False while
     * locked: the trip then carries no message.
     */
    suspend fun holdForTrip(): Boolean =
        mutex.withLock {
            val handle = refreshLocked()?.unlocked?.handle ?: return@withLock false
            (ops.tripHold(VaultHandleRequest(handle)) as? Outcome.Ok)?.value?.held == true
        }

    /** The trip ended: its copy of the link goes. */
    suspend fun releaseTrip() {
        ops.tripRelease()
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
            if (pending === offer) pending = null
            before
                ?.vault
                ?.hwAlias
                ?.takeIf { it != alias }
                ?.let(keystore::delete)
            // The daily and stay blobs sealed the old data key: they are gone with the commit.
            deleteKeys(VaultKeyKind.Daily)
            deleteKeys(VaultKeyKind.Stay)
            prefs.failedDaily = 0
            if (change) {
                // The open session seals a new stay wrap (no prompt), so the vault keeps reopening by itself; the
                // biometric unlock waits for the next passphrase unlock.
                offerStay(offer.handle, seal = null)
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
            if (unlocked is Outcome.Ok) {
                offerStay(unlocked.value.handle, unlocked.value.staySeal)
                offerDaily(unlocked.value)
            }
            reason = null
            becameUnlocked()
            VaultOutcome.Done
        }

    /**
     * A setup the person left: the pending session is released (a change keeps its open session). A link that was
     * waiting for its vault ([adopt]) goes with it: the person links again.
     */
    suspend fun abandon(offer: PassphraseOffer) {
        mutex.withLock {
            val open = (stateNow() as? Outcome.Ok)?.value?.unlocked?.handle
            if (open != offer.handle) ops.lock(VaultLockRequest(handle = offer.handle))
            if (pending === offer) {
                pending = null
                refreshLocked()
            }
        }
    }

    // ---- lock and reset

    /**
     * Locks WhatsApp now ("Lock now" here or in a sibling, or 90 days without a use): the state turns at once, then
     * the app tears down, Rust zeroizes the keys and the stay wrap goes, so the vault does not reopen by itself. The
     * biometric unlock stays (it reopens the vault and seals a new stay wrap).
     */
    suspend fun lock() {
        val was = stateFlow.value
        if (was == WaVaultState.Unlocked) stateFlow.value = WaVaultState.Locked(biometric = true)
        onLock()
        mutex.withLock {
            ops.lock()
            stayWaits = false
            ops.stayForget()
            deleteKeys(VaultKeyKind.Stay)
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
            pending?.let { ops.lock(VaultLockRequest(handle = it.handle)) }
            pending = null
            val done = ops.reset() is Outcome.Ok
            for (alias in keystore.aliases()) keystore.delete(alias)
            prefs.failedDaily = 0
            reason = null
            broken = false
            stayWaits = false
            onReset()
            refreshLocked()
            if (done) VaultOutcome.Done else refused(VaultProblem.Failed)
        }
    }

    private fun refused(problem: VaultProblem) = VaultOutcome.Refused(problem)

    companion object {
        const val MAX_FAILED_DAILY = 5

        private const val DAMAGED = "damaged"
        private const val EXPIRED = "expired"

        private fun VaultIdleRefusal.reason(): PassphraseReason =
            when (this) {
                VaultIdleRefusal.Idle -> PassphraseReason.Idle
                VaultIdleRefusal.ClockBack -> PassphraseReason.ClockBack
            }

        /** The reason of an `expired` refusal (`detail.reason`). */
        private fun idleReason(error: RustError): PassphraseReason {
            val why = ((error.detail as? JsonObject)?.get("reason") as? JsonPrimitive)?.content
            return if (why == "clock_back") PassphraseReason.ClockBack else PassphraseReason.Idle
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
