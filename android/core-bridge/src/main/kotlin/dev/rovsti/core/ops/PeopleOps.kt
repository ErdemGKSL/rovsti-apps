package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.call
import dev.rovsti.core.model.people.PeopleLookupAnswer
import dev.rovsti.core.model.people.PeopleLookupRequest
import dev.rovsti.core.model.people.PeoplePlanAnswer
import dev.rovsti.core.model.people.PeoplePlanRequest
import dev.rovsti.core.model.people.PeopleSnapshotAnswer
import dev.rovsti.core.model.people.PeopleSnapshotRequest
import dev.rovsti.core.model.people.PeopleUpsertAnswer
import dev.rovsti.core.model.people.PeopleUpsertRequest
import dev.rovsti.core.model.people.SyncBeginAnswer
import dev.rovsti.core.model.people.SyncBeginRequest
import dev.rovsti.core.model.people.SyncCommitted
import dev.rovsti.core.model.people.SyncNextRequest
import dev.rovsti.core.model.people.SyncReportRequest
import dev.rovsti.core.model.people.SyncStatus
import dev.rovsti.core.model.people.SyncStatusRequest
import dev.rovsti.core.model.people.SyncStep
import dev.rovsti.core.model.people.SyncStopRequest

/**
 * The people cache (X.1): what the apps know about the people they meet on WhatsApp, kept in `people.json` by the Rust
 * core. Reads work offline; only [upsert] writes.
 */
interface PeopleOps {
    /** Applies what is known about people; answers what became stale or must be deleted. */
    suspend fun upsert(req: PeopleUpsertRequest): Outcome<PeopleUpsertAnswer>

    /** One person by number, JID or LID. */
    suspend fun lookup(req: PeopleLookupRequest): Outcome<PeopleLookupAnswer>

    /** Which pictures to fetch and which files to delete. */
    suspend fun plan(req: PeoplePlanRequest): Outcome<PeoplePlanAnswer>

    /** Every person, sorted by name, for the Contacts provider. */
    suspend fun snapshot(req: PeopleSnapshotRequest = PeopleSnapshotRequest()): Outcome<PeopleSnapshotAnswer>

    /** People sync v2: what the settings row shows (the stored run, the last finished one, WhatsApp's penalty). */
    suspend fun syncStatus(req: SyncStatusRequest): Outcome<SyncStatus>

    /** Opens a sync slice (a `handle`) or answers why not. */
    suspend fun syncBegin(req: SyncBeginRequest): Outcome<SyncBeginAnswer>

    /** What the slice should do next. */
    suspend fun syncNext(req: SyncNextRequest): Outcome<SyncStep>

    /** Commits what the gateway answered for the last step. */
    suspend fun syncReport(req: SyncReportRequest): Outcome<SyncCommitted>

    /** Ends the slice and releases its handle; without a handle, interrupts the stored run (unlink). */
    suspend fun syncStop(req: SyncStopRequest): Outcome<SyncStatus>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "people.upsert",
                "people.lookup",
                "people.plan",
                "people.snapshot",
                "people.sync_status",
                "people.sync_begin",
                "people.sync_next",
                "people.sync_report",
                "people.sync_stop",
            )
    }
}

/** [PeopleOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativePeopleOps(
    private val core: RustCore = NativeRustCore(),
) : PeopleOps {
    override suspend fun upsert(req: PeopleUpsertRequest) =
        core.call("people.upsert", req, PeopleUpsertRequest.serializer(), PeopleUpsertAnswer.serializer())

    override suspend fun lookup(req: PeopleLookupRequest) =
        core.call("people.lookup", req, PeopleLookupRequest.serializer(), PeopleLookupAnswer.serializer())

    override suspend fun plan(req: PeoplePlanRequest) =
        core.call("people.plan", req, PeoplePlanRequest.serializer(), PeoplePlanAnswer.serializer())

    override suspend fun snapshot(req: PeopleSnapshotRequest) =
        core.call("people.snapshot", req, PeopleSnapshotRequest.serializer(), PeopleSnapshotAnswer.serializer())

    override suspend fun syncStatus(req: SyncStatusRequest) =
        core.call("people.sync_status", req, SyncStatusRequest.serializer(), SyncStatus.serializer())

    override suspend fun syncBegin(req: SyncBeginRequest) =
        core.call("people.sync_begin", req, SyncBeginRequest.serializer(), SyncBeginAnswer.serializer())

    override suspend fun syncNext(req: SyncNextRequest) =
        core.call("people.sync_next", req, SyncNextRequest.serializer(), SyncStep.serializer())

    override suspend fun syncReport(req: SyncReportRequest) =
        core.call("people.sync_report", req, SyncReportRequest.serializer(), SyncCommitted.serializer())

    override suspend fun syncStop(req: SyncStopRequest) =
        core.call("people.sync_stop", req, SyncStopRequest.serializer(), SyncStatus.serializer())
}
