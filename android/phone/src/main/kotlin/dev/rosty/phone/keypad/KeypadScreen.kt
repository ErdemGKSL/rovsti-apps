package dev.rosty.phone.keypad

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.rosty.core.model.phone.FieldAction
import dev.rosty.core.model.phone.SimAccount
import dev.rosty.design.chrome.LocalContentInsets
import dev.rosty.design.chrome.Toolbar
import dev.rosty.design.chrome.ToolbarAction
import dev.rosty.design.haptics.LocalRostyHaptics
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosContextMenuEntry
import dev.rosty.design.ios.component.IosGlyph
import dev.rosty.design.ios.component.IosMenuItem
import dev.rosty.design.ios.component.IosMenuSection
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.list.Avatar
import dev.rosty.design.overlay.ActionSheetAction
import dev.rosty.design.overlay.ActionSheetEntry
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.LocalOverlay
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.text.DialField
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.phone.KeypadMetrics
import dev.rosty.phone.PhoneTags
import dev.rosty.phone.R
import dev.rosty.phone.RootLinks
import dev.rosty.phone.people.PhoneAvatar
import dev.rosty.phone.playRefusalHaptic
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.util.concurrent.atomic.AtomicInteger

/** What the Keypad needs from Android; the app implements it, tests record it. */
interface KeypadHost {
    /** Plays the DTMF tone of [char] (the app checks that tones are on before it asks). */
    fun tone(char: Char)

    /** The system contact editor on [number] (insert, or add to an existing contact). */
    fun addContact(
        number: String,
        create: Boolean,
    )

    fun share(text: String)

    fun message(number: String)

    /** Opens the Contacts tab so a contact can be picked for speed-dial key [digit]. */
    fun chooseSpeedDialContact(digit: Int)
}

/** Counts the recompositions of [KeypadContent] (a test reads it: typing one key may recompose at most twice). */
internal object KeypadProbe {
    val recompositions = AtomicInteger()
}

/** Recomposition budget of one key press. */
internal const val KEY_RECOMPOSITION_BUDGET = 2

/**
 * The Keypad tab (E5.2): the number field on top with the gear beside it, the suggestions (T9) under it, and the keys
 * anchored above the tab bar. The view model's [KeypadViewModel.effect]s become toasts, the Undo pill, sheets and menus
 * here; nothing is pushed into the layout, so typing never moves a key.
 */
@Composable
fun KeypadScreen(
    model: KeypadViewModel,
    host: KeypadHost,
    links: RootLinks,
    modifier: Modifier = Modifier,
) {
    val ui by model.state.collectAsState()
    val overlays = LocalOverlay.current
    val haptics = LocalRostyHaptics.current
    val fieldRect = remember { arrayOf(Rect.Zero) }
    val callRect = remember { arrayOf(Rect.Zero) }
    val currentHost by rememberUpdatedState(host)
    val strings = KeypadStrings.current()

    // Edits that do not come from a key (hardware keyboard, the system toolbar) reach the view model here.
    LaunchedEffect(model) {
        androidx.compose.runtime
            .snapshotFlow { model.field.text.toString() }
            .collect { model.onAction(KeypadAction.TextChanged) }
    }
    LaunchedEffect(model, overlays) {
        model.effect.collect { effect ->
            when (effect) {
                KeypadEffect.Reject -> {
                    haptics.reject()
                }

                is KeypadEffect.Say -> {
                    if (effect.notice.isSuccess()) haptics.confirm()
                    overlays.showToast(strings.say(effect.notice))
                }

                is KeypadEffect.Core -> {
                    playRefusalHaptic(haptics)
                    overlays.showToast(strings.refused(effect.refusal))
                }

                is KeypadEffect.Undo -> {
                    haptics.confirm()
                    overlays.showUndo(
                        message = strings.say(Notice.Cleared),
                        onUndo = { model.onAction(KeypadAction.Restore(effect.old)) },
                        actionLabel = strings.undo,
                    )
                }

                is KeypadEffect.ChooseSim -> {
                    overlays.present(simChooser(strings, effect, model::onAction))
                }

                is KeypadEffect.ChoosePaste -> {
                    overlays.present(pasteChooser(strings, effect, model::onAction))
                }

                is KeypadEffect.FieldMenu -> {
                    overlays.present(
                        IosContextMenuEntry(fieldRect[0], fieldMenuSections(strings, effect.entries, model::onAction)),
                    )
                }

                is KeypadEffect.CallMenu -> {
                    overlays.present(
                        IosContextMenuEntry(callRect[0], callMenuSections(strings, effect, model::onAction)),
                    )
                }

                is KeypadEffect.AssignSpeedDial -> {
                    overlays.present(speedDialSheet(strings, effect.digit, currentHost))
                }

                is KeypadEffect.AddContact -> {
                    currentHost.addContact(effect.number, effect.create)
                }

                is KeypadEffect.ShareText -> {
                    currentHost.share(effect.text)
                }

                is KeypadEffect.SendMessage -> {
                    currentHost.message(effect.number)
                }
            }
        }
    }
    KeypadContent(
        ui = ui,
        field = model.field,
        strings = strings,
        onAction = model::onAction,
        onTone = { currentHost.tone(it) },
        onMoreMatches = { overlays.present(matchesSheet(strings, ui.t9, model::onAction)) },
        onAddContact = { overlays.present(addContactSheet(strings, model::onAction)) },
        onFieldMenu = { rect ->
            fieldRect[0] = rect
            model.onAction(KeypadAction.OpenFieldMenu)
        },
        onCallMenu = { rect ->
            callRect[0] = rect
            model.onAction(KeypadAction.OpenCallMenu)
        },
        onSettings = links.openSettings,
        modifier = modifier,
    )
}

/** The Keypad without a view model: every callback is a lambda, so tests and screenshots drive it directly. */
@Composable
internal fun KeypadContent(
    ui: KeypadUi,
    field: TextFieldState,
    strings: KeypadStrings,
    onAction: (KeypadAction) -> Unit,
    onTone: (Char) -> Unit,
    onMoreMatches: () -> Unit,
    onAddContact: () -> Unit,
    onFieldMenu: (Rect) -> Unit,
    onCallMenu: (Rect) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onImeRequest: () -> Unit = {},
) {
    SideEffect { KeypadProbe.recompositions.incrementAndGet() }
    val insets = LocalContentInsets.current
    val haptics = LocalRostyHaptics.current
    val tonesOn = ui.tonesOn
    val currentOnAction by rememberUpdatedState(onAction)
    val currentTone by rememberUpdatedState(onTone)
    val currentHaptics by rememberUpdatedState(haptics)
    val toneOn by rememberUpdatedState(tonesOn)
    // One lambda per kind of key for the whole life of the screen: a press recomposes the field, never the keys.
    val down =
        remember {
            { c: Char ->
                currentHaptics.keyboardTap()
                if (toneOn) currentTone(c)
                currentOnAction(KeypadAction.Digit(c))
            }
        }
    val hold = remember { { c: Char -> currentOnAction(KeypadAction.Hold(c)) } }
    val delete = remember { { currentOnAction(KeypadAction.Backspace) } }
    val clear = remember { { currentOnAction(KeypadAction.Clear) } }
    val call = remember { { currentOnAction(KeypadAction.Call) } }
    val fieldMenu by rememberUpdatedState(onFieldMenu)
    val fieldMenuOpen = remember { { r: Rect -> fieldMenu(r) } }
    val density = LocalDensity.current

    BoxWithConstraints(modifier.fillMaxSize().testTag(KeypadTags.Root)) {
        val top = insets.calculateTopPadding()
        val bottom = insets.calculateBottomPadding() + KeypadMetrics.BarGap
        val sizes =
            remember(maxWidth, maxHeight, top, bottom, density.fontScale) {
                KeypadLayout.compute(maxWidth.value, (maxHeight - top - bottom).value, density.fontScale)
            }
        Column(
            Modifier
                .fillMaxSize()
                .padding(top = top, bottom = bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(sizes.fieldHeight.dp)) {
                DialField(
                    state = field,
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = KeypadMetrics.FieldSide)
                            .testTag(KeypadTags.Field),
                    description = strings.fieldDescription,
                    onLongPressOutside = fieldMenuOpen,
                    onImeRequest = onImeRequest,
                )
                // The same glass toolbar capsule as the other tabs' top bars (Recents, Favorites, Contacts), not a lone
                // round button, so the gear looks and sits the same on every tab.
                val settingsLabel = strings.settings
                val toolbar =
                    remember(settingsLabel, onSettings) {
                        persistentListOf(
                            ToolbarAction("settings", RostyIcons.Gear, settingsLabel, onClick = onSettings),
                        )
                    }
                Toolbar(
                    actions = toolbar,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(end = Spacing.gutter, top = KeypadMetrics.FieldTop)
                            .testTag(PhoneTags.SettingsButton),
                )
            }
            Suggestions(
                ui = ui,
                rows = sizes.suggestionRows,
                strings = strings,
                onPick = { onAction(KeypadAction.Suggestion(it)) },
                onMore = onMoreMatches,
                onAddContact = onAddContact,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            KeysBlock(
                sizes = sizes,
                strings = strings,
                down = down,
                hold = hold,
                delete = delete,
                clear = clear,
                call = call,
                onCallMenu = onCallMenu,
                showDelete = ui.dialed.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun KeysBlock(
    sizes: KeypadSizes,
    strings: KeypadStrings,
    down: (Char) -> Unit,
    hold: (Char) -> Unit,
    delete: () -> Unit,
    clear: () -> Unit,
    call: () -> Unit,
    onCallMenu: (Rect) -> Unit,
    showDelete: Boolean,
) {
    val key = sizes.key.dp
    Column(
        verticalArrangement = Arrangement.spacedBy(sizes.rowGap.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        for (row in KeyRows) {
            Row(horizontalArrangement = Arrangement.spacedBy(sizes.columnGap.dp)) {
                for ((char, letters) in row) {
                    DigitKey(
                        char = char,
                        letters = letters,
                        diameter = key,
                        showLetters = sizes.letters,
                        voicemail = if (char == '1') RostyIcons.Voicemail else null,
                        onDown = down,
                        onHold = hold,
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(sizes.columnGap.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(key))
            CallKey(key, strings.call, call, onCallMenu)
            if (showDelete) {
                DeleteKey(key, strings.delete, delete, clear)
            } else {
                Spacer(Modifier.size(key))
            }
        }
    }
}

/** The T9 suggestions and the line under them ("Add to Contacts", "N more"). */
@Composable
private fun Suggestions(
    ui: KeypadUi,
    rows: Int,
    strings: KeypadStrings,
    onPick: (T9Item) -> Unit,
    onMore: () -> Unit,
    onAddContact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = ui.t9.take(rows)
    val more = ui.t9.size - shown.size
    Column(modifier.testTag(KeypadTags.Suggestions)) {
        for ((index, item) in shown.withIndex()) {
            androidx.compose.runtime.key(
                item.key,
            ) { SuggestionRow(item, onPick, Modifier.testTag(KeypadTags.suggestion(index))) }
        }
        val actionLine = ui.unknown || more > 0 || ui.toneHint != null
        if (actionLine) {
            Row(
                Modifier.fillMaxWidth().height(KeypadMetrics.AddRow).padding(horizontal = Spacing.gutter),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val accent = RostyTheme.colors.accent
                val body = RostyTheme.type.subheadline
                if (ui.unknown) {
                    IosText(
                        strings.addContact,
                        Modifier
                            .weight(1f, fill = false)
                            .clickable(role = Role.Button, onClick = onAddContact)
                            .testTag(KeypadTags.AddContact),
                        color = accent,
                        style = body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else if (ui.toneHint != null) {
                    IosText(
                        strings.toneHint(ui.toneHint),
                        Modifier.weight(1f, fill = false),
                        color = RostyTheme.colors.labelSecondary,
                        style = body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (more > 0) {
                    if (ui.unknown) Spacer(Modifier.width(Spacing.xl))
                    IosText(
                        if (rows == 0) strings.matches(more) else strings.more(more),
                        Modifier
                            .clickable(role = Role.Button, onClick = onMore)
                            .testTag(KeypadTags.More),
                        color = accent,
                        style = body,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    item: T9Item,
    onPick: (T9Item) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RostyTheme.colors
    val type = RostyTheme.type
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = KeypadMetrics.SuggestionRow)
            .clickable(role = Role.Button) { onPick(item) }
            .padding(horizontal = Spacing.gutter),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        PhoneAvatar(
            name = item.name,
            number = item.number,
            size = KeypadMetrics.SuggestionAvatar,
            colorKey = item.contactId.toString(),
            contactId = item.contactId,
        )
        Column(Modifier.weight(1f)) {
            BasicText(
                text = highlighted(item),
                style = type.body.copy(color = colors.labelPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IosText(item.number, color = colors.labelSecondary, style = type.footnote, maxLines = 1)
        }
    }
}

/** The name with the letters the digits matched in bold. */
internal fun highlighted(item: T9Item): AnnotatedString =
    buildAnnotatedString {
        val span = item.span
        val name = item.name
        if (span == null || span.first < 0 || span.first >= name.length) {
            append(name)
            return@buildAnnotatedString
        }
        val end = minOf(span.last + 1, name.length)
        append(name.substring(0, span.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(name.substring(span.first, end)) }
        append(name.substring(end))
    }

// ---- menus and sheets (entries the effects present)

internal fun fieldMenuSections(
    strings: KeypadStrings,
    entries: ImmutableList<FieldEntry>,
    act: (KeypadAction) -> Unit,
): List<IosMenuSection> {
    val items =
        entries.map { entry ->
            when (entry) {
                is FieldEntry.Core -> {
                    IosMenuItem(
                        title = strings.field(entry.item.action),
                        icon =
                            glyph(
                                when (entry.item.action) {
                                    FieldAction.Copy -> RostyIcons.Copy
                                    FieldAction.Paste -> RostyIcons.Document
                                    FieldAction.Clear -> RostyIcons.Xmark
                                },
                            ),
                        role =
                            if (entry.item.action ==
                                FieldAction.Clear
                            ) {
                                dev.rosty.design.ios.component.IosButtonRole.Destructive
                            } else {
                                null
                            },
                        onClick = { act(KeypadAction.Field(entry.item.action)) },
                    )
                }

                FieldEntry.Share -> {
                    IosMenuItem(strings.share, icon = glyph(RostyIcons.Share), onClick = { act(KeypadAction.Share) })
                }

                is FieldEntry.E164 -> {
                    IosMenuItem(
                        title = strings.copyE164,
                        description = entry.text,
                        icon = glyph(RostyIcons.Globe),
                        onClick = { act(KeypadAction.CopyE164) },
                    )
                }
            }
        }
    return listOf(IosMenuSection(items))
}

private fun glyph(vector: androidx.compose.ui.graphics.vector.ImageVector): @Composable (Color) -> Unit =
    { tint -> IosGlyph(vector, tint, KeypadMetrics.MenuGlyph) }

internal fun callMenuSections(
    strings: KeypadStrings,
    menu: KeypadEffect.CallMenu,
    act: (KeypadAction) -> Unit,
): List<IosMenuSection> {
    val calls =
        if (menu.sims.isEmpty()) {
            listOf(
                IosMenuItem(
                    strings.call,
                    icon = glyph(RostyIcons.Phone),
                    onClick = { act(KeypadAction.Call) },
                ),
            )
        } else {
            menu.sims.map { sim ->
                IosMenuItem(
                    title = strings.callWith(sim.title),
                    icon = { _ -> SimDot(sim.colorArgb) },
                    onClick = { act(KeypadAction.CallOn(menu.number, sim.key)) },
                )
            }
        }
    val others =
        listOf(
            IosMenuItem(
                title = strings.whatsApp,
                icon = glyph(RostyIcons.WhatsApp),
                enabled = menu.whatsApp,
                onClick = { act(KeypadAction.WhatsAppCall) },
            ),
            IosMenuItem(
                title = strings.message,
                icon = glyph(RostyIcons.Message),
                onClick = { act(KeypadAction.Message) },
            ),
        )
    return listOf(IosMenuSection(calls), IosMenuSection(others))
}

@Composable
internal fun SimDot(argb: Int) {
    Box(Modifier.size(KeypadMetrics.SimDot).clip(CircleShape).background(Color(argb)))
}

internal fun simChooser(
    strings: KeypadStrings,
    effect: KeypadEffect.ChooseSim,
    act: (KeypadAction) -> Unit,
): SheetEntry =
    SheetEntry(
        title = strings.simTitle,
        detents =
            persistentListOf(
                Detent.Fixed(KeypadMetrics.SimSheetBase + (KeypadMetrics.SimRowHeight * effect.choices.size)),
            ),
    ) { sheet ->
        SimChooserSheet(effect.number, effect.choices, strings) { sim ->
            sheet.dismiss()
            act(KeypadAction.CallOn(effect.number, sim.key))
        }
    }

/** The dual-SIM chooser: one row per call-capable SIM, each with its colour dot. */
@Composable
internal fun SimChooserSheet(
    number: String,
    choices: ImmutableList<SimAccount>,
    strings: KeypadStrings,
    onPick: (SimAccount) -> Unit,
) {
    val colors = RostyTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag("sim-chooser")) {
        IosText(strings.simTitle, color = colors.labelPrimary, style = RostyTheme.type.headline)
        IosText(number, color = colors.labelSecondary, style = RostyTheme.type.footnote, maxLines = 1)
        Spacer(Modifier.height(Spacing.s))
        for (sim in choices) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(KeypadMetrics.SimRowHeight)
                    .clickable(role = Role.Button) { onPick(sim) }
                    .testTag("sim-${sim.key}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.m),
            ) {
                SimDot(sim.colorArgb)
                IosText(
                    sim.title,
                    Modifier.weight(1f),
                    color = colors.labelPrimary,
                    style = RostyTheme.type.body,
                    maxLines = 1,
                )
                IosText(sim.chip, color = colors.labelSecondary, style = RostyTheme.type.footnote, maxLines = 1)
            }
        }
    }
}

internal fun pasteChooser(
    strings: KeypadStrings,
    effect: KeypadEffect.ChoosePaste,
    act: (KeypadAction) -> Unit,
): ActionSheetEntry =
    ActionSheetEntry(
        title = strings.pasteTitle,
        message = strings.pasteMessage,
        actions =
            kotlinx.collections.immutable.persistentListOf<ActionSheetAction>().addAll(
                effect.numbers.map { n -> ActionSheetAction(n) { act(KeypadAction.PasteChosen(n)) } },
            ),
        cancelLabel = strings.cancel,
    )

internal fun addContactSheet(
    strings: KeypadStrings,
    act: (KeypadAction) -> Unit,
): ActionSheetEntry =
    ActionSheetEntry(
        actions =
            persistentListOf(
                ActionSheetAction(strings.createContact) { act(KeypadAction.AddContact(create = true)) },
                ActionSheetAction(strings.addExisting) { act(KeypadAction.AddContact(create = false)) },
            ),
        cancelLabel = strings.cancel,
    )

private fun speedDialSheet(
    strings: KeypadStrings,
    digit: Int,
    host: KeypadHost,
): ActionSheetEntry =
    ActionSheetEntry(
        title = strings.speedTitle,
        message = strings.speedMessage(digit),
        actions = persistentListOf(ActionSheetAction(strings.speedChoose) { host.chooseSpeedDialContact(digit) }),
        cancelLabel = strings.cancel,
    )

internal fun matchesSheet(
    strings: KeypadStrings,
    all: ImmutableList<T9Item>,
    act: (KeypadAction) -> Unit,
): SheetEntry =
    SheetEntry(title = strings.matchesTitle, detents = persistentListOf(Detent.Medium, Detent.Large)) { sheet ->
        LazyColumn(Modifier.fillMaxSize().testTag("matches-sheet")) {
            items(all, key = { it.key }, contentType = { "match" }) { item ->
                SuggestionRow(item, { picked ->
                    sheet.dismiss()
                    act(KeypadAction.Suggestion(picked))
                })
            }
        }
    }

/** Builds the intent the app uses for "Add to Contacts" / "Create New Contact" (kept pure for the tests). */
fun addContactIntent(
    number: String,
    create: Boolean,
): Intent =
    Intent(if (create) Intent.ACTION_INSERT else Intent.ACTION_INSERT_OR_EDIT).apply {
        type = android.provider.ContactsContract.Contacts.CONTENT_ITEM_TYPE
        putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, number)
    }
