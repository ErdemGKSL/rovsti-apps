package dev.rovsti.phone.people

import dev.rovsti.core.Outcome
import dev.rovsti.core.model.people.PersonRow
import dev.rovsti.people.GatewaySource
import dev.rovsti.wa.WaLinkState
import dev.rovsti.wa.WaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Phone without Contacts (X.1d): the people live in memory only, fed by the gateway. When WhatsApp is linked (at
 * process start once the link is read, and again when a link is made) [sync] asks the gateway which of the numbers
 * [numbers] are on WhatsApp, reads their names and picture ids, and asks the app's own avatar cache ([wantPicture]) for
 * the pictures. Nothing is written to disk here: the pictures are the files the WhatsApp avatar cache already keeps,
 * and the rows are forgotten with the process. Until a sync has answered the source is empty and every avatar draws
 * initials; the incoming-call path only ever reads what is in memory ([GatewaySource] through `PeopleIndex`).
 *
 * [pictureFile] finds a stored picture and [pictureChanges] fires when the avatar cache stored a new one, so a picture
 * that arrives after the sync is picked up without asking the gateway again.
 */
class StandalonePeople(
    private val wa: WaRepository,
    private val numbers: suspend () -> List<String>,
    private val wantPicture: (jid: String, pictureId: String?) -> Unit,
    private val pictureFile: suspend (jid: String) -> File?,
    private val pictureChanges: Flow<*>,
    private val scope: CoroutineScope,
) {
    private val files = ConcurrentHashMap<String, File>()

    @Volatile private var rows: List<PersonRow> = emptyList()

    /** The in-memory source the app's `PeopleIndex` follows. */
    val source: GatewaySource = GatewaySource(fetch = { rows }, pictureFile = { files[it.jid] })

    private var started = false

    /** Follows the link: syncs when WhatsApp becomes linked, forgets everything when it is not. Idempotent. */
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            var syncedFor: String? = null
            wa.linkState.collect { link ->
                val instance = (link as? WaLinkState.Connected)?.status?.instanceId
                when {
                    instance == null -> {
                        syncedFor = null
                        forget()
                    }

                    instance != syncedFor -> {
                        syncedFor = instance
                        sync()
                    }
                }
            }
        }
        scope.launch {
            pictureChanges.collect {
                if (rows.isNotEmpty() && refreshFiles()) source.changed()
            }
        }
    }

    /** One pass over the gateway: who is on WhatsApp, their profiles, then the pictures. False when it could not. */
    suspend fun sync(): Boolean {
        if (wa.linkState.value !is WaLinkState.Connected) return false
        val gateway = wa.gateway() ?: return false
        val asked =
            numbers()
                .mapNotNull { e164 -> jidOf(e164)?.let { it to e164 } }
                .distinctBy { it.first }
                .take(MAX_NUMBERS)
        val numberOf = asked.toMap()
        val onWhatsapp = ArrayList<String>()
        for (batch in asked.map { it.first }.chunked(BATCH)) {
            val users = (gateway.lookupUsers(batch) as? Outcome.Ok)?.value ?: return false
            users.filter { it.onWhatsapp }.mapTo(onWhatsapp) { it.jid }
        }
        val found = ArrayList<PersonRow>()
        for (batch in onWhatsapp.chunked(BATCH)) {
            val profiles = (gateway.profiles(batch) as? Outcome.Ok)?.value ?: return false
            for (profile in profiles) {
                found +=
                    PersonRow(
                        jid = profile.jid,
                        numbers = listOfNotNull(numberOf[profile.jid]),
                        name = profile.displayName(),
                        onWhatsapp = true,
                        pictureId = profile.pictureId,
                    )
            }
        }
        rows = found
        found.forEach { wantPicture(it.jid, it.pictureId) }
        refreshFiles()
        source.changed()
        return true
    }

    private fun forget() {
        rows = emptyList()
        files.clear()
        source.changed()
    }

    /** Re-reads which pictures the avatar cache holds; true when that changed. */
    private suspend fun refreshFiles(): Boolean {
        var changed = false
        for (row in rows) {
            if (row.pictureId == null) continue
            val file = pictureFile(row.jid)
            if (file != null && files.put(row.jid, file) != file) changed = true
        }
        return changed
    }

    companion object {
        const val BATCH = 50
        const val MAX_NUMBERS = 1_000
        private const val PHONE_SUFFIX = "@s.whatsapp.net"

        /** `+905321234567` -> `905321234567@s.whatsapp.net`; null when it is not a plausible number. */
        fun jidOf(e164: String): String? {
            val digits = e164.removePrefix("+")
            return if (digits.length in 7..15 && digits.all { it in '0'..'9' }) digits + PHONE_SUFFIX else null
        }
    }
}
