/*
 * Convx Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Ported from Convx (https://github.com/cosmictaserdev-creator/Convx), GPL-3.0, app/src/main/kotlin/com/convx/music/ui/screens/settings/AboutScreen.kt
 * Changes for Rovsti: the rows and the header of AboutScreen, on SettingsPage and IosSettingsGroup. Convx's developer, GitHub,
 * Discord and donation rows and its logo are dropped. Rovsti's rows are installed date, version name and code, the Rust core
 * version (RustCore.version()) and Licences (dp literals live in SettingsMetrics). The Material typography and colours became IosTheme tokens; the SF Symbols became
 * RovstiIcons; the page is a pure function of AboutInfo.
 */

package dev.rovsti.shell.settings

import android.content.Context
import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustCore
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.shell.R
import java.text.DateFormat
import java.util.Date

/** What the About page shows. Plain values, so a test or a screenshot builds it without a device. */
@Immutable
data class AboutInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
    /** `RustCore.version()`; null until the call returns or when it failed. */
    val coreVersion: String? = null,
    /** First install, already formatted for the locale; null when the package manager could not say. */
    val installedAt: String? = null,
)

/** Reads the version, build and install date of this app from the package manager. */
fun Context.aboutInfo(
    appName: String,
    coreVersion: String? = null,
): AboutInfo {
    val info =
        try {
            packageManager.getPackageInfo(packageName, 0)
        } catch (_: Exception) {
            null
        }
    val code =
        when {
            info == null -> {
                0L
            }

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> {
                info.longVersionCode
            }

            else -> {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        }
    return AboutInfo(
        appName = appName,
        versionName = info?.versionName.orEmpty(),
        versionCode = code,
        coreVersion = coreVersion,
        installedAt = info?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.firstInstallTime)) },
    )
}

/** The Rust core's version, loaded off the main thread; null while loading or when the call failed. */
@Composable
fun rememberCoreVersion(core: RustCore): State<String?> =
    produceState<String?>(initialValue = null, core) {
        value = (core.version() as? Outcome.Ok)?.value
    }

/** The About page: a title and version pill, the app information rows, and the way to the licences. */
@Composable
fun AboutPage(
    info: AboutInfo,
    onOpenLicences: () -> Unit,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    val unknown = stringResource(R.string.settings_about_unknown)
    SettingsPage(
        title = stringResource(R.string.settings_about),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = SettingsMetrics.S32),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IosText(
                text = info.appName,
                style =
                    IosTheme.typography.largeTitle.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 48.sp,
                        letterSpacing = 2.sp,
                    ),
                color = IosTheme.colors.label,
            )

            Spacer(modifier = Modifier.height(SettingsMetrics.S16))

            Row(
                modifier =
                    Modifier
                        .border(
                            width = SettingsMetrics.S1_5,
                            color = IosTheme.tint.copy(alpha = 0.5f),
                            shape = CircleShape,
                        ).padding(horizontal = SettingsMetrics.S16, vertical = SettingsMetrics.S8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SettingsMetrics.S8),
            ) {
                IosText(
                    text = "v${info.versionName.ifEmpty { unknown }} • ${info.versionCode}",
                    style = IosTheme.typography.headline,
                    color = IosTheme.tint,
                )
            }
        }

        // App information section
        IosSettingsGroup(
            title = stringResource(R.string.settings_about_info_header),
            items =
                listOf(
                    IosSettingsItem(
                        icon = tilePainter(RovstiIcons.Download),
                        title = { IosText(stringResource(R.string.settings_about_installed)) },
                        description = { IosText(info.installedAt ?: unknown) },
                    ),
                    IosSettingsItem(
                        icon = tilePainter(RovstiIcons.Info),
                        title = { IosText(stringResource(R.string.settings_about_version)) },
                        description = { IosText(info.versionName.ifEmpty { unknown }) },
                    ),
                    IosSettingsItem(
                        icon = tilePainter(RovstiIcons.Gear),
                        title = { IosText(stringResource(R.string.settings_about_build)) },
                        description = { IosText(info.versionCode.toString()) },
                    ),
                    IosSettingsItem(
                        icon = tilePainter(RovstiIcons.Phone),
                        title = { IosText(stringResource(R.string.settings_about_core)) },
                        description = {
                            IosText(
                                info.coreVersion ?: stringResource(R.string.settings_about_core_unknown),
                            )
                        },
                    ),
                ),
        )
        GroupGap()

        // Legal section
        IosSettingsGroup(
            title = stringResource(R.string.settings_about_legal_header),
            items =
                listOf(
                    IosSettingsItem(
                        icon = tilePainter(RovstiIcons.Document),
                        title = { IosText(stringResource(R.string.settings_licences)) },
                        description = { IosText(stringResource(R.string.settings_about_licences_desc)) },
                        onClick = onOpenLicences,
                    ),
                ),
        )
        Spacer(modifier = Modifier.height(SettingsMetrics.S20))
    }
}

@Composable
internal fun rememberAboutInfo(
    appName: String,
    core: RustCore,
): AboutInfo {
    val context = LocalContext.current
    val coreVersion = rememberCoreVersion(core).value
    return remember(context, appName, coreVersion) { context.aboutInfo(appName, coreVersion) }
}
