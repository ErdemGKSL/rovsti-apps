package dev.rosty.messages.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.design.list.RowSeparator
import dev.rosty.design.list.SectionHeader
import dev.rosty.design.list.TextStartWithLeading
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.messages.ui.MessagesAvatar

/** The Messages section under the chats while searching (M7): a header, one row per hit, the count line. */
internal fun LazyListScope.messagesSection(
    section: MessagesSectionUi,
    strings: InboxStrings,
    onAction: (InboxAction) -> Unit,
) {
    if (section.hits.isEmpty()) return
    item(key = "messages-header", contentType = "header") {
        SectionHeader(strings.messagesHeader, Modifier.testTag(InboxTags.MessagesHeader))
    }
    section.hits.forEachIndexed { at, hit ->
        item(key = "hit-${hit.key}", contentType = "hit") {
            if (at > 0) RowSeparator(inset = TextStartWithLeading)
            HitRow(hit) { onAction(InboxAction.OpenRoute(hit.route)) }
        }
    }
    item(key = "messages-footer", contentType = "footer") {
        val line =
            listOfNotNull(
                strings.resultsCount(section.count),
                strings.partialNote.takeIf { section.partial },
                strings.smsMissingNote.takeIf { section.smsMissing },
            ).joinToString(" · ")
        RostyText(
            line,
            modifier = Modifier.padding(horizontal = Spacing.gutter, vertical = Spacing.m),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
        )
    }
}

@Composable
private fun HitRow(
    hit: SearchHitUi,
    onClick: () -> Unit,
) {
    val colors = RostyTheme.colors
    val type = RostyTheme.type
    val snippet =
        remember(hit, colors) {
            val start = hit.highlightStart.coerceIn(0, hit.snippet.length)
            val end = hit.highlightEnd.coerceIn(start, hit.snippet.length)
            AnnotatedString
                .Builder(hit.snippet)
                .apply {
                    addStyle(SpanStyle(color = colors.labelPrimary, fontWeight = FontWeight.SemiBold), start, end)
                }.toAnnotatedString()
        }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.rowMinTwoLine)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(InboxTags.hit(hit.key))
            .padding(horizontal = Spacing.gutter, vertical = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MessagesAvatar(hit.title, hit.avatar)
        Column(Modifier.weight(1f).padding(start = Spacing.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RostyText(
                    hit.title,
                    modifier = Modifier.weight(1f),
                    style = type.body,
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                RostyText(
                    hit.time,
                    modifier = Modifier.padding(start = Spacing.s),
                    style = type.footnote,
                    color = colors.labelSecondary,
                    maxLines = 1,
                )
            }
            BasicText(
                snippet,
                modifier = Modifier.padding(top = Spacing.textGap),
                style = type.subheadline.copy(color = colors.labelSecondary),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
