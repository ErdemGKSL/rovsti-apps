package dev.rovsti.people

import android.util.Base64
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.wa.PictureAnswer
import dev.rovsti.core.model.wa.PictureBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** S12: the full-size WhatsApp picture the viewer swaps in, and every way it falls back to the cached one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaFullPicturesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val jid = "905321234567@s.whatsapp.net"
    private val asks = mutableListOf<PictureBody>()

    private fun pictures(answer: () -> Outcome<PictureAnswer>?): WaFullPictures =
        WaFullPictures(tmp.root, { body ->
            asks += body
            answer()
        }, io = Dispatchers.Unconfined, keep = 2)

    private fun image(text: String) =
        Outcome.Ok(
            PictureAnswer("image", id = "7", dataB64 = Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP)),
        )

    @Test fun theFullPictureIsAskedOnceAndKeptInTheCacheFolder() =
        runBlocking {
            val full = pictures { image("full") }
            val file = full.load(jid, "7")!!
            assertEquals("full", file.readText())
            assertEquals(listOf(PictureBody(jid, full = true)), asks)
            assertEquals("a second open asks nothing", file, full.load(jid, "7"))
            assertEquals(1, asks.size)
        }

    @Test fun aRateLimitKeepsTheCachedPicture() =
        runBlocking {
            val limited =
                pictures {
                    Outcome.Err(
                        RustError.Invalid("slow down", JsonObject(mapOf("code" to JsonPrimitive("rate_limited")))),
                    )
                }
            assertNull(limited.load(jid, "7"))
            assertTrue(
                "nothing is stored",
                tmp.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test fun notLinkedNoPictureOrAnErrorKeepTheCachedPicture() =
        runBlocking {
            assertNull(pictures { null }.load(jid, "7"))
            assertNull(pictures { Outcome.Ok(PictureAnswer("none")) }.load(jid, "7"))
            assertNull(pictures { Outcome.Err(RustError.Invalid("down")) }.load(jid, "7"))
        }

    @Test fun onlyTheNewestFewAreKept() =
        runBlocking {
            val full = pictures { image("x") }
            for (id in listOf("1", "2", "3")) {
                full.load(jid, id)!!.setLastModified(id.toLong() * 1_000_000)
            }
            full.load(jid, "4")
            assertEquals(
                2,
                tmp.root
                    .listFiles()
                    .orEmpty()
                    .size,
            )
        }

    @Test fun aFileNameNeverLeavesTheFolder() {
        val name = WaFullPictures.name("../../etc@x", "../p")
        assertTrue(name, '/' !in name)
    }
}
