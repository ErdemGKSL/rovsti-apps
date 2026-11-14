package dev.rosty.phone

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.rosty.core.model.phone.ItemState
import dev.rosty.core.model.phone.SetupItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.transition.Tab
import dev.rosty.design.transition.TabNavigator
import dev.rosty.phone.diagnostics.DiagRow
import dev.rosty.phone.diagnostics.DiagSection
import dev.rosty.phone.diagnostics.DiagnosticsScreen
import dev.rosty.phone.diagnostics.DiagnosticsUi
import dev.rosty.phone.setup.SetupAct
import dev.rosty.phone.setup.SetupButtonUi
import dev.rosty.phone.setup.SetupRowUi
import dev.rosty.phone.setup.SetupSteps
import dev.rosty.phone.setup.SetupUiState
import dev.rosty.phone.setup.StepsTail
import dev.rosty.platform.system.permissions.PermissionGroup
import dev.rosty.shell.RostyShell
import dev.rosty.shell.settings.FakeCommonSettings
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Records what the pages ask for. */
internal class PhoneHarness(
    val overlays: OverlayController = OverlayController(),
) {
    val opened = mutableListOf<String>()
    var taps = 0
    val common = FakeCommonSettings()

    fun links() = RootLinks(openSettings = { opened += "settings" }, openSetup = { opened += "setup" })

    fun settingsLinks() =
        SettingsLinks(
            openSetup = { opened += "setup" },
            openWhatsApp = { opened += "whatsapp" },
            openStats = { opened += "stats" },
            openCommon = { opened += "common-$it" },
            versionTapped = { taps++ },
            openRoute = { opened += "route-$it" },
        )
}

internal object PhoneSample {
    private fun button(
        act: SetupAct,
        primary: Boolean,
        group: PermissionGroup? = null,
    ) = SetupButtonUi(act, primary, group)

    private fun row(
        item: SetupItem,
        title: String,
        reason: String,
        state: ItemState,
        steps: SetupSteps? = null,
        vararg buttons: SetupButtonUi,
    ) = SetupRowUi(item, title, reason, state, steps, StepsTail.None, persistentListOf(*buttons))

    /** The default phone app is missing, contacts and full-screen notifications are open, the rest is done. */
    fun setup(
        roleHeld: Boolean = false,
        changedTitle: String? = null,
    ): SetupUiState {
        val rows =
            listOf(
                row(
                    SetupItem.DefaultPhoneApp,
                    "Default phone app",
                    "Lets Rosty Phone place and answer calls",
                    if (roleHeld) ItemState.Done else ItemState.NotDone,
                    if (roleHeld) null else SetupSteps.Restricted,
                    *(
                        if (roleHeld) {
                            emptyArray()
                        } else {
                            arrayOf(button(SetupAct.AppInfo, false), button(SetupAct.RequestRole, true))
                        }
                    ),
                ),
                row(
                    SetupItem.PhoneAndCallLog,
                    "Phone and call log",
                    "Shows your recent calls and places calls",
                    ItemState.Done,
                ),
                row(
                    SetupItem.Contacts,
                    "Contacts",
                    "Shows names instead of numbers",
                    ItemState.NotDone,
                    null,
                    button(SetupAct.Allow, true, PermissionGroup.CONTACTS),
                ),
                row(SetupItem.Notifications, "Notifications", "Shows incoming and ongoing calls", ItemState.Done),
                row(
                    SetupItem.FullScreenNotifications,
                    "Full-screen notifications",
                    "Shows the call screen over the lock screen",
                    ItemState.NotDone,
                    null,
                    button(SetupAct.FullScreen, true),
                ),
            )
        val done = rows.count { it.state == ItemState.Done }
        return SetupUiState(
            loaded = true,
            roleHeld = roleHeld,
            changedTitle = changedTitle,
            complete = done == rows.size,
            progressLabel = "$done of ${rows.size}",
            fraction = done.toFloat() / rows.size,
            rows = rows.toImmutableList(),
        )
    }

    /** Every row done. */
    fun setupComplete(): SetupUiState {
        val s = setup(roleHeld = true)
        val rows = s.rows.map { it.copy(state = ItemState.Done, steps = null, buttons = persistentListOf()) }
        return s.copy(complete = true, progressLabel = "5 of 5", fraction = 1f, rows = rows.toImmutableList())
    }

    /** The Xiaomi rows on top of [setup]: autostart denied, the others only the owner can confirm. */
    fun diagnostics() =
        DiagnosticsUi(
            sections =
                persistentListOf(
                    DiagSection(
                        "App",
                        persistentListOf(DiagRow("Version", "0.1.0"), DiagRow("Rust core", "0.1.0")),
                    ),
                    DiagSection(
                        "Counters",
                        persistentListOf(
                            DiagRow("Block guard", "on"),
                            DiagRow("Calls rejected by the block guard", "2"),
                            DiagRow("Silence-ringer callbacks", "5"),
                            DiagRow("Audio route change", "120 ms"),
                        ),
                    ),
                    DiagSection(
                        "Last ring",
                        persistentListOf(),
                        persistentListOf("notify 9 ms", "activity_created 300 ms", "first_frame 812 ms"),
                    ),
                    DiagSection(
                        "Call events",
                        persistentListOf(),
                        persistentListOf(
                            "onBind none",
                            "onCallAdded 1 ringing",
                            "onStateChanged 1 active",
                            "onCallRemoved 1 disconnected",
                        ),
                    ),
                ),
            report = "Rosty Phone diagnostics\n",
        )
}

/** [content] on the real shell (tab bar and chrome as in the app): the tab root of [tab] or [push] on top of it. */
@Composable
internal fun HostedPhone(
    harness: PhoneHarness,
    setup: SetupUiState,
    tab: Tab = RecentsTab,
    push: PhoneRoute? = null,
    // The body of the pushed pages that have no fixture of their own (WhatsApp, Appearance, About, Licences ...).
    extra: @Composable () -> Unit = {},
    // The Keypad tab; the placeholder until a test brings the real screen.
    keypad: @Composable () -> Unit = { KeypadPlaceholder(harness.links()) },
    // The Statistics page (E5.10).
    stats: @Composable () -> Unit = {},
    // The value on the Blocked numbers row of Settings and .
    blockedCount: Int? = 12,
    // The Notes page (E5.11).
    notes: @Composable () -> Unit = {},
) {
    val navigator =
        remember {
            TabNavigator(PhoneTabs, PhoneRoots).also {
                it.select(tab)
                if (push != null) it.push(push)
            }
        }
    val tabs = phoneTabItems("Favorites", "Recents", "Contacts", "Keypad")
    RostyShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = ::chromeFor,
        overlays = harness.overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<PhoneRoute.Favorites> {
            PhoneRootPlaceholder(
                "favorites",
                "Favorites",
                dev.rosty.design.icons.RostyIcons.Star,
                "Soon",
                setup,
                harness.links(),
            )
        }
        entry<PhoneRoute.Recents> {
            PhoneRootPlaceholder(
                "recents",
                "Recents",
                dev.rosty.design.icons.RostyIcons.Clock,
                "This screen arrives in a later update.",
                setup,
                harness.links(),
                showNotice = true,
            )
        }
        entry<PhoneRoute.Contacts> {
            PhoneRootPlaceholder(
                "contacts",
                "Contacts",
                dev.rosty.design.icons.RostyIcons.People,
                "Soon",
                setup,
                harness.links(),
            )
        }
        entry<PhoneRoute.Keypad> { keypad() }
        entry<PhoneRoute.Settings> {
            PhoneSettingsPage(
                setup,
                harness.common,
                "0.1.0",
                harness.settingsLinks(),
                blockedCount = blockedCount,
            )
        }
        entry<PhoneRoute.Stats> { stats() }
        entry<PhoneRoute.Notes> { notes() }
        entry<PhoneRoute.WhatsAppSettings> { extra() }
        entry<PhoneRoute.Appearance> { extra() }
        entry<PhoneRoute.Haptics> { extra() }
        entry<PhoneRoute.About> { extra() }
        entry<PhoneRoute.Licences> { extra() }
        entry<PhoneRoute.LicenceDetail> { extra() }
        entry<PhoneRoute.Blocked> { extra() }
        entry<PhoneRoute.QuickReplies> { extra() }
        entry<PhoneRoute.SpeedDialPage> { extra() }
        entry<PhoneRoute.SimChoices> { extra() }
        entry<PhoneRoute.Data> { extra() }
        entry<PhoneRoute.Backup> { extra() }
        entry<PhoneRoute.Diagnostics> {
            DiagnosticsScreen(PhoneSample.diagnostics(), onCopyAll = {
                harness.opened +=
                    "copy"
            })
        }
    }
}
