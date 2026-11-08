package dev.rovsti.design.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacingTokensTest {
    @Test
    fun spacingValuesArePinned() {
        assertEquals(16.dp, Spacing.gutter)
        assertEquals(
            listOf(4.dp, 8.dp, 12.dp, 16.dp, 24.dp),
            listOf(Spacing.xs, Spacing.s, Spacing.m, Spacing.l, Spacing.xl),
        )
        assertEquals(2.dp, Spacing.textGap)
        assertEquals(24.dp, Spacing.sectionGap)
    }

    @Test
    fun sizesArePinned() {
        assertEquals(56.dp, Sizes.rowMin)
        assertEquals(64.dp, Sizes.rowMinTwoLine)
        assertEquals(40.dp, Sizes.avatar)
        assertEquals(44.dp, Sizes.avatarLarge)
        assertEquals(48.dp, Sizes.touch)
        assertEquals(44.dp, Sizes.button)
        assertEquals(14.dp, Sizes.sourceGlyph)
        assertEquals(60.dp, Sizes.tabBarHeight)
        assertEquals(500.dp, Sizes.tabBarMaxWidth)
        assertEquals(640.dp, Sizes.contentMaxWidth)
        assertEquals(560.dp, Sizes.sheetMaxWidth)
    }

    @Test
    fun touchTargetsAreAtLeast44() {
        assertTrue(Sizes.touch >= 48.dp && Sizes.button >= 44.dp)
        assertTrue(Sizes.rowMin >= Sizes.touch)
    }

    @Test
    fun typeScaleIsPinnedAndDescending() {
        val t = RovstiType.Default
        assertEquals(34.sp, t.largeTitle.fontSize)
        assertEquals((-0.7).sp, t.largeTitle.letterSpacing)
        assertEquals(17.sp, t.headline.fontSize)
        assertEquals(17.sp, t.body.fontSize)
        assertEquals(15.sp, t.subheadline.fontSize)
        assertEquals(13.sp, t.footnote.fontSize)
        assertEquals(12.sp, t.caption1.fontSize)
        assertEquals(11.sp, t.caption2.fontSize)
        val sizes = t.all.map { it.second.fontSize.value }
        assertEquals(sizes.sortedDescending(), sizes)
    }

    @Test
    fun everyStyleHasAnExplicitLineHeightLargerThanItsSize() {
        for ((name, style) in RovstiType.Default.all) {
            assertFalse("$name has no line height", style.lineHeight.isUnspecified)
            assertTrue("$name line height", style.lineHeight.value > style.fontSize.value)
        }
    }
}
