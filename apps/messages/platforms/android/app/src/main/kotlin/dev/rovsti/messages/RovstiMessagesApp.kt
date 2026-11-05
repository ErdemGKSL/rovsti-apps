package dev.rovsti.messages

import android.app.Application
import dev.rovsti.bridge.Headless
import dev.rovsti.bridge.MmsCodec
import dev.rovsti.bridge.RovstiBridge

/**
 * Hands the application to the shared Kotlin bridge and tells the headless path which library to load.
 * Receivers run this `onCreate` first, so everything they need is set before `onReceive`. The native
 * library is loaded by the activity (AD-8) or, with no activity, lazily by `Headless.call`; never here.
 */
class RovstiMessagesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RovstiBridge.install(this)
        Headless.libName = LIBRARY
        MmsCodec.downloadedHandler = MmsCodec.DownloadedHandler(MmsInbox::onDownloaded)
    }

    companion object {
        const val LIBRARY = "rovsti_messages"
    }
}
