package dev.rovsti.contacts.people

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import dev.rovsti.contacts.favorites.FavHarness
import dev.rovsti.contacts.favorites.FavUi
import dev.rovsti.contacts.favorites.HostedFavorites
import dev.rovsti.contacts.list.HostedList
import dev.rovsti.contacts.list.ListHarness
import dev.rovsti.contacts.list.SampleBook
import dev.rovsti.people.AvatarResolver
import dev.rovsti.people.PeopleIndex
import dev.rovsti.people.ui.AvatarFace
import dev.rovsti.people.ui.AvatarKind
import dev.rovsti.people.ui.ContactAvatar
import dev.rovsti.people.ui.LocalAvatarResolver
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * X.1c: every Contacts avatar is decided by [AvatarResolver] (own photo, else the WhatsApp picture, else initials).
 * The surfaces are drawn once with a cache that knows everybody and once with an empty one.
 */
@Config(sdk = [35])
class ContactsAvatarSurfacesTest : ShotTest() {
    private fun kind(name: String) = SemanticsMatcher("AvatarKind = $name") { it.config.getOrNull(AvatarKind) == name }

    private fun count(name: String): Int =
        compose.onAllNodes(kind(name), useUnmergedTree = true).fetchSemanticsNodes().size

    private val everybody = waResolver(SampleBook.people.flatMap { listOfNotNull(it.number, it.second) })

    private fun withResolver(
        resolver: AvatarResolver?,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) = composeFrame {
        CompositionLocalProvider(LocalAvatarResolver provides resolver) { content() }
    }

    @Test fun listRowsAreOwnPhotoElseWhatsAppElseInitials() {
        withResolver(everybody) { HostedList(ListHarness(SampleBook.state())) }
        advance(300)
        assertTrue("some rows have an own photo", count("own") > 0)
        assertTrue("the others show the WhatsApp picture", count("whatsapp") > 0)
        assertEquals("nobody is left on initials", 0, count("initials"))
        // A contact with a photo of its own never shows another one.
        val ownRows = SampleBook.items().take(VISIBLE).count { it.photoUri != null }
        assertTrue(count("own") >= ownRows.coerceAtMost(1))
    }

    @Test fun listRowsWithoutACacheAreOwnPhotoElseInitials() {
        withResolver(null) { HostedList(ListHarness(SampleBook.state())) }
        advance(300)
        assertTrue(count("own") > 0)
        assertEquals(0, count("whatsapp"))
        assertTrue(count("initials") > 0)
    }

    @Test fun searchResultsAndRecentlyViewedGoThroughTheResolver() {
        val items = SampleBook.items()
        val results = items.filter { it.name.contains("e", ignoreCase = true) && it.photoUri == null }.take(6)
        withResolver(everybody) { HostedList(ListHarness(SampleBook.state(items = results, query = "e"))) }
        advance(300)
        assertEquals(0, count("initials"))
        assertTrue(count("whatsapp") >= results.size)

        val viewed = items.filter { it.photoUri == null }.take(3)
        val harness =
            ListHarness(SampleBook.state(items = emptyList(), viewed = viewed).copy(searchFocused = true))
        withResolver(everybody) { HostedList(harness) }
        advance(300)
        assertEquals(0, count("initials"))
        assertTrue("recently viewed shows the pictures", count("whatsapp") >= viewed.size)
    }

    @Test fun favoritesAndUpcomingGoThroughTheResolver() {
        fun withNumbers(p: PersonItem) = p.copy(numbers = listOf(NUMBERS.getValue(p.id)))
        val favorites = FavUi.favorites.map(::withNumbers)
        val upcoming = FavUi.upcoming.map { it.copy(numbers = listOf(NUMBERS.getValue(it.contactId))) }
        val frequent = FavUi.frequent.map(::withNumbers)
        val state =
            FavUi
                .state(favorites = favorites, frequent = frequent, upcoming = upcoming)
                .copy(favorites = favorites.toImmutableList())
        withResolver(waResolver(NUMBERS.values)) { HostedFavorites(FavHarness(state)) }
        advance(300)
        assertEquals(0, count("initials"))
        assertTrue(count("whatsapp") >= favorites.size)
    }

    @Test fun anAvatarWithAPictureOfItsOwnIgnoresTheWhatsAppOne() {
        val face = AvatarFace(own = "content://contacts/photo/1", numbers = listOf("+90 532 111 22 33"))
        withResolver(waResolver(listOf("+90 532 111 22 33"))) {
            ContactAvatar("Ayşe Yılmaz", face, modifier = Modifier.testTag("a"))
        }
        advance(100)
        compose.onNodeWithTag("a").assert(kind("own"))
    }

    companion object {
        private const val VISIBLE = 12
        val NUMBERS: Map<Long, String> =
            mapOf(
                1L to "+90 555 111 22 33",
                2L to "0532 123 45 67",
                3L to "+90 212 555 00 11",
                4L to "+49 151 234 567 89",
                5L to "0544 987 65 43",
                7L to "0505 222 33 44",
                11L to "+44 20 7946 0958",
                22L to "0559 220 13 24",
            )
    }
}
