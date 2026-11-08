// Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/component/IosSheetPresentation.kt
// (the numbers asserted here)
package dev.rovsti.design.ios.component

import androidx.compose.ui.unit.dp
import dev.rovsti.design.overlay.SheetMetrics
import dev.rovsti.design.overlay.SheetTarget
import dev.rovsti.design.overlay.sheetReleaseTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The numbers of Convx's sheet presentation and release rule (IosSheetPresentation.kt, IosSheet.kt settleSheet). */
class IosSheetPresentationTest {
    @Test
    fun theScreenPushesBackLikeConvx() {
        assertEquals(0.91f, PresentedScale, 0f)
        assertEquals(32.dp, PresentedCornerRadius)
        assertEquals(0.22f, PresentedDim, 0f)
        assertEquals(PresentedScale, SheetMetrics.PushBackScale, 0f)
        assertEquals(24.dp, IosSheetLargeGap)
    }

    @Test
    fun theSheetCornerIsConvxs38() {
        assertEquals(38.dp, SheetMetrics.CornerRadius)
    }

    @Test
    fun sheetsAreCountedNotFlagged() {
        val s = SheetPresentationState()
        s.opened()
        s.opened()
        s.closed()
        assertEquals(1, s.openCount)
        s.closed()
        s.closed()
        assertEquals(0, s.openCount)
    }

    /** IosSheet.kt settleSheet: the dismiss threshold is the lowest detent plus 12 % of the container, on the projected end. */
    @Test
    fun releaseDismissesOnTheProjectedEndpointPlusTwelvePercent() {
        val h = 2000f
        val offsets = floatArrayOf(100f, 1000f)
        // 0.998 projection: v px/s travels v * 0.499 px. Threshold: 1000 + 0.12 * 2000 = 1240.
        assertEquals(SheetTarget.Snap(1000f), sheetReleaseTarget(1000f, 470f, offsets, h, true))
        assertEquals(SheetTarget.Dismiss, sheetReleaseTarget(1000f, 490f, offsets, h, true))
        assertTrue(sheetReleaseTarget(1000f, 5000f, offsets, h, false) is SheetTarget.Snap)
    }
}
