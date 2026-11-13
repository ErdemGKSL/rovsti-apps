package dev.rovsti.contacts

import android.app.Application
import dev.rovsti.platform.PlatformComponents
import dev.rovsti.platform.notify.PlatformChannels
import dev.rovsti.shell.HasContainer

/**
 * The `Application` of Rovsti Contacts (FQCN kept from the GPUI build, ARCH 7.2). It only builds the container lazily:
 * nothing heavy runs before the first frame, and the receiver and the share provider reach the repositories through
 * [HasContainer] without an activity.
 */
class RovstiContactsApp :
    Application(),
    HasContainer<ContactsContainer> {
    override val container: ContactsContainer by lazy { ContactsContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Birthday notifications and pinned shortcuts launch this activity (PlatformComponents, ARCH 7.4).
        PlatformComponents.install(PlatformComponents(mainActivity = MainActivity::class.java))
        // Channel names and descriptions in the phone's language, again after a language change (F32).
        PlatformChannels.followLocale(this)
    }
}
