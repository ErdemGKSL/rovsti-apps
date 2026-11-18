package dev.rosty.shell

import android.content.Context
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.wa.share.WaLinkShareHost
import dev.rosty.wa.share.WaLockAllHost

/**
 * Implemented by the app's `Application`. Cold paths (an SMS receiver, a push service, `TripService`, an
 * `InCallService`) reach the repositories without an activity through [shellContainer] (ARCH 5.3).
 */
interface HasContainer<C : ShellContainer> :
    WaLinkShareHost,
    WaLockAllHost {
    val container: C

    /** What `:wa`'s `WaLinkProvider` hands to a sibling Rosty app: the link of this app's unlocked vault, if any. */
    override suspend fun waLinkToShare(): StoredLink? = container.sharedWaLink()

    override suspend fun waLinkOffered(): Boolean = container.waLinkOffered()

    /** A sibling's "Lock now" (`:wa`'s `WaLockAllReceiver`, round 7c). */
    override fun lockWhatsAppForSibling() = container.lockWhatsAppForSibling()
}

/** The container of the app this context belongs to; the application must implement [HasContainer]. */
fun Context.shellContainer(): ShellContainer {
    val app = applicationContext
    check(app is HasContainer<*>) { "${app.javaClass.name} must implement HasContainer" }
    return app.container
}
