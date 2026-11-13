package dev.rovsti.phone.settings

import dev.rovsti.core.model.phone.CallLogEntry
import dev.rovsti.core.model.phone.CallLogKind
import dev.rovsti.core.model.phone.CallNote
import dev.rovsti.core.model.phone.Notes
import dev.rovsti.core.model.phone.PruneAge
import dev.rovsti.phone.HostRust
import dev.rovsti.phone.UnconfinedMain
import dev.rovsti.phone.data.T0
import dev.rovsti.phone.data.logEntry
import dev.rovsti.phone.settings.data.DataAction
import dev.rovsti.phone.settings.data.DataEffect
import dev.rovsti.phone.settings.data.DataViewModel
import dev.rovsti.platform.calllog.FakeCallLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E5.9b Data on the REAL core: the CSV with its note column, the names, the prune count, Delete all and its Undo. */
class DataViewModelTest {
    private val host by lazy { HostRust("rovsti-data") }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val day = 24 * 60L

    private val log =
        listOf(
            logEntry(1, "+905551112233", CallLogKind.Incoming, ago = 5, name = "Ayse Yilmaz"),
            logEntry(2, "+905553334455", CallLogKind.Outgoing, ago = 40 * day, name = "Deniz Aksoy"),
            logEntry(3, "+905559998877", CallLogKind.Missed, ago = 100 * day),
            logEntry(4, "+905551112233", CallLogKind.Outgoing, ago = 400 * day, name = "Ayse Yilmaz"),
        )

    @Before fun mainDispatcher() = UnconfinedMain.install()

    @After fun stop() = scope.cancel()

    private class Parts(
        val model: DataViewModel,
        val callLog: FakeCallLogRepository,
        val rig: SettingsRig,
        val forgotten: MutableList<String>,
        val refreshed: IntArray,
    )

    private suspend fun parts(
        undoWindowMs: Long = 150,
        contacts: List<dev.rovsti.core.model.search.WireContact> = listOf(person(1, "Ayse Yilmaz", "+905551112233")),
    ): Parts {
        val rig = SettingsRig(host, scope)
        rig.store.load()
        val callLog = FakeCallLogRepository(log)
        val forgotten = mutableListOf<String>()
        val refreshed = IntArray(1)
        val model =
            DataViewModel(
                ops = rig.ops,
                contactsOps = rig.contactsOps,
                store = rig.store,
                callLog = callLog,
                entries = { callLog.snapshot() },
                extras = { emptyList() },
                contacts = { contacts },
                names = { mapOf("+905553334455" to "Deniz Aksoy") },
                forgetWhatsApp = { forgotten += it },
                refresh = { refreshed[0]++ },
                clock = { T0 },
                tzOffsetS = { 0 },
                undoWindowMs = undoWindowMs,
                scope = scope,
            )
        return Parts(model, callLog, rig, forgotten, refreshed)
    }

    private suspend fun DataViewModel.effectNow() = withTimeout(10_000) { effect.first() }

    @Test fun theCallHistoryCsvHasTheNoteColumnAndTheNoteOfACall() =
        runBlocking {
            val p = parts()
            p.rig.store.updateNotes {
                Notes(
                    1,
                    listOf(CallNote("+905551112233", log[0].dateMs, "Ask about the keys", T0)),
                )
            }
            p.model.onAction(DataAction.ExportHistory)
            val export = p.model.effectNow() as DataEffect.Export
            assertEquals("text/csv", export.file.mime)
            assertEquals(SavedWhat.History, export.file.what)
            assertTrue(export.file.fileName.startsWith("rovsti-calls-") && export.file.fileName.endsWith(".csv"))
            val lines =
                export.file.text
                    .trim()
                    .split("\r\n")
            assertEquals("date,time,direction,name,number,duration_s,duration,channel,note", lines.first())
            assertEquals(5, lines.size)
            assertTrue(
                "the note sits on its call",
                lines.any {
                    it.contains("Ayse Yilmaz") &&
                        it.endsWith("Ask about the keys")
                },
            )
        }

    @Test fun anEmptyLogHasNothingToExport() =
        runBlocking {
            val p = parts()
            p.callLog.deleteAll()
            p.model.onAction(DataAction.ExportHistory)
            assertEquals(DataEffect.NothingToExport(SavedWhat.History), p.model.effectNow())
        }

    @Test fun theNotesExportCarriesTheNames() =
        runBlocking {
            val p = parts()
            p.model.onAction(DataAction.ExportNotes)
            assertEquals(DataEffect.NothingToExport(SavedWhat.Notes), p.model.effectNow())
            p.rig.store.updateNotes { Notes(1, listOf(CallNote("+905553334455", log[1].dateMs, "Invoice", T0))) }
            p.model.onAction(DataAction.ExportNotes)
            val export = p.model.effectNow() as DataEffect.Export
            assertEquals(SavedWhat.Notes, export.file.what)
            assertTrue("U41: the name of the number is in the file", export.file.text.contains("Deniz Aksoy"))
            assertTrue(export.file.text.contains("Invoice"))
        }

    @Test fun theContactsExportIsAVcardWithEachPerson() =
        runBlocking {
            val p =
                parts(
                    contacts =
                        listOf(
                            person(1, "Ayse Yilmaz", "+905551112233"),
                            person(2, "Deniz Aksoy", "+905553334455"),
                        ),
                )
            p.model.onAction(DataAction.ExportContacts)
            val export = p.model.effectNow() as DataEffect.Export
            assertEquals("text/x-vcard", export.file.mime)
            assertEquals(SavedWhat.Contacts, export.file.what)
            assertEquals(2, Regex("BEGIN:VCARD").findAll(export.file.text).count())
            assertTrue(export.file.text.contains("Deniz Aksoy") && export.file.text.contains("905553334455"))
        }

    @Test fun noContactsHaveNothingToExport() =
        runBlocking {
            val p = parts(contacts = emptyList())
            p.model.onAction(DataAction.ExportContacts)
            assertEquals(DataEffect.NothingToExport(SavedWhat.Contacts), p.model.effectNow())
        }

    @Test fun theChooserCountsTheCallsOlderThanEachAge() =
        runBlocking {
            val p = parts()
            p.model.onAction(DataAction.ChooseOlder)
            val choices = (p.model.effectNow() as DataEffect.OlderChoices).choices
            assertEquals(listOf(PruneAge.Days30, PruneAge.Days90, PruneAge.Year), choices.map { it.age })
            assertEquals("older than 30, 90 and 365 days", listOf(3, 2, 1), choices.map { it.count })
        }

    @Test fun theConfirmCarriesTheCoresSentenceAndDeletingOlderCallsCanBeUndone() =
        runBlocking {
            val p = parts()
            p.model.onAction(DataAction.AskOlder(PruneAge.Days90))
            val ask = p.model.effectNow() as DataEffect.ConfirmOlder
            assertEquals(2, ask.plan.count)
            assertTrue("the core words the title", !ask.plan.title.isNullOrBlank())
            p.model.onAction(DataAction.DeleteOlder(PruneAge.Days90))
            val done = p.model.effectNow() as DataEffect.DeletedOlder
            assertEquals(2, done.count)
            assertEquals(
                listOf(1L, 2L),
                p.callLog
                    .snapshot()
                    .map { it.id }
                    .sorted(),
            )
            assertTrue(p.refreshed[0] > 0)
            p.model.onAction(DataAction.UndoOlder(done.rows))
            delay(100)
            assertEquals(4, p.callLog.snapshot().size)
        }

    @Test fun nothingOlderMeansNothingToDelete() =
        runBlocking {
            val p = parts()
            p.callLog.delete(listOf(3, 4))
            p.model.onAction(DataAction.DeleteOlder(PruneAge.Year))
            delay(100)
            assertEquals(2, p.callLog.snapshot().size)
        }

    @Test fun deleteAllWaitsForItsWindowAndThenAsksTheProvider() =
        runBlocking {
            val p = parts(undoWindowMs = 200)
            p.model.state.until { it.calls == 4 }
            p.model.onAction(DataAction.DeleteAll)
            assertEquals(DataEffect.DeleteAllPending(200), p.model.effectNow())
            assertTrue(p.model.state.value.deletePending)
            assertEquals("the provider is untouched inside the window", 4, p.callLog.snapshot().size)
            assertEquals(DataEffect.DeletedAll, p.model.effectNow())
            assertTrue(p.callLog.snapshot().isEmpty())
            assertFalse(p.model.state.value.deletePending)
        }

    @Test fun undoInsideTheWindowCancelsTheProviderCall() =
        runBlocking {
            val p = parts(undoWindowMs = 300)
            p.model.state.until { it.calls == 4 }
            p.model.onAction(DataAction.DeleteAll)
            p.model.effectNow()
            p.model.onAction(DataAction.UndoDeleteAll)
            delay(600)
            assertEquals("nothing was deleted", 4, p.callLog.snapshot().size)
            assertFalse(p.model.state.value.deletePending)
            assertTrue(p.forgotten.isEmpty())
        }
}
