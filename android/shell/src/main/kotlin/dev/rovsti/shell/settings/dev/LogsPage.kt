package dev.rovsti.shell.settings.dev

import android.content.ActivityNotFoundException
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.core.log.DevLog
import dev.rovsti.core.log.LogEntry
import dev.rovsti.core.log.LogLevel
import dev.rovsti.core.log.RovstiLog
import dev.rovsti.design.chrome.Toolbar
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.ChipModel
import dev.rovsti.design.controls.ChipRow
import dev.rovsti.design.controls.SegmentedControl
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.overlay.AlertAction
import dev.rovsti.design.overlay.AlertEntry
import dev.rovsti.design.overlay.AlertRole
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.text.SearchField
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.time.LocalRovstiClock
import dev.rovsti.platform.files.ClipboardRepository
import dev.rovsti.platform.files.FilesRepository
import dev.rovsti.platform.files.ShareIntents
import dev.rovsti.shell.R
import dev.rovsti.shell.settings.SettingsLazyPage
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date

/** Test tags of the Logs page. */
object LogsTags {
    const val Page = "logs-page"
    const val Search = "logs-search"
    const val Empty = "logs-empty"
    const val Latest = "logs-latest"

    fun line(seq: Long) = "logs-line-$seq"
}

/** The level segments, in order: All (every level), then Info, Warn, Error as the least level shown. */
private val LevelSegments = listOf(LogLevel.Debug, LogLevel.Info, LogLevel.Warn, LogLevel.Error)

/** What the menu of the Logs page can do. */
enum class LogsCommand { Copy, Share, Clear }

/**
 * The Logs page (developer mode): search, a level filter, a tag filter, and the matching lines, newest at the bottom.
 * The list follows new lines while it is scrolled to the end; scrolled up, a "Jump to latest" pill takes it back.
 * Tapping a line shows all of it. Stateless: [state] in, every change out.
 */
@Composable
fun LogsScreen(
    state: LogsUi,
    onQuery: (String) -> Unit,
    onLevel: (LogLevel) -> Unit,
    onTag: (String?) -> Unit,
    onCommand: (LogsCommand) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    val query = rememberTextFieldState(state.filter.query)
    val currentOnQuery by rememberUpdatedState(onQuery)
    LaunchedEffect(query) { snapshotFlow { query.text.toString() }.collect { currentOnQuery(it) } }

    val levels =
        listOf(
            stringResource(R.string.dev_logs_all),
            stringResource(R.string.dev_logs_info),
            stringResource(R.string.dev_logs_warn),
            stringResource(R.string.dev_logs_error),
        ).toImmutableList()
    val allTags = stringResource(R.string.dev_logs_all)
    val chips =
        remember(state.tags, allTags) {
            (listOf(ChipModel(ALL_TAGS, allTags)) + state.tags.map { ChipModel(it, it) }).toImmutableList()
        }
    val copy = stringResource(R.string.dev_logs_copy)
    val share = stringResource(R.string.dev_logs_share)
    val clear = stringResource(R.string.dev_logs_clear)
    val more = stringResource(R.string.dev_logs_more)
    val currentOnCommand by rememberUpdatedState(onCommand)
    val toolbar =
        remember(copy, share, clear, more) {
            val menu =
                persistentListOf(
                    MenuItem.Action(copy, RovstiIcons.Copy) { currentOnCommand(LogsCommand.Copy) },
                    MenuItem.Action(share, RovstiIcons.Share) { currentOnCommand(LogsCommand.Share) },
                    MenuItem.Separator,
                    MenuItem.Action(clear, RovstiIcons.Trash, destructive = true) {
                        currentOnCommand(LogsCommand.Clear)
                    },
                )
            persistentListOf(ToolbarAction("logs-menu", RovstiIcons.Ellipsis, more, menu = menu) {})
        }

    // Follow new lines while the end is in view.
    val atEnd by remember(listState) { derivedStateOf { !listState.canScrollForward } }
    val lastIndex = HEADER_ITEMS + state.lines.size - 1
    // The page opens on the filters; new lines are followed while the end is in view, else the pill takes it there.
    LaunchedEffect(state.lines.lastOrNull()?.seq) {
        if (atEnd && state.lines.isNotEmpty()) listState.scrollToItem(lastIndex)
    }
    val scope = rememberCoroutineScope()
    var expanded by rememberSaveable { mutableStateOf(setOf<Long>()) }

    Box(modifier.fillMaxSize().testTag(LogsTags.Page)) {
        SettingsLazyPage(
            title = stringResource(R.string.dev_logs),
            onBack = onBack,
            onBackLongClick = onBackLongClick,
            listState = listState,
            actions = { Toolbar(toolbar) },
        ) {
            item(key = "search", contentType = "search") {
                SearchField(
                    query,
                    Modifier.fillMaxWidth().padding(bottom = Spacing.m).testTag(LogsTags.Search),
                    placeholder = stringResource(R.string.dev_logs_search),
                )
            }
            item(key = "levels", contentType = "levels") {
                SegmentedControl(
                    items = levels,
                    selected = LevelSegments.indexOf(state.filter.minLevel).coerceAtLeast(0),
                    onSelect = { onLevel(LevelSegments[it]) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = Spacing.m),
                )
            }
            item(key = "tags", contentType = "tags") {
                ChipRow(
                    chips = chips,
                    selected = state.filter.tag ?: ALL_TAGS,
                    onSelect = { id -> onTag(id.takeIf { it != ALL_TAGS }) },
                    modifier = Modifier.padding(bottom = Spacing.s),
                )
            }
            if (state.lines.isEmpty()) {
                item(key = "empty", contentType = "empty") {
                    IosText(
                        text =
                            stringResource(
                                if (state.total == 0) R.string.dev_logs_empty else R.string.dev_logs_no_match,
                            ),
                        color = IosTheme.colors.secondaryLabel,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xl).testTag(LogsTags.Empty),
                    )
                }
            }
            items(state.lines, key = { it.seq }, contentType = { "line" }) { entry ->
                LogLine(
                    entry = entry,
                    expanded = entry.seq in expanded,
                    onToggle = {
                        expanded = if (entry.seq in expanded) expanded - entry.seq else expanded + entry.seq
                    },
                )
            }
        }
        if (!atEnd && state.lines.isNotEmpty()) {
            CapsuleButton(
                text = stringResource(R.string.dev_logs_latest),
                onClick = { scope.launch { listState.animateScrollToItem(lastIndex) } },
                icon = RovstiIcons.ArrowDown,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = Spacing.l)
                        .testTag(LogsTags.Latest),
            )
        }
    }
}

/** One line: a dot in the level's colour, then time, level letter, tag and text in monospace. */
@Composable
private fun LogLine(
    entry: LogEntry,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = IosTheme.colors
    val dot =
        when (entry.level) {
            LogLevel.Error -> colors.systemRed
            LogLevel.Warn -> colors.systemOrange
            LogLevel.Info -> colors.systemBlue
            LogLevel.Debug -> colors.systemGray
        }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = Spacing.xs)
            .testTag(LogsTags.line(entry.seq)),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = Spacing.xs)
                .size(Spacing.s)
                .background(dot, CircleShape)
                .semantics { contentDescription = entry.level.name },
        )
        IosText(
            text = LogsViewModel.line(entry),
            style = IosTheme.typography.caption.copy(fontFamily = FontFamily.Monospace),
            color = if (entry.level >= LogLevel.Warn) colors.label else colors.secondaryLabel,
            maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The Logs page over [devLog]'s memory ring: Copy puts the filtered view on [clipboard], Share writes it to a file in
 * the cache and opens the share sheet (the only way a line leaves the phone), Clear asks first and then forgets every
 * line, in memory and on disk. [initialTag] preselects a tag ("Sync Details" opens it on `sync`).
 */
@Composable
fun LogsPage(
    devLog: DevLog,
    clipboard: ClipboardRepository,
    files: FilesRepository,
    appName: String,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    initialTag: String? = null,
) {
    val scope = rememberCoroutineScope()
    val model = remember(devLog) { LogsViewModel(RovstiLog.ring, scope, LogsFilter(tag = initialTag)) }
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val overlay = LocalOverlayOrNull.current
    val copied = stringResource(R.string.dev_logs_copied)
    val copyFailed = stringResource(R.string.dev_logs_copy_failed)
    val shareFailed = stringResource(R.string.dev_logs_share_failed)
    val clearTitle = stringResource(R.string.dev_logs_clear_title)
    val clearBody = stringResource(R.string.dev_logs_clear_body)
    val clearLabel = stringResource(R.string.dev_logs_clear)
    val cancel = stringResource(R.string.dev_logs_cancel)
    val headerFormat = stringResource(R.string.dev_logs_header)
    val clock = LocalRovstiClock.current

    fun header(): String =
        String.format(headerFormat, appName, DateFormat.getDateTimeInstance().format(Date(clock.nowMillis())))

    LogsScreen(
        state = state,
        onQuery = model::setQuery,
        onLevel = model::setLevel,
        onTag = model::selectTag,
        onCommand = { command ->
            when (command) {
                LogsCommand.Copy -> {
                    val text = LogsViewModel.export(header(), state.lines)
                    overlay?.showToast(if (clipboard.copy(text)) copied else copyFailed)
                }

                LogsCommand.Share -> {
                    val text = LogsViewModel.export(header(), state.lines)
                    scope.launch {
                        val ok = share(context, files, text, clock.nowMillis())
                        if (!ok) overlay?.showToast(shareFailed)
                    }
                }

                LogsCommand.Clear -> {
                    overlay?.present(
                        AlertEntry(
                            heading = clearTitle,
                            message = clearBody,
                            actions =
                                persistentListOf(
                                    AlertAction(cancel, AlertRole.Cancel),
                                    AlertAction(clearLabel, AlertRole.Destructive) { devLog.clear() },
                                ),
                        ),
                    )
                }
            }
        },
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}

/** Writes [text] to `cacheDir/logs/rovsti-log-<time>.txt`, stages it and opens the share sheet; false on failure. */
private suspend fun share(
    context: Context,
    files: FilesRepository,
    text: String,
    stamp: Long,
): Boolean {
    val name = "rovsti-log-$stamp.txt"
    val path =
        withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "logs").apply { mkdirs() }
                File(dir, name).apply { writeText(text) }.absolutePath
            } catch (_: IOException) {
                null
            }
        } ?: return false
    val uri = files.stageForShare(path, name) ?: return false
    File(path).delete()
    return try {
        context.startActivity(
            ShareIntents.file(uri, "text/plain", name).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

/** The chip id of "every tag". */
private const val ALL_TAGS = ""

/** Rows above the first line: search, levels, tags. */
private const val HEADER_ITEMS = 3

/** A collapsed line shows this many text lines. */
private const val COLLAPSED_LINES = 3
