package dev.rosty.stops

import android.content.Intent
import android.os.Bundle
import dev.gpui.android.GpuiActivity
import dev.rosty.bridge.RostyBridge

class MainActivity : GpuiActivity() {
    override fun nativeLibraryName() = "rosty_stops"
    override fun insetHandling() = InsetHandling.APPLICATION

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // GpuiActivity loaded the library; now resolve the JNI bridge (AD-8).
        RostyBridge.attachNative()
        RostyBridge.deliverOpenStop(intent)
    }

    /** A stop shortcut tap while the app runs queues `open_stop` through the mailbox. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        RostyBridge.deliverOpenStop(intent)
    }

    /** Live location is a foreground-only convenience; the app re-arms it on resume if following. */
    override fun onPause() {
        RostyBridge.watchLocation(false)
        super.onPause()
    }

    /** The GTFS file picker answers here; the bridge turns the answer into a `document_result`. */
    @Deprecated("The pickers use startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        RostyBridge.handleActivityResult(requestCode, resultCode, data)
    }
}
