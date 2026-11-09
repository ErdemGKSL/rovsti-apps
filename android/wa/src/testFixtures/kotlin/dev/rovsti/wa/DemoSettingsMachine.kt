package dev.rovsti.wa

import dev.rovsti.core.Envelope
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.wa.CreatedInstance
import dev.rovsti.core.model.wa.GatewayEvent
import dev.rovsti.core.model.wa.InstanceState
import dev.rovsti.core.model.wa.InstanceStatus
import dev.rovsti.core.model.wa.IssuedKey
import dev.rovsti.core.model.wa.KeyInfo
import dev.rovsti.core.model.wa.SettingsApplyAnswer
import dev.rovsti.core.model.wa.SettingsApplyRequest
import dev.rovsti.core.model.wa.SettingsButton
import dev.rovsti.core.model.wa.SettingsEffect
import dev.rovsti.core.model.wa.SettingsField
import dev.rovsti.core.model.wa.SettingsInput
import dev.rovsti.core.model.wa.SettingsKey
import dev.rovsti.core.model.wa.SettingsRow
import dev.rovsti.core.model.wa.SettingsView
import dev.rovsti.core.model.wa.StoredLink
import dev.rovsti.core.ops.WaSettingsOps
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A small stand-in for the Rust settings machine (`rovsti_app_core::wa_link::settings`), for the places that cannot
 * load the native library: the view-model tests, the screen tests and the catalog page. It follows the same pages,
 * inputs and effects and uses the same words, so what it draws is what the app draws. It is NOT the contract: the
 * contract is Rust's, pinned by the `wa.settings_apply.*` fixtures (`SettingsFixturesTest`, `WaSettingsFixtureViewsTest`).
 * Anything this class does that Rust does not is a bug of this class.
 */
class DemoSettingsMachine : WaSettingsOps {
    @Serializable
    private data class M(
        val screen: String = "choose",
        val app: String = "messages",
        val gatewayUrl: String = DEFAULT_URL,
        val link: StoredLink? = null,
        val status: InstanceStatus? = null,
        val pendingKey: String? = null,
        val relinking: Boolean = false,
        val method: String = "qr",
        val payload: String? = null,
        val code: String? = null,
        val key: String? = null,
        val keys: List<KeyInfo> = emptyList(),
        val subject: String? = null,
        val message: String? = null,
        val backTo: String? = null,
    )

    override suspend fun apply(req: SettingsApplyRequest): Outcome<SettingsApplyAnswer> {
        var m =
            req.model?.let { Envelope.json.decodeFromJsonElement(M.serializer(), it) }
                ?: M(
                    app = req.app.name.lowercase(),
                    link = req.link?.let { StoredLink(it.gatewayUrl, it.instanceId, it.key) },
                )
        val effects = mutableListOf<SettingsEffect>()
        if (req.model == null && m.link != null) {
            m = m.copy(screen = "connecting")
            effects += fx("verify", link(m.link!!))
        }
        req.input?.let { m = step(m, it, effects) }
        val model = Envelope.json.encodeToJsonElement(M.serializer(), m)
        return Outcome.Ok(SettingsApplyAnswer(model, effects, view(m, req.nowMs)))
    }

    // ---- transitions

    private fun step(
        m: M,
        input: SettingsInput,
        fx: MutableList<SettingsEffect>,
    ): M {
        val text = (input.value as? JsonPrimitive)?.content
        return when (input.kind) {
            "choose_link_new" -> {
                m.copy(screen = "link_new", method = "qr")
            }

            "use_pair_code" -> {
                m.copy(screen = "link_new", method = "code")
            }

            "choose_connect_existing" -> {
                m.copy(screen = "paste_key")
            }

            "edit_url" -> {
                m.copy(screen = "enter_url")
            }

            "url_entered" -> {
                if (text!!.startsWith("https://") || text.startsWith("http://")) {
                    m.copy(screen = "choose", gatewayUrl = text.trimEnd('/'))
                } else {
                    failed(m, "enter_url", "The address must start with https:// (or http:// on your own network)")
                }
            }

            "start_qr" -> {
                create(m, fx, "qr")
            }

            "phone_entered" -> {
                if (text!!.trim().startsWith("+")) {
                    create(m, fx, "phone_number", text.trim())
                } else {
                    failed(m, "link_new", "Enter the number with its country code, for example +90 532 111 22 33")
                }
            }

            "key_pasted" -> {
                if (text!!.trim().startsWith("rwk_")) {
                    fx += fx("verify", link(StoredLink(m.gatewayUrl, "", text.trim())))
                    m.copy(screen = "connecting", pendingKey = text.trim())
                } else {
                    failed(m, "paste_key", "connect key must start with \"rwk_\"")
                }
            }

            "created" -> {
                created(m, input, fx)
            }

            "status_ok" -> {
                statusOk(m, Envelope.json.decodeFromJsonElement(InstanceStatus.serializer(), input.value!!), fx)
            }

            "event" -> {
                event(m, Envelope.json.decodeFromJsonElement(GatewayEvent.serializer(), input.value!!), fx)
            }

            "unauthorized" -> {
                if (m.pendingKey != null) {
                    failed(m.copy(pendingKey = null), "paste_key", REJECTED)
                } else {
                    fx += SettingsEffect("forget")
                    failed(m.copy(link = null, status = null), "choose", REJECTED)
                }
            }

            "error" -> {
                failed(
                    m.copy(pendingKey = null),
                    if (m.pendingKey !=
                        null
                    ) {
                        "paste_key"
                    } else {
                        home(m)
                    },
                    text.orEmpty(),
                )
            }

            "request_share_key" -> {
                fx += SettingsEffect("mint_key", JsonPrimitive("Messages key"))
                m
            }

            "show_key" -> {
                val issued = Envelope.json.decodeFromJsonElement(IssuedKey.serializer(), input.value!!)
                fx += SettingsEffect("copy_to_clipboard", JsonPrimitive(issued.key))
                m.copy(screen = "share_key", key = issued.key)
            }

            "copy_key" -> {
                fx += SettingsEffect("copy_to_clipboard", JsonPrimitive(m.key.orEmpty()))
                m
            }

            "request_unlink" -> {
                m.copy(screen = "confirm_unlink")
            }

            "unlink" -> {
                fx += SettingsEffect("forget")
                m.copy(screen = "choose", link = null, status = null)
            }

            "back" -> {
                back(m)
            }

            "cancel" -> {
                cancel(m, fx)
            }

            "retry" -> {
                if (m.link != null && m.pendingKey == null) {
                    fx += fx("verify", link(m.link))
                    m.copy(screen = "connecting")
                } else {
                    m.copy(screen = m.backTo ?: "choose")
                }
            }

            "open_keys" -> {
                fx += SettingsEffect("list_keys")
                m.copy(screen = "connecting")
            }

            "keys_loaded" -> {
                m.copy(
                    screen = "keys",
                    keys = Envelope.json.decodeFromJsonElement(ListSerializer(KeyInfo.serializer()), input.value!!),
                )
            }

            "request_revoke" -> {
                m.copy(screen = "confirm_revoke", subject = text)
            }

            "revoke" -> {
                fx += SettingsEffect("revoke_key", JsonPrimitive(text))
                m.copy(screen = "keys")
            }

            "revoked" -> {
                if (m.keys.any { it.keyId == text && it.current }) {
                    fx += SettingsEffect("forget")
                    m.copy(screen = "choose", link = null, status = null)
                } else {
                    m.copy(keys = m.keys.filterNot { it.keyId == text })
                }
            }

            "relink" -> {
                val link = m.link ?: return m
                val request = input.value as JsonObject
                fx +=
                    SettingsEffect(
                        "relink",
                        buildJsonObject {
                            put("link", Envelope.json.encodeToJsonElement(StoredLink.serializer(), link))
                            put("request", request)
                        },
                    )
                m.copy(screen = "connecting", relinking = true)
            }

            "request_delete" -> {
                m.copy(screen = "confirm_delete")
            }

            "confirm_delete" -> {
                fx += fx("delete_instance", link(m.link ?: return m))
                m.copy(screen = "connecting")
            }

            "deleted" -> {
                fx += SettingsEffect("forget")
                m.copy(screen = "choose", link = null, status = null)
            }

            else -> {
                m
            }
        }
    }

    private fun create(
        m: M,
        fx: MutableList<SettingsEffect>,
        kind: String,
        number: String? = null,
    ): M {
        val request =
            buildJsonObject {
                put(
                    "link",
                    buildJsonObject {
                        put("type", kind)
                        if (number != null) put("number", number)
                    },
                )
                put("app", m.app)
            }
        fx +=
            SettingsEffect(
                "create_instance",
                buildJsonObject {
                    put("gateway_url", m.gatewayUrl)
                    put("request", request)
                },
            )
        return m.copy(screen = "connecting")
    }

    private fun created(
        m: M,
        input: SettingsInput,
        fx: MutableList<SettingsEffect>,
    ): M {
        val created = Envelope.json.decodeFromJsonElement(CreatedInstance.serializer(), input.value!!)
        val link = StoredLink(m.gatewayUrl, created.instanceId, created.key)
        fx += fx("save", link(link))
        fx += fx("subscribe", link(link))
        return m.copy(link = link, status = null)
    }

    private fun statusOk(
        m0: M,
        status: InstanceStatus,
        fx: MutableList<SettingsEffect>,
    ): M {
        var m = m0
        m.pendingKey?.let { key ->
            val link = StoredLink(m.gatewayUrl, status.instanceId, key)
            m = m.copy(link = link, pendingKey = null)
            fx += fx("save", link(link))
        }
        val link = m.link ?: return m
        if (status.state == InstanceState.Banned) {
            fx.clear()
            fx += SettingsEffect("forget")
            return failed(m.copy(link = null, status = null), "choose", BANNED)
        }
        fx += fx("subscribe", link(link))
        return showStatus(m, status)
    }

    private fun showStatus(
        m: M,
        status: InstanceStatus,
    ): M {
        val relinking = m.relinking && status.state != InstanceState.Connected
        val next =
            when (status.state) {
                InstanceState.LoggedOut -> {
                    "logged_out"
                }

                InstanceState.Linking -> {
                    if (m.screen == "show_qr" ||
                        m.screen == "show_code"
                    ) {
                        return m.copy(status = status)
                    } else {
                        "connecting"
                    }
                }

                else -> {
                    "connected"
                }
            }
        return m.copy(screen = next, status = status, relinking = relinking)
    }

    private fun event(
        m: M,
        event: GatewayEvent,
        fx: MutableList<SettingsEffect>,
    ): M {
        val waiting = m.screen in setOf("connecting", "show_qr", "show_code")
        return when {
            event is GatewayEvent.Qr && waiting && m.link != null -> {
                m.copy(screen = "show_qr", payload = event.payload)
            }

            event is GatewayEvent.PairCode && waiting && m.link != null -> {
                m.copy(screen = "show_code", code = event.code)
            }

            event is GatewayEvent.State && m.link != null && event.status().state == InstanceState.Banned -> {
                fx += SettingsEffect("forget")
                failed(m.copy(link = null, status = null), "choose", BANNED)
            }

            event is GatewayEvent.State && m.link != null -> {
                val busy =
                    m.screen in
                        setOf("share_key", "failed", "keys", "confirm_delete", "confirm_unlink", "confirm_revoke")
                if (busy || (m.screen == "logged_out" && event.status().state != InstanceState.Connected)) {
                    m.copy(status = event.status())
                } else {
                    showStatus(m, event.status())
                }
            }

            event is GatewayEvent.KeyRevoked && m.link != null -> {
                fx += SettingsEffect("forget")
                failed(m.copy(link = null, status = null), "choose", REJECTED)
            }

            else -> {
                m
            }
        }
    }

    private fun back(m: M): M =
        when (m.screen) {
            "enter_url", "link_new", "paste_key" -> m.copy(screen = "choose")
            "share_key", "keys", "confirm_delete", "confirm_unlink" -> m.copy(screen = home(m))
            "failed" -> m.copy(screen = m.backTo ?: "choose")
            "confirm_revoke" -> m.copy(screen = "keys")
            else -> m
        }

    private fun cancel(
        m: M,
        fx: MutableList<SettingsEffect>,
    ): M {
        if (m.screen != "show_qr" && m.screen != "show_code") return m
        if (m.relinking) return m.copy(screen = "logged_out", relinking = false)
        fx += SettingsEffect("forget")
        m.link?.let { fx += fx("abandon_instance", link(it)) }
        return m.copy(screen = "choose", link = null, status = null)
    }

    private fun home(m: M): String =
        when {
            m.link == null -> "choose"
            m.status?.state == InstanceState.LoggedOut -> "logged_out"
            else -> "connected"
        }

    private fun failed(
        m: M,
        back: String,
        message: String,
    ) = m.copy(screen = "failed", message = message, backTo = back)

    private fun link(l: StoredLink): JsonElement = Envelope.json.encodeToJsonElement(StoredLink.serializer(), l)

    private fun fx(
        kind: String,
        value: JsonElement,
    ) = SettingsEffect(kind, value)

    // ---- views

    private fun view(
        m: M,
        now: Long,
    ): SettingsView {
        val back = SettingsInput.back

        fun btn(
            id: String,
            label: String,
            role: String,
            input: SettingsInput?,
            detail: String? = null,
            section: String? = null,
            field: String? = null,
            destructive: Boolean = false,
        ) = SettingsButton(id, label, detail, role, destructive, section, input, field)

        fun page(
            kind: String,
            title: String,
            notes: List<String> = emptyList(),
            buttons: List<SettingsButton> = emptyList(),
            rows: List<SettingsRow> = emptyList(),
            fields: List<SettingsField> = emptyList(),
            qr: String? = null,
            code: String? = null,
            keyLines: List<String> = emptyList(),
            keys: List<SettingsKey> = emptyList(),
            error: String? = null,
            busy: Boolean = false,
            backInput: SettingsInput? = null,
        ) = SettingsView(kind, title, notes, rows, buttons, fields, qr, code, keyLines, keys, error, busy, backInput)

        return when (m.screen) {
            "choose" -> {
                page(
                    "choose",
                    "WhatsApp",
                    listOf(
                        "WhatsApp runs on your Rovsti gateway, a server you control. WhatsApp does not endorse third-party clients and may restrict accounts that use them.",
                    ),
                    listOf(
                        btn("edit_url", "Address", "option", SettingsInput.unit("edit_url"), m.gatewayUrl, "Gateway"),
                        btn(
                            "link_new",
                            "Link WhatsApp for the first time",
                            "option",
                            SettingsInput.unit("choose_link_new"),
                            "Create a new instance and link it from the WhatsApp app",
                        ),
                        btn(
                            "connect_existing",
                            "Connect to an existing instance",
                            "option",
                            SettingsInput.unit("choose_connect_existing"),
                            "Paste a connect key shown by another Rovsti app",
                        ),
                    ),
                )
            }

            "enter_url" -> {
                page(
                    "enter_url",
                    "Gateway address",
                    listOf(
                        "The address of your gateway, for example https://wa.example.com. Plain http is only accepted on your own network.",
                    ),
                    listOf(
                        btn(
                            "use_address",
                            "Use this address",
                            "primary",
                            SettingsInput.text("url_entered", ""),
                            field = "url",
                        ),
                        btn("back", "Back", "secondary", back),
                    ),
                    fields = listOf(SettingsField("url", "https://", m.gatewayUrl, "url")),
                    backInput = back,
                )
            }

            "link_new" -> {
                if (m.method == "qr") {
                    page(
                        "link_new",
                        "Link WhatsApp",
                        listOf(
                            "Scan a QR code from WhatsApp on another phone, or share it to a PC. Or get a pairing code if this phone has WhatsApp.",
                        ),
                        listOf(
                            btn("start_qr", "Show a QR code", "primary", SettingsInput.unit("start_qr")),
                            btn(
                                "use_code",
                                "Use a pairing code instead",
                                "secondary",
                                SettingsInput.unit("use_pair_code"),
                            ),
                            btn("back", "Back", "secondary", back),
                        ),
                        backInput = back,
                    )
                } else {
                    page(
                        "link_new",
                        "Pairing code",
                        listOf(
                            "Enter the number of the WhatsApp account with its country code, for example +90 532 111 22 33.",
                        ),
                        listOf(
                            btn(
                                "get_code",
                                "Get a pairing code",
                                "primary",
                                SettingsInput.text("phone_entered", ""),
                                field = "phone",
                            ),
                            btn("back", "Back", "secondary", back),
                        ),
                        fields = listOf(SettingsField("phone", "+90 …", "", "phone")),
                        backInput = back,
                    )
                }
            }

            "show_qr" -> {
                page(
                    "show_qr",
                    "Scan to link",
                    listOf(
                        "On another screen, open WhatsApp → Settings → Linked devices → Link a device and scan this code. Share it to your PC if this is the only phone.",
                    ),
                    listOf(
                        btn("share_qr", "Share QR to PC…", "secondary", null),
                        btn("cancel", "Cancel", "secondary", SettingsInput.cancel),
                    ),
                    qr = m.payload,
                    backInput = SettingsInput.cancel,
                )
            }

            "show_code" -> {
                page(
                    "show_code",
                    "Pairing code",
                    listOf("In WhatsApp → Linked devices → Link a device → Link with phone number instead, enter:"),
                    listOf(btn("cancel", "Cancel", "secondary", SettingsInput.cancel)),
                    code = m.code,
                    backInput = SettingsInput.cancel,
                )
            }

            "paste_key" -> {
                page(
                    "paste_key",
                    "Connect key",
                    listOf(
                        "On a Rovsti app that is already connected, open WhatsApp settings, tap \"Show connect key\" and paste the key here.",
                    ),
                    listOf(
                        btn("paste_clipboard", "Paste from clipboard", "secondary", null),
                        btn("connect", "Connect", "primary", SettingsInput.text("key_pasted", ""), field = "key"),
                        btn("back", "Back", "secondary", back),
                    ),
                    fields = listOf(SettingsField("key", "rwk_…", "", "key")),
                    backInput = back,
                )
            }

            "connecting" -> {
                page("connecting", "WhatsApp", listOf("Connecting to WhatsApp…"), busy = true)
            }

            "connected" -> {
                page(
                    "connected",
                    "WhatsApp",
                    listOf(
                        "Unlinking only removes WhatsApp from this app. The instance keeps running on the gateway for your other apps.",
                    ),
                    listOf(
                        btn("show_key", "Show connect key", "secondary", SettingsInput.unit("request_share_key")),
                        btn("keys", "Connected apps", "secondary", SettingsInput.unit("open_keys")),
                        btn("unlink", "Unlink this app", "secondary", SettingsInput.unit("request_unlink")),
                        btn(
                            "delete",
                            "Delete instance…",
                            "destructive",
                            SettingsInput.unit("request_delete"),
                            destructive = true,
                        ),
                    ),
                    rows =
                        listOf(
                            SettingsRow(
                                m.status?.pushName ?: "WhatsApp",
                                stateText(m.status),
                                "status",
                                m.status
                                    ?.state
                                    ?.name
                                    ?.lowercase(),
                            ),
                        ),
                )
            }

            "share_key" -> {
                page(
                    "share_key",
                    "Connect key",
                    listOf(
                        "Copied. Paste this key into \"Connect to an existing instance\" in another Rovsti app. Anyone with the key can use your WhatsApp, so share it only with your own apps.",
                    ),
                    listOf(
                        btn("copy_key", "Copy again", "primary", SettingsInput.copyKey),
                        btn("back", "Back", "secondary", back),
                    ),
                    keyLines = m.key.orEmpty().chunked(24),
                    backInput = back,
                )
            }

            "keys" -> {
                page(
                    "keys",
                    "Connected apps",
                    listOf(
                        "Every app that can use this WhatsApp instance. Revoking a key signs that app out. Revoking this app's own key unlinks it.",
                    ),
                    listOf(btn("back", "Back", "secondary", back)),
                    keys =
                        m.keys.map {
                            SettingsKey(
                                it.keyId,
                                it.label,
                                keyDetail(it, now),
                                it.current,
                                SettingsInput.text("request_revoke", it.keyId),
                            )
                        },
                    backInput = back,
                )
            }

            "confirm_revoke" -> {
                val key = m.keys.firstOrNull { it.keyId == m.subject }
                page(
                    "confirm_revoke",
                    "Revoke key",
                    listOf("The app using “${key?.label.orEmpty()}” is signed out of WhatsApp and needs a new key."),
                    listOf(
                        btn(
                            "revoke_confirm",
                            "Revoke",
                            "destructive",
                            SettingsInput.text("revoke", m.subject.orEmpty()),
                            destructive = true,
                        ),
                        btn("back", "Back", "secondary", back),
                    ),
                    backInput = back,
                )
            }

            "confirm_unlink" -> {
                page(
                    "confirm_unlink",
                    "Unlink this app",
                    listOf(
                        "This app forgets its WhatsApp link. To use WhatsApp here again, paste a connect key from another Rovsti app or link again. Your other apps stay linked.",
                    ),
                    listOf(
                        btn(
                            "unlink_confirm",
                            "Unlink",
                            "destructive",
                            SettingsInput.unit("unlink"),
                            destructive = true,
                        ),
                        btn("back", "Back", "secondary", back),
                    ),
                    backInput = back,
                )
            }

            "confirm_delete" -> {
                page(
                    "confirm_delete",
                    "Delete instance",
                    listOf(
                        "This logs WhatsApp out and deletes the instance, its history and every connect key on the gateway. All your Rovsti apps lose WhatsApp. This cannot be undone.",
                    ),
                    listOf(
                        btn(
                            "delete_confirm",
                            "Delete instance",
                            "destructive",
                            SettingsInput.unit("confirm_delete"),
                            destructive = true,
                        ),
                        btn("back", "Back", "secondary", back),
                    ),
                    backInput = back,
                )
            }

            "logged_out" -> {
                page(
                    "logged_out",
                    "Logged out",
                    listOf(
                        "WhatsApp logged this instance out. Link it again; your other apps keep their keys.",
                        "Or get a pairing code: enter the number with its country code, for example +90 532 111 22 33.",
                    ),
                    listOf(
                        btn("relink_qr", "Link again with a QR code", "primary", SettingsInput.relinkQr()),
                        btn(
                            "relink_code",
                            "Link again with a pairing code",
                            "secondary",
                            SettingsInput(
                                "relink",
                                buildJsonObject {
                                    put("type", "phone_number")
                                    put("number", "")
                                },
                            ),
                            field = "phone",
                        ),
                        btn("unlink", "Unlink this app", "secondary", SettingsInput.unit("request_unlink")),
                    ),
                    fields = listOf(SettingsField("phone", "+90 …", "", "phone")),
                )
            }

            else -> {
                page(
                    "failed",
                    "Something went wrong",
                    buttons =
                        listOf(
                            btn("retry", "Try again", "primary", SettingsInput.retry),
                            btn("back", "Back", "secondary", back),
                        ),
                    error = m.message,
                    backInput = back,
                )
            }
        }
    }

    private fun stateText(status: InstanceStatus?): String =
        when (status?.state) {
            InstanceState.Connected -> "Connected"
            InstanceState.Linking -> "Linking"
            InstanceState.LoggedOut -> "Logged out"
            else -> "Reconnecting…"
        }

    private fun keyDetail(
        key: KeyInfo,
        now: Long,
    ): String {
        val used = key.lastUsedMs?.let { ago(now - it) } ?: "never used"
        return if (key.current) "this app · $used" else used
    }

    private fun ago(ms: Long): String =
        when {
            ms < 60_000 -> "just now"
            ms < 3_600_000 -> "${ms / 60_000} min ago"
            ms < 86_400_000 -> "${ms / 3_600_000} h ago"
            else -> "${ms / 86_400_000} d ago"
        }

    private companion object {
        const val DEFAULT_URL = "https://rovsti.erdemdev.tr"
        const val REJECTED = "The gateway did not accept this connect key"
        const val BANNED = "WhatsApp banned this number. Nothing this app can do."
    }
}
