package dev.rovsti.testing

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf

/**
 * Makes the plain `androidx.activity.ComponentActivity` launchable in a Robolectric test of an app module.
 *
 * The release checks (docs/kotlin-ui/toolchain.md) run the unit tests of the apps against the release manifest, which
 * does not carry compose's `ui-test-manifest` (a debug-only dependency, so the test activity never ships). Without it
 * `ActivityScenarioRule` fails with "Unable to resolve activity". Registering the component with the package manager
 * shadow is the Robolectric-documented replacement for the manifest entry; it is a no-op where the manifest has it.
 */
fun registerComposeActivity() {
    val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    shadowOf(
        app.packageManager,
    ).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
}
