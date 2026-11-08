// Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/ios/component/IosSheet.kt
// (behaviour asserted here)
package dev.rovsti.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import dev.rovsti.design.ios.component.IosDetent
import dev.rovsti.design.ios.component.IosSheet
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Convx IosSheet port itself (not the SheetEntry facade): it sits at its first detent and a scrim tap asks to close. */
@OptIn(ExperimentalTestApi::class)
class IosSheetTest : ShotTest() {
    private var presented by mutableStateOf(true)
    private var requests = 0

    private fun start() {
        composeFrame {
            Box(Modifier.fillMaxSize().background(Color(0xFFF2F2F7))) {
                IosSheet(
                    isPresented = presented,
                    onDismissRequest = {
                        requests++
                        presented = false
                    },
                    detents = listOf(IosDetent.Medium, IosDetent.Large),
                ) {
                    Box(Modifier.fillMaxSize().height(200.dp).testTag("ios-sheet-body"))
                }
            }
        }
        repeat(20) { advance(100) }
    }

    @Test
    fun itOpensOnItsShortestDetent() {
        start()
        val root = compose.onNodeWithTag("ios-sheet-body").fetchSemanticsNode().boundsInRoot
        val window =
            compose.activity.resources.displayMetrics.heightPixels
                .toFloat()
        assertTrue("body starts in the lower half: ${root.top} of $window", root.top >= window * 0.5f - 1f)
    }

    @Test
    fun tappingTheScrimAsksToClose() {
        start()
        compose.onNodeWithTag("ios-sheet-body").performTouchInput { }
        compose.onRoot().performTouchInput {
            click(
                androidx.compose.ui.geometry
                    .Offset(10f, 10f),
            )
        }
        repeat(5) { advance(100) }
        assertEquals(1, requests)
    }
}
