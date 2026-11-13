package dev.rovsti.phone

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.WaHandoffRequest
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.phone.contacts.PhoneContactsScreen
import dev.rovsti.phone.contacts.PhoneContactsViewModel
import dev.rovsti.phone.data.People
import dev.rovsti.phone.details.DetailsHost
import dev.rovsti.phone.details.DetailsPresenter
import dev.rovsti.phone.details.DetailsStrings
import dev.rovsti.phone.details.PersonTarget
import dev.rovsti.phone.details.TargetNumber
import dev.rovsti.phone.details.personTarget
import dev.rovsti.phone.details.photo.LocalPersonPhotoOpener
import dev.rovsti.phone.details.photo.rememberPersonPhotoOpener
import dev.rovsti.phone.diagnostics.DiagnosticsLabels
import dev.rovsti.phone.diagnostics.DiagnosticsPage
import dev.rovsti.phone.diagnostics.DiagnosticsViewModel
import dev.rovsti.phone.diagnostics.SevenTaps
import dev.rovsti.phone.diagnostics.versionText
import dev.rovsti.phone.favorites.FavPerson
import dev.rovsti.phone.favorites.FavoritesScreen
import dev.rovsti.phone.favorites.FavoritesViewModel
import dev.rovsti.phone.incall.InCallAction
import dev.rovsti.phone.incall.InCallEffects
import dev.rovsti.phone.incall.InCallHost
import dev.rovsti.phone.incall.InCallScreen
import dev.rovsti.phone.incall.InCallSheets
import dev.rovsti.phone.incall.InCallStrings
import dev.rovsti.phone.incall.MiniCallPill
import dev.rovsti.phone.incall.rememberCallTimer
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
import dev.rovsti.phone.setup.SetupEffectsRunner
import dev.rovsti.phone.setup.SetupSheetContent
import dev.rovsti.phone.setup.SetupSheetViewModel
import dev.rovsti.phone.stats.StatsHost
import dev.rovsti.phone.stats.StatsPage
import dev.rovsti.phone.stats.StatsViewModel
import dev.rovsti.phone.wa.WaNotice
import dev.rovsti.phone.wa.WaRefusal
import dev.rovsti.platform.files.ExportDocument
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.files.ShareIntents
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.platform.telephony.KeypadTone
import dev.rovsti.shell.RovstiShell
import dev.rovsti.shell.settings.CommonSettingsPage
import dev.rovsti.shell.settings.aboutInfo
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
                        whatsAppAvailable = { container.waLauncher.available.value },
                        placeWhatsApp = container.waLauncher::place,
                    )
                }
            }
        ViewModelProvider(owner, factory)[KeypadViewModel::class.java]
    }
}

@Composable
private fun rememberFavoritesViewModel(container: PhoneContainer): FavoritesViewModel {
    val owner = LocalContext.current.findComponentActivity()
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    FavoritesViewModel(
                        people = container.people,
                        store = container.store,
                        contactsOps = container.contactsOps,
                        phoneOps = container.ops,
                        contacts = container.contacts,
                        callLog = container.callLog,
                        callLogPermission = container.permissions.state(PermissionGroup.CALL_LOG),
                        placer = container.placer,
                        ids = container.callerIds,
                    )
                }
            }
        ViewModelProvider(owner, factory)[FavoritesViewModel::class.java]
    }
}

@Composable
private fun rememberContactsViewModel(container: PhoneContainer): PhoneContactsViewModel {
    val owner = LocalContext.current.findComponentActivity()
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer {
                    PhoneContactsViewModel(
                        people = container.people,
                        store = container.store,
                        ops = container.contactsOps,
                    )
                }
            }
        ViewModelProvider(owner, factory)[PhoneContactsViewModel::class.java]
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
                        placeWhatsApp = container.waLauncher::place,
                        forgetWhatsApp = container.waCalls::forget,
                        whatsAppAvailable = container.waLauncher.available,
                    )
                }
            }
        ViewModelProvider(owner, factory)[RecentsViewModel::class.java]
    }
}

/** Recents' Android side: the SMS composer, the contact editor, the share sheet. */
private class AndroidRecentsHost(
    private val starter: SystemStarter,
    private val details: (String, () -> Unit) -> Unit,
    private val onLeave: () -> Unit = {},
) : RecentsHost {
    override fun message(
        number: String,
        body: String?,
    ) = launch(ShareIntents.sendSmsTo(number, body), onLeave)

    override fun addContact(number: String) = launch(addContactIntent(number, create = false))

    override fun share(text: String) = launch(Intent.createChooser(ShareIntents.text(text), null))

    override fun openDetails(
        key: String,
        onDismissed: () -> Unit,
    ) = details(key, onDismissed)

    /** Starts [intent]; [opened] runs when an app took it, a toast says so when none did (F34). */
    private fun launch(
        intent: Intent,
        opened: () -> Unit = {},
    ) {
        starter.start(intent, opened)
    }
}

/** The details sheets' Android side: the SMS composer, the contact editor, the share sheet and the CSV picker. */
private class AndroidDetailsHost(
    private val starter: SystemStarter,
    private val export: (String, String) -> Unit,
    private val onLeave: () -> Unit = {},
) : DetailsHost {
    override fun message(
        number: String,
        body: String?,
    ) = launch(ShareIntents.sendSmsTo(number, body), onLeave)

    override fun addContact(number: String) = launch(addContactIntent(number, create = false))

    override fun editContact(contactId: Long) =
        launch(
            Intent(Intent.ACTION_EDIT).setDataAndType(
                android.content.ContentUris.withAppendedId(
                    android.provider.ContactsContract.Contacts.CONTENT_URI,
                    contactId,
                ),
                android.provider.ContactsContract.Contacts.CONTENT_ITEM_TYPE,
            ),
        )

    override fun share(text: String) = launch(Intent.createChooser(ShareIntents.text(text), null))

    override fun saveCsv(
        fileName: String,
        csv: String,
    ) = export(fileName, csv)

    /** Starts [intent]; [opened] runs when an app took it, a toast says so when none did (F34). */
    private fun launch(
        intent: Intent,
        opened: () -> Unit = {},
    ) {
        starter.start(intent, opened)
    }
}

/** The Keypad's Android side: the DTMF tone, the contact editor, the share sheet and the SMS app. */
private class AndroidKeypadHost(
    private val starter: SystemStarter,
    private val openContacts: (Int) -> Unit,
    private val onLeave: () -> Unit = {},
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

    override fun message(number: String) = launch(ShareIntents.sendSmsTo(number), onLeave)

    override fun chooseSpeedDialContact(digit: Int) = openContacts(digit)

    /** Starts [intent]; [opened] runs when an app took it, a toast says so when none did (F34). */
    private fun launch(
        intent: Intent,
        opened: () -> Unit = {},
    ) {
        starter.start(intent, opened)
    }

    private companion object {
        const val STAR_TONE = 10
        const val HASH_TONE = 11
    }
}

/** A Statistics model for each visit of the page: it rebuilds from the call log and ends with the page. */
@Composable
private fun rememberStatsViewModel(container: PhoneContainer): StatsViewModel {
    val scope = remember(container) { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val model = remember(container, scope) { container.statsModel(scope) }
    DisposableEffect(model) { onDispose { model.close() } }
    return model
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
                        labels = DiagnosticsLabels.from(owner.resources),
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
    // Back in front on another day (or with another time format): the day labels and times are rebuilt.
    OnResume { container.recents.refreshIfStale() }
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
        // A WhatsApp call handed over by a sibling app (AD-WA10): the core says which number it dials, and it is placed.
        val jid = pending ?: return@LaunchedEffect
        container.pendingHandoff.value = null
        val number =
            (container.ops.waHandoffNumber(WaHandoffRequest("wa_call", jid)) as? Outcome.Ok)?.value?.number
        if (number != null) container.waLauncher.place(number) else overlays.showToast(whatsAppToast)
    }
    // Answer or Decline tapped on a WhatsApp call notification: taken once, after the core is ready (waActions waits).
    val waAction by container.pendingWaCallAction.collectAsState()
    LaunchedEffect(waAction) {
        val payload = waAction ?: return@LaunchedEffect
        container.pendingWaCallAction.value = null
        container.waActions.offer(payload)
    }
    val waMissing = stringResource(R.string.wa_notice_microphone)
    val waFailed = stringResource(R.string.wa_notice_failed)
    val waRefused =
        WaRefusal.entries.associateWith { reason ->
            stringResource(
                when (reason) {
                    WaRefusal.NoNumber -> R.string.wa_refused_no_number
                    WaRefusal.NotLinked -> R.string.wa_refused_not_linked
                    WaRefusal.Busy -> R.string.wa_refused_busy
                    WaRefusal.NeedsCountryCode -> R.string.wa_refused_country_code
                },
            )
        }
    val activity = LocalContext.current.findComponentActivity()
    val microphone =
        rememberLauncherForActivityResult(container.permissions.contract(PermissionGroup.MICROPHONE)) { granted ->
            if (granted.values.all { it }) {
                container.permissions.refresh(activity)
                container.waLauncher.retry()
            } else {
                overlays.showToast(waMissing)
            }
        }
    LaunchedEffect(container) {
        container.waNotices.flow.collect { notice ->
            when (notice) {
                WaNotice.NeedsMicrophone -> microphone.launch(Unit)
                WaNotice.Failed -> overlays.showToast(waFailed)
                is WaNotice.Refused -> overlays.showToast(waRefused.getValue(notice.reason))
            }
        }
    }

    val context = LocalContext.current
    // Other apps' screens; "no app handles it" is a toast, never silence (F34).
    val starter = remember(context, overlays) { SystemStarter(context) { overlays.showToast(it) } }
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
        remember(starter, exporter, container) {
            AndroidDetailsHost(
                starter,
                export = { name, csv ->
                    pendingCsv = name to csv
                    exporter.launch(name)
                },
                onLeave = container.leaves::leave,
            )
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
    val openDetails: (String, () -> Unit) -> Unit =
        remember(details, recentsModel, container) {
            { key, onDismissed ->
                recentsModel.rowOf(key)?.let { row ->
                    val book = (container.people.people.value as? People.Ready)?.contacts
                    details.presentPerson(personTarget(row, book), onDismissed)
                }
            }
        }

    val incomingStrings = IncomingStrings.current()
    val incoming =
        remember(container, incomingStrings) { container.incomingModel(container.scope, incomingStrings.unknown) }
    val incomingUi by incoming.state.collectAsState()
    DisposableEffect(container, incoming) {
        container.ringKey = incoming::onRingKey
        onDispose { if (container.ringKey != null) container.ringKey = null }
    }
    IncomingEffects(incoming, overlays) { toCall ->
        // The ringing call is gone: close its screen, and open In-Call when it was answered.
        if (navigator.current is PhoneRoute.Incoming) {
            navigator.pop()
            if (toCall) navigator.push(PhoneRoute.InCall)
        }
    }
    // A WhatsApp call that rings (the gateway event or the FCM wake brought it): its Incoming page opens once.
    val ringingWa = incomingUi.caller?.takeIf { it.whatsapp }?.callId
    LaunchedEffect(ringingWa) {
        val id = ringingWa ?: return@LaunchedEffect
        if ((navigator.current as? PhoneRoute.Incoming)?.callId != id) navigator.push(PhoneRoute.Incoming(id))
    }
    val inCallStrings = InCallStrings.current()
    val inCall =
        remember(container, incomingStrings, inCallStrings) {
            container.inCallModel(container.scope, incomingStrings.unknown, inCallStrings.conference)
        }
    val inCallUi by inCall.state.collectAsState()
    val favoritesModel = rememberFavoritesViewModel(container)
    val inCallSheets =
        remember(overlays, inCallStrings, inCall, starter, favoritesModel) {
            InCallSheets(
                overlays,
                inCallStrings,
                inCall,
                AndroidInCallHost(starter),
                container.scope,
                favoritesModel.state,
            )
        }
    InCallEffects(inCall, inCallSheets, overlays, inCallStrings) {
        // The ended summary has been shown: close the page (a collapsed call has nothing to close).
        if (navigator.current == PhoneRoute.InCall) navigator.pop()
    }
    // A call placed from anywhere (or an SMS opened) and a call page opening close every sheet, menu and the keyboard.
    CloseOverlaysOnLeave(
        leaves = container.leaves.flow,
        overlays = overlays,
        onCallPage = navigator.current.let { it is PhoneRoute.Incoming || it == PhoneRoute.InCall },
    )
    // A call that was placed or arrived by itself opens its page once; collapsing it keeps it down until the user asks.
    var openedCall by remember { mutableIntStateOf(NO_CALL) }
    val liveCallId = inCallUi.call?.takeIf { inCallUi.live }?.callId
    LaunchedEffect(liveCallId) {
        val id = liveCallId ?: return@LaunchedEffect
        val here = navigator.current
        if (here is PhoneRoute.Incoming || here == PhoneRoute.InCall) {
            openedCall = id
        } else if (id != openedCall) {
            openedCall = id
            navigator.push(PhoneRoute.InCall)
        }
    }
    val miniTimer = rememberCallTimer(inCallUi.call, inCall::now, inCall::elapsed)
    val contactsModel = rememberContactsViewModel(container)
    val openContact: (Long, () -> Unit) -> Unit =
        remember(details, container) {
            { id, onDismissed ->
                val book = (container.people.people.value as? People.Ready)?.contacts
                book?.firstOrNull { it.id == id }?.let { details.presentPerson(personTarget(it), onDismissed) }
            }
        }
    val openFavorite: (FavPerson) -> Unit =
        remember(details, openContact) {
            { person ->
                val id = person.contactId
                if (id != null) {
                    openContact(id) {}
                } else {
                    val number = person.number.orEmpty()
                    details.presentPerson(
                        PersonTarget(person.name, persistentListOf(TargetNumber(number, person.label))),
                    )
                }
            }
        }
    val newContact: () -> Unit =
        remember(starter) {
            {
                starter.start(addContactIntent("", create = true))
                Unit
            }
        }

    val photoOpener =
        rememberPersonPhotoOpener(container, overlays) { id -> detailsHost.editContact(id) }
    CompositionLocalProvider(LocalPersonPhotoOpener provides photoOpener) {
        RovstiShell(
            navigator = navigator,
            tabs = tabs,
            chromeFor = { route -> chromeFor(route, selecting = selection != null) },
            overlays = overlays,
            modifier = Modifier.fillMaxSize(),
            chrome = {
                // A second caller while a call is live: the card over the call (the Incoming screen has its own buttons).
                val waiting = incomingUi.waiting
                val onCallPage = navigator.current.let { it is PhoneRoute.Incoming || it == PhoneRoute.InCall }
                Column(Modifier.align(Alignment.TopCenter)) {
                    MiniCallPill(
                        ui = inCallUi,
                        strings = inCallStrings,
                        timer = miniTimer,
                        visible = !onCallPage && inCallUi.call != null,
                        onExpand = { navigator.push(PhoneRoute.InCall) },
                        onEnd = { inCall.onAction(InCallAction.End) },
                    )
                    if (waiting != null && navigator.current !is PhoneRoute.Incoming) {
                        WaitingCallCard(waiting, incomingStrings, incoming::onAction)
                    }
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
                FavoritesScreen(favoritesModel, links, setup, openFavorite)
            }
            entry<PhoneRoute.Recents> {
                val host =
                    remember(starter, openDetails) {
                        AndroidRecentsHost(starter, openDetails, container.leaves::leave)
                    }
                RecentsScreen(
                    model = recentsModel,
                    host = host,
                    links = links,
                    showNotice = setup.loaded && !setup.roleHeld,
                )
            }
            entry<PhoneRoute.Contacts> {
                PhoneContactsScreen(contactsModel, links, setup, onOpen = openContact, onNew = newContact)
            }
            entry<PhoneRoute.Keypad> {
                val model = rememberKeypadViewModel(container)
                val host =
                    remember(starter, navigator) {
                        AndroidKeypadHost(starter, { navigator.select(ContactsTab) }, container.leaves::leave)
                    }
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
                // The rows of the Setup section carry out their own effects (role dialog, permission dialogs, system pages).
                SetupEffectsRunner(setupModel, container.specialAccess, container.permissions)
                PhoneSettingsPage(
                    onSetupAction = setupModel::onAction,
                    setup = setup,
                    settings = container.commonSettings,
                    versionName = version,
                    links =
                        SettingsLinks(
                            openSetup = sheets::present,
                            openWhatsApp = { navigator.push(PhoneRoute.WhatsAppSettings) },
                            openStats = { navigator.push(PhoneRoute.Stats) },
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
            entry<PhoneRoute.Stats> {
                val model = rememberStatsViewModel(container)
                val statsHost =
                    remember(detailsHost) {
                        object : StatsHost {
                            override fun share(text: String) = detailsHost.share(text)

                            override fun saveCsv(
                                fileName: String,
                                csv: String,
                            ) = detailsHost.saveCsv(fileName, csv)
                        }
                    }
                StatsPage(
                    viewModel = model,
                    host = statsHost,
                    onOpenNumber = { name, number ->
                        details.presentPerson(PersonTarget(name, persistentListOf(TargetNumber(number))))
                    },
                    onBack = pop,
                    onBackLongClick = popToRoot,
                )
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
            entry<PhoneRoute.Incoming> { IncomingScreen(incoming, photos = container.callPhotos) }
            // InCall (E5.7a): the page of the single call. Back and the chevron collapse it into the mini pill.
            entry<PhoneRoute.InCall> {
                InCallScreen(
                    inCall,
                    inCallSheets,
                    onCollapse = pop,
                    photos = container.callPhotos,
                )
            }
        }
    }
}

/** The In-Call screen's Android side: the share sheet for the call summary. */
private class AndroidInCallHost(
    private val starter: SystemStarter,
) : InCallHost {
    override fun share(text: String) {
        starter.start(Intent.createChooser(ShareIntents.text(text), null))
    }
}

private const val NO_CALL = -1

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
