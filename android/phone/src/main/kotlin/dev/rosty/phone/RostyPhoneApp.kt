package dev.rosty.phone

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import androidx.work.Configuration
import dev.rosty.core.log.RostyLog
import dev.rosty.design.time.SystemRostyClock
import dev.rosty.phone.wa.WaCallActionReceiver
import dev.rosty.platform.PlatformComponents
import dev.rosty.platform.contacts.ContactPhotos
import dev.rosty.platform.identity.CallerLookup
import dev.rosty.platform.notify.PlatformChannels
import dev.rosty.platform.telephony.CallRegistry
import dev.rosty.platform.wa.WaPush
import dev.rosty.shell.HasContainer

/**
 * The `Application` of Rosty Phone (FQCN kept from the GPUI build, ARCH 7.2). It runs for every process start,
 * including the ones for an incoming call or a push, so it does only Kotlin work and never loads the native library:
 * the container is lazy and the Rust core starts on first use (AD-8). WorkManager (the people sync of Phone without
 * Contacts, people sync v2 S11) starts on demand through [workManagerConfiguration], not from a startup provider.
 */
class RostyPhoneApp :
    Application(),
    HasContainer<PhoneContainer>,
    Configuration.Provider {
    override val container: PhoneContainer by lazy { PhoneContainer(this) }

    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

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
            // A WhatsApp push whose caller is a LID gets its number from Phone's identity table (WA-R2).
            WaPush.lidNumber = { jid -> container.waNumberBlocking(jid) }
            // While Phone's WhatsApp vault is locked (always in a cold process: the keys only live in memory) a call
            // shows from the push alone, content-free, and nothing reaches the gateway (`plans/wa-vault.md` 13.3).
            WaPush.locked = { container.waVaultIfMade?.unlocked != true }
        }
        // The people cache (names and WhatsApp pictures) follows its source from the start, so the incoming screen
        // and the notifications find a known caller's picture in memory (X.1d). Off the main thread.
        step("people") {
            Thread({ runCatching { container.startPeople() } }, "people-start").start()
        }
        // Old contact photos go, off the main thread.
        step("prune") {
            Thread({ ContactPhotos.prune(this, SystemRostyClock.nowMillis()) }, "photo-prune").start()
        }
    }

    private inline fun step(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: RuntimeException) {
            RostyLog.e("phone", "start-up step $name failed", e)
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
