package dev.rovsti.contacts.card

import android.net.Uri
import dev.rovsti.contacts.R
import dev.rovsti.design.text.UiText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** E4.9 on the REAL Rust core: what each entry of the card's "..." menu asks of the system. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ContactShareTest {
    private var rig: CardRig? = null

    @After fun stop() {
        rig
            ?.scope
            ?.coroutineContext
            ?.get(kotlinx.coroutines.Job)
            ?.cancel()
    }

    private fun ready(id: Long = 1): CardRig =
        CardRig(id).also {
            rig = it
            it.ready()
        }

    @Test fun shareAsTextCarriesTheCardText() {
        val r = ready()
        r.model.onAction(ContactCardAction.ShareText)
        val effect = r.effect() as ContactCardEffect.Share
        assertTrue("name in the text: ${effect.text}", effect.text.contains("Ayşe Yılmaz"))
        assertTrue("numbers in the text: ${effect.text}", effect.text.contains("+90 532 111 22 33"))
    }

    @Test fun shareAsFileStagesTheVcardUnderTheContactsName() {
        val r = ready()
        r.files.staged = Uri.parse("content://dev.rovsti.contacts.rovstishare/share/Ay.vcf")
        r.model.onAction(ContactCardAction.ShareFile)
        val effect = r.effect() as ContactCardEffect.ShareFile
        assertEquals(r.files.staged, effect.uri)
        assertTrue("named after the person: ${effect.fileName}", effect.fileName.endsWith(".vcf"))
        val (path, name) = r.files.stagedPaths.single()
        assertEquals(effect.fileName, name)
        val body = File(path).readText()
        assertTrue(body.startsWith("BEGIN:VCARD"))
        assertTrue("the vCard has the name: $body", body.contains("Yılmaz"))
        assertTrue("and the default number: $body", body.contains("+90 532 111 22 33"))
    }

    @Test fun aFailedStageToastsInsteadOfSharing() {
        val r = ready()
        r.files.staged = null
        r.model.onAction(ContactCardAction.ShareFile)
        assertEquals(ContactCardEffect.Toast(UiText.Res(R.string.contacts_cant_share_file)), r.effect())
    }

    @Test fun exportAsksWhereToSaveTheSameVcard() {
        val r = ready()
        r.model.onAction(ContactCardAction.ExportVcf)
        val effect = r.effect() as ContactCardEffect.ExportVcf
        assertTrue(effect.fileName.endsWith(".vcf"))
        assertTrue(effect.text.contains("BEGIN:VCARD") && effect.text.contains("Yılmaz"))
    }

    @Test fun copyContactAndCopyAsVcardUseTheClipboardAndToast() {
        val r = ready()
        r.model.onAction(ContactCardAction.CopyContact)
        assertEquals(ContactCardEffect.Toast(UiText.Res(R.string.contacts_contact_copied)), r.effect())
        assertTrue(r.clipboard.text!!.contains("Ayşe Yılmaz"))
        r.model.onAction(ContactCardAction.CopyVcard)
        assertEquals(ContactCardEffect.Toast(UiText.Res(R.string.contacts_vcard_copied)), r.effect())
        assertTrue(r.clipboard.text!!.startsWith("BEGIN:VCARD"))
        assertEquals(2, r.clipboard.copies.size)
    }

    @Test fun addToHomePinsWithThePinShortcutAnswer() {
        val r = ready()
        r.model.onAction(ContactCardAction.AddToHome)
        assertEquals(ContactCardEffect.Toast(UiText.Res(R.string.contacts_added_to_home_screen)), r.effect())
        val spec = r.pins.single()
        assertEquals(1L, spec.contactId)
        assertTrue("label has the name: ${spec.shortLabel}", spec.shortLabel.contains("Ayşe"))
    }

    @Test fun showQrOpensTheSheetWithTheContactsCode() {
        val r = ready()
        r.model.onAction(ContactCardAction.ShowQr)
        val sheet = (r.effect() as ContactCardEffect.ShowQr).model
        assertEquals("Ayşe Yılmaz", sheet.name)
        assertTrue("a real matrix: ${sheet.matrix.side}", sheet.matrix.side >= 21 && sheet.matrix.darkCount > 100)
        assertTrue("png name: ${sheet.fileName}", sheet.fileName.endsWith(".png"))
    }
}
