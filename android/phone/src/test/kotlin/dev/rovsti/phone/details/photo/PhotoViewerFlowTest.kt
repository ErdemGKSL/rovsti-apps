package dev.rovsti.phone.details.photo

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import dev.rovsti.design.media.PhotoViewerSemantics
import dev.rovsti.design.media.PhotoViewerTags
import dev.rovsti.phone.details.DetailsSample
import dev.rovsti.phone.details.presentPerson
import dev.rovsti.phone.recents.HostedRecents
import dev.rovsti.phone.recents.RecentsHarness
import dev.rovsti.phone.recents.RecentsStrings
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * E5.13 (U1): the avatar of the Person sheet opens the shared-bounds viewer, a drag down dismisses it and the photo
 * flies back into the avatar's rectangle. The Compose clock is manual (`autoAdvance = false`), so every frame of the
 * flight is ours to read.
 */
@OptIn(ExperimentalTestApi::class)
@Config(sdk = [35])
class PhotoViewerFlowTest : ShotTest() {
    override val imageProducer: (Any, Int, Int) -> Bitmap? = { _, w, h -> portrait(w, h) }

    private lateinit var harness: RecentsHarness
    private lateinit var opener: TestPhotoOpener

    private val recentsStrings get() = RecentsStrings(compose.activity.resources)

    private fun openSheet(photo: PersonPhoto? = SamplePhoto) {
        harness = RecentsHarness()
        opener = TestPhotoOpener(harness.overlays)
        composeFrame { WithOpener(opener) { HostedRecents(harness, recentsStrings) } }
        advance(500)
        compose.runOnUiThread { harness.overlays.presentPerson(DetailsSample.person(photo = photo)) }
        advance(1_600)
    }

    private fun viewerOpen() = compose.onAllNodes(hasTestTag(PhotoViewerTags.Root)).fetchSemanticsNodes().isNotEmpty()

    private fun viewerNode(): SemanticsNode = compose.onNodeWithTag(PhotoViewerTags.Root).fetchSemanticsNode()

    private fun avatarBounds(): Rect = compose.onNodeWithTag(PERSON_AVATAR_TAG).fetchSemanticsNode().boundsInRoot

    private fun photoBounds(): Rect = compose.onNodeWithTag(PhotoViewerTags.Photo).fetchSemanticsNode().boundsInRoot

    @Test fun theAvatarOfTheSheetOpensTheViewerOnThePhoto() {
        openSheet()
        assertFalse(viewerOpen())
        compose.onNodeWithTag(PERSON_AVATAR_TAG).performClick()
        advance(1_000)
        assertTrue(viewerOpen())
        assertEquals(listOf(SamplePhoto), opener.opened)
        assertEquals(0, viewerNode().config[PhotoViewerSemantics.Page])
        assertEquals(1f, viewerNode().config[PhotoViewerSemantics.Dim], 0.02f)
        assertEquals(1f, viewerNode().config[PhotoViewerSemantics.Zoom], 0f)
    }

    @Test fun theFlightStartsAtTheAvatarsRectangle() {
        openSheet()
        val avatar = avatarBounds()
        compose.onNodeWithTag(PERSON_AVATAR_TAG).performClick()
        advance(16)
        val first = photoBounds()
        assertTrue("starts on the avatar: $first vs $avatar", centreDistance(first, avatar) < 40f)
        assertTrue("and is about its size", abs(first.width - avatar.width) < avatar.width * 0.6f)
        advance(1_000)
        val rest = photoBounds()
        assertTrue("the photo grew to fill the screen width: $rest", rest.width > avatar.width * 3f)
    }

    @Test fun aDragDownDismissesAndThePhotoReturnsToTheAvatar() {
        openSheet()
        val avatar = avatarBounds()
        compose.onNodeWithTag(PERSON_AVATAR_TAG).performClick()
        advance(1_000)
        compose.onNodeWithTag(PhotoViewerTags.Root).performTouchInput {
            down(center)
            moveBy(Offset(0f, height * 0.06f), 60)
            moveBy(Offset(0f, height * 0.06f), 60)
            moveBy(Offset(0f, height * 0.2f), 60)
        }
        advance(50)
        compose.onNodeWithTag(PhotoViewerTags.Root).performTouchInput {
            advanceEventTime(400)
            up()
        }
        // Frame by frame: the last rectangle before the entry is dropped is where the photo ends up.
        var last: Rect? = null
        var frames = 0
        while (viewerOpen() && frames < 120) {
            runCatching { last = photoBounds() }
            advance(16)
            frames++
        }
        assertFalse("closed", viewerOpen())
        val end = checkNotNull(last)
        assertTrue("it flew back into the avatar: $end vs $avatar", centreDistance(end, avatar) < 60f)
        assertTrue("and shrank to about its size: ${end.width}", end.width < avatar.width * 2.2f)
    }

    @Test fun aSheetWithoutAnOpenerHasAPlainAvatar() {
        harness = RecentsHarness()
        composeFrame { HostedRecents(harness, recentsStrings) }
        advance(500)
        compose.runOnUiThread { harness.overlays.presentPerson(DetailsSample.person(photo = SamplePhoto)) }
        advance(1_600)
        assertTrue(compose.onAllNodes(hasTestTag(PERSON_AVATAR_TAG)).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aNumberThatIsNotInTheBookHasNoPhotoToOpen() {
        openSheet(photo = null)
        assertTrue(compose.onAllNodes(hasTestTag(PERSON_AVATAR_TAG)).fetchSemanticsNodes().isEmpty())
        assertNull(opener.opened.firstOrNull())
    }

    private fun centreDistance(
        a: Rect,
        b: Rect,
    ): Float = (a.center - b.center).getDistance()
}
