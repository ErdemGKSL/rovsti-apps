package dev.rosty.contacts.cards

import android.net.Uri
import dev.rosty.contacts.R
import dev.rosty.core.ops.CardPalette
import dev.rosty.core.ops.CardTemplate
import dev.rosty.design.text.UiText
import dev.rosty.platform.files.FakeFilesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext

/** X.2 step 5: the Card Studio model renders once per change, debounces fast taps, caches by spec and cancels on close. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CardStudioViewModelTest {
    private val ops = FakeCardsOps()
    private val files = FakeFilesRepository(staged = Uri.parse("content://x/card.png"))
    private val dir = Files.createTempDirectory("cards").toFile().apply { deleteOnExit() }

    private fun TestScope.model(subject: CardStudioSubject = StudioSamples.birthday) =
        CardStudioViewModel(
            subject,
            ops,
            files,
            dir,
            backgroundScope,
            textsFor = { who, template -> mapOf("title" to who.name, "template" to template.name) },
            renderContext = EmptyCoroutineContext,
        )

    private fun TestScope.settle() {
        advanceTimeBy(CardStudioViewModel.DEBOUNCE_MS * 2)
        runCurrent()
    }

    @Test fun opensOnBirthdayWithOneImmediateRender() =
        runTest {
            val m = model()
            runCurrent()
            assertEquals(1, ops.renders.size)
            assertEquals(
                CardTemplate.Birthday,
                ops.renders
                    .single()
                    .spec.template,
            )
            assertNotNull(m.state.value.preview)
            assertFalse(m.state.value.rendering)
        }

    @Test fun aContactWithoutABirthdayOpensOnContact() =
        runTest {
            val m = model(StudioSamples.plain)
            runCurrent()
            assertEquals(CardTemplate.Contact, m.state.value.template)
            assertEquals(1, ops.renders.size)
        }

    @Test fun theBirthdayTabOfAContactWithoutABirthdayStillRenders() =
        runTest {
            val m = model(StudioSamples.plain)
            runCurrent()
            m.onAction(CardStudioAction.PickTemplate(CardTemplate.Birthday))
            settle()
            val state = m.state.value
            assertEquals(CardTemplate.Birthday, state.template)
            assertFalse(state.failed)
            assertFalse(state.rendering)
            assertNotNull(state.preview)
            assertEquals(
                CardTemplate.Birthday,
                ops.renders
                    .last()
                    .spec.template,
            )
        }

    @Test fun eachSlowSwitchIsOneRender() =
        runTest {
            val m = model()
            runCurrent()
            m.onAction(CardStudioAction.PickPalette(CardPalette.Lagoon))
            advanceTimeBy(CardStudioViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            m.onAction(CardStudioAction.PickTemplate(CardTemplate.Contact))
            advanceTimeBy(CardStudioViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals(3, ops.renders.size)
            assertEquals(CardPalette.Lagoon, ops.renders[1].spec.palette)
            assertEquals(CardTemplate.Contact, ops.renders[2].spec.template)
        }

    @Test fun tapsCloserThanTheDebounceCostOneRender() =
        runTest {
            val m = model()
            runCurrent()
            m.onAction(CardStudioAction.PickPalette(CardPalette.Lagoon))
            advanceTimeBy(60)
            m.onAction(CardStudioAction.PickPalette(CardPalette.Meadow))
            advanceTimeBy(60)
            m.onAction(CardStudioAction.PickPalette(CardPalette.Dusk))
            assertTrue(m.state.value.rendering)
            advanceTimeBy(CardStudioViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals(2, ops.renders.size)
            assertEquals(
                CardPalette.Dusk,
                ops.renders
                    .last()
                    .spec.palette,
            )
        }

    @Test fun goingBackToAColourIsAnswerFromTheCache() =
        runTest {
            val m = model()
            runCurrent()
            m.onAction(CardStudioAction.PickPalette(CardPalette.Lagoon))
            settle()
            assertEquals(2, ops.renders.size)
            m.onAction(CardStudioAction.PickPalette(CardPalette.Dawn))
            assertFalse("a cached spec shows at once", m.state.value.rendering)
            settle()
            assertEquals(2, ops.renders.size)
        }

    @Test fun theOldPreviewStaysWhileTheNextRenderRuns() =
        runTest {
            val m = model()
            runCurrent()
            val first = m.state.value.preview
            ops.gate = CompletableDeferred()
            m.onAction(CardStudioAction.SetAnimated(true))
            advanceTimeBy(CardStudioViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertTrue(m.state.value.rendering)
            assertEquals(first, m.state.value.preview)
            ops.gate?.complete(Unit)
            runCurrent()
            assertTrue(
                m.state.value.preview
                    ?.animated == true,
            )
        }

    @Test fun closingCancelsTheRunningRender() =
        runTest {
            val m = model()
            runCurrent()
            ops.gate = CompletableDeferred()
            m.onAction(CardStudioAction.PickPalette(CardPalette.Ink))
            advanceTimeBy(CardStudioViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertEquals(2, ops.renders.size)
            m.close()
            runCurrent()
            assertEquals(listOf(ops.renders.last().out), ops.cancelled.toList())
            assertFalse(m.state.value.rendering)
            m.onAction(CardStudioAction.PickPalette(CardPalette.Dusk))
            settle()
            assertEquals("a closed sheet renders nothing more", 2, ops.renders.size)
        }

    @Test fun closingMidRenderShowsNoLateImage() =
        runTest {
            val m = model()
            runCurrent()
            val shown = m.state.value.preview
            ops.gate = CompletableDeferred()
            m.onAction(CardStudioAction.SetAnimated(true))
            advanceTimeBy(CardStudioViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertTrue(m.state.value.rendering)
            m.close()
            runCurrent()
            // The core answers anyway (a render in flight cannot be stopped from outside): nothing may reach the sheet.
            ops.gate?.complete(Unit)
            settle()
            assertEquals("the old picture stays, no late one", shown, m.state.value.preview)
            assertFalse(m.state.value.rendering)
            assertFalse(m.state.value.failed)
        }

    @Test fun aRotationKeepsTheJob() =
        runTest {
            val m = model()
            runCurrent()
            ops.gate = CompletableDeferred()
            m.onAction(CardStudioAction.SetAnimated(true))
            advanceTimeBy(CardStudioViewModel.DEBOUNCE_MS + 1)
            runCurrent()
            assertTrue(m.state.value.rendering)
            // A rotation drops every observer of the old activity and attaches new ones; the model (the app scope)
            // is not asked to close, so the render is neither cancelled nor started again.
            val before = ops.renders.size
            val old = launch { m.state.collect {} }
            old.cancel()
            runCurrent()
            val again = launch { m.state.collect {} }
            runCurrent()
            assertTrue(m.state.value.rendering)
            assertTrue("no cancel for a rotation", ops.cancelled.isEmpty())
            assertEquals("no second render", before, ops.renders.size)
            ops.gate?.complete(Unit)
            runCurrent()
            assertTrue(
                m.state.value.preview
                    ?.animated == true,
            )
            assertFalse(m.state.value.rendering)
            again.cancel()
        }

    @Test fun closingBeforeTheDebounceEndsRendersNothing() =
        runTest {
            val m = model()
            runCurrent()
            m.onAction(CardStudioAction.PickPalette(CardPalette.Ink))
            advanceTimeBy(50)
            m.close()
            settle()
            assertEquals(1, ops.renders.size)
            assertTrue(ops.cancelled.isEmpty())
        }

    @Test fun newNumberIsStaticOnly() =
        runTest {
            val m = model()
            runCurrent()
            m.onAction(CardStudioAction.SetAnimated(true))
            m.onAction(CardStudioAction.PickTemplate(CardTemplate.NewNumber))
            settle()
            assertFalse(m.state.value.canAnimate)
            assertFalse(
                ops.renders
                    .last()
                    .spec.animated,
            )
            m.onAction(CardStudioAction.SetAnimated(true))
            settle()
            assertFalse(
                ops.renders
                    .last()
                    .spec.animated,
            )
        }

    @Test fun shareStagesTheFileAndSaveWritesTheGallery() =
        runTest {
            val m = model()
            runCurrent()
            m.onAction(CardStudioAction.Share)
            runCurrent()
            val shared = m.effects.first()
            assertTrue(shared is CardStudioEffect.Share)
            assertEquals("Ayşe-Yılmaz-card.png", (shared as CardStudioEffect.Share).name)
            m.onAction(CardStudioAction.Save)
            runCurrent()
            assertEquals(1, files.gallery.size)
            assertEquals("image/png", files.gallery.single().third)
        }

    @Test fun anAnimatedCardIsSharedAsGifAndAsAStillOnRequest() =
        runTest {
            val m = model()
            runCurrent()
            m.onAction(CardStudioAction.SetAnimated(true))
            settle()

            m.onAction(CardStudioAction.Share)
            runCurrent()
            val gif = m.effects.first() as CardStudioEffect.Share
            assertEquals("image/gif", gif.mime)
            assertEquals("Ayşe-Yılmaz-card.gif", gif.name)
            assertEquals(false, files.stagedCards.last().second)

            m.onAction(CardStudioAction.ShareAsStill)
            runCurrent()
            val still = m.effects.first() as CardStudioEffect.Share
            assertEquals("image/jpeg", still.mime)
            assertEquals("Ayşe-Yılmaz-card.jpg", still.name)
            assertEquals(true, files.stagedCards.last().second)
        }

    @Test fun aFailedSaveToastsAndRejects() =
        runTest {
            files.galleryOk = false
            val m = model()
            runCurrent()
            m.onAction(CardStudioAction.Save)
            runCurrent()
            val first = m.effects.first()
            assertEquals(UiText.Res(R.string.contacts_cant_save_image), (first as CardStudioEffect.Toast).text)
            assertEquals(CardStudioEffect.Reject, m.effects.first())
        }

    @Test fun aSavedCardToastsWithoutTheRejectHaptic() =
        runTest {
            val m = model()
            runCurrent()
            m.onAction(CardStudioAction.Save)
            runCurrent()
            assertEquals(
                UiText.Res(R.string.contacts_saved_to_photos),
                (m.effects.first() as CardStudioEffect.Toast).text,
            )
            assertEquals(1, files.gallery.size)
        }

    @Test fun aCardThatCannotBeStagedToastsAndRejects() =
        runTest {
            files.staged = null
            val m = model()
            runCurrent()
            m.onAction(CardStudioAction.Share)
            runCurrent()
            assertEquals(
                UiText.Res(R.string.card_studio_cant_share),
                (m.effects.first() as CardStudioEffect.Toast).text,
            )
            assertEquals(CardStudioEffect.Reject, m.effects.first())
        }

    @Test fun aFailedRenderKeepsThePreviewAndSaysSo() =
        runTest {
            val m = model()
            runCurrent()
            ops.fail = true
            m.onAction(CardStudioAction.PickPalette(CardPalette.Ink))
            settle()
            assertTrue(m.state.value.failed)
            assertNotNull(m.state.value.preview)
        }

    /** The error reaches the Logs page by its code, with the text masked (a number in it never survives). */
    @Test fun aFailedRenderIsLoggedByCode() =
        runTest {
            dev.rosty.core.log.RostyLog.ring
                .clear()
            val m = model()
            runCurrent()
            ops.fail = true
            m.onAction(CardStudioAction.PickPalette(CardPalette.Ink))
            settle()
            val line =
                dev.rosty.core.log.RostyLog.ring
                    .snapshot()
                    .single { it.tag == "cards" }
            assertTrue(line.text, line.text.startsWith("render failed: template="))
            assertTrue(line.text, line.text.contains("out_unwritable"))
        }
}
