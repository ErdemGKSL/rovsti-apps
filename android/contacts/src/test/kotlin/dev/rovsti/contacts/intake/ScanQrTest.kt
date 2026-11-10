package dev.rovsti.contacts.intake

import dev.rovsti.contacts.R
import dev.rovsti.contacts.card.SampleCards
import dev.rovsti.contacts.en
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.qr.QrEncodeRequest
import dev.rovsti.core.model.qr.QrMatrix
import dev.rovsti.core.ops.QrOps
import dev.rovsti.design.text.UiText
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The luminance of a generated QR code for [text]: what the Photo Picker's picture is after the decode step. */
internal suspend fun qrLuma(
    qr: QrOps,
    text: String,
    pixelsPerModule: Int = 4,
): Luma {
    val answer = (qr.encode(QrEncodeRequest(text)) as Outcome.Ok).value
    val matrix = QrMatrix.fromAnswer(answer)
    val dim = (matrix.side + 2 * 4) * pixelsPerModule
    return Luma(dim, dim, matrix.toLuma(pixelsPerModule, 4))
}

/** E4.10 C19 on the REAL Rust core: a picture of a QR code becomes a preview, the editor or the alert. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScanQrTest {
    private var rig: IntakeRig? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun scan(
        payload: String?,
        blank: Boolean = false,
    ): IntakeRig =
        IntakeRig(
            IntakeRig.scan(),
            makePicture = { qr ->
                when {
                    blank -> Luma(64, 64, ByteArray(64 * 64) { 0xFF.toByte() })
                    else -> qrLuma(qr, payload.orEmpty())
                }
            },
        ).also { rig = it }

    @Test fun aVcardCodeOpensThePreviewOfItsCard() {
        val r = scan(vcard("Ada Lovelace", "Lovelace", "Ada", "+44 20 7946 0958"))
        val state = r.preview()
        assertEquals(listOf("Ada Lovelace"), state.rows.map { it.name })
        assertEquals("+44 20 7946 0958", state.rows.single().secondary)
        assertEquals("1 new contact", (state.countLine).en())
        assertTrue("a single card can go into a contact that exists", state.canAddToExisting)
        assertEquals("the picture is read once", listOf("content://media/picker/qr.png"), r.picked)
        r.model.onAction(IntakeAction.Add)
        assertTrue(r.effect() is IntakeEffect.Undo)
        assertEquals(SampleCards.all.size + 1, r.contacts().size)
    }

    @Test fun aCodeThatIsNoContactIsTheAlert() {
        val r = scan("https://example.org/not-a-contact")
        val alert = r.effect() as IntakeEffect.Alert
        assertEquals("No contact in this code", (alert.title).en())
        assertEquals("This QR code holds no contact.", (alert.message).en())
        assertEquals(IntakeEffect.Close, r.effect())
        assertEquals("nothing was added", SampleCards.all.size, r.contacts().size)
    }

    @Test fun aPictureWithoutAnyCodeIsTheSameAlertWithItsOwnWords() {
        val r = scan(payload = null, blank = true)
        val alert = r.effect() as IntakeEffect.Alert
        assertEquals("No contact in this code", (alert.title).en())
        assertEquals("No QR code was found in this picture.", (alert.message).en())
    }

    @Test fun aTelCodeOpensTheEditorWithTheNumber() {
        val r = scan("tel:+905551112233")
        val editor = r.effect() as IntakeEffect.OpenEditor
        assertEquals("+905551112233", editor.seed.number.filter { it.isDigit() || it == '+' })
        assertEquals(IntakeEffect.Close, r.effect())
    }

    @Test fun aMecardOpensTheEditorWithTheNameNumberAndAddress() {
        val r = scan("MECARD:N:Lovelace,Ada;TEL:+905551112233;EMAIL:ada@example.org;;")
        val editor = r.effect() as IntakeEffect.OpenEditor
        assertEquals("Ada Lovelace", editor.seed.name)
        assertEquals("+905551112233", editor.seed.number.filter { it.isDigit() || it == '+' })
        assertEquals("ada@example.org", editor.seed.email)
    }

    @Test fun anUnreadablePictureToasts() {
        val r = IntakeRig(IntakeRig.scan()).also { rig = it }
        assertEquals(IntakeEffect.Toast(UiText.Res(R.string.contacts_cant_read_picture)), r.effect())
        assertEquals(IntakeEffect.Close, r.effect())
    }

    @Test fun grayValuesWeighTheChannelsAndReadTransparencyAsWhite() {
        val gray =
            AndroidLumaReader.lumaOf(
                intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFF0000.toInt(), 0x00000000),
            )
        assertArrayEquals(byteArrayOf(0, -1, 76, -1), gray)
    }

    @Test fun aBigPictureIsSampledDownToTheDecodersLimit() {
        assertEquals(1, AndroidLumaReader.sampleFor(1600, 900))
        assertEquals(2, AndroidLumaReader.sampleFor(3000, 2000))
        assertEquals(4, AndroidLumaReader.sampleFor(4000, 6000))
    }
}
