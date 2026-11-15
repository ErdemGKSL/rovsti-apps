package dev.rosty.bridge

// Ported from crates/rosty_android/kotlin/dev/rosty/bridge/MediaShare.kt (E3.8a). It stays in package dev.rosty.bridge:
// the manifests of the update-in-place apps name this class (ARCH 7.2; FqcnTest pins it).

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.rosty.platform.files.MediaShare

/**
 * Serves the files in `cache/share` (and `cache/share/cards`, X.2) read-only to the share target. Only `camera-<digits>.jpg` may be opened for
 * writing (the camera app writes the photo there). Names with a separator or `..` are refused.
 */
class RostyShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val write = mode == "w" || mode == "wt"
        if (mode != "r" && !write) return null
        val ctx = context ?: return null
        val file = MediaShare.resolve(ctx, uri) ?: return null
        val name = file.name
        if (write && (!MediaShare.writable(name) || file.parentFile?.name == MediaShare.CARDS)) return null
        if (!file.isFile) return null
        val flags =
            if (write) {
                ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_TRUNCATE
            } else {
                ParcelFileDescriptor.MODE_READ_ONLY
            }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun query(
        uri: Uri,
        p: Array<out String>?,
        s: String?,
        a: Array<out String>?,
        o: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = MediaShare.mimeFor(uri.lastPathSegment.orEmpty())

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        s: String?,
        a: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        v: ContentValues?,
        s: String?,
        a: Array<out String>?,
    ): Int = 0
}
