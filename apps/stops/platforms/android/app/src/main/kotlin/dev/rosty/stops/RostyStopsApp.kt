package dev.rosty.stops

import android.app.Application
import dev.rosty.bridge.Headless
import dev.rosty.bridge.RostyBridge

/**
 * Hands the application to the shared Kotlin bridge and names the native library the headless
 * calls load. The library itself is loaded by the activity (AD-8) or, with no UI, by
 * [Headless.call] on first use, never here.
 */
class RostyStopsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RostyBridge.install(this)
        Headless.libName = "rosty_stops"
    }
}
