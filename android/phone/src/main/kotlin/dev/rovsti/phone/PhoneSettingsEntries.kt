package dev.rovsti.phone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.core.model.phone.HapticsLevel
import dev.rovsti.core.model.stops.Appearance
import dev.rovsti.core.model.wa.AppId
import dev.rovsti.core.ops.NativeWaSettingsOps
import dev.rovsti.shell.settings.AboutPage
import dev.rovsti.shell.settings.AppearancePage
import dev.rovsti.shell.settings.CommonSettings
import dev.rovsti.shell.settings.HapticsPage
import dev.rovsti.shell.settings.LicenceDetailPage
import dev.rovsti.shell.settings.LicencesPage
import dev.rovsti.shell.settings.aboutInfo
import dev.rovsti.shell.settings.rememberCoreVersion
import dev.rovsti.shell.settings.rememberLicenceData
import dev.rovsti.wa.WaLinkStore
import dev.rovsti.wa.settings.WhatsAppSettingsRoute
import dev.rovsti.wa.settings.WhatsAppSettingsViewModel

/** The WhatsApp settings of the shared connect key, on a view model that lives as long as the activity. */
@Composable
fun PhoneWhatsAppSettings(container: PhoneContainer) {
    val owner = LocalContext.current.findComponentActivity()
    val viewModel =
        remember(owner, container) {
            val factory =
                viewModelFactory {
                    initializer {
                        WhatsAppSettingsViewModel(
                            app = AppId.Phone,
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
fun PhoneAppearanceEntry(
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
fun PhoneHapticsEntry(
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
fun PhoneAboutEntry(
    container: PhoneContainer,
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
fun PhoneLicencesEntry(
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val data by rememberLicenceData()
    LicencesPage(data, onOpen = onOpen, onBack = onBack, onBackLongClick = onBackLongClick)
}

@Composable
fun PhoneLicenceDetailEntry(
    id: String,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val data by rememberLicenceData()
    data?.let { LicenceDetailPage(id, it, onBack = onBack, onBackLongClick = onBackLongClick) }
}
