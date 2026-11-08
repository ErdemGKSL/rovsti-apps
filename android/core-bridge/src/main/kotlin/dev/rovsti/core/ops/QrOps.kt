package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
import dev.rovsti.core.model.qr.QrDecodeAnswer
import dev.rovsti.core.model.qr.QrDecodeRequest
import dev.rovsti.core.model.qr.QrEncodeAnswer
import dev.rovsti.core.model.qr.QrEncodeRequest

/**
 * QR codes without ZXing or ML Kit: [encode] answers the matrix, [decode] reads the codes of a gray image. Both run on
 * the Rust dispatcher; the camera screen downsizes a frame to luma, the share sheet draws the [QrMatrix].
 */
interface QrOps {
    /** Encodes text as a QR matrix (read it with `QrMatrix.fromAnswer`). */
    suspend fun encode(req: QrEncodeRequest): Outcome<QrEncodeAnswer>

    /** Reads the QR codes of an 8-bit gray image. */
    suspend fun decode(req: QrDecodeRequest): Outcome<QrDecodeAnswer>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "qr.encode",
                "qr.decode",
            )
    }
}

/** [QrOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeQrOps(
    private val core: RustCore = NativeRustCore(),
) : QrOps {
    override suspend fun encode(req: QrEncodeRequest) =
        core.call("qr.encode", req, QrEncodeRequest.serializer(), QrEncodeAnswer.serializer())

    override suspend fun decode(req: QrDecodeRequest) =
        core.call("qr.decode", req, QrDecodeRequest.serializer(), QrDecodeAnswer.serializer())
}
