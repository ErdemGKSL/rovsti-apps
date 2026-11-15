package dev.rosty.stops.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import dev.rosty.core.model.stops.Slot
import dev.rosty.core.model.stops.StopColour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.lang.management.ManagementFactory
import kotlin.math.cos
import kotlin.math.sin

/**
 * E7.3: the radar layers pixel by pixel on a transparent canvas (sizes and order of the GPUI `scene_paths`), their
 * edge cases, and that a frame of all of them allocates nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RadarPainterTest {
    private val camera = MapCamera(41.0, 29.0, 4.0).apply { setViewport(400, 800, 1f) }
    private val holder = RadarHolder()
    private val painter = RadarPainter()
    private val light = RadarPalette.Light

    private fun pin(
        x: Float,
        y: Float,
        snapped: Boolean = false,
        caption: String = "",
    ) = RadarPin(camera.latAt(y), camera.lonAt(x), snapped, caption)

    private fun points(vararg at: Pair<Float, Float>) =
        DoubleArray(at.size * 2) {
            if (it % 2 ==
                0
            ) {
                camera.latAt(at[it / 2].second)
            } else {
                camera.lonAt(at[it / 2].first)
            }
        }

    /** The circle on a horizontal AB from (100, 400) to (300, 400): left of A to B is north, the upper half. */
    private fun crossing(): RadarCrossing {
        fun half(sign: Int) =
            points(
                *Array(25) {
                    val angle = Math.PI * it / 24
                    (200f - 100f * cos(angle).toFloat()) to (400f - sign * 100f * sin(angle).toFloat())
                },
            )
        return RadarCrossing(
            camera.latAt(400f),
            camera.lonAt(100f),
            camera.latAt(400f),
            camera.lonAt(300f),
            camera.latAt(400f),
            camera.lonAt(200f),
            radiusM = 100 * camera.metresPerDpAt(400f),
            left = half(1),
            right = half(-1),
        )
    }

    private fun draw(state: RadarState): Bitmap {
        holder.set(state)
        val out = Bitmap.createBitmap(camera.widthPx, camera.heightPx, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(out), camera, holder)
        return out
    }

    private fun Bitmap.isBlank(): Boolean {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.all { it == 0 }
    }

    // ---- nothing ----

    @Test fun emptyStateDrawsNothing() {
        assertTrue(draw(RadarState.Empty).isBlank())
        // Lists too short to be a line or an area are skipped, not half drawn.
        assertTrue(draw(RadarState(trail = points(10f to 10f), line = points(50f to 50f))).isBlank())
        val degenerate = RadarCrossing(41.0, 29.0, 41.0, 29.0, 41.0, 29.0, 0.0, DoubleArray(0), DoubleArray(4))
        val out = draw(RadarState(crossing = degenerate))
        // Only the two end dots of a zero-length diameter, at the centre.
        assertEquals(light.ring, out.getPixel(200, 400))
        assertEquals(0, out.getPixel(200, 450))
    }

    @Test fun aViewportWithoutSizeIsSkipped() {
        val nowhere = MapCamera()
        holder.set(RadarState(pinA = RadarPin(41.0, 29.0)))
        painter.draw(Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)), nowhere, holder)
    }

    // ---- pins ----

    @Test fun pinsAreDiscsInTheirSideColourOnAWhiteRim() {
        val out = draw(RadarState(pinA = pin(100f, 200f), pinB = pin(300f, 200f)))
        // 12 dp disc, 2.5 dp white rim; the letter is in the middle, so look off-centre.
        assertEquals(light.left, out.getPixel(100, 209))
        assertEquals(light.right, out.getPixel(300, 209))
        assertEquals(light.halo, out.getPixel(100, 200 - 14))
        assertEquals(0, out.getPixel(100, 200 - 17))
        // The white letter.
        var white = 0
        for (x in 94..106) for (y in 194..206) if (out.getPixel(x, y) == Color.WHITE) white++
        assertTrue("letter pixels: $white", white > 8)
    }

    @Test fun aSnappedPinHasARingAndADraggedOneIsLargerAtTheFinger() {
        val snapped = draw(RadarState(pinA = pin(100f, 200f, snapped = true)))
        // Ring at 12 + 2.5 + 3.5 = 18 dp from the centre.
        val ring = snapped.getPixel(100 + 18, 200)
        assertEquals(light.left and 0xFFFFFF, ring and 0xFFFFFF)
        assertTrue(Color.alpha(ring) > 150)
        assertEquals(0, snapped.getPixel(100 + 15, 200))

        holder.dragTo(Slot.A, camera.latAt(500f), camera.lonAt(250f))
        val dragged = draw(RadarState(pinA = pin(100f, 200f, snapped = true)))
        holder.endDrag()
        assertEquals("the pin left its place", 0, dragged.getPixel(100, 209))
        // 12 * 1.2 = 14.4 dp: a pixel 13.5 out is disc, not rim.
        assertEquals(light.left, dragged.getPixel(250 + 13, 500))
        assertEquals(light.halo, dragged.getPixel(250 + 15, 500))
        // No snap ring while it is in the finger.
        assertEquals(0, dragged.getPixel(250 + 21, 500))
    }

    @Test fun aCaptionSitsUnderItsPinAndLongNamesAreCut() {
        val out = draw(RadarState(pinA = pin(200f, 200f, caption = "Point A")))
        // The plate starts 12 + 2.5 + 4 dp under the centre.
        val plateRow = 200 + 19 + 3
        assertNotEquals(0, out.getPixel(200, plateRow))
        assertEquals(0, out.getPixel(200, 200 + 17))

        fun plateWidth(b: Bitmap) = (0 until 400).count { Color.alpha(b.getPixel(it, plateRow)) > 0 }
        val short = plateWidth(out)
        val long =
            plateWidth(
                draw(
                    RadarState(
                        pinA = pin(200f, 200f, caption = "Kadıköy İskele Meydanı Aktarma Merkezi Peronu".repeat(2)),
                    ),
                ),
            )
        assertTrue("short $short", short in 30..90)
        assertTrue("long $long is capped at 160 dp", long in 120..161)
        assertTrue(draw(RadarState(pinA = pin(200f, 200f, caption = ""))).getPixel(200, plateRow) == 0)
    }

    // ---- the circle on AB ----

    @Test fun theCircleHasTwoWashedHalvesADashedRingAndTheDiameter() {
        val out = draw(RadarState(crossing = crossing()))
        val upper = out.getPixel(170, 340)
        val lower = out.getPixel(170, 460)
        // Left of A to B (north here) is the blue wash, right the green one, both faint.
        assertTrue(
            "upper ${Integer.toHexString(upper)}",
            Color.blue(upper) > Color.green(upper) && Color.alpha(upper) in 30..60,
        )
        assertTrue(
            "lower ${Integer.toHexString(lower)}",
            Color.green(lower) > Color.blue(lower) && Color.alpha(lower) in 30..60,
        )
        assertEquals("outside the circle", 0, out.getPixel(200, 280))
        // The diameter line and its end dots on white.
        assertEquals(light.ring, out.getPixel(200, 400))
        assertEquals(light.ring, out.getPixel(100, 400))
        assertEquals(light.halo, out.getPixel(100, 400 - 7))
        // The ring is dashed: along the circle some pixels are red and some are not.
        var on = 0
        var off = 0
        for (i in 0 until 360) {
            val a = Math.toRadians(i.toDouble())
            val p = out.getPixel((200 + 100 * cos(a)).toInt(), (400 + 100 * sin(a)).toInt())
            if (Color.red(p) > 200 && Color.blue(p) < 120 && Color.alpha(p) > 200) on++ else off++
        }
        assertTrue("on $on off $off", on > 120 && off > 80)
    }

    @Test fun theRingFollowsTheZoom() {
        val c = crossing()
        holder.set(RadarState(crossing = c))
        camera.zoomAbout(2.0, 200f, 400f)
        val out = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        painter.draw(Canvas(out), camera, holder)
        camera.zoomAbout(0.5, 200f, 400f)
        // Twice as close: A is at the left edge and the wash reaches 200 px up.
        assertEquals(light.ring, out.getPixel(3, 400))
        assertNotEquals(0, out.getPixel(200, 230))
    }

    @Test fun twoPinsWithoutAValidCircleAreJoinedByAPlainLine() {
        val out = draw(RadarState(pinA = pin(100f, 300f), pinB = pin(300f, 300f)))
        assertEquals(light.ring, out.getPixel(200, 300))
        assertEquals(0, out.getPixel(200, 306))
        // One pin alone has no line.
        assertEquals(0, draw(RadarState(pinA = pin(100f, 300f))).getPixel(200, 300))
        // The line follows a dragged end.
        holder.dragTo(Slot.B, camera.latAt(500f), camera.lonAt(100f))
        val dragged = draw(RadarState(pinA = pin(100f, 300f), pinB = pin(300f, 300f)))
        holder.endDrag()
        assertEquals(light.ring, dragged.getPixel(100, 400))
        assertEquals(0, dragged.getPixel(200, 300))
    }

    // ---- position ----

    @Test fun thePositionIsADotOnWhiteInItsAccuracyDisc() {
        val accuracy = 40 * camera.metresPerDpAt(400f)
        val out = draw(RadarState(position = RadarPosition(camera.latAt(400f), camera.lonAt(200f), accuracy)))
        assertEquals(light.you, out.getPixel(200, 400))
        assertEquals(light.halo, out.getPixel(200 + 7, 400))
        val disc = out.getPixel(200 + 30, 400)
        assertTrue(
            "a faint blue disc: ${Integer.toHexString(disc)}",
            Color.alpha(disc) in 30..50 && Color.blue(disc) > 200,
        )
        assertNotEquals(0, out.getPixel(200 + 38, 400))
        assertEquals(0, out.getPixel(200 + 42, 400))
    }

    @Test fun accuracyOfZeroNaNOrHalfThePlanetIsHarmless() {
        val at = camera.latAt(400f) to camera.lonAt(200f)
        for (accuracy in doubleArrayOf(0.0, -3.0, Double.NaN)) {
            val out = draw(RadarState(position = RadarPosition(at.first, at.second, accuracy)))
            assertEquals(light.you, out.getPixel(200, 400))
            assertEquals("no disc for $accuracy", 0, out.getPixel(200 + 12, 400))
        }
        // A useless fix covers the whole view with the wash (the disc is capped at 4000 dp), and still draws.
        val out = draw(RadarState(position = RadarPosition(at.first, at.second, 20_000_000.0)))
        assertNotEquals(0, out.getPixel(5, 5))
        assertEquals(light.you, out.getPixel(200, 400))
    }

    // ---- lists ----

    @Test fun theBreadcrumbIsATranslucentLineThroughItsFixes() {
        val out = draw(RadarState(trail = points(50f to 600f, 150f to 600f, 150f to 700f)))
        val p = out.getPixel(100, 600)
        assertTrue(Integer.toHexString(p), Color.blue(p) > 200 && Color.alpha(p) in 140..165)
        assertNotEquals(0, out.getPixel(150, 650))
        assertEquals(0, out.getPixel(100, 650))
    }

    @Test fun stopsAreDotsTheLineIsOrangeAndOnlyStopsInViewAreDrawn() {
        val stops =
            RadarStops(
                points(100f to 100f, 200f to 100f, 5000f to 100f, -900f to 100f),
                listOf("a", "b", "c", "d"),
                listOf("A", "B", "C", "D"),
            )
        val out = draw(RadarState(stops = stops, line = points(100f to 100f, 200f to 100f)))
        assertEquals(2, painter.lastStops)
        // The dot is over the line.
        assertTrue(Color.alpha(out.getPixel(100, 100)) > 200)
        assertEquals(light.line, out.getPixel(150, 100))
        assertEquals(0, out.getPixel(150, 104))
    }

    @Test fun noMoreThanTheCapOfStopsIsDrawn() {
        val n = RadarStops.CAP + 50
        val latLon =
            DoubleArray(2 * n) {
                if (it % 2 ==
                    0
                ) {
                    camera.latAt(10f + (it / 2) % 700)
                } else {
                    camera.lonAt(10f + (it / 2) % 380)
                }
            }
        draw(RadarState(stops = RadarStops(latLon, List(n) { "s$it" }, List(n) { "S" })))
        assertEquals(RadarStops.CAP, painter.lastStops)
    }

    @Test fun savedStopsAreColouredRingsAndTheToggleHidesThem() {
        val saved =
            RadarSaved(
                points(100f to 100f, 200f to 100f, 300f to 100f, 100f to 200f, 9000f to 9000f),
                listOf(StopColour.Orange, StopColour.Red, StopColour.Green, StopColour.Blue, StopColour.Red),
                longArrayOf(1, 2, 3, 4, 5),
            )
        val out = draw(RadarState(saved = saved))
        assertEquals(4, painter.lastSaved)
        // A hollow ring 5 dp out on a white disc.
        assertEquals(light.halo, out.getPixel(100, 100))
        assertEquals(light.saved[0], out.getPixel(105, 100))
        assertEquals(light.saved[1], out.getPixel(205, 100))
        assertEquals(light.saved[2], out.getPixel(305, 100))
        assertEquals(light.saved[3], out.getPixel(105, 200))
        assertTrue(draw(RadarState(saved = saved, showSaved = false)).isBlank())
    }

    @Test fun theMeasureLineHasADotOnEveryPoint() {
        val out = draw(RadarState(measure = points(100f to 500f, 300f to 500f)))
        assertEquals(light.measure, out.getPixel(200, 500))
        assertEquals(light.halo, out.getPixel(200, 500 - 3))
        assertEquals(light.measure, out.getPixel(100, 500 - 3))
        // One point is a dot without a line.
        assertEquals(light.measure, draw(RadarState(measure = points(100f to 500f))).getPixel(100, 500))
    }

    @Test fun darkUsesTheDarkPalette() {
        painter.setDark(true)
        val out =
            draw(
                RadarState(
                    pinA = pin(100f, 200f),
                    position = RadarPosition(camera.latAt(400f), camera.lonAt(200f), 0.0),
                ),
            )
        painter.setDark(false)
        assertEquals(RadarPalette.Dark.left, out.getPixel(100, 209))
        assertEquals(RadarPalette.Dark.you, out.getPixel(200, 400))
    }

    @Test fun paintOrderPutsPinsOverEverything() {
        val state =
            RadarState(
                pinA = pin(100f, 400f),
                crossing = crossing(),
                position = RadarPosition(camera.latAt(400f), camera.lonAt(100f), 0.0),
                trail = points(50f to 409f, 150f to 409f),
            )
        // The pin of A sits on the diameter's end dot, the position and the trail.
        assertEquals(light.left, draw(state).getPixel(100, 409))
    }

    // ---- E7.3: no allocation per frame ----

    @Test fun aFrameOfEveryLayerAllocatesNothing() {
        val n = 200
        val state =
            RadarState(
                pinA = pin(100f, 400f, snapped = true, caption = "Kadıköy İskele"),
                pinB = pin(300f, 400f, caption = "Point B"),
                crossing = crossing(),
                position = RadarPosition(camera.latAt(300f), camera.lonAt(150f), 80.0),
                trail =
                    DoubleArray(1000) {
                        if (it % 2 ==
                            0
                        ) {
                            camera.latAt(700f - it / 10f)
                        } else {
                            camera.lonAt(20f + it / 3f)
                        }
                    },
                stops =
                    RadarStops(
                        DoubleArray(2 * n) {
                            if (it % 2 ==
                                0
                            ) {
                                camera.latAt(5f + it * 2f)
                            } else {
                                camera.lonAt(7f + (it * 13) % 390)
                            }
                        },
                        List(n) { "s$it" },
                        List(n) { "Stop $it" },
                    ),
                line = points(20f to 20f, 100f to 60f, 200f to 50f, 380f to 300f),
                saved =
                    RadarSaved(
                        points(60f to 60f, 340f to 700f, 200f to 650f),
                        listOf(StopColour.Orange, StopColour.Blue, StopColour.Green),
                        longArrayOf(1, 2, 3),
                    ),
                measure = points(30f to 760f, 200f to 740f, 370f to 770f),
            )
        holder.set(state)
        val canvas = Canvas(Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888))
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val me = Thread.currentThread().id

        fun frames(count: Int) {
            for (i in 0 until count) {
                // The map moves and a pin is dragged, as in a real gesture.
                camera.panBy(if (i % 2 == 0) 0.5f else -0.5f, if (i % 3 == 0) 0.25f else -0.125f)
                camera.zoomAbout(if (i % 2 == 0) 1.0004 else 1 / 1.0004, 200f, 400f)
                holder.dragTo(Slot.B, camera.latAt(400f + i % 7), camera.lonAt(300f - i % 5))
                painter.draw(canvas, camera, holder)
            }
        }
        frames(3000)
        var best = Long.MAX_VALUE
        repeat(5) {
            val before = threads.getThreadAllocatedBytes(me)
            frames(FRAMES)
            best = minOf(best, threads.getThreadAllocatedBytes(me) - before)
        }
        assertTrue(painter.lastStops > 50)
        println("radar draw allocation: $best bytes over $FRAMES frames")
        assertEquals("bytes allocated over $FRAMES frames", 0L, best)
    }

    private companion object {
        const val FRAMES = 500
    }
}
