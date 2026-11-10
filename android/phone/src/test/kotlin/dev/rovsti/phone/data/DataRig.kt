package dev.rovsti.phone.data

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue

/** A [RustCore] that forwards to the real one and remembers which ops were asked. */
internal class CountingCore(
    private val inner: RustCore,
) : RustCore by inner {
    private val seen = ConcurrentLinkedQueue<String>()

    fun count(op: String): Int = seen.count { it == op }

    override suspend fun call(
        op: String,
        request: JsonElement,
    ): Outcome<JsonElement> {
        seen += op
        return inner.call(op, request)
    }
}

/** A directory of its own for one test's preference files. */
internal fun tempDir(prefix: String): String =
    Files
        .createTempDirectory(prefix)
        .toFile()
        .apply { deleteOnExit() }
        .absolutePath

/** Polls [check] until it holds (the sources are cold flows and the Rust core answers on its own threads). */
internal suspend fun eventually(
    what: String,
    timeoutMs: Long = 10_000,
    check: () -> Boolean,
) {
    try {
        withTimeout(timeoutMs) { while (!check()) delay(10) }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw AssertionError("timed out waiting for: $what", e)
    }
}

internal const val T0 = 1_700_000_000_000L

internal fun logEntry(
    id: Long,
    number: String,
    kind: CallLogKind,
    ago: Long = 0,
    name: String? = null,
    contactId: Long? = null,
) = CallLogEntry(
    id = id,
    numberRaw = number,
    normalized = number.filter { it.isDigit() || it == '+' },
    kind = kind,
    dateMs = T0 - ago * 60_000,
    durationS = 30,
    cachedName = name,
    contactId = contactId,
)
