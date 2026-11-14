package dev.rosty.messages.conversation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.rosty.messages.inbox.RowAvatar
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.io.File

/** A short SMS thread for the screenshots: two days, both sides, a failed send, an MMS picture. */
internal object ConversationSample {
    private const val DAY = 86_400_000L
    const val NOW = 1_790_000_000_000L

    /** A small picture on disk, standing for an MMS part. */
    fun picture(
        dir: File,
        name: String = "mms-part.png",
        sky: Int = Color.rgb(90, 160, 220),
    ): File {
        val file = File(dir, name)
        if (!file.exists()) {
            val bmp = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(sky)
            c.drawCircle(300f, 90f, 50f, Paint().apply { color = Color.rgb(255, 214, 10) })
            c.drawRect(0f, 220f, 400f, 300f, Paint().apply { color = Color.rgb(60, 140, 80) })
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return file
    }

    fun bubbles(picture: File?): List<BubbleUi> =
        listOf(
            BubbleUi("sms:1", false, "Are we still on for tomorrow?", "18:02", atMs = NOW - DAY - 3_600_000),
            BubbleUi("sms:2", true, "Yes! Six at the station.", "18:05", BubbleState.Delivered, NOW - DAY - 3_400_000),
            BubbleUi("sms:3", true, "I'll bring the tickets.", "18:05", BubbleState.Delivered, NOW - DAY - 3_390_000),
            BubbleUi(
                "mms:4",
                false,
                "The view from here",
                "09:12",
                atMs = NOW - 5 * 3_600_000,
                media = persistentListOf(BubbleMedia(picture, "Photo", isImage = picture != null, aspect = 4f / 3f)),
            ),
            BubbleUi("sms:5", false, "Running ten minutes late, sorry", "13:58", atMs = NOW - 600_000),
            BubbleUi(
                "local:6",
                true,
                "No problem, take your time",
                "Not sent · Tap to retry",
                BubbleState.Failed,
                NOW - 300_000,
            ),
            BubbleUi("local:7", true, "See you soon", "Sending…", BubbleState.Sending, NOW - 10_000),
        )

    fun state(
        picture: File?,
        sim: String? = null,
        divider: Pair<String, String>? = null,
    ): ConversationUi {
        val labels = DayLabels(java.time.ZoneId.of("UTC"), java.util.Locale.US, true, "Today", "Yesterday")
        return ConversationUi(
            title = "Mert Kaya",
            subtitle = "+90 555 000 00 01",
            avatar = RowAvatar(colorKey = "mert"),
            items = withDays(bubbles(picture), labels, NOW, divider).toImmutableList(),
            loaded = true,
            hasOlder = true,
            composer = ComposerMode.Ready("Text message", sim, if (sim != null) "142 left · 1 SMS" else null),
        )
    }
}
