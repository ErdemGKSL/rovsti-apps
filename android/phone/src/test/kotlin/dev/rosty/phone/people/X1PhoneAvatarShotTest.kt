package dev.rosty.phone.people

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.design.overlay.Detent
import dev.rosty.people.AvatarResolver
import dev.rosty.people.ui.LocalAvatarResolver
import dev.rosty.phone.details.DetailsSample
import dev.rosty.phone.details.presentPerson
import dev.rosty.phone.incoming.HostedIncoming
import dev.rosty.phone.incoming.IncomingHarness
import dev.rosty.phone.incoming.IncomingShotHarness
import dev.rosty.phone.incoming.hostDisplay
import dev.rosty.phone.incoming.knownCallPhoto
import dev.rosty.phone.incoming.ringing
import dev.rosty.phone.incoming.ringingSnapshot
import dev.rosty.phone.recents.HostedRecents
import dev.rosty.phone.recents.RecentsHarness
import dev.rosty.phone.recents.RecentsItem
import dev.rosty.phone.recents.RecentsSample
import dev.rosty.phone.recents.RecentsStrings
import dev.rosty.testing.ShotTest
import dev.rosty.testing.portraitImage
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * X.1d screenshots: a WhatsApp picture on Recents rows (also strangers), as the incoming call's backdrop, and on the
 * avatar of the sheet of a number nobody saved. The pictures are portraits drawn in code ([portraitImage]).
 */
@Config(sdk = [35])
class X1PhoneAvatarShotTest : ShotTest() {
    /** Every picture is a drawn head-and-shoulders portrait, one face per person (F40). */
    override val imageProducer: (Any, Int, Int) -> android.graphics.Bitmap? = ::portraitImage

    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.01f))

    private val strings get() = RecentsStrings(ApplicationProvider.getApplicationContext<Context>().resources)

    private fun snap(
        name: String,
        resolver: AvatarResolver,
        settle: Long = 1_900,
        after: (Boolean) -> Unit = {},
        content: @Composable (Boolean) -> Unit,
    ) {
        for (dark in listOf(false, true)) {
            composeFrame(dark = dark) {
                CompositionLocalProvider(LocalAvatarResolver provides resolver) { content(dark) }
            }
            advance(500)
            after(dark)
            advance(settle)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    @Test fun recents() {
        // WhatsApp knows every second caller: the others stay on initials (a named contact) or the person glyph.
        val numbers =
            RecentsSample
                .rows()
                .filterIsInstance<RecentsItem.Row>()
                .mapIndexedNotNull { i, row -> row.row.number.takeIf { i % 2 == 0 } }
        snap("x1-wa-avatar-recents", waResolver(numbers)) { HostedRecents(RecentsHarness(), strings) }
    }

    @Test fun incoming() {
        val snapshot = ringingSnapshot(ringing(account = "tel/svc#2"))
        val ui = IncomingHarness(snapshot, display = hostDisplay(snapshot)).ui
        val number = ui.caller!!.number
        val resolver = waResolver(listOf(number))
        snap("x1-wa-avatar-incoming", resolver) {
            // The screen's own path: what memory knows, on the first frame.
            val photo =
                knownCallPhoto(resolver, ContactPhotoBook.Empty, ui.caller!!.contactId, number, ui.caller!!.photoJid)
            HostedIncoming(IncomingShotHarness(ui), photo = photo)
        }
    }

    @Test fun personSheet() {
        val resolver = waResolver(listOf("+4930123456"))
        val harnesses = mutableMapOf<Boolean, RecentsHarness>()
        snap(
            "x1-wa-avatar-person-sheet",
            resolver,
            after = { dark ->
                compose.runOnUiThread {
                    harnesses
                        .getValue(
                            dark,
                        ).overlays
                        .presentPerson(DetailsSample.person(named = false), detent = Detent.Large)
                }
            },
        ) { dark ->
            val h = RecentsHarness().also { harnesses[dark] = it }
            HostedRecents(h, strings)
        }
    }
}
