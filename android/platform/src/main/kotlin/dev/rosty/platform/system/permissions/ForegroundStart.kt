package dev.rosty.platform.system.permissions

import dev.rosty.platform.location.StartResult
import dev.rosty.platform.location.startFailure

/**
 * A refused foreground-service start never crashes the caller (ARCH 7.5). `ForegroundServiceStartNotAllowedException`
 * and `SecurityException` become [StartResult.NotAllowed]; the trip service and the phone-call service share this.
 */
object ForegroundStart {
    fun map(failure: Throwable): StartResult = startFailure(failure)
}
