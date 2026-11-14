package dev.rovsti.phone.people

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.people.AvatarResolver
import dev.rovsti.people.ui.AvatarKind
import dev.rovsti.people.ui.LocalAvatarResolver
import dev.rovsti.phone.details.photo.PersonPhotoAvatar
import dev.rovsti.phone.favorites.FavHarness
import dev.rovsti.phone.favorites.FavUi
import dev.rovsti.phone.favorites.HostedContacts
import dev.rovsti.phone.favorites.HostedFavorites
import dev.rovsti.phone.favorites.PeopleHarness
import dev.rovsti.phone.favorites.PeopleUiSample
import dev.rovsti.phone.incoming.HostedIncoming
import dev.rovsti.phone.incoming.IncomingHarness
import dev.rovsti.phone.incoming.IncomingShotHarness
import dev.rovsti.phone.incoming.hostDisplay
import dev.rovsti.phone.incoming.waitingSnapshot
import dev.rovsti.phone.recents.HostedRecents
import dev.rovsti.phone.recents.RecentsHarness
import dev.rovsti.phone.recents.RecentsItem
import dev.rovsti.phone.recents.RecentsSample
import dev.rovsti.phone.recents.RecentsStrings
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import java.io.File

/**
 * X.1d: every Phone avatar is decided by the `:people` resolver (own photo, else the WhatsApp picture, else initials).
 * The surfaces that can be drawn here are drawn with a cache that knows everybody and with none; the rest (keypad
 * suggestions, statistics, notes, the call backdrops) are pinned by a source scan, because their harnesses draw
 * through the same [PhoneAvatar].
 */
@Config(sdk = [35])
class PhoneAvatarSurfacesTest : ShotTest() {
    private fun kind(name: String) = SemanticsMatcher("AvatarKind = $name") { it.config.getOrNull(AvatarKind) == name }

    private fun count(name: String): Int =
        compose.onAllNodes(kind(name), useUnmergedTree = true).fetchSemanticsNodes().size

    private fun withResolver(
        resolver: AvatarResolver?,
        content: @Composable () -> Unit,
    ) = composeFrame { CompositionLocalProvider(LocalAvatarResolver provides resolver) { content() } }

    private val strings get() = RecentsStrings(ApplicationProvider.getApplicationContext<Context>().resources)

    private val recentNumbers =
        RecentsSample
            .rows()
            .filterIsInstance<RecentsItem.Row>()
            .map { it.row.number }

    @Test fun recentsRowsShowTheWhatsAppPictureOfTheirNumberEvenForAStranger() {
        withResolver(waResolver(recentNumbers)) { HostedRecents(RecentsHarness(), strings) }
        advance(400)
        assertTrue("rows draw the WhatsApp picture", count("whatsapp") >= 4)
        assertEquals("nobody is left on initials", 0, count("initials"))
    }

    @Test fun recentsRowsWithoutACacheStayOnInitials() {
        withResolver(null) { HostedRecents(RecentsHarness(), strings) }
        advance(400)
        assertEquals(0, count("whatsapp"))
        assertTrue(count("initials") >= 4)
    }

    @Test fun favoritesAndTheSpeedDialPickerRowsUseTheResolver() {
        val numbers = (FavUi.favorites + FavUi.frequent).mapNotNull { it.number }
        withResolver(waResolver(numbers)) { HostedFavorites(FavHarness(FavUi.state())) }
        advance(400)
        assertEquals(0, count("initials"))
        assertTrue(count("whatsapp") >= FavUi.favorites.size)
    }

    @Test fun aFavoriteWithAPhotoOfItsOwnIgnoresTheWhatsAppPicture() {
        val own = FavUi.favorites.first().copy(photoUri = "content://contacts/photo/1")
        val state = FavUi.state(favorites = listOf(own) + FavUi.favorites.drop(1))
        withResolver(waResolver(FavUi.favorites.mapNotNull { it.number })) { HostedFavorites(FavHarness(state)) }
        advance(400)
        assertEquals(1, count("own"))
        assertTrue(count("whatsapp") >= FavUi.favorites.size - 1)
    }

    @Test fun contactsTabRowsUseTheResolver() {
        val rows = PeopleUiSample.rows()
        withResolver(waResolver(rows.flatMap { it.numbers })) { HostedContacts(PeopleHarness(PeopleUiSample.ui(rows))) }
        advance(400)
        assertEquals(0, count("initials"))
        assertTrue(count("whatsapp") >= 6)
    }

    @Test fun theWaitingCallCardShowsTheWhatsAppPictureOfTheWaitingNumber() {
        val snapshot = waitingSnapshot()
        val ui = IncomingHarness(snapshot, display = hostDisplay(snapshot)).ui
        val number = ui.waiting!!.number
        assertTrue("the waiting call knows its number", number.isNotEmpty())
        val harness = IncomingShotHarness(ui)
        withResolver(waResolver(listOf(number))) { HostedIncoming(harness, inCall = true) }
        advance(400)
        assertEquals(1, count("whatsapp"))
    }

    @Test fun personSheetAndCallDetailsAvatarsUseTheResolverForTheirNumbers() {
        val number = "+90 532 111 22 33"
        withResolver(waResolver(listOf(number))) {
            PersonPhotoAvatar(
                name = "",
                colorKey = "k",
                size = 96.dp,
                photo = null,
                modifier = Modifier.testTag("sheet-avatar"),
                numbers = listOf(number),
            )
        }
        advance(300)
        compose.onNodeWithTag("sheet-avatar").assert(kind("whatsapp"))
        withResolver(waResolver(emptyList())) {
            PersonPhotoAvatar("", "k", 96.dp, null, Modifier.testTag("sheet-avatar"), listOf(number))
        }
        advance(300)
        compose.onNodeWithTag("sheet-avatar").assert(kind("initials"))
    }

    @Test fun everySurfaceNamedByTheSpecReadsItsPictureThroughThePeopleLayer() {
        val root = File("src/main/kotlin/dev/rovsti/phone")

        fun text(path: String) = File(root, path).readText()
        val viaAvatar =
            listOf(
                "recents/RecentsScreen.kt",
                "favorites/FavoritesScreen.kt",
                "keypad/KeypadScreen.kt",
                "stats/StatsScreen.kt",
                "notes/NotesScreen.kt",
                "incoming/WaitingCallCard.kt",
            )
        for (path in viaAvatar) assertTrue("$path draws PhoneAvatar", "PhoneAvatar(" in text(path))
        assertTrue("Contacts tab", "ContactAvatar(" in text("contacts/PhoneContactsScreen.kt"))
        assertTrue("person sheet", "numbers = ui.numbers" in text("details/PersonSheet.kt"))
        assertTrue("call details", "numbers = listOf(ui.number)" in text("details/CallDetailsSheet.kt"))
        for (path in listOf("incoming/IncomingScreen.kt", "incall/InCallRoute.kt")) {
            assertTrue(
                "$path asks rememberCallPhoto with the number",
                Regex("""rememberCallPhoto\([^)]*number|\.number\)""").containsMatchIn(text(path)),
            )
        }
        // No Phone screen hands a photo URI straight to the design Avatar any more.
        val offenders =
            root
                .walkTopDown()
                .filter { it.extension == "kt" && it.name.endsWith("Screen.kt") }
                .filter { "photoModel = " in it.readText() }
                .map { it.name }
                .toList()
        assertEquals("use PhoneAvatar: $offenders", emptyList<String>(), offenders)
    }
}
