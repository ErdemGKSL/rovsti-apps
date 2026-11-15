package dev.rosty.messages.spam

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.design.icons.RostyIcon
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.ios.component.IosText
import dev.rosty.design.ios.component.IosToggle
import dev.rosty.design.ios.foundation.IosTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.R
import dev.rosty.shell.settings.IosSettingsGroup
import dev.rosty.shell.settings.IosSettingsItem
import dev.rosty.shell.settings.SettingsFooter
import dev.rosty.shell.settings.SettingsPage

/** Test tags of the Spam filter page. */
object SpamTags {
    const val Page = "spam-page"
    const val Enabled = "spam-enabled"
    const val AddRule = "spam-add-rule"
    const val AddAllow = "spam-add-allow"
    const val Block = "spam-block"
    const val DeleteCodes = "spam-delete-codes"

    fun rule(index: Int) = "spam-rule-$index"

    fun allowed(index: Int) = "spam-allowed-$index"

    fun blocked(index: Int) = "spam-blocked-$index"
}

/** Where the rows of the Spam filter page lead; the page itself holds no state. */
class SpamPageLinks(
    val onAction: (SpamSettingsAction) -> Unit,
    /** The rule editor: a rule to edit, or null for a new one. */
    val editRule: (RuleRow?) -> Unit,
    val addAllowed: () -> Unit,
    val blockNumber: () -> Unit,
    /** M47's switch: turning it on asks first. */
    val setDeleteCodes: (Boolean) -> Unit,
)

/** A rule as one line ("Sender: PROMO*"). */
@Composable
fun ruleLabel(rule: RuleRow): String =
    when (rule.kind) {
        RuleKind.Sender -> stringResource(R.string.spam_rule_sender, rule.text)
        RuleKind.Keyword -> stringResource(R.string.spam_rule_keyword, rule.text)
        RuleKind.UnknownLinks -> stringResource(R.string.spam_rule_links)
        RuleKind.ShortCodes -> stringResource(if (rule.on) R.string.spam_rule_short_codes else R.string.spam_rule_short_codes_off)
    }

/**
 * Settings, Spam filter (E6.11, GPUI `settings_ui::spam`): the master switch, the rules (a tap edits, the editor
 * deletes), the senders that are always allowed, the system blocklist shared with Phone, and M47 "Delete codes after
 * copying". Every list ends in its add row; removing answers an Undo pill. Spam is never deleted or announced.
 */
@Composable
fun SpamSettingsPage(
    ui: SpamSettingsUi,
    links: SpamPageLinks,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    val danger = IosTheme.colors.systemRed
    val tint = IosTheme.tint
    SettingsPage(
        title = stringResource(R.string.settings_spam_row),
        modifier = modifier.testTag(SpamTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    ) {
        IosSettingsGroup(
            items =
                listOf(
                    switchItem(
                        title = stringResource(R.string.spam_filter_switch),
                        checked = ui.enabled,
                        tag = SpamTags.Enabled,
                        icon = RostyIcons.Shield,
                    ) { links.onAction(SpamSettingsAction.SetEnabled(!ui.enabled)) },
                ),
        )
        Footnote(stringResource(R.string.spam_filter_footer))

        IosSettingsGroup(
            title = stringResource(R.string.spam_group_rules),
            items =
                ui.rules.map { rule ->
                    IosSettingsItem(
                        title = {
                            IosText(
                                ruleLabel(rule),
                                Modifier.testTag(SpamTags.rule(rule.index)),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        enabled = ui.enabled,
                        onClick = { links.editRule(rule) },
                    )
                } + addItem(stringResource(R.string.spam_add_rule), SpamTags.AddRule, tint) { links.editRule(null) },
        )
        Footnote(stringResource(R.string.spam_rules_footer))

        IosSettingsGroup(
            title = stringResource(R.string.spam_group_allow),
            items =
                ui.allow.mapIndexed { index, sender ->
                    valueItem(sender, stringResource(R.string.spam_remove), SpamTags.allowed(index), danger) {
                        links.onAction(SpamSettingsAction.RemoveAllowed(index))
                    }
                } + addItem(stringResource(R.string.spam_add_allow), SpamTags.AddAllow, tint, onClick = links.addAllowed),
        )
        Footnote(stringResource(R.string.spam_allow_footer))

        IosSettingsGroup(
            title = stringResource(R.string.spam_group_blocked),
            items =
                ui.blocked.mapIndexed { index, number ->
                    valueItem(number, stringResource(R.string.spam_unblock), SpamTags.blocked(index), tint, ui.canBlock) {
                        links.onAction(SpamSettingsAction.Unblock(number))
                    }
                } +
                    addItem(
                        stringResource(R.string.spam_block_number),
                        SpamTags.Block,
                        tint,
                        enabled = ui.canBlock,
                        onClick = links.blockNumber,
                    ),
        )
        Footnote(stringResource(if (ui.canBlock) R.string.spam_blocked_footer else R.string.spam_blocked_unavailable))

        IosSettingsGroup(
            title = stringResource(R.string.spam_group_codes),
            items =
                listOf(
                    switchItem(
                        title = stringResource(R.string.spam_delete_codes),
                        checked = ui.deleteCodes,
                        tag = SpamTags.DeleteCodes,
                        icon = RostyIcons.Trash,
                    ) { links.setDeleteCodes(!ui.deleteCodes) },
                ),
        )
        Footnote(stringResource(R.string.spam_delete_codes_footer))
    }
}

@Composable
private fun switchItem(
    title: String,
    checked: Boolean,
    tag: String,
    icon: ImageVector,
    onToggle: () -> Unit,
) = IosSettingsItem(
    icon = rememberVectorPainter(icon),
    title = {
        Box(
            Modifier.testTag(tag).semantics {
                role = Role.Switch
                selected = checked
            },
        ) { IosText(title) }
    },
    trailingContent = { IosToggle(checked, onCheckedChange = null, exposeSemantics = false) },
    onClick = onToggle,
)

/** The last row of a list: "+ Add ..." in the tint, no chevron. */
private fun addItem(
    title: String,
    tag: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = IosSettingsItem(
    leadingContent = {
        RostyIcon(RostyIcons.Plus, null, tint = if (enabled) tint else IosTheme.colors.tertiaryLabel)
    },
    title = { IosText(title, Modifier.testTag(tag), color = if (enabled) tint else IosTheme.colors.tertiaryLabel) },
    trailingContent = {},
    enabled = enabled,
    onClick = onClick,
)

/** A sender or number with its one action as a word on the end ("Remove", "Unblock"); the whole row is the button. */
private fun valueItem(
    value: String,
    action: String,
    tag: String,
    actionColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) = IosSettingsItem(
    title = { IosText(value, Modifier.testTag(tag), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    trailingContent = { IosText(action, color = if (enabled) actionColor else IosTheme.colors.tertiaryLabel) },
    enabled = enabled,
    onClick = onClick,
)

@Composable
private fun Footnote(text: String) {
    SettingsFooter(text)
    Spacer(Modifier.height(Spacing.l))
}
