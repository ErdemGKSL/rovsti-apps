// Rovsti Project (C) 2026, GPL-3.0-or-later. Original code.
package dev.rovsti.design.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntSize
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import kotlin.math.pow

/** The photo background of the call screens: no photo -> aura only; photo -> image after the load; contrast of the scrim. */
@Config(sdk = [35])
class CallBackdropTest : ShotTest() {
    override val imageProducer: (Any, Int, Int) -> android.graphics.Bitmap? = { data, w, h ->
        if (data.toString().startsWith("bad")) null else dev.rovsti.testing.gradientImage(data, w, h)
    }

    private lateinit var state: CallPhotoState
    private var window = IntSize.Zero

    private fun show(photo: Any?) {
        composeFrame {
            state = rememberCallPhotoState()
            window = LocalWindowInfo.current.containerSize
            Box(Modifier.fillMaxSize().background(Color.Black).testTag("aura")) {
                CallBackdrop(photo = photo, state = state, cacheKey = "7")
            }
        }
    }

    @Test fun noPhotoLeavesTheAuraAlone() {
        show(null)
        advance(600)
        compose.onNodeWithTag("aura").assertExists()
        compose.onNodeWithTag(CallBackdropTags.Photo).assertDoesNotExist()
        assertEquals(0f, state.alpha.value, 0f)
        assertFalse(state.avatarGone.value)
        assertTrue(images.requests.isEmpty())
    }

    @Test fun thePhotoShowsAfterTheFakeCoilLoad() {
        show("content://contacts/7/photo")
        advance(900)
        compose.onNodeWithTag(CallBackdropTags.Photo).assertExists()
        compose.onNodeWithTag(CallBackdropTags.Scrim).assertExists()
        assertEquals(1f, state.alpha.value, 0.001f)
        assertTrue(state.avatarGone.value)
    }

    @Test fun thePhotoIsDecodedAtScreenSize() {
        show("content://contacts/7/photo")
        advance(900)
        val request = images.requests.first { it.data == "content://contacts/7/photo" }
        assertEquals(window.width, request.widthPx)
        assertEquals(window.height, request.heightPx)
        assertFalse("Coil's own crossfade stays off: the fade is ours", request.crossfade)
    }

    @Test fun theFirstFrameDoesNotWaitForThePhoto() {
        show("content://contacts/7/photo")
        // The frame is up and the photo is still fading in: the aura under it is what the first frames show.
        compose.onNodeWithTag("aura").assertExists()
        assertTrue("fade not over: ${state.alpha.value}", state.alpha.value < 1f)
        assertFalse(state.avatarGone.value)
        advance(900)
        assertEquals(1f, state.alpha.value, 0.001f)
    }

    @Test fun aPhotoThatFailsToLoadLeavesTheAura() {
        show("bad://photo")
        advance(900)
        compose.onNodeWithTag("aura").assertExists()
        compose.onNodeWithTag(CallBackdropTags.Photo).assertDoesNotExist()
        assertEquals(0f, state.alpha.value, 0f)
        assertFalse(state.avatarGone.value)
    }

    @Test fun theFadeTakesAboutThreeHundredMilliseconds() {
        show("content://contacts/7/photo")
        advance(120)
        val mid = state.alpha.value
        assertTrue("mid fade: $mid", mid in 0.01f..0.99f)
        advance(400)
        assertEquals(1f, state.alpha.value, 0.001f)
    }

    // ---- Contrast ------------------------------------------------------------------------------------------------

    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()

    private fun luminance(grey: Float) = lin(grey)

    private fun contrastWhiteOn(grey: Float): Float = 1.05f / (luminance(grey) + 0.05f)

    @Test fun whiteNameReadsOnTheScrimmedBrightPhoto() {
        // A bright photo: mean grey 0.67 (sky and snow). The names sit between 30 % and 60 % of the screen height.
        val photo = 0.67f
        for (fraction in listOf(0.30f, 0.40f, 0.50f, 0.60f)) {
            val behind = photo * (1f - CallScrim.alphaAt(fraction))
            val ratio = contrastWhiteOn(behind)
            assertTrue("at $fraction: $ratio", ratio >= 4.5f)
        }
    }

    @Test fun theScrimRunsFromAQuarterToAboutAHalf() {
        assertEquals(0.25f, CallScrim.alphaAt(0f), 0f)
        assertEquals(0.55f, CallScrim.alphaAt(1f), 0f)
    }
}
