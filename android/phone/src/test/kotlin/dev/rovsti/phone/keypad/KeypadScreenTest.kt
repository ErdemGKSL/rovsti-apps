package dev.rovsti.phone.keypad

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.compose.ui.text.TextRange
import dev.rovsti.design.haptics.HapticEvent
import dev.rovsti.design.haptics.LocalRovstiHaptics
import dev.rovsti.phone.PhoneTags
import dev.rovsti.testing.RecordingHaptics
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** The Keypad screen without a view model: every callback is recorded, `autoAdvance` is off so time is ours. */
@Config(sdk = [35])
class KeypadScreenTest : ShotTest() {
    private val actions = mutableListOf<KeypadAction>()
    private val tones = mutableListOf<Char>()
    private val haptics = RecordingHaptics()
    private var ime = 0

    private fun show(
        ui: KeypadUi = KeypadUi(),
        field: TextFieldState = TextFieldState(),
    ) {
        composeFrame {
            androidx.compose.runtime.CompositionLocalProvider(LocalRovstiHaptics provides haptics) {
                KeypadContent(
                    ui = ui,
                    field = field,
                    strings =
                        KeypadStrings(
                            androidx.test.core.app.ApplicationProvider
                                .getApplicationContext<android.content.Context>()
                                .resources,
                        ),
                    onAction = { actions += it },
                    onTone = { tones += it },
                    onMoreMatches = {},
                    onAddContact = {},
                    onFieldMenu = {},
                    onCallMenu = {},
                    onSettings = {},
                    onImeRequest = { ime++ },
                )
            }
        }
    }

    @Test fun aKeyTypesItsDigitWithAToneAndAKeyboardTap() {
        show()
        compose.onNodeWithTag(KeypadTags.key('5')).performClick()
        advance(100)
        assertEquals(listOf<KeypadAction>(KeypadAction.Digit('5')), actions)
        assertEquals(listOf('5'), tones)
        assertEquals(listOf(HapticEvent.KeyboardTap), haptics.events)
    }

    @Test fun noToneWhenTonesAreOff() {
        show(KeypadUi(tonesOn = false))
        compose.onNodeWithTag(KeypadTags.key('7')).performClick()
        advance(100)
        assertTrue(tones.isEmpty())
        assertEquals(listOf<KeypadAction>(KeypadAction.Digit('7')), actions)
    }

    @Test fun holdingZeroTypesTheDigitThenGivesThePlus() {
        show()
        compose.onNodeWithTag(KeypadTags.key('0')).performTouchInput { down(center) }
        advance(100)
        assertEquals("the digit is typed on touch-down", listOf<KeypadAction>(KeypadAction.Digit('0')), actions)
        advance(600)
        assertEquals(listOf(KeypadAction.Digit('0'), KeypadAction.Hold('0')), actions)
        compose.onNodeWithTag(KeypadTags.key('0')).performTouchInput { up() }
        advance(100)
        assertEquals("lifting adds nothing", 2, actions.size)
    }

    @Test fun aQuickTapOnZeroNeverHolds() {
        show()
        compose.onNodeWithTag(KeypadTags.key('0')).performTouchInput { click(center) }
        advance(700)
        assertEquals(listOf<KeypadAction>(KeypadAction.Digit('0')), actions)
    }

    @Test fun deleteRepeatsWhileHeldAndOnlyAppearsWithText() {
        show()
        compose.onNodeWithTag(KeypadTags.Delete).assertDoesNotExist()
        show(KeypadUi(dialed = "123456"), TextFieldState("123456", TextRange(6)))
        compose.onNodeWithTag(KeypadTags.Delete).performTouchInput { down(center) }
        advance(100)
        assertEquals("one delete on touch-down", 1, actions.count { it == KeypadAction.Backspace })
        advance(400)
        advance(70 * 3)
        val held = actions.count { it == KeypadAction.Backspace }
        assertTrue("held key repeats: $held", held >= 4)
        compose.onNodeWithTag(KeypadTags.Delete).performTouchInput { up() }
        advance(500)
        assertEquals("nothing after the finger left", held, actions.count { it == KeypadAction.Backspace })
    }

    @Test fun holdingDeleteLongEnoughClears() {
        show(KeypadUi(dialed = "123456"), TextFieldState("123456", TextRange(6)))
        compose.onNodeWithTag(KeypadTags.Delete).performTouchInput { down(center) }
        advance(2_000)
        compose.onNodeWithTag(KeypadTags.Delete).performTouchInput { up() }
        advance(100)
        assertTrue(KeypadAction.Clear in actions)
    }

    @Test fun theCallKeyCalls() {
        show()
        compose.onNodeWithTag(KeypadTags.Call).performClick()
        advance(100)
        assertEquals(listOf<KeypadAction>(KeypadAction.Call), actions)
    }

    @Test fun focusingTheFieldNeverOpensTheKeyboard() {
        show(KeypadUi(dialed = "5551234"), TextFieldState("5551234", TextRange(7)))
        compose.onNodeWithTag(KeypadTags.Field).performTouchInput { click(center) }
        advance(200)
        val imm =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>()
                .getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        assertFalse(
            org.robolectric.Shadows
                .shadowOf(imm)
                .isSoftInputVisible,
        )
    }

    @Test fun theKeysSitAboveTheTabBarSlot() {
        show()
        compose.onNodeWithTag(KeypadTags.key('0')).assertIsDisplayed()
        compose.onNodeWithTag(KeypadTags.Call).assertIsDisplayed()
        compose.onNodeWithTag(PhoneTags.SettingsButton).assertIsDisplayed()
    }

    @Test fun typingOneKeyRecomposesTheScreenAtMostTwice() {
        val field = TextFieldState()
        val ui = androidx.compose.runtime.mutableStateOf(KeypadUi())
        composeFrame {
            KeypadContent(
                ui = ui.value,
                field = field,
                strings =
                    KeypadStrings(
                        androidx.test.core.app.ApplicationProvider
                            .getApplicationContext<android.content.Context>()
                            .resources,
                    ),
                onAction = {},
                onTone = {},
                onMoreMatches = {},
                onAddContact = {},
                onFieldMenu = {},
                onCallMenu = {},
                onSettings = {},
            )
        }
        advance(200)
        val before = KeypadProbe.recompositions.get()
        for ((i, c) in "5551234".withIndex()) {
            val start = KeypadProbe.recompositions.get()
            field.edit { append(c) }
            ui.value = ui.value.copy(dialed = ui.value.dialed + c, t9 = persistentListOf())
            advance(100)
            val cost = KeypadProbe.recompositions.get() - start
            assertTrue("key ${i + 1} recomposed $cost times", cost <= KEY_RECOMPOSITION_BUDGET)
        }
        assertTrue(KeypadProbe.recompositions.get() > before)
    }
}
