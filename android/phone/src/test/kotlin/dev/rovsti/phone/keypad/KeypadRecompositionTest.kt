package dev.rovsti.phone.keypad

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.rovsti.testing.CountedStatic
import dev.rovsti.testing.RecompositionCounter
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/**
 * E5.14, E1.21 harness: typing ten digits recomposes the Keypad screen at most [KEY_RECOMPOSITION_BUDGET] times per
 * key (the field and the T9 list change, the keys do not), and a static sibling beside it, counted with
 * [RecompositionCounter], is never recomposed by typing.
 */
@Config(sdk = [35])
class KeypadRecompositionTest : ShotTest() {
    private val counter = RecompositionCounter()

    private fun t9(n: Int) =
        persistentListOf(
            T9Item("a$n", 1, "Ayse Yilmaz", "+90 555 111 22 33", 0..3),
            T9Item("b$n", 2, "Mert Kaya", "+90 555 999 88 77", null),
        )

    @Test fun tenDigitsRecomposeOnlyTheFieldAndTheSuggestions() {
        val field = TextFieldState()
        val ui = mutableStateOf(KeypadUi())
        val strings = KeypadStrings(ApplicationProvider.getApplicationContext<Context>().resources)
        composeFrame {
            Box {
                CountedStatic("sibling", counter)
                KeypadContent(
                    ui = ui.value,
                    field = field,
                    strings = strings,
                    onAction = {},
                    onTone = {},
                    onMoreMatches = {},
                    onAddContact = {},
                    onFieldMenu = {},
                    onCallMenu = {},
                    onSettings = {},
                )
            }
        }
        advance(300)
        counter.reset()
        var worst = 0
        for ((i, c) in "5551234567".withIndex()) {
            val start = KeypadProbe.recompositions.get()
            field.edit { append(c) }
            ui.value = ui.value.copy(dialed = ui.value.dialed + c, t9 = t9(i))
            advance(100)
            worst = maxOf(worst, KeypadProbe.recompositions.get() - start)
        }
        assertTrue(
            "a key recomposed the screen $worst times (budget $KEY_RECOMPOSITION_BUDGET)",
            worst <= KEY_RECOMPOSITION_BUDGET,
        )
        assertEquals("typing recomposed a sibling that did not change: $counter", 0, counter["sibling"])
        assertEquals("5551234567", field.text.toString())
        compose.onNodeWithTag(KeypadTags.key('5')).performClick()
    }
}
