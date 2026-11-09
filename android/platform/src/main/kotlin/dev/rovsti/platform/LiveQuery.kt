package dev.rovsti.platform

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart

/** Quiet time after the last change notification before a live read queries again. */
const val CHANGE_DEBOUNCE_MS = 50L

/**
 * A provider read as a cold flow: it emits once on collect and again after every change of [uris]. A burst of
 * notifications (one provider write sends several) is one re-query, a newer change cancels a query still running, the
 * query runs on [io], and a read the app may not make ([SecurityException]) is [empty].
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
internal fun <T> liveQuery(
    resolver: ContentResolver,
    uris: List<Uri>,
    io: CoroutineDispatcher,
    empty: T,
    query: () -> T,
): Flow<T> =
    ProviderChanges
        .observe(resolver, uris)
        .debounce(CHANGE_DEBOUNCE_MS)
        .onStart { emit(Unit) }
        .conflate()
        .mapLatest {
            try {
                query()
            } catch (_: SecurityException) {
                empty
            }
        }.flowOn(io)
