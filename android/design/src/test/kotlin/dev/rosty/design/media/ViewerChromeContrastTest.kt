package dev.rosty.design.media

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import dev.rosty.design.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer chrome is white on a black shade over the photo, in both app themes (its colours do not read the theme).
 * For a white, a beige and a dark photo: the title reaches 4.5:1, the back chevron and the action glyphs 3:1, and the
 * shade of a dark photo is the floor.
 */
class ViewerChromeContrastTest {
    private val photos =
        mapOf(
            "white" to Color.White,
            "beige" to Color(0xFFEBC9A0),
            "dark" to Color(0xFF1B1B22),
        )

    private fun shadeOver(photo: Color): Float =
        ViewerScrim.alphaFor(
            dev.rosty.design.theme
                .relativeLuminance(photo),
        )

    private fun shaded(photo: Color) = Color.Black.copy(alpha = shadeOver(photo)).compositeOver(photo)

    @Test
    fun titleReadsOnEveryPhoto() {
        for ((name, photo) in photos) {
            val ratio = contrastRatio(ViewerChromeColors.Title, shaded(photo))
            assertTrue("title on $name photo: $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun backChevronReadsOnItsDarkDisc() {
        for ((name, photo) in photos) {
            // The glass may show the photo unshaded and lightened: the disc must carry the chevron on its own too.
            for (under in listOf(shaded(photo), photo, Color.White)) {
                val disc = ViewerChromeColors.BackDisc.compositeOver(under)
                val ratio = contrastRatio(ViewerChromeColors.Glyph, disc)
                assertTrue("back chevron on $name photo: $ratio", ratio >= 3.0)
            }
        }
    }

    @Test
    fun actionGlyphsReadOnTheBottomShade() {
        for ((name, photo) in photos) {
            val capsule = ViewerChromeColors.ActionsDisc.compositeOver(Color.White)
            assertTrue(contrastRatio(ViewerChromeColors.Glyph, capsule) >= 3.0)
            val ratio = contrastRatio(ViewerChromeColors.Glyph, shaded(photo))
            assertTrue("action glyphs on $name photo: $ratio", ratio >= 3.0)
        }
    }

    @Test
    fun darkPhotoShadeIsTheFloor() {
        assertEquals(ViewerScrim.FLOOR, shadeOver(photos.getValue("dark")), 0f)
    }

    @Test
    fun lightPhotosGetMoreThanTheFloor() {
        assertTrue(shadeOver(photos.getValue("white")) > ViewerScrim.FLOOR)
        assertTrue(shadeOver(photos.getValue("beige")) > ViewerScrim.FLOOR)
    }
}
