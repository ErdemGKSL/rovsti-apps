package dev.rovsti.shell.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.shapes.ContinuousRoundedRectangle
import dev.rovsti.shell.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Id of the entry for Rovsti itself, whose detail page shows the GPL text. */
const val OwnLicenceId = "rovsti"

/** Asset paths written by the `copyLicenceAssets` Gradle task of `:shell`. */
internal const val NoticesAsset = "licences/THIRD_PARTY_NOTICES.md"
internal const val GplAsset = "licences/LICENSE"

/** What the licence pages show: the parsed notices and the text of the project's own licence. */
@Immutable
data class LicenceData(
    val notices: Notices,
    val gplText: List<String>,
)

/** Reads [LicenceData] from the assets of `:shell`; null when an asset is missing. */
suspend fun Context.loadLicenceData(): LicenceData? =
    withContext(Dispatchers.IO) {
        try {
            val notices = assets.open(NoticesAsset).bufferedReader().use { NoticesParser.parse(it.readText()) }
            val gpl = assets.open(GplAsset).bufferedReader().use { it.readLines() }
            LicenceData(notices, gpl)
        } catch (_: java.io.IOException) {
            null
        }
    }

@Composable
fun rememberLicenceData(): State<LicenceData?> {
    val context = LocalContext.current
    return produceState<LicenceData?>(initialValue = null, context) { value = context.loadLicenceData() }
}

/** Test tag of the row of one project in the list. */
fun licenceRowTag(id: String): String = SettingsTags.LicenceRowPrefix + id

/** One row per project (name, licence, how many sources), Rovsti itself first. [data] null shows a loading row. */
@Composable
fun LicencesPage(
    data: LicenceData?,
    onOpen: (id: String) -> Unit,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    SettingsLazyPage(
        title = stringResource(R.string.settings_licences),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        item(key = "own") {
            IosSettingsGroup(
                title = stringResource(R.string.settings_licences_app_header),
                items =
                    listOf(
                        IosSettingsItem(
                            icon = tilePainter(RovstiIcons.Document),
                            title = { IosText("Rovsti") },
                            description = { IosText(stringResource(R.string.settings_licences_app_desc)) },
                            onClick = { onOpen(OwnLicenceId) },
                        ),
                    ),
            )
            Spacer(Modifier.height(SettingsMetrics.S27))
        }
        item(key = "third-party") {
            if (data == null) {
                IosText(
                    stringResource(R.string.settings_licences_loading),
                    color = IosTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(horizontal = SettingsMetrics.S20, vertical = SettingsMetrics.S12),
                )
            } else {
                IosSettingsGroup(
                    title = stringResource(R.string.settings_licences_third_party_header),
                    items =
                        data.notices.projects.map { entry ->
                            IosSettingsItem(
                                icon = tilePainter(RovstiIcons.Document),
                                title = { IosText(entry.name) },
                                description = { IosText(entry.summary()) },
                                onClick = { onOpen(entry.id) },
                            )
                        },
                )
            }
        }
    }
}

@Composable
private fun NoticeEntry.summary(): String =
    if (sources > 0) {
        "$licence · " + pluralStringResource(R.plurals.settings_licences_sources, sources, sources)
    } else {
        licence
    }

/**
 * The full text of one project: its notice (source, licence, what it is used for, then any licence text the notice
 * carries) and, under it, the text of the licence itself: the Apache text for Apache projects, and for Rovsti the GPL
 * text of the repository `LICENSE`.
 */
@Composable
fun LicenceDetailPage(
    id: String,
    data: LicenceData,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    val own = id == OwnLicenceId
    val entry = data.notices.entry(id)
    val title = if (own) "Rovsti" else entry?.name.orEmpty()
    val intro: List<NoticeBlock> =
        if (own) {
            listOf(NoticeBlock.Paragraph(stringResource(R.string.settings_licences_app_body)))
        } else {
            NoticesParser.blocks(entry?.body.orEmpty())
        }
    val licenceText: List<String>? =
        when {
            own -> data.gplText
            entry != null -> data.notices.licenceText(entry)
            else -> null
        }
    SettingsLazyPage(title = title, onBack = onBack, onBackLongClick = onBackLongClick) {
        item(key = "notice") {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(ContinuousRoundedRectangle(dev.rovsti.design.ios.foundation.IosMetrics.GroupedCornerRadius))
                    .background(IosTheme.colors.secondarySystemGroupedBackground)
                    .padding(SettingsMetrics.S16),
            ) {
                intro.forEachIndexed { i, block ->
                    if (i > 0) Spacer(Modifier.height(SettingsMetrics.S8))
                    NoticeBlockText(block)
                }
            }
        }
        if (licenceText != null) {
            item(key = "text-header") {
                IosText(
                    stringResource(R.string.settings_licences_text_header),
                    style = IosTheme.typography.subheadline,
                    color = IosTheme.colors.secondaryLabel,
                    modifier =
                        Modifier
                            .padding(
                                start = SettingsMetrics.S20,
                                end = SettingsMetrics.S20,
                                top = SettingsMetrics.S27,
                                bottom = SettingsMetrics.S8,
                            ).semantics { heading() },
                )
            }
            items(NoticesParser.paragraphs(licenceText)) { paragraph -> LicenceParagraph(paragraph) }
        }
    }
}

@Composable
private fun NoticeBlockText(block: NoticeBlock) {
    when (block) {
        is NoticeBlock.Paragraph -> {
            IosText(block.text)
        }

        is NoticeBlock.Bullet -> {
            IosText(block.text, color = IosTheme.colors.secondaryLabel)
        }

        is NoticeBlock.Code -> {
            NoticesParser.paragraphs(block.lines).forEachIndexed { i, p ->
                if (i > 0) Spacer(Modifier.height(SettingsMetrics.S8))
                IosText(p, style = IosTheme.typography.footnote, color = IosTheme.colors.secondaryLabel)
            }
        }
    }
}

@Composable
private fun LicenceParagraph(text: String) {
    IosText(
        text,
        style = IosTheme.typography.footnote,
        color = IosTheme.colors.secondaryLabel,
        modifier = Modifier.fillMaxWidth().padding(horizontal = SettingsMetrics.S20, vertical = SettingsMetrics.S6),
    )
}
