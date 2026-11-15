package dev.rosty.contacts.cards

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Turns a card photo the Rust renderer cannot open (a `content://` URI of the contacts provider or the X.1 picture
 * provider) into a JPEG file under `cacheDir/share/cards/photos/`, at most [MAX_SIDE] px a side, so a 4000 x 3000
 * camera photo is downsized before the render. Null when the picture cannot be read: the card draws initials then.
 */
internal class CardPhotos(
    private val context: Context,
) {
    suspend fun file(uri: String): String? =
        withContext(Dispatchers.IO) {
            runCatching { copy(uri) }.getOrNull()
        }

    private fun copy(uri: String): String? {
        val dir = File(cardsDir(context), "photos").apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray())
        val out = File(dir, "photo-" + digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) } + ".jpg")
        val resolver = context.contentResolver
        val parsed = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(parsed)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val bitmap =
            resolver.openInputStream(parsed)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        return out.absolutePath
    }

    private companion object {
        const val MAX_SIDE = 1024
        const val JPEG_QUALITY = 90
        const val HASH_BYTES = 8
    }
}
