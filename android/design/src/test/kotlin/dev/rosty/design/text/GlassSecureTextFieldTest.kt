package dev.rosty.design.text

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.input.ImeAction
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassSecureTextFieldTest : ShotTest() {
    private val state = TextFieldState()

    @Test fun typedTextIsKeptButShownAsPasswordAndTheImeKeySubmits() {
        var submitted = 0
        composeFrame {
            GlassSecureTextField(
                state,
                "Passphrase",
                Modifier.testTag("pass"),
                imeAction = ImeAction.Go,
                onSubmit = { submitted++ },
            )
        }
        compose.onNodeWithTag("pass").performClick()
        advance(300)
        compose.onNodeWithTag("pass").assertIsFocused()
        compose.onNodeWithTag("pass").performTextInput("ferry at six")
        advance(300)
        assertEquals("ferry at six", state.text.toString())
        val node = compose.onNodeWithTag("pass").fetchSemanticsNode()
        assertTrue("a password field", SemanticsProperties.Password in node.config)
        compose.onNodeWithTag("pass").performImeAction()
        advance(100)
        assertEquals(1, submitted)
    }
}
