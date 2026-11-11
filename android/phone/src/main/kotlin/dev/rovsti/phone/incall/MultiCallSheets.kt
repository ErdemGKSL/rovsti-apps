package dev.rovsti.phone.incall

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.controls.SegmentedControl
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.RowSeparator
import dev.rovsti.design.list.TextStartWithLeading
import dev.rovsti.design.overlay.SheetState
import dev.rovsti.design.overlay.sheetScrollConnection
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.DECLINE_RED
import dev.rovsti.phone.InCallMetrics
import dev.rovsti.phone.favorites.FavPerson
import dev.rovsti.phone.favorites.FavoritesUi
import dev.rovsti.phone.favorites.PersonListRow
import dev.rovsti.phone.keypad.CallKey
import dev.rovsti.phone.keypad.DeleteKey
import dev.rovsti.phone.keypad.DigitKey
import dev.rovsti.phone.keypad.KeyRows
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.StateFlow

/** The nodes the multi-call tests find. */
object MultiCallTags {
    const val Held = "incall-held-card"
    const val People = "incall-people"
    const val Swap = "incall-swap"
    const val Merge = "incall-merge"
    const val AddSheet = "addcall-sheet"
    const val AddTabs = "addcall-tabs"
    const val AddNumber = "addcall-number"
    const val AddCall = "addcall-call"
    const val Conference = "conference-sheet"
    const val EndAll = "conference-end-all"

    fun addKey(c: Char) = "addcall-key-$c"

    fun addPerson(key: String) = "addcall-person-$key"

    fun split(id: Int) = "conference-split-$id"

    fun drop(id: Int) = "conference-drop-$id"
}

private const val TAB_KEYPAD = 0
private const val TAB_FAVORITES = 1
private const val TAB_CONTACTS = 2

/**
 * The Add call sheet (`addcall`): a large sheet over the call with a keypad, the Favorites and the Contacts. A pick or a
 * tap on the green key places the number as the second call and closes the sheet; the call underneath never leaves the
 * screen. [people] is the Favorites model's state: its favorites and frequent numbers are the Favorites tab, everybody
 * with a number is the Contacts tab.
 */
@Composable
fun ColumnScope.AddCallSheetBody(
    people: StateFlow<FavoritesUi>,
    strings: InCallStrings,
    sheet: SheetState?,
    onDial: (String) -> Unit,
    initialTab: Int = TAB_KEYPAD,
) {
    val ui by people.collectAsState()
    var tab by remember { mutableIntStateOf(initialTab) }
    val tabs =
        remember(strings) { persistentListOf(strings.addTabKeypad, strings.addTabFavorites, strings.addTabContacts) }
    val favorites = remember(ui.favorites, ui.frequent) { (ui.favorites + ui.frequent).filter { it.number != null } }
    val everyone =
        remember(ui.favorites, ui.pickables) {
            (ui.favorites + ui.pickables).filter { it.number != null }.sortedBy { it.name.lowercase() }
        }
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(Modifier.fillMaxWidth().weight(1f).testTag(MultiCallTags.AddSheet)) {
            RovstiText(
                strings.addTitle,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter)
                        .padding(top = Spacing.s)
                        .semantics { heading() },
                style = RovstiTheme.type.title2,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            SegmentedControl(
                items = tabs,
                selected = tab,
                onSelect = { tab = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.m)
                        .testTag(MultiCallTags.AddTabs),
            )
            when (tab) {
                TAB_KEYPAD -> AddKeypad(strings, onDial)
                TAB_FAVORITES -> AddPeople(favorites.toImmutableList(), strings, sheet, onDial)
                else -> AddPeople(everyone.toImmutableList(), strings, sheet, onDial)
            }
        }
    }
}

@Composable
private fun ColumnScope.AddKeypad(
    strings: InCallStrings,
    onDial: (String) -> Unit,
) {
    var digits by remember { mutableStateOf("") }
    val colors = RovstiTheme.colors
    Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().height(InCallMetrics.AddNumberHeight).padding(horizontal = Spacing.gutter),
            contentAlignment = Alignment.Center,
        ) {
            RovstiText(
                digits.ifEmpty { strings.addNumberHint },
                modifier = Modifier.testTag(MultiCallTags.AddNumber),
                style = RovstiTheme.type.title1.copy(fontFeatureSettings = "tnum"),
                color = if (digits.isEmpty()) colors.labelTertiary else colors.labelPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(Spacing.s))
        Column(
            verticalArrangement = Arrangement.spacedBy(InCallMetrics.AddKeyGapY),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            KeyRows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(InCallMetrics.AddKeyGapX)) {
                    row.forEach { (char, letters) ->
                        DigitKey(
                            char = char,
                            letters = letters,
                            diameter = InCallMetrics.AddKey,
                            showLetters = true,
                            voicemail = null,
                            onDown = { digits = (digits + it).takeLast(MAX_NUMBER) },
                            onHold = if (char == '0') { _ -> digits = digits.dropLast(1) + '+' } else null,
                            modifier = Modifier.testTag(MultiCallTags.addKey(char)),
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(InCallMetrics.AddKeyGapX),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.size(InCallMetrics.AddKey))
                CallKey(
                    diameter = InCallMetrics.AddKey,
                    description = strings.addDial,
                    onCall = { if (digits.isNotEmpty()) onDial(digits) },
                    onMenu = {},
                    modifier = Modifier.testTag(MultiCallTags.AddCall),
                )
                if (digits.isEmpty()) {
                    Spacer(Modifier.size(InCallMetrics.AddKey))
                } else {
                    DeleteKey(
                        diameter = InCallMetrics.AddKey,
                        description = strings.addDelete,
                        onDelete = { digits = digits.dropLast(1) },
                        onClear = { digits = "" },
                    )
                }
            }
        }
    }
}

private const val MAX_NUMBER = 32

@Composable
private fun ColumnScope.AddPeople(
    rows: ImmutableList<FavPerson>,
    strings: InCallStrings,
    sheet: SheetState?,
    onDial: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        RovstiText(
            strings.addNone,
            Modifier.fillMaxWidth().padding(Spacing.xl),
            style = RovstiTheme.type.body,
            color = RovstiTheme.colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
        return
    }
    val scroll = if (sheet != null) Modifier.nestedScroll(sheetScrollConnection(sheet)) else Modifier
    LazyColumn(scroll.weight(1f).fillMaxWidth()) {
        items(rows, key = { it.key }, contentType = { "person" }) { person ->
            Column {
                PersonListRow(
                    person,
                    Modifier.testTag(MultiCallTags.addPerson(person.key)),
                    onClick = { person.number?.let(onDial) },
                    trailing = null,
                )
                RowSeparator(inset = TextStartWithLeading)
            }
        }
    }
}

/**
 * The conference panel: one row per person with Split (a private call) and End, and End all under them. A row's button
 * shows only what the carrier allows; when the conference is gone the sheet closes itself.
 */
@Composable
fun ColumnScope.ConferenceSheetBody(
    state: StateFlow<InCallUi>,
    strings: InCallStrings,
    onAction: (InCallAction) -> Unit,
    onGone: () -> Unit,
) {
    val ui by state.collectAsState()
    val multi = ui.multi
    LaunchedEffect(multi.conference, ui.live) { if (!multi.conference || !ui.live) onGone() }
    CompositionLocalProvider(LocalIosBackdrop provides null) {
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .testTag(MultiCallTags.Conference),
        ) {
            RovstiText(
                strings.confTitle,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.gutter)
                        .padding(top = Spacing.s)
                        .semantics { heading() },
                style = RovstiTheme.type.title2,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Spacer(Modifier.height(Spacing.l))
            InsetGroupedSection {
                multi.participants.forEach { p ->
                    row {
                        ListRow(
                            title = p.label,
                            leading = { Avatar(p.label, size = InCallMetrics.PersonAvatar, colorKey = p.avatarKey) },
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                                    if (p.canSplit) {
                                        CapsuleButton(
                                            strings.confSplit,
                                            onClick = { onAction(InCallAction.Split(p.id)) },
                                            modifier = Modifier.testTag(MultiCallTags.split(p.id)),
                                        )
                                    }
                                    if (p.canDrop) {
                                        CapsuleButton(
                                            strings.confDrop,
                                            onClick = { onAction(InCallAction.Drop(p.id)) },
                                            modifier = Modifier.testTag(MultiCallTags.drop(p.id)),
                                            tint = Color(DECLINE_RED),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.l))
            InsetGroupedSection {
                row {
                    ListRow(
                        title = strings.endAll,
                        titleColor = Color(DECLINE_RED),
                        onClick = { onAction(InCallAction.EndAll) },
                        modifier = Modifier.testTag(MultiCallTags.EndAll),
                    )
                }
            }
            Spacer(Modifier.height(Spacing.xl))
        }
    }
}
