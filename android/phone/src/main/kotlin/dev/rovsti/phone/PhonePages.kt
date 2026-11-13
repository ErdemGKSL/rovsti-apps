package dev.rovsti.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rovsti.design.chrome.LargeTitleScaffold
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.controls.EmptyState
import dev.rovsti.design.controls.GlassIconButton
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosChevron
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowChevron
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.setup.SetupAction
import dev.rovsti.phone.setup.SetupUiState
import dev.rovsti.phone.setup.setupSettingsItems
import dev.rovsti.shell.settings.CommonSettings
import dev.rovsti.shell.settings.CommonSettingsGroup
import dev.rovsti.shell.settings.CommonSettingsPage
import dev.rovsti.shell.settings.IosSettingsGroup
import dev.rovsti.shell.settings.IosSettingsItem
import dev.rovsti.shell.settings.SettingsPage
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the Phone pages. */
object PhoneTags {
    const val Notice = "phone-notice-line"
    const val SetupButton = "phone-setup-button"
    const val SettingsButton = "phone-settings-button"
    const val SettingsPage = "phone-settings-page"
    const val StatsRow = "phone-settings-stats"
    const val SetupRow = "phone-settings-setup-row"
    const val VersionRow = "phone-settings-version-row"
    const val WhatsAppRow = "phone-settings-whatsapp-row"
    const val BlockedRow = "phone-settings-blocked-row"
    const val Placeholder = "phone-placeholder"

    fun root(route: String) = "phone-root-$route"
}

/** What a tab root can do besides showing itself: the two toolbar buttons and the notice line. */
data class RootLinks(
    val openSettings: () -> Unit,
    val openSetup: () -> Unit,
)

/**
 * The toolbar of a tab root: Setup (the shield, only while the checklist is open) and the gear that pushes Settings.
 * The shield is the way back into the sheet once it was dismissed.
 */
@Composable
fun rootToolbar(
    setup: SetupUiState,
    links: RootLinks,
): kotlinx.collections.immutable.ImmutableList<ToolbarAction> {
    val setupLabel = stringResource(R.string.setup_open)
    val settingsLabel = stringResource(R.string.settings_open)
    return remember(setup.complete, setup.loaded, links, setupLabel, settingsLabel) {
        persistentListOf<ToolbarAction>().let { list ->
            val setupAction =
                ToolbarAction("setup", RovstiIcons.Shield, setupLabel, onClick = links.openSetup)
            val gear = ToolbarAction("settings", RovstiIcons.Gear, settingsLabel, onClick = links.openSettings)
            if (setup.loaded && !setup.complete) list.add(setupAction).add(gear) else list.add(gear)
        }
    }
}

/**
 * The line under the Recents title while Rovsti is not the default phone app: calls and the call log only work as the
 * default phone app, so it says so and a tap opens the Setup sheet. A row of its own card, never an overlay, so the list
 * below it keeps its place.
 */
@Composable
fun NoticeLine(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InsetGroupedSection(modifier.padding(top = PhoneMetrics.NoticeTop).testTag(PhoneTags.Notice)) {
        row {
            ListRow(
                title = stringResource(R.string.notice_not_default),
                secondary = stringResource(R.string.notice_not_default_action),
                leading = {
                    dev.rovsti.design.icons.RovstiIcon(
                        RovstiIcons.Shield,
                        null,
                        tint = RovstiTheme.colors.accent,
                        size = PhoneMetrics.NoticeIcon,
                    )
                },
                trailing = { ListRowChevron() },
                onClick = onClick,
            )
        }
    }
}

/**
 * A tab root before its own screen exists (Keypad E5.2, Recents E5.3, Favorites and Contacts E5.5): the large title, the
 * toolbar, an honest empty state that fills the page above the tab bar, and, on Recents, the notice line.
 */
@Composable
fun PhoneRootPlaceholder(
    route: String,
    title: String,
    icon: ImageVector,
    message: String,
    setup: SetupUiState,
    links: RootLinks,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    showNotice: Boolean = false,
) {
    LargeTitleScaffold(
        title = title,
        state = state,
        actions = rootToolbar(setup, links),
        modifier = modifier.testTag(PhoneTags.root(route)),
    ) {
        if (showNotice && setup.loaded && !setup.roleHeld) {
            item(key = "notice", contentType = "notice") { NoticeLine(links.openSetup) }
        }
        item(key = "placeholder", contentType = "placeholder") {
            val bottom = LocalContentInsets.current.calculateBottomPadding()
            Box(
                Modifier
                    .fillParentMaxHeight()
                    .fillMaxWidth()
                    .padding(bottom = bottom)
                    .testTag(PhoneTags.Placeholder),
                contentAlignment = Alignment.Center,
            ) {
                EmptyState(icon = icon, title = stringResource(R.string.coming_title), message = message)
            }
        }
    }
}

/** The Keypad before its field and keys exist (E5.2): no large title, the gear in the corner, the same empty state. */
@Composable
fun KeypadPlaceholder(
    links: RootLinks,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize().testTag(PhoneTags.root("keypad"))) {
        Box(
            Modifier
                .align(Alignment.Center)
                .padding(bottom = LocalContentInsets.current.calculateBottomPadding())
                .testTag(PhoneTags.Placeholder),
        ) {
            EmptyState(
                icon = RovstiIcons.Keypad,
                title = stringResource(R.string.coming_title),
                message = stringResource(R.string.keypad_coming_message),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = LocalContentInsets.current.calculateTopPadding() + PhoneMetrics.CornerInset)
                .padding(end = Spacing.gutter),
        ) {
            GlassIconButton(
                icon = RovstiIcons.Gear,
                contentDescription = stringResource(R.string.settings_open),
                onClick = links.openSettings,
                modifier = Modifier.testTag(PhoneTags.SettingsButton),
            )
        }
    }
}

/** What the Settings page needs from the app. */
data class SettingsLinks(
    val openSetup: () -> Unit,
    val openWhatsApp: () -> Unit,
    val openStats: () -> Unit,
    val openCommon: (CommonSettingsPage) -> Unit,
    /** A tap on the version row; the seventh in a row opens Diagnostics. */
    val versionTapped: () -> Unit,
    /** Pushes one of the pages Settings opens (Blocked numbers, Quick replies, ...). */
    val openRoute: (PhoneRoute) -> Unit = {},
)

/**
 * Settings (E5.9a): the inset-grouped iOS page. The groups, top to bottom: Setup (progress), Calls (Blocked numbers,
 * Statistics), WhatsApp, the rows every app shares (Appearance, Haptics, About) and the version row, which
 * counts taps for the hidden Diagnostics page. [blockedCount] is the value on the Blocked numbers row (null hides it).
 */
@Composable
fun PhoneSettingsPage(
    setup: SetupUiState,
    settings: CommonSettings,
    versionName: String,
    links: SettingsLinks,
    modifier: Modifier = Modifier,
    blockedCount: Int? = null,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    onSetupAction: (SetupAction) -> Unit = {},
) {
    val setupRows = setupSettingsItems(setup, onSetupAction)
    SettingsPage(
        title = stringResource(R.string.settings_title),
        modifier = modifier.testTag(PhoneTags.SettingsPage),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_setup),
            items =
                listOf(
                    IosSettingsItem(
                        icon =
                            rememberVectorPainter(
                                if (setup.complete) RovstiIcons.ShieldCheck else RovstiIcons.Shield,
                            ),
                        title = {
                            IosText(
                                stringResource(
                                    if (setup.complete) R.string.settings_setup_done else R.string.settings_setup_row,
                                ),
                                Modifier.testTag(PhoneTags.SetupRow),
                            )
                        },
                        trailingContent = {
                            if (setup.loaded) IosText(setup.progressLabel)
                        },
                        onClick = links.openSetup,
                    ),
                ) + setupRows,
        )
        IosSettingsGroup(
            title = stringResource(R.string.settings_group_calls),
            items =
                listOf(
                    pageRow(
                        RovstiIcons.Block,
                        R.string.settings_blocked,
                        PhoneTags.BlockedRow,
                        value = blockedCount?.takeIf { it > 0 }?.toString(),
                    ) { links.openRoute(PhoneRoute.Blocked) },
                    pageRow(
                        RovstiIcons.BarChart,
                        R.string.settings_stats,
                        PhoneTags.StatsRow,
                        onClick = links.openStats,
                    ),
                ),
        )
        IosSettingsGroup(
            items =
                listOf(
                    IosSettingsItem(
                        icon = rememberVectorPainter(RovstiIcons.WhatsApp),
                        title = {
                            IosText(stringResource(R.string.settings_whatsapp), Modifier.testTag(PhoneTags.WhatsAppRow))
                        },
                        onClick = links.openWhatsApp,
                    ),
                ),
        )
        CommonSettingsGroup(settings, links.openCommon)
        IosSettingsGroup(
            items =
                listOf(
                    IosSettingsItem(
                        title = {
                            IosText(
                                stringResource(R.string.settings_version),
                                Modifier.testTag(PhoneTags.VersionRow),
                            )
                        },
                        trailingContent = { IosText(versionName) },
                        onClick = links.versionTapped,
                    ),
                ),
        )
    }
}

/** A Settings row that pushes a page: a tinted glyph tile, the title, an optional value and the chevron. */
@Composable
private fun pageRow(
    icon: ImageVector,
    title: Int,
    tag: String,
    value: String? = null,
    onClick: () -> Unit,
): IosSettingsItem =
    IosSettingsItem(
        icon = rememberVectorPainter(icon),
        title = { IosText(stringResource(title), Modifier.testTag(tag)) },
        trailingContent =
            value?.let { shown ->
                {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PhoneMetrics.ValueGap),
                    ) {
                        IosText(shown)
                        IosChevron()
                    }
                }
            },
        onClick = onClick,
    )
