package dev.rovsti.phone.diagnostics

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.getOrNull
import dev.rovsti.platform.files.ClipboardRepository
import dev.rovsti.platform.telephony.CallsApi
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@Immutable
data class DiagRow(
    val label: String,
    val value: String,
)

@Immutable
data class DiagSection(
    val title: String,
    val rows: ImmutableList<DiagRow>,
    /** Free lines (the registry's event ring, the last ring timeline), shown in the log style. */
    val lines: ImmutableList<String> = persistentListOf(),
)

@Immutable
data class DiagnosticsUi(
    val sections: ImmutableList<DiagSection> = persistentListOf(),
    val report: String = "",
)

sealed interface DiagnosticsAction {
    data object Refresh : DiagnosticsAction

    data object CopyAll : DiagnosticsAction
}

sealed interface DiagnosticsEffect {
    /** The report was copied: the screen says so (a toast), or [copied] false when the clipboard refused. */
    data class Copied(
        val copied: Boolean,
    ) : DiagnosticsEffect
}

/**
 * The hidden Diagnostics page (story 14.0f, AD-36): what the call owner knows about itself (the event ring, the block
 * guard, the silence callbacks, the audio route latency), the ring-latency timeline and the Rust core version, with a
 * Copy all that puts it into one text. No number and no name is ever in it (the registry's events carry none).
 */
class DiagnosticsViewModel(
    private val calls: CallsApi,
    private val coreVersion: suspend () -> String?,
    private val ringTimeline: () -> List<String>,
    private val clipboard: ClipboardRepository,
    private val appVersion: String,
) : ViewModel() {
    private val mutable = MutableStateFlow(DiagnosticsUi())
    val state: StateFlow<DiagnosticsUi> = mutable.asStateFlow()

    private val outbox = Channel<DiagnosticsEffect>(Channel.UNLIMITED)
    val effects: Flow<DiagnosticsEffect> = outbox.receiveAsFlow()

    init {
        onAction(DiagnosticsAction.Refresh)
    }

    fun onAction(action: DiagnosticsAction) {
        when (action) {
            DiagnosticsAction.Refresh -> viewModelScope.launch { mutable.value = build(coreVersion()) }
            DiagnosticsAction.CopyAll -> outbox.trySend(DiagnosticsEffect.Copied(clipboard.copy(mutable.value.report)))
        }
    }

    private fun build(core: String?): DiagnosticsUi {
        val latency = calls.routeLatencyMs()
        val sections =
            listOf(
                DiagSection(
                    title = "App",
                    rows =
                        persistentListOf(
                            DiagRow("Version", appVersion),
                            DiagRow("Rust core", core ?: "not loaded"),
                        ),
                ),
                DiagSection(
                    title = "Counters",
                    rows =
                        persistentListOf(
                            DiagRow("Block guard", if (calls.blockGuardOn()) "on" else "off"),
                            DiagRow("Calls rejected by the block guard", calls.blockGuardHits().toString()),
                            DiagRow("Silence-ringer callbacks", calls.silenceCallbacks().toString()),
                            DiagRow("Audio route change", if (latency >= 0) "$latency ms" else "not measured"),
                        ),
                ),
                DiagSection("Last ring", persistentListOf(), ringTimeline().toImmutableList()),
                DiagSection("Call events", persistentListOf(), calls.eventDump().toImmutableList()),
            )
        return DiagnosticsUi(sections.toImmutableList(), report(sections))
    }

    private fun report(sections: List<DiagSection>): String =
        buildString {
            append("Rovsti Phone diagnostics\n")
            for (section in sections) {
                append('\n').append(section.title).append('\n')
                for (row in section.rows) {
                    append("  ")
                        .append(row.label)
                        .append(": ")
                        .append(row.value)
                        .append('\n')
                }
                for (line in section.lines) append("  ").append(line).append('\n')
                if (section.rows.isEmpty() && section.lines.isEmpty()) append("  none\n")
            }
        }

    companion object {
        /** Seven taps on the version row of Settings open the page. */
        const val TAPS_TO_OPEN = 7
    }
}

/** Counts the taps on the version row; the seventh in a row (each within [windowMs] of the last) opens Diagnostics. */
class SevenTaps(
    private val windowMs: Long = WINDOW_MS,
    private val needed: Int = DiagnosticsViewModel.TAPS_TO_OPEN,
) {
    private var count = 0
    private var last = Long.MIN_VALUE

    /** Registers a tap at [nowMs]; true on the one that completes the sequence (the count then starts over). */
    fun tap(nowMs: Long): Boolean {
        count = if (last != Long.MIN_VALUE && nowMs - last <= windowMs) count + 1 else 1
        last = nowMs
        if (count < needed) return false
        count = 0
        last = Long.MIN_VALUE
        return true
    }

    private companion object {
        const val WINDOW_MS = 1_500L
    }
}

/** The core version as text, or null when the library is not loaded. */
suspend fun dev.rovsti.core.RustCore.versionText(): String? = version().getOrNull()
