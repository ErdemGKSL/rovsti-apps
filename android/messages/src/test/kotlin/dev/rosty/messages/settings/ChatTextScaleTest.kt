package dev.rosty.messages.settings

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import dev.rosty.core.model.messages.ChatTextSize
import dev.rosty.messages.settings.textsize.ChatTextScale
import dev.rosty.messages.settings.textsize.scaledBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** M34: the factors and rounding of `rosty_core::text_scale` (its Rust tests, mirrored). */
class ChatTextScaleTest {
    @Test fun factorsInOrder() {
        assertEquals(listOf(0.875f, 1f, 1.15f, 1.3f), ChatTextScale.all.map(ChatTextScale::factor))
    }

    @Test fun defaultKeepsTheBodyMetricsAndSizesGrow() {
        val body = TextStyle(fontSize = 16.sp, lineHeight = 22.sp)
        assertSame(body, body.scaledBy(ChatTextScale.factor(ChatTextSize.Default)))
        val sizes = ChatTextScale.all.map { body.scaledBy(ChatTextScale.factor(it)).fontSize.value }
        assertTrue(sizes.zipWithNext().all { (a, b) -> a < b })
        // Rounded to half a unit like `ChatTextSize::scaled`: 16 * 1.15 = 18.4 -> 18.5.
        assertEquals(18.5f, ChatTextScale.scaled(16f, 1.15f))
        assertEquals(14f, ChatTextScale.scaled(16f, 0.875f))
    }
}
