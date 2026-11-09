package dev.rovsti.phone.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.design.controls.GlassIconButton
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.ios.component.IosText
import dev.rovsti.design.ios.foundation.IosTheme
import dev.rovsti.design.overlay.LocalOverlay
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import dev.rovsti.phone.PhoneMetrics
import dev.rovsti.phone.R
import dev.rovsti.shell.settings.SettingsPage

/** Test tags of the Diagnostics page. */
object DiagnosticsTags {
    const val Page = "diagnostics-page"
    const val CopyAll = "diagnostics-copy-all"

    fun section(title: String) = "diagnostics-section-$title"
}

/**
 * The hidden Diagnostics page (E5.1): one card per section, rows as label and value, free lines (the event ring, the
 * last ring timeline) in a monospace log style, and Copy all in the top bar. Stateless.
 */
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUi,
    onCopyAll: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onBackLongClick: (() -> Unit)? = null,
) {
    SettingsPage(
        title = stringResource(R.string.diag_title),
        modifier = modifier.testTag(DiagnosticsTags.Page),
        onBack = onBack,
        onBackLongClick = onBackLongClick,
        actions = {
            GlassIconButton(
                icon = RovstiIcons.Copy,
                contentDescription = stringResource(R.string.diag_copy_all),
                onClick = onCopyAll,
                modifier = Modifier.testTag(DiagnosticsTags.CopyAll),
            )
        },
    ) {
        for (section in state.sections) {
            DiagnosticsSection(section)
        }
    }
}

@Composable
private fun DiagnosticsSection(section: DiagSection) {
    val colors = IosTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = Spacing.xl).testTag(DiagnosticsTags.section(section.title))) {
        IosText(
            text = section.title,
            style = IosTheme.typography.subheadline,
            color = colors.secondaryLabel,
            modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PhoneMetrics.CardRadius))
                .background(colors.secondarySystemGroupedBackground)
                .padding(vertical = Spacing.xs),
        ) {
            for (row in section.rows) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    IosText(row.label, Modifier.weight(1f))
                    IosText(
                        row.value,
                        Modifier.widthIn(max = PhoneMetrics.ValueMax),
                        color = colors.secondaryLabel,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            for (line in section.lines) {
                RovstiText(
                    line,
                    style = RovstiTheme.type.caption1.copy(fontFamily = FontFamily.Monospace),
                    color = RovstiTheme.colors.labelSecondary,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
                )
            }
            if (section.rows.isEmpty() && section.lines.isEmpty()) {
                IosText(
                    stringResource(R.string.diag_none),
                    Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                    color = colors.secondaryLabel,
                )
            }
        }
    }
}

/** [DiagnosticsScreen] over its view model: Copy all copies the report and a toast says so. */
@Composable
fun DiagnosticsPage(
    viewModel: DiagnosticsViewModel,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val overlays = LocalOverlay.current
    val copied = stringResource(R.string.diag_copied)
    val failed = stringResource(R.string.diag_copy_failed)
    val toast by rememberUpdatedState(overlays)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            if (effect is DiagnosticsEffect.Copied) toast.showToast(if (effect.copied) copied else failed)
        }
    }
    DiagnosticsScreen(
        state = state,
        onCopyAll = { viewModel.onAction(DiagnosticsAction.CopyAll) },
        onBack = onBack,
        onBackLongClick = onBackLongClick,
    )
}
