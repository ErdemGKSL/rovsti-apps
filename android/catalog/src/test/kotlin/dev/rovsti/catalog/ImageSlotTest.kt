package dev.rovsti.catalog

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.media.RowImage
import dev.rovsti.design.media.slotPixels
import dev.rovsti.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1.20: images are requested at the size of their slot and without crossfade in rows; a zero slot cannot crash. */
@OptIn(ExperimentalTestApi::class)
class ImageSlotTest : ShotTest() {
    private val density get() = compose.activity.resources.displayMetrics.density

    @Test
    fun anAvatarPhotoAsksForItsSlotInPixels() {
        composeFrame { Avatar(name = "Ada Lovelace", size = 44.dp, photoModel = "content://contacts/1") }
        advance(300)
        val px = (44 * density).toInt()
        val request = images.requests.single { it.data == "content://contacts/1" }
        assertEquals(px, request.widthPx)
        assertEquals(px, request.heightPx)
        assertFalse("no crossfade in rows", request.crossfade)
    }

    @Test
    fun aRowImageUsesItsOwnSize() {
        composeFrame {
            Column {
                RowImage("content://a", 120.dp)
                RowImage("content://b", 56.dp)
            }
        }
        advance(300)
        assertEquals((120 * density).toInt(), images.requests.single { it.data == "content://a" }.widthPx)
        assertEquals((56 * density).toInt(), images.requests.single { it.data == "content://b" }.widthPx)
    }

    @Test
    fun aSlotOfZeroAsksForOnePixelAndDoesNotCrash() {
        assertEquals(
            1,
            slotPixels(
                0.dp,
                androidx.compose.ui.unit
                    .Density(2.625f),
            ),
        )
        composeFrame { RowImage("content://zero", 0.dp) }
        advance(300)
        assertTrue(images.requests.none { it.widthPx <= 0 && it.data == "content://zero" })
    }

    @Test
    fun withoutAPhotoTheInitialsStay() {
        composeFrame { Avatar(name = "Ada Lovelace", size = 44.dp, photoModel = null) }
        advance(300)
        assertTrue(images.requests.isEmpty())
    }
}
