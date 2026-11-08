package dev.rovsti.core.ops

import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.RustHandle
import dev.rovsti.core.RustNative
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DeepFindAnswer
import dev.rovsti.core.model.contacts.DeepFindRequest
import dev.rovsti.core.model.contacts.DeepIndexNewRequest

/**
 * The detail-search index of the Contacts app (company, job title, nickname, note, address, email, website): build it
 * once per contacts payload with [open], [find] for each query, [close] when the screen ends. A closed handle answers
 * [RustError.NotFound] without a native call.
 */
class DeepIndexHandle internal constructor(
    id: Long,
    /** Contacts the index was built from. */
    val count: Int,
    private val ops: ContactsOps,
    release: (Long) -> Unit,
) : RustHandle(id, release) {
    /** Contacts whose details match [query], at most [limit], skipping the ids in [skip] (already shown by name). */
    suspend fun find(
        query: String,
        limit: Int,
        skip: List<Long> = emptyList(),
    ): Outcome<DeepFindAnswer> =
        if (released) {
            Outcome.Err(RustError.NotFound("detail index $id is closed"))
        } else {
            ops.deepFind(DeepFindRequest(id, query, skip, limit))
        }

    companion object {
        /** Builds the index; the handle is the caller's to close. */
        suspend fun open(
            drafts: List<ContactDraft>,
            ops: ContactsOps = NativeContactsOps(),
            release: (Long) -> Unit = RustNative::release,
        ): Outcome<DeepIndexHandle> =
            when (val built = ops.deepIndexNew(DeepIndexNewRequest(drafts))) {
                is Outcome.Err -> built
                is Outcome.Ok -> Outcome.Ok(DeepIndexHandle(built.value.handle, built.value.count, ops, release))
            }
    }
}
