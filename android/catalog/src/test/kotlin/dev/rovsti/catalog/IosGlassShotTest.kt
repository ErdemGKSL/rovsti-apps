package dev.rovsti.catalog

import dev.rovsti.testing.ShotTest
import org.junit.Test

/** E1.25: iosGlass, iosMenuSurface, liquidGlass presets and TopFadeBlur over stripes, real effect path. */
class IosGlassShotTest : ShotTest() {
    @Test
    fun materialsShot() {
        shot("e1.25-ios-glass-materials") { IosGlassPage() }
    }
}
