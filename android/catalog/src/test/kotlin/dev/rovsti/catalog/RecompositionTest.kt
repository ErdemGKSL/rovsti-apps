package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.up
import androidx.compose.ui.unit.dp
import dev.rovsti.design.chrome.RovstiTabBar
import dev.rovsti.design.ios.component.IosSlider
import dev.rovsti.design.list.SwipeAction
import dev.rovsti.design.list.SwipeRow
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.OverlayHost
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.menuTrigger
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.testing.CountRecompositions
import dev.rovsti.testing.CountedStatic
import dev.rovsti.testing.RecompositionCounter
import dev.rovsti.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E1.21 AC1: a drag or an open must not recompose what does not change (SKILL performance 10.2: at most
 * [LIMIT] recompositions per counted slot, the first composition not counted). Gestures run on the manual clock, and the
 * counter is reset after the first composition so only the interaction is measured.
 *
 * What is counted, because the design components are not instrumented (no test hooks in production code): the slots a
 * caller hands to a component, and the caller's own static siblings.
 *  - tab bar: the `imeVisible` lambda (runs in the bar's own scope, so it counts the bar's recompositions), the accessory
 *    slot, and a static sibling of the bar;
 *  - menu: the page the menu opens over (a static sibling of the trigger);
 *  - sheet: the `content` slot of the entry and the page behind it;
 *  - slider: both value-label slots and a static neighbour (the value itself changes, so it is not counted);
 *  - swipe row: the row `content` slot and a static neighbour row.
 */
@OptIn(ExperimentalTestApi::class)
class RecompositionTest : ShotTest() {
    private val counter = RecompositionCounter()

    private fun assertWithinLimit(vararg tags: String) {
        for (tag in tags) {
            assertTrue("$tag recomposed ${counter[tag]} times (limit $LIMIT); all: $counter", counter[tag] in 0..LIMIT)
        }
    }

    @Test
    fun tabBarDragAcrossFourTabs() {
        var selected by mutableIntStateOf(0)
        composeFrame {
            Column(Modifier.fillMaxSize()) {
                CountedStatic("tabbar.sibling", counter)
                Box(Modifier.weight(1f))
                RovstiTabBar(
                    tabs = CatalogTabs,
                    selected = selected,
                    onSelect = { selected = it },
                    onReselect = {},
                    imeVisible = {
                        CountRecompositions("tabbar.scope", counter)
                        false
                    },
                    accessory = { modifier, _ ->
                        CountRecompositions("tabbar.accessory", counter)
                        Box(modifier.height(40.dp).fillMaxWidth())
                    },
                )
            }
        }
        advance(500)
        counter.reset()
        val g = BarGeometry(this)
        val from = g.slotCentre(0f) - g.surface.topLeft
        compose.onNodeWithTag("tab-surface").performTouchInput { down(from) }
        advance(200)
        val total = 3f * g.slot
        repeat(12) {
            compose.onNodeWithTag("tab-surface").performTouchInput { moveBy(Offset(total / 12, 0f)) }
            advance(80)
        }
        compose.onNodeWithTag("tab-surface").performTouchInput { up() }
        advance(1200)
        assertTrue("the drag selected the last tab, was $selected", selected == 3)
        assertWithinLimit("tabbar.sibling", "tabbar.accessory", "tabbar.scope")
    }

    @Test
    fun menuOpen() {
        val items =
            persistentListOf<MenuItem>(
                MenuItem.Action("One", onClick = {}),
                MenuItem.Action("Two", onClick = {}),
                MenuItem.Separator,
                MenuItem.Action("Delete", destructive = true, onClick = {}),
            )
        composeFrame {
            val controller = remember { OverlayController() }
            OverlayHost(controller) {
                Box(Modifier.fillMaxSize().background(RovstiTheme.colors.page)) {
                    CountedStatic("menu.page", counter)
                    Box(
                        Modifier
                            .align(
                                Alignment.TopStart,
                            ).padding(24.dp, 120.dp)
                            .size(44.dp)
                            .menuTrigger(controller, items)
                            .testTag("trigger"),
                    )
                }
            }
        }
        advance(300)
        counter.reset()
        compose.onNodeWithTag("trigger", useUnmergedTree = true).performTouchInput { down(center) }
        repeat(20) { advance(60) }
        compose.onNodeWithTag("trigger", useUnmergedTree = true).performTouchInput { up() }
        advance(600)
        compose.onNodeWithTag("menu-card").assertExists()
        assertWithinLimit("menu.page")
    }

    @Test
    fun sheetDragMediumToLarge() {
        val controller = OverlayController()
        composeFrame {
            OverlayHost(controller) {
                Box(Modifier.fillMaxSize().background(RovstiTheme.colors.page)) { CountedStatic("sheet.page", counter) }
            }
        }
        val entry =
            SheetEntry(
                title = "Counted",
                detents = persistentListOf(Detent.Medium, Detent.Large),
                initialDetent = Detent.Medium,
            ) { _ ->
                CountRecompositions("sheet.content", counter)
                Box(Modifier.fillMaxWidth().height(400.dp)) { RovstiText("Body") }
            }
        compose.runOnUiThread { controller.present(entry) }
        repeat(15) { advance(100) }
        counter.reset()
        val density = compose.activity.resources.displayMetrics.density
        val travelPx = entry.sheet!!.detentOffsets[1] - entry.sheet!!.detentOffsets[0]
        compose.onNodeWithTag("sheet-grabber").performTouchInput {
            swipe(
                Offset(centerX, centerY),
                Offset(centerX, centerY - travelPx),
                (travelPx / density / 70f * 1000f).toLong(),
            )
        }
        repeat(15) { advance(100) }
        assertTrue(
            "the sheet reached the large detent",
            entry.sheet!!.offset.value <= entry.sheet!!.detentOffsets[0] + 3f,
        )
        assertWithinLimit("sheet.content", "sheet.page")
    }

    @Test
    fun sliderDrag() {
        var value by mutableFloatStateOf(0.2f)
        composeFrame {
            Column(Modifier.padding(16.dp)) {
                IosSlider(
                    value = value,
                    onValueChange = { value = it },
                    minimumValueLabel = {
                        CountRecompositions("slider.min", counter)
                        RovstiText("0")
                    },
                    maximumValueLabel = {
                        CountRecompositions("slider.max", counter)
                        RovstiText("1")
                    },
                    modifier = Modifier.testTag("slider"),
                )
                CountedStatic("slider.neighbour", counter)
            }
        }
        advance(300)
        counter.reset()
        val node = SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
        compose.onNode(node).performTouchInput {
            down(Offset(width * 0.2f, centerY))
            repeat(10) { moveBy(Offset(width * 0.06f, 0f), 100) }
            up()
        }
        advance(600)
        assertTrue("the drag moved the value, was $value", value > 0.6f)
        assertWithinLimit("slider.min", "slider.max", "slider.neighbour")
    }

    @Test
    fun swipeRowDrag() {
        composeFrame {
            Column {
                SwipeRow(
                    trailing =
                        persistentListOf(
                            SwipeAction("Archive", RovstiTheme.colors.accent, onClick = {}),
                            SwipeAction("Delete", RovstiTheme.colors.destructive, onClick = {}),
                        ),
                    modifier = Modifier.testTag("swipe-row"),
                ) {
                    CountRecompositions("swipe.content", counter)
                    Box(Modifier.fillMaxWidth().height(64.dp)) { RovstiText("Row") }
                }
                CountedStatic("swipe.neighbour", counter)
            }
        }
        advance(300)
        counter.reset()
        compose.onNodeWithTag("swipe-row").performTouchInput {
            down(center)
            repeat(8) { moveBy(Offset(-width * 0.06f, 0f), 60) }
            up()
        }
        advance(900)
        assertTrue(
            "the row opened its actions",
            compose.onAllNodesWithTag("swipe-action-Delete").fetchSemanticsNodes().isNotEmpty(),
        )
        assertWithinLimit("swipe.content", "swipe.neighbour")
    }

    /** Control: a slot that reads changing state is counted, so a pass above is not an artefact of the counter. */
    @Test
    fun theCounterSeesARecomposingSlot() {
        var n by mutableIntStateOf(0)
        composeFrame {
            RovstiText("n=$n")
            SideEffect { counter.increment("reads-state") }
        }
        counter.reset()
        repeat(5) {
            n++
            advance(32)
        }
        assertTrue("state read in the same scope recomposes it: $counter", counter["reads-state"] > LIMIT)
    }

    companion object {
        /** Recompositions allowed per counted slot during the whole interaction (SKILL performance 10.2). */
        const val LIMIT = 2
    }
}
