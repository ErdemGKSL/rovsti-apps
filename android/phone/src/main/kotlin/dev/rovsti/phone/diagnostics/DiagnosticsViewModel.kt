package dev.rovsti.phone.diagnostics

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.getOrNull
import dev.rovsti.phone.R
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

/** The words of the Diagnostics page, read from resources so the page is translated like the rest of the app. */
@Immutable
data class DiagnosticsLabels(
    val reportTitle: String = "Rovsti Phone diagnostics",
    val app: String = "App",
    val version: String = "Version",
    val rustCore: String = "Rust core",
    val notLoaded: String = "not loaded",
    val counters: String = "Counters",
    val blockGuard: String = "Block guard",
    val blockGuardHits: String = "Calls rejected by the block guard",
    val silenceCallbacks: String = "Silence-ringer callbacks",
    val routeChange: String = "Audio route change",
    val notMeasured: String = "not measured",
    val on: String = "on",
    val off: String = "off",
    val lastRing: String = "Last ring",
    val callEvents: String = "Call events",
    val none: String = "none",
) {
    companion object {
        fun from(resources: android.content.res.Resources) =
            DiagnosticsLabels(
                reportTitle = resources.getString(R.string.diag_report_title),
                app = resources.getString(R.string.diag_section_app),
                version = resources.getString(R.string.settings_version),
                rustCore = resources.getString(R.string.diag_rust_core),
                notLoaded = resources.getString(R.string.diag_not_loaded),
                counters = resources.getString(R.string.diag_section_counters),
                blockGuard = resources.getString(R.string.diag_block_guard),
                blockGuardHits = resources.getString(R.string.diag_block_guard_hits),
                silenceCallbacks = resources.getString(R.string.diag_silence_callbacks),
                routeChange = resources.getString(R.string.diag_route_change),
                notMeasured = resources.getString(R.string.diag_not_measured),
                on = resources.getString(R.string.diag_on),
                off = resources.getString(R.string.diag_off),
                lastRing = resources.getString(R.string.diag_last_ring),
                callEvents = resources.getString(R.string.diag_call_events),
                none = resources.getString(R.string.diag_report_none),
            )
    }
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
    private val labels: DiagnosticsLabels = DiagnosticsLabels(),
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
                    title = labels.app,
                    rows =
                        persistentListOf(
                            DiagRow(labels.version, appVersion),
                            DiagRow(labels.rustCore, core ?: labels.notLoaded),
                        ),
                ),
                DiagSection(
                    title = labels.counters,
                    rows =
                        persistentListOf(
                            DiagRow(labels.blockGuard, if (calls.blockGuardOn()) labels.on else labels.off),
                            DiagRow(labels.blockGuardHits, calls.blockGuardHits().toString()),
                            DiagRow(labels.silenceCallbacks, calls.silenceCallbacks().toString()),
                            DiagRow(labels.routeChange, if (latency >= 0) "$latency ms" else labels.notMeasured),
                        ),
                ),
                DiagSection(labels.lastRing, persistentListOf(), ringTimeline().toImmutableList()),
                DiagSection(labels.callEvents, persistentListOf(), calls.eventDump().toImmutableList()),
            )
        return DiagnosticsUi(sections.toImmutableList(), report(sections))
    }

    private fun report(sections: List<DiagSection>): String =
        buildString {
            append(labels.reportTitle).append('\n')
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
                if (section.rows.isEmpty() && section.lines.isEmpty()) append("  ").append(labels.none).append('\n')
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
