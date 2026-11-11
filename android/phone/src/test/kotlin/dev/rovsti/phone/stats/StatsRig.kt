package dev.rovsti.phone.stats

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.phone.DateOrder
import dev.rovsti.core.model.phone.LocaleTime
import dev.rovsti.core.model.phone.TimeFormat
import dev.rovsti.core.ops.NativeSharedOps
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.data.T0
import dev.rovsti.phone.data.logEntry
import dev.rovsti.phone.recents.RecentsRig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

private const val DAY_MIN = 1_440L

/**
 * A call log around [T0] (Tuesday 14 Nov 2023, 22:13 UTC): three calls today, two on Monday, one on Sunday and one three
 * weeks ago. The longest call is the 30 minute one of the stranger on Sunday.
 */
internal fun statsLog(): List<CallLogEntry> =
    listOf(
        logEntry(
            1,
            "+905551112233",
            CallLogKind.Outgoing,
            ago = 60,
            name = "Ayse Yilmaz",
            contactId = 1,
        ).copy(durationS = 600),
        logEntry(
            2,
            "+905551112233",
            CallLogKind.Incoming,
            ago = 120,
            name = "Ayse Yilmaz",
            contactId = 1,
        ).copy(durationS = 125),
        logEntry(
            3,
            "+905551112233",
            CallLogKind.Outgoing,
            ago = 200,
            name = "Ayse Yilmaz",
            contactId = 1,
        ).copy(durationS = 20),
        logEntry(
            4,
            "+905559998877",
            CallLogKind.Incoming,
            ago = DAY_MIN + 60,
            name = "Mert Kaya",
            contactId = 2,
        ).copy(durationS = 300),
        logEntry(
            5,
            "+905559998877",
            CallLogKind.Missed,
            ago = DAY_MIN + 600,
            name = "Mert Kaya",
            contactId = 2,
        ).copy(durationS = 0),
        logEntry(6, "+4930123456", CallLogKind.Incoming, ago = 2 * DAY_MIN + 60).copy(durationS = 1_800),
        logEntry(
            7,
            "+905553334455",
            CallLogKind.Incoming,
            ago = 21 * DAY_MIN,
            name = "Deniz Aksoy",
            contactId = 3,
        ).copy(durationS = 60),
    )

/** The Statistics model over the REAL Rust core and the Recents rig's fakes, at [T0] in UTC. */
internal class StatsRig(
    host: HostRust,
    scope: CoroutineScope,
    log: List<CallLogEntry> = statsLog(),
) {
    val base = RecentsRig(host, scope, log)
    val model =
        StatsViewModel(
            recents = base.repo,
            ops = base.ops,
            shared = NativeSharedOps(host.core),
            homeCc = { "90" },
            locale = { LocaleTime(0, TimeFormat(hour12 = false, dateOrder = DateOrder.DayMonthYear)) },
            scope = scope,
            clock = { T0 },
            tzOffsetS = { 0 },
            compute = Dispatchers.Default,
            language = { java.util.Locale.US },
        )

    suspend fun ready() = base.ready()
}

internal suspend fun StatsViewModel.loaded(
    check: (StatsUi) -> Boolean = {
        it.ready && (it.cells?.calls ?: 0) > 0
    },
): StatsUi = withTimeout(10_000) { state.first(check) }

internal suspend inline fun <reified E : StatsEffect> StatsViewModel.nextEffect(): E =
    withTimeout(10_000) { effect.first { it is E } as E }

/** Swipes the page up until the node tagged [tag] sits in the upper part of the screen (the manual clock cannot scrollToNode). */
internal fun dev.rovsti.testing.ShotTest.scrollInto(
    tag: String,
    maxSwipes: Int = 10,
) {
    repeat(maxSwipes) {
        // boundsInRoot is clipped to the viewport (a node scrolled out has an empty rect): position plus size is not.
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode()
        val bottom = node.positionInRoot.y + node.size.height
        val root =
            compose
                .onRoot()
                .fetchSemanticsNode()
                .boundsInRoot
        if (bottom < root.bottom * VISIBLE_UNTIL) return
        val all = compose.onAllNodes(hasScrollAction(), useUnmergedTree = true)
        val count = all.fetchSemanticsNodes().size
        for (n in 0 until count) {
            all[n].performSemanticsAction(
                androidx.compose.ui.semantics.SemanticsActions.ScrollBy,
            ) { it(0f, SCROLL_STEP) }
        }
        advance(600)
    }
}

private const val VISIBLE_UNTIL = 0.8f
private const val SCROLL_STEP = 700f
