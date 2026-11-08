package dev.rovsti.design.chrome

import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class ChromeMetricsTest {
    private val sys = SystemInsets(left = 0.dp, top = 28.dp, right = 0.dp, bottom = 24.dp)

    @Test
    fun bottomIsSystemPlusBarPlusEight() {
        val p = contentInsetsOf(sys, topBar = 0.dp, bottomBar = 60.dp)
        assertEquals(24.dp + 60.dp + 8.dp, p.calculateBottomPadding())
    }

    @Test
    fun noBarMeansOnlyTheSystemInset() {
        val p = contentInsetsOf(sys, topBar = 0.dp, bottomBar = 0.dp)
        assertEquals(24.dp, p.calculateBottomPadding())
    }

    @Test
    fun topIsTheLargerOfStatusAndTopBar() {
        assertEquals(80.dp, contentInsetsOf(sys, 80.dp, 0.dp).calculateTopPadding())
        assertEquals(28.dp, contentInsetsOf(sys, 10.dp, 0.dp).calculateTopPadding())
    }

    @Test
    fun sidesFollowTheCutout() {
        val cut = SystemInsets(left = 44.dp, top = 0.dp, right = 0.dp, bottom = 0.dp)
        val p = contentInsetsOf(cut, 0.dp, 0.dp)
        assertEquals(44.dp, p.calculateLeftPadding(LayoutDirection.Ltr))
        assertEquals(44.dp, p.calculateLeftPadding(LayoutDirection.Rtl))
    }
}
