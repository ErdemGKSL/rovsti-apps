package dev.rovsti.core.contracts

import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.core.ErrEnvelope
import dev.rovsti.core.model.core.OkEnvelope
import dev.rovsti.core.model.search.CallCountsRequest
import dev.rovsti.core.model.search.FoldAnswer
import dev.rovsti.core.model.search.FoldRequest
import dev.rovsti.core.model.search.IndexAnswer
import dev.rovsti.core.model.search.IndexNewRequest
import dev.rovsti.core.model.search.IndexReplaceRequest
import dev.rovsti.core.model.search.MatchNumberAnswer
import dev.rovsti.core.model.search.MatchNumberRequest
import dev.rovsti.core.model.search.MatchNumbersAnswer
import dev.rovsti.core.model.search.MatchNumbersRequest
import dev.rovsti.core.model.search.QueryAnswer
import dev.rovsti.core.model.search.QueryRequest
import dev.rovsti.core.model.search.T9Answer
import dev.rovsti.core.model.search.T9Request
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/** Fixture stem to model for `search.*` and `t9.*`. */
object SearchContracts : ContractModels {
    override val models: Map<String, KSerializer<*>> =
        buildMap {
            op<IndexNewRequest, IndexAnswer>("search.index_new", ok = listOf("empty"))
            op<IndexReplaceRequest, IndexAnswer>("search.index_replace", err = listOf("stale_handle"))
            op<CallCountsRequest, Empty>("search.set_call_counts")
            op<QueryRequest, QueryAnswer>(
                "search.query",
                ok = listOf("digits", "cyrillic", "empty_query"),
                err = listOf("stale_handle"),
            )
            op<QueryRequest, QueryAnswer>("search.query_loose", ok = listOf("words"))
            op<MatchNumberRequest, MatchNumberAnswer>("search.match_number", ok = listOf("unknown", "unparsable"))
            op<MatchNumbersRequest, MatchNumbersAnswer>("search.match_numbers", err = listOf("stale_handle"))
            op<FoldRequest, FoldAnswer>("search.fold")
            op<T9Request, T9Answer>("t9.query", ok = listOf("by_number", "not_digits", "last_first"))
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
