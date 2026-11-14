package dev.rosty.contacts

import android.app.Application
import dev.rosty.bridge.RostyBridge

/**
 * Hands the application to the shared Kotlin bridge. The native library is
 * loaded by the activity (AD-8), never here.
 */
class RostyContactsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RostyBridge.install(this)
    }
}
