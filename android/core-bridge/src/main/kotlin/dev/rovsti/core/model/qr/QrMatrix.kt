package dev.rovsti.core.model.qr

import kotlin.io.encoding.Base64

/**
 * The modules of a QR code as `qr.encode` answers them: [side] x [side] bits, row-major, the first module of the first
 * row in the most significant bit of the first byte, a set bit a dark module. The quiet zone is not part of the matrix.
 * Immutable; build it with [of] or [fromAnswer].
 */
class QrMatrix private constructor(
    val side: Int,
    private val bits: ByteArray,
) {
    /** Whether the module at column [x], row [y] is dark; false outside the matrix. */
    operator fun get(
        x: Int,
        y: Int,
    ): Boolean {
        if (x !in 0 until side || y !in 0 until side) return false
        val i = y * side + x
        return bits[i / 8].toInt() and (0x80 ushr (i % 8)) != 0
    }

    /** Number of dark modules. */
    val darkCount: Int
        get() {
            var n = 0
            for (y in 0 until side) for (x in 0 until side) if (this[x, y]) n++
            return n
        }

    /**
     * The matrix as an 8-bit gray image for `qr.decode` or a bitmap: [pixelsPerModule] pixels per module and [quiet]
     * light modules on every side; dark is 0, light 255. Row-major, `(side + 2 * quiet) * pixelsPerModule` pixels square.
     */
    fun toLuma(
        pixelsPerModule: Int = 4,
        quiet: Int = 4,
    ): ByteArray {
        require(pixelsPerModule > 0 && quiet >= 0) { "pixelsPerModule must be positive and quiet not negative" }
        val dim = (side + 2 * quiet) * pixelsPerModule
        val luma = ByteArray(dim * dim) { 0xFF.toByte() }
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (!this[x, y]) continue
                for (dy in 0 until pixelsPerModule) {
                    val row = ((y + quiet) * pixelsPerModule + dy) * dim + (x + quiet) * pixelsPerModule
                    for (dx in 0 until pixelsPerModule) luma[row + dx] = 0
                }
            }
        }
        return luma
    }

    override fun equals(other: Any?): Boolean =
        other is QrMatrix && side == other.side && bits.contentEquals(other.bits)

    override fun hashCode(): Int = 31 * side + bits.contentHashCode()

    override fun toString(): String = "QrMatrix($side x $side, $darkCount dark)"

    companion object {
        /** Reads the base64 [modules] of a [side] x [side] matrix; throws [IllegalArgumentException] when it is too short. */
        fun of(
            side: Int,
            modules: String,
        ): QrMatrix {
            require(side in 1..MAX_SIDE) { "side $side is not a QR size" }
            val bytes = Base64.decode(modules)
            require(bytes.size == (side * side + 7) / 8) {
                "a $side x $side matrix needs ${(side * side + 7) / 8} bytes, got ${bytes.size}"
            }
            return QrMatrix(side, bytes)
        }

        fun fromAnswer(answer: QrEncodeAnswer): QrMatrix = of(answer.side, answer.modules)

        /** Version 40 has 177 modules a side. */
        const val MAX_SIDE = 177
    }
}
