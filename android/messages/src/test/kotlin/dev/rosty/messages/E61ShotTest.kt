package dev.rosty.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.access.DefaultSmsCard
import dev.rosty.messages.settings.MessagesSettingsPage
import dev.rosty.messages.settings.SettingsLinks
import dev.rosty.shell.permissions.LocalPermissionController
import dev.rosty.shell.permissions.LocalSpecialAccess
import dev.rosty.shell.settings.FakeCommonSettings
import dev.rosty.testing.ShotTest
import dev.rosty.testing.ShotVariant
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** E6.1 screenshots: the default-SMS ask and the Settings page with its access rows. */
@Config(sdk = [35], application = TestMessagesApp::class)
class E61ShotTest : ShotTest() {
    private val container get() = (RuntimeEnvironment.getApplication() as TestMessagesApp).container

    @Test fun defaultSmsAsk() =
        shot("e6.1-default-sms-ask", setOf(ShotVariant.Fs2)) {
            CompositionLocalProvider(LocalSpecialAccess provides container.specialAccess) {
                Box(Modifier.fillMaxSize().background(RostyTheme.colors.groupedBackground).padding(top = Spacing.xl)) {
                    DefaultSmsCard(onResult = {})
                }
            }
        }

    @Test fun permissionRows() =
        shot("e6.1-permission-row") {
            CompositionLocalProvider(
                LocalPermissionController provides container.permissions,
                LocalSpecialAccess provides container.specialAccess,
            ) {
                MessagesSettingsPage(
                    smsRoleHeld = false,
                    settings = FakeCommonSettings(),
                    links = SettingsLinks(openWhatsApp = {}, openCommon = {}),
                    onRoleResult = {},
                )
            }
        }
}
