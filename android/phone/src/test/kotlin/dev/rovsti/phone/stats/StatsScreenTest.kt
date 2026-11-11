package dev.rovsti.phone.stats

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E5.10 behaviour on a manual clock: the bars, the bucket sheet, the actions and the empty state. */
class StatsScreenTest : ShotTest() {
    private val overlays = OverlayController()
    private val actions = mutableListOf<StatsAction>()
    private val opened = mutableListOf<String>()
    private var ui by mutableStateOf(StatsSample.week())

    private fun start(reduceMotion: Boolean = false) {
        composeFrame {
            CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                OverlayHost(overlays) {
                    Box(Modifier.fillMaxSize()) {
                        StatsScreen(
                            state = ui,
                            onAction = { action ->
                                actions += action
                                if (action is StatsAction.TapBar) {
                                    ui = ui.copy(selected = action.index)
                                    presentBucketSheet(
                                        overlays,
                                        StatsSample.bucket(),
                                        onOpen = { opened += it.number },
                                        onDismissed = {},
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    private fun fill(index: Int) = compose.onNodeWithTag(StatsTags.bar(index)).fetchSemanticsNode().config[StatsBarFill]

    @Test fun withReduceMotionTheBarsAreAtFullHeightInTheFirstFrame() {
        start(reduceMotion = true)
        for (bar in ui.bars) assertEquals("bar ${bar.index}", bar.fraction, fill(bar.index), 0.0001f)
        assertEquals(1f, fill(6), 0f)
    }

    @Test fun withMotionTheBarsStartAtZeroAndGrowOneAfterAnother() {
        start()
        assertEquals("first frame: nothing has grown", 0f, fill(6), 0.001f)
        advance(60)
        val early = fill(1)
        val late = fill(6)
        assertTrue("bar 1 left the ground before bar 6: $early vs $late", early > late)
        advance(1_500)
        for (bar in ui.bars) assertEquals("bar ${bar.index}", bar.fraction, fill(bar.index), 0.01f)
    }

    @Test fun eachBarHasADescriptionForScreenReaders() {
        start(reduceMotion = true)
        compose.onNodeWithTag(StatsTags.bar(6)).assertIsDisplayed()
        val description =
            compose
                .onNodeWithTag(
                    StatsTags.bar(6),
                ).fetchSemanticsNode()
                .config[SemanticsProperties.ContentDescription]
        assertEquals(listOf("Tuesday, 6 calls"), description)
        compose.onNodeWithTag(StatsTags.bar(0)).assertContentDescriptionEquals("Wednesday, 0 calls")
        compose.onNodeWithTag(StatsTags.bar(2)).assertContentDescriptionEquals("Friday, 1 call")
    }

    @Test fun tappingABarOpensItsBucketSheet() {
        start(reduceMotion = true)
        compose.onNodeWithTag(StatsTags.bar(6)).performClick()
        advance(1_500)
        assertEquals(StatsAction.TapBar(6), actions.single())
        compose.onNodeWithTag(StatsTags.Bucket).assertIsDisplayed()
        compose.onNodeWithTag(StatsTags.line(0)).assertIsDisplayed()
        compose.onNodeWithTag(StatsTags.line(1)).performClick()
        assertEquals(listOf("+905551112233"), opened)
    }

    @Test fun theRangeControlAndTheRowsSendTheirActions() {
        start(reduceMotion = true)
        scrollInto(StatsTags.person("+905559998877"))
        compose.onNodeWithTag(StatsTags.person("+905559998877"), useUnmergedTree = true).performClick()
        scrollInto(StatsTags.Export)
        compose.onNodeWithTag(StatsTags.Share, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(StatsTags.Export, useUnmergedTree = true).performClick()
        assertEquals(
            listOf(StatsAction.OpenPerson("+905559998877"), StatsAction.Share, StatsAction.ExportCsv),
            actions,
        )
    }

    @Test fun recordsShowTheBusiestDayAndTheLongestCall() {
        start(reduceMotion = true)
        scrollInto(StatsTags.Longest)
        compose.onNodeWithTag(StatsTags.Busiest, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(StatsTags.Longest, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun noCallsShowTheEmptyStateAndNoChart() {
        ui = StatsSample.empty()
        start(reduceMotion = true)
        compose.onNodeWithTag(StatsTags.Empty).assertIsDisplayed()
        compose.onNodeWithTag(StatsTags.Chart).assertDoesNotExist()
        compose.onNodeWithTag(StatsTags.Range).assertIsDisplayed()
    }
}
