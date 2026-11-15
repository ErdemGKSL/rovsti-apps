package dev.rosty.contacts.cards

import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.contacts.card.CardHarness
import dev.rosty.contacts.card.CardRig
import dev.rosty.contacts.card.CardTags
import dev.rosty.contacts.card.HostedCard
import dev.rosty.core.ops.CardPalette
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/** X.2 step 5 screenshots: Card Studio in its states and the Card chip on the birthday row, light and dark. */
@Config(sdk = [35])
class X2ShotTest : ShotTest() {
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

    private fun capture(
        name: String,
        dark: Boolean,
    ) {
        compose.onRoot().captureRoboImage(
            filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
            roborazziOptions = options,
        )
    }

    private fun studio(
        name: String,
        fontScale: Float = 1f,
        subject: CardStudioSubject = StudioSamples.birthday,
        steps: (CardStudioRig) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val r = CardStudioRig(this, subject = subject)
            r.show(dark, fontScale)
            r.open()
            steps(r)
            capture(name, dark)
        }
    }

    @Test fun birthday() = studio("x2-card-preview-birthday")

    @Test fun contact() =
        studio("x2-card-preview-contact") { r ->
            compose.onNodeWithText("Contact").performClick()
            advance(400)
            r.settle()
        }

    @Test fun gifRendering() =
        studio("x2-card-preview-gif") { r ->
            compose
                .onNodeWithTag(
                    CardStudioTags.Animated,
                ).performTouchInput { click(centerRight.copy(x = right - 40f)) }
            r.settle(3)
            r.ops.gate = CompletableDeferred()
            compose.onNodeWithTag(CardStudioTags.swatch(CardPalette.Dusk)).performClick()
            advance(300)
            r.settle(2)
        }

    @Config(qualifiers = "+tr")
    @Test
    fun birthdayTurkish() = studio("x2-card-preview-birthday-tr")

    @Test fun birthdayFs2() = studio("x2-card-preview-birthday-fs2", fontScale = 2f)

    @Test fun chipOnCard() {
        val r = CardRig(1).also { rig = it }
        val state = r.ready()
        for (dark in listOf(false, true)) {
            val h = CardHarness(state)
            composeFrame(dark = dark) { HostedCard(h) }
            advance(400)
            compose.onNodeWithTag("scaffold-list").performScrollToNode(hasTestTag(CardTags.cardChip("date-0")))
            advance(400)
            capture("x2-card-chip-on-card", dark)
        }
    }
}
