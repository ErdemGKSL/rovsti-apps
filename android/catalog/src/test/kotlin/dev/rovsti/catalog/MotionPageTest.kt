package dev.rovsti.catalog

import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import dev.rovsti.design.motion.LocalReduceMotion
import dev.rovsti.design.motion.ProvideReduceMotion
import dev.rovsti.design.motion.RovstiMotion
import dev.rovsti.design.motion.rememberReduceMotion
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.testing.ShotTest
import dev.rovsti.testing.ShotVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPageTest : ShotTest() {
    @Test
    fun springCurves() {
        shot("e1.3-spring-curves", variants = setOf(ShotVariant.Fs2)) { CatalogApp(initialPage = CatalogPage.Motion) }
    }

    @Test
    fun legendNumbersComeFromTheSolver() {
        composeFrame { CatalogApp(initialPage = CatalogPage.Motion) }
        compose.onNodeWithText("Spring curves").assertIsDisplayed()
        compose.onNodeWithText("GlassPressUp").assertIsDisplayed()
        // The glisse press-up spring (ratio 0.373) overshoots by about 28%.
        compose.onNodeWithText("+28%").assertIsDisplayed()
        compose
            .onAllNodes(
                hasContentDescription("Horizontal axis: time in milliseconds", substring = true),
            ).assertCountEquals(2)
        compose.onAllNodes(hasText("no overshoot")).onFirst().assertExists()
    }

    @Test
    fun curvesOvershootOrNot() {
        val byName =
            listOf(
                Curve.of("up", RovstiMotion.GlassPressUp, Color.Red),
                Curve.of("toggle", RovstiMotion.Toggle, Color.Red),
                Curve.of("morph", 1f, 950f, Color.Red),
            ).associateBy { it.name }
        assertEquals(0.28f, byName.getValue("up").overshoot, 0.03f)
        assertEquals(0f, byName.getValue("toggle").overshoot)
        assertEquals(0f, byName.getValue("morph").overshoot)
        assertEquals(1f, byName.getValue("up").samples.last(), 0.02f)
    }

    @Test
    fun reduceMotionFollowsTheSystemAnimationSetting() {
        composeFrame {
            val reduce by rememberReduceMotion()
            RovstiText(if (reduce) "reduce on" else "reduce off")
        }
        compose.onNodeWithText("reduce off").assertIsDisplayed()
        compose.runOnUiThread {
            val resolver = compose.activity.contentResolver
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        }
        advance()
        compose.onNodeWithText("reduce on").assertIsDisplayed()
        compose.runOnUiThread {
            val resolver = compose.activity.contentResolver
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
            resolver.notifyChange(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), null)
        }
        advance()
        compose.onNodeWithText("reduce off").assertIsDisplayed()
    }

    @Test
    fun appPreferenceIsOredWithTheSystemSetting() {
        var seen = false
        composeFrame {
            ProvideReduceMotion(appPreference = true) { seen = LocalReduceMotion.current }
        }
        assertTrue(seen)
        var plain = true
        composeFrame { ProvideReduceMotion { plain = LocalReduceMotion.current } }
        assertFalse(plain)
    }
}
