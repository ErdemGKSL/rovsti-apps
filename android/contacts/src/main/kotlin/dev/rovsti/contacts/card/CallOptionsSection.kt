package dev.rovsti.contacts.card

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.contacts.R
import dev.rovsti.design.icons.RovstiIcon
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.list.InsetGroupedSection
import dev.rovsti.design.list.ListRow
import dev.rovsti.design.list.ListRowSwitch
import dev.rovsti.design.text.UiText
import dev.rovsti.design.text.text
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Sizes
import dev.rovsti.design.theme.Spacing

/** Test tags of the "Calls" section. */
object CallOptionsTags {
    const val Section = "card-calls"
    const val Ringtone = "card-calls-ringtone"
    const val Voicemail = "card-calls-voicemail"
    const val Block = "card-calls-block"
}

/**
 * The "Calls" section as one list item: Ringtone (the tone's name and a chevron), the Send Calls to Voicemail switch and
 * the red Block This Caller / Unblock This Caller row. The rows exist from the first frame the section is drawn, with
 * their final text, so flipping the switch or finishing a block changes no height and the sections below stay put.
 */
fun LazyListScope.callOptionsItem(
    state: CallOptionsUiState?,
    onAction: (CallOptionsAction) -> Unit,
) {
    if (state == null || !state.loaded) return
    item(key = "calls", contentType = "section") {
        Box(Modifier.padding(top = Spacing.sectionGap)) {
            InsetGroupedSection(
                modifier = Modifier.testTag(CallOptionsTags.Section),
                header = stringResource(R.string.contacts_calls),
            ) {
                row {
                    ListRow(
                        title = stringResource(R.string.contacts_ringtone),
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RovstiText(
                                    state.ringtone.text(),
                                    color = RovstiTheme.colors.labelSecondary,
                                    style = RovstiTheme.type.body,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(end = Spacing.xs),
                                )
                                RovstiIcon(
                                    RovstiIcons.ChevronRight,
                                    null,
                                    tint = RovstiTheme.colors.labelTertiary,
                                    size = Sizes.chevron,
                                )
                            }
                        },
                        onClick = { onAction(CallOptionsAction.ChooseRingtone) },
                        modifier = Modifier.testTag(CallOptionsTags.Ringtone),
                    )
                }
                row {
                    ListRowSwitch(
                        title = stringResource(R.string.contacts_voicemail_setting),
                        checked = state.voicemail,
                        onCheckedChange = { onAction(CallOptionsAction.SetVoicemail(it)) },
                        modifier = Modifier.testTag(CallOptionsTags.Voicemail),
                    )
                }
                val block = state.block
                val unblock = block is BlockRow.Available && block.blocked
                if (block != BlockRow.Hidden) {
                    row {
                        ListRow(
                            title =
                                if (unblock) {
                                    stringResource(
                                        R.string.contacts_unblock_caller,
                                    )
                                } else {
                                    stringResource(R.string.contacts_block_caller)
                                },
                            secondary =
                                if (block ==
                                    BlockRow.NeedsRole
                                ) {
                                    stringResource(R.string.contacts_set_default_phone)
                                } else {
                                    null
                                },
                            secondaryMaxLines = 2,
                            titleColor = if (unblock) Color.Unspecified else RovstiTheme.colors.destructive,
                            onClick = { onAction(CallOptionsAction.ToggleBlock) },
                            modifier = Modifier.testTag(CallOptionsTags.Block),
                        )
                    }
                }
            }
        }
    }
}
