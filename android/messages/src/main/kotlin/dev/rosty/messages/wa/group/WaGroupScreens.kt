package dev.rosty.messages.wa.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import dev.rosty.design.chrome.ScaffoldSpec
import dev.rosty.design.chrome.ScreenLazyPage
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRow
import dev.rosty.design.list.ListRowChevron
import dev.rosty.design.list.ListRowSwitch
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.text.GlassTextField
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.messages.inbox.RowAvatar
import dev.rosty.messages.theme.GroupMetrics
import dev.rosty.messages.ui.MessagesAvatar
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the group pages and sheets (E6-WA R6). */
object GroupTags {
    const val Page = "wa-group-info"
    const val Header = "wa-group-header"
    const val Left = "wa-group-left"
    const val Settings = "wa-group-settings"
    const val Announce = "wa-group-announce"
    const val Locked = "wa-group-locked"
    const val Approval = "wa-group-approval"
    const val Link = "wa-group-link"
    const val Requests = "wa-group-requests"
    const val Add = "wa-group-add"
    const val Leave = "wa-group-leave"
    const val More = "wa-group-more"
    const val Search = "wa-group-search"
    const val Results = "wa-group-results"
    const val LinkSheet = "wa-group-link-sheet"
    const val LinkReset = "wa-group-link-reset"
    const val Picker = "wa-group-picker"
    const val PickerField = "wa-group-picker-field"
    const val PickerDone = "wa-group-picker-done"
    const val Subject = "wa-group-subject"
    const val Join = "wa-group-join"
    const val JoinButton = "wa-group-join-button"

    fun member(jid: String) = "wa-group-member-$jid"

    fun option(key: String) = "wa-group-option-$key"

    fun invite(jid: String) = "wa-group-invite-$jid"
}

/** What the info page does when tapped; each action is checked again by WhatsApp. */
@Immutable
class GroupInfoActions(
    val onBack: () -> Unit = {},
    val onMember: (GroupMemberUi) -> Unit = {},
    val onSetting: (type: String, on: Boolean) -> Unit = { _, _ -> },
    val onLink: () -> Unit = {},
    val onRequests: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onEditSubject: () -> Unit = {},
    val onEditDescription: () -> Unit = {},
    val onPicture: () -> Unit = {},
    val onTimer: () -> Unit = {},
    val onLeave: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onSubgroup: (SubgroupUi) -> Unit = {},
)

/**
 * The group info page (E6-WA R6): the group's picture, subject, member count and creation; its description; my role;
 * the disappearing timer (the R4 setting) and a pointer to the chat menu's own settings; for admins the settings
 * switches, the invite link, the join requests and "Add Members"; the community's groups; the members (me first,
 * admins, then by name, in pages) and "Exit Group". A group I left keeps what was known and says so.
 */
@Composable
fun GroupInfoScreen(
    state: GroupInfoUi,
    actions: GroupInfoActions,
    timerLabel: String,
    searchField: TextFieldState? = null,
) {
    val title = stringResource(R.string.wa_group_info_title)
    ScreenLazyPage(
        title = title,
        onBack = actions.onBack,
        modifier = Modifier.testTag(GroupTags.Page),
        largeTitle = {
            Spacer(Modifier.statusBarsPadding().height(ScaffoldSpec.BarHeight))
            GroupHeader(state, actions)
        },
    ) {
        if (state.left) {
            item(key = "left") {
                RostyText(
                    stringResource(R.string.wa_group_left_notice),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.gutter, vertical = Spacing.s)
                            .testTag(GroupTags.Left),
                    style = RostyTheme.type.subheadline,
                    color = RostyTheme.colors.labelSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
        item(key = "about") {
            InsetGroupedSection(Modifier.padding(top = Spacing.sectionGap)) {
                val description = state.description
                if (description != null) {
                    row {
                        ListRow(
                            title = description,
                            secondary = stringResource(R.string.wa_group_description),
                            titleMaxLines = DESCRIPTION_LINES,
                            onClick = actions.onEditDescription.takeIf { state.abilities.editInfo },
                        )
                    }
                } else if (state.abilities.editInfo) {
                    row {
                        ListRow(
                            title = stringResource(R.string.wa_group_add_description),
                            titleColor = RostyTheme.colors.accent,
                            onClick = actions.onEditDescription,
                        )
                    }
                }
                state.myRole?.let { role -> row { ListRow(title = role) } }
                row {
                    ListRow(
                        title = stringResource(R.string.wa_disappearing),
                        meta = timerLabel,
                        leading = { RostyIcon(RostyIcons.Clock, null, tint = RostyTheme.colors.labelSecondary) },
                        trailing = if (state.left) null else ({ ListRowChevron() }),
                        onClick = actions.onTimer.takeIf { !state.left },
                    )
                }
            }
            if (!state.left) {
                RostyText(
                    stringResource(R.string.wa_group_chat_settings_hint),
                    modifier = Modifier.padding(horizontal = Spacing.gutter * 2, vertical = Spacing.s),
                    style = RostyTheme.type.footnote,
                    color = RostyTheme.colors.labelSecondary,
                )
            }
        }
        if (state.abilities.settings) {
            item(key = "settings") {
                InsetGroupedSection(
                    Modifier.padding(top = Spacing.sectionGap).testTag(GroupTags.Settings),
                    header = stringResource(R.string.wa_group_settings),
                ) {
                    row {
                        ListRowSwitch(
                            stringResource(R.string.wa_group_only_admins_send),
                            state.announce,
                            { actions.onSetting("announce", it) },
                            Modifier.testTag(GroupTags.Announce),
                        )
                    }
                    row {
                        ListRowSwitch(
                            stringResource(R.string.wa_group_only_admins_edit),
                            state.locked,
                            { actions.onSetting("locked", it) },
                            Modifier.testTag(GroupTags.Locked),
                        )
                    }
                    row {
                        ListRowSwitch(
                            stringResource(R.string.wa_group_approval),
                            state.approval,
                            { actions.onSetting("approval", it) },
                            Modifier.testTag(GroupTags.Approval),
                        )
                    }
                }
            }
        }
        if (state.abilities.inviteLink || state.abilities.add || state.abilities.editInfo) {
            item(key = "admin") {
                InsetGroupedSection(Modifier.padding(top = Spacing.sectionGap)) {
                    if (state.abilities.add) {
                        row {
                            ListRow(
                                title = stringResource(R.string.wa_group_add_members),
                                modifier = Modifier.testTag(GroupTags.Add),
                                leading = { RostyIcon(RostyIcons.PersonAdd, null, tint = RostyTheme.colors.accent) },
                                titleColor = RostyTheme.colors.accent,
                                onClick = actions.onAdd,
                            )
                        }
                    }
                    if (state.abilities.inviteLink) {
                        row {
                            ListRow(
                                title = stringResource(R.string.wa_group_invite_link),
                                modifier = Modifier.testTag(GroupTags.Link),
                                leading = { RostyIcon(RostyIcons.Share, null, tint = RostyTheme.colors.accent) },
                                trailing = { ListRowChevron() },
                                onClick = actions.onLink,
                            )
                        }
                    }
                    if (state.abilities.requests && state.approval) {
                        row {
                            ListRow(
                                title = stringResource(R.string.wa_group_requests),
                                modifier = Modifier.testTag(GroupTags.Requests),
                                leading = { RostyIcon(RostyIcons.People, null, tint = RostyTheme.colors.accent) },
                                trailing = { ListRowChevron() },
                                onClick = actions.onRequests,
                            )
                        }
                    }
                    if (state.abilities.editInfo) {
                        row {
                            ListRow(
                                title = stringResource(R.string.wa_group_edit_subject),
                                leading = { RostyIcon(RostyIcons.Pencil, null, tint = RostyTheme.colors.accent) },
                                onClick = actions.onEditSubject,
                            )
                        }
                        row {
                            ListRow(
                                title = stringResource(R.string.wa_group_change_picture),
                                leading = { RostyIcon(RostyIcons.Photo, null, tint = RostyTheme.colors.accent) },
                                onClick = actions.onPicture,
                            )
                        }
                    }
                }
            }
        }
        if (state.subgroups.isNotEmpty()) {
            item(key = "community") {
                InsetGroupedSection(
                    Modifier.padding(top = Spacing.sectionGap),
                    header = stringResource(R.string.wa_group_community_groups),
                ) {
                    for (group in state.subgroups) {
                        row {
                            ListRow(
                                title = group.subject,
                                secondary =
                                    listOfNotNull(
                                        stringResource(R.string.wa_group_announcements).takeIf { group.announcement },
                                        group.size,
                                    ).joinToString(" · ").ifEmpty { null },
                                leading = {
                                    RostyIcon(
                                        if (group.announcement) RostyIcons.Bell else RostyIcons.People,
                                        null,
                                        tint = RostyTheme.colors.labelSecondary,
                                    )
                                },
                                onClick = { actions.onSubgroup(group) },
                            )
                        }
                    }
                }
            }
        }
        item(key = "members-head") {
            RostyText(
                stringResource(R.string.wa_group_members) + " · " + state.count,
                modifier = Modifier.padding(start = Spacing.gutter * 2, top = Spacing.sectionGap, bottom = Spacing.s),
                style = RostyTheme.type.footnote,
                color = RostyTheme.colors.labelSecondary,
            )
            if (searchField != null && state.total > SEARCH_FROM) {
                GlassTextField(
                    searchField,
                    stringResource(R.string.wa_group_search_members),
                    Modifier
                        .padding(horizontal = Spacing.gutter, vertical = Spacing.xs)
                        .testTag(GroupTags.Search),
                )
            }
        }
        // A large group lists its members lazily, page by page.
        items(state.members.size, key = { "m-" + state.members[it].jid }, contentType = { "member" }) { index ->
            val member = state.members[index]
            if (index > 0) RowSeparator(inset = TextStartWithLeading)
            MemberRow(member) { actions.onMember(member) }
        }
        if (state.hasMore) {
            item(key = "more") {
                ListRow(
                    title = stringResource(R.string.wa_group_more_members),
                    modifier = Modifier.testTag(GroupTags.More),
                    titleColor = RostyTheme.colors.accent,
                    onClick = actions.onMore,
                )
            }
        }
        if (state.abilities.leave) {
            item(key = "leave") {
                InsetGroupedSection(Modifier.padding(top = Spacing.sectionGap, bottom = Spacing.xl)) {
                    row {
                        ListRow(
                            title = stringResource(R.string.wa_group_leave),
                            modifier = Modifier.testTag(GroupTags.Leave),
                            titleColor = RostyTheme.colors.destructive,
                            onClick = actions.onLeave,
                        )
                    }
                }
            }
        }
    }
}

/** The picture, subject, count and creation line at the top of the page. */
@Composable
private fun GroupHeader(
    state: GroupInfoUi,
    actions: GroupInfoActions,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(GroupTags.Header),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        MessagesAvatar(
            state.subject,
            RowAvatar(waJid = state.jid, group = true, colorKey = state.jid),
            size = GroupMetrics.HeaderAvatar,
        )
        Spacer(Modifier.height(GroupMetrics.HeaderGap - Spacing.xs))
        RostyText(
            state.subject,
            style = RostyTheme.type.title2,
            color = RostyTheme.colors.labelPrimary,
            textAlign = TextAlign.Center,
            maxLines = SUBJECT_LINES,
        )
        RostyText(
            state.count,
            style = RostyTheme.type.subheadline,
            color = RostyTheme.colors.labelSecondary,
        )
        state.community?.let {
            RostyText(it, style = RostyTheme.type.footnote, color = RostyTheme.colors.accent)
        }
        state.created?.let {
            RostyText(
                it,
                style = RostyTheme.type.footnote,
                color = RostyTheme.colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** One member: their picture, the best name (never LID digits), the number under it, the role at the end. */
@Composable
private fun MemberRow(
    member: GroupMemberUi,
    onClick: () -> Unit,
) {
    ListRow(
        title = member.name,
        secondary = member.detail,
        modifier = Modifier.testTag(GroupTags.member(member.jid)),
        leading = {
            MessagesAvatar(
                member.name,
                RowAvatar(waJid = member.jid, numbers = persistentListOf(member.jid), colorKey = member.jid),
            )
        },
        trailing = member.badge?.let { badge -> { RoleBadge(badge) } },
        onClick = onClick.takeIf { !member.me },
    )
}

@Composable
private fun RoleBadge(text: String) {
    RostyText(
        text,
        modifier =
            Modifier
                .clip(RoundedCornerShape(GroupMetrics.BadgePadH))
                .background(RostyTheme.colors.fillSecondary)
                .padding(horizontal = GroupMetrics.BadgePadH, vertical = GroupMetrics.BadgePadV),
        style = RostyTheme.type.caption1,
        color = RostyTheme.colors.labelSecondary,
        maxLines = 1,
    )
}

/** Who a bulk action did not work for and why; "Send Invite" for someone whose privacy refused the add. */
@Composable
fun GroupResultsContent(
    done: String?,
    rows: ImmutableList<ResultRowUi>,
    onInvite: (ResultRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag(GroupTags.Results)) {
        done?.let {
            RostyText(
                it,
                modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.s),
                style = RostyTheme.type.subheadline,
                color = RostyTheme.colors.labelSecondary,
            )
        }
        InsetGroupedSection(Modifier.padding(top = Spacing.s, bottom = Spacing.l)) {
            for (row in rows) {
                row {
                    ListRow(
                        title = row.name,
                        secondary = row.why,
                        secondaryMaxLines = WHY_LINES,
                        trailing =
                            row.invite?.let {
                                {
                                    CapsuleButton(
                                        stringResource(R.string.wa_group_send_invite),
                                        onClick = { onInvite(row) },
                                        modifier = Modifier.testTag(GroupTags.invite(row.jid)),
                                    )
                                }
                            },
                    )
                }
            }
        }
    }
}

/** The invite link with copy, share and reset (admins). */
@Composable
fun GroupLinkContent(
    link: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(bottom = Spacing.l).testTag(GroupTags.LinkSheet),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        RostyText(
            link,
            modifier = Modifier.padding(horizontal = Spacing.gutter),
            style = RostyTheme.type.body,
            color = RostyTheme.colors.accent,
            maxLines = LINK_LINES,
        )
        RostyText(
            stringResource(R.string.wa_group_link_reset_note),
            modifier = Modifier.padding(horizontal = Spacing.gutter),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
        )
        InsetGroupedSection(Modifier.padding(top = Spacing.s)) {
            row {
                ListRow(
                    title = stringResource(R.string.wa_group_link_copy),
                    leading = { RostyIcon(RostyIcons.Copy, null, tint = RostyTheme.colors.accent) },
                    onClick = onCopy,
                )
            }
            row {
                ListRow(
                    title = stringResource(R.string.wa_group_link_share),
                    leading = { RostyIcon(RostyIcons.Share, null, tint = RostyTheme.colors.accent) },
                    onClick = onShare,
                )
            }
            row {
                ListRow(
                    title = stringResource(R.string.wa_group_link_reset),
                    modifier = Modifier.testTag(GroupTags.LinkReset),
                    leading = { RostyIcon(RostyIcons.Rotate, null, tint = RostyTheme.colors.destructive) },
                    titleColor = RostyTheme.colors.destructive,
                    onClick = onReset,
                )
            }
        }
    }
}

/** One person to pick (a contact's number or a typed number). */
@Immutable
data class PickOption(
    val key: String,
    val title: String,
    val detail: String,
    val jid: String,
    val photoUri: String?,
)

/**
 * Picks people: a field (name or number), the matching contacts with a check on the picked ones, and the button. With
 * [subject] it is the "New Group" flow (the group's name above the people).
 */
@Composable
fun GroupPickerContent(
    field: TextFieldState,
    options: ImmutableList<PickOption>,
    picked: Set<String>,
    onToggle: (PickOption) -> Unit,
    button: String,
    enabled: Boolean,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    subject: TextFieldState? = null,
) {
    Column(
        modifier.fillMaxWidth().testTag(GroupTags.Picker),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        subject?.let {
            GlassTextField(
                it,
                stringResource(R.string.wa_group_subject_hint),
                Modifier.fillMaxWidth().testTag(GroupTags.Subject),
            )
        }
        GlassTextField(
            field,
            stringResource(R.string.wa_group_add_hint),
            Modifier.fillMaxWidth().testTag(GroupTags.PickerField),
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            options.forEachIndexed { index, option ->
                if (index > 0) RowSeparator(inset = TextStartWithLeading)
                ListRow(
                    title = option.title,
                    secondary = option.detail,
                    modifier = Modifier.testTag(GroupTags.option(option.key)),
                    leading = {
                        MessagesAvatar(
                            option.title,
                            RowAvatar(
                                photoUri = option.photoUri,
                                numbers = persistentListOf(option.jid),
                                colorKey = option.jid,
                            ),
                        )
                    },
                    trailing =
                        if (option.jid in picked) {
                            { RostyIcon(RostyIcons.CircleCheck, null, tint = RostyTheme.colors.accent) }
                        } else {
                            { RostyIcon(RostyIcons.Circle, null, tint = RostyTheme.colors.labelTertiary) }
                        },
                    onClick = { onToggle(option) },
                )
            }
        }
        CapsuleButton(
            button,
            onClick = onDone,
            enabled = enabled,
            style = CapsuleStyle.Filled,
            fillWidth = true,
            modifier = Modifier.padding(bottom = Spacing.l).testTag(GroupTags.PickerDone),
        )
    }
}

/** A text to edit (the subject or the description) and Save. */
@Composable
fun GroupTextContent(
    field: TextFieldState,
    hint: String,
    lines: Int,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(bottom = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        GlassTextField(field, hint, Modifier.fillMaxWidth(), maxLines = lines)
        CapsuleButton(
            stringResource(R.string.wa_group_save),
            onClick = onSave,
            style = CapsuleStyle.Filled,
            fillWidth = true,
        )
    }
}

/** The group a link leads to, before joining: picture, subject, size, description, and Join or Request to Join. */
@Immutable
data class JoinPreviewUi(
    val jid: String = "",
    val subject: String = "",
    val size: String? = null,
    val description: String? = null,
    val approval: Boolean = false,
    /** The link is invalid or was reset. */
    val invalid: Boolean = false,
    /** A request was sent and waits for an admin. */
    val requested: Boolean = false,
    val loading: Boolean = false,
)

@Composable
fun JoinPreviewContent(
    preview: JoinPreviewUi,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(bottom = Spacing.l).testTag(GroupTags.Join),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        if (preview.invalid) {
            RostyText(
                stringResource(R.string.wa_group_link_invalid),
                style = RostyTheme.type.body,
                color = RostyTheme.colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
            return@Column
        }
        MessagesAvatar(
            preview.subject,
            RowAvatar(group = true, colorKey = preview.jid.ifEmpty { preview.subject }),
            size = GroupMetrics.HeaderAvatar,
        )
        RostyText(
            preview.subject,
            style = RostyTheme.type.title2,
            color = RostyTheme.colors.labelPrimary,
            textAlign = TextAlign.Center,
            maxLines = SUBJECT_LINES,
        )
        preview.size?.let {
            RostyText(it, style = RostyTheme.type.subheadline, color = RostyTheme.colors.labelSecondary)
        }
        preview.description?.let {
            RostyText(
                it,
                style = RostyTheme.type.subheadline,
                color = RostyTheme.colors.labelSecondary,
                textAlign = TextAlign.Center,
                maxLines = DESCRIPTION_LINES,
            )
        }
        if (preview.approval && !preview.requested) {
            RostyText(
                stringResource(R.string.wa_group_join_needs_approval),
                style = RostyTheme.type.footnote,
                color = RostyTheme.colors.labelSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (preview.requested) {
            RostyText(
                stringResource(R.string.wa_group_join_requested),
                style = RostyTheme.type.subheadline,
                color = RostyTheme.colors.labelPrimary,
                textAlign = TextAlign.Center,
            )
        } else {
            CapsuleButton(
                stringResource(if (preview.approval) R.string.wa_group_request_join else R.string.wa_group_join),
                onClick = onJoin,
                enabled = !preview.loading,
                style = CapsuleStyle.Filled,
                fillWidth = true,
                modifier = Modifier.padding(top = Spacing.s).testTag(GroupTags.JoinButton),
            )
        }
    }
}

/** The pending join requests with Approve and Reject per person. */
@Composable
fun GroupRequestsContent(
    rows: ImmutableList<ResultRowUi>?,
    onAnswer: (ResultRowUi, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reject = stringResource(R.string.wa_group_reject)
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Spacing.l)) {
        if (rows != null && rows.isEmpty()) {
            RostyText(
                stringResource(R.string.wa_group_requests_none),
                modifier = Modifier.padding(Spacing.xl),
                color = RostyTheme.colors.labelSecondary,
            )
        }
        InsetGroupedSection(Modifier.padding(top = Spacing.s)) {
            for (row in rows.orEmpty()) {
                row {
                    ListRow(
                        title = row.name,
                        trailing = {
                            CapsuleButton(stringResource(R.string.wa_group_approve), onClick = { onAnswer(row, true) })
                        },
                        actions =
                            persistentListOf(
                                dev.rosty.design.list.CustomAction(reject) { onAnswer(row, false) },
                            ),
                        onLongClick = { onAnswer(row, false) },
                    )
                }
            }
        }
    }
}

private const val DESCRIPTION_LINES = 6
private const val SUBJECT_LINES = 2
private const val WHY_LINES = 3
private const val LINK_LINES = 2

/** From this many members the list gets a search field. */
private const val SEARCH_FROM = 20
