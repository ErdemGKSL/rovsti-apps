package dev.rovsti.catalog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.rovsti.design.backdrop.backdrops.layerBackdrop
import dev.rovsti.design.backdrop.backdrops.rememberLayerBackdrop
import dev.rovsti.design.backdrop.drawBackdrop
import dev.rovsti.design.backdrop.effects.blur
import dev.rovsti.design.backdrop.effects.lens
import dev.rovsti.design.backdrop.effects.vibrancy
import dev.rovsti.design.shapes.ContinuousCapsule
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ported Convx backdrop engine, end to end under Robolectric NATIVE graphics (RenderEffect blur and AGSL run).
 * 300 x 300 dp source, a 200 x 80 dp capsule sampling it as a sibling.
 */
class BackdropEngineTest : ShotTest() {
    private class Px(
        val w: Int,
        val h: Int,
        val data: IntArray,
    ) {
        fun at(
            x: Int,
            y: Int,
        ) = data[y * w + x]

        fun channel(
            x: Int,
            y: Int,
            shift: Int,
        ) = (at(x, y) shr shift) and 0xFF

        fun variance(
            x0: Int,
            y0: Int,
            size: Int,
        ): Double {
            val lum = ArrayList<Double>()
            for (y in y0 until y0 + size) {
                for (x in x0 until x0 + size) {
                    val c = at(x, y)
                    lum += 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)
                }
            }
            val mean = lum.average()
            return lum.sumOf { (it - mean) * (it - mean) } / lum.size
        }
    }

    private fun grab(tag: String): Px {
        val image = compose.onNodeWithTag(tag).captureToImage()
        val data = IntArray(image.width * image.height)
        image.readPixels(data, 0, 0, image.width, image.height)
        return Px(image.width, image.height, data)
    }

    private val density get() = compose.density.density

    /** Vertical 3 dp stripes (any 8 px patch crosses an edge) or a horizontal red-to-blue ramp. */
    @Composable
    private fun Scene(
        ramp: Boolean,
        capsuleX: Float = 50f,
        withCapsule: Boolean = true,
        withLens: Boolean = true,
    ) {
        val backdrop = rememberLayerBackdrop()
        Box(Modifier.testTag("scene").size(300.dp)) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                Canvas(Modifier.fillMaxSize()) {
                    if (ramp) {
                        drawRect(Brush.horizontalGradient(listOf(Color.Red, Color.Blue)))
                    } else {
                        val step = 3.dp.toPx()
                        var x = 0f
                        var i = 0
                        while (x < size.width) {
                            drawRect(
                                if (i % 2 == 0) Color(0xFFD32F2F) else Color.White,
                                Offset(x, 0f),
                                Size(step, size.height),
                            )
                            x += step
                            i++
                        }
                    }
                }
            }
            if (withCapsule) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .offset(capsuleX.dp, 110.dp)
                        .width(200.dp)
                        .height(80.dp)
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { ContinuousCapsule },
                            effects = {
                                vibrancy()
                                blur(8.dp.toPx())
                                if (withLens) lens(16.dp.toPx(), 32.dp.toPx())
                            },
                        ),
                )
            }
        }
    }

    @Test
    fun blurFlattensTheStripesUnderTheCapsule() {
        composeFrame { Scene(ramp = false, withCapsule = false) }
        val outside = grab("scene")
        composeFrame { Scene(ramp = false) }
        val inside = grab("scene")
        val cx = (150 * density).toInt() + 4 // off a stripe edge, so the patch spans two stripes
        val cy = (150 * density).toInt()
        val vOut = outside.variance(cx, cy, 8)
        val vIn = inside.variance(cx, cy, 8)
        assertTrue("stripes must have variance, was $vOut", vOut > 500)
        assertTrue("blurred patch variance $vIn must be under 25% of $vOut", vIn < 0.25 * vOut)
    }

    @Test
    fun lensDisplacesThePixelsAtTheEdge() {
        composeFrame { Scene(ramp = true, withLens = true) }
        val bent = grab("scene")
        composeFrame { Scene(ramp = true, withLens = false) }
        val flat = grab("scene")
        val x = ((50 + 2) * density).toInt()
        val y = (150 * density).toInt()
        val differ = (0 until 30).any { bent.at(x + it, y) != flat.at(x + it, y) }
        assertTrue(
            "edge pixels with a lens must differ from the blur-only ones: " +
                (0 until 30 step 5).joinToString { "%x/%x".format(bent.at(x + it, y), flat.at(x + it, y)) },
            differ,
        )
    }

    @Test
    fun movingTheCapsuleRerecordsWhatItSamples() {
        composeFrame { Scene(ramp = true, capsuleX = 10f) }
        val before = grab("scene")
        composeFrame { Scene(ramp = true, capsuleX = 90f) }
        val after = grab("scene")
        val cy = (150 * density).toInt()
        val bx = (110 * density).toInt()
        val ax = (190 * density).toInt()
        // The ramp goes red to blue to the right; the capsule centre must follow it.
        assertTrue(
            "blue under the capsule must grow when it moves right (before=${before.channel(bx, cy, 0)} " +
                "after=${after.channel(ax, cy, 0)})",
            after.channel(ax, cy, 0) > before.channel(bx, cy, 0) + 30,
        )
        assertTrue(
            "red under the capsule must fall",
            after.channel(ax, cy, 16) < before.channel(bx, cy, 16) - 30,
        )
    }

    @Test
    fun engineShot() {
        shot("e1.24-backdrop-engine") { BackdropEnginePage() }
    }
}
