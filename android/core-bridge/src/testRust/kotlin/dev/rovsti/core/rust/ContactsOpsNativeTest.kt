package dev.rovsti.core.rust

import dev.rovsti.core.Envelope
import dev.rovsti.core.Outcome
import dev.rovsti.core.RustError
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.DraftIssueKind
import dev.rovsti.core.model.contacts.DraftRequest
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.TextRequest
import dev.rovsti.core.model.contacts.TrashListRequest
import dev.rovsti.core.model.contacts.TrashPushRequest
import dev.rovsti.core.model.contacts.TrashRestoreRequest
import dev.rovsti.core.ops.ContactsOps
import dev.rovsti.core.ops.DeepIndexHandle
import dev.rovsti.core.ops.NativeContactsOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The Contacts facade against the real host library, and every self-contained Contacts fixture against its answer. */
class ContactsOpsNativeTest {
    private val ops = NativeContactsOps(TestRust.core)
    private val dir = File(System.getProperty("rovsti.contract") ?: error("rovsti.contract not set"))

    @Before
    fun load() = TestRust.ensure()

    @Test
    fun aDraftIsCleanedAndChecked() =
        runBlocking {
            val messy =
                ContactDraft(
                    given = "  Ada ",
                    numbers = listOf(DraftField(" 0555 111 22 33 ", FieldKind.Mobile), DraftField("05551112233")),
                )
            val ok = ops.draftValidate(DraftRequest(messy)).getOrOk()
            assertEquals("Ada", ok.draft.given)
            assertEquals(1, ok.draft.numbers.size)
            assertEquals(null, ok.error)
            val bad = ops.draftValidate(DraftRequest(ContactDraft(note = "x"))).getOrOk()
            assertEquals(DraftIssueKind.NoName, bad.error?.kind)
        }

    @Test
    fun vcardTextBecomesDraftsAndBack() =
        runBlocking {
            val card = "BEGIN:VCARD\nVERSION:3.0\nFN:Ada Lovelace\nN:Lovelace;Ada;;;\nTEL:+905551112233\nEND:VCARD\n"
            val parsed = ops.vcardParse(TextRequest(card)).getOrOk()
            assertEquals("Ada", parsed.contacts.single().given)
            assertEquals(listOf(null), parsed.photos)
        }

    @Test
    fun theTrashKeepsContactsInTheDataDirectory() =
        runBlocking {
            val tmp =
                Files
                    .createTempDirectory("rovsti-trash")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ada =
                ContactDraft(id = 5, given = "Ada", numbers = listOf(DraftField("0555 111 22 33", FieldKind.Mobile)))
            val pushed = ops.trashPush(TrashPushRequest(tmp, listOf(ada), 1_790_000_000_000)).getOrOk()
            assertEquals("Moved to Trash", pushed.notice)
            val listed = ops.trashList(TrashListRequest(tmp, 1_790_000_000_000)).getOrOk()
            assertEquals("Deleted today", listed.entries.single().leftText)
            val restored =
                ops
                    .trashRestore(
                        TrashRestoreRequest(tmp, pushed.keys, emptyList(), emptyList(), "", 1_790_000_000_000),
                    ).getOrOk()
            assertEquals(null, restored.drafts.single().id)
            assertTrue(
                ops
                    .trashList(TrashListRequest(tmp, 1_790_000_000_000))
                    .getOrOk()
                    .entries
                    .isEmpty(),
            )
        }

    @Test
    fun deepIndexHandleLifecycle() =
        runBlocking {
            val drafts = listOf(ContactDraft(id = 1, given = "Ada", org = "Analytical Engines Ltd"))
            val index = DeepIndexHandle.open(drafts, ops).getOrOk()
            val hits = index.find("engine", 10).getOrOk().hits
            assertEquals(1L, hits.single().id)
            index.close()
            assertTrue((index.find("engine", 10) as Outcome.Err).error is RustError.NotFound)
        }

    @Test
    fun everySelfContainedContactsFixtureRequestGivesItsAnswerFile() =
        runBlocking {
            val stems =
                File(dir, "INDEX").readLines().filter { stem ->
                    ContactsOps.ops.any { stem == it || stem.startsWith("$it.") }
                }
            var ran = 0
            for (stem in stems) {
                val request = File(dir, "$stem.request.json").readText().trim()
                val answer = File(dir, "$stem.answer.json").readText()
                // Handle and seed-directory cases need a live object or files; Rust runs those itself.
                if ("7777777777" in request || "7777777777" in answer || "\$DIR" in request) continue
                val op = ContactsOps.ops.filter { stem == it || stem.startsWith("$it.") }.maxBy { it.length }
                val live = TestRust.core.call(op, Envelope.json.parseToJsonElement(request))
                val wanted = Envelope.decode(answer.trim().encodeToByteArray())
                assertEquals(stem, shape(wanted), shape(live))
                ran++
            }
            assertTrue("ran $ran fixtures", ran > 100)
            assertNotNull(stems.firstOrNull())
        }

    private fun shape(outcome: Outcome<*>): String =
        when (outcome) {
            is Outcome.Ok -> "ok ${outcome.value}"
            is Outcome.Err -> "err ${outcome.error::class.simpleName} ${outcome.error.message} ${outcome.error.detail}"
        }

    private fun <T> Outcome<T>.getOrOk(): T = (this as? Outcome.Ok)?.value ?: error("failed: $this")
}
