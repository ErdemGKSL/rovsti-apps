package dev.rosty.messages

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.access.AccessTags
import dev.rosty.messages.inbox.InboxTags
import dev.rosty.messages.settings.SettingsTags
import dev.rosty.shell.permissions.LocalPermissionController
import dev.rosty.shell.permissions.LocalSpecialAccess
import dev.rosty.testing.ShotTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Launch smoke test: the whole app root (the real shell, the overlay host, every tab root) composes on fakes. A missing
 * provider once crashed Phone on open; this catches the same for Messages. Without the SMS role the inbox shows the
 * default-SMS ask; the Settings tab shows the access rows.
 */
@Config(sdk = [35], application = TestMessagesApp::class)
class MessagesAppLaunchTest : ShotTest() {
    private val container get() = (RuntimeEnvironment.getApplication() as TestMessagesApp).container

    @Test fun theAppRootComposesAndEveryTabOpens() {
        val navigator = TabNavigator(MessagesTabs, MessagesRoots)
        composeFrame {
            CompositionLocalProvider(
                LocalPermissionController provides container.permissions,
                LocalSpecialAccess provides container.specialAccess,
            ) {
                MessagesApp(container = container, navigator = navigator)
            }
        }
        advance(800)
        compose.onNodeWithTag(InboxTags.Root).assertExists()
        compose.onNodeWithTag(AccessTags.DefaultSmsCard).assertExists()
        compose.runOnUiThread { navigator.select(SettingsTab) }
        advance(800)
        compose.onNodeWithTag(SettingsTags.Page).assertExists()
        compose.runOnUiThread { navigator.select(WhatsAppTab) }
        advance(800)
        compose.runOnUiThread { navigator.push(MessagesRoute.NewConversation()) }
        advance(800)
        compose.runOnUiThread { navigator.push(MessagesRoute.SmsThread(0, listOf("+905550000001"))) }
        advance(800)
    }

    @Test fun withTheRoleTheAskIsGone() {
        container.fakeSpecial.heldRoles += "android.app.role.SMS"
        container.refreshSmsRole()
        val navigator = TabNavigator(MessagesTabs, MessagesRoots)
        composeFrame {
            CompositionLocalProvider(
                LocalPermissionController provides container.permissions,
                LocalSpecialAccess provides container.specialAccess,
            ) {
                MessagesApp(container = container, navigator = navigator)
            }
        }
        advance(800)
        compose.onNodeWithTag(AccessTags.DefaultSmsCard).assertDoesNotExist()
    }
}
