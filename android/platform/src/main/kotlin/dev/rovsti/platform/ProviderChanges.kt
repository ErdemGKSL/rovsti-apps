package dev.rovsti.platform

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * Provider change notifications as a cold flow. Collecting registers one `ContentObserver` per uri (descendants
 * included) and unregisters on cancel; each change emits a `Unit`. The flow is conflated, so a burst of provider
 * writes becomes one re-query. A uri the app may not observe (a `SecurityException`, for example the blocklist when
 * the app is not the default dialer) is skipped, as the original observers did.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/ProviderObservers.kt
 */
object ProviderChanges {
    fun observe(
        resolver: ContentResolver,
        vararg uris: Uri,
    ): Flow<Unit> = observe(resolver, uris.asList())

    fun observe(
        resolver: ContentResolver,
        uris: List<Uri>,
    ): Flow<Unit> =
        callbackFlow {
            val handler = Handler(Looper.getMainLooper())
            val observer =
                object : ContentObserver(handler) {
                    override fun onChange(selfChange: Boolean) {
                        trySend(Unit)
                    }
                }
            for (uri in uris) {
                try {
                    resolver.registerContentObserver(uri, true, observer)
                } catch (_: SecurityException) {
                    // Refused (for example the blocklist for a non-default dialer): that is fine.
                } catch (_: RuntimeException) {
                }
            }
            awaitClose { resolver.unregisterContentObserver(observer) }
        }.buffer(Channel.CONFLATED)
}
