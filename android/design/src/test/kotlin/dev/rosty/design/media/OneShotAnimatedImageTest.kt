package dev.rosty.design.media

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.rosty.design.motion.LocalReduceMotion
import dev.rosty.testing.ShotTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

/** A fake picture that counts what the composable does to it. */
internal class FakeOneShotImage : OneShotImage {
    override val drawable = BitmapDrawable(null, Bitmap.createBitmap(8, 10, Bitmap.Config.ARGB_8888))
    var starts = 0
    var releases = 0

    override fun start() {
        starts++
    }

    override fun release() {
        releases++
    }
}

internal class FakeSource(
    val image: FakeOneShotImage = FakeOneShotImage(),
) : AnimatedImageSource {
    val asked = mutableListOf<Boolean>()

    override suspend fun open(
        path: String,
        animate: Boolean,
    ): OneShotImage {
        asked += animate
        return image
    }
}

@Config(sdk = [35])
class OneShotAnimatedImageTest : ShotTest() {
    private fun settle() = repeat(3) { advance(100) }

    @Test fun startIsCalledOncePerKey() {
        val source = FakeSource()
        var key by mutableStateOf("a")
        composeFrame {
            CompositionLocalProvider(LocalAnimatedImageSource provides source) {
                OneShotAnimatedImage("/x.gif", "card", Modifier.size(40.dp), playKey = key)
            }
        }
        settle()
        assertEquals(1, source.image.starts)
        repeat(3) { advance(300) }
        assertEquals("a recomposition does not restart it", 1, source.image.starts)
        key = "b"
        settle()
        assertEquals("a new key replays", 2, source.image.starts)
    }

    @Test fun underReduceMotionItNeverStartsAndAsksForTheStill() {
        val source = FakeSource()
        composeFrame {
            CompositionLocalProvider(LocalAnimatedImageSource provides source, LocalReduceMotion provides true) {
                OneShotAnimatedImage("/x.gif", "card", Modifier.size(40.dp))
            }
        }
        settle()
        assertEquals(0, source.image.starts)
        assertEquals(listOf(false), source.asked)
    }

    @Test fun theDrawableIsReleasedOnDispose() {
        val source = FakeSource()
        var shown by mutableStateOf(true)
        composeFrame {
            CompositionLocalProvider(LocalAnimatedImageSource provides source) {
                if (shown) OneShotAnimatedImage("/x.gif", "card", Modifier.size(40.dp))
            }
        }
        settle()
        assertFalse(source.image.releases > 0)
        shown = false
        settle()
        assertEquals(1, source.image.releases)
        assertTrue("the callback is detached", source.image.drawable.callback == null)
    }

    @Test fun theDrawableDrawsThroughItsCallback() {
        val source = FakeSource()
        composeFrame {
            CompositionLocalProvider(LocalAnimatedImageSource provides source) {
                OneShotAnimatedImage("/x.gif", "card", Modifier.size(40.dp))
            }
        }
        settle()
        assertTrue("the composable listens for frames", source.image.drawable.callback != null)
    }
}
