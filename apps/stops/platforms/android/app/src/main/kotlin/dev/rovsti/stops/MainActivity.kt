package dev.rovsti.stops

import android.content.Intent
import android.os.Bundle
import dev.gpui.android.GpuiActivity
import dev.rovsti.bridge.RovstiBridge

class MainActivity : GpuiActivity() {
    override fun nativeLibraryName() = "rovsti_stops"
    override fun insetHandling() = InsetHandling.APPLICATION

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // GpuiActivity loaded the library; now resolve the JNI bridge (AD-8).
        RovstiBridge.attachNative()
        RovstiBridge.deliverOpenStop(intent)
    }

    /** A stop shortcut tap while the app runs queues `open_stop` through the mailbox. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        RovstiBridge.deliverOpenStop(intent)
    }

    /** Live location is a foreground-only convenience; the app re-arms it on resume if following. */
    override fun onPause() {
        RovstiBridge.watchLocation(false)
        super.onPause()
    }

    /** The GTFS file picker answers here; the bridge turns the answer into a `document_result`. */
    @Deprecated("The pickers use startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        RovstiBridge.handleActivityResult(requestCode, resultCode, data)
    }
}
