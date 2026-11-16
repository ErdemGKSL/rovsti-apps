package dev.rosty.core.rust

import dev.rosty.core.Outcome
import dev.rosty.core.RustDispatcher
import dev.rosty.core.RustError
import dev.rosty.core.call
import dev.rosty.core.ops.CardCancelAnswer
import dev.rosty.core.ops.CardCancelRequest
import dev.rosty.core.ops.CardPalette
import dev.rosty.core.ops.CardRenderRequest
import dev.rosty.core.ops.CardRenderResult
import dev.rosty.core.ops.CardSpec
import dev.rosty.core.ops.CardTemplate
import dev.rosty.core.ops.NativeCardsOps
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * X.2 without fakes: `cards.*` through `NativeCardsOps`, the JNI entry and the real host `librosty_ffi` (the same op
 * the release APK calls). Renders a Birthday and a Contact card and reads the PNGs back.
 */
class CardsOpsNativeTest {
    private val dir: File = Files.createTempDirectory("rosty-cards").toFile().apply { deleteOnExit() }
    private val asked = mutableListOf<String>()
    private val ops =
        NativeCardsOps(TestRust.core) { uri ->
            asked += uri
            null
        }

    @Before
    fun load() = TestRust.ensure()

    private fun render(
        spec: CardSpec,
        name: String,
    ): CardRenderResult =
        runBlocking {
            when (val answer = ops.render(CardRenderRequest(spec, File(dir, name).path))) {
                is Outcome.Ok -> answer.value
                is Outcome.Err -> error("$name failed: ${answer.error}")
            }
        }

    private fun assertPng(
        result: CardRenderResult,
        width: Int,
        height: Int,
    ) {
        val file = File(result.path)
        assertEquals(file.length(), result.bytes)
        val image = ImageIO.read(file) ?: error("${result.path} is not a picture")
        assertEquals(width to height, image.width to image.height)
        assertEquals(width to height, result.width to result.height)
        assertEquals(1, result.frames)
        assertEquals("image/png", result.mime)
    }

    @Test
    fun aBirthdayCardRendersToAPngOfTheRightSize() {
        val spec =
            CardSpec(
                template = CardTemplate.Birthday,
                palette = CardPalette.Dawn,
                texts =
                    mapOf(
                        "title" to "Doğum günün kutlu olsun, Ayşe Yılmaz Karadenizoğlu Çağlayangil!",
                        "age_line" to "36 yaşına giriyor",
                        "date_line" to "3 Ekim",
                    ),
                initials = "AY",
            )
        assertPng(render(spec, "birthday.png"), 720, 900)
    }

    /** The owner's device report: the contact's name is its number and it has no birthday. */
    @Test
    fun aContactNamedWithItsNumberAndNoBirthdayRendersEveryTemplate() {
        val number = "+90 532 000 11 22"
        val contact =
            CardSpec(
                template = CardTemplate.Contact,
                palette = CardPalette.Ink,
                texts = mapOf("name" to number, "title" to number, "numbers" to number),
                photo = "content://com.android.contacts/contacts/7/photo",
                initials = "+9",
                qr = "BEGIN:VCARD\nVERSION:3.0\nTEL:$number\nEND:VCARD\n",
            )
        assertPng(render(contact, "contact.png"), 720, 900)
        assertEquals(listOf("content://com.android.contacts/contacts/7/photo"), asked)
        val birthday =
            contact.copy(
                template = CardTemplate.Birthday,
                texts = mapOf("title" to "Doğum günün kutlu olsun, $number!"),
                qr = null,
            )
        assertPng(render(birthday, "birthday-number.png"), 720, 900)
        val newNumber =
            contact.copy(
                template = CardTemplate.NewNumber,
                texts = mapOf("title" to number, "subtitle" to "Yeni numaram", "numbers" to number),
                qr = null,
            )
        assertPng(render(newNumber, "new-number.png"), 720, 900)
    }

    @Test
    fun anAnimatedCardIsAOneShotGif() {
        val spec =
            CardSpec(
                template = CardTemplate.Contact,
                palette = CardPalette.Lagoon,
                animated = true,
                texts = mapOf("title" to "Ayşe Yılmaz", "numbers" to "+90 532 000 11 22"),
                initials = "AY",
                qr = "BEGIN:VCARD\nFN:Ayşe Yılmaz\nEND:VCARD\n",
            )
        val result = render(spec, "contact.gif")
        assertTrue(result.frames > 1)
        assertEquals("image/gif", result.mime)
        val bytes = File(result.path).readBytes()
        assertEquals("GIF89a", String(bytes, 0, 6, Charsets.US_ASCII))
        assertFalse("plays once", String(bytes, Charsets.ISO_8859_1).contains("NETSCAPE2.0"))
        val image = ImageIO.read(File(result.path))
        assertEquals(540 to 675, image.width to image.height)
    }

    @Test
    fun errorsAreCodes() =
        runBlocking {
            val missing =
                ops.render(
                    CardRenderRequest(
                        CardSpec(CardTemplate.Birthday, CardPalette.Dawn, texts = mapOf("title" to " ")),
                        File(dir, "never.png").path,
                    ),
                ) as Outcome.Err
            assertTrue(missing.error is RustError.Invalid)
            assertEquals("text_missing", missing.error.message)
            assertFalse(File(dir, "never.png").exists())
            ops.cancel(File(dir, "not-running.png").path)
            val templates = (ops.templates() as Outcome.Ok).value
            assertEquals(CardTemplate.entries.toList(), templates.templates.map { it.id })
            assertEquals(CardPalette.entries.toList(), templates.palettes)
        }

    /** A render on its own thread, polled with `cards.cancel` (it answers whether the render is running) and stopped. */
    @Test
    fun anAnimatedRenderIsSeenRunningAndCancelled() =
        runBlocking {
            val spec =
                CardSpec(
                    template = CardTemplate.Contact,
                    palette = CardPalette.Dusk,
                    animated = true,
                    texts = mapOf("title" to "Ayşe Yılmaz", "numbers" to "+90 532 000 11 22"),
                    initials = "AY",
                )
            val out = File(dir, "cancelled.gif").path
            val render = async(RustDispatcher) { ops.render(CardRenderRequest(spec, out)) }
            var stopped = false
            repeat(400) {
                if (!stopped && !render.isCompleted) {
                    val answer =
                        withContext(RustDispatcher) {
                            TestRust.core.call(
                                "cards.cancel",
                                CardCancelRequest(out),
                                CardCancelRequest.serializer(),
                                CardCancelAnswer.serializer(),
                            )
                        }
                    stopped = (answer as Outcome.Ok).value.cancelled
                    if (!stopped) delay(5)
                }
            }
            val result = render.await()
            if (stopped) {
                assertEquals("cancelled", (result as Outcome.Err).error.message)
                assertFalse(File(out).exists())
            } else {
                // The render beat every poll; it must then have finished whole.
                assertPng(result.let { (it as Outcome.Ok).value }.copy(frames = 1), 540, 675)
            }
        }
}
