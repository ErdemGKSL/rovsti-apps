@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.qr

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `qr.*` (E2.10, AC0), mirroring `crates/rovsti_ffi/fixtures/qr.*`. The matrix format is in OPS.md; read it
// with [QrMatrix]. Stability: `compose_stability.conf`.

/** The error-correction level: about 7%, 15%, 25% or 30% of the code can be damaged. */
@Serializable
enum class QrEcc {
    @SerialName("l")
    L,

    @SerialName("m")
    M,

    @SerialName("q")
    Q,

    @SerialName("h")
    H,
}

@Serializable
data class QrEncodeRequest(
    val text: String,
    /** Defaults to [QrEcc.M]. */
    @EncodeDefault(NEVER) val ecc: QrEcc? = null,
)

/** [modules] is the standard base64 of `side * side` bits, row-major, most significant bit first; a set bit is dark. */
@Serializable
data class QrEncodeAnswer(
    val side: Int,
    val modules: String,
)

/** [luma] is the standard base64 of `w * h` 8-bit gray values, row-major. */
@Serializable
data class QrDecodeRequest(
    val w: Int,
    val h: Int,
    val luma: String,
)

@Serializable
data class QrDecodeAnswer(
    val texts: List<String>,
)
