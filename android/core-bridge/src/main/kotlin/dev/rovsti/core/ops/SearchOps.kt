package dev.rovsti.core.ops

import dev.rovsti.core.NativeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.core.RustError
import dev.rovsti.core.RustHandle
import dev.rovsti.core.RustNative
import dev.rovsti.core.call
import dev.rovsti.core.model.core.Empty
import dev.rovsti.core.model.search.CallCount
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
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.model.search.QueryAnswer
import dev.rovsti.core.model.search.QueryRequest
import dev.rovsti.core.model.search.T9Answer
import dev.rovsti.core.model.search.T9Request
import dev.rovsti.core.model.search.WantedNumber
import dev.rovsti.core.model.search.WireContact

/**
 * Contact search and T9 over a native index. [indexNew] sends the contacts once and answers a handle; every query
 * then sends only the typed text. Most callers use [ContactIndexHandle] instead of the raw methods.
 */
interface SearchOps {
    /** Builds an index (and its T9 memory) from the contacts payload. Close the handle with `RustNative.release`. */
    suspend fun indexNew(req: IndexNewRequest): Outcome<IndexAnswer>

    /** Swaps the contacts of an open index. */
    suspend fun indexReplace(req: IndexReplaceRequest): Outcome<IndexAnswer>

    /** Sets the calls per contact that rank the T9 strip; replaces earlier counts. */
    suspend fun setCallCounts(req: CallCountsRequest): Outcome<Empty>

    /** Contacts whose name, email or number contains the query, in provider order. */
    suspend fun query(req: QueryRequest): Outcome<QueryAnswer>

    /** Like [query], then word-prefix and initials matches ("ay" finds Ayse Yilmaz). */
    suspend fun queryLoose(req: QueryRequest): Outcome<QueryAnswer>

    /** The contact that owns a number. */
    suspend fun matchNumber(req: MatchNumberRequest): Outcome<MatchNumberAnswer>

    /** The contact id behind each number (`rovsti_core::caller_id`), in request order. */
    suspend fun matchNumbers(req: MatchNumbersRequest): Outcome<MatchNumbersAnswer>

    /** Case- and accent-folds text the way the index does. */
    suspend fun fold(req: FoldRequest): Outcome<FoldAnswer>

    /** The suggestions for the digits typed so far (at most 50, best first). */
    suspend fun t9(req: T9Request): Outcome<T9Answer>

    companion object {
        /** The Rust op behind each method. */
        val ops: List<String> =
            listOf(
                "search.index_new",
                "search.index_replace",
                "search.set_call_counts",
                "search.query",
                "search.query_loose",
                "search.match_number",
                "search.match_numbers",
                "search.fold",
                "t9.query",
            )
    }
}

/** [SearchOps] over a [RustCore]; the real core runs every call on `RustDispatcher`. */
class NativeSearchOps(
    private val core: RustCore = NativeRustCore(),
) : SearchOps {
    override suspend fun indexNew(req: IndexNewRequest) =
        core.call("search.index_new", req, IndexNewRequest.serializer(), IndexAnswer.serializer())

    override suspend fun indexReplace(req: IndexReplaceRequest) =
        core.call("search.index_replace", req, IndexReplaceRequest.serializer(), IndexAnswer.serializer())

    override suspend fun setCallCounts(req: CallCountsRequest) =
        core.call("search.set_call_counts", req, CallCountsRequest.serializer(), Empty.serializer())

    override suspend fun query(req: QueryRequest) =
        core.call("search.query", req, QueryRequest.serializer(), QueryAnswer.serializer())

    override suspend fun queryLoose(req: QueryRequest) =
        core.call("search.query_loose", req, QueryRequest.serializer(), QueryAnswer.serializer())

    override suspend fun matchNumber(req: MatchNumberRequest) =
        core.call("search.match_number", req, MatchNumberRequest.serializer(), MatchNumberAnswer.serializer())

    override suspend fun matchNumbers(req: MatchNumbersRequest) =
        core.call("search.match_numbers", req, MatchNumbersRequest.serializer(), MatchNumbersAnswer.serializer())

    override suspend fun fold(req: FoldRequest) =
        core.call("search.fold", req, FoldRequest.serializer(), FoldAnswer.serializer())

    override suspend fun t9(req: T9Request) = core.call("t9.query", req, T9Request.serializer(), T9Answer.serializer())
}

/**
 * An open contact index. Create it with [open] when the contacts load, keep it for the screen's life and [close] it
 * (a closed handle answers [RustError.NotFound] without a native call). Queries run on the Rust dispatcher, never on
 * the main thread.
 */
class ContactIndexHandle internal constructor(
    id: Long,
    /** Contacts in the index when it was opened or last replaced. */
    val count: Int,
    private val ops: SearchOps,
    release: (Long) -> Unit,
) : RustHandle(id, release) {
    private fun <T> guarded(call: suspend () -> Outcome<T>): suspend () -> Outcome<T> =
        {
            if (released) Outcome.Err(RustError.NotFound("contact index $id is closed")) else call()
        }

    suspend fun query(
        text: String,
        limit: Int? = null,
    ): Outcome<QueryAnswer> = guarded { ops.query(QueryRequest(id, text, limit)) }()

    suspend fun queryLoose(
        text: String,
        limit: Int? = null,
    ): Outcome<QueryAnswer> = guarded { ops.queryLoose(QueryRequest(id, text, limit)) }()

    suspend fun matchNumber(number: String): Outcome<MatchNumberAnswer> =
        guarded { ops.matchNumber(MatchNumberRequest(id, number)) }()

    /** The contact id behind each of [numbers] (null: nobody), in order. */
    suspend fun matchNumbers(
        numbers: List<WantedNumber>,
        homeCc: String?,
    ): Outcome<MatchNumbersAnswer> = guarded { ops.matchNumbers(MatchNumbersRequest(id, numbers, homeCc)) }()

    suspend fun replace(contacts: List<WireContact>): Outcome<IndexAnswer> =
        guarded { ops.indexReplace(IndexReplaceRequest(id, contacts)) }()

    suspend fun setCallCounts(counts: Map<Long, Int>): Outcome<Empty> =
        guarded { ops.setCallCounts(CallCountsRequest(id, counts.map { CallCount(it.key, it.value) })) }()

    suspend fun t9(
        digits: String,
        nameFormat: NameFormat? = null,
    ): Outcome<T9Answer> = guarded { ops.t9(T9Request(id, digits, nameFormat)) }()

    companion object {
        /** Builds the index; the handle is the caller's to close. */
        suspend fun open(
            contacts: List<WireContact>,
            nameFormat: NameFormat? = null,
            ops: SearchOps = NativeSearchOps(),
            release: (Long) -> Unit = RustNative::release,
        ): Outcome<ContactIndexHandle> =
            when (val built = ops.indexNew(IndexNewRequest(contacts, nameFormat))) {
                is Outcome.Err -> built
                is Outcome.Ok -> Outcome.Ok(ContactIndexHandle(built.value.handle, built.value.count, ops, release))
            }
    }
}
