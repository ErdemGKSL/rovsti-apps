package dev.rovsti.shell

import android.content.Context

/**
 * Implemented by the app's `Application`. Cold paths (an SMS receiver, a push service, `TripService`, an
 * `InCallService`) reach the repositories without an activity through [shellContainer] (ARCH 5.3).
 */
interface HasContainer<C : ShellContainer> {
    val container: C
}

/** The container of the app this context belongs to; the application must implement [HasContainer]. */
fun Context.shellContainer(): ShellContainer {
    val app = applicationContext
    check(app is HasContainer<*>) { "${app.javaClass.name} must implement HasContainer" }
    return app.container
}
