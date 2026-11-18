package dev.rosty.messages.wa

import android.net.Uri
import android.webkit.MimeTypeMap
import dev.rosty.core.getOrNull
import dev.rosty.core.model.chat.VoiceSpeed
import dev.rosty.core.ops.NativeWaSyncOps
import dev.rosty.core.ops.WaSyncStore
import dev.rosty.messages.MessagesContainer
import dev.rosty.platform.files.FileResult
import dev.rosty.platform.system.AndroidDeviceData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * The real [WaOffline] of the app: the store of the instance the unlocked vault serves (read again whenever the link
 * state changes, so it also opens offline, where the link state is an error), filled through [remote] and woken by the
 * WorkManager outbox job. Nothing opens while the vault is locked, and the plaintext link is never read here
 * (`plans/wa-vault.md` 15, round 4).
 */
fun realWaOffline(
    container: MessagesContainer,
    remote: WaRemote,
): WaOffline =
    WaOffline(
        scope = container.scope,
        linkChanges = combine(container.wa.linkState, container.wa.settled) { state, settled -> state to settled },
        link = {
            container.vaultOps
                .state()
                .getOrNull()
                ?.unlocked
                ?.let { open -> WaStoreLink(open.instanceId, socketKey = "${open.gatewayUrl}#${open.handle}") }
        },
        // Memory only, lazily filled, the identity map from the encrypted vault cache (`plans/wa-vault.md` 13, 14).
        open = { instance ->
            WaSyncStore.open(
                instance,
                memory = true,
                ops = NativeWaSyncOps(container.rust),
                lazy = true,
                vaultCache = true,
            )
        },
        remote = remote,
        now = System::currentTimeMillis,
        wake = WaOutboxWork(container.context),
        rekeys = container.waRekeys,
        downloads = container.waDownloads,
        cleanup = messagesLinkCleanup(container),
        wipe = { instance -> WaSyncStore.wipe(instance, ops = NativeWaSyncOps(container.rust)) },
        gate = container.waGate,
        onLock = { container.lockedWhatsApp() },
        memoryOnly = true,
        startLocked = !container.waVault.unlocked,
    )

/**
 * The real [WaDownloads]: files under `files/wa-media`, the network kind and the free space from `:platform`, woken
 * by the same WorkManager job as the outbox.
 */
fun realWaDownloads(container: MessagesContainer): WaDownloads =
    WaDownloads(
        scope = container.scope,
        remote = container.waRemote,
        dir = File(container.context.filesDir, "wa-media"),
        now = System::currentTimeMillis,
        device = AndroidDeviceData(container.context),
        wake = WaOutboxWork(container.context),
        ram = MemfdRamFiles(),
        requests = container.waGate,
    )

/**
 * The real voice note player: Rust's `voice_player.*` rules, Android's media player (on the main thread) and the speed
 * remembered in `messages-prefs.json` (`voice_speed`).
 */
fun realWaVoice(container: MessagesContainer): WaVoiceNotes =
    WaVoiceNotes(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        rules = ChatVoiceRules(container.chat),
        engine = MediaVoiceEngine(),
        savedSpeed = {
            container.store.load()
            val saved =
                container.store.prefs.value
                    ?.voiceSpeed
                    ?.name
            VoiceSpeed.entries.firstOrNull { it.name == saved } ?: VoiceSpeed.X1
        },
        saveSpeed = { speed ->
            val next =
                dev.rosty.core.model.messages.VoiceSpeed.entries
                    .firstOrNull { it.name == speed.name }
            if (next != null) container.store.updatePrefs { it.copy(voiceSpeed = next) }
        },
    )

/**
 * Copies the picked or pasted document [uri] into the app's WhatsApp media folder, where the outbox sends it from. The
 * read stops at WhatsApp's 16 MiB ([WaStageResult.TooLarge]).
 */
suspend fun stageForWhatsApp(
    container: MessagesContainer,
    uri: String,
): WaStageResult =
    when (val read = container.files.readUri(Uri.parse(uri), WaMedia.MAX_SEND_BYTES.toInt())) {
        is FileResult.Ok -> {
            val copy = read.path?.let(::File)
            if (copy == null) {
                WaStageResult.Failed
            } else {
                withContext(Dispatchers.IO) {
                    val mime = read.mime?.takeIf { it.isNotBlank() } ?: mimeOfName(read.name) ?: OCTET_STREAM
                    val name = read.name?.takeIf { it.isNotBlank() } ?: defaultName(mime)
                    if (container.waMedia.inRam) {
                        // The copy the picker made is read into RAM and deleted at once (`plans/wa-vault.md` 13.1).
                        val staged =
                            try {
                                container.waMedia.stageInRam(name, copy.readBytes())
                            } catch (_: IOException) {
                                null
                            } finally {
                                copy.delete()
                            }
                        return@withContext staged
                            ?.let { WaStageResult.Staged(WaStagedFile(it, name, mime, it.length())) }
                            ?: WaStageResult.Failed
                    }
                    val target = container.waMedia.outgoing(UUID.randomUUID().toString(), name)
                    try {
                        if (target == null) {
                            copy.delete()
                            WaStageResult.Failed
                        } else {
                            if (!copy.renameTo(target)) {
                                copy.copyTo(target, overwrite = true)
                                copy.delete()
                            }
                            WaStageResult.Staged(WaStagedFile(target, name, mime, target.length()))
                        }
                    } catch (_: IOException) {
                        copy.delete()
                        WaStageResult.Failed
                    }
                }
            }
        }

        FileResult.TooLarge -> {
            WaStageResult.TooLarge
        }

        else -> {
            WaStageResult.Failed
        }
    }

private fun mimeOfName(name: String?): String? =
    name
        ?.substringAfterLast('.', "")
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }
        ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }

/** What a file without a name is called: a pasted picture is `image.png`. */
private fun defaultName(mime: String): String {
    val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
    return "${mime.substringBefore('/').ifEmpty { "file" }}.$extension"
}

private const val OCTET_STREAM = "application/octet-stream"
