package dev.rosty.contacts.cards

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import dev.rosty.contacts.ContactsContainer
import dev.rosty.contacts.R
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.overlay.showToast
import dev.rosty.platform.files.CardShare
import java.io.File

/** Where finished cards are written; the share provider serves `cache/share`. */
internal fun cardsDir(context: Context): File = File(context.cacheDir, "share/cards").apply { mkdirs() }

/**
 * Opens Card Studio for [subject] from any entry point (the card page, the Upcoming rows): builds the model over the
 * container's cards and files, shows the sheet and starts the share chooser for a finished card.
 */
fun OverlayController.openCardStudio(
    context: Context,
    container: ContactsContainer,
    subject: CardStudioSubject,
): SheetEntry {
    val model =
        CardStudioViewModel(
            subject = subject,
            ops = container.cards,
            files = container.files,
            outDir = cardsDir(context),
            scope = container.scope,
            textsFor = { who, template -> cardTexts(context, who, template) },
        )
    val controller = this
    return presentCardStudio(context, model) { share ->
        val chooser = CardShare.shareIntent(share.uri, share.mime, share.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (_: ActivityNotFoundException) {
            controller.showToast(context.getString(R.string.contacts_no_app))
        }
    }
}
