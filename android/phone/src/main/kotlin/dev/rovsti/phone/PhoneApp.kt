package dev.rovsti.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import dev.rovsti.phone.data.People
import dev.rovsti.phone.details.DetailsHost
import dev.rovsti.phone.details.DetailsPresenter
import dev.rovsti.phone.details.DetailsStrings
import dev.rovsti.phone.details.personTarget
import dev.rovsti.phone.diagnostics.DiagnosticsPage
import dev.rovsti.phone.diagnostics.DiagnosticsViewModel
import dev.rovsti.phone.diagnostics.SevenTaps
import dev.rovsti.phone.diagnostics.versionText
import dev.rovsti.phone.incoming.IncomingEffects
import dev.rovsti.phone.incoming.IncomingScreen
import dev.rovsti.phone.incoming.IncomingStrings
import dev.rovsti.phone.incoming.WaitingCallCard
import dev.rovsti.phone.keypad.KeypadAction
import dev.rovsti.phone.keypad.KeypadHost
import dev.rovsti.phone.keypad.KeypadScreen
import dev.rovsti.phone.keypad.KeypadViewModel
import dev.rovsti.phone.keypad.addContactIntent
import dev.rovsti.phone.recents.RecentsHost
import dev.rovsti.phone.recents.RecentsScreen
import dev.rovsti.phone.recents.RecentsSelectToolbar
import dev.rovsti.phone.recents.RecentsStrings
import dev.rovsti.phone.recents.RecentsViewModel
import dev.rovsti.phone.setup.OnResume
import dev.rovsti.phone.setup.SetupAction
import dev.rovsti.phone.setup.SetupSheetContent
import dev.rovsti.phone.setup.SetupSheetViewModel
import dev.rovsti.platform.files.ExportDocument
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.ShareIntents
import dev.rovsti.platform.telephony.KeypadTone
import dev.rovsti.shell.RovstiShell
import dev.rovsti.shell.settings.CommonSettingsPage
import dev.rovsti.shell.settings.aboutInfo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

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
private fun rememberKeypadViewModel(container: PhoneContainer): KeypadViewModel {
    val owner = LocalContext.current.findComponentActivity()
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    KeypadViewModel(
                        ops = container.ops,
                        people = container.people,
                        placer = container.placer,
                        speedDial = container.speedDial,
                        sims = container.simState.sims,
                        clipboard = container.clipboard,
                        redialEntries = { container.recents.callEntries },
                        voicemailNumber = container::voicemailNumber,
                        toneSetting = { container.store.prefs.value.settings.keypadTones },
                        systemTones = container::systemKeypadTones,
                        e164 = container::e164,
                    )
                }
            }
        ViewModelProvider(owner, factory)[KeypadViewModel::class.java]
    }
}

@Composable
private fun rememberRecentsViewModel(container: PhoneContainer): RecentsViewModel {
    val owner = LocalContext.current.findComponentActivity()
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    RecentsViewModel(
                        recents = container.recents,
                        callLog = container.callLog,
                        ops = container.ops,
                        shared = container.sharedOps,
                        chat = container.chat,
                        placer = container.placer,
                        block = container.block,
                        sims = container.simState.sims,
                        store = container.store,
                        clipboard = container.clipboard,
                        reminders = container.callReminders,
                        homeCc = container::homeCc,
                    )
                }
            }
        ViewModelProvider(owner, factory)[RecentsViewModel::class.java]
    }
}

/** Recents' Android side: the SMS composer, the contact editor, the share sheet. */
private class AndroidRecentsHost(
    private val context: Context,
    private val details: (String) -> Unit,
) : RecentsHost {
    override fun message(
        number: String,
        body: String?,
    ) = launch(ShareIntents.sendSmsTo(number, body))

    override fun addContact(number: String) = launch(addContactIntent(number, create = false))

    override fun share(text: String) = launch(Intent.createChooser(ShareIntents.text(text), null))

    override fun openDetails(key: String) = details(key)

    private fun launch(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No app handles it: nothing to show.
        }
    }
}

/** The details sheets' Android side: the SMS composer, the contact editor, the share sheet and the CSV picker. */
private class AndroidDetailsHost(
    private val context: Context,
    private val export: (String, String) -> Unit,
) : DetailsHost {
    override fun message(
        number: String,
        body: String?,
    ) = launch(ShareIntents.sendSmsTo(number, body))

    override fun addContact(number: String) = launch(addContactIntent(number, create = false))

    override fun share(text: String) = launch(Intent.createChooser(ShareIntents.text(text), null))

    override fun saveCsv(
        fileName: String,
        csv: String,
    ) = export(fileName, csv)

    private fun launch(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No app handles it: nothing to show.
        }
    }
}

/** The Keypad's Android side: the DTMF tone, the contact editor, the share sheet and the SMS app. */
private class AndroidKeypadHost(
    private val context: Context,
    private val openContacts: (Int) -> Unit,
) : KeypadHost {
    override fun tone(char: Char) {
        val index =
            when (char) {
                '*' -> STAR_TONE
                '#' -> HASH_TONE
                else -> char - '0'
            }
        KeypadTone.play(index)
    }

    override fun addContact(
        number: String,
        create: Boolean,
    ) = launch(addContactIntent(number, create))

    override fun share(text: String) = launch(Intent.createChooser(ShareIntents.text(text), null))

    override fun message(number: String) = launch(ShareIntents.sendSmsTo(number))

    override fun chooseSpeedDialContact(digit: Int) = openContacts(digit)

    private fun launch(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // No app handles it: nothing to show.
        }
    }

    private companion object {
        const val STAR_TONE = 10
        const val HASH_TONE = 11
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
    val recentsModel = rememberRecentsViewModel(container)
    val missed by recentsModel.missedCount.collectAsState()
    val selection by recentsModel.selection.collectAsState()
    val recentsStrings = RecentsStrings.current()
    val tabs =
        phoneTabItems(
            favorites = stringResource(R.string.tab_favorites),
            recents = stringResource(R.string.tab_recents),
            contacts = stringResource(R.string.tab_contacts),
            keypad = stringResource(R.string.tab_keypad),
            missed = missed,
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

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val detailsStrings = DetailsStrings.current()
    var pendingCsv by remember { mutableStateOf<Pair<String, String>?>(null) }
    val exporter =
        rememberLauncherForActivityResult(ExportDocument("text/csv")) { result ->
            val csv = pendingCsv ?: return@rememberLauncherForActivityResult
            pendingCsv = null
            val uri = (result as? FileResult.Ok)?.uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val written = container.files.writeExport(uri, text = csv.second)
                overlays.showToast(
                    if (written is FileResult.Ok) detailsStrings.saved(csv.first) else detailsStrings.saveFailed,
                )
            }
        }
    val detailsHost =
        remember(context, exporter) {
            AndroidDetailsHost(context) { name, csv ->
                pendingCsv = name to csv
                exporter.launch(name)
            }
        }
    val details =
        remember(overlays, container, detailsHost, context) {
            DetailsPresenter(
                overlays = overlays,
                host = detailsHost,
                resources = { context.resources },
                person = container::personModel,
                call = container::callModel,
            )
        }
    val openDetails: (String) -> Unit =
        remember(details, recentsModel, container) {
            { key ->
                recentsModel.rowOf(key)?.let { row ->
                    val book = (container.people.people.value as? People.Ready)?.contacts
                    details.presentPerson(personTarget(row, book))
                }
            }
        }

    val incomingStrings = IncomingStrings.current()
    val incoming =
        remember(container, incomingStrings) { container.incomingModel(container.scope, incomingStrings.unknown) }
    val incomingUi by incoming.state.collectAsState()
    IncomingEffects(incoming, overlays) { toCall ->
        // The ringing call is gone: close its screen, and open In-Call when it was answered.
        if (navigator.current is PhoneRoute.Incoming) {
            navigator.pop()
            if (toCall) navigator.push(PhoneRoute.InCall)
        }
    }
    RovstiShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = { route -> chromeFor(route, selecting = selection != null) },
        overlays = overlays,
        modifier = Modifier.fillMaxSize(),
        chrome = {
            // A second caller while a call is live: the card over the call (the Incoming screen has its own buttons).
            val waiting = incomingUi.waiting
            if (waiting != null && navigator.current !is PhoneRoute.Incoming) {
                WaitingCallCard(
                    waiting,
                    incomingStrings,
                    incoming::onAction,
                    Modifier.align(Alignment.TopCenter),
                )
            }
        },
        accessory =
            selection?.let { chosen ->
                { modifier, inline ->
                    RecentsSelectToolbar(chosen, recentsStrings, recentsModel::onAction, modifier, inline)
                }
            },
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
            val host = remember(context, openDetails) { AndroidRecentsHost(context, openDetails) }
            RecentsScreen(
                model = recentsModel,
                host = host,
                links = links,
                showNotice = setup.loaded && !setup.roleHeld,
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
        entry<PhoneRoute.Keypad> {
            val model = rememberKeypadViewModel(container)
            val context = LocalContext.current
            val host = remember(context, navigator) { AndroidKeypadHost(context) { navigator.select(ContactsTab) } }
            val dial by container.pendingDial.collectAsState()
            LaunchedEffect(dial) {
                // A number from a `tel:` link: it lands in the field, once.
                dial?.let {
                    model.onAction(KeypadAction.Restore(it.text))
                    container.pendingDial.value = null
                }
            }
            KeypadScreen(model, host, links)
        }
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
        // Incoming (E5.6): the full-screen call screen. Its sheets, toasts and leaving run in IncomingEffects above.
        entry<PhoneRoute.Incoming> { IncomingScreen(incoming) }
        // InCall (E5.7a) arrives in the next step; until then the route a notification can reach shows an honest
        // placeholder, and the notification itself still ends the call (AD-2).
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
