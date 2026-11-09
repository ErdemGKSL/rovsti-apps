@file:Suppress("ktlint:standard:max-line-length")

package dev.rovsti.platform.telephony

import dev.rovsti.platform.wire.CallInfo
import dev.rovsti.platform.wire.Focus
import dev.rovsti.platform.wire.Wire

// Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/MultiCall.kt (ARCH 7.3).

/*
 * Multi-call rules (AD-24, AD-25), pure JVM so the JVM tests run them without Android.
 * `CallRegistry` feeds these with the calls it built; nothing here touches Telecom.
 */

/** What the three snapshot booleans say (`can_add_call`, `can_merge`, `can_swap`). */
data class CanDo(
    val addCall: Boolean,
    val merge: Boolean,
    val swap: Boolean,
)

object MultiCall {
    private val LIVE = setOf("active", "dialing", "holding")

    fun has(
        mask: Int,
        bit: Int,
    ) = mask and bit != 0

    fun topLevel(calls: List<CallInfo>) = calls.filter { it.parentId == null }

    /** Top-level calls that are neither ringing nor over. */
    fun liveCount(calls: List<CallInfo>) = topLevel(calls).count { it.state in LIVE }

    fun isEmergency(call: CallInfo) = has(call.props, Wire.Props.EMERGENCY)

    /**
     * `can_add_call` = the platform says so ([systemCanAdd] is `InCallService.canAddCall()`), and the
     * call is not an emergency call, and fewer than two non-ringing top-level calls exist.
     */
    fun canAddCall(
        systemCanAdd: Boolean,
        calls: List<CallInfo>,
    ): Boolean {
        val top = topLevel(calls)
        if (top.any { isEmergency(it) && it.state != "disconnected" }) return false
        if (liveCount(calls) >= 2) return false
        return systemCanAdd
    }

    /** `can_merge` = the foreground call has the merge capability, or any top-level call can be conferenced. */
    fun canMerge(
        calls: List<CallInfo>,
        focus: Focus,
    ): Boolean {
        val top = topLevel(calls)
        val fg = top.firstOrNull { it.id == focus.foreground }
        return (fg != null && has(fg.caps, Wire.Caps.MERGE)) || top.any { it.conferenceable }
    }

    /**
     * `can_swap` = two non-ringing calls and the held one can resume, or the foreground has the
     * swap capability (a conference beside a single call).
     */
    fun canSwap(
        calls: List<CallInfo>,
        focus: Focus,
    ): Boolean {
        val top = topLevel(calls)
        val fg = top.firstOrNull { it.id == focus.foreground }
        if (fg != null && has(fg.caps, Wire.Caps.SWAP)) return true
        val held = top.firstOrNull { it.id == focus.background } ?: return false
        return fg != null && fg.state in LIVE && (held.canHold || has(held.caps, Wire.Caps.HOLD))
    }

    fun canDo(
        systemCanAdd: Boolean,
        calls: List<CallInfo>,
        focus: Focus,
    ) = CanDo(canAddCall(systemCanAdd, calls), canMerge(calls, focus), canSwap(calls, focus))

    /**
     * Whether the first-choice "Hold and answer" is offered: the current call can be held and no
     * held call exists. Otherwise the choice is relabelled "End held call and answer".
     */
    fun holdAndAnswerOffered(
        calls: List<CallInfo>,
        focus: Focus,
    ): Boolean {
        val top = topLevel(calls)
        val fg = top.firstOrNull { it.id == focus.foreground } ?: return true
        if (fg.state !in LIVE) return true
        if (focus.background != null) return false
        return fg.canHold || has(fg.caps, Wire.Caps.HOLD)
    }
}

// ---- The pending-operation machine (AD-25) -----------------------------------------------------

enum class OpKind(
    val wire: String,
) {
    ANSWER("answer"),
    SWAP("swap"),
    MERGE("merge"),
}

enum class OpResult { DONE, FAILED }

/** An operation the registry started and is waiting on. */
sealed class PendingOp(
    val kind: OpKind,
) {
    /** Ends [replacedId], then answers [targetId] (answer-replacing, as built). */
    data class AnswerReplacing(
        val targetId: Int,
        val replacedId: Int,
    ) : PendingOp(OpKind.ANSWER)

    /** Resumes [toResume]; [former] is the call that was active and must end up held. */
    data class Swap(
        val toResume: Int,
        val former: Int?,
    ) : PendingOp(OpKind.SWAP)

    /** Merges; done once a conference holds more than [childrenBefore] children. */
    data class Merge(
        val childrenBefore: Int,
    ) : PendingOp(OpKind.MERGE)
}

/** What [PendingMachine.tick] found. */
sealed interface Tick {
    object Idle : Tick

    /** Swap only: the first deadline passed; the caller must `hold()` the former active call once. */
    data class Retry(
        val op: PendingOp.Swap,
    ) : Tick

    /** The deadline passed; the op is finished as `Failed`. */
    data class Failed(
        val op: PendingOp,
    ) : Tick
}

/**
 * Holds at most one [PendingOp]. Deadlines: answer 2 s, swap 1 s then one retry second, merge 2 s.
 * A new op is refused while another runs, except [PendingOp.AnswerReplacing], which supersedes
 * a swap or a merge. The wire carries `pending` while an op runs and `pending_failed` for exactly
 * one snapshot after a failure.
 */
class PendingMachine(
    private val now: () -> Long,
) {
    private class Running(
        val op: PendingOp,
        var deadline: Long,
        var retried: Boolean = false,
    )

    private var running: Running? = null
    private var failedKind: OpKind? = null

    val current: PendingOp? get() = running?.op

    /** Milliseconds from now until the deadline; null when nothing runs. */
    fun nextDeadlineIn(): Long? = running?.let { (it.deadline - now()).coerceAtLeast(0) }

    fun start(op: PendingOp): Boolean {
        val r = running
        if (r != null) {
            val supersedes = op is PendingOp.AnswerReplacing && r.op !is PendingOp.AnswerReplacing
            if (!supersedes) return false
        }
        running = Running(op, now() + timeoutOf(op))
        failedKind = null
        return true
    }

    /** Ends the running op; returns it (null when nothing ran or it was already gone). */
    fun finish(result: OpResult): PendingOp? {
        val r = running ?: return null
        running = null
        if (result == OpResult.FAILED) failedKind = r.op.kind
        return r.op
    }

    fun tick(): Tick {
        val r = running ?: return Tick.Idle
        if (now() < r.deadline) return Tick.Idle
        val op = r.op
        if (op is PendingOp.Swap && !r.retried) {
            r.retried = true
            r.deadline = now() + SWAP_MS
            return Tick.Retry(op)
        }
        finish(OpResult.FAILED)
        return Tick.Failed(op)
    }

    /** The `pending` and `pending_failed` wire values for the snapshot being built; consumes the failure. */
    fun flags(): Pair<String?, String?> {
        val pending = running?.op?.kind?.wire
        val failed = failedKind?.wire
        failedKind = null
        return pending to failed
    }

    fun clear() {
        running = null
        failedKind = null
    }

    private fun timeoutOf(op: PendingOp) =
        when (op) {
            is PendingOp.AnswerReplacing -> ANSWER_MS
            is PendingOp.Swap -> SWAP_MS
            is PendingOp.Merge -> MERGE_MS
        }

    companion object {
        const val ANSWER_MS = 2_000L
        const val SWAP_MS = 1_000L
        const val MERGE_MS = 2_000L
    }
}

// ---- Spike S4 facts -----------------------------------------------------------------------------

/**
 * What the registry measured for the S4 probe: `-1` / null mean "not seen yet". Latencies are the
 * time from the command to the matching Telecom callback.
 */
data class MultiCallFacts(
    val swapLatencyMs: Long = -1,
    /** Both calls were held at the same time after a swap. */
    val bothHeldSeen: Boolean? = null,
    val mergeLatencyMs: Long = -1,
    /** A top-level conference call with `props.conference` appeared after a merge. */
    val mergeProducedConference: Boolean? = null,
    /** Hold-and-answer: the first call ended up held (true), or was disconnected (false). */
    val holdAnswerHeldFirst: Boolean? = null,
    /** Ids of calls that could be conferenced right now (not on the wire). */
    val conferenceable: Set<Int> = emptySet(),
)

/** Pure S4 report lines (the probe prints them; JVM tests pin them). */
object MultiCallProbe {
    fun masks(
        calls: List<CallInfo>,
        facts: MultiCallFacts = MultiCallFacts(),
    ): List<String> =
        calls.map { c ->
            "id=${c.id} state=${c.state} caps=${c.caps} props=${c.props} children=${c.children.size}" +
                " parent=${c.parentId != null} conferenceable=${c.conferenceable || c.id in facts.conferenceable}"
        }

    fun verdicts(
        calls: List<CallInfo>,
        facts: MultiCallFacts,
    ): List<String> {
        val out = ArrayList<String>()
        out.add(
            when {
                facts.swapLatencyMs < 0 -> "unknown: Swap changes the Active call within 1 s without both held (swap twice, then rerun)"
                facts.bothHeldSeen == true -> "fail: Swap changes the Active call within 1 s without both held (both calls were held)"
                facts.swapLatencyMs <= 1000 -> "pass: Swap changes the Active call within 1 s without both held (${facts.swapLatencyMs} ms)"
                else -> "fail: Swap changes the Active call within 1 s without both held (${facts.swapLatencyMs} ms)"
            },
        )
        out.add(
            when {
                facts.mergeLatencyMs < 0 -> "unknown: Merge yields a top-level call with props.conference within 2 s (merge two calls, then rerun)"
                facts.mergeProducedConference != true -> "fail: Merge yields a top-level call with props.conference within 2 s (no conference reported)"
                facts.mergeLatencyMs <= 2000 -> "pass: Merge yields a top-level call with props.conference within 2 s (${facts.mergeLatencyMs} ms)"
                else -> "fail: Merge yields a top-level call with props.conference within 2 s (${facts.mergeLatencyMs} ms)"
            },
        )
        val hosts = topLevelConferences(calls)
        out.add(
            when {
                hosts.isEmpty() -> {
                    "unknown: children and their split/disconnect bits are reported (merge a conference first)"
                }

                hosts.all { h -> childrenOf(calls, h).isNotEmpty() } -> {
                    val kids = hosts.flatMap { childrenOf(calls, it) }
                    val split = kids.count { MultiCall.has(it.caps, Wire.Caps.SPLIT) }
                    val drop = kids.count { MultiCall.has(it.caps, Wire.Caps.DISCONNECT_PARTICIPANT) }
                    "pass: children and their split/disconnect bits are reported (children=${kids.size} split=$split disconnect=$drop)"
                }

                else -> {
                    "fail: children and their split/disconnect bits are reported (the conference lists no children; rows will read 'n people')"
                }
            },
        )
        out.add(
            when (facts.holdAnswerHeldFirst) {
                null -> "unknown: Hold-and-answer holds the first call (let a third phone call in, choose Hold and answer, rerun)"
                true -> "pass: Hold-and-answer holds the first call"
                false -> "fail: Hold-and-answer holds the first call (the carrier ended it; the choice is relabelled 'End held call and answer')"
            },
        )
        return out
    }

    private fun topLevelConferences(calls: List<CallInfo>) =
        calls.filter { it.parentId == null && MultiCall.has(it.props, Wire.Props.CONFERENCE) }

    private fun childrenOf(
        calls: List<CallInfo>,
        host: CallInfo,
    ) = calls.filter { it.parentId == host.id }
}
