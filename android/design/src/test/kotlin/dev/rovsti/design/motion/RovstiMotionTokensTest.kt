package dev.rovsti.design.motion

import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import org.junit.Assert.assertEquals
import org.junit.Test

class RovstiMotionTokensTest {
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
                RovstiMotion.GlassPressDown to Triple(515f, 27.3f, 0.601f),
                RovstiMotion.GlassPressUp to Triple(250f, 11.8f, 0.373f),
                RovstiMotion.PointerPressDown to Triple(417.6f, 29.2f, 0.714f),
                RovstiMotion.PointerPressUp to Triple(230f, 23.7f, 0.781f),
                RovstiMotion.SelectRest to Triple(1024f, 58.9f, 0.920f),
                RovstiMotion.SelectDrag to Triple(2500f, 94f, 0.940f),
                RovstiMotion.LiftRest to Triple(676f, 47.8f, 0.919f),
                RovstiMotion.LiftDrag to Triple(576f, 44.2f, 0.921f),
                RovstiMotion.Toggle to Triple(1000f, 80f, 1.265f),
                RovstiMotion.ThumbLift to Triple(2000f, 80f, 0.894f),
                RovstiMotion.MenuMorphOpen to Triple(600f, 46f, 0.939f),
                RovstiMotion.MenuMorphClose to Triple(900f, 57f, 0.950f),
                RovstiMotion.MenuCardPress to Triple(650f, 36f, 0.706f),
                RovstiMotion.MenuCardRelease to Triple(600f, 30f, 0.612f),
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
        assertEquals(0.920f, RovstiMotion.SelectRest.dampingRatio, 0.001f)
    }

    /** Convx constants/Dimensions.kt NavigationBarAnimationSpec: MediumBouncy (0.5) and StiffnessLow (200). */
    @Test
    fun bottomSheetMountIsConvxsNavigationBarSpec() {
        val spec = RovstiMotion.bottomSheetMount() as androidx.compose.animation.core.SpringSpec<*>
        org.junit.Assert.assertEquals(0.5f, spec.dampingRatio, 0f)
        org.junit.Assert.assertEquals(200f, spec.stiffness, 0f)
    }

    @Test
    fun rovstiSpringsKeepTheirPicks() {
        val t = 0.001f
        pin("press", RovstiMotion.press(t), 0.75f, 1200f)
        pin("select", RovstiMotion.select(t), 0.76f, 750f)
        pin("appear", RovstiMotion.appear(t), 1f, 450f)
        pin("morph", RovstiMotion.morph(t), 1f, 950f)
        pin("sheetSettle", RovstiMotion.sheetSettle(t), 0.86f, 380f)
        pin("sheetDismiss", RovstiMotion.sheetDismiss(t), 1f, 400f)
        pin("pushBack", RovstiMotion.pushBack(t), 1f, 380f)
        pin("swipeSettle", RovstiMotion.swipeSettle(t), 1f, 500f)
        pin("menuGrowX", RovstiMotion.menuGrowX(t), 0.62f, 560f)
        pin("menuGrowY", RovstiMotion.menuGrowY(t), 0.78f, 330f)
        pin("menuLift", RovstiMotion.menuLift(t), 0.80f, 420f)
        pin("menuLand", RovstiMotion.menuLand(t), 1f, 520f)
        pin("previewReturn", RovstiMotion.previewReturn(t), 0.75f, 500f)
        pin("titleCollapse", RovstiMotion.titleCollapse(t), 1f, 500f)
        pin("barMinimize", RovstiMotion.barMinimize(t), 0.90f, 500f)
        pin("squashDecay", RovstiMotion.squashDecay(t), 0.5f, 300f)
    }

    @Test
    fun reboundIsStifferWhenThePullWasSlow() {
        assertEquals(247f, RovstiMotion.rebound(1000f).stiffness)
        assertEquals(247f, RovstiMotion.rebound(-5000f).stiffness)
        assertEquals(130f, RovstiMotion.rebound(5001f).stiffness)
        assertEquals(130f, RovstiMotion.rebound(-9000f).stiffness)
        assertEquals(1f, RovstiMotion.rebound(0f).dampingRatio)
        assertEquals(0.5f, RovstiMotion.rebound(0f).visibilityThreshold)
    }

    @Test
    fun tweensAndConstants() {
        fun ms(spec: Any) = (spec as TweenSpec<*>).durationMillis
        assertEquals(400, ms(RovstiMotion.push<Float>()))
        assertEquals(140, ms(RovstiMotion.menuExit<Float>()))
        assertEquals(180, ms(RovstiMotion.scrimIn<Float>()))
        assertEquals(140, ms(RovstiMotion.scrimOut<Float>()))
        assertEquals(100, ms(RovstiMotion.menuPlate<Float>()))
        assertEquals(200, ms(RovstiMotion.focusFill<Float>()))
        assertEquals(220, ms(RovstiMotion.glowOut<Float>()))
        assertEquals(0.30f, RovstiMotion.PushParallax)
        assertEquals(0.85f, RovstiMotion.PushDimAlpha)
        assertEquals(0.96f, RovstiMotion.PressScale)
    }

    @Test
    fun easingsHitTheirEndPointsAndHaveTheDocumentedShape() {
        for (e in listOf(
            RovstiMotion.PushEasing,
            RovstiMotion.MenuExitEasing,
            RovstiMotion.MenuEnterEasing,
            RovstiMotion.PlateEasing,
            RovstiMotion.AmbientEasing,
            RovstiMotion.FocusFillEasing,
            RovstiMotion.GlowEasing,
        )) {
            assertEquals(0f, e.transform(0f), 1e-4f)
            assertEquals(1f, e.transform(1f), 1e-4f)
        }
        // Push is front-loaded (iOS push), menu exit is back-loaded (slow start).
        assert(RovstiMotion.PushEasing.transform(0.2f) > 0.4f)
        assert(RovstiMotion.MenuExitEasing.transform(0.5f) < 0.3f)
        // The menu enter curve overshoots 1 on its way to 1.
        assert(RovstiMotion.MenuEnterEasing.transform(0.7f) > 1f)
    }
}
