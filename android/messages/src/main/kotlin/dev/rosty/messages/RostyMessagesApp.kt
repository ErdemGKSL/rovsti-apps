package dev.rosty.messages

import android.app.Application
import androidx.work.Configuration
import dev.rosty.core.log.RostyLog
import dev.rosty.messages.notify.MessagesNotifier
import dev.rosty.platform.PlatformComponents
import dev.rosty.platform.notify.PlatformChannels
import dev.rosty.platform.sms.MmsCodec
import dev.rosty.platform.sms.MmsInbox
import dev.rosty.shell.HasContainer

/**
 * The `Application` of Rosty Messages (FQCN kept from the GPUI build, ARCH 7.2). Receivers run this `onCreate` first,
 * so everything they need is set before `onReceive`. It does only Kotlin work and never loads the native library: the
 * container is lazy and the Rust core starts on first use (AD-8). WorkManager starts on demand through
 * [workManagerConfiguration], not from a startup provider.
 */
class RostyMessagesApp :
    Application(),
    HasContainer<MessagesContainer>,
    Configuration.Provider {
    override val container: MessagesContainer by lazy { MessagesContainer(this) }

    override val workManagerConfiguration: Configuration get() = Configuration.Builder().build()

    override fun onCreate() {
        super.onCreate()
        // Each step on its own: a throw here would stop the app before a message could be stored.
        step("components") {
            // Notification taps and reminders launch this activity (PlatformComponents, ARCH 7.4).
            PlatformComponents.install(PlatformComponents(mainActivity = MainActivity::class.java))
        }
        step("mms") {
            MmsCodec.downloadedHandler = MmsCodec.DownloadedHandler(MmsInbox::onDownloaded)
            // The app's own renderer: avatars, the group summary, labels in the phone's language (E6.9).
            MmsInbox.notifier = MessagesNotifier.notifier
        }
        // Channel names and descriptions in the phone's language, again after a language change (F32).
        step("channels") { PlatformChannels.followLocale(this) }
        // Names and WhatsApp pictures from Contacts' people cache, off the main thread.
        step("people") { Thread({ runCatching { container.startPeople() } }, "people-start").start() }
    }

    private inline fun step(
        name: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: RuntimeException) {
            RostyLog.e("messages", "start-up step $name failed", e)
        }
    }
}
