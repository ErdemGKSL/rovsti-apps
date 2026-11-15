package dev.rosty.contacts.cards

import dev.rosty.contacts.R
import dev.rosty.core.Outcome
import dev.rosty.core.ops.CardPalette
import dev.rosty.core.ops.CardRenderRequest
import dev.rosty.core.ops.CardSpec
import dev.rosty.core.ops.CardTemplate
import dev.rosty.core.ops.CardsOps
import dev.rosty.design.text.UiText
import dev.rosty.platform.files.FilesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/**
 * The model of the Card Studio sheet (X.2 step 5). Every change of template, colour or Still/Animated asks for one
 * render: the first runs at once, later ones wait [debounceMs] after the last change so a quick run of taps costs one
 * render. Renders run on [renderContext] (Dispatchers.Default) through [ops]; a spec seen before is answered from a small
 * in-memory cache keyed by the [CardSpec], so going back to a colour is instant. The old preview stays on screen while
 * a render runs ([CardStudioState.rendering]); closing the sheet or asking for another card cancels the running render
 * (the job, and `cards.cancel` for the core).
 */
class CardStudioViewModel(
    subject: CardStudioSubject,
    private val ops: CardsOps,
    private val files: FilesRepository,
    private val outDir: File,
    private val scope: CoroutineScope,
    private val textsFor: (CardStudioSubject, CardTemplate) -> Map<String, String>,
    private val renderContext: CoroutineContext = Dispatchers.Default,
    private val debounceMs: Long = DEBOUNCE_MS,
    initialTemplate: CardTemplate = subject.defaultTemplate,
) {
    private val mutable = MutableStateFlow(CardStudioState(subject, initialTemplate))
    val state: StateFlow<CardStudioState> = mutable.asStateFlow()

    private val outbox = Channel<CardStudioEffect>(Channel.BUFFERED)
    val effects: Flow<CardStudioEffect> = outbox.receiveAsFlow()

    private val cache =
        object : LinkedHashMap<CardSpec, CardPreview>(CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CardSpec, CardPreview>?) = size > CACHE_SIZE
        }
    private var job: Job? = null
    private var running: String? = null
    private var closed = false

    init {
        refresh(immediate = true)
    }

    fun onAction(action: CardStudioAction) {
        if (closed) return
        when (action) {
            is CardStudioAction.PickTemplate -> {
                if (action.template == mutable.value.template) return
                mutable.update { it.copy(template = action.template) }
                refresh()
            }

            is CardStudioAction.PickPalette -> {
                if (action.palette == mutable.value.palette) return
                mutable.update { it.copy(palette = action.palette) }
                refresh()
            }

            is CardStudioAction.SetAnimated -> {
                if (action.on == mutable.value.animated || !mutable.value.canAnimate) return
                mutable.update { it.copy(animated = action.on) }
                refresh()
            }

            CardStudioAction.Share -> {
                scope.launch { share() }
            }

            CardStudioAction.Save -> {
                scope.launch { save() }
            }

            CardStudioAction.Close -> {
                close()
            }
        }
    }

    /** Stops the running render and ignores every later action; the sheet calls it as it leaves. */
    fun close() {
        if (closed) return
        closed = true
        job?.cancel()
        job = null
        cancelRunning()
        mutable.update { it.copy(rendering = false) }
    }

    private fun refresh(immediate: Boolean = false) {
        val now = mutable.value
        val spec = now.toSpec(textsFor(now.subject, now.template))
        job?.cancel()
        cancelRunning()
        cache[spec]?.let { hit ->
            mutable.update { it.copy(preview = hit, rendering = false, failed = false) }
            return
        }
        mutable.update { it.copy(rendering = true, failed = false) }
        job =
            scope.launch {
                if (!immediate) delay(debounceMs)
                val out = File(outDir, fileName(spec)).path
                running = out
                val answer = withContext(renderContext) { ops.render(CardRenderRequest(spec, out)) }
                running = null
                when (answer) {
                    is Outcome.Ok -> {
                        val shown =
                            CardPreview(
                                answer.value.path,
                                answer.value.width,
                                answer.value.height,
                                answer.value.frames > 1,
                            )
                        cache[spec] = shown
                        mutable.update { it.copy(preview = shown, rendering = false, failed = false) }
                    }

                    is Outcome.Err -> {
                        mutable.update { it.copy(rendering = false, failed = true) }
                        outbox.trySend(CardStudioEffect.Toast(UiText.Res(R.string.card_studio_failed)))
                    }
                }
            }
    }

    private fun cancelRunning() {
        val out = running ?: return
        running = null
        scope.launch(NonCancellable + renderContext) { ops.cancel(out) }
    }

    private suspend fun share() {
        val shown = mutable.value.preview ?: return
        val name = shareName(shown)
        val uri = files.stageForShare(shown.path, name)
        outbox.trySend(
            if (uri != null) {
                CardStudioEffect.Share(uri, mimeOf(shown), name)
            } else {
                CardStudioEffect.Toast(UiText.Res(R.string.card_studio_cant_share))
            },
        )
    }

    private suspend fun save() {
        val shown = mutable.value.preview ?: return
        val saved = files.saveToGallery(shown.path, shareName(shown), mimeOf(shown))
        outbox.trySend(
            CardStudioEffect.Toast(
                UiText.Res(if (saved) R.string.contacts_saved_to_photos else R.string.contacts_cant_save_image),
            ),
        )
    }

    private fun shareName(shown: CardPreview): String {
        val base =
            mutable.value.subject.name
                .trim()
                .replace(
                    Regex("[^\\p{L}\\p{N}]+"),
                    "-",
                ).trim('-')
                .ifEmpty { "card" }
        return "$base-card.${extension(shown)}"
    }

    private fun mimeOf(shown: CardPreview) = if (shown.animated) "image/gif" else "image/png"

    private fun extension(shown: CardPreview) = if (shown.animated) "gif" else "png"

    private fun fileName(spec: CardSpec): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(spec.toString().toByteArray())
        val hash = digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
        return "card-$hash.${if (spec.animated) "gif" else "png"}"
    }

    companion object {
        /** Taps closer together than this cost one render. */
        const val DEBOUNCE_MS = 150L
        private const val CACHE_SIZE = 12
        private const val HASH_BYTES = 8
    }
}

/** The colours in the order the swatch row shows them. */
internal val StudioPalettes: List<CardPalette> = CardPalette.entries
