package dev.rovsti.contacts.data

import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DeepIndexNewRequest
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.HandleAnswer
import dev.rovsti.core.model.search.IndexAnswer
import dev.rovsti.core.model.search.IndexNewRequest
import dev.rovsti.core.model.search.IndexReplaceRequest
import dev.rovsti.core.ops.NativeContactsOps
import dev.rovsti.core.ops.NativeSearchOps
import dev.rovsti.platform.contacts.FakeContactsRepository
import dev.rovsti.platform.system.permissions.PermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BookRepositoryTest {
    private val ada =
        ContactDraft(id = 1, given = "Ada", numbers = listOf(DraftField("+44 20 7946 0000", FieldKind.Mobile)))
    private val bob = ContactDraft(id = 2, given = "Bob")

    private val core = FakeRustCore()
    private val released = mutableListOf<Long>()
    private val indexNews = mutableListOf<Int>()
    private val replaces = mutableListOf<Pair<Long, Int>>()
    private val deepNews = mutableListOf<Int>()
    private var nextHandle = 100L

    init {
        core.onTyped("search.index_new", IndexNewRequest.serializer(), IndexAnswer.serializer()) { req ->
            indexNews += req.contacts.size
            Outcome.Ok(IndexAnswer(nextHandle++, req.contacts.size))
        }
        core.onTyped("search.index_replace", IndexReplaceRequest.serializer(), IndexAnswer.serializer()) { req ->
            replaces += req.handle to req.contacts.size
            Outcome.Ok(IndexAnswer(req.handle, req.contacts.size))
        }
        core.onTyped("contacts.deep_index_new", DeepIndexNewRequest.serializer(), HandleAnswer.serializer()) { req ->
            deepNews += req.drafts.size
            Outcome.Ok(HandleAnswer(nextHandle++, req.drafts.size))
        }
    }

    private fun TestScope.repo(
        fake: FakeContactsRepository,
        permission: MutableStateFlow<PermissionState>,
    ) = BookRepository(
        contacts = fake,
        permission = permission,
        search = NativeSearchOps(core),
        ops = NativeContactsOps(core),
        scope = backgroundScope,
        release = { released += it },
    )

    @Test fun theBookIsLoadingUntilThePermissionIsKnownThenReady() =
        runTest(UnconfinedTestDispatcher()) {
            val permission = MutableStateFlow<PermissionState>(PermissionState.NeverAsked)
            val repo = repo(FakeContactsRepository(listOf(ada, bob)), permission)
            assertEquals("not granted yet: the gate shows its row", Book.NoPermission, repo.book.value)
            permission.value = PermissionState.Granted
            advanceUntilIdle()
            val ready = repo.book.value as Book.Ready
            assertEquals(listOf(1L, 2L), ready.contacts.map { it.id })
            assertEquals(2, ready.drafts.size)
            assertEquals(listOf(2), indexNews)
            assertNotNull(repo.index.value)
            assertEquals(listOf(2), deepNews)
        }

    @Test fun aProviderChangeIsOneIndexReplaceNotARebuild() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeContactsRepository(listOf(ada))
            val repo = repo(fake, MutableStateFlow(PermissionState.Granted))
            val first = repo.index.value!!
            assertEquals(listOf(1), indexNews)
            assertEquals(emptyList<Pair<Long, Int>>(), replaces)

            fake.save(ContactDraft(given = "Bob"))
            advanceUntilIdle()
            assertEquals("one replace for one change", listOf(first.id to 2), replaces)
            assertEquals("the index was not rebuilt", listOf(1), indexNews)
            assertSame(first, repo.index.value)
            assertEquals(2, (repo.book.value as Book.Ready).contacts.size)
            assertTrue("the index is current when the book is published", replaces.isNotEmpty())
        }

    @Test fun theDeepIndexIsRebuiltAndThePreviousHandleClosed() =
        runTest(UnconfinedTestDispatcher()) {
            val fake = FakeContactsRepository(listOf(ada))
            val repo = repo(fake, MutableStateFlow(PermissionState.Granted))
            val firstDeep = repo.deep.value!!
            assertEquals(emptyList<Long>(), released)

            fake.save(ContactDraft(given = "Bob"))
            advanceUntilIdle()
            val secondDeep = repo.deep.value!!
            assertTrue(firstDeep.released)
            assertEquals(listOf(firstDeep.id), released)
            assertTrue(!secondDeep.released)
            assertEquals(listOf(1, 2), deepNews)
        }

    @Test fun revokingThePermissionIsNoPermissionAndClosesBothHandles() =
        runTest(UnconfinedTestDispatcher()) {
            val permission = MutableStateFlow<PermissionState>(PermissionState.Granted)
            val repo = repo(FakeContactsRepository(listOf(ada)), permission)
            val index = repo.index.value!!
            val deep = repo.deep.value!!

            permission.value = PermissionState.RevokedWhileRunning
            advanceUntilIdle()
            assertEquals(Book.NoPermission, repo.book.value)
            assertNull(repo.index.value)
            assertNull(repo.deep.value)
            assertTrue(index.released)
            assertTrue(deep.released)
            assertEquals(setOf(index.id, deep.id), released.toSet())

            permission.value = PermissionState.Granted
            advanceUntilIdle()
            assertTrue(repo.book.value is Book.Ready)
            assertEquals("a fresh index after the grant", listOf(1, 1), indexNews)
        }
}
