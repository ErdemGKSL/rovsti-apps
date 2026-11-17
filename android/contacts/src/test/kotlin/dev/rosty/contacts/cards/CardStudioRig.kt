package dev.rosty.contacts.cards

import androidx.compose.runtime.rememberCoroutineScope
import dev.rosty.contacts.card.CardHarness
import dev.rosty.contacts.card.ContactCardUiState
import dev.rosty.contacts.card.HostedCard
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.platform.files.FakeFilesRepository
import dev.rosty.testing.ShotTest
import kotlinx.coroutines.CoroutineScope
import org.robolectric.RuntimeEnvironment
import java.nio.file.Files
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Card Studio on the real shell: the sheet opens over a card page whose model is bound to the composition's own
 * coroutine scope, so `delay` follows the manual Compose clock (autoAdvance is off in [ShotTest]).
 */
internal class CardStudioRig(
    private val test: ShotTest,
    val ops: FakeCardsOps = FakeCardsOps(),
    val subject: CardStudioSubject = StudioSamples.birthday,
    val ui: ContactCardUiState = ContactCardUiState(),
    val images: dev.rosty.design.media.AnimatedImageSource? = null,
    val reduceMotion: Boolean = false,
) {
    /** The haptic events the sheet played. */
    val haptics = mutableListOf<dev.rosty.design.haptics.HapticEvent>()

    val harness = CardHarness(ui)
    val files = FakeFilesRepository()
    private val dir = Files.createTempDirectory("studio").toFile().apply { deleteOnExit() }
    private var scope: CoroutineScope? = null
    lateinit var model: CardStudioViewModel
    var entry: SheetEntry? = null

    fun show(
        dark: Boolean = false,
        fontScale: Float = 1f,
        content: (@androidx.compose.runtime.Composable () -> Unit)? = null,
    ) {
        test.composeFrame(dark = dark, fontScale = fontScale) {
            scope = rememberCoroutineScope()
            androidx.compose.runtime.CompositionLocalProvider(
                dev.rosty.design.motion.LocalReduceMotion provides reduceMotion,
                dev.rosty.design.haptics.LocalRostyHaptics provides
                    object : dev.rosty.design.haptics.RostyHaptics {
                        override fun play(event: dev.rosty.design.haptics.HapticEvent) {
                            haptics += event
                        }
                    },
                dev.rosty.design.media.LocalAnimatedImageSource provides
                    (images ?: dev.rosty.design.media.LocalAnimatedImageSource.current),
            ) {
                content?.invoke() ?: HostedCard(harness)
            }
        }
        test.advance(600)
    }

    fun open() {
        val context = RuntimeEnvironment.getApplication()
        model =
            CardStudioViewModel(
                subject,
                ops,
                files,
                dir,
                scope!!,
                textsFor = { who, template -> cardTexts(context, who, template) },
                renderContext = EmptyCoroutineContext,
            )
        test.compose.runOnUiThread { entry = harness.overlays.presentCardStudio(context, model) }
        settle()
    }

    /** Lets the sheet spring in and the decoded picture arrive from its IO thread. */
    fun settle(rounds: Int = 6) {
        repeat(rounds) {
            Thread.sleep(40)
            test.advance(200)
        }
    }
}
