package dev.rovsti.core.rust

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.qr.QrDecodeRequest
import dev.rovsti.core.model.qr.QrEcc
import dev.rovsti.core.model.qr.QrEncodeRequest
import dev.rovsti.core.model.qr.QrMatrix
import dev.rovsti.core.ops.NativeQrOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.io.encoding.Base64

/** `qr.encode` and `qr.decode` through the real library: encode, draw the matrix in Kotlin, decode it back. */
class QrOpsNativeTest {
    private val ops = NativeQrOps(TestRust.core)

    @Before
    fun load() = TestRust.ensure()

    @Test
    fun kotlinRendersTheMatrixAndRustReadsItBack() =
        runBlocking {
            for ((text, ecc) in listOf("hello" to QrEcc.L, "Ayşe Yılmaz +90 532 123 45 67" to QrEcc.H)) {
                val answer = ops.encode(QrEncodeRequest(text, ecc)).getOrOk()
                val matrix = QrMatrix.fromAnswer(answer)
                val dim = (matrix.side + 8) * 4
                val read = ops.decode(QrDecodeRequest(dim, dim, Base64.encode(matrix.toLuma(4, 4)))).getOrOk()
                assertEquals(listOf(text), read.texts)
            }
        }

    @Test
    fun badInputIsInvalid() =
        runBlocking {
            val empty = ops.encode(QrEncodeRequest("")) as Outcome.Err
            assertTrue(empty.error is RustError.Invalid)
            val mismatch = ops.decode(QrDecodeRequest(4, 4, Base64.encode(ByteArray(3)))) as Outcome.Err
            assertTrue(mismatch.error is RustError.Invalid)
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
