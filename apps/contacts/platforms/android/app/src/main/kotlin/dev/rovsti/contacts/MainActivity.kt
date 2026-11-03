package dev.rovsti.contacts

import android.content.Intent
import android.os.Bundle
import dev.gpui.android.GpuiActivity
import dev.rovsti.bridge.RovstiBridge

class MainActivity : GpuiActivity() {
    override fun nativeLibraryName() = "rovsti_contacts"
    override fun insetHandling() = InsetHandling.APPLICATION

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        // GpuiActivity loaded the library; now resolve the JNI bridge (AD-8).
        RovstiBridge.attachNative()
    }

    /** The vCard file pickers answer here; the bridge turns the answer into a `document_result`. */
    @Deprecated("The pickers use startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        RovstiBridge.handleActivityResult(requestCode, resultCode, data)
    }
}
