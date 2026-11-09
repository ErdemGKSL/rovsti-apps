package dev.rovsti.platform.contacts

import dev.rovsti.core.model.contacts.PhotoPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Moved from apps/phone/.../src/test/java/dev/rovsti/bridge/ContactPhotosTest.kt (the plan cases use [PhotoPlan]). */
class ContactPhotosTest {
    @Test
    fun sampleSizeKeepsAtLeastTheTarget() {
        assertEquals(1, ContactPhotos.sampleSize(96, 96, 128))
        assertEquals(1, ContactPhotos.sampleSize(200, 300, 128))
        assertEquals(2, ContactPhotos.sampleSize(256, 400, 128))
        assertEquals(4, ContactPhotos.sampleSize(1024, 768, 128))
        assertEquals(1, ContactPhotos.sampleSize(500, 500, 0))
    }

    @Test
    fun outputIsNeverUpscaled() {
        assertEquals(96, ContactPhotos.outputSide(96, 120, 128))
        assertEquals(128, ContactPhotos.outputSide(512, 512, 128))
        assertEquals(1, ContactPhotos.outputSide(0, 0, 128))
    }

    @Test
    fun cacheNamesAreStableAndDistinct() {
        val a = ContactPhotos.cacheName("content://com.android.contacts/contacts/1/photo", 128)
        assertEquals(a, ContactPhotos.cacheName("content://com.android.contacts/contacts/1/photo", 128))
        assertNotEquals(a, ContactPhotos.cacheName("content://com.android.contacts/contacts/2/photo", 128))
        assertNotEquals(a, ContactPhotos.cacheName("content://com.android.contacts/contacts/1/photo", 64))
        assertTrue(a.matches(Regex("p-?[0-9a-f]+_128\\.png")))
    }

    @Test
    fun pruneDropsOldThenTheOldestBeyondTheCap() {
        val day = 24 * 60 * 60 * 1000L
        val now = 100 * day
        val files =
            listOf(
                ContactPhotos.Cached("ancient", now - 30 * day),
                ContactPhotos.Cached("a", now - 3 * day),
                ContactPhotos.Cached("b", now - 2 * day),
                ContactPhotos.Cached("c", now - 1 * day),
            )
        assertEquals(listOf("ancient"), ContactPhotos.pruneVictims(files, now))
        assertEquals(listOf("ancient", "a"), ContactPhotos.pruneVictims(files, now, maxFiles = 2))
    }

    private fun plan(
        x: Int = 0,
        y: Int = 0,
        side: Int = 100,
        out: Int = 100,
        turns: Int = 0,
    ) = PhotoPlan(x = x, y = y, side = side, out = out, turns = turns)

    @Test
    fun aPlanScalesOntoTheDecodedImageAndStaysInside() {
        // The header said 4000 x 3000; the decode was shrunk to 2000 x 1500.
        assertEquals(
            ContactPhotos.Crop(x = 250, y = 0, side = 1000, out = 512),
            ContactPhotos.cropFor(plan(x = 500, y = 0, side = 2000, out = 512), 4000, 3000, 2000, 1500),
        )
        // A plan that overshoots is clamped to the image, never past it.
        val clamped = ContactPhotos.cropFor(plan(x = 9000, y = 9000, side = 9000, out = 5000), 100, 100, 100, 100)!!
        assertEquals(ContactPhotos.Crop(x = 0, y = 0, side = 100, out = 720), clamped)
    }

    @Test
    fun aPlanWithNothingToCropIsNull() {
        assertNull(ContactPhotos.cropFor(plan(side = 0), 100, 100, 100, 100))
        assertNull(ContactPhotos.cropFor(plan(), 0, 100, 100, 100))
    }

    @Test
    fun turnsAreReducedToAQuarterCircle() {
        assertEquals(0, ContactPhotos.quarterTurns(plan(turns = 0)))
        assertEquals(3, ContactPhotos.quarterTurns(plan(turns = 3)))
        assertEquals(1, ContactPhotos.quarterTurns(plan(turns = 5)))
        assertEquals(3, ContactPhotos.quarterTurns(plan(turns = -1)))
    }
}
