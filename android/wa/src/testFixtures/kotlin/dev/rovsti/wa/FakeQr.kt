package dev.rovsti.wa

import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.qr.QrEncodeAnswer
import dev.rovsti.core.model.qr.QrEncodeRequest
import kotlin.io.encoding.Base64

/**
 * A `qr.encode` for tests and the catalog: a 29 x 29 matrix with the three finder squares and modules from a hash of the
 * text. It looks like a QR code and is deterministic; it does not scan (the real encoder is Rust's, see `QrOpsNativeTest`).
 */
object FakeQr {
    const val SIDE = 29

    fun answer(text: String): QrEncodeAnswer {
        val bits = BooleanArray(SIDE * SIDE)
        var seed = text.fold(17L) { acc, c -> acc * 31 + c.code }
        for (i in bits.indices) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            bits[i] = (seed ushr 33) % 2L == 0L
        }

        fun finder(
            x0: Int,
            y0: Int,
        ) {
            for (y in -1..7) {
                for (x in -1..7) {
                    val px = x0 + x
                    val py = y0 + y
                    if (px !in 0 until SIDE || py !in 0 until SIDE) continue
                    val ring = maxOf(kotlin.math.abs(x - 3), kotlin.math.abs(y - 3))
                    bits[py * SIDE + px] = ring in 0..1 || ring == 3
                }
            }
        }
        finder(0, 0)
        finder(SIDE - 7, 0)
        finder(0, SIDE - 7)
        val bytes = ByteArray((bits.size + 7) / 8)
        for (i in bits.indices) {
            if (bits[i]) bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        }
        return QrEncodeAnswer(SIDE, Base64.encode(bytes))
    }

    /** Answers `qr.encode` on [core] with [answer]. */
    fun install(core: FakeRustCore) {
        core.onTyped(
            "qr.encode",
            QrEncodeRequest.serializer(),
            QrEncodeAnswer.serializer(),
        ) { Outcome.Ok(answer(it.text)) }
    }
}
