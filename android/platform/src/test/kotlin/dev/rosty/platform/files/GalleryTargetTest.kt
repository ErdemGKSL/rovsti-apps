package dev.rosty.platform.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a saved WhatsApp file goes in the phone's collections (E6-WA R3). */
class GalleryTargetTest {
    @Test fun photosAreMoviesAreAndEverythingElseIsADownload() {
        assertEquals(GalleryTarget.Pictures, GalleryTarget.of("image/jpeg"))
        assertEquals(GalleryTarget.Pictures, GalleryTarget.of("image/webp"))
        assertEquals(GalleryTarget.Pictures, GalleryTarget.of("IMAGE/GIF"))
        assertEquals(GalleryTarget.Movies, GalleryTarget.of("video/mp4"))
        assertEquals(GalleryTarget.Downloads, GalleryTarget.of("application/pdf"))
        assertEquals(GalleryTarget.Downloads, GalleryTarget.of("audio/ogg"))
        assertEquals(GalleryTarget.Downloads, GalleryTarget.of(""))
        assertEquals("Pictures/Rosty", GalleryTarget.Pictures.relativePath)
        assertEquals("Movies/Rosty", GalleryTarget.Movies.relativePath)
        assertEquals("Download/Rosty", GalleryTarget.Downloads.relativePath)
    }

    @Test fun aNameKeepsItsScriptAndLosesSeparators() {
        assertEquals("Feribot tarifesi (şubat).pdf", GalleryTarget.displayName("Feribot tarifesi (şubat).pdf"))
        assertEquals("a_b_c.jpg", GalleryTarget.displayName("a/b\\c.jpg"))
        assertEquals("x.jpg", GalleryTarget.displayName("  ..x.jpg  "))
        assertNull(GalleryTarget.displayName(" ... "))
        assertEquals(120, GalleryTarget.displayName("n".repeat(300))?.length)
    }
}
