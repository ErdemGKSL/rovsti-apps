package dev.rovsti.phone

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import android.util.Log
import dev.rovsti.design.time.SystemRovstiClock
import dev.rovsti.phone.wa.WaCallActionReceiver
import dev.rovsti.platform.PlatformComponents
import dev.rovsti.platform.contacts.ContactPhotos
import dev.rovsti.platform.identity.CallerLookup
import dev.rovsti.platform.notify.PlatformChannels
import dev.rovsti.platform.telephony.CallRegistry
import dev.rovsti.shell.HasContainer

/**
 * The `Application` of Rovsti Phone (FQCN kept from the GPUI build, ARCH 7.2). It runs for every process start,
 * including the ones for an incoming call or a push, so it does only Kotlin work and never loads the native library:
 * the container is lazy and the Rust core starts on first use (AD-8).
 */
class RovstiPhoneApp :
    Application(),
    HasContainer<PhoneContainer> {
    override val container: PhoneContainer by lazy { PhoneContainer(this) }

    override fun onCreate() {
        super.onCreate()
        watchMainThread()
        // Each step on its own: a throw here would stop the app before it could ring.
        step("components") {
            // Notification taps and the call-notification actions start these (PlatformComponents, ARCH 7.4).
            PlatformComponents.install(
                PlatformComponents(
                    mainActivity = MainActivity::class.java,
                    callAction = CallActionReceiver::class.java,
                    waCallAction = WaCallActionReceiver::class.java,
                ),
            )
        }
        // Channel names and descriptions in the phone's language, again after a language change (F32).
        step("channels") { PlatformChannels.followLocale(this) }
        step("calls") {
            CallRegistry.init(this)
            CallRegistry.timing = IncomingPerf
            // Calls and their notifications are named by the same caller-ID as Recents (F39); the container is
            // built on the first lookup, off the main thread.
            CallerLookup.install { number -> container.identifyBlocking(number) }
        }
        // Old contact photos go, off the main thread.
        step("prune") {
            Thread({ ContactPhotos.prune(this, SystemRovstiClock.nowMillis()) }, "photo-prune").start()
        }
    }

    private inline fun step(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.e("Rovsti", "start-up step $name failed", e)
        }
    }

    /**
     * Debuggable builds only (the signed release is not): logs, under the StrictMode tag, any disk, network or slow
     * call on the main thread. The provider reads (contacts, call log, photos) run off it, so a violation here is a bug
     * to fix, never to silence.
     */
    private fun watchMainThread() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectAll()
                .penaltyLog()
                .build(),
        )
    }
}
