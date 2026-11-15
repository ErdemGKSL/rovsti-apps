package dev.rosty.contacts.card

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
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
import dev.rosty.contacts.R
import dev.rosty.core.model.contacts.TitleDetail
import dev.rosty.design.chrome.LocalContentInsets
import dev.rosty.design.chrome.ScaffoldSpec
import dev.rosty.design.chrome.ScreenLazyPage
import dev.rosty.design.chrome.Toolbar
import dev.rosty.design.chrome.ToolbarAction
import dev.rosty.design.controls.MessageSource
import dev.rosty.design.controls.SourceGlyph
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosButton
import dev.rosty.design.ios.component.IosButtonSizing
import dev.rosty.design.ios.component.IosButtonStyle
import dev.rosty.design.ios.foundation.IosControlSize
import dev.rosty.design.ios.foundation.LocalIosBackdrop
import dev.rosty.design.list.Avatar
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.media.PhotoSource
import dev.rosty.design.media.ViewerPhoto
import dev.rosty.design.media.showPhotoViewer
import dev.rosty.design.motion.rememberIosOverscrollFactory
import dev.rosty.design.overlay.ButtonMenuEntry
import dev.rosty.design.overlay.ContextMenuStyle
import dev.rosty.design.overlay.LocalOverlayOrNull
import dev.rosty.design.overlay.MenuItem
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayEntry
import dev.rosty.design.overlay.longPressMenu
import dev.rosty.design.text.UiText
import dev.rosty.design.text.resolve
import dev.rosty.design.text.text
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.design.transition.rostySharedBounds
import dev.rosty.people.PersonAvatar
import dev.rosty.people.ui.AvatarFace
import dev.rosty.people.ui.ContactAvatar
import dev.rosty.people.ui.rememberPersonAvatar
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

    fun cardChip(key: String) = "card-chip-$key"
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
    calls: CallOptionsUiState? = null,
    onCalls: (CallOptionsAction) -> Unit = {},
    photoViewer: PhotoViewerLauncher? = null,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val header = state.header
    val colors = RostyTheme.colors
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
                                ToolbarAction(
                                    "edit",
                                    RostyIcons.Pencil,
                                    resources.getString(R.string.favorites_edit),
                                ) { onAction(ContactCardAction.Edit) },
                                ToolbarAction(
                                    id = "star",
                                    icon = if (header.starred) RostyIcons.StarFilled else RostyIcons.Star,
                                    label =
                                        if (header.starred) {
                                            resources.getString(R.string.favorites_remove)
                                        } else {
                                            resources.getString(R.string.favorites_add_star)
                                        },
                                ) { onAction(ContactCardAction.ToggleStar) },
                            )
                        }
                    Toolbar(
                        actions = toolbar,
                        onOverflow = { anchor, _ ->
                            overlays?.present(
                                ButtonMenuEntry(anchor, overflowMenu(context, anchor, overlays, header, onAction)),
                            )
                        },
                    )
                }
            },
            largeTitle = {
                if (header != null) {
                    Hero(
                        header = header,
                        capsules = state.capsules,
                        onAction = onAction,
                        onBounds = { avatarBounds = it },
                        onPhoto = { avatar ->
                            when {
                                avatar is PersonAvatar.Own && photoViewer != null -> {
                                    photoViewer.open(header) { avatarBounds }
                                }

                                avatar is PersonAvatar.Own && overlays != null -> {
                                    overlays.showPhotoViewer(
                                        photos = persistentListOf(avatar.uri),
                                        initialPage = 0,
                                        actions = persistentListOf(),
                                        anchorFor = { avatarBounds },
                                    )
                                }

                                avatar is PersonAvatar.WhatsApp -> {
                                    photoViewer?.openWhatsApp(header, avatar) { avatarBounds }
                                        ?: overlays?.showPhotoViewer(
                                            photos = persistentListOf(whatsAppViewerPhoto(avatar, null)),
                                            initialPage = 0,
                                            actions = persistentListOf(),
                                            anchorFor = { avatarBounds },
                                        )
                                }
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
                callOptionsItem(calls, onCalls)
                recentItem(state.recent, gate)
                item(key = "open-in-contacts", contentType = "section") {
                    SectionSpacing {
                        InsetGroupedSection {
                            row {
                                ListRow(
                                    title = resources.getString(R.string.contacts_open_android_contacts),
                                    trailing = {
                                        RostyIcon(
                                            RostyIcons.ChevronRight,
                                            null,
                                            tint = RostyTheme.colors.labelTertiary,
                                            size = Sizes.chevron,
                                        )
                                    },
                                    titleColor = RostyTheme.colors.accent,
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

/** Opens the photo viewer of the card: the page decides which actions the toolbar has. [anchorFor] is the avatar's rect. */
fun interface PhotoViewerLauncher {
    fun open(
        header: CardHeader,
        anchorFor: () -> Rect?,
    )

    /**
     * The WhatsApp picture of a contact without a photo of its own: read-only, so no Edit menu. Answers the open
     * viewer; null (the default) leaves it to the screen, which opens it without a toolbar or the full-size swap.
     */
    fun openWhatsApp(
        header: CardHeader,
        picture: PersonAvatar.WhatsApp,
        anchorFor: () -> Rect?,
    ): OverlayEntry? = null
}

/**
 * The viewer photo of a WhatsApp avatar: the cached picture at once (as both layers, so the full-size one that
 * [upgrade] brings fades in over the same picture), flying out of the circle.
 */
fun whatsAppViewerPhoto(
    picture: PersonAvatar.WhatsApp,
    upgrade: (suspend () -> Any?)?,
): ViewerPhoto =
    ViewerPhoto(
        full = picture.uri,
        thumb = picture.uri,
        source = PhotoSource.WhatsApp,
        circular = true,
        upgrade = upgrade,
    )

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
    onPhoto: (PersonAvatar) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val colors = RostyTheme.colors
    val type = RostyTheme.type
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = ScaffoldSpec.BarHeight, bottom = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The contact's own photo, else its WhatsApp picture, opens the viewer; initials do not.
        val avatar = rememberPersonAvatar(header.face)
        val clickable =
            if (avatar is PersonAvatar.Initials) {
                Modifier
            } else {
                Modifier.clickable(role = Role.Button) { onPhoto(avatar) }
            }
        val avatarLabel =
            when (avatar) {
                is PersonAvatar.Own -> resources.getString(R.string.contacts_view_photo)
                is PersonAvatar.WhatsApp -> resources.getString(R.string.contacts_view_whatsapp_photo)
                PersonAvatar.Initials -> header.name
            }
        Box(
            Modifier
                .testTag(CardTags.Avatar)
                .semantics { contentDescription = avatarLabel }
                .onGloballyPositioned { coordinates ->
                    val origin = coordinates.positionInWindow()
                    onBounds(Rect(origin, Size(coordinates.size.width.toFloat(), coordinates.size.height.toFloat())))
                }.then(clickable),
        ) {
            ContactAvatar(
                name = header.name,
                face = header.face,
                size = CardMetrics.HeroAvatar,
                colorKey = header.id.toString(),
                modifier = Modifier.rostySharedBounds("avatar-${header.id}").testTag(CardTags.AvatarFace),
            )
        }
        RostyText(
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
            RostyText(
                resources.getString(R.string.contacts_my_card),
                style = type.footnote,
                color = colors.accent,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        header.orgLine?.let {
            RostyText(
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
                RostyText(
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
        CapsuleKind.Call -> RostyIcons.Phone
        CapsuleKind.Message -> RostyIcons.Message
        CapsuleKind.WhatsApp -> RostyIcons.WhatsApp
        CapsuleKind.Email -> RostyIcons.Mail
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
    val colors = RostyTheme.colors
    // Inside the recorded page content there is no backdrop to sample (a layer cannot draw into itself): the capsule
    // takes the flat Bordered fill, the same decision as CapsuleButton.
    val glass = LocalIosBackdrop.current != null
    val tint = if (capsule.kind == CapsuleKind.WhatsApp) colors.success else colors.accent
    val capsuleLabel = capsule.label.text()
    Box(
        modifier
            .height(CardMetrics.CapsuleHeight)
            .semantics { contentDescription = capsuleLabel }
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
        RostyIcon(capsule.kind.icon(), null, tint = tint, size = CardMetrics.CapsuleIcon)
        if (!iconsOnly) {
            RostyText(
                capsule.label.text(),
                style = RostyTheme.type.caption1,
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
        InsetGroupedSection(header = section.title?.text()) {
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
                        header = stringResource(R.string.contacts_recent),
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
                        header = stringResource(R.string.contacts_recent),
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
    val context = LocalContext.current
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
            row.menu.map { item -> menuItemOf(item, onAction, context) }.toImmutableList()
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
    context: Context,
): MenuItem =
    MenuItem.Action(
        label = item.label.resolve(context),
        icon = menuIcon(item),
        onClick = { onAction(ContactCardAction.Field(item.op)) },
    )

private fun menuIcon(item: FieldMenuItem): ImageVector =
    when (val op = item.op) {
        is FieldOp.Copy -> {
            RostyIcons.Copy
        }

        is FieldOp.ShareText -> {
            RostyIcons.Share
        }

        is FieldOp.Dial -> {
            RostyIcons.Phone
        }

        is FieldOp.Sms -> {
            RostyIcons.Message
        }

        is FieldOp.Email -> {
            RostyIcons.Mail
        }

        is FieldOp.SetDefault -> {
            if (op.clear) RostyIcons.StarFilled else RostyIcons.Star
        }

        is FieldOp.OpenUrl -> {
            val labelId = (item.label as? UiText.Res)?.id
            if (labelId == R.string.contacts_maps || labelId == R.string.contacts_open_in_maps) {
                RostyIcons.Map
            } else {
                RostyIcons.Globe
            }
        }
    }

/** The row itself: a small caption over the value, then the Default tag, the WhatsApp chip and the Wish chip. */
@Composable
private fun FieldBody(
    row: CardRow,
    onAction: (ContactCardAction) -> Unit,
    preview: Boolean = false,
) {
    val colors = RostyTheme.colors
    val type = RostyTheme.type
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
                RostyText(
                    it.text(),
                    style = type.footnote,
                    color = colors.labelSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RostyText(
                row.title.text(),
                modifier = Modifier.padding(top = if (row.label != null) Spacing.textGap else CardMetrics.NoGap),
                style = type.body,
                color = if (actionable) colors.accent else colors.labelPrimary,
                maxLines = if (row.kind == RowKind.Note || row.kind == RowKind.Address) 6 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.isDefault) Tag(stringResource(R.string.contacts_default))
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
        if (row.birthday != null) {
            DateChip(stringResource(R.string.card_studio_chip), CardTags.cardChip(row.key)) {
                onAction(ContactCardAction.MakeCard)
            }
        }
        if (row.wish != null && row.wishNumber != null) {
            val text = row.wish
            val number = row.wishNumber
            DateChip(stringResource(R.string.card_wish), CardTags.wish(row.key)) {
                onAction(ContactCardAction.Wish(number, text))
            }
        }
    }
}

/** A small accent pill on the date row (Card, Wish): one line, never a wrapping chip row, a 48 dp touch target. */
@Composable
private fun DateChip(
    text: String,
    tag: String,
    onClick: () -> Unit,
) {
    val colors = RostyTheme.colors
    Box(
        Modifier
            .testTag(tag)
            .heightIn(min = Sizes.touch)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = Spacing.xs),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .background(colors.accent.copy(alpha = 0.14f), CircleShape)
                .padding(horizontal = Spacing.m, vertical = Spacing.xs),
        ) {
            RostyText(text, style = RostyTheme.type.footnote, color = colors.accent, maxLines = 1)
        }
    }
}

@Composable
private fun Tag(text: String) {
    val colors = RostyTheme.colors
    Box(
        Modifier
            .background(colors.fillTertiary, RoundedCornerShape(CardMetrics.TagRadius))
            .padding(horizontal = Spacing.s, vertical = Spacing.xs),
    ) {
        RostyText(text, style = RostyTheme.type.caption1, color = colors.labelSecondary, maxLines = 1)
    }
}

/**
 * The "..." menu of the card (E4.9): Share and Copy open a second menu from the same button, Export .vcf asks where to
 * save the file, Add to Home Screen pins the shortcut and Delete Contact asks first.
 */
private fun overflowMenu(
    context: Context,
    anchor: Rect,
    overlays: OverlayController,
    header: CardHeader,
    onAction: (ContactCardAction) -> Unit,
): ImmutableList<MenuItem> {
    fun submenu(items: ImmutableList<MenuItem>) = { overlays.present(ButtonMenuEntry(anchor, items)) }
    val share =
        persistentListOf<MenuItem>(
            MenuItem.Action(context.getString(R.string.contacts_share_as_text), RostyIcons.Share) {
                onAction(ContactCardAction.ShareText)
            },
            MenuItem.Action(context.getString(R.string.contacts_share_as_file), RostyIcons.Document) {
                onAction(ContactCardAction.ShareFile)
            },
            MenuItem.Action(
                context.getString(R.string.contacts_show_qr),
                RostyIcons.QrCode,
            ) { onAction(ContactCardAction.ShowQr) },
        )
    val copy =
        persistentListOf<MenuItem>(
            MenuItem.Action(context.getString(R.string.contacts_copy_contact), RostyIcons.Copy) {
                onAction(ContactCardAction.CopyContact)
            },
            MenuItem.Action(context.getString(R.string.contacts_copy_vcard), RostyIcons.Document) {
                onAction(ContactCardAction.CopyVcard)
            },
        )
    return persistentListOf(
        MenuItem.Action(context.getString(R.string.contacts_share), RostyIcons.Share) { submenu(share)() },
        MenuItem.Action(context.getString(R.string.contacts_copy), RostyIcons.Copy) { submenu(copy)() },
        MenuItem.Action(context.getString(R.string.card_studio_menu), RostyIcons.Document) {
            onAction(ContactCardAction.MakeCard)
        },
        MenuItem.Action(context.getString(R.string.contacts_export_vcf), RostyIcons.Download) {
            onAction(ContactCardAction.ExportVcf)
        },
        MenuItem.Action(context.getString(R.string.contacts_add_to_home_screen), RostyIcons.AddToHome) {
            onAction(ContactCardAction.AddToHome)
        },
        MenuItem.Separator,
        MenuItem.Action(
            if (header.isMyCard) {
                context.getString(R.string.contacts_not_me)
            } else {
                context.getString(R.string.contacts_this_is_me)
            },
            RostyIcons.Person,
        ) { onAction(ContactCardAction.ToggleMyCard) },
        MenuItem.Action(
            if (header.isEmergency) {
                context.getString(R.string.contacts_remove_from_emergency)
            } else {
                context.getString(R.string.contacts_add_to_emergency)
            },
            RostyIcons.MedicalCross,
        ) { onAction(ContactCardAction.ToggleEmergency) },
        MenuItem.Separator,
        MenuItem.Action(context.getString(R.string.contacts_delete_contact), RostyIcons.Trash, destructive = true) {
            onAction(ContactCardAction.Delete)
        },
    )
}
