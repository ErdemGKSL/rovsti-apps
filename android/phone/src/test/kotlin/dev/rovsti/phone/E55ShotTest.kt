package dev.rovsti.phone

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.contacts.PeopleEmpty
import dev.rovsti.phone.contacts.PeopleTags
import dev.rovsti.phone.favorites.FavHarness
import dev.rovsti.phone.favorites.FavTags
import dev.rovsti.phone.favorites.FavUi
import dev.rovsti.phone.favorites.HostedContacts
import dev.rovsti.phone.favorites.HostedFavorites
import dev.rovsti.phone.favorites.PeopleHarness
import dev.rovsti.phone.favorites.PeopleUiSample
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** E5.5 screenshots: Favorites, a row in the air during a reorder, the Contacts tab with its rail and a search. */
@Config(sdk = [35])
class E55ShotTest : ShotTest() {
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

    @Test fun favorites() {
        for (dark in listOf(false, true)) {
            val h = FavHarness(FavUi.state())
            composeFrame(dark = dark) { HostedFavorites(h) }
            advance(1200)
            capture("e5.5-favorites", dark)
        }
    }

    /** Turkish labels are the longest ones: nothing may clip and the last row still ends above the bar. */
    @Test fun favoritesTurkish() {
        RuntimeEnvironment.setQualifiers("+tr")
        for (dark in listOf(false, true)) {
            val h = FavHarness(FavUi.state())
            composeFrame(dark = dark) { HostedFavorites(h) }
            advance(1200)
            capture("e5.5-favorites-tr", dark)
        }
    }

    @Test fun favoritesEmpty() {
        for (dark in listOf(false, true)) {
            val h = FavHarness(FavUi.state(favorites = emptyList(), frequent = emptyList()))
            composeFrame(dark = dark) { HostedFavorites(h) }
            advance(1200)
            capture("e5.5-favorites-empty", dark)
        }
    }

    @Test fun reorderDrag() {
        for (dark in listOf(false, true)) {
            val h = FavHarness(FavUi.state(editing = true))
            composeFrame(dark = dark) { HostedFavorites(h, RecordingHaptics()) }
            advance(1200)
            val pitch =
                compose
                    .onNodeWithTag(FavTags.row(3))
                    .fetchSemanticsNode()
                    .boundsInRoot.top -
                    compose
                        .onNodeWithTag(FavTags.row(1))
                        .fetchSemanticsNode()
                        .boundsInRoot.top
            val handle = { compose.onNodeWithTag(FavTags.handle(1), useUnmergedTree = true) }
            handle().performTouchInput { down(center) }
            advance(100)
            repeat(6) {
                handle().performTouchInput { moveBy(Offset(0f, pitch * 1.3f / 6f)) }
                advance(80)
            }
            advance(500)
            capture("e5.5-favorites-reorder", dark)
            handle().performTouchInput { up() }
            advance(500)
        }
    }

    @Test fun contactsTab() {
        for (dark in listOf(false, true)) {
            val h = PeopleHarness(PeopleUiSample.ui())
            composeFrame(dark = dark) { HostedContacts(h) }
            advance(1200)
            capture("e5.5-contacts-tab", dark)
        }
    }

    @Test fun contactsSearch() {
        for (dark in listOf(false, true)) {
            val rows =
                PeopleUiSample.rows(
                    listOf("Ayse Yilmaz", "Kerem Aydin", "Aylin Bulut", "Ada Lovelace"),
                    sections = false,
                )
            val h = PeopleHarness(PeopleUiSample.ui(rows, query = "ay"))
            composeFrame(dark = dark) { HostedContacts(h, initialQuery = "ay") }
            advance(1200)
            capture("e5.5-contacts-search", dark)
        }
    }

    /** F24: a result opened from the search lets the field go and drops the keyboard before the sheet rises over it. */
    @Test fun contactsSearchSheetOpen() {
        for (dark in listOf(false, true)) {
            val rows =
                PeopleUiSample.rows(
                    listOf("Ayse Yilmaz", "Kerem Aydin", "Aylin Bulut", "Ada Lovelace"),
                    sections = false,
                )
            val h = PeopleHarness(PeopleUiSample.ui(rows, query = "ay"))
            h.onOpenSheet = { _, dismissed ->
                h.overlays.present(
                    SheetEntry(
                        "Ayse Yilmaz",
                        persistentListOf(Detent.Medium, Detent.Large),
                        initialDetent = Detent.Medium,
                        onDismissed = dismissed,
                    ) { _ ->
                        RovstiText("Ayse Yilmaz", Modifier.padding(Spacing.gutter).testTag("sheet-body"))
                    },
                )
            }
            composeFrame(dark = dark) { HostedContacts(h, initialQuery = "ay") }
            advance(600)
            compose.onNodeWithTag(PeopleTags.Search).performClick()
            advance(300)
            compose.onNodeWithTag(PeopleTags.Search, useUnmergedTree = true).assertIsFocused()
            compose.onNodeWithTag(PeopleTags.row(1)).performClick()
            advance(1_400)
            compose.onNodeWithTag(PeopleTags.Search, useUnmergedTree = true).assertIsNotFocused()
            capture("e5.5-contacts-search-sheet-open", dark)
        }
    }
}
