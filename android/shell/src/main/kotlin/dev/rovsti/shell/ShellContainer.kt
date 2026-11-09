package dev.rovsti.shell

import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.view.View
import coil3.ImageLoader
import dev.rovsti.core.Rust
import dev.rovsti.core.RustCore
import dev.rovsti.core.ops.NativeWaOps
import dev.rovsti.core.ops.WaOps
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.HapticMode
import dev.rovsti.design.haptics.PlatformHaptics
import dev.rovsti.design.haptics.RovstiHaptics
import dev.rovsti.design.media.rovstiImageLoader
import dev.rovsti.platform.files.AndroidFilesRepository
import dev.rovsti.platform.files.ClipboardRepository
import dev.rovsti.platform.files.FilesRepository
import dev.rovsti.platform.files.SystemClipboardRepository
import dev.rovsti.platform.system.permissions.AndroidSpecialAccess
import dev.rovsti.platform.system.permissions.AppPermissions
import dev.rovsti.platform.system.permissions.PermissionController
import dev.rovsti.platform.system.permissions.SpecialAccess
import dev.rovsti.wa.DefaultWaRepository
import dev.rovsti.wa.WaLinkStore
import dev.rovsti.wa.WaRepository
import dev.rovsti.wa.avatars.WaAvatar
import dev.rovsti.wa.avatars.WaAvatars
import dev.rovsti.wa.avatars.waAvatars
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    val wa: WaRepository by lazy { createWa() }

    /** Profile pictures of WhatsApp contacts; started by the screens that show them. */
    val avatars: WaAvatars by lazy { createAvatars() }

    /** The app's one Coil loader, with the [WaAvatar] fetcher. */
    private val imageLoaderLazy = lazy { createImageLoader() }
    val imageLoader: ImageLoader get() = imageLoaderLazy.value

    val specialAccess: SpecialAccess by lazy { createSpecialAccess() }

    val permissions: PermissionController by lazy { PermissionController(context, appPermissions, specialAccess) }

    val clipboard: ClipboardRepository by lazy { SystemClipboardRepository(context) }

    val files: FilesRepository by lazy { AndroidFilesRepository(context, Dispatchers.IO) }

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

    protected open fun createWa(): WaRepository =
        DefaultWaRepository(WaLinkStore(waOps), waOps, scope, context.cacheDir.absolutePath).also { it.start() }

    protected open fun createAvatars(): WaAvatars =
        WaAvatars(
            repo = wa,
            ops = waOps,
            dir = File(context.cacheDir, "wa-avatars"),
            scope = scope,
            invalidate = ::dropCachedAvatar,
        )

    protected open fun createImageLoader(): ImageLoader =
        rovstiImageLoader(context, lowRam = isLowRam()) { waAvatars(avatars) }

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
) : RovstiHaptics {
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
