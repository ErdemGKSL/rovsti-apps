package dev.rovsti.contacts.card

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/** E4.9 screenshots: the Share submenu floating over the card and the QR sheet, light and dark. */
@Config(sdk = [35])
class E49ShotTest : ShotTest() {
    private var rig: CardRig? = null

    @After fun stop() {
        rig
            ?.scope
            ?.coroutineContext
            ?.get(kotlinx.coroutines.Job)
            ?.cancel()
    }

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(
        name: String,
        r: CardRig,
        before: (CardHarness) -> Unit,
    ) {
        val state = r.ready()
        for (dark in listOf(false, true)) {
            val h = CardHarness(state)
            composeFrame(dark = dark) { HostedCard(h) }
            advance(400)
            before(h)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun shareMenu() =
        snap("e4.9-share-menu", rig()) {
            compose.onNodeWithTag("toolbar-overflow").performClick()
            advance(700)
            compose.onNodeWithText("Share").performClick()
            advance(900)
        }

    private fun rig(): CardRig = CardRig(1).also { rig = it }

    @Test fun qrSheet() {
        val r = rig()
        r.ready()
        r.model.onAction(ContactCardAction.ShowQr)
        val model = (r.effect() as ContactCardEffect.ShowQr).model
        snap("e4.9-qr-sheet", r) { h ->
            compose.runOnUiThread { h.overlays.presentQrSheet(model) {} }
            advance(1_200)
        }
    }
}
