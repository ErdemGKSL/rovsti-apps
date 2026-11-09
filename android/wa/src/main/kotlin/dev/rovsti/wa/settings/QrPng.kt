package dev.rovsti.wa.settings

import android.graphics.Bitmap
import dev.rovsti.core.model.qr.QrMatrix
import java.io.File

/** Writes a [QrMatrix] as a PNG (black modules on white, a four-module quiet zone) for the share sheet. */
internal object QrPng {
    /** Pixels per module: 12 gives a scannable 300 px image for the smallest codes and a sharp one for large ones. */
    private const val PIXELS_PER_MODULE = 12
    private const val QUIET_MODULES = 4

    fun write(
        matrix: QrMatrix,
        file: File,
    ) {
        val luma = matrix.toLuma(PIXELS_PER_MODULE, QUIET_MODULES)
        val dim = (matrix.side + 2 * QUIET_MODULES) * PIXELS_PER_MODULE
        val pixels = IntArray(dim * dim) { i -> if (luma[i].toInt() == 0) BLACK else WHITE }
        val bitmap = Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.ARGB_8888)
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
