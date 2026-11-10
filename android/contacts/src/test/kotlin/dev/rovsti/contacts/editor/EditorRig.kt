package dev.rovsti.contacts.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import dev.rovsti.contacts.ContactsRoots
import dev.rovsti.contacts.ContactsRoute
import dev.rovsti.contacts.ContactsTabs
import dev.rovsti.contacts.DraftSeed
import dev.rovsti.contacts.card.SampleCards
import dev.rovsti.contacts.chromeFor
import dev.rovsti.contacts.contactsTabItems
import dev.rovsti.contacts.data.ContactsStore
import dev.rovsti.contacts.en
import dev.rovsti.contacts.photo.ContactPhotoModel
import dev.rovsti.contacts.photo.WaPhotoResult
import dev.rovsti.contacts.trash.ContactDeletion
import dev.rovsti.contacts.trash.ContactTrash
import dev.rovsti.core.Outcome
import dev.rovsti.core.Rust
import dev.rovsti.core.model.contacts.Account
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.design.overlay.OverlayController
import dev.rovsti.design.text.UiText
import dev.rovsti.design.theme.RovstiText
import dev.rovsti.design.transition.TabNavigator
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.files.FakeFilesRepository
import dev.rovsti.shell.RovstiShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.nio.file.Files

internal val GOOGLE = Account("com.google|ayse@example.org", "ayse@example.org", "com.google")

/**
 * The editor's model on the REAL Rust core (the host `librovsti_ffi`), with the in-memory provider (a phone account and
 * a Google account; groups Family on the phone, Work on Google). The test is skipped when the host library is absent.
 */
internal class EditorRig(
    val id: Long? = null,
    seed: DraftSeed? = null,
    drafts: List<ContactDraft> = SampleCards.all,
    val saved: SavedStateHandle = SavedStateHandle(),
    accounts: List<Account> = listOf(Account(), GOOGLE),
    saveTo: String = "",
    storedPhoto: String? = null,
    whatsAppJid: String? = null,
) {
    val scope = CoroutineScope(Dispatchers.Default)
    val repo =
        FakeContactsRepository(
            drafts,
            listOf(Group(10, "Family"), Group(11, "Work", account = GOOGLE.key), Group(12, "Friends")),
            accounts,
        )
    val store: ContactsStore
    val model: ContactEditorViewModel

    /** The stored photo and the WhatsApp JID the editor sees (the book and the marks in the app). */
    val photoUri = MutableStateFlow(storedPhoto)
    val waJid = MutableStateFlow(whatsAppJid)
    val files = FakeFilesRepository()

    /** What "Use WhatsApp Photo" fetches. */
    var waPhoto: WaPhotoResult = WaPhotoResult.NoPicture
    val extras: EditorExtras

    init {
        val lib = File(System.getProperty("rovsti.hostLib").orEmpty())
        assumeTrue(
            "host librovsti_ffi not built (run :core-bridge:cargoBuildHost)",
            lib.isDirectory && lib.listFiles().orEmpty().any { it.name.contains("rovsti_ffi") },
        )
        val root = Files.createTempDirectory("rovsti-editor").toFile().apply { deleteOnExit() }
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
        if (saveTo.isNotEmpty()) runBlocking { store.update { it.copy(saveTo = saveTo) } }
        model = ContactEditorViewModel(id, seed, saved, repo, ops, store, scope, clock = { SampleCards.NOW })
        val photo =
            ContactPhotoModel(
                contactId = id,
                repo = repo,
                ops = ops,
                files = files,
                waPhotos = { _, _ -> waPhoto },
                cacheDir = File(root, "cache"),
                clock = { SampleCards.NOW },
                scope = scope,
            )
        extras =
            EditorExtras(
                id,
                photo,
                photoUri,
                waJid,
                ContactDeletion(ContactTrash(repo, store), ops, scope, RuntimeEnvironment.getApplication()),
            )
    }

    fun stop() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    /** The first state that satisfies [predicate]; fails after [TIMEOUT_MS]. */
    fun stateWhere(predicate: (EditorUiState) -> Boolean): EditorUiState =
        runBlocking { withTimeout(TIMEOUT_MS) { model.state.first(predicate) } }

    fun ready(): EditorUiState = stateWhere { it.phase == EditorPhase.Ready && it.accounts.isNotEmpty() }

    fun effect(): EditorEffect = runBlocking { withTimeout(TIMEOUT_MS) { model.effects.first() } }

    /** The key of row [index] of [list]. */
    fun key(
        list: EditorList,
        index: Int,
    ): Long = stateWhere { it.keys.of(list).size > index }.keys.of(list)[index]

    /** Every contact the provider holds. */
    fun contacts(): List<ContactDraft> = runBlocking { repo.full().first() }

    companion object {
        const val TIMEOUT_MS = 15_000L
    }
}

/** A stand-in page under the editor sheet, so the sheet has a page to step back. */
@Composable
internal fun EditorHost(overlays: OverlayController) {
    val tabs = contactsTabItems("Contacts", "Favorites", "Groups", "Settings")
    val navigator = remember { TabNavigator(ContactsTabs, ContactsRoots) }
    RovstiShell(
        navigator = navigator,
        tabs = tabs,
        chromeFor = { chromeFor(it, UiText::en) },
        overlays = overlays,
        modifier = Modifier.fillMaxSize(),
    ) {
        entry<ContactsRoute.ContactsList> { Box(Modifier.fillMaxSize()) { RovstiText("Contacts") } }
        entry<ContactsRoute.Favorites> {}
        entry<ContactsRoute.Groups> {}
        entry<ContactsRoute.Settings> {}
    }
}
