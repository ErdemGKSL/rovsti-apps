package dev.rovsti.design.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the call aura: the palette sources and the mesh renderer (the loop is tested in `:phone`). */
class CallAuraTest {
    private fun channel(
        pixel: Int,
        shift: Int,
    ) = (pixel ushr shift) and 0xFF

    @Test
    fun aSeedSpinsNineCellsOfTheSameHue() {
        val palette = ArtworkMeshPalette.fromSeed(0xFF0A5FC4.toInt())
        assertEquals(9, palette.cells.size)
        // A blue seed stays blue: in every cell blue is the strongest channel.
        assertTrue(palette.cells.all { it.blue >= it.red && it.blue >= it.green })
        // Nine different lightness values, so the drift is colour and not a flat wash.
        assertTrue(
            palette.cells
                .map { it.blue }
                .toSet()
                .size >= 6,
        )
    }

    @Test
    fun pixelsAverageIntoACellGrid() {
        val side = 30
        // Left third red, middle third green, right third blue.
        val pixels =
            IntArray(side * side) { i ->
                when ((i % side) / 10) {
                    0 -> 0xFFFF0000.toInt()
                    1 -> 0xFF00FF00.toInt()
                    else -> 0xFF0000FF.toInt()
                }
            }
        val palette = ArtworkMeshPalette.fromPixels(pixels, side, side)
        assertTrue(palette.cells[0].red > 0.9f && palette.cells[0].green < 0.1f)
        assertTrue(palette.cells[4].green > 0.9f && palette.cells[4].red < 0.1f)
        assertTrue(palette.cells[8].blue > 0.9f && palette.cells[8].red < 0.1f)
    }

    @Test
    fun liftingPullsEveryChannelTowardWhite() {
        val base = ArtworkMeshPalette.fromSeed(0xFF8A3AAE.toInt())
        val lifted = base.lifted(AuraLift)
        base.cells.zip(lifted.cells).forEach { (a, b) ->
            assertTrue(b.red >= a.red && b.green >= a.green && b.blue >= a.blue)
        }
    }

    @Test
    fun theMeshRendersAnOpaqueBitmapOfTheDocumentedSize() {
        val mesh = ArtworkMesh()
        assertEquals(32, mesh.width)
        assertEquals(73, mesh.height)
        val pixels = IntArray(mesh.width * mesh.height)
        mesh.render(ArtworkMeshPalette.Fallback, 0f, pixels)
        assertTrue(pixels.all { channel(it, 24) == 0xFF })
    }

    @Test
    fun theSamePhaseRendersTheSamePixelsAndAnotherPhaseMovesThem() {
        val mesh = ArtworkMesh()
        val a = IntArray(mesh.width * mesh.height)
        val b = IntArray(a.size)
        val c = IntArray(a.size)
        val palette = ArtworkMeshPalette.fromSeed(0xFFBD2F29.toInt())
        mesh.render(palette, 1.5f, a)
        mesh.render(palette, 1.5f, b)
        mesh.render(palette, 4.0f, c)
        assertTrue(a.contentEquals(b))
        assertNotEquals(a.toList(), c.toList())
    }

    @Test
    fun aNonFinitePhaseFallsBackToZero() {
        val mesh = ArtworkMesh()
        val a = IntArray(mesh.width * mesh.height)
        val b = IntArray(a.size)
        mesh.render(ArtworkMeshPalette.Fallback, Float.NaN, a)
        mesh.render(ArtworkMeshPalette.Fallback, 0f, b)
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun theFrameCapIsThirtyFramesPerSecond() {
        assertEquals(33_000_000L, AuraFrameIntervalNanos)
        assertTrue(1_000_000_000L / AuraFrameIntervalNanos <= 30L)
    }
}
