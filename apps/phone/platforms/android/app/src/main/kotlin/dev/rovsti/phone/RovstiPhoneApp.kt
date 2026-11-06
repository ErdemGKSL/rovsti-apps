package dev.rovsti.phone

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import dev.rovsti.bridge.ContactPhotos
import dev.rovsti.bridge.RovstiBridge

/**
 * Initialises Kotlin-only pieces. The native library is loaded by the activity
 * (AD-8), never here, so an InCallService cold bind stays cheap.
 */
class RovstiPhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        watchMainThread()
        // Each step on its own: this runs for every process start, including the ones for an
        // incoming call or a push, and a throw here would stop the app before it could ring.
        step("install") { RovstiBridge.install(this) }
        step("calls") { CallRegistry.init(this) }
        step("observers") { RovstiBridge.observeProviders() }
        // Old contact photos go, off the main thread.
        step("prune") { Thread({ ContactPhotos.prune(this) }, "photo-prune").start() }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            android.util.Log.e("Rovsti", "start-up step $name failed", e)
        }
    }

    /**
     * Debuggable builds only (the signed release is not): logs, under the
     * StrictMode tag, any disk, network or slow call on the main thread. The
     * provider reads (contacts, call log, photos) must run off it (story 12.5),
     * so a violation here is a bug to fix, never to silence.
     */
    private fun watchMainThread() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
        )
    }
}
