package dev.rosty.core.contracts

import dev.rosty.core.model.core.ErrEnvelope
import dev.rosty.core.model.core.OkEnvelope
import dev.rosty.core.model.people.PeopleLookupAnswer
import dev.rosty.core.model.people.PeopleLookupRequest
import dev.rosty.core.model.people.PeoplePlanAnswer
import dev.rosty.core.model.people.PeoplePlanRequest
import dev.rosty.core.model.people.PeopleSnapshotAnswer
import dev.rosty.core.model.people.PeopleSnapshotRequest
import dev.rosty.core.model.people.PeopleUpsertAnswer
import dev.rosty.core.model.people.PeopleUpsertRequest
import dev.rosty.core.model.people.SyncBeginAnswer
import dev.rosty.core.model.people.SyncBeginRequest
import dev.rosty.core.model.people.SyncCommitted
import dev.rosty.core.model.people.SyncNextRequest
import dev.rosty.core.model.people.SyncReportRequest
import dev.rosty.core.model.people.SyncStatus
import dev.rosty.core.model.people.SyncStatusRequest
import dev.rosty.core.model.people.SyncStep
import dev.rosty.core.model.people.SyncStopRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `people.*`. */
object PeopleContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<PeopleUpsertRequest, PeopleUpsertAnswer>("people.upsert", ok = listOf("lid_join", "picture_removed"))
            op<PeopleLookupRequest, PeopleLookupAnswer>(
                "people.lookup",
                ok = listOf("national", "national_without_home", "by_lid", "unknown"),
            )
            op<PeoplePlanRequest, PeoplePlanAnswer>("people.plan", ok = listOf("nothing_to_do"))
            op<PeopleSnapshotRequest, PeopleSnapshotAnswer>("people.snapshot", ok = listOf("missing"))
            op<SyncStatusRequest, SyncStatus>("people.sync_status", ok = listOf("never", "job"))
            op<SyncBeginRequest, SyncBeginAnswer>(
                "people.sync_begin",
                ok = listOf("resume", "nothing_to_resume", "not_before", "job"),
            )
            op<SyncNextRequest, SyncStep>("people.sync_next", ok = listOf("job_watch"))
            op<SyncReportRequest, SyncCommitted>(
                "people.sync_report",
                ok =
                    listOf(
                        "rate_limited",
                        "files",
                        "job_started",
                        "job_unsupported",
                        "job",
                        "job_gone",
                        "bundle",
                        "job_closed",
                    ),
            )
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
