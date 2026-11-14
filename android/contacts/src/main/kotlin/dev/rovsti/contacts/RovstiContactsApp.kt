package dev.rovsti.contacts

import android.app.Application
import dev.rovsti.contacts.people.PeopleSyncWorker
import dev.rovsti.platform.PlatformComponents
import dev.rovsti.platform.notify.PlatformChannels
import dev.rovsti.shell.HasContainer
import kotlinx.coroutines.launch

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
        // The periodic WhatsApp names and photos sync (X.1b); the work finishes at once while WhatsApp is not linked.
        // Off the main thread: WorkManager opens its database on first use.
        Thread({ PeopleSyncWorker.schedule(this) }, "people-sync-schedule").start()
        // The people index is warmed after Rust.init, so the first list already draws the cached WhatsApp pictures (X.1c).
        Thread({ container.scope.launch { runCatching { container.warmPeopleIndex() } } }, "people-index-warm").start()
    }
}
