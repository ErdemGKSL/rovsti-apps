package dev.rosty.stops

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import dev.rosty.platform.contacts.Shortcuts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * The activity itself starts on a container of fakes (theme, edge to edge, the shell's providers, the overlay host, the
 * map home; `StopsAppTest` checks what that root draws) and turns an intent into a one-shot link for the map.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = TestStopsApp::class)
class MainActivityLaunchTest {
    companion object {
        /**
         * Robolectric gives an activity a plain (non-visual) context unless asked; `RostyShellActivity` reads the
         * activity's display for the refresh rate, which a device always has.
         */
        @JvmStatic
        @BeforeClass
        fun activityContexts() {
            System.setProperty("robolectric.createActivityContexts", "true")
        }
    }

    private val app get() = RuntimeEnvironment.getApplication() as TestStopsApp

    private fun idle() = repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200)) }

    @Test fun theActivityStartsWithItsComposeRootAndCloses() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        idle()
        val content = controller.get().findViewById<ViewGroup>(android.R.id.content)
        assertTrue("the Compose root is set", content.getChildAt(0) is ComposeView)
        assertFalse(controller.get().isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun aStopShortcutBecomesALinkAndIsUsedOnce() {
        val intent = Intent(Intent.ACTION_MAIN).putExtra(Shortcuts.EXTRA_STOP_ID, 1L)
        val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
        assertEquals(StopsLink.Stop(1L), app.container.pendingLink.value)
        assertFalse("the extra is removed", controller.get().intent.hasExtra(Shortcuts.EXTRA_STOP_ID))
        controller.destroy()
    }

    @Test fun aGeoIntentWhileOpenBecomesAPlaceLink() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).create()
        assertNull(app.container.pendingLink.value)
        controller.newIntent(Intent(Intent.ACTION_VIEW, Uri.parse("geo:41.0,29.0")))
        assertEquals(StopsLink.Place("geo:41.0,29.0"), app.container.pendingLink.value)
        controller.destroy()
    }
}
