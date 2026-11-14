package dev.rosty.contacts

import android.content.Intent
import android.os.Bundle
import dev.gpui.android.GpuiActivity
import dev.rosty.bridge.RostyBridge

class MainActivity : GpuiActivity() {
    override fun nativeLibraryName() = "rosty_contacts"
    override fun insetHandling() = InsetHandling.APPLICATION

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // GpuiActivity loaded the library; now resolve the JNI bridge (AD-8).
        RostyBridge.attachNative()
        RostyBridge.deliverOpenContact(intent)
    }

    /** A birthday notification tap queues `open_contact` through the mailbox. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        RostyBridge.deliverOpenContact(intent)
    }

    /** The vCard file pickers answer here; the bridge turns the answer into a `document_result`. */
    @Deprecated("The pickers use startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        RostyBridge.handleActivityResult(requestCode, resultCode, data)
    }
}
