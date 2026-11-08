package dev.rovsti.design.list

import androidx.compose.ui.graphics.Color
import dev.rovsti.design.controls.badgeText
import dev.rovsti.design.theme.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarColorTest {
    @Test
    fun everyPaletteEntryReachesFourPointFiveWithWhite() {
        for ((index, color) in AvatarPalette.withIndex()) {
            val ratio = contrastRatio(Color.White, color)
            assertTrue("palette[$index] white contrast $ratio", ratio >= 4.5)
        }
    }

    @Test
    fun paletteHasEightDistinctColours() {
        assertEquals(8, AvatarPalette.size)
        assertEquals(8, AvatarPalette.toSet().size)
    }

    @Test
    fun colourIsStableAndIgnoresCaseAndOuterSpace() {
        assertEquals(avatarColor("Ayşe Yılmaz"), avatarColor("Ayşe Yılmaz"))
        assertEquals(avatarColor("Mehmet"), avatarColor("  mehmet "))
        assertTrue(avatarColor("anything") in AvatarPalette)
    }

    @Test
    fun namesSpreadOverTheWholePalette() {
        val names =
            listOf(
                "Ali",
                "Ayşe",
                "Mehmet",
                "Fatma",
                "Can",
                "Elif",
                "Deniz",
                "Zeynep",
                "Burak",
                "Selin",
                "Kemal",
                "Leyla",
                "Mina",
                "Omar",
                "Pia",
                "Sam",
            )
        val used = names.map { avatarColor(it) }.toSet()
        assertTrue("only ${used.size} colours used", used.size >= 5)
    }

    @Test
    fun differentKeysCanDiffer() {
        assertNotEquals(avatarColor("Ali"), avatarColor("Ayşe"))
    }

    @Test
    fun initials() {
        assertEquals("AY", initialsOf("Ayşe Yılmaz"))
        assertEquals("M", initialsOf("mehmet"))
        assertEquals("AC", initialsOf("  ada   lovelace  byron ".replace("byron", "clark")))
        assertEquals("", initialsOf("+90 532 000 00 00"))
        assertEquals("", initialsOf("   "))
        assertEquals("A", initialsOf("Ada 3"))
    }

    @Test
    fun badgeCapsAtNinetyNine() {
        assertEquals("7", badgeText(7))
        assertEquals("99", badgeText(99))
        assertEquals("99+", badgeText(100))
    }
}
