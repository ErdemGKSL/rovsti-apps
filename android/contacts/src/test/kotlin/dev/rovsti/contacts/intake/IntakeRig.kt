package dev.rovsti.contacts.intake

import android.net.Uri
import dev.rovsti.contacts.card.SampleCards
import dev.rovsti.contacts.data.BookRepository
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.files.FakeClipboardRepository
import dev.rovsti.platform.files.FakeFilesRepository
import dev.rovsti.platform.files.FileResult
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files

/** A 1 x 1 PNG, the PHOTO of the card of [ONE_WITH_PHOTO]. */
private const val PIXEL_PNG =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="

internal fun vcard(
    full: String,
    family: String,
    given: String,
    tel: String,
    extra: String = "",
) = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:$full\r\nN:$family;$given;;;\r\nTEL;TYPE=CELL:$tel\r\n${extra}END:VCARD\r\n"

/** One card the address book does not have. */
internal val ONE_NEW = vcard("Ada Lovelace", "Lovelace", "Ada", "+44 20 7946 0958")

/** Two cards the address book does not have. */
internal val TWO_NEW =
    ONE_NEW +
        vcard("Grace Hopper", "Hopper", "Grace", "+1 415 555 0100", "ORG:US Navy\r\n")

/** The two new cards and a copy of Burak Demir ([SampleCards.burak]), which the book has. */
internal val TWO_AND_A_COPY =
    TWO_NEW + vcard("Burak Demir", "Demir", "Burak", "0532 123 45 67")

/** One new card with a picture. */
internal val ONE_WITH_PHOTO =
    vcard(
        "Katherine Johnson",
        "Johnson",
        "Katherine",
        "+1 304 555 0111",
        "EMAIL:kj@example.org\r\nPHOTO;ENCODING=b;TYPE=PNG:$PIXEL_PNG\r\n",
    )

/**
 * The import model on the REAL Rust core (the host `librovsti_ffi`), with the in-memory provider, files and
 * clipboard. The test is skipped when the host library is absent (as `CardRig` does).
 */
internal class IntakeRig(
    source: (IntakeRig) -> IntakeSource,
    drafts: List<ContactDraft> = SampleCards.all,
    file: String? = null,
    clip: String? = null,
) {
    val scope = CoroutineScope(Dispatchers.Default)
    val repo = FakeContactsRepository(drafts, SampleCards.groups)
    val files = FakeFilesRepository()
    val clipboard = FakeClipboardRepository(text = clip)
    val staging: File = Files.createTempDirectory("rovsti-intake").toFile().apply { deleteOnExit() }
    val store: ContactsStore
    val trash: ContactTrash
    val book: BookRepository
    val model: IntakeViewModel

    init {
        val lib = File(System.getProperty("rovsti.hostLib").orEmpty())
        assumeTrue(
            "host librovsti_ffi not built (run :core-bridge:cargoBuildHost)",
            lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rovsti_ffi") },
        )
        val root = Files.createTempDirectory("rovsti-intake-core").toFile().apply { deleteOnExit() }
        val started =
            runBlocking {
                Rust
                    .init(
                        File(root, "data"),
                        File(root, "cache"),
                        "dev.rovsti.contacts",
                        35,
                        CoroutineScope(Dispatchers.Default),
                    ).await()
            }
        assertTrue("Rust.init: $started", started is Outcome.Ok)
        val ops = NativeContactsOps(Rust.core())
        store = ContactsStore(ops, File(root, "store").apply { mkdirs() }.absolutePath) { SampleCards.NOW }
        trash = ContactTrash(repo, store)
        book = BookRepository(repo, MutableStateFlow(PermissionState.Granted), NativeSearchOps(Rust.core()), ops, scope)
        if (file != null) {
            val picked = File(staging, "picked.vcf").apply { writeText(file) }
            files.readResult = FileResult.Ok(name = "people.vcf", path = picked.absolutePath, mime = "text/x-vcard")
        }
        model =
            IntakeViewModel(
                source = source(this),
                ops = ops,
                repo = repo,
                book = book,
                files = files,
                clipboard = clipboard,
                trash = trash,
                scope = scope,
                stagingDir = staging,
            )
    }

    fun stateWhere(predicate: (IntakeUiState) -> Boolean): IntakeUiState =
        runBlocking { withTimeout(TIMEOUT_MS) { model.state.first(predicate) } }

    fun preview(): IntakeUiState = stateWhere { it.phase == IntakePhase.Preview }

    fun effect(): IntakeEffect = runBlocking { withTimeout(TIMEOUT_MS) { model.effects.first() } }

    /** Every contact of the provider now. */
    fun contacts(): List<ContactDraft> = runBlocking { repo.full().first() }

    fun stop() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    companion object {
        const val TIMEOUT_MS = 15_000L

        fun file(uri: String = "content://x/people.vcf") = { _: IntakeRig -> IntakeSource.File(uri) }

        val paste = { _: IntakeRig -> IntakeSource.Paste }
    }
}

internal fun uriOf(text: String): Uri = Uri.parse(text)
