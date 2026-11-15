package dev.rosty.contacts.cards

import dev.rosty.contacts.TestContactsApp
import dev.rosty.core.Outcome
import dev.rosty.core.ops.CardPalette
import dev.rosty.core.ops.CardRenderRequest
import dev.rosty.core.ops.CardRenderResult
import dev.rosty.core.ops.CardSpec
import dev.rosty.core.ops.CardTemplate
import dev.rosty.core.ops.NativeCardsOps
import dev.rosty.core.ops.UnavailableCardsOps
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * X.2 root cause guard: Card Studio failed on every device because the production container answered with
 * [UnavailableCardsOps]. The real container must hand out the native renderer, and no production source may name the
 * stand-in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestContactsApp::class)
class CardsWiringGuardTest {
    private val container get() = (RuntimeEnvironment.getApplication() as TestContactsApp).container

    @Test
    fun theContainerRendersThroughTheRustOp() {
        val c = container
        assertNotSame(UnavailableCardsOps, c.cards)
        assertTrue("cards is ${c.cards}", c.cards is NativeCardsOps)
        c.fakeRust.onTyped("cards.render", CardRenderRequest.serializer(), CardRenderResult.serializer()) {
            Outcome.Ok(CardRenderResult(it.out, 720, 900, 1, 1))
        }
        val out = File(RuntimeEnvironment.getApplication().cacheDir, "share/cards/x.png").path
        val spec =
            CardSpec(
                CardTemplate.Contact,
                CardPalette.Ink,
                texts = mapOf("title" to "+90 555 000 12 34"),
                photo = "/data/photo.jpg",
            )
        val answer = runBlocking { c.cards.render(CardRenderRequest(spec, out)) }
        assertTrue(answer is Outcome.Ok)
        val call = c.fakeRust.calls.single { it.op == "cards.render" }
        assertEquals(
            "/data/photo.jpg",
            call.request.jsonObject["spec"]!!
                .jsonObject["photo"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun noProductionSourceUsesTheStandIn() {
        val android = File("..").canonicalFile
        val sources =
            android
                .listFiles()
                .orEmpty()
                .map { File(it, "src/main") }
                .filter { it.isDirectory }
                .flatMap { dir -> dir.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertTrue("run from the module directory: ${android.absolutePath}", sources.size > 100)
        val offenders =
            sources
                .filter { it.name != "CardsOps.kt" }
                .filter { "UnavailableCardsOps" in it.readText() }
                .map { it.path }
        assertEquals("production code must use NativeCardsOps", emptyList<String>(), offenders)
    }
}
