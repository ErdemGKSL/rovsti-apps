package dev.rosty.shell

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.view.View
import coil3.ImageLoader
import dev.rosty.core.Outcome
import dev.rosty.core.Rust
import dev.rosty.core.RustCore
import dev.rosty.core.log.DevLog
import dev.rosty.core.log.RostyLog
import dev.rosty.core.model.wa.PushKey
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.core.model.wa.VaultWipeRequest
import dev.rosty.core.ops.NativeVaultOps
import dev.rosty.core.ops.NativeWaOps
import dev.rosty.core.ops.VaultOps
import dev.rosty.core.ops.WaGateway
import dev.rosty.core.ops.WaOps
import dev.rosty.design.haptics.HapticEvent
import dev.rosty.design.haptics.HapticMode
import dev.rosty.design.haptics.PlatformHaptics
import dev.rosty.design.haptics.RostyHaptics
import dev.rosty.design.media.rostyImageLoader
import dev.rosty.platform.files.AndroidFilesRepository
import dev.rosty.platform.files.ClipboardRepository
import dev.rosty.platform.files.FilesRepository
import dev.rosty.platform.files.SystemClipboardRepository
import dev.rosty.platform.files.copySecret
import dev.rosty.platform.system.permissions.AndroidSpecialAccess
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.platform.system.permissions.PermissionController
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.platform.vault.AndroidVaultKeystore
import dev.rosty.platform.vault.BiometricVaultAuthenticator
import dev.rosty.platform.vault.VaultKeystore
import dev.rosty.platform.vault.VaultPromptHost
import dev.rosty.platform.wa.WaPush
import dev.rosty.platform.wa.push.PushKeys
import dev.rosty.shell.settings.CommonSettings
import dev.rosty.shell.settings.NoCommonSettings
import dev.rosty.wa.DefaultWaRepository
import dev.rosty.wa.WaGatewayOpener
import dev.rosty.wa.WaLinkSource
import dev.rosty.wa.WaLinkStore
import dev.rosty.wa.WaRepository
import dev.rosty.wa.avatars.VaultAvatarPictures
import dev.rosty.wa.avatars.WaAvatar
import dev.rosty.wa.avatars.WaAvatars
import dev.rosty.wa.avatars.waAvatars
import dev.rosty.wa.share.HeldLink
import dev.rosty.wa.share.PrefsWaLinkMarks
import dev.rosty.wa.share.ResolverSiblingLinks
import dev.rosty.wa.share.SiblingAnswer
import dev.rosty.wa.share.SiblingLinks
import dev.rosty.wa.share.WaLinkHandover
import dev.rosty.wa.share.WaLinkShare
import dev.rosty.wa.share.WaLockAll
import dev.rosty.wa.vault.SharedWaMigrationMark
import dev.rosty.wa.vault.SharedWaVaultPrefs
import dev.rosty.wa.vault.VaultFlow
import dev.rosty.wa.vault.WaLegacyLeftovers
import dev.rosty.wa.vault.WaMigrationMark
import dev.rosty.wa.vault.WaMigrationResult
import dev.rosty.wa.vault.WaPlaintextMigration
import dev.rosty.wa.vault.WaVault
import dev.rosty.wa.vault.WaVaultEnv
import dev.rosty.wa.vault.WaVaultState
import dev.rosty.wa.vault.vaultPrompts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What every app shares (ARCH 5.3): the Rust core, haptics, the image loader, the WhatsApp repository, permissions,
 * special access, the clipboard and the files. An app subclasses it, adds its own repositories and hands it out from
 * `Application` through [HasContainer].
 *
 * Every member is lazy, so nothing heavy runs before the first frame and a cold path (a receiver, a service) pays only
 * for what it touches. The `create*` functions are the seams: they build the real thing, and tests override them with
 * fakes (see `ShellContainerTest`).
 */
abstract class ShellContainer(
    val context: Context,
    /** What this app declares in its manifest (ARCH 7.5); decides which groups [permissions] watches. */
    val appPermissions: AppPermissions,
) {
    /** Lives as long as the process: the repository collectors and the avatar worker run in it. */
    val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    /** The typed core. The first read starts [Rust.init] off the main thread (data under `noBackupFilesDir`). */
    val rust: RustCore by lazy { createRust() }

    /** Haptics for every window of the app; the activity attaches its decor view (see [ShellHaptics]). */
    val haptics: ShellHaptics by lazy { ShellHaptics(context) }

    val waOps: WaOps by lazy { NativeWaOps(rust) }

    /** The one WhatsApp repository of the process, already started. */
    val wa: WaRepository get() = waOnce.value

    private val waOnce = lazy { createWa() }

    /**
     * The link hand-over between Rosty apps signed with the same key (WA-R2, `plans/wa-vault.md` 13.3): this app,
     * while it has no link, takes the link of a sibling whose vault is unlocked when the person asks on the link
     * screen; the link goes into this app's vault setup, in memory only.
     */
    val waLinkHandover: WaLinkHandover by lazy { createWaLinkHandover() }

    /** Profile pictures of WhatsApp contacts; started by the screens that show them. */
    val avatars: WaAvatars get() = avatarsOnce.value

    private val avatarsOnce = lazy { createAvatars() }

    /** The app's one Coil loader, with the [WaAvatar] fetcher. */
    private val imageLoaderLazy = lazy { createImageLoader() }
    val imageLoader: ImageLoader get() = imageLoaderLazy.value

    val specialAccess: SpecialAccess by lazy { createSpecialAccess() }

    /**
     * The appearance and haptics preferences of the app. The activity follows them: the theme and the haptics mode
     * change as soon as a flow does. An app without the preferences keeps this default (both flows hold null).
     */
    open val commonSettings: CommonSettings by lazy { NoCommonSettings }

    val permissions: PermissionController by lazy { PermissionController(context, appPermissions, specialAccess) }

    val clipboard: ClipboardRepository by lazy { SystemClipboardRepository(context) }

    val files: FilesRepository by lazy { AndroidFilesRepository(context, Dispatchers.IO) }

    /** The window the WhatsApp vault's system prompts open over; the activity attaches itself (plan 13.2). */
    val vaultPromptHost: VaultPromptHost by lazy { VaultPromptHost() }

    /** The Rust half of this app's WhatsApp vault (`plans/wa-vault.md`): every key lives there. */
    val vaultOps: VaultOps by lazy { createVaultOps() }

    protected open fun createVaultOps(): VaultOps = NativeVaultOps(rust)

    /** The Keystore half: K_hw and K_daily of this app, prompted over the activity ([vaultPromptHost]). */
    val vaultKeystore: VaultKeystore by lazy { createVaultKeystore() }

    protected open fun createVaultKeystore(): VaultKeystore =
        AndroidVaultKeystore(context, BiometricVaultAuthenticator(vaultPromptHost))

    private val waVaultOnce = lazy { createWaVault().also { vault -> scope.launch { vault.refresh() } } }

    /**
     * The WhatsApp vault of this app (`plans/wa-vault.md` 3-6, 13): each Rosty app has its own vault and Keystore
     * keys, with the shared setup, unlock, change and forgot screens. Its state decides what the WhatsApp screens show
     * and whether anything may reach the gateway; it reads the vault on first use.
     */
    val waVault: WaVault get() = waVaultOnce.value

    /** [waVault] when something already made it: a push or a provider in a cold process must not start the core. */
    val waVaultIfMade: WaVault? get() = if (waVaultOnce.isInitialized()) waVault else null

    protected open fun createWaVault(): WaVault =
        WaVault(
            ops = vaultOps,
            keystore = vaultKeystore,
            prefs = SharedWaVaultPrefs(context),
            env =
                WaVaultEnv(
                    bootCount = { AndroidVaultKeystore.bootCount(context) ?: 0L },
                    now = System::currentTimeMillis,
                ),
            prompts = vaultPrompts(context.resources),
            onLock = { lockWhatsApp() },
            onUnlocked = {
                unlockedWhatsApp()
                // After the app's own unlock work (Phone moves `wa-identity.json` into the vault cache there); off the
                // vault's lock, since erasing an old store in place can take a moment.
                migratePlaintextSoon()
            },
            onReset = { forgetWhatsApp() },
            siblingLink = ::unlockedSiblingLink,
        )

    /**
     * The link of the first sibling Rosty app whose vault is unlocked now (round 7a, the one prompt for the four
     * apps): its `WaLinkProvider` answers only then, in memory; this app opens its own vault with it
     * (`vault.unlock_link`). Asked only from the locked page the person opened. Never logged.
     */
    private suspend fun unlockedSiblingLink(): StoredLink? {
        for (sibling in WaLinkShare.siblingsOf(context.packageName)) {
            val link = (siblingLinks.ask(sibling) as? SiblingAnswer.Link)?.link ?: continue
            if (WaLinkShare.usable(link)) return link
        }
        return null
    }

    /** How this app asks its siblings (their `WaLinkProvider`). */
    private val siblingLinks: SiblingLinks by lazy { createSiblingLinks() }

    /**
     * The upgrade migration (`plans/wa-vault.md` 8, round 6): the plaintext WhatsApp data of an earlier build goes, once
     * per app, at the first start that allows it (see [WaPlaintextMigration]).
     */
    val waMigration: WaPlaintextMigration by lazy { createWaMigration() }

    protected open fun createWaMigration(): WaPlaintextMigration =
        WaPlaintextMigration(
            ops = vaultOps,
            request = { VaultWipeRequest(filesDir = context.filesDir.absolutePath) },
            mark = waMigrationMark,
            leftovers = {
                val common = withContext(Dispatchers.IO) { WaLegacyLeftovers.drop(context) }
                dropPlaintextLeftovers() && common
            },
            movesAtUnlock = { File(context.noBackupFilesDir, WaPlaintextMigration.IDENTITY_FILE).exists() },
        )

    /**
     * What this app kept of WhatsApp in plaintext outside the files Rust deletes, dropped by the migration before the
     * wipe; false when something could not be dropped (the migration then runs again at the next start). Messages
     * drops the `wa:` drafts and mutes it holds in memory and in `messages-prefs.json`.
     */
    protected open suspend fun dropPlaintextLeftovers(): Boolean = true

    /** Whether the migration finished; read before anything else, so a finished one never starts the core for it. */
    private val waMigrationMark: WaMigrationMark by lazy { createWaMigrationMark() }

    protected open fun createWaMigrationMark(): WaMigrationMark = SharedWaMigrationMark(context)

    /** Runs the migration when it is due and the vault's state allows it; one prefs read once it is done. */
    suspend fun migratePlaintext(): WaMigrationResult {
        if (waMigrationMark.done) return WaMigrationResult.NotDue
        waVault.refresh()
        return waMigration.run(waVault.state.value)
    }

    /** The activity started: the migration runs off the main thread when it is still due. */
    fun migratePlaintextSoon() {
        scope.launch {
            try {
                migratePlaintext()
            } catch (e: RuntimeException) {
                // Never take the app down for it: the next start tries again.
                RostyLog.w(WaPlaintextMigration.TAG, "plaintext migration failed", e)
            }
        }
    }

    /** What the vault screens show and do; one per process, so a commit survives the page that started it. */
    val vaultFlow: VaultFlow by lazy { VaultFlow(waVault, scope) { secret -> clipboard.copySecret(scope, secret) } }

    /**
     * What the activities tell the vault (round 7c: it stays unlocked): each start is a use (the 90-day clock moves
     * on, or a locked vault reopens with its stay wrap); the last stop with WhatsApp open is the background. No
     * auto-lock, no screen-off lock.
     */
    open val vaultUse: VaultUseLifecycle by lazy {
        VaultUseLifecycle(
            scope = scope,
            hasVault = { File(context.noBackupFilesDir, WaLockAll.VAULT_FILE).exists() },
            unlocked = { waVaultIfMade?.unlocked == true },
            prompting = { waVaultIfMade?.prompting?.value == true },
            use = { waVault.used() },
            background = { wentToBackground() },
        )
    }

    /**
     * "Lock now" (round 7a, kept in 7c): locks WhatsApp here and in every sibling Rosty app, and each one deletes its
     * stay wrap, so none reopens by itself or opens another by a hand-over right after. A sibling whose process is not
     * running is reached too: its manifest receiver deletes the stay wrap without starting the core
     * ([lockWhatsAppForSibling]).
     */
    fun lockWhatsAppEverywhere() {
        lockWhatsAppForSibling()
        WaLockAll.send(context)
    }

    /**
     * A sibling's "Lock now" reached this app ([WaLockAll], round 7c): an open vault in this process locks (and drops
     * its stay wrap); otherwise only the stay wrap goes, file and Keystore key, without starting the core.
     */
    fun lockWhatsAppForSibling() {
        // A cold process: delete at once (a receiver's process may go right after); a running one tears down.
        val vault = waVaultIfMade ?: return forgetStayUnlock()
        scope.launch { vault.lock() }
    }

    /** The stay wrap of this app, file and K_stay, with no core: the next unlock needs the biometric or a passphrase. */
    private fun forgetStayUnlock() {
        WaLockAll.forgetStay(context.noBackupFilesDir, vaultKeystore)
    }

    /**
     * Locks WhatsApp (`plans/wa-vault.md` 6): the one teardown path of the app, called by [waVault] ("Lock now" here or
     * in a sibling, 90 days without a use, a reset). Nothing reaches the gateway after it starts. The base closes the
     * repository (its gateway and event socket), forgets the profile pictures in memory and zeroizes the keys
     * (`vault.lock`); an app adds what it keeps of WhatsApp in memory.
     */
    open suspend fun lockWhatsApp() {
        if (waOnce.isInitialized()) wa.lock()
        if (avatarsOnce.isInitialized()) avatars.pictures?.clear()
        vaultOps.lock()
    }

    /** The app went to the background with WhatsApp unlocked (it stays unlocked, round 7c). */
    protected open suspend fun wentToBackground() {}

    /** The vault was opened: the repository may reach the gateway again (through the vault's key). */
    protected open suspend fun unlockedWhatsApp() {
        wa.unlock()
        scope.launch { wa.gateway() }
    }

    /**
     * "Forgot passphrase" or a new link: the link and everything kept for it go, as an unlink does (plan 5). The push
     * key is rotated too (round 7b): a push sealed for the old link no longer opens, and the next link registers a
     * new key.
     */
    protected open suspend fun forgetWhatsApp() {
        wa.forget()
        waLinkHandover.onForget()
        rotatePushKey()
    }

    /** Drops this install's push key (`AndroidPushKeyStore`); a test container keeps the Keystore out. */
    protected open fun rotatePushKey() {
        if (WaPushRegistrar.appOf(context.packageName) != null) PushKeys.store.rotate()
    }

    /** The public half of this install's push key for the registration; a test container gives none. */
    protected open fun pushKey(): PushKey? = PushKeys.store.ensure()

    /**
     * Where this app reads its WhatsApp link: the vault's URL and instance with an empty key while it is unlocked
     * (`wa.open` then takes the key from the vault), nothing otherwise. No Kotlin path reads `whatsapp-link.json` any
     * more: a plaintext link of an earlier build is read by Rust alone, for the vault's setup (`vault.create`).
     */
    val waLinkSource: WaLinkSource by lazy {
        WaLinkSource {
            when (val read = vaultOps.state()) {
                is Outcome.Err -> read
                is Outcome.Ok -> Outcome.Ok(read.value.unlocked?.let { StoredLink(it.gatewayUrl, it.instanceId, "") })
            }
        }
    }

    /**
     * Developer mode and the log behind the Logs page. Started off the main thread when the container is created, so
     * a background start (the people-sync worker) writes the log file too while developer mode is on.
     */
    val devLog: DevLog by lazy { createDevLog() }

    init {
        if (startsDevLogAtCreate()) scope.launch { devLog.start() }
    }

    protected open fun createDevLog(): DevLog = DevLog.create(context, { rust }, scope)

    /**
     * Whether [devLog] starts on a pool thread as soon as the container is built (true for the apps). A JVM test's
     * container returns false: that start could run after the test has torn the application down. Called from the
     * constructor, so an override must not read the subclass's own fields.
     */
    protected open fun startsDevLogAtCreate(): Boolean = true

    protected open fun createRust(): RustCore {
        Rust.init(
            dataDir = context.noBackupFilesDir,
            cacheDir = context.cacheDir,
            appId = context.packageName,
            sdk = Build.VERSION.SDK_INT,
            scope = scope,
            debug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
        return Rust.core()
    }

    /**
     * The repository [createWa] starts. It reads its link from the vault ([waLinkSource]), starts locked and follows
     * [waVault]: open for an open vault, closed otherwise. No request reaches the gateway before the unlock.
     */
    protected open fun newWaRepository(): WaRepository {
        val cacheDir = context.cacheDir.absolutePath
        return DefaultWaRepository(
            store = WaLinkStore(waOps),
            scope = scope,
            opener =
                WaGatewayOpener { link ->
                    WaGateway.open(link.gatewayUrl, link.key.ifEmpty { null }, cacheDir, waOps)
                },
            source = waLinkSource,
            startLocked = true,
        ).also { repo -> scope.launch { followVault(repo) } }
    }

    /** Opens and closes [repo] with the vault. */
    private suspend fun followVault(repo: WaRepository) {
        waVault.state.collect { state ->
            when (state) {
                WaVaultState.Unlocked -> {
                    repo.unlock()
                    repo.gateway()
                }

                WaVaultState.NoLink -> {
                    // The link flow: nothing to open until a link waits for its vault (no plaintext link is written).
                    repo.unlock()
                    repo.gateway()
                }

                WaVaultState.Checking -> {
                    Unit
                }

                else -> {
                    repo.lock()
                }
            }
        }
    }

    protected open fun createWa(): WaRepository =
        newWaRepository().also { repo ->
            repo.start()
            // Where the gateway wakes this app: its FCM token, once the link is up (nothing in the plain flavor).
            waPush =
                WaPushRegistrar.appOf(context.packageName)?.let { app ->
                    WaPushRegistrar(
                        wa = repo,
                        app = app,
                        scope = scope,
                        token = WaPush::token,
                        pushKey = ::pushKey,
                    ).also { it.start() }
                }
        }

    /**
     * Registers this app's FCM token with the gateway whenever the WhatsApp link comes up; set when [wa] is created,
     * null before that and for a package the gateway has no app id for. The firebase flavor's messaging service calls
     * [WaPushRegistrar.refresh] on a new token.
     */
    @Volatile var waPush: WaPushRegistrar? = null
        private set

    protected open fun createWaLinkHandover(): WaLinkHandover =
        WaLinkHandover(
            held = ::heldLink,
            siblings = WaLinkShare.siblingsOf(context.packageName),
            source = siblingLinks,
            marks = PrefsWaLinkMarks(context),
            adopt = { link -> waVault.adopt(link) },
        )

    /** How this app asks its siblings (their `WaLinkProvider`); tests answer for them. */
    protected open fun createSiblingLinks(): SiblingLinks = ResolverSiblingLinks(context)

    /** What this app holds, for the hand-over: no link, a link or vault (its instance while open), or unreadable. */
    private suspend fun heldLink(): HeldLink {
        waVault.refresh()
        return when (waVault.state.value) {
            WaVaultState.NoLink -> {
                HeldLink.None
            }

            WaVaultState.Checking -> {
                HeldLink.Unreadable
            }

            WaVaultState.Unlocked -> {
                HeldLink.Held((vaultOps.state() as? Outcome.Ok)?.value?.unlocked?.instanceId)
            }

            else -> {
                HeldLink.Held(null)
            }
        }
    }

    /**
     * The link `WaLinkProvider` hands a sibling app that is setting up its own vault: the link of this app's vault
     * while this process has it unlocked (`vault.export_link`, memory only), else null. A locked vault, a cold
     * process (the provider started it: nothing is unlocked there) and an app without a vault answer nothing, and no
     * link file is read.
     */
    open suspend fun sharedWaLink(): StoredLink? = waVaultIfMade?.exportLink()

    /** Whether [sharedWaLink] would answer now, without reading the link. */
    open fun waLinkOffered(): Boolean = waVaultIfMade?.unlocked == true

    /** Profile pictures live in memory and in the DK-encrypted vault cache only (`plans/wa-vault.md` 13.5). */
    protected open fun createAvatars(): WaAvatars =
        WaAvatars(
            repo = wa,
            ops = waOps,
            dir = File(context.cacheDir, WaAvatars.DIR_NAME),
            scope = scope,
            invalidate = ::dropCachedAvatar,
            pictures = VaultAvatarPictures(vaultOps),
        )

    protected open fun createImageLoader(): ImageLoader =
        rostyImageLoader(context, lowRam = isLowRam()) { waAvatars(avatars) }

    protected open fun createSpecialAccess(): SpecialAccess = AndroidSpecialAccess(context)

    private fun isLowRam(): Boolean =
        context.getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice == true

    /** The picture of [jid] changed on disk: Coil must not serve the old bitmap from memory. */
    private fun dropCachedAvatar(jid: String) {
        val cache = imageLoader.memoryCache ?: return
        val key = WaAvatar(jid).key
        for (entry in cache.keys.filter { it.key == key }) cache.remove(entry)
    }

    /**
     * `onTrimMemory(level)`: give bitmaps back (half of the cache from RUNNING_LOW, all of it from MODERATE). Does
     * nothing when the loader was never built, so a trim never wakes it.
     */
    fun trimMemory(level: Int) {
        if (!imageLoaderLazy.isInitialized()) return
        val cache = imageLoader.memoryCache ?: return
        when {
            level >= TRIM_MODERATE -> cache.clear()
            level >= TRIM_RUNNING_LOW -> cache.trimToSize(cache.size / 2)
        }
    }

    private companion object {
        // ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW and TRIM_MEMORY_MODERATE (deprecated names, stable values).
        const val TRIM_RUNNING_LOW = 10
        const val TRIM_MODERATE = 60
    }
}

/**
 * Haptics of the whole app. [mode] is read on every event (the preference changes without recreation); [attach] gives
 * it the window to vibrate through, which the activity does in `onCreate`. Before that, and after [detach], it plays
 * nothing.
 */
class ShellHaptics(
    private val context: Context,
) : RostyHaptics {
    @Volatile var mode: HapticMode = HapticMode.System

    @Volatile private var inner: PlatformHaptics? = null

    fun attach(view: View) {
        inner = PlatformHaptics(view, context.getSystemService(Vibrator::class.java)) { mode }
    }

    fun detach() {
        inner = null
    }

    override fun play(event: HapticEvent) {
        inner?.play(event)
    }
}
