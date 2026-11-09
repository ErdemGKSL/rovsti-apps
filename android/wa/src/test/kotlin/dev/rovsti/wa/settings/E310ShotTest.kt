package dev.rovsti.wa.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.core.model.wa.SettingsView
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The E3.10 screenshots of the WhatsApp settings: one test per page, light and dark. Views are Rust's fixtures. */
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [35])
class E310ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val toolbar = RecordingToolbar()

    private fun snap(
        name: String,
        harness: () -> Harness,
        fontScale: Float = 1f,
        modes: List<Boolean> = listOf(false, true),
        before: () -> Unit = {},
    ) {
        for (dark in modes) {
            val h = harness()
            composeFrame(dark = dark, fontScale = fontScale) { HostedSettings(h, toolbar) }
            advance(300)
            before()
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private fun page(
        view: SettingsView,
        behind: SettingsView = view,
    ) = { Harness(stateOf(view, behind)) }

    @Test
    fun notLinked() = snap("e3.10-wa-not-linked", page(fixtureView("")))

    @Test
    fun notLinkedLargeFont() =
        snap("e3.10-wa-not-linked-fs2", page(fixtureView("")), fontScale = 2f, modes = listOf(false))

    @Test
    fun linkingQr() = snap("e3.10-wa-linking-qr", page(fixtureView("show_qr")))

    @Test
    fun pairingCode() = snap("e3.10-wa-pairing-code", page(fixtureView("show_code")))

    @Test
    fun connectKeyField() =
        snap(
            "e3.10-wa-connect-key-field",
            { Harness(stateOf(fixtureView("paste_key")).copy(paste = PasteRequest(1, PASTED_KEY))) },
        ) {
            val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("k", PASTED_KEY))
            val before = toolbar.shown
            val node = compose.onNodeWithTag("wa-field-key")
            val results = mutableListOf<TextLayoutResult>()
            val semantics = node.fetchSemanticsNode()
            semantics.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            val density = compose.activity.resources.displayMetrics.density
            val at = Offset(14 * density + results.first().getCursorRect(12).left, semantics.size.height / 2f)
            node.performTouchInput { longClick(at) }
            advance(800)
            // Robolectric draws no floating toolbar; it is asserted by request, as in E1.16.
            assertTrue("selection toolbar requested", toolbar.shown > before)
        }

    @Test
    fun connected() = snap("e3.10-wa-connected", page(fixtureView("connected")))

    @Test
    fun keyListMenuOpen() =
        snap("e3.10-wa-key-list-menu-open", page(fixtureView("keys_loaded"))) {
            compose.onNodeWithTag("wa-key-menu-a", useUnmergedTree = true).performClick()
            advance(1200)
        }

    @Test
    fun loggedOut() = snap("e3.10-wa-logged-out", page(fixtureView("logged_out")))
}
