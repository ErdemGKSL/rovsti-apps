package dev.rovsti.contacts

import android.Manifest
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.shell.permissions.LocalPermissionController
import dev.rovsti.shell.permissions.LocalSpecialAccess
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/** The app on fakes: no native library, no provider; the permission state is the real controller over the shadow. */
internal class AppFixture(
    granted: Boolean,
    val app: Application = RuntimeEnvironment.getApplication(),
) {
    val container = CountingContactsContainer(app)
    val navigator = TabNavigator(ContactsTabs, ContactsRoots)

    init {
        val permissions = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
        if (granted) shadowOf(app).grantPermissions(*permissions) else shadowOf(app).denyPermissions(*permissions)
        container.permissions.refreshNow { false }
    }

    @Composable
    fun Content() {
        CompositionLocalProvider(
            LocalPermissionController provides container.permissions,
            LocalSpecialAccess provides container.specialAccess,
        ) {
            ContactsApp(container = container, navigator = navigator)
        }
    }
}
