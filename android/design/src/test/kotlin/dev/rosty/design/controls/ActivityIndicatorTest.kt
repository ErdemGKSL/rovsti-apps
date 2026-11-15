package dev.rosty.design.controls

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.onNodeWithTag
import dev.rosty.design.motion.LocalReduceMotion
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityIndicatorTest : ShotTest() {
    @Test fun itReadsAsIndeterminateProgressAndSpinsWithoutRecomposing() {
        var compositions = 0
        composeFrame {
            SideEffect { compositions++ }
            ActivityIndicator("Restoring", Modifier.testTag("spin"))
        }
        advance(100)
        val before = compositions
        repeat(10) { advance(130) }
        assertEquals("the page around it does not recompose while it spins", before, compositions)
        compose.onNodeWithTag("spin").assertContentDescriptionEquals("Restoring")
        assertEquals(
            ProgressBarRangeInfo.Indeterminate,
            compose.onNodeWithTag("spin").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo],
        )
    }

    @Test fun underReduceMotionItStandsStill() {
        composeFrame {
            CompositionLocalProvider(LocalReduceMotion provides true) {
                ActivityIndicator("Restoring", Modifier.testTag("spin"))
            }
        }
        advance(500)
        compose.onNodeWithTag("spin").assertContentDescriptionEquals("Restoring")
    }
}
