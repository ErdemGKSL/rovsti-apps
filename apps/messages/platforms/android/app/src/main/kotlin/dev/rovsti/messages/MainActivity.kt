package dev.rovsti.messages

import android.content.Intent
import android.os.Bundle
import dev.gpui.android.GpuiActivity
import dev.rovsti.bridge.RovstiBridge

class MainActivity : GpuiActivity() {
    override fun nativeLibraryName() = RovstiMessagesApp.LIBRARY
    override fun insetHandling() = InsetHandling.APPLICATION

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // GpuiActivity loaded the library; now resolve the JNI bridge (AD-8).
        RovstiBridge.attachNative()
        forward(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        forward(intent)
    }

    /**
     * Notification taps and compose intents become `open_thread` one-shots. They go through the
     * `Mailbox`, so they survive a cold start and reach Rust right after its snapshot (AD-5).
     */
    private fun forward(intent: Intent?) {
        if (RovstiBridge.deliverHandoff(intent)) return
        if (RovstiBridge.deliverOpenThread(intent)) return
        val json = ComposeRequest.fromIntent(intent) ?: return
        // A launcher restore replays the same intent; forward each one once.
        intent?.action = Intent.ACTION_MAIN
        RovstiBridge.postOneShot("open_thread", json)
    }

    /** The file pickers (backup export and import) answer here; the bridge turns the answer into a `document_result`. */
    @Deprecated("The pickers use startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        RovstiBridge.handleActivityResult(requestCode, resultCode, data)
    }
}
