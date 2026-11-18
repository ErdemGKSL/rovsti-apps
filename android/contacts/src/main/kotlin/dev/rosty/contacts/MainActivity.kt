package dev.rosty.contacts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.rosty.design.transition.Tab
import dev.rosty.people.ui.LocalAvatarResolver
import dev.rosty.platform.contacts.Shortcuts
import dev.rosty.platform.notify.BirthdayReminders
import dev.rosty.shell.NavSpec
import dev.rosty.shell.RostyShellActivity
import dev.rosty.shell.shellContainer
import kotlinx.coroutines.launch

/**
 * The one activity of Rosty Contacts (FQCN kept, singleTop). Edge to edge, back order, refresh rate and permission
 * refresh come from [RostyShellActivity]; this class only says which tabs there are and how an intent becomes routes.
 */
class MainActivity : RostyShellActivity<ContactsRoute>() {
    override val container: ContactsContainer get() = shellContainer() as ContactsContainer

    override val navSpec: NavSpec<ContactsRoute> =
        NavSpec(ContactsTabs, ContactsRoots, ContactsRoute.serializer())

    override fun routeFor(extras: Bundle?): Pair<Tab, List<ContactsRoute>>? = contactsRouteFor(null, null, null, extras)

    override fun routeFor(intent: Intent): Pair<Tab, List<ContactsRoute>>? {
        val stream =
            if (intent.action == Intent.ACTION_SEND) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.toString()
            } else {
                null
            }
        val route = contactsRouteFor(intent.action, intent.data?.toString(), stream, intent.extras)
        // A link is used once: a rotation or a restart must not open the same card again (AD-CT8).
        if (route != null) {
            intent.removeExtra(Shortcuts.EXTRA_CONTACT_ID)
            intent.removeExtra(BirthdayReminders.EXTRA_MAKE_CARD)
        }
        return route
    }

    override fun onResume() {
        super.onResume()
        // Coming to the foreground with names and photos older than the sync period: sync (X.1b). Built off the main thread.
        // While the WhatsApp vault is locked nothing reaches the gateway: the unlock runs the same trigger.
        container.scope.launch { container.peopleSyncWhenUnlocked()?.syncIfStale() }
    }

    @Composable
    override fun Content() {
        // Every avatar goes through the resolver (own photo, else the WhatsApp picture, else initials; X.1c).
        CompositionLocalProvider(LocalAvatarResolver provides container.avatarResolver) {
            ContactsApp(container = container, navigator = navigator)
        }
    }
}
