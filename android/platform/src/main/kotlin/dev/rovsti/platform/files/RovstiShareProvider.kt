package dev.rovsti.bridge

// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/MediaShare.kt (E3.8a). It stays in package dev.rovsti.bridge:
// the manifests of the update-in-place apps name this class (ARCH 7.2; FqcnTest pins it).

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import dev.rovsti.platform.files.MediaShare
import java.io.File

/**
 * Serves the files in `cache/share` read-only to the share target. Only `camera-<digits>.jpg` may be opened for
 * writing (the camera app writes the photo there). Names with a separator or `..` are refused.
 */
class RovstiShareProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val write = mode == "w" || mode == "wt"
        if (mode != "r" && !write) return null
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (!MediaShare.isSafeName(name)) return null
        if (write && !MediaShare.writable(name)) return null
        val file = File(MediaShare.dir(ctx), name)
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
