package dev.rovsti.contacts

import android.app.Application
import dev.rovsti.bridge.RovstiBridge

/**
 * Hands the application to the shared Kotlin bridge. The native library is
 * loaded by the activity (AD-8), never here.
 */
class RovstiContactsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RovstiBridge.install(this)
    }
}
