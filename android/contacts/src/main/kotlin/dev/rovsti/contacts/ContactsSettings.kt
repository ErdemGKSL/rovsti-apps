package dev.rovsti.contacts

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.core.model.wa.AppId
import dev.rovsti.core.ops.NativeWaSettingsOps
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.shell.settings.AboutPage
import dev.rovsti.shell.settings.AppearancePage
import dev.rovsti.shell.settings.CommonSettings
import dev.rovsti.shell.settings.CommonSettingsGroup
import dev.rovsti.shell.settings.CommonSettingsPage
import dev.rovsti.shell.settings.HapticsPage
import dev.rovsti.shell.settings.IosSettingsGroup
import dev.rovsti.shell.settings.IosSettingsItem
import dev.rovsti.shell.settings.LicenceDetailPage
import dev.rovsti.shell.settings.LicencesPage
import dev.rovsti.shell.settings.SettingsPage
import dev.rovsti.shell.settings.aboutInfo
import dev.rovsti.shell.settings.rememberCoreVersion
import dev.rovsti.shell.settings.rememberLicenceData
import dev.rovsti.wa.WaLinkStore
import dev.rovsti.wa.settings.WhatsAppSettingsRoute
import dev.rovsti.wa.settings.WhatsAppSettingsViewModel

/** The Settings tab root: the WhatsApp row (the E3.10 screen) and the rows every app shares. */
@Composable
fun ContactsSettingsPage(
    settings: CommonSettings,
    onOpenWhatsApp: () -> Unit,
    onOpenCommon: (CommonSettingsPage) -> Unit,
) {
    SettingsPage(
        title = stringResource(R.string.tab_settings),
        modifier = Modifier.testTag(ContactsTags.SettingsPage),
    ) {
        IosSettingsGroup(
            items =
                listOf(
                    IosSettingsItem(
                        icon = rememberVectorPainter(RovstiIcons.WhatsApp),
                        title = { IosText(stringResource(R.string.settings_whatsapp)) },
                        onClick = onOpenWhatsApp,
                    ),
                ),
        )
        CommonSettingsGroup(settings, onOpenCommon)
    }
}

/** The WhatsApp settings of the shared connect key, on a view model that lives as long as the activity. */
@Composable
fun WhatsAppSettingsEntry(container: ContactsContainer) {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    val viewModel =
        remember(owner, container) {
            val factory =
                viewModelFactory {
                    initializer {
                        WhatsAppSettingsViewModel(
                            app = AppId.Contacts,
                            machine = NativeWaSettingsOps(container.rust),
                            ops = container.waOps,
                            store = WaLinkStore(container.waOps),
                            repo = container.wa,
                            qr = container.qr,
                            clipboard = container.clipboard,
                            files = container.files,
                            stagingDir = container.context.cacheDir,
                            clock = System::currentTimeMillis,
                            saved = SavedStateHandle(),
                        )
                    }
                }
            ViewModelProvider(owner, factory)[WhatsAppSettingsViewModel::class.java]
        }
    WhatsAppSettingsRoute(viewModel)
}

@Composable
fun AppearanceEntry(
    settings: CommonSettings,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val value by settings.appearance.collectAsState()
    AppearancePage(
        selected = value ?: Appearance.Auto,
        onSelect = settings::setAppearance,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

@Composable
fun HapticsEntry(
    settings: CommonSettings,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val value by settings.haptics.collectAsState()
    HapticsPage(
        selected = value ?: HapticsLevel.Light,
        onSelect = settings::setHaptics,
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

@Composable
fun AboutEntry(
    container: ContactsContainer,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val appName = stringResource(R.string.app_name)
    val coreVersion = rememberCoreVersion(container.rust).value
    val info = remember(context, appName, coreVersion) { context.aboutInfo(appName, coreVersion) }
    AboutPage(info, onOpenLicences = onOpenLicences, onBack = onBack, onBackLongClick = onBackLongClick)
}

@Composable
fun LicencesEntry(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val data by rememberLicenceData()
    LicencesPage(data, onOpen = onOpen, onBack = onBack, onBackLongClick = onBackLongClick)
}

@Composable
fun LicenceDetailEntry(
    id: String,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val data by rememberLicenceData()
    data?.let { LicenceDetailPage(id, it, onBack = onBack, onBackLongClick = onBackLongClick) }
}

private fun Context.findComponentActivity(): ComponentActivity {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("No ComponentActivity around $this")
}
