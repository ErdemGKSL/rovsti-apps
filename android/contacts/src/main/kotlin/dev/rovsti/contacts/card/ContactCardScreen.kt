package dev.rovsti.contacts.card

import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.rovsti.contacts.R
import dev.rovsti.core.model.contacts.TitleDetail
import dev.rovsti.design.chrome.LocalContentInsets
import dev.rovsti.design.chrome.ScaffoldSpec
import dev.rovsti.design.chrome.ScreenLazyPage
import dev.rovsti.design.chrome.Toolbar
import dev.rovsti.design.chrome.ToolbarAction
import dev.rovsti.design.controls.MessageSource
import dev.rovsti.design.controls.SourceGlyph
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosButton
import dev.rovsti.design.ios.component.IosButtonSizing
import dev.rovsti.design.ios.component.IosButtonStyle
import dev.rovsti.design.ios.foundation.IosControlSize
import dev.rovsti.design.ios.foundation.LocalIosBackdrop
import dev.rovsti.design.list.Avatar
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.media.showPhotoViewer
import dev.rovsti.design.motion.rememberIosOverscrollFactory
import dev.rovsti.design.overlay.ContextMenuStyle
import dev.rovsti.design.overlay.LocalOverlayOrNull
import dev.rovsti.design.overlay.MenuItem
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.longPressMenu
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing
import dev.rovsti.design.transition.rovstiSharedBounds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** Test tags of the card. */
object CardTags {
    const val Screen = "card-screen"
    const val Avatar = "card-avatar"
    const val AvatarFace = "card-avatar-face"
    const val Name = "card-name"
    const val Presence = "card-presence"
    const val Capsules = "card-capsules"
    const val Recent = "card-recent"
    const val OpenInContacts = "card-open-in-contacts"
    const val Gate = "card-recent-gate"

    fun capsule(kind: CapsuleKind) = "card-capsule-${kind.name.lowercase()}"

    fun row(key: String) = "card-row-$key"

    fun chip(key: String) = "card-wa-chip-$key"

    fun wish(key: String) = "card-wish-$key"
}

/**
 * One contact's page (E4.3), stateless. The page is a [ScreenLazyPage]: the hero (88 dp avatar, name, organisation,
 * presence and the row of action capsules) is item 0 and scrolls away under the frosted bar, which then shows the name.
 * Below it, one grouped section per kind of field, "Recent" and the "Open in Android Contacts" row. A long press on a
 * field lifts the row and opens its menu over the page; nothing in the list moves. The avatar is the shared element of
 * the list row it came from (`avatar-<id>`, 500 ms) and opens the photo viewer when the contact has a photo.
 *
 * [gate] draws the call-log permission row inside the "Recent" section while the log may not be read.
 */
@Composable
fun ContactCardScreen(
    state: ContactCardUiState,
    onAction: (ContactCardAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    gate: (@Composable () -> Unit)? = null,
    overlays: OverlayController? = LocalOverlayOrNull.current,
) {
    val header = state.header
    val colors = RovstiTheme.colors
    val insets = LocalContentInsets.current
    val contentPadding =
        PaddingValues.Absolute(
            left = insets.calculateLeftPadding(LayoutDirection.Ltr),
            right = insets.calculateRightPadding(LayoutDirection.Ltr),
            top = WindowInsets.safeDrawing.asPaddingValues().calculateTopPadding(),
            bottom = insets.calculateBottomPadding() + Spacing.xl,
        )
    var avatarBounds by remember { mutableStateOf<Rect?>(null) }
    CompositionLocalProvider(
        LocalOverscrollFactory provides rememberIosOverscrollFactory(),
    ) {
        ScreenLazyPage(
            title = header?.name.orEmpty(),
            modifier = modifier.fillMaxSize().testTag(CardTags.Screen),
            onBack = onBack,
            pageColor = colors.page,
            contentPadding = contentPadding,
            listState = listState,
            titleAlphaProvider = { ((it - TITLE_FADE_FROM) / TITLE_FADE_SPAN).coerceIn(0f, 1f) },
            titleSideReserve = Spacing.gutter + Sizes.button + Spacing.s,
            titleEndReserve = Spacing.gutter + Sizes.button * 2 + Spacing.s * 2,
            listModifier = Modifier.widthIn(max = Sizes.contentMaxWidth).fillMaxSize().testTag("scaffold-list"),
            actions = {
                if (header != null) {
                    val toolbar =
                        remember(header.starred, onAction) {
                            persistentListOf(
                                ToolbarAction("edit", RovstiIcons.Pencil, "Edit") { onAction(ContactCardAction.Edit) },
                                ToolbarAction(
                                    id = "star",
                                    icon = if (header.starred) RovstiIcons.StarFilled else RovstiIcons.Star,
                                    label = if (header.starred) "Remove from Favorites" else "Add to Favorites",
                                ) { onAction(ContactCardAction.ToggleStar) },
                            )
                        }
                    Toolbar(actions = toolbar)
                }
            },
            largeTitle = {
                if (header != null) {
                    Hero(
                        header = header,
                        capsules = state.capsules,
                        onAction = onAction,
                        onBounds = { avatarBounds = it },
                        onPhoto = {
                            val uri = header.photoUri
                            if (uri != null && overlays != null) {
                                overlays.showPhotoViewer(
                                    photos = persistentListOf(uri),
                                    initialPage = 0,
                                    actions = persistentListOf(),
                                    anchorFor = { avatarBounds },
                                )
                            }
                        },
                    )
                }
            },
        ) {
            if (header != null) {
                for (section in state.sections) {
                    item(key = "section-${section.kind}-${section.title}", contentType = "section") {
                        FieldSection(section, overlays, onAction)
                    }
                }
                recentItem(state.recent, gate)
                item(key = "open-in-contacts", contentType = "section") {
                    SectionSpacing {
                        InsetGroupedSection {
                            row {
                                ListRow(
                                    title = "Open in Android Contacts",
                                    trailing = {
                                        RovstiIcon(
                                            RovstiIcons.ChevronRight,
                                            null,
                                            tint = RovstiTheme.colors.labelTertiary,
                                            size = Sizes.chevron,
                                        )
                                    },
                                    titleColor = RovstiTheme.colors.accent,
                                    onClick = { onAction(ContactCardAction.OpenInContacts) },
                                    modifier = Modifier.testTag(CardTags.OpenInContacts),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val TITLE_FADE_FROM = 0.45f
private const val TITLE_FADE_SPAN = 0.2f

@Composable
private fun SectionSpacing(content: @Composable () -> Unit) {
    Box(Modifier.padding(top = Spacing.sectionGap)) { content() }
}

// ---- hero

@Composable
private fun Hero(
    header: CardHeader,
    capsules: ImmutableList<CardCapsule>,
    onAction: (ContactCardAction) -> Unit,
    onBounds: (Rect) -> Unit,
    onPhoto: () -> Unit,
) {
    val colors = RovstiTheme.colors
    val type = RovstiTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = ScaffoldSpec.BarHeight, bottom = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val clickable =
            if (header.photoUri != null) {
                Modifier.clickable(role = Role.Button, onClick = onPhoto)
            } else {
                Modifier
            }
        Box(
            Modifier
                .testTag(CardTags.Avatar)
                .semantics { contentDescription = if (header.photoUri != null) "View photo" else header.name }
                .onGloballyPositioned { coordinates ->
                    val origin = coordinates.positionInWindow()
                    onBounds(Rect(origin, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
                }.then(clickable),
        ) {
            Avatar(
                name = header.name,
                size = CardMetrics.HeroAvatar,
                photoModel = header.photoUri,
                colorKey = header.id.toString(),
                modifier = Modifier.rovstiSharedBounds("avatar-${header.id}").testTag(CardTags.AvatarFace),
            )
        }
        RovstiText(
            header.name,
            modifier =
                Modifier
                    .padding(horizontal = Spacing.gutter)
                    .padding(top = Spacing.m)
                    .semantics { heading() }
                    .testTag(CardTags.Name),
            style = type.title1,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (header.isMyCard) {
            RovstiText(
                "My Card",
                style = type.footnote,
                color = colors.accent,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        header.orgLine?.let {
            RovstiText(
                it,
                modifier = Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.xs),
                style = type.body,
                color = colors.labelSecondary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        header.presence?.let {
            Row(
                Modifier.padding(top = Spacing.xs).testTag(CardTags.Presence),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                SourceGlyph(MessageSource.WhatsApp, contentDescription = null)
                RovstiText(
                    it,
                    style = type.subheadline,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (capsules.isNotEmpty()) {
            Capsules(
                capsules,
                onAction,
                Modifier.padding(horizontal = Spacing.gutter).padding(top = Spacing.xl),
            )
        }
    }
}

/** The action capsules: one row, equal width, never wrapping. At a large font scale only the icons stay. */
@Composable
private fun Capsules(
    capsules: ImmutableList<CardCapsule>,
    onAction: (ContactCardAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val iconsOnly = LocalDensity.current.fontScale >= ICON_ONLY_FONT_SCALE
    Row(
        modifier.fillMaxWidth().testTag(CardTags.Capsules),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        for (capsule in capsules) {
            CapsuleItem(capsule, iconsOnly, onAction, Modifier.weight(1f))
        }
    }
}

private fun CapsuleKind.icon(): ImageVector =
    when (this) {
        CapsuleKind.Call -> RovstiIcons.Phone
        CapsuleKind.Message -> RovstiIcons.Message
        CapsuleKind.WhatsApp -> RovstiIcons.WhatsApp
        CapsuleKind.Email -> RovstiIcons.Mail
    }

private fun CapsuleKind.action(): ContactCardAction =
    when (this) {
        CapsuleKind.Call -> ContactCardAction.Call
        CapsuleKind.Message -> ContactCardAction.Message
        CapsuleKind.WhatsApp -> ContactCardAction.WhatsApp
        CapsuleKind.Email -> ContactCardAction.Email
    }

@Composable
private fun CapsuleItem(
    capsule: CardCapsule,
    iconsOnly: Boolean,
    onAction: (ContactCardAction) -> Unit,
    modifier: Modifier,
) {
    val colors = RovstiTheme.colors
    // Inside the recorded page content there is no backdrop to sample (a layer cannot draw into itself): the capsule
    // takes the flat Bordered fill, the same decision as CapsuleButton.
    val glass = LocalIosBackdrop.current != null
    val tint = if (capsule.kind == CapsuleKind.WhatsApp) colors.success else colors.accent
    Box(
        modifier
            .height(CardMetrics.CapsuleHeight)
            .semantics { contentDescription = capsule.label }
            .testTag(CardTags.capsule(capsule.kind)),
    ) {
        IosButton(
            onClick = { onAction(capsule.kind.action()) },
            modifier = Modifier.fillMaxSize(),
            style = if (glass) IosButtonStyle.Glass else IosButtonStyle.Bordered,
            tint = tint,
            sizing = IosButtonSizing.Flexible,
            enabled = capsule.enabled,
            controlSize = IosControlSize.Large,
        ) {
            CapsuleContent(capsule, iconsOnly, tint)
        }
    }
}

@Composable
private fun RowScope.CapsuleContent(
    capsule: CardCapsule,
    iconsOnly: Boolean,
    tint: Color,
) {
    Column(
        Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RovstiIcon(capsule.kind.icon(), null, tint = tint, size = CardMetrics.CapsuleIcon)
        if (!iconsOnly) {
            RovstiText(
                capsule.label,
                style = RovstiTheme.type.caption1,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(top = CardMetrics.CapsuleLabelGap),
            )
        }
    }
}

// ---- sections

@Composable
private fun FieldSection(
    section: CardSection,
    overlays: OverlayController?,
    onAction: (ContactCardAction) -> Unit,
) {
    SectionSpacing {
        InsetGroupedSection(header = section.title) {
            for (item in section.rows) {
                row { FieldRow(item, overlays, onAction) }
            }
        }
    }
}

private fun LazyListScope.recentItem(
    recent: CardRecent,
    gate: (@Composable () -> Unit)?,
) {
    when (recent) {
        CardRecent.Hidden -> {
            Unit
        }

        is CardRecent.NeedsPermission -> {
            item(key = "recent", contentType = "section") {
                SectionSpacing {
                    InsetGroupedSection(
                        header = "Recent",
                        footer = recent.text.detail,
                        modifier = Modifier.testTag(CardTags.Gate),
                    ) {
                        row {
                            if (gate != null) {
                                gate()
                            } else {
                                ListRow(title = recent.text.title)
                            }
                        }
                    }
                }
            }
        }

        is CardRecent.Calls -> {
            item(key = "recent", contentType = "section") {
                SectionSpacing {
                    InsetGroupedSection(
                        header = "Recent",
                        footer = recent.summary,
                        modifier = Modifier.testTag(CardTags.Recent),
                    ) {
                        for (call in recent.rows) {
                            row { RecentRow(call) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(call: TitleDetail) {
    ListRow(title = call.title, secondary = call.detail)
}

// ---- one field

@Composable
private fun FieldRow(
    row: CardRow,
    overlays: OverlayController?,
    onAction: (ContactCardAction) -> Unit,
) {
    val tap: (() -> Unit)? =
        remember(row, onAction) {
            when {
                row.tap != null -> ({ onAction(ContactCardAction.Tap(row.tap)) })
                row.relationName != null -> ({ onAction(ContactCardAction.Relation(row.relationName)) })
                else -> null
            }
        }
    val items =
        remember(row.menu, onAction) {
            row.menu.map { item -> menuItemOf(item, onAction) }.toImmutableList()
        }
    val tag = Modifier.testTag(CardTags.row(row.key))
    if (overlays != null && items.isNotEmpty()) {
        Box(
            tag.longPressMenu(
                controller = overlays,
                items = items,
                style = ContextMenuStyle.Lifted(preview = { FieldBody(row, onAction, preview = true) }),
                onTap = tap,
            ),
        ) {
            FieldBody(row, onAction)
        }
    } else {
        Box(if (tap != null) tag.clickable(role = Role.Button, onClick = tap) else tag) {
            FieldBody(row, onAction)
        }
    }
}

private fun menuItemOf(
    item: FieldMenuItem,
    onAction: (ContactCardAction) -> Unit,
): MenuItem =
    MenuItem.Action(
        label = item.label,
        icon = menuIcon(item),
        onClick = { onAction(ContactCardAction.Field(item.op)) },
    )

private fun menuIcon(item: FieldMenuItem): ImageVector =
    when (val op = item.op) {
        is FieldOp.Copy -> RovstiIcons.Copy
        is FieldOp.ShareText -> RovstiIcons.Share
        is FieldOp.Dial -> RovstiIcons.Phone
        is FieldOp.Sms -> RovstiIcons.Message
        is FieldOp.Email -> RovstiIcons.Mail
        is FieldOp.SetDefault -> if (op.clear) RovstiIcons.StarFilled else RovstiIcons.Star
        is FieldOp.OpenUrl -> if (item.label.contains("Maps")) RovstiIcons.Map else RovstiIcons.Globe
    }

/** The row itself: a small caption over the value, then the Default tag, the WhatsApp chip and the Wish chip. */
@Composable
private fun FieldBody(
    row: CardRow,
    onAction: (ContactCardAction) -> Unit,
    preview: Boolean = false,
) {
    val colors = RovstiTheme.colors
    val type = RovstiTheme.type
    val source = remember { MutableInteractionSource() }
    val chatLabel = stringResource(R.string.card_chat_whatsapp)
    val actionable = row.tap != null || row.relationName != null
    val base =
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMinTwoLine)
            .then(if (preview) Modifier.background(colors.page) else Modifier)
    Row(
        base.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Column(Modifier.weight(1f)) {
            row.label?.let {
                RovstiText(
                    it,
                    style = type.footnote,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RovstiText(
                row.title,
                modifier = Modifier.padding(top = if (row.label != null) Spacing.textGap else CardMetrics.NoGap),
                style = type.body,
                color = if (actionable) colors.accent else colors.labelPrimary,
                maxLines = if (row.kind == RowKind.Note || row.kind == RowKind.Address) 6 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.isDefault) Tag("Default")
        row.waJid?.let { jid ->
            Box(
                Modifier
                    .testTag(CardTags.chip(row.key))
                    .size(Sizes.touch)
                    .semantics { contentDescription = chatLabel }
                    .clickable(interactionSource = source, indication = null, role = Role.Button) {
                        onAction(ContactCardAction.WaChip(jid))
                    },
                contentAlignment = Alignment.Center,
            ) {
                SourceGlyph(MessageSource.WhatsApp, contentDescription = null)
            }
        }
        if (row.wish != null && row.wishNumber != null) {
            val text = row.wish
            val number = row.wishNumber
            Box(
                Modifier
                    .testTag(CardTags.wish(row.key))
                    .heightIn(min = Sizes.touch)
                    .clickable(role = Role.Button) { onAction(ContactCardAction.Wish(number, text)) }
                    .padding(horizontal = Spacing.xs),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .background(colors.accent.copy(alpha = 0.14f), CircleShape)
                        .padding(horizontal = Spacing.m, vertical = Spacing.xs),
                ) {
                    RovstiText(
                        stringResource(R.string.card_wish),
                        style = type.footnote,
                        color = colors.accent,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String) {
    val colors = RovstiTheme.colors
    Box(
        Modifier
            .background(colors.fillTertiary, RoundedCornerShape(CardMetrics.TagRadius))
            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
    ) {
        RovstiText(text, style = RovstiTheme.type.caption1, color = colors.labelSecondary, maxLines = 1)
    }
}
