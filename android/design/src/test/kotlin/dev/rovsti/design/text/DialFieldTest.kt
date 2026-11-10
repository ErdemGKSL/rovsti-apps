package dev.rovsti.design.text

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.design.theme.RovstiTheme
import kotlinx.coroutines.awaitCancellation
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@OptIn(ExperimentalComposeUiApi::class)
class DialFieldTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        state: TextFieldState,
        onLongPress: (Rect) -> Unit = {},
        systemRequests: () -> Unit = {},
    ) {
        compose.setContent {
            RovstiTheme {
                // Whatever the field lets through would reach the system: nothing may.
                InterceptPlatformTextInput(
                    PlatformTextInputInterceptor { _, _ ->
                        systemRequests()
                        awaitCancellation()
                    },
                ) {
                    DialField(
                        state = state,
                        modifier = Modifier.fillMaxWidth().testTag("field-box"),
                        onLongPressOutside = onLongPress,
                    )
                }
            }
        }
    }

    @Test
    fun theFontStepsAre36DownTo24InTwos() {
        assertEquals(listOf(36, 34, 32, 30, 28, 26, 24), DialSizes.steps)
    }

    @Test
    fun theLargestSizeThatFitsWins() {
        // Text is 10 px wide per sp: 300 px fits 30 sp, and nothing fits below 24 sp's 240 px in 100 px.
        assertEquals(30, DialSizes.fit(300f) { it * 10f })
        assertEquals(36, DialSizes.fit(1000f) { it * 10f })
        assertEquals(24, DialSizes.fit(100f) { it * 10f })
    }

    @Test
    fun onlyDialCharactersSurvive() {
        assertEquals("+90555123#,;*", DialAlphabet.keep("+90 (555) 123-#,;*"))
        assertEquals(DialAlphabet.MAX_LEN, DialAlphabet.keep("1".repeat(50)).length)
    }

    @Test
    fun pasteInsertsAtTheCaret() {
        val state = TextFieldState("123456", TextRange(3))
        state.insertAtCaret("99")
        assertEquals("12399456", state.text.toString())
        assertEquals(TextRange(5), state.selection)
    }

    @Test
    fun pasteReplacesTheSelection() {
        val state = TextFieldState("123456", TextRange(1, 4))
        state.insertAtCaret("7")
        assertEquals("1756", state.text.toString())
        assertEquals(TextRange(2), state.selection)
    }

    @Test
    fun backspaceTakesTheCharacterBeforeTheCaretOrTheSelection() {
        val state = TextFieldState("123456", TextRange(3))
        assertTrue(state.backspace())
        assertEquals("12456", state.text.toString())
        assertEquals(TextRange(2), state.selection)
        state.edit { selection = TextRange(0, 2) }
        assertTrue(state.backspace())
        assertEquals("456", state.text.toString())
        state.edit { selection = TextRange(0) }
        assertFalse("nothing before the caret", state.backspace())
    }

    @Test
    fun focusNeverAsksTheSystemForAKeyboard() {
        val state = TextFieldState("5551234", TextRange(7))
        var reached = 0
        show(state, systemRequests = { reached++ })
        compose.onNodeWithTag("field-box").performTouchInput { click(center) }
        compose.waitForIdle()
        val imm = ApplicationProvider.getApplicationContext<Context>().getSystemService(InputMethodManager::class.java)
        assertFalse("the soft keyboard stays closed", shadowOf(imm).isSoftInputVisible)
        assertEquals("no input session reaches the system", 0, reached)
    }

    @Test
    fun aTapOnTheDigitsMovesTheCaret() {
        val state = TextFieldState("1234567890", TextRange(10))
        show(state)
        compose.onNodeWithTag("field-box").performTouchInput { click(center) }
        compose.waitForIdle()
        // The field is as wide as its text and centred, so the middle of the box is the middle of the digits.
        val caret = state.selection.start
        assertTrue("caret moved off the end: $caret", caret in 1..9)
    }

    @Test
    fun aLongPressBesideTheDigitsOpensTheFieldMenu() {
        val state = TextFieldState("12", TextRange(2))
        var rect: Rect? = null
        show(state, onLongPress = { rect = it })
        compose.onNodeWithTag("field-box").performTouchInput {
            longClick(
                centerLeft +
                    androidx.compose.ui.geometry
                        .Offset(20f, 0f),
            )
        }
        compose.waitForIdle()
        assertTrue("the menu was asked for", rect != null)
    }
}
