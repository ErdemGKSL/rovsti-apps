package dev.rovsti.catalog

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rovsti.design.glass.GlassConditions
import dev.rovsti.design.glass.LocalGlassConditions
import dev.rovsti.testing.ShotTest
import dev.rovsti.testing.ShotVariant
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The E1.8 screenshots: large title expanded, collapsed, mid-scroll, font scale 2, landscape (reduce transparency too). */
class E18ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))

    private fun snap(name: String) {
        compose.onRoot().captureRoboImage(filePath = "screenshots/$name.png", roborazziOptions = options)
    }

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    private fun scroll(
        state: LazyListState,
        factor: Float,
    ) {
        val titleHeight =
            state.layoutInfo.visibleItemsInfo
                .first()
                .size
        compose.runOnIdle { runBlocking { state.scrollBy(titleHeight * factor) } }
        advance(800)
    }

    @Test
    fun expanded() {
        shot("e1.8-title-expanded") { LargeTitlePage() }
    }

    /** Font scale 2 and landscape; the plain pair this also writes is a duplicate of `expanded`. */
    @Test
    fun fs2AndLandscape() {
        shot("e1.8-title", variants = setOf(ShotVariant.Fs2, ShotVariant.Landscape)) { LargeTitlePage() }
    }

    @Test
    fun collapsed() {
        for (dark in listOf(false, true)) {
            val state = LazyListState()
            composeFrame(dark = dark) { LargeTitlePage(listState = state) }
            scroll(state, 3f)
            snap("e1.8-title-collapsed-${theme(dark)}")
        }
    }

    @Test
    fun midScroll() {
        for (dark in listOf(false, true)) {
            val state = LazyListState()
            composeFrame(dark = dark) { LargeTitlePage(listState = state) }
            scroll(state, 0.5f)
            snap("e1.8-title-mid-scroll-${theme(dark)}")
        }
    }

    @Test
    fun reduceTransparencyCollapsed() {
        for (dark in listOf(false, true)) {
            val state = LazyListState()
            composeFrame(dark = dark) {
                val base = LocalGlassConditions.current
                CompositionLocalProvider(LocalGlassConditions provides base.copy(reduceTransparency = true)) {
                    LargeTitlePage(listState = state)
                }
            }
            scroll(state, 3f)
            snap("e1.8-title-reduce-transparency-${theme(dark)}")
        }
    }

    @Test
    fun pushedSubPage() {
        shot("e1.8-title-pushed", variants = setOf(ShotVariant.Rtl)) { LargeTitlePage(initialPushed = 7) }
    }
}
