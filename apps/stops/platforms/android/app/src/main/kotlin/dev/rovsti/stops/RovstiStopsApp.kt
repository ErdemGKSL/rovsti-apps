package dev.rovsti.stops

import android.app.Application
import dev.rovsti.bridge.Headless
import dev.rovsti.bridge.RovstiBridge

/**
 * Hands the application to the shared Kotlin bridge and names the native library the headless
 * calls load. The library itself is loaded by the activity (AD-8) or, with no UI, by
 * [Headless.call] on first use, never here.
 */
class RovstiStopsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RovstiBridge.install(this)
        Headless.libName = "rovsti_stops"
    }
}
