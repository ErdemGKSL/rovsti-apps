package dev.rovsti.design.overlay

import androidx.compose.ui.geometry.Offset
import dev.rovsti.design.theme.DarkColors
import dev.rovsti.design.theme.LightColors
import dev.rovsti.design.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToastMathTest {
    private val down = 84f
    private val side = 210f

    private fun outcome(
        offset: Offset,
        velocity: Offset = Offset.Zero,
    ) = ToastMath.outcome(offset, velocity, down, side)

    @Test
    fun aShortSlowDragStays() {
        assertEquals(ToastSwipe.Stay, outcome(Offset(0f, 30f)))
        assertEquals(ToastSwipe.Stay, outcome(Offset(60f, 5f)))
    }

    @Test
    fun aLongDragDown() = assertEquals(ToastSwipe.Down, outcome(Offset(0f, 120f)))

    @Test
    fun aQuickFlickDownCountsEvenWhenShort() {
        // 20 px moved, but 600 px/s projects to ~300 px.
        assertEquals(ToastSwipe.Down, outcome(Offset(0f, 20f), Offset(0f, 600f)))
    }

    @Test
    fun sidewaysGoesTheWayItWasMoving() {
        assertEquals(ToastSwipe.Left, outcome(Offset(-60f, 0f), Offset(-900f, 0f)))
        assertEquals(ToastSwipe.Right, outcome(Offset(250f, 0f)))
    }

    @Test
    fun upwardNeverDismisses() {
        assertEquals(ToastSwipe.Stay, outcome(Offset(0f, -200f), Offset(0f, -3000f)))
    }

    @Test
    fun whenBothQualifyTheBiggerOvershootWins() {
        assertEquals(ToastSwipe.Down, outcome(Offset(100f, 400f)))
        assertEquals(ToastSwipe.Right, outcome(Offset(600f, 100f)))
    }

    @Test
    fun theGapClearsTheBarOrJustTheInset() {
        // No bar: inset 63 px + 21 px gap.
        assertEquals(84f, ToastMath.bottomGapPx(63f, 0, 21f, 21f), 0f)
        // Bar 160 px with a 21 px margin: inset + margin + bar + gap.
        assertEquals(63f + 21f + 160f + 21f, ToastMath.bottomGapPx(63f, 160, 21f, 21f), 0f)
    }

    @Test
    fun theRingEmptiesWithTime() {
        assertEquals(360f, ToastMath.ringSweep(0f), 0f)
        assertEquals(180f, ToastMath.ringSweep(0.5f), 0f)
        assertEquals(0f, ToastMath.ringSweep(1f), 0f)
        assertEquals(0f, ToastMath.ringSweep(2f), 0f)
        assertEquals(360f, ToastMath.ringSweep(-1f), 0f)
    }

    @Test
    fun upwardDragIsHeldBack() {
        assertEquals(40f, ToastMath.rubberY(40f), 0f)
        assertEquals(-10f, ToastMath.rubberY(-40f), 0f)
    }

    @Test
    fun textAndActionAreReadableOnTheInvertedCapsuleInBothThemes() {
        for ((name, colors, opposite) in listOf(
            Triple("light", LightColors, DarkColors),
            Triple("dark", DarkColors, LightColors),
        )) {
            val capsule = colors.labelPrimary.copy(alpha = ToastMetrics.LabelAlpha)
            val backdrop = colors.page
            val text = contrastRatio(colors.page, capsule, backdrop)
            val action = contrastRatio(opposite.accent, capsule, backdrop)
            assertTrue("$name text $text", text >= 4.5)
            assertTrue("$name action $action", action >= 4.5)
        }
    }
}
