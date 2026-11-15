package dev.rosty.stops

import android.app.Application
import dev.rosty.platform.PlatformComponents
import dev.rosty.platform.notify.PlatformChannels
import dev.rosty.shell.HasContainer

/**
 * The `Application` of Rosty Stops (FQCN kept from the GPUI build, ARCH 7.2). It only builds the container lazily:
 * nothing heavy runs before the first frame, and the boot receiver and the trip components reach the repositories
 * through [HasContainer] without an activity.
 */
class RostyStopsApp :
    Application(),
    HasContainer<StopsContainer> {
    override val container: StopsContainer by lazy { StopsContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // The trip notification, the alarm and the restart notice open this activity (PlatformComponents, ARCH 7.4).
        PlatformComponents.install(PlatformComponents(mainActivity = MainActivity::class.java))
        // Channel names and descriptions (trip_alarm_v2, trip_problem) in the phone's language, again after a change.
        PlatformChannels.followLocale(this)
    }
}
