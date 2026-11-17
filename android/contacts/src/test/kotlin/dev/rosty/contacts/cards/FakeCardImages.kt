package dev.rosty.contacts.cards

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import dev.rosty.design.media.AnimatedImageSource
import dev.rosty.design.media.OneShotImage
import java.io.File

/**
 * A GIF stand-in for the tests: it counts starts and releases and shows a frozen frame. [frame] is what an animated
 * request draws (a Rust frame copied into the test resources); [still] is what a reduce-motion request draws.
 */
internal class FakeCardImages(
    private val frame: File? = null,
    private val still: File? = null,
) : AnimatedImageSource {
    val opened = mutableListOf<Pair<String, Boolean>>()
    var starts = 0
    var releases = 0

    override suspend fun open(
        path: String,
        animate: Boolean,
    ): OneShotImage {
        opened += path to animate
        val file = (if (animate) frame else still) ?: File(path)
        val drawable = BitmapDrawable(null, BitmapFactory.decodeFile(file.path))
        return object : OneShotImage {
            override val drawable = drawable

            override fun start() {
                if (animate) starts++
            }

            override fun release() {
                releases++
            }
        }
    }
}
