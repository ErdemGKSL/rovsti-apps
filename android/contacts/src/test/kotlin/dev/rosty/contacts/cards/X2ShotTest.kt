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
import dev.rosty.design.media.AnimatedImageSource
import dev.rosty.testing.ShotTest
import dev.rosty.testing.assertTagTextReadable
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File

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
        darks: List<Boolean> = listOf(false, true),
        images: AnimatedImageSource? = null,
        reduceMotion: Boolean = false,
        // Off while a menu scrim or the render ring dims Share: the button is deliberately not at full strength then.
        shareLive: Boolean = true,
        steps: (CardStudioRig) -> Unit = {},
    ) {
        for (dark in darks) {
            val r = CardStudioRig(this, subject = subject, images = images, reduceMotion = reduceMotion)
            r.show(dark, fontScale)
            r.open()
            steps(r)
            assertTrue("$name: the Rust golden answered every render", r.ops.renders.isNotEmpty())
            assertEquals("$name: no placeholder card", emptyList<Any>(), r.ops.placeholders.toList())
            capture(name, dark)
            if (shareLive) assertTagTextReadable(CardStudioTags.Share, 4.5, "$name Share dark=$dark")
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
        studio("x2-card-preview-gif-rendering", images = frozenOn12(), shareLive = false) { r ->
            animatedReady(r)
            // A colour that has a golden (Dawn again after Dusk is not needed): hold the next render open so the ring
            // shows over the finished picture. The toggle has settled by now (animatedReady runs the clock).
            r.ops.gate = CompletableDeferred()
            compose.onNodeWithTag(CardStudioTags.swatch(CardPalette.Lagoon)).performClick()
            advance(300)
            r.settle(2)
        }

    /** The animated preview with the fake frozen on Rust frame 12 of the 720 px optics timeline (the bent name, the glint). */
    private fun frozenOn12() =
        FakeCardImages(
            frame = File("src/test/resources/cards/birthday-dawn-en-frame12.png"),
            still = File("../../crates/rosty_app_core/tests/cards/birthday-dawn-en.png"),
        )

    @Test fun gifPlaying() = studio("x2-card-preview-gif", images = frozenOn12()) { animatedReady(it) }

    @Test fun gifReduceMotion() =
        studio("x2-card-preview-gif-reduce-motion", darks = listOf(false), images = frozenOn12(), reduceMotion = true) {
            animatedReady(it)
        }

    private fun animatedReady(r: CardStudioRig) {
        compose
            .onNodeWithTag(CardStudioTags.Animated)
            .performTouchInput { click(centerRight.copy(x = right - 40f)) }
        r.settle(3)
    }

    private fun openMore(r: CardStudioRig) {
        animatedReady(r)
        compose.onNodeWithTag(CardStudioTags.ShareMore).performClick()
        advance(1200)
        r.settle(2)
    }

    /** The finished animated card: Save, Share and the "..." button that holds "Share as still image". */
    @Test fun animatedReady() = studio("x2-share-animated") { animatedReady(it) }

    @Test fun shareStillMenu() = studio("x2-share-still-menu", shareLive = false) { openMore(it) }

    @Config(qualifiers = "+tr")
    @Test
    fun shareStillMenuTurkish() = studio("x2-share-still-menu-tr", shareLive = false) { openMore(it) }

    @Test fun animatedReadyFs2() = studio("x2-share-animated-fs2", fontScale = 2f) { animatedReady(it) }

    @Config(qualifiers = "+tr")
    @Test
    fun birthdayTurkish() = studio("x2-card-preview-birthday-tr")

    @Test fun birthdayFs2() = studio("x2-card-preview-birthday-fs2", fontScale = 2f)

    @Test fun birthdayLarge() =
        studio("x2-card-preview-birthday-large", darks = listOf(false)) { r ->
            compose.runOnUiThread { r.entry?.sheet?.animateToDetent(0) }
            r.settle(10)
        }

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
