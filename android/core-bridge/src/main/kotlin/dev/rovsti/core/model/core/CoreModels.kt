@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Models of the `core.*` ops (E2.7). They mirror `crates/rovsti_ffi/fixtures/core.*.json`; ContractFixturesTest decodes
// every fixture strictly into these and re-encodes it, so a field renamed on one side fails a test on the other.

/** `core.version` answer and the `init` answer. */
@Serializable
data class VersionInfo(
    val version: String,
    val abi: Int,
)

/** `core.data_dir` answer: what `init` stored. */
@Serializable
data class DataDirInfo(
    @SerialName("data_dir") val dataDir: String,
    @SerialName("cache_dir") val cacheDir: String,
    @SerialName("app_id") val appId: String,
    val sdk: Int,
)

/** `core.echo` request and answer: the bridge returns its request unchanged. */
@Serializable
data class EchoProbe(
    val text: String,
    val numbers: List<Int>,
    val nested: EchoNested,
)

@Serializable
data class EchoNested(
    val flag: Boolean,
    val note: String?,
)

/** `core.fail` request: asks the core to answer an error of [kind] (error-mapping tests). */
@Serializable
data class FailRequest(
    val kind: String,
    val message: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val detail: JsonElement? = null,
)

/** The `err` object of an envelope. */
@Serializable
data class ErrorBody(
    val kind: String,
    val message: String,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val detail: JsonElement? = null,
)

/** The envelope of a successful call: `{"ok": value}`. */
@Serializable
data class OkEnvelope<T>(
    val ok: T,
)

/** The envelope of a failed call: `{"err": {...}}`. */
@Serializable
data class ErrEnvelope(
    val err: ErrorBody,
)
