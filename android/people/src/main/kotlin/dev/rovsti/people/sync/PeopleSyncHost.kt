package dev.rovsti.people.sync

/**
 * What the people sync worker ([ForegroundPeopleSyncWorker], people sync v2 S11) needs of an app's sync: Contacts'
 * `PeopleSync` and Phone's `StandalonePeople`. [now] and [pause] are the sync's own clock and sleep (the tests move a
 * fake clock), so the worker's waits run on the same time as the planner's.
 */
interface PeopleSyncHost {
    val runner: PeopleSyncRunner

    /** One slice as [start] says (`SyncWire.START_*`), bounded by [sliceMs] (null: until the run ends or waits). */
    suspend fun runSlice(
        start: String,
        sliceMs: Long?,
    ): SliceResult = runner.run(start, sliceMs)

    /** The Stop button and the notification's Stop: the active slice ends `interrupted(cancelled)`. */
    fun stop() = runner.cancel()

    fun now(): Long

    suspend fun pause(ms: Long)
}
