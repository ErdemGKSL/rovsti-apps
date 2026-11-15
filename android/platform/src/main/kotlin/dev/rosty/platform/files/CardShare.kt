package dev.rosty.platform.files

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** The file formats a finished share card can leave the app in. */
enum class CardFormat(
    val extension: String,
    val mime: String,
) {
    Jpeg("jpg", "image/jpeg"),
    Png("png", "image/png"),
    Gif("gif", "image/gif"),
    WebP("webp", "image/webp"),
    ;

    val animatedCapable: Boolean get() = this == Gif || this == WebP
}

/** A card copied into `cache/share/cards`: the provider [uri], the [mime] type and the [file] behind it. */
data class StagedCard(
    val uri: Uri,
    val mime: String,
    val file: File,
    val format: CardFormat,
)

/**
 * Share and save for finished cards (X.2 step 6). A card is written to `cacheDir/share/cards/<hash>.<ext>` and served
 * by `RostyShareProvider` with a read grant. Stills leave as JPEG at quality [JPEG_QUALITY], or PNG when the picture has
 * transparent pixels; animated cards leave as GIF, or as animated WebP when that is what the core wrote (see [prefersWebP]).
 * The folder keeps at most [MAX_FILES] files (the oldest go first).
 */
object CardShare {
    const val MAX_FILES = 20
    const val JPEG_QUALITY = 88

    /** Messaging apps known to play an animated WebP in a chat; every other target gets the GIF. */
    private val WEBP_LOOPERS =
        setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thoughtcrime.securesms")

    /** Pure: whether the chosen share target [packageName] is known to play animated WebP. */
    fun prefersWebP(packageName: String?): Boolean = packageName != null && packageName in WEBP_LOOPERS

    /** Pure: the container format of [head] (the first bytes of a file); null when it is none of GIF, WebP, PNG, JPEG. */
    fun sniff(head: ByteArray): CardFormat? =
        when {
            head.startsWith("GIF8") -> CardFormat.Gif

            head.size >= WEBP_HEAD &&
                head.startsWith(
                    "RIFF",
                ) && String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> CardFormat.WebP

            head.size >= PNG_HEAD && head[0] == 0x89.toByte() && head.startsWith("PNG", 1) -> CardFormat.Png

            head.size >= 3 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> CardFormat.Jpeg

            else -> null
        }

    /** Pure: JPEG for a picture without transparency, else PNG. */
    fun stillFormat(hasTransparency: Boolean): CardFormat = if (hasTransparency) CardFormat.Png else CardFormat.Jpeg

    /** Whether any pixel of [bitmap] is not fully opaque. */
    fun hasTransparency(bitmap: Bitmap): Boolean {
        if (!bitmap.hasAlpha()) return false
        val row = IntArray(bitmap.width)
        for (y in 0 until bitmap.height) {
            bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
            if (row.any { (it ushr ALPHA_SHIFT) != OPAQUE }) return true
        }
        return false
    }

    /** Deletes the oldest files of [dir] until at most [keep] remain; returns how many went. */
    fun prune(
        dir: File,
        keep: Int = MAX_FILES,
    ): Int {
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return 0
        val surplus = files.size - keep
        if (surplus <= 0) return 0
        return files.take(surplus).count { it.delete() }
    }

    /**
     * Copies the finished card at [path] into the cards folder and returns it with its provider URI. A still is
     * re-encoded (JPEG, or PNG when transparent); an animated card keeps its bytes (GIF, or WebP). [asStill] turns a
     * GIF or WebP into its first frame. Null when the file is missing or cannot be written.
     */
    fun stage(
        context: Context,
        path: String,
        asStill: Boolean = false,
        now: () -> Long = System::currentTimeMillis,
    ): StagedCard? {
        val source = File(path)
        if (!source.isFile) return null
        val dir = MediaShare.cardsDir(context)
        return try {
            val head = source.inputStream().use { it.readNBytes(HEAD) }
            val format = sniff(head) ?: return null
            val (bytes, out) =
                if (format.animatedCapable && !asStill) {
                    // The bytes stay as the core wrote them (GIF by default, animated WebP when the core was asked for one).
                    source.readBytes() to format
                } else {
                    encodeStill(source) ?: return null
                }
            val name = "${hash(bytes)}.${out.extension}"
            val target = File(dir, name)
            if (!target.isFile) target.writeBytes(bytes)
            target.setLastModified(now())
            prune(dir)
            StagedCard(MediaShare.cardUriFor(context, name), out.mime, target, out)
        } catch (_: IOException) {
            null
        }
    }

    private fun encodeStill(source: File): Pair<ByteArray, CardFormat>? {
        val bitmap = BitmapFactory.decodeFile(source.path) ?: return null
        return try {
            val format = stillFormat(hasTransparency(bitmap))
            val out = java.io.ByteArrayOutputStream()
            val ok =
                if (format == CardFormat.Jpeg) {
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                } else {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            if (ok) out.toByteArray() to format else null
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * The `ACTION_SEND` chooser for the staged card [uri]: its [mime] type, a read grant and a clip so the grant reaches
     * the chosen target (the clip is what the system reads the grant from).
     */
    fun shareIntent(
        uri: Uri,
        mime: String,
        title: String,
    ): Intent {
        val chooser = ShareIntents.image(uri, mime, title)
        val clip = ClipData.newRawUri(title, uri)
        chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.clipData = clip
        chooser.clipData = clip
        return chooser
    }

    private fun hash(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .take(HASH_BYTES)
            .joinToString("") { "%02x".format(it) }

    private fun ByteArray.startsWith(
        text: String,
        at: Int = 0,
    ): Boolean = size >= at + text.length && text.indices.all { this[at + it] == text[it].code.toByte() }

    private const val HEAD = 16
    private const val WEBP_HEAD = 12
    private const val PNG_HEAD = 8
    private const val HASH_BYTES = 8
    private const val ALPHA_SHIFT = 24
    private const val OPAQUE = 0xFF
}
