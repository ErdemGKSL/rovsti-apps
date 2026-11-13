package dev.rovsti.platform.calllog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** U55: the prefix ranges of the blocklist, as pure rules and in the in-memory store. */
class BlockPrefixTest {
    @Test fun aRangeIsParsedToTheDigitsAndKeepsTheOwnersGrouping() {
        val valid = BlockPrefix.parse(" +90 (850) ") as BlockPrefix.Parsed.Valid
        assertEquals("+90 850", valid.display)
        assertEquals("+90850", valid.key)
        assertEquals(
            "a trailing star is the display, not input",
            "0850",
            (BlockPrefix.parse("0850 *") as BlockPrefix.Parsed.Valid).key,
        )
    }

    @Test fun lettersDialCodesAndStrayPlusSignsAreInvalid() {
        for (typed in listOf("", "abc", "*21#", "90+850", "+90 8x0")) {
            assertEquals(typed, BlockPrefix.Parsed.Invalid, BlockPrefix.parse(typed))
        }
    }

    @Test fun fewerThanThreeDigitsIsTooShort() {
        assertEquals(BlockPrefix.Parsed.TooShort, BlockPrefix.parse("85"))
        assertEquals(BlockPrefix.Parsed.TooShort, BlockPrefix.parse("+9"))
        assertTrue(BlockPrefix.parse("850") is BlockPrefix.Parsed.Valid)
    }

    @Test fun anInternationalRangeMatchesPlusAndDoubleZeroForms() {
        val key = BlockPrefix.key("+90 850")
        assertTrue(BlockPrefix.matches(key, "+90 850 333 44 55"))
        assertTrue(BlockPrefix.matches(key, "+908503334455"))
        assertTrue(BlockPrefix.matches(key, "00908503334455"))
        assertFalse("the national form is another range", BlockPrefix.matches(key, "0850 333 44 55"))
        assertFalse(BlockPrefix.matches(key, "+90 532 111 22 33"))
    }

    @Test fun aNationalRangeMatchesTheDigitsAsDialled() {
        val key = BlockPrefix.key("0850")
        assertTrue(BlockPrefix.matches(key, "0850 333 44 55"))
        assertFalse(BlockPrefix.matches(key, "+90 850 333 44 55"))
        assertFalse(BlockPrefix.matches(key, ""))
        assertFalse(BlockPrefix.matches("", "0850"))
    }

    @Test fun theStoreKeepsOrderAndRefusesADuplicateWhateverTheSeparators() {
        val store = MemoryPrefixStore()
        assertTrue(store.add("+90 850"))
        assertFalse(store.add("+90850"))
        assertTrue(store.add("+44 7700"))
        assertEquals(listOf("+90 850", "+44 7700"), store.all())
        assertTrue(store.covers("+90 850 111 22 33"))
        assertFalse(store.covers("+49 151 2345678"))
        assertTrue(store.remove("+90-850"))
        assertEquals(listOf("+44 7700"), store.all())
        assertFalse(store.remove("+90 850"))
    }
}
