package dev.rovsti.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.rovsti.testing.ShotTest
import dev.rovsti.testing.accessibilityFindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The checks of `Accessibility.kt` must fail on bad nodes (positive fixtures) and stay quiet on good ones. */
class AccessibilityHelperTest : ShotTest() {
    private fun findings() = compose.accessibilityFindings(compose.activity.resources.displayMetrics.density)

    @Test
    fun smallUnlabelledClickableIsReportedTwice() {
        // Compose enlarges the touch bounds of a clickable to 48 dp by itself; a design system that opts out (or a
        // custom pointer input) is what the check exists for, so the fixture removes the enlargement.
        composeFrame {
            val base = LocalViewConfiguration.current
            val noMinimum =
                object : ViewConfiguration by base {
                    override val minimumTouchTargetSize: DpSize = DpSize.Zero
                }
            CompositionLocalProvider(LocalViewConfiguration provides noMinimum) {
                Column {
                    androidx.compose.foundation.layout
                        .Box(Modifier.size(20.dp).clickable { })
                }
            }
        }
        val problems = findings().map { it.problem }
        assertTrue(problems.any { "below 48 dp" in it })
        assertTrue(problems.any { "screen reader" in it })
    }

    @Test
    fun bigLabelledClickableIsClean() {
        composeFrame {
            androidx.compose.foundation.layout.Box(
                Modifier.size(48.dp).semantics { contentDescription = "Close" }.clickable { },
            )
        }
        assertEquals(emptyList<Any>(), findings())
    }

    @Test
    fun enabledChecksFailTheFrame() {
        enableAccessibilityChecks()
        val failed =
            try {
                composeFrame {
                    androidx.compose.foundation.layout
                        .Box(Modifier.size(20.dp).clickable { })
                }
                false
            } catch (e: IllegalStateException) {
                "Accessibility findings" in e.message.orEmpty()
            }
        assertTrue("a bad node fails the frame once the checks are on", failed)
    }

    @Test
    fun aSuppressionNeedsAReason() {
        val rejected =
            try {
                suppressAccessibility("anything", " ")
                false
            } catch (e: IllegalArgumentException) {
                true
            }
        assertTrue(rejected)
    }
}
