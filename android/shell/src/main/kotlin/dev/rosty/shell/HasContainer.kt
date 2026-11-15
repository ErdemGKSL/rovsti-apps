package dev.rosty.shell

import android.content.Context
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.wa.share.WaLinkShareHost

/**
 * Implemented by the app's `Application`. Cold paths (an SMS receiver, a push service, `TripService`, an
 * `InCallService`) reach the repositories without an activity through [shellContainer] (ARCH 5.3).
 */
interface HasContainer<C : ShellContainer> : WaLinkShareHost {
    val container: C

    /** What `:wa`'s `WaLinkProvider` hands to a sibling Rosty app: the link this app stored, if any. */
    override suspend fun waLinkToShare(): StoredLink? = container.sharedWaLink()
}

/** The container of the app this context belongs to; the application must implement [HasContainer]. */
fun Context.shellContainer(): ShellContainer {
    val app = applicationContext
    check(app is HasContainer<*>) { "${app.javaClass.name} must implement HasContainer" }
    return app.container
}
