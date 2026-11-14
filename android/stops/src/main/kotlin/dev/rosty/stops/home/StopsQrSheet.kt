package dev.rosty.stops.home

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rosty.core.Outcome
import dev.rosty.core.model.qr.QrEncodeRequest
import dev.rosty.core.model.qr.QrMatrix
import dev.rosty.core.ops.QrOps
import dev.rosty.design.controls.CapsuleButton
import dev.rosty.design.controls.CapsuleStyle
import dev.rosty.design.icons.RostyIcons
import dev.rosty.design.media.QrCode
import dev.rosty.design.media.QrModules
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.theme.RostyText
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Spacing
import dev.rosty.stops.R
import dev.rosty.stops.StopsSizes
import kotlinx.collections.immutable.persistentListOf

/** Test tags of the QR sheet. */
object QrTags {
    const val Sheet = "stops-qr-sheet"
    const val Code = "stops-qr-code"
    const val Share = "stops-qr-share"
}

/**
 * S3: the QR code of a place or a stop in a medium sheet (the Contacts QR sheet pattern: the white code card, the
 * title under it, one Share button that shares [payload] as text). The code comes from `qr.encode`; a failed encode
 * presents nothing and answers false.
 */
suspend fun OverlayController.presentStopsQrSheet(
    context: Context,
    qr: QrOps,
    title: String,
    payload: String,
    onShare: (String) -> Unit,
): Boolean {
    val answer = (qr.encode(QrEncodeRequest(payload)) as? Outcome.Ok)?.value ?: return false
    val matrix = QrMatrix.fromAnswer(answer)
    val modules = QrModules(matrix.side) { x, y -> matrix[x, y] }
    present(
        SheetEntry(title = context.getString(R.string.qr_title), detents = persistentListOf(Detent.Medium)) { _ ->
            QrContent(modules, title, payload, onShare)
        },
    )
    return true
}

@Composable
private fun QrContent(
    modules: QrModules,
    title: String,
    payload: String,
    onShare: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(QrTags.Sheet),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        QrCode(modules, Modifier.padding(top = Spacing.s).size(StopsSizes.qrCode).testTag(QrTags.Code))
        RostyText(
            title,
            Modifier.fillMaxWidth(),
            style = RostyTheme.type.title3,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RostyText(
            payload,
            Modifier.fillMaxWidth(),
            style = RostyTheme.type.footnote,
            color = RostyTheme.colors.labelSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        CapsuleButton(
            stringResource(R.string.qr_share),
            onClick = { onShare(payload) },
            modifier = Modifier.fillMaxWidth().testTag(QrTags.Share),
            icon = RostyIcons.Share,
            style = CapsuleStyle.Filled,
            fillWidth = true,
        )
    }
}
