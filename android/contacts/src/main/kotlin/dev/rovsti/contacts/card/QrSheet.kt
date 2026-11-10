package dev.rovsti.contacts.card

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import dev.rovsti.contacts.R
import dev.rovsti.design.controls.CapsuleButton
import dev.rovsti.design.controls.CapsuleStyle
import dev.rovsti.design.icons.RovstiIcons
import dev.rovsti.design.media.QrCode
import dev.rovsti.design.overlay.Detent
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.overlay.SheetEntry
import dev.rovsti.design.overlay.showToast
import dev.rovsti.design.text.UiText
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.theme.RovstiTheme
import dev.rovsti.design.theme.Spacing
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch

/**
 * The QR code of a contact in a medium sheet (E4.9, C4): the white code card, the name under it and two buttons, Save
 * Image (gallery + the "Saved to Photos" toast) and Share Image (the share sheet with the staged PNG). The sheet moves
 * with `sheetSettle` (0.86 / 380) and closes by drag, scrim tap or back like every sheet.
 */
fun OverlayController.presentQrSheet(
    context: Context,
    model: QrSheetModel,
    onShareImage: (Uri) -> Unit,
): SheetEntry {
    val controller = this
    val entry =
        SheetEntry(
            title = context.getString(R.string.contacts_qr_code),
            detents = persistentListOf(Detent.Medium),
        ) { _ ->
            QrSheetContent(model, toast = { controller.showToast(it) }, onShareImage = onShareImage)
        }
    present(entry)
    return entry
}

@Composable
internal fun ColumnScope.QrSheetContent(
    model: QrSheetModel,
    toast: (String) -> Unit,
    onShareImage: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val strContactsScanToAdd = stringResource(R.string.contacts_scan_to_add)
    val strContactsSaveImage = stringResource(R.string.contacts_save_image)
    val strContactsSavedToPhotos = resources.getString(R.string.contacts_saved_to_photos)
    val strContactsShareImage = stringResource(R.string.contacts_share_image)
    val strContactsCantShareImage = stringResource(R.string.contacts_cant_share_image)
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().padding(horizontal = Spacing.gutter).testTag(QrSheetTags.Sheet),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        QrCode(
            model.modules,
            Modifier.padding(top = QrSheetMetrics.TopGap).size(QrSheetMetrics.Code).testTag(QrSheetTags.Code),
        )
        RovstiText(
            model.name,
            Modifier.fillMaxWidth(),
            style = RovstiTheme.type.title3,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RovstiText(
            strContactsScanToAdd,
            style = RovstiTheme.type.footnote,
            color = RovstiTheme.colors.labelSecondary,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            CapsuleButton(
                strContactsSaveImage,
                onClick = {
                    scope.launch {
                        toast(
                            if (model.save()) {
                                strContactsSavedToPhotos
                            } else {
                                resources.getString(R.string.contacts_cant_save_image)
                            },
                        )
                    }
                },
                modifier = Modifier.weight(1f).testTag(QrSheetTags.Save),
                icon = RovstiIcons.Download,
                fillWidth = true,
            )
            CapsuleButton(
                strContactsShareImage,
                onClick = {
                    scope.launch {
                        val uri = model.stageForShare()
                        if (uri !=
                            null
                        ) {
                            onShareImage(uri)
                        } else {
                            toast(strContactsCantShareImage)
                        }
                    }
                },
                modifier = Modifier.weight(1f).testTag(QrSheetTags.Share),
                icon = RovstiIcons.Share,
                style = CapsuleStyle.Filled,
                fillWidth = true,
            )
        }
    }
}
