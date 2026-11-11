package dev.rovsti.design.text

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.design.theme.RovstiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** F24: a result that opens a sheet lets the search go and hides the keyboard; closing the sheet brings both back. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class SearchFocusHandoffTest {
    @get:Rule
    val compose = createComposeRule()

    private val state = TextFieldState()

    private fun show() {
        compose.setContent {
            RovstiTheme {
                val handoff = rememberSearchFocusHandoff(state)
                Column {
                    SearchField(state, modifier = Modifier.testTag("search"), focusRequester = handoff.requester)
                    BasicText("Result", Modifier.testTag("result").clickable { handoff.onResultOpened() })
                    BasicText("Close sheet", Modifier.testTag("close").clickable { handoff.onSheetDismissed() })
                }
            }
        }
    }

    private fun keyboardUp(): Boolean {
        val imm =
            ApplicationProvider.getApplicationContext<Context>().getSystemService(
                Context.INPUT_METHOD_SERVICE,
            ) as InputMethodManager
        return shadowOf(imm).isSoftInputVisible
    }

    @Test fun openingAResultUnfocusesTheFieldAndHidesTheKeyboard() {
        show()
        compose.onNodeWithTag("search").performClick()
        compose.onNodeWithTag("search").performTextInput("mert")
        compose.waitForIdle()
        compose.onNodeWithTag("search").assertIsFocused()
        compose.onNodeWithTag("result").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search").assertIsNotFocused()
        assertFalse("the keyboard is hidden", keyboardUp())
        assertEquals("the query is kept", "mert", state.text.toString())
    }

    @Test fun dismissingTheSheetFocusesTheFieldAgainWithTheCaretAtTheEnd() {
        show()
        compose.onNodeWithTag("search").performClick()
        compose.onNodeWithTag("search").performTextInput("mert")
        compose.onNodeWithTag("result").performClick()
        compose.waitForIdle()
        state.edit { selection = TextRange(0) }
        compose.onNodeWithTag("close").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("search").assertIsFocused()
        assertTrue("the keyboard is shown again", keyboardUp())
        assertEquals(TextRange(4), state.selection)
    }
}
