package dev.rovsti.contacts

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.rovsti.design.transition.Tab
import dev.rovsti.people.ui.LocalAvatarResolver
import dev.rovsti.platform.contacts.Shortcuts
import dev.rovsti.shell.NavSpec
import dev.rovsti.shell.RovstiShellActivity
import dev.rovsti.shell.shellContainer
import kotlinx.coroutines.launch

/**
 * The one activity of Rovsti Contacts (FQCN kept, singleTop). Edge to edge, back order, refresh rate and permission
 * refresh come from [RovstiShellActivity]; this class only says which tabs there are and how an intent becomes routes.
 */
class MainActivity : RovstiShellActivity<ContactsRoute>() {
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
        if (route != null) intent.removeExtra(Shortcuts.EXTRA_CONTACT_ID)
        return route
    }

    override fun onResume() {
        super.onResume()
        // Coming to the foreground with names and photos older than the sync period: sync (X.1b). Built off the main thread.
        container.scope.launch { container.peopleSync.syncIfStale() }
    }

    @Composable
    override fun Content() {
        // Every avatar goes through the resolver (own photo, else the WhatsApp picture, else initials; X.1c).
        CompositionLocalProvider(LocalAvatarResolver provides container.avatarResolver) {
            ContactsApp(container = container, navigator = navigator)
        }
    }
}
