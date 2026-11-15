package dev.rosty.wa.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Envelope
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.qr.QrEncodeRequest
import dev.rosty.core.model.qr.QrMatrix
import dev.rosty.core.model.wa.AppId
import dev.rosty.core.model.wa.CreateInstance
import dev.rosty.core.model.wa.CreatedInstance
import dev.rosty.core.model.wa.GatewayEvent
import dev.rosty.core.model.wa.InstanceStatus
import dev.rosty.core.model.wa.IssuedKey
import dev.rosty.core.model.wa.KeyInfo
import dev.rosty.core.model.wa.LinkRequest
import dev.rosty.core.model.wa.SettingsApplyAnswer
import dev.rosty.core.model.wa.SettingsApplyRequest
import dev.rosty.core.model.wa.SettingsEffect
import dev.rosty.core.model.wa.SettingsInput
import dev.rosty.core.model.wa.SettingsLink
import dev.rosty.core.model.wa.SettingsView
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.core.ops.QrOps
import dev.rosty.core.ops.WaGateway
import dev.rosty.core.ops.WaOps
import dev.rosty.core.ops.WaSettingsOps
import dev.rosty.design.text.UiText
import dev.rosty.platform.files.ClipboardRepository
import dev.rosty.platform.files.FilesRepository
import dev.rosty.wa.R
import dev.rosty.wa.WaLinkStore
import dev.rosty.wa.WaRepository
import dev.rosty.wa.share.WaLinkHandover
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * The WhatsApp settings screen's logic (E3.10). The state machine is Rust's (`wa.settings_apply`, stateless): this class
 * keeps its `model` (as JSON text in the [SavedStateHandle], so a recreated process lands on the same page), sends it
 * one input at a time and draws the answer's `view`. It decides nothing about pages.
 *
 * The `effects` of an answer run in order and each result goes back as the matching input (`created`, `status_ok`,
 * `unauthorized`, `error`, `show_key`, `keys_loaded`, `revoked`, `deleted`); gateway events of the app's own instance
 * become `event` inputs. Everything, user presses, effect results and events, passes through one queue and one consumer,
 * so the machine never sees two inputs at once. `CopyToClipboard` copies with `sensitive = true` (the text is a connect
 * key) and reports a "Copied" toast.
 */
class WhatsAppSettingsViewModel(
    private val app: AppId,
    private val machine: WaSettingsOps,
    private val opener: WaUrlOpener,
    private val store: WaLinkStore,
    private val repo: WaRepository,
    private val qr: QrOps,
    private val clipboard: ClipboardRepository,
    private val files: FilesRepository,
    private val stagingDir: File,
    private val clock: () -> Long,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The link hand-over between Rosty apps; null for a host that does not take part (the catalog, tests). */
    private val handover: WaLinkHandover? = null,
) : ViewModel() {
    constructor(
        app: AppId,
        machine: WaSettingsOps,
        ops: WaOps,
        store: WaLinkStore,
        repo: WaRepository,
        qr: QrOps,
        clipboard: ClipboardRepository,
        files: FilesRepository,
        stagingDir: File,
        clock: () -> Long,
        saved: SavedStateHandle,
        handover: WaLinkHandover? = null,
    ) : this(
        app = app,
        machine = machine,
        opener = WaUrlOpener { url, key -> WaGateway.open(url, key, null, ops) },
        store = store,
        repo = repo,
        qr = qr,
        clipboard = clipboard,
        files = files,
        stagingDir = stagingDir,
        clock = clock,
        saved = saved,
        handover = handover,
    )

    private sealed interface Work {
        data object Start : Work

        data class Send(
            val input: SettingsInput,
        ) : Work

        data object UseSibling : Work
    }

    private val inbox = Channel<Work>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(WaSettingsUiState(view = LOADING, page = LOADING))
    private val mutableEvents = MutableSharedFlow<WaSettingsEvent>(extraBufferCapacity = EVENT_BUFFER)
    private var model: JsonElement? = null
    private var qrPayload: String? = null
    private var pasteCounter = 0

    /** The page to draw. */
    val state: StateFlow<WaSettingsUiState> = mutableState.asStateFlow()

    /** Toasts and share sheets for the host to show. */
    val events: SharedFlow<WaSettingsEvent> = mutableEvents.asSharedFlow()

    init {
        viewModelScope.launch {
            for (work in inbox) {
                when (work) {
                    Work.Start -> start()
                    is Work.Send -> step(work.input)
                    Work.UseSibling -> useSibling()
                }
            }
        }
        inbox.trySend(Work.Start)
        viewModelScope.launch {
            repo.events.filter { it.concernsLinking() }.collect {
                inbox.trySend(
                    Work.Send(
                        SettingsInput.json("event", Envelope.json.encodeToJsonElement(GatewayEvent.serializer(), it)),
                    ),
                )
            }
        }
    }

    fun onAction(action: WaSettingsAction) {
        when (action) {
            is WaSettingsAction.Press -> {
                inbox.trySend(Work.Send(action.input))
            }

            WaSettingsAction.ShareQr -> {
                viewModelScope.launch { shareQr() }
            }

            WaSettingsAction.PasteClipboard -> {
                paste()
            }

            is WaSettingsAction.PasteDone -> {
                mutableState.value =
                    mutableState.value.let { if (it.paste?.id == action.id) it.copy(paste = null) else it }
            }

            WaSettingsAction.ShareKey -> {
                shareKey()
            }

            WaSettingsAction.ToggleKeyQr -> {
                viewModelScope.launch { toggleKeyQr() }
            }

            WaSettingsAction.UseSiblingLink -> {
                inbox.trySend(Work.UseSibling)
            }
        }
    }

    // ---- the queue

    private suspend fun start() {
        val restored =
            saved
                .get<String>(
                    MODEL_KEY,
                )?.let { runCatching { Envelope.json.parseToJsonElement(it) }.getOrNull() }
        if (restored != null) {
            val view = apply(SettingsApplyRequest(model = restored, app = app, nowMs = clock()))
            // A page that was still working lost its effects with the process: start over instead of waiting for them.
            if (view != null && !view.view.busy) {
                accept(view, runEffects = false)
                refreshShare()
                return
            }
        }
        // The link screen opens without a link: a sibling Rosty app may have one (WA-R2). Adopting it stores it and
        // connects, so the read below finds it as if the key had been pasted.
        handover?.adoptIfUnlinked()
        val stored = store.read()
        val link = (stored as? Outcome.Ok)?.value?.let { SettingsLink(it.gatewayUrl, it.instanceId, it.key) }
        apply(SettingsApplyRequest(link = link, app = app, nowMs = clock()))?.let { accept(it, runEffects = true) }
        refreshShare()
    }

    /** "Use the link of ...": the user takes back the link a sibling holds, and the page starts over with it. */
    private suspend fun useSibling() {
        val share = handover ?: return
        if (share.adoptIfUnlinked(explicit = true) !is WaLinkHandover.Result.Adopted) {
            mutableEvents.tryEmit(WaSettingsEvent.Toast(UiText.Res(R.string.wa_error_generic)))
            refreshShare()
            return
        }
        saved.remove<String>(MODEL_KEY)
        model = null
        start()
    }

    /**
     * Sets the two hand-over notes of the state: where the link in use came from and, while this app has no link,
     * the sibling that has one to offer (the link that was removed here; any other would have been adopted).
     */
    private suspend fun refreshShare() {
        val share = handover ?: return
        val from = share.adoptedFrom()
        val linked = (store.read() as? Outcome.Ok)?.value != null
        val offer = if (linked) null else share.offer()
        mutableState.value = mutableState.value.copy(sharedFrom = from, siblingOffer = offer)
    }

    private suspend fun step(input: SettingsInput) {
        apply(SettingsApplyRequest(model = model, app = app, nowMs = clock(), input = input))?.let {
            accept(it, runEffects = true)
        }
    }

    private suspend fun apply(request: SettingsApplyRequest): SettingsApplyAnswer? =
        when (val out = machine.apply(request)) {
            is Outcome.Ok -> {
                out.value
            }

            is Outcome.Err -> {
                mutableEvents.tryEmit(WaSettingsEvent.Toast(UiText.Res(R.string.wa_error_generic)))
                null
            }
        }

    private suspend fun accept(
        answer: SettingsApplyAnswer,
        runEffects: Boolean,
    ) {
        model = answer.model
        saved[MODEL_KEY] = Envelope.json.encodeToString(JsonElement.serializer(), answer.model)
        publish(answer.view)
        if (runEffects) {
            for (effect in answer.effects) {
                run(effect)?.let { inbox.trySend(Work.Send(it)) }
            }
        }
    }

    private suspend fun publish(view: SettingsView) {
        val previous = mutableState.value
        val page = if (view.kind.startsWith(CONFIRM_PREFIX) && previous.view !== LOADING) previous.page else view
        val payload = view.qrPayload
        val matrix =
            when {
                payload == null -> null
                payload == qrPayload && previous.qr != null -> previous.qr
                else -> encode(payload)
            }
        qrPayload = payload
        mutableState.value =
            previous.copy(
                view = view,
                page = page,
                qr = matrix,
                keyQr = if (view.kind == SHARE_KEY) previous.keyQr else null,
            )
    }

    private suspend fun encode(text: String): QrMatrix? =
        when (val out = qr.encode(QrEncodeRequest(text))) {
            is Outcome.Ok -> QrMatrix.fromAnswer(out.value)
            is Outcome.Err -> null
        }

    // ---- effects

    @Serializable
    private data class CreateEffect(
        @SerialName("gateway_url") val gatewayUrl: String,
        val request: CreateInstance,
    )

    @Serializable
    private data class RelinkEffect(
        val link: StoredLink,
        val request: LinkRequest,
    )

    /** Runs one effect; the result is the input to feed back, or null when the effect has no answer. */
    private suspend fun run(effect: SettingsEffect): SettingsInput? {
        val json = Envelope.json
        val value = effect.value
        return when (effect.kind) {
            "create_instance" -> {
                val e = json.decodeFromJsonElement(CreateEffect.serializer(), value!!)
                withGateway(e.gatewayUrl, null) { it.createInstance(e.request) }.toInput { created ->
                    SettingsInput.json("created", json.encodeToJsonElement(CreatedInstance.serializer(), created))
                }
            }

            "verify" -> {
                val link = json.decodeFromJsonElement(StoredLink.serializer(), value!!)
                withGateway(link.gatewayUrl, link.key) { it.status() }.toInput { status ->
                    SettingsInput.json("status_ok", json.encodeToJsonElement(InstanceStatus.serializer(), status))
                }
            }

            "save" -> {
                val link = json.decodeFromJsonElement(StoredLink.serializer(), value!!)
                val failed = repo.relinkTo(link) as? Outcome.Err
                refreshShare()
                failed?.let { SettingsInput.error(it.error.message.orEmpty()) }
            }

            "forget" -> {
                // Unlinking is per app: the siblings keep their link, and this one is not taken back unasked.
                val removed = (store.read() as? Outcome.Ok)?.value
                repo.forget()
                handover?.onForget(removed)
                refreshShare()
                null
            }

            "subscribe" -> {
                repo.start()
                null
            }

            "abandon_instance" -> {
                val link = json.decodeFromJsonElement(StoredLink.serializer(), value!!)
                withGateway(link.gatewayUrl, link.key) { it.deleteInstance() }
                null
            }

            "mint_key" -> {
                val label = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                viaRepo { it.mintKey(label) }.toInput { issued ->
                    SettingsInput.json("show_key", json.encodeToJsonElement(IssuedKey.serializer(), issued))
                }
            }

            "copy_to_clipboard" -> {
                val text = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                // A connect key is a bearer secret: keep it out of the paste preview.
                if (clipboard.copy(
                        text,
                        sensitive = true,
                    )
                ) {
                    mutableEvents.tryEmit(WaSettingsEvent.Toast(UiText.Res(R.string.wa_copied)))
                }
                null
            }

            "list_keys" -> {
                viaRepo { it.keys() }.toInput { keys ->
                    SettingsInput.json(
                        "keys_loaded",
                        json.encodeToJsonElement(ListSerializer(KeyInfo.serializer()), keys),
                    )
                }
            }

            "revoke_key" -> {
                val id = (value as? JsonPrimitive)?.contentOrNull.orEmpty()
                viaRepo { it.revokeKey(id) }.toInput { SettingsInput.revoked(id) }
            }

            "relink" -> {
                val e = json.decodeFromJsonElement(RelinkEffect.serializer(), value!!)
                // Its answer arrives as events (a new QR or code), so only a failure goes back.
                withGateway(e.link.gatewayUrl, e.link.key) { it.relink(e.request) }.toInput(onOk = { null })
            }

            "delete_instance" -> {
                val link = json.decodeFromJsonElement(StoredLink.serializer(), value!!)
                withGateway(link.gatewayUrl, link.key) { it.deleteInstance() }.toInput { SettingsInput.deleted() }
            }

            else -> {
                null
            }
        }
    }

    private suspend fun <T> withGateway(
        url: String,
        key: String?,
        call: suspend (WaGateway) -> Outcome<T>,
    ): Outcome<T> =
        when (val opened = opener.open(url, key)) {
            is Outcome.Err -> {
                opened
            }

            is Outcome.Ok -> {
                try {
                    call(opened.value)
                } finally {
                    opened.value.close()
                }
            }
        }

    private suspend fun <T> viaRepo(call: suspend (WaGateway) -> Outcome<T>): Outcome<T> {
        val gateway = repo.gateway() ?: return Outcome.Err(RustError.NotFound("WhatsApp is not linked"))
        return call(gateway)
    }

    /** `Auth` means the key is wrong; every other failure goes back as its sentence. */
    private fun <T> Outcome<T>.toInput(onOk: (T) -> SettingsInput?): SettingsInput? =
        when (this) {
            is Outcome.Ok -> {
                onOk(value)
            }

            is Outcome.Err -> {
                if (error is RustError.Auth) {
                    SettingsInput.unauthorized()
                } else {
                    SettingsInput.error(
                        error.message.orEmpty(),
                    )
                }
            }
        }

    // ---- platform actions

    private suspend fun shareQr() {
        val matrix = mutableState.value.qr ?: return
        val staged =
            withContext(io) {
                val file = File(stagingDir, QR_FILE)
                QrPng.write(matrix, file)
                files.stageForShare(file.absolutePath, QR_NAME)
            }
        if (staged !=
            null
        ) {
            mutableEvents.tryEmit(WaSettingsEvent.ShareImage(staged))
        } else {
            mutableEvents.tryEmit(WaSettingsEvent.Toast(UiText.Res(R.string.wa_error_generic)))
        }
    }

    private fun paste() {
        val text = clipboard.primaryText()
        if (text.isNullOrEmpty()) {
            mutableEvents.tryEmit(WaSettingsEvent.Toast(UiText.Res(R.string.wa_nothing_to_paste)))
        } else {
            mutableState.value = mutableState.value.copy(paste = PasteRequest(++pasteCounter, text))
        }
    }

    private fun shareKey() {
        val key =
            mutableState.value.view.keyLines
                .joinToString("")
        if (key.isNotEmpty()) mutableEvents.tryEmit(WaSettingsEvent.ShareText(key))
    }

    private suspend fun toggleKeyQr() {
        val current = mutableState.value
        if (current.view.kind != SHARE_KEY) return
        mutableState.value =
            current.copy(keyQr = if (current.keyQr != null) null else encode(current.view.keyLines.joinToString("")))
    }

    /** The events the link machine reacts to; the rest is chat traffic it would ignore. */
    private fun GatewayEvent.concernsLinking() =
        this is GatewayEvent.Qr || this is GatewayEvent.PairCode || this is GatewayEvent.State ||
            this is GatewayEvent.KeyRevoked

    companion object {
        const val MODEL_KEY = "wa_settings_model"
        private const val CONFIRM_PREFIX = "confirm_"
        private const val SHARE_KEY = "share_key"
        private const val QR_FILE = "wa-qr.png"
        private const val QR_NAME = "whatsapp-qr.png"
        private const val EVENT_BUFFER = 16

        /** The page before the machine has answered. */
        val LOADING: SettingsView =
            SettingsView(
                kind = "connecting",
                title = "WhatsApp",
                notes = emptyList(),
                rows = emptyList(),
                buttons = emptyList(),
                fields = emptyList(),
                qrPayload = null,
                code = null,
                keyLines = emptyList(),
                keys = emptyList(),
                error = null,
                busy = true,
                back = null,
            )
    }
}
