package dev.rovsti.phone

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import dev.rovsti.bridge.RovstiBridge

/**
 * Initialises Kotlin-only pieces. The native library is loaded by the activity
 * (AD-8), never here, so an InCallService cold bind stays cheap.
 */
class RovstiPhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        watchMainThread()
        RovstiBridge.install(this)
        CallRegistry.init(this)
        RovstiBridge.observeProviders()
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
