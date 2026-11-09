package dev.rovsti.contacts.data

import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.contacts.Confirm
import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.TrashConfirmRequest
import dev.rovsti.core.model.contacts.TrashDeleteAnswer
import dev.rovsti.core.model.contacts.TrashDeleteRequest
import dev.rovsti.core.model.contacts.TrashPushAnswer
import dev.rovsti.core.model.contacts.TrashPushRequest
import dev.rovsti.core.model.search.NameFormat
import dev.rovsti.core.ops.NativeContactsOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactsStoreTest {
    private val file = FakePrefsFile()
    private val core = FakeRustCore().also { file.installOn(it) }
    private val store = ContactsStore(NativeContactsOps(core), dir = "/data", clock = { NOW })

    @Test fun theFirstReadLoadsTheFileOnce() =
        runBlocking {
            file.disk =
                file.disk.copy(
                    favoritesOrder = listOf("k-a", "", "k-a", "k-b"),
                    emergency = listOf("k-e"),
                    myCard = "k-me",
                )
            val loaded = (store.load() as Outcome.Ok).value
            store.load()
            assertEquals(1, file.loads.get())
            assertEquals("k-me", loaded.myCard)
            assertEquals("blank and repeated keys are dropped", listOf("k-a", "k-b"), store.favoritesOrder.value)
            assertEquals(listOf("k-e"), store.emergency.value)
            assertEquals("k-me", store.myCard)
        }

    @Test fun anUpdateWritesTheFileAndPublishesTheResult() =
        runBlocking {
            store.update { it.copy(nameFormat = NameFormat.LastFirst, viewed = listOf("k-x")) }
            assertEquals(NameFormat.LastFirst, file.disk.nameFormat)
            assertEquals(NameFormat.LastFirst, store.prefs.value.nameFormat)
            assertEquals(listOf("k-x"), store.viewed)
            assertEquals(1, file.saves.get())
        }

    @Test fun anUpdateThatChangesNothingWritesNothing() =
        runBlocking {
            store.update { it }
            assertEquals(0, file.saves.get())
        }

    @Test fun aFailedWriteLeavesTheHeldValuesAlone() =
        runBlocking {
            file.failSave = "disk full"
            val out = store.update { it.copy(emergency = listOf("k-e")) }
            assertTrue(out is Outcome.Err)
            assertEquals(emptyList<String>(), store.prefs.value.emergency)
            assertEquals(emptyList<String>(), file.disk.emergency)
        }

    @Test fun concurrentUpdatesAreSerialised() =
        runBlocking {
            val n = 64
            (0 until n)
                .map { i -> async(Dispatchers.Default) { store.update { it.copy(emergency = it.emergency + "k$i") } } }
                .awaitAll()
            assertEquals(
                "no update lost",
                n,
                file.disk.emergency
                    .toSet()
                    .size,
            )
            assertEquals(n, store.prefs.value.emergency.size)
            assertEquals(n, file.saves.get())
            assertEquals("the derived list follows", n, store.emergency.value.size)
        }

    @Test fun theTrashWrappersCarryTheDirAndTheClock() =
        runBlocking {
            core.onTyped("contacts.trash_push", TrashPushRequest.serializer(), TrashPushAnswer.serializer()) {
                Outcome.Ok(TrashPushAnswer(keys = listOf(1), count = 1, notice = "Moved to Trash"))
            }
            core.onTyped("contacts.trash_delete", TrashDeleteRequest.serializer(), TrashDeleteAnswer.serializer()) {
                Outcome.Ok(TrashDeleteAnswer(removed = 3))
            }
            core.onTyped("contacts.trash_confirm", TrashConfirmRequest.serializer(), Confirm.serializer()) {
                Outcome.Ok(Confirm(it.kind, "${it.count}"))
            }
            store.trashPush(listOf(ContactDraft(given = "Ada")))
            store.trashDelete(listOf(1, 2, 3))
            store.trashEmpty()
            val confirm = (store.trashConfirm(count = 2, forever = true) as Outcome.Ok).value
            assertEquals("forever", confirm.title)

            val push =
                core.calls
                    .first { it.op == "contacts.trash_push" }
                    .request.jsonObject
            assertEquals("/data", push["dir"]!!.jsonPrimitive.content)
            assertEquals(NOW, push["now_ms"]!!.jsonPrimitive.content.toLong())
            val deletes = core.calls.filter { it.op == "contacts.trash_delete" }.map { it.request.jsonObject }
            assertEquals(listOf(false, true), deletes.map { "all" in it })
        }

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}
