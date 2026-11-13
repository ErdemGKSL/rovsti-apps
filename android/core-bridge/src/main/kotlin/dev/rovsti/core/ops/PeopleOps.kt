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

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "people.upsert",
                "people.lookup",
                "people.plan",
                "people.snapshot",
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
}
