package dev.rovsti.core.contracts

import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.OkEnvelope
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
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `people.*`. */
object PeopleContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<PeopleUpsertRequest, PeopleUpsertAnswer>("people.upsert", ok = listOf("lid_join", "picture_removed"))
            op<PeopleLookupRequest, PeopleLookupAnswer>("people.lookup", ok = listOf("by_lid", "unknown"))
            op<PeoplePlanRequest, PeoplePlanAnswer>("people.plan", ok = listOf("nothing_to_do"))
            op<PeopleSnapshotRequest, PeopleSnapshotAnswer>("people.snapshot", ok = listOf("missing"))
            op<SyncStatusRequest, SyncStatus>("people.sync_status", ok = listOf("never"))
            op<SyncBeginRequest, SyncBeginAnswer>(
                "people.sync_begin",
                ok = listOf("resume", "nothing_to_resume", "not_before"),
            )
            op<SyncNextRequest, SyncStep>("people.sync_next")
            op<SyncReportRequest, SyncCommitted>("people.sync_report", ok = listOf("rate_limited", "files"))
            op<SyncStopRequest, SyncStatus>("people.sync_stop", ok = listOf("unlink"))
        }

    private inline fun <reified Req, reified Ans> MutableMap<String, KSerializer<*>>.op(
        op: String,
        ok: List<String> = emptyList(),
        err: List<String> = emptyList(),
    ) {
        val okAnswer = OkEnvelope.serializer(serializer<Ans>())
        put("$op.request", serializer<Req>())
        put("$op.answer", okAnswer)
        for (label in ok) {
            put("$op.$label.request", serializer<Req>())
            put("$op.$label.answer", okAnswer)
        }
        for (label in err) {
            put("$op.$label.request", serializer<Req>())
            put("$op.$label.answer", ErrEnvelope.serializer())
        }
    }
}
