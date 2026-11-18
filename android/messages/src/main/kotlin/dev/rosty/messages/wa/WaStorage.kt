package dev.rosty.messages.wa

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.rosty.core.model.wa.WaSyncMediaStats
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.menuTrigger
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.R
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import java.util.Locale

/** The media cache of the linked WhatsApp account as the settings show it. */
@Immutable
data class WaStorageUi(
    /** Bytes of every cached file and of the files unsent messages still send. */
    val usedBytes: Long,
    /** The cap in bytes; null: no limit. */
    val capBytes: Long?,
) {
    companion object {
        private const val MIB = 1024L * 1024

        /** The caps the settings offer, smallest first; null is "No limit". The store starts at 512 MiB. */
        val CAPS: List<Long?> = listOf(256 * MIB, 512 * MIB, 1024 * MIB, 2048 * MIB, null)

        fun of(stats: WaSyncMediaStats): WaStorageUi =
            WaStorageUi(usedBytes = stats.total.bytes + stats.outbox.bytes, capBytes = stats.capBytes)

        /**
         * A size as people read it: bytes below 1 KB, whole KB and MB (binary), GB with one decimal unless it is
         * whole. The same in every language.
         */
        fun size(bytes: Long): String {
            val kb = 1024L
            val gb = MIB * kb
            return when {
                bytes < kb -> "$bytes B"
                bytes < MIB -> "${bytes / kb} KB"
                bytes < gb -> "${bytes / MIB} MB"
                bytes % gb == 0L -> "${bytes / gb} GB"
                else -> "%.1f GB".format(Locale.ROOT, bytes / gb.toDouble())
            }
        }
    }
}

/** Test tags of the storage section. */
object WaStorageTags {
    const val Section = "wa-storage"
    const val Used = "wa-storage-used"
    const val Cap = "wa-storage-cap"
    const val Rules = "wa-storage-rules"
    const val HistoryNote = "wa-history-note"
}

/**
 * "Storage and data" of the WhatsApp settings: how much the media of the account takes on this phone, the limit it
 * is kept under (a menu of [WaStorageUi.CAPS]), and what downloads by itself (read only: the rules are the store's).
 */
@Composable
fun WaStorageSection(
    ui: WaStorageUi,
    onCap: (Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.testTag(WaStorageTags.Section)) {
        InsetGroupedSection(
            Modifier.padding(top = Spacing.sectionGap),
            header = stringResource(R.string.wa_storage_title),
            footer = stringResource(R.string.wa_storage_cap_note),
        ) {
            row {
                ListRow(
                    title = stringResource(R.string.wa_storage_used),
                    modifier = Modifier.testTag(WaStorageTags.Used),
                    trailing = { Value(WaStorageUi.size(ui.usedBytes)) },
                )
            }
            row {
                ListRow(
                    title = stringResource(R.string.wa_storage_cap),
                    trailing = { CapChoice(ui.capBytes, onCap) },
                )
            }
        }
        InsetGroupedSection(
            Modifier.padding(top = Spacing.sectionGap).testTag(WaStorageTags.Rules),
            header = stringResource(R.string.wa_auto_title),
            footer = stringResource(R.string.wa_auto_note),
        ) {
            row {
                ListRow(
                    title = stringResource(R.string.wa_auto_wifi),
                    secondary = stringResource(R.string.wa_auto_wifi_kinds),
                    secondaryMaxLines = 2,
                )
            }
            row {
                ListRow(
                    title = stringResource(R.string.wa_auto_mobile),
                    secondary = stringResource(R.string.wa_auto_mobile_kinds),
                    secondaryMaxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun Value(text: String) {
    RostyText(text, style = RostyTheme.type.body, color = RostyTheme.colors.labelSecondary, maxLines = 1)
}

@Composable
private fun capLabel(cap: Long?): String = cap?.let(WaStorageUi::size) ?: stringResource(R.string.wa_storage_no_limit)

/** The chosen limit in the accent colour; a touch opens the choices. */
@Composable
private fun CapChoice(
    chosen: Long?,
    onCap: (Long?) -> Unit,
) {
    val overlay = LocalOverlayOrNull.current
    val labels = WaStorageUi.CAPS.map { capLabel(it) }
    val items =
        remember(labels, chosen, onCap) {
            WaStorageUi.CAPS
                .mapIndexed { index, cap ->
                    MenuItem.Action(labels[index], checked = cap == chosen, onClick = { onCap(cap) })
                }.toImmutableList<MenuItem>()
        }
    val current = capLabel(chosen)
    val describe = stringResource(R.string.wa_storage_cap_choose, current)
    val trigger = if (overlay != null) Modifier.menuTrigger(overlay, items, label = describe) else Modifier
    Box(
        Modifier
            .defaultMinSize(minHeight = Sizes.touch)
            .then(trigger)
            .semantics { contentDescription = describe }
            .testTag(WaStorageTags.Cap),
        contentAlignment = Alignment.CenterEnd,
    ) {
        RostyText(current, style = RostyTheme.type.body, color = RostyTheme.colors.accent, maxLines = 1)
    }
}

/** The note under the storage section: who gets the whole history, and how the phone names this device. */
@Composable
fun WaHistoryNote(modifier: Modifier = Modifier) {
    Column(
        modifier
            .padding(horizontal = Spacing.gutter + Spacing.gutter)
            .padding(top = Spacing.m, bottom = Spacing.l)
            .testTag(WaStorageTags.HistoryNote),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        RostyText(
            stringResource(R.string.wa_history_relink_note),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
        )
        RostyText(
            stringResource(R.string.wa_history_device_note),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
        )
    }
}

/**
 * What Rosty Messages adds to the page of a linked account in the WhatsApp settings: the history progress while the
 * history arrives (the same row as on the WhatsApp tab), "Storage and data", and the note about the full history.
 * Null while WhatsApp is not linked. The numbers are read from the store again when its rows change (a download
 * ended, the cap evicted files).
 */
@Composable
fun rememberWaSettingsBlocks(container: MessagesContainer): (LazyListScope.() -> Unit)? {
    val offline = container.waOffline
    val session by offline.session.collectAsState()
    val history by offline.history.collectAsState()
    var storage by remember { mutableStateOf<WaStorageUi?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(offline) { offline.changes.conflate().collect { revision++ } }
    LaunchedEffect(session, revision) { storage = offline.mediaStats()?.let(WaStorageUi::of) }
    if (session == null) return null
    val progress = WaHistoryUi.of(history)
    val shown = storage
    return {
        if (progress != null) {
            item(key = "wa-history") { WaHistoryRow(progress, Modifier.padding(top = Spacing.m)) }
        }
        if (shown != null) {
            item(key = "wa-storage") {
                WaStorageSection(
                    shown,
                    onCap = { cap ->
                        scope.launch {
                            offline.setMediaCap(cap)
                            revision++
                        }
                    },
                )
            }
        }
        item(key = "wa-history-note") { WaHistoryNote() }
    }
}
