package dev.rovsti.contacts.photo

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.rovsti.contacts.HostRust
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.CropView
import dev.rovsti.core.model.contacts.PhotoAcceptsRequest
import dev.rovsti.core.model.contacts.PhotoCropRequest
import dev.rovsti.core.model.contacts.PhotoPlanRequest
import dev.rovsti.design.media.ZoomState
import dev.rovsti.design.media.crop.PhotoCropMath
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4.4 AC2: the geometry of the crop page. The pure part (pan bounds, pinch around a point, rotate, the conversion to
 * the core's [CropView]) is checked against the Rust core itself: whatever the page shows, `contacts.photo_crop` and
 * `contacts.photo_plan` must agree with it.
 */
class PhotoCropTest {
    private val side = 300f
    private val maxZoom = 3000f / 96f

    private fun viewOf(
        state: ZoomState,
        turns: Int,
        rotated: Size,
        side: Float,
        maxZoom: Float,
    ): CropView = PhotoCropMath.viewOf(state, turns, rotated, side, maxZoom).toView()

    private fun stateOf(
        view: CropView,
        rotated: Size,
        side: Float,
    ) = PhotoCropMath.stateOf(view.toFrame(), rotated, side)

    private fun size(
        w: Int,
        h: Int,
        turns: Int = 0,
    ) = PhotoCropMath.rotated(w, h, turns)

    @Test fun coverFillsTheShortSideWhateverTheTurn() {
        val landscape = size(4000, 3000)
        assertEquals(Size(400f, 300f), PhotoCropMath.cover(landscape, side))
        val turned = size(4000, 3000, 1)
        assertEquals(Size(300f, 400f), PhotoCropMath.cover(turned, side))
    }

    @Test fun aTurnSwapsTheSidesAndKeepsTheShortOne() {
        assertEquals(Size(3000f, 4000f), size(4000, 3000, 1))
        assertEquals(Size(4000f, 3000f), size(4000, 3000, 2))
        assertEquals(Size(3000f, 4000f), size(4000, 3000, 3))
    }

    @Test fun theUntouchedPictureIsTheCentredSquareOfTheShortSide() {
        val view = viewOf(ZoomState.Fit, 0, size(4000, 3000), side, maxZoom)
        assertEquals(CropView(0, 1.0, 0.5, 0.5), view)
    }

    @Test fun panStopsWhereTheWindowReachesAnEdge() {
        val rotated = size(4000, 3000)
        val far = PhotoCropMath.pan(ZoomState.Fit, Offset(10_000f, 10_000f), rotated, side)
        // At zoom 1 a landscape picture can move sideways by half the part that sticks out, and not at all vertically.
        assertEquals(50f, far.offset.x, 0.001f)
        assertEquals(0f, far.offset.y, 0.001f)
        val back = PhotoCropMath.pan(far, Offset(-10_000f, -10_000f), rotated, side)
        assertEquals(-50f, back.offset.x, 0.001f)
    }

    @Test fun pinchKeepsThePointUnderTheFingersAndStaysInRange() {
        val rotated = size(4000, 3000)
        val focus = Offset(60f, -40f)
        val next = PhotoCropMath.pinch(ZoomState.Fit, 2f, focus, Offset.Zero, rotated, side, maxZoom)
        assertEquals(2f, next.scale, 0.0001f)
        // The picture point that was under the focus is still under it: p = (focus - offset) / scale.
        val before = (focus - ZoomState.Fit.offset) / ZoomState.Fit.scale
        val after = (focus - next.offset) / next.scale
        val limit = PhotoCropMath.limit(next.scale, rotated, side)
        if (kotlin.math.abs(next.offset.x) < limit.x - 0.01f && kotlin.math.abs(next.offset.y) < limit.y - 0.01f) {
            assertEquals(before.x, after.x, 0.01f)
            assertEquals(before.y, after.y, 0.01f)
        }
        val huge = PhotoCropMath.pinch(next, 1000f, Offset.Zero, Offset.Zero, rotated, side, maxZoom)
        assertEquals(maxZoom, huge.scale, 0.0001f)
        val tiny = PhotoCropMath.pinch(next, 0.0001f, Offset.Zero, Offset.Zero, rotated, side, maxZoom)
        assertEquals(1f, tiny.scale, 0.0001f)
    }

    @Test fun theViewAndTheStateConvertBothWays() {
        val rotated = size(4000, 3000, 1)
        val state = ZoomState(2.5f, Offset(40f, -75f))
        val view = viewOf(state, 1, rotated, side, maxZoom)
        assertEquals(1, view.turns)
        assertEquals(2.5, view.zoom, 0.0001)
        val back = stateOf(view, rotated, side)
        assertEquals(state.scale, back.scale, 0.0001f)
        assertEquals(state.offset.x, back.offset.x, 0.01f)
        assertEquals(state.offset.y, back.offset.y, 0.01f)
    }

    @Test fun anOutOfRangeStateIsBroughtBackBeforeItBecomesAView() {
        val rotated = size(4000, 3000)
        val view = viewOf(ZoomState(9_999f, Offset(1e6f, -1e6f)), 4, rotated, side, maxZoom)
        assertEquals(0, view.turns)
        assertEquals(maxZoom.toDouble(), view.zoom, 0.001)
        assertTrue(view.cx in 0.0..1.0 && view.cy in 0.0..1.0)
    }

    // ---- against the Rust core

    @Test fun theViewAgreesWithTheCoreAfterPinchAndPan() {
        val host = HostRust("rovsti-crop")
        val (w, h) = 4000 to 3000
        val rotated = size(w, h)
        var state = PhotoCropMath.pinch(ZoomState.Fit, 2f, Offset.Zero, Offset.Zero, rotated, side, maxZoom)
        state = PhotoCropMath.pan(state, Offset(30f, -20f), rotated, side)
        val ours = viewOf(state, 0, rotated, side, maxZoom)

        var theirs = CropView(0, 1.0, 0.5, 0.5)
        runBlocking {
            theirs =
                (host.ops.photoCrop(PhotoCropRequest(w, h, theirs, "pinch", factor = 2.0)) as Outcome.Ok).value.view
            theirs =
                (
                    host.ops.photoCrop(
                        PhotoCropRequest(w, h, theirs, "pan", dx = 30.0, dy = -20.0, sidePx = side.toDouble()),
                    ) as Outcome.Ok
                ).value.view
        }
        assertEquals(theirs.zoom, ours.zoom, 0.0001)
        assertEquals(theirs.cx, ours.cx, 0.0005)
        assertEquals(theirs.cy, ours.cy, 0.0005)
    }

    @Test fun thePanLimitsAreTheCoresLimits() {
        val host = HostRust("rovsti-crop")
        val (w, h) = 3000 to 4000
        val rotated = size(w, h)
        val zoomed = PhotoCropMath.pinch(ZoomState.Fit, 3f, Offset.Zero, Offset.Zero, rotated, side, maxZoom)
        val ours =
            viewOf(
                PhotoCropMath.pan(zoomed, Offset(5_000f, 5_000f), rotated, side),
                0,
                rotated,
                side,
                maxZoom,
            )
        var theirs = CropView(0, 3.0, 0.5, 0.5)
        runBlocking {
            theirs =
                (
                    host.ops.photoCrop(
                        PhotoCropRequest(w, h, theirs, "pan", dx = 5_000.0, dy = 5_000.0, sidePx = side.toDouble()),
                    ) as Outcome.Ok
                ).value.view
        }
        assertEquals(theirs.cx, ours.cx, 0.0005)
        assertEquals(theirs.cy, ours.cy, 0.0005)
    }

    @Test fun rotateRightIsTheCoresRotate() {
        val host = HostRust("rovsti-crop")
        val turned =
            runBlocking {
                (
                    host.ops.photoCrop(
                        PhotoCropRequest(4000, 3000, CropView(0, 2.0, 0.3, 0.6), "rotate"),
                    ) as Outcome.Ok
                ).value.view
            }
        assertEquals(CropView(1, 1.0, 0.5, 0.5), turned)
        // After a turn our page is back at zoom 1, centred: the same view.
        assertEquals(turned, viewOf(ZoomState.Fit, 1, size(4000, 3000, 1), side, maxZoom))
    }

    @Test fun theChosenViewBecomesTheSquareTheCoreCuts() {
        val host = HostRust("rovsti-crop")
        val (w, h) = 4000 to 3000
        val rotated = size(w, h)
        val state = PhotoCropMath.pinch(ZoomState.Fit, 2f, Offset.Zero, Offset.Zero, rotated, side, maxZoom)
        val view = viewOf(state, 0, rotated, side, maxZoom)
        val answer = runBlocking { (host.ops.photoPlan(PhotoPlanRequest(w, h, view)) as Outcome.Ok).value }
        val plan = answer.plan
        assertNotNull(plan)
        assertNull(answer.error)
        // Zoom 2 of a 3000 px short side is a 1500 px square, centred: x = 2000 - 750, y = 1500 - 750.
        assertEquals(1500, plan!!.side)
        assertEquals(1250, plan.x)
        assertEquals(750, plan.y)
        assertEquals(720, plan.out)
        assertEquals(0, plan.turns)
        assertEquals(3000.0 / 96.0, answer.maxZoom, 0.01)
    }

    @Test fun aTooSmallPictureHasNoPlanAndTheCoreSaysWhy() {
        val host = HostRust("rovsti-crop")
        val answer = runBlocking { (host.ops.photoPlan(PhotoPlanRequest(80, 80)) as Outcome.Ok).value }
        assertNull(answer.plan)
        assertEquals("That image is too small for a contact photo.", answer.error)
    }

    @Test fun onlyImagesAreAcceptedAndCameraNamesFollowTheClock() {
        val host = HostRust("rovsti-crop")
        runBlocking {
            val jpeg = (host.ops.photoAccepts(PhotoAcceptsRequest("image/jpeg", 1234L)) as Outcome.Ok).value
            assertTrue(jpeg.accepts)
            assertEquals("camera-1234.jpg", jpeg.cameraFileName)
            assertTrue(!(host.ops.photoAccepts(PhotoAcceptsRequest("text/plain", 1L)) as Outcome.Ok).value.accepts)
            assertTrue(!(host.ops.photoAccepts(PhotoAcceptsRequest(null, 1L)) as Outcome.Ok).value.accepts)
        }
    }
}
