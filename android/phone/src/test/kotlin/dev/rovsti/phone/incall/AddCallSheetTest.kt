package dev.rovsti.phone.incall

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import dev.rovsti.phone.favorites.FavoritesUi
import dev.rovsti.testing.ShotTest
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config

/** E5.7b: the Add call sheet's three tabs, on their own (no shell): what a tap or a pick dials. */
@Config(sdk = [35])
class AddCallSheetTest : ShotTest() {
    private val dialed = mutableListOf<String>()
    private val people = MutableStateFlow(addCallPeople)

    private fun show(tab: Int = 0) {
        composeFrame {
            Column(Modifier.fillMaxSize()) {
                AddCallSheetBody(people, inCallStrings, sheet = null, onDial = { dialed += it }, initialTab = tab)
            }
        }
        advance(300)
    }

    @Test fun theKeypadTypesAndTheGreenKeyDials() {
        show()
        compose.onNodeWithTag(MultiCallTags.AddNumber).assertTextEquals("Enter a number")
        for (c in "0532") {
            compose.onNodeWithTag(MultiCallTags.addKey(c)).performTouchInput {
                down(center)
                up()
            }
        }
        advance(100)
        compose.onNodeWithTag(MultiCallTags.AddNumber).assertTextEquals("0532")
        compose.onNodeWithTag(MultiCallTags.AddCall).performClick()
        assertEquals(listOf("0532"), dialed)
    }

    @Test fun theGreenKeyDoesNothingWithoutDigits() {
        show()
        compose.onNodeWithTag(MultiCallTags.AddCall).performClick()
        assertEquals(emptyList<String>(), dialed)
    }

    @Test fun holdingZeroTypesAPlus() {
        show()
        compose.onNodeWithTag(MultiCallTags.addKey('0')).performTouchInput { longClick() }
        advance(100)
        compose.onNodeWithTag(MultiCallTags.AddNumber).assertTextEquals("+")
    }

    @Test fun aFavoriteIsDialedByItsNumber() {
        show(tab = 1)
        compose.onNodeWithTag(MultiCallTags.addPerson("f2")).assertIsDisplayed().performClick()
        assertEquals(listOf("+905553334455"), dialed)
    }

    @Test fun frequentNumbersAreInTheFavoritesTab() {
        show(tab = 1)
        compose.onNodeWithTag(MultiCallTags.addPerson("q1")).assertIsDisplayed().performClick()
        assertEquals(listOf("+905550001122"), dialed)
    }

    @Test fun contactsListEverybodyWithANumber() {
        show(tab = 2)
        compose.onNodeWithTag(MultiCallTags.addPerson("p2")).assertIsDisplayed().performClick()
        compose.onNodeWithTag(MultiCallTags.addPerson("p1")).assertIsDisplayed()
        assertEquals(listOf("+905557776655"), dialed)
    }

    @Test fun anEmptyListSaysSo() {
        people.value = FavoritesUi()
        show(tab = 2)
        compose.onNodeWithTag(MultiCallTags.AddSheet).assertIsDisplayed()
    }
}
