package dev.rovsti.contacts.people

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.contacts.card.CardHarness
import dev.rovsti.contacts.card.CardRig
import dev.rovsti.contacts.card.HostedCard
import dev.rovsti.contacts.list.HostedList
import dev.rovsti.contacts.list.ListHarness
import dev.rovsti.contacts.list.SampleBook
import dev.rovsti.people.ui.LocalAvatarResolver
import dev.rovsti.testing.ShotTest
import org.junit.After
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * X.1c screenshots: a WhatsApp picture where the contact has no photo of its own, the contact's photo where it has one,
 * initials where WhatsApp knows nobody. The pictures are the fake loader's gradients.
 */
@Config(sdk = [35])
class X1AvatarShotTest : ShotTest() {
    private var rig: CardRig? = null

    @After fun stop() {
        rig
            ?.scope
            ?.coroutineContext
            ?.get(kotlinx.coroutines.Job)
            ?.cancel()
    }

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

    /** WhatsApp knows every third person of the book: the rest stay on initials, three have a photo of their own. */
    private val resolver =
        waResolver(
            SampleBook.people
                .filter { it.id % 3L != 0L }
                .flatMap { listOfNotNull(it.number, it.second) },
        )

    private fun snap(
        name: String,
        content: @Composable () -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) {
                CompositionLocalProvider(LocalAvatarResolver provides resolver) { content() }
            }
            advance(400)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun list() = snap("x1-wa-avatar-list") { HostedList(ListHarness(SampleBook.state())) }

    @Test fun search() {
        val rows =
            SampleBook
                .items()
                .filter {
                    it.name.contains(
                        "e",
                        ignoreCase = true,
                    )
                }.map { it.copy(header = false) }
        snap("x1-wa-avatar-search") {
            HostedList(ListHarness(SampleBook.state(items = rows, query = "e")))
        }
    }

    @Test fun card() {
        val r = CardRig(2).also { rig = it }
        val state = r.ready()
        val header = state.header!!
        // The card of a contact WhatsApp knows: its numbers are in the cache.
        val known = waResolver(header.numbers)
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) {
                CompositionLocalProvider(LocalAvatarResolver provides known) { HostedCard(CardHarness(state)) }
            }
            advance(400)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/x1-wa-avatar-card-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }
}
