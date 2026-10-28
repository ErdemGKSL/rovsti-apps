package dev.rovsti.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val files = listOf(
            ContactPhotos.Cached("ancient", now - 30 * day),
            ContactPhotos.Cached("a", now - 3 * day),
            ContactPhotos.Cached("b", now - 2 * day),
            ContactPhotos.Cached("c", now - 1 * day),
        )
        assertEquals(listOf("ancient"), ContactPhotos.pruneVictims(files, now))
        assertEquals(listOf("ancient", "a"), ContactPhotos.pruneVictims(files, now, maxFiles = 2))
    }
}
