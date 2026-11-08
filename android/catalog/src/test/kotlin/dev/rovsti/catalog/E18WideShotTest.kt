package dev.rovsti.catalog

import dev.rovsti.testing.ShotTest
import org.junit.Test
import org.robolectric.annotation.Config

/** `e1.8-title-w840`: on an 840 x 1200 dp window the content is a centred 640 dp column. */
@Config(qualifiers = "w840dp-h1200dp-xxhdpi")
class E18WideShotTest : ShotTest() {
    @Test
    fun wide() {
        shot("e1.8-title-w840") { LargeTitlePage() }
    }
}
