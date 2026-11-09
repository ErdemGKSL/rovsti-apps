package dev.rovsti.platform.system.permissions

import dev.rovsti.platform.location.StartResult
import dev.rovsti.platform.location.startFailure

/**
 * A refused foreground-service start never crashes the caller (ARCH 7.5). `ForegroundServiceStartNotAllowedException`
 * and `SecurityException` become [StartResult.NotAllowed]; the trip service and the phone-call service share this.
 */
object ForegroundStart {
    fun map(failure: Throwable): StartResult = startFailure(failure)
}
