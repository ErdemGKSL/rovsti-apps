package dev.rosty.people.sync

import dev.rosty.core.model.people.SyncRunStatus
import dev.rosty.core.model.people.SyncWire

/**
 * When an automatic trigger starts a people sync run (people sync v3, section 6), the same for Contacts and Phone. Pure,
 * so the rules are tested without a link or WorkManager.
 *
 * - The periodic work runs every [PERIOD_MS] by itself and does not ask here.
 * - [Kind.FOREGROUND] (the app comes to the foreground) and [Kind.RECONNECT] (Phone's link is back after a disconnect)
 *   start a run only when the last finished one is at least [PERIOD_MS] old (or there is none), the last attempt is at
 *   least [ATTEMPT_DEBOUNCE_MS] old, the stored run was not stopped by the user and has not failed, and WhatsApp's
 *   penalty is over. A resumable run is not resumed from here: its continuation work does that.
 * - [Kind.NEW_LINK] (another WhatsApp instance) starts one run unless the penalty runs.
 *
 * There is no trigger for a changed address book: a new number is sent at the next run.
 */
object PeopleSyncTriggers {
    /** How often names and photos are synced (the periodic work, and the age the foreground trigger waits for). */
    const val PERIOD_MS = 12L * 3_600_000

    /** An automatic start at most this often, whatever the last run did. */
    const val ATTEMPT_DEBOUNCE_MS = 30L * 60_000

    /** What asks. */
    enum class Kind {
        /** The app came to the foreground. */
        FOREGROUND,

        /** The same WhatsApp link connected again. */
        RECONNECT,

        /** A WhatsApp link this sync has not followed before. */
        NEW_LINK,
    }

    /**
     * Whether [kind] starts a run at [nowMs]. [lastFinishedMs] is when the last run finished (null: never),
     * [lastAttemptMs] when a run was last started or resumed (`SyncStatus.attemptMs`), [run] the stored run and
     * [notBeforeMs] WhatsApp's penalty (`SyncStatus.notBeforeMs`).
     */
    fun shouldStart(
        kind: Kind,
        nowMs: Long,
        lastFinishedMs: Long?,
        lastAttemptMs: Long?,
        run: SyncRunStatus?,
        notBeforeMs: Long? = null,
    ): Boolean {
        if (notBeforeMs != null && notBeforeMs > nowMs) return false
        if (kind == Kind.NEW_LINK) return true
        val phase = run?.phase
        if (phase?.kind == SyncWire.FAILED) return false
        if (phase?.kind == SyncWire.INTERRUPTED && phase.reason == SyncWire.CANCELLED) return false
        if (lastFinishedMs != null && nowMs - lastFinishedMs < PERIOD_MS) return false
        if (lastAttemptMs != null && nowMs - lastAttemptMs < ATTEMPT_DEBOUNCE_MS) return false
        return true
    }
}
