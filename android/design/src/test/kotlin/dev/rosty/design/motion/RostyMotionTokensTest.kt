package dev.rosty.design.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class RostyMotionTokensTest {
    private fun pin(
        name: String,
        spec: SpringSpec<Float>,
        ratio: Float,
        stiffness: Float,
    ) {
        assertEquals("$name ratio", ratio, spec.dampingRatio, 1e-4f)
        assertEquals("$name stiffness", stiffness, spec.stiffness, 1e-3f)
        assertEquals("$name threshold", 0.001f, spec.visibilityThreshold)
    }

    @Test
    fun glisseSpringsKeepTheirCoefficientsAndDerivedRatios() {
        val expected =
            mapOf(
                RostyMotion.GlassPressDown to Triple(515f, 27.3f, 0.601f),
                RostyMotion.GlassPressUp to Triple(250f, 11.8f, 0.373f),
                RostyMotion.PointerPressDown to Triple(417.6f, 29.2f, 0.714f),
                RostyMotion.PointerPressUp to Triple(230f, 23.7f, 0.781f),
                RostyMotion.SelectRest to Triple(1024f, 58.9f, 0.920f),
                RostyMotion.SelectDrag to Triple(2500f, 94f, 0.940f),
                RostyMotion.LiftRest to Triple(676f, 47.8f, 0.919f),
                RostyMotion.LiftDrag to Triple(576f, 44.2f, 0.921f),
                RostyMotion.Toggle to Triple(1000f, 80f, 1.265f),
                RostyMotion.ThumbLift to Triple(2000f, 80f, 0.894f),
                RostyMotion.MenuMorphOpen to Triple(600f, 46f, 0.939f),
                RostyMotion.MenuMorphClose to Triple(900f, 57f, 0.950f),
                RostyMotion.MenuCardPress to Triple(650f, 36f, 0.706f),
                RostyMotion.MenuCardRelease to Triple(600f, 30f, 0.612f),
            )
        for ((spring, e) in expected) {
            assertEquals(e.first, spring.stiffness)
            assertEquals(e.second, spring.damping)
            assertEquals("ratio of $spring", e.third, spring.dampingRatio, 0.001f)
            val spec = spring.spec(0.001f)
            assertEquals(spring.dampingRatio, spec.dampingRatio)
            assertEquals(spring.stiffness, spec.stiffness)
            assertEquals(0.001f, spec.visibilityThreshold)
        }
        assertEquals(0.920f, RostyMotion.SelectRest.dampingRatio, 0.001f)
    }

    /** Convx constants/Dimensions.kt NavigationBarAnimationSpec: MediumBouncy (0.5) and StiffnessLow (200). */
    @Test
    fun bottomSheetMountIsConvxsNavigationBarSpec() {
        val spec = RostyMotion.bottomSheetMount() as androidx.compose.animation.core.SpringSpec<*>
        org.junit.Assert.assertEquals(0.5f, spec.dampingRatio, 0f)
        org.junit.Assert.assertEquals(200f, spec.stiffness, 0f)
    }

    @Test
    fun rostySpringsKeepTheirPicks() {
        val t = 0.001f
        pin("press", RostyMotion.press(t), 0.75f, 1200f)
        pin("select", RostyMotion.select(t), 0.76f, 750f)
        pin("appear", RostyMotion.appear(t), 1f, 450f)
        pin("morph", RostyMotion.morph(t), 1f, 950f)
        pin("sheetSettle", RostyMotion.sheetSettle(t), 0.86f, 380f)
        pin("sheetDismiss", RostyMotion.sheetDismiss(t), 1f, 400f)
        pin("pushBack", RostyMotion.pushBack(t), 1f, 380f)
        pin("swipeSettle", RostyMotion.swipeSettle(t), 1f, 500f)
        pin("menuGrowX", RostyMotion.menuGrowX(t), 0.62f, 560f)
        pin("menuGrowY", RostyMotion.menuGrowY(t), 0.78f, 330f)
        pin("menuLift", RostyMotion.menuLift(t), 0.80f, 420f)
        pin("menuLand", RostyMotion.menuLand(t), 1f, 520f)
        pin("previewReturn", RostyMotion.previewReturn(t), 0.75f, 500f)
        pin("titleCollapse", RostyMotion.titleCollapse(t), 1f, 500f)
        pin("barMinimize", RostyMotion.barMinimize(t), 0.90f, 500f)
        pin("squashDecay", RostyMotion.squashDecay(t), 0.5f, 300f)
    }

    @Test
    fun reboundIsStifferWhenThePullWasSlow() {
        assertEquals(247f, RostyMotion.rebound(1000f).stiffness)
        assertEquals(247f, RostyMotion.rebound(-5000f).stiffness)
        assertEquals(130f, RostyMotion.rebound(5001f).stiffness)
        assertEquals(130f, RostyMotion.rebound(-9000f).stiffness)
        assertEquals(1f, RostyMotion.rebound(0f).dampingRatio)
        assertEquals(0.5f, RostyMotion.rebound(0f).visibilityThreshold)
    }

    @Test
    fun tweensAndConstants() {
        fun ms(spec: Any) = (spec as TweenSpec<*>).durationMillis
        assertEquals(400, ms(RostyMotion.push<Float>()))
        assertEquals(140, ms(RostyMotion.menuExit<Float>()))
        assertEquals(180, ms(RostyMotion.scrimIn<Float>()))
        assertEquals(140, ms(RostyMotion.scrimOut<Float>()))
        assertEquals(100, ms(RostyMotion.menuPlate<Float>()))
        assertEquals(200, ms(RostyMotion.focusFill<Float>()))
        assertEquals(220, ms(RostyMotion.glowOut<Float>()))
        assertEquals(0.30f, RostyMotion.PushParallax)
        assertEquals(0.85f, RostyMotion.PushDimAlpha)
        assertEquals(0.96f, RostyMotion.PressScale)
    }

    @Test
    fun easingsHitTheirEndPointsAndHaveTheDocumentedShape() {
        for (e in listOf(
            RostyMotion.PushEasing,
            RostyMotion.MenuExitEasing,
            RostyMotion.MenuEnterEasing,
            RostyMotion.PlateEasing,
            RostyMotion.AmbientEasing,
            RostyMotion.FocusFillEasing,
            RostyMotion.GlowEasing,
        )) {
            assertEquals(0f, e.transform(0f), 1e-4f)
            assertEquals(1f, e.transform(1f), 1e-4f)
        }
        // Push is front-loaded (iOS push), menu exit is back-loaded (slow start).
        assert(RostyMotion.PushEasing.transform(0.2f) > 0.4f)
        assert(RostyMotion.MenuExitEasing.transform(0.5f) < 0.3f)
        // The menu enter curve overshoots 1 on its way to 1.
        assert(RostyMotion.MenuEnterEasing.transform(0.7f) > 1f)
    }
}
