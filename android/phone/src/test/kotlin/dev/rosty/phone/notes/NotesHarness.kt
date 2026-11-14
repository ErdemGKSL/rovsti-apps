package dev.rosty.phone.notes

import androidx.compose.runtime.Composable
import dev.rosty.phone.HostRust
import dev.rosty.phone.HostedPhone
import dev.rosty.phone.PhoneHarness
import dev.rosty.phone.PhoneRoute
import dev.rosty.phone.PhoneSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** What the page asked of Android and of the call sheet. */
internal class RecordingNotesHost : NotesHost {
    val shared = mutableListOf<String>()
    val saved = mutableListOf<Pair<String, String>>()
    val opened = mutableListOf<dev.rosty.phone.details.CallDetailsTarget>()
    var dismissOpened: (() -> Unit)? = null

    override fun share(text: String) {
        shared += text
    }

    override fun saveCsv(
        fileName: String,
        csv: String,
    ) {
        saved += fileName to csv
    }
}

/** The Notes page on the real shell over the REAL core: [HostRust] files, [NotesRig] model. */
internal class NotesPageRig(
    host: HostRust,
    val harness: PhoneHarness = PhoneHarness(),
    notes: dev.rosty.core.model.phone.Notes = sampleNotes(),
) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val rig = NotesRig(host, scope, notes = notes)
    val host = RecordingNotesHost()

    suspend fun ready() = rig.ready()

    fun close() = scope.cancel()

    @Composable
    fun Content() {
        HostedPhone(harness, PhoneSample.setup(), push = PhoneRoute.Notes, notes = {
            NotesPage(
                viewModel = rig.model,
                host = host,
                onOpenCall = { target, onDismissed ->
                    host.opened += target
                    host.dismissOpened = onDismissed
                },
                onBack = {},
                onBackLongClick = {},
            )
        })
    }
}
