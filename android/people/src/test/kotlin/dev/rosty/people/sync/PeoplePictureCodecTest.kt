package dev.rosty.people.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S12: a stored picture's short side is exactly the target; a small one is left alone. */
class PeoplePictureCodecTest {
    @Test fun theShortSideBecomesTheTarget() {
        assertEquals(512 to 512, scaledSize(640, 640, 512))
        assertEquals(512 to 683, scaledSize(480 * 2, 640 * 2, 512))
        assertEquals(683 to 512, scaledSize(1280, 960, 512))
    }

    @Test fun aSmallPictureIsKept() {
        assertNull(scaledSize(96, 96, 512))
        assertNull(scaledSize(512, 900, 512))
    }

    @Test fun theSyncStoresSharpPictures() {
        // The largest avatar showing a cached picture is 112 dp: 448 px at 4x.
        assertEquals(true, PeopleSyncRunner.PICTURE_PX >= 448)
    }
}
