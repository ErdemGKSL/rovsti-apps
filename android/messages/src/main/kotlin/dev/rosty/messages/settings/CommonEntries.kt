package dev.rosty.messages.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import dev.rosty.core.model.phone.HapticsLevel
import dev.rosty.core.model.stops.Appearance
import dev.rosty.core.model.wa.AppId
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import dev.rosty.messages.wa.UnlinkingWaRepository
import dev.rosty.messages.wa.rememberWaSettingsBlocks
import dev.rosty.shell.settings.AboutPage
import dev.rosty.shell.settings.AppearancePage
import dev.rosty.shell.settings.CommonSettings
import dev.rosty.shell.settings.HapticsPage
import dev.rosty.shell.settings.LicenceDetailPage
import dev.rosty.shell.settings.LicencesPage
import dev.rosty.shell.settings.aboutInfo
import dev.rosty.shell.settings.dev.LogsPage
import dev.rosty.shell.settings.rememberCoreVersion
import dev.rosty.shell.settings.rememberLicenceData
import dev.rosty.shell.wa.WhatsAppVaultSettings

/**
 * The WhatsApp settings behind the vault (`plans/wa-vault.md`), the page every Rosty app shares (`:shell`): the link
 * flow while nothing is linked, then the setup; the unlock page while locked; "Change passphrase" in place of the page
 * while it runs. Unlink also deletes the offline store, its media and what is kept per chat; Messages adds its history,
 * storage and data blocks.
 */
@Composable
fun WhatsAppSettingsEntry(container: MessagesContainer) {
    WhatsAppVaultSettings(
        container = container,
        app = AppId.Messages,
        title = stringResource(R.string.tab_whatsapp),
        repo = UnlinkingWaRepository(container.wa) { container.waOffline },
        linked = rememberWaSettingsBlocks(container),
    )
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
    container: MessagesContainer,
    onOpenLicences: () -> Unit,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val appName = resources.getString(R.string.app_name)
    val coreVersion = rememberCoreVersion(container.rust).value
    val info = remember(context, appName, coreVersion) { context.aboutInfo(appName, coreVersion) }
    AboutPage(info, onOpenLicences = onOpenLicences, onBack = onBack, onBackLongClick = onBackLongClick)
}

/** Developer mode's Logs page of Messages. */
@Composable
fun LogsEntry(
    container: MessagesContainer,
    tag: String?,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    LogsPage(
        devLog = container.devLog,
        clipboard = container.clipboard,
        files = container.files,
        appName = LocalResources.current.getString(R.string.app_name),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        initialTag = tag,
    )
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

fun Context.findComponentActivity(): ComponentActivity {
    var current = this
    while (current is android.content.ContextWrapper) {
        if (current is ComponentActivity) return current
        current = current.baseContext
    }
    error("No ComponentActivity around $this")
}
