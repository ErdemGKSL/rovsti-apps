package dev.rovsti.phone

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.phone.diagnostics.DiagnosticsPage
import dev.rovsti.phone.diagnostics.DiagnosticsViewModel
import dev.rovsti.phone.diagnostics.SevenTaps
import dev.rovsti.phone.diagnostics.versionText
import dev.rovsti.phone.setup.OnResume
import dev.rovsti.phone.setup.SetupAction
import dev.rovsti.phone.setup.SetupSheetContent
import dev.rovsti.phone.setup.SetupSheetViewModel
import dev.rovsti.shell.RovstiShell
import dev.rovsti.shell.settings.CommonSettingsPage
import dev.rovsti.shell.settings.aboutInfo
import kotlinx.collections.immutable.persistentListOf

/** The setup sheet's view model, on the activity so the sheet, the notice line and Settings read one checklist. */
@Composable
fun rememberSetupViewModel(container: PhoneContainer): SetupSheetViewModel {
    val owner = LocalContext.current.findComponentActivity()
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    SetupSheetViewModel(
                        ops = container.ops,
                        facts = container.setupFacts,
                        memory = container.setupMemory,
                        permission = { container.permissions.state(it).value },
                    )
                }
            }
        ViewModelProvider(owner, factory)[SetupSheetViewModel::class.java]
    }
}

@Composable
private fun rememberDiagnosticsViewModel(container: PhoneContainer): DiagnosticsViewModel {
    val owner = LocalContext.current.findComponentActivity()
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    DiagnosticsViewModel(
                        calls = container.calls,
                        coreVersion = { container.rust.versionText() },
                        ringTimeline = IncomingPerf::report,
                        clipboard = container.clipboard,
                        appVersion = container.setupFacts.appVersion,
                    )
                }
            }
        // A new view model each visit: the page shows the counters of now.
        ViewModelProvider(owner, factory)[DiagnosticsViewModel::class.java]
    }
}

/**
 * The screens of Rovsti Phone: one [RovstiShell] over the activity's navigator.
 *
 * The Setup sheet opens by itself once, while Rovsti is not the default phone app; after that the shield on each root
 * and the Setup row in Settings open it. It re-reads Android on every resume, so a change made in the system Settings
 * (a permission taken back) reopens the row it affected (`setup.reopened`).
 */
@Composable
fun PhoneApp(
    container: PhoneContainer,
    navigator: TabNavigator<PhoneRoute>,
    overlays: OverlayController = remember { OverlayController() },
) {
    val tabs =
        phoneTabItems(
            favorites = stringResource(R.string.tab_favorites),
            recents = stringResource(R.string.tab_recents),
            contacts = stringResource(R.string.tab_contacts),
            keypad = stringResource(R.string.tab_keypad),
        )
    val pop: () -> Unit =
        remember(navigator) {
            {
                navigator.pop()
                Unit
            }
        }
    // A long press on a back button jumps to the root of the tab: selecting the current tab does that.
    val popToRoot: () -> Unit = remember(navigator) { { navigator.select(navigator.currentTab) } }

    val setupModel = rememberSetupViewModel(container)
    val setup by setupModel.state.collectAsState()
    OnResume { setupModel.onAction(SetupAction.Refresh) }
    val setupTitle = stringResource(R.string.setup_open)
    val sheets = remember(overlays, container) { SetupSheetPresenter(overlays, container, setupModel, setupTitle) }
    // First launch: offer the sheet once while the role is missing (never over a call).
    LaunchedEffect(setup.loaded, setup.roleHeld) {
        val onCall = navigator.current.let { it is PhoneRoute.Incoming || it == PhoneRoute.InCall }
        if (setup.loaded && !setup.roleHeld && !onCall && !container.setupMemory.offered()) {
            container.setupMemory.markOffered()
            sheets.present()
        }
    }
    val links =
        remember(navigator, sheets) {
            RootLinks(
                openSettings = { navigator.push(PhoneRoute.Settings) },
                openSetup = sheets::present,
            )
        }
    val taps = remember { SevenTaps() }
    val whatsAppToast = stringResource(R.string.wa_handoff_toast)
    val pending by container.pendingHandoff.collectAsState()
    LaunchedEffect(pending) {
        // A WhatsApp call handed over by a sibling app: the number shows on the Keypad; WhatsApp calls come with E5.8.
        if (pending != null) {
            overlays.showToast(whatsAppToast)
            container.pendingHandoff.value = null
        }
    }

    RovstiShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = ::chromeFor,
        overlays = overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<PhoneRoute.Favorites> {
            PhoneRootPlaceholder(
                route = "favorites",
                title = stringResource(R.string.tab_favorites),
                icon = RovstiIcons.Star,
                message = stringResource(R.string.coming_message),
                setup = setup,
                links = links,
            )
        }
        entry<PhoneRoute.Recents> {
            PhoneRootPlaceholder(
                route = "recents",
                title = stringResource(R.string.tab_recents),
                icon = RovstiIcons.Clock,
                message = stringResource(R.string.coming_message),
                setup = setup,
                links = links,
                showNotice = true,
            )
        }
        entry<PhoneRoute.Contacts> {
            PhoneRootPlaceholder(
                route = "contacts",
                title = stringResource(R.string.tab_contacts),
                icon = RovstiIcons.People,
                message = stringResource(R.string.coming_message),
                setup = setup,
                links = links,
            )
        }
        entry<PhoneRoute.Keypad> { KeypadPlaceholder(links) }
        entry<PhoneRoute.Settings> {
            val context = LocalContext.current
            val version = remember(context) { context.aboutInfo(context.getString(R.string.app_name)).versionName }
            PhoneSettingsPage(
                setup = setup,
                settings = container.commonSettings,
                versionName = version,
                links =
                    SettingsLinks(
                        openSetup = sheets::present,
                        openWhatsApp = { navigator.push(PhoneRoute.WhatsAppSettings) },
                        openCommon = { page ->
                            navigator.push(
                                when (page) {
                                    CommonSettingsPage.Appearance -> PhoneRoute.Appearance
                                    CommonSettingsPage.Haptics -> PhoneRoute.Haptics
                                    CommonSettingsPage.About -> PhoneRoute.About
                                    CommonSettingsPage.Licences -> PhoneRoute.Licences
                                },
                            )
                        },
                        versionTapped = {
                            if (taps.tap(
                                    android.os.SystemClock.elapsedRealtime(),
                                )
                            ) {
                                navigator.push(PhoneRoute.Diagnostics)
                            }
                        },
                    ),
                onBack = pop,
                onBackLongClick = popToRoot,
            )
        }
        entry<PhoneRoute.Diagnostics> {
            DiagnosticsPage(rememberDiagnosticsViewModel(container), onBack = pop, onBackLongClick = popToRoot)
        }
        entry<PhoneRoute.WhatsAppSettings> { PhoneWhatsAppSettings(container) }
        entry<PhoneRoute.Appearance> { PhoneAppearanceEntry(container.commonSettings, pop, popToRoot) }
        entry<PhoneRoute.Haptics> { PhoneHapticsEntry(container.commonSettings, pop, popToRoot) }
        entry<PhoneRoute.About> {
            PhoneAboutEntry(container, onOpenLicences = { navigator.push(PhoneRoute.Licences) }, pop, popToRoot)
        }
        entry<PhoneRoute.Licences> {
            PhoneLicencesEntry(onOpen = { navigator.push(PhoneRoute.LicenceDetail(it)) }, pop, popToRoot)
        }
        entry<PhoneRoute.LicenceDetail> { route -> PhoneLicenceDetailEntry(route.id, pop, popToRoot) }
        // Incoming (E5.6) and InCall (E5.7a) arrive in later steps; until then the route a notification can reach
        // shows an honest placeholder, and the notification itself still rings, answers and declines (AD-2).
        entry<PhoneRoute.Incoming> {
            PhoneRootPlaceholder(
                route = "incoming",
                title = stringResource(R.string.tab_recents),
                icon = RovstiIcons.Phone,
                message = stringResource(R.string.coming_message),
                setup = setup,
                links = links,
            )
        }
        entry<PhoneRoute.InCall> {
            PhoneRootPlaceholder(
                route = "incall",
                title = stringResource(R.string.tab_recents),
                icon = RovstiIcons.Phone,
                message = stringResource(R.string.coming_message),
                setup = setup,
                links = links,
            )
        }
    }
}

/** Presents the Setup sheet, one at a time. */
class SetupSheetPresenter(
    private val overlays: OverlayController,
    private val container: PhoneContainer,
    private val model: SetupSheetViewModel,
    private val title: String,
) {
    private var entry: SheetEntry? = null

    fun present() {
        val open = entry
        if (open != null && !open.isRemovable) return
        model.onAction(SetupAction.Refresh)
        entry =
            overlays.present(
                SheetEntry(
                    title = title,
                    detents = persistentListOf(Detent.Medium, Detent.Large),
                    initialDetent = Detent.Large,
                ) { _ ->
                    SetupSheetContent(model, container.specialAccess, container.permissions)
                },
            ) as? SheetEntry
    }
}
