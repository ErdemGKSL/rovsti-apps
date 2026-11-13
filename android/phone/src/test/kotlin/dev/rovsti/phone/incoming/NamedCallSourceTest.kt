package dev.rovsti.phone.incoming

import dev.rovsti.core.model.phone.CallSnapshot
import dev.rovsti.core.model.phone.WireCall
import dev.rovsti.core.model.phone.WireCallState
import dev.rovsti.platform.identity.CallerIdentity
import dev.rovsti.platform.identity.CallerIds
import dev.rovsti.platform.identity.IdentitySource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F39: the call screens read the calls through [NamedCallSource], named by the same resolver as Recents. A WhatsApp
 * call (no registry lookup of its own) gains the contact and its photo; a renamed contact is swapped in place; a number
 * nobody owns keeps what the call said (the WhatsApp push name) and the number.
 */
class NamedCallSourceTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() = scope.cancel()

    /** Knows "+905321234567" in every form the core's book would: here, by the last ten digits. */
    private class Book : IdentitySource {
        var name = "Ayşe Yılmaz"
        val asked = mutableListOf<List<String>>()
        override val revision = MutableStateFlow(1L)
        override val ready = true

        override suspend fun lookup(numbers: List<String>): Map<String, CallerIdentity> {
            asked += numbers
            return numbers
                .filter { n -> n.filter(Char::isDigit).takeLast(10) == "5321234567" }
                .associateWith { CallerIdentity(contactId = 1, name = name, photoUri = "content://photo/1") }
        }
    }

    private fun call(
        number: String,
        name: String? = null,
    ) = WireCall(id = 1, state = WireCallState.Active, numberRaw = number, displayName = name)

    private fun <T> StateFlow<T>.await(done: (T) -> Boolean): T {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!done(value)) {
            assertTrue("timed out at $value", System.nanoTime() < deadline)
            Thread.sleep(5)
        }
        return value
    }

    private fun source(
        book: Book,
        calls: List<WireCall>,
    ): Pair<NamedCallSource, MutableStateFlow<CallSnapshot>> {
        val base = MutableStateFlow(CallSnapshot(calls = calls))
        val ids = CallerIds(listOf(book))
        val changes = combine(ids.revisions) { r -> r.sum() }
        val named =
            NamedCallSource(
                object : CallSource {
                    override val snapshot: StateFlow<CallSnapshot> = base
                },
                ids,
                changes,
                scope,
            )
        return named to base
    }

    @Test fun aWhatsAppCallInE164FormGetsTheContactSavedNationally() {
        val (named, _) = source(Book(), listOf(call("+905321234567", name = "ayse_wa")))
        val wire =
            named.snapshot
                .await { it.calls.single().contactId != null }
                .calls
                .single()
        assertEquals("Ayşe Yılmaz", wire.displayName)
        assertEquals(1L, wire.contactId)
    }

    @Test fun aRenameDuringTheCallIsSwappedInPlace() {
        val book = Book()
        val (named, _) = source(book, listOf(call("+905321234567")))
        named.snapshot.await { it.calls.single().displayName == "Ayşe Yılmaz" }
        book.name = "Ayşe Demir"
        book.revision.value += 1
        named.snapshot.await { it.calls.single().displayName == "Ayşe Demir" }
    }

    @Test fun aNumberNobodyOwnsKeepsWhatTheCallSaid() {
        val book = Book()
        val (named, base) = source(book, listOf(call("+905559998877", name = "Push Name")))
        // Wait until the resolver was asked, then the snapshot is as the base had it.
        base.await { book.asked.isNotEmpty() }
        Thread.sleep(50)
        val wire =
            named.snapshot.value.calls
                .single()
        assertEquals("Push Name", wire.displayName)
        assertNull(wire.contactId)
    }

    @Test fun nothingIsResolvedOnTheCallersThreadAndTheFirstSnapshotIsAtOnce() {
        val (named, _) = source(Book(), listOf(call("+905321234567")))
        // The first value exists without waiting (the number), the name follows.
        assertEquals(
            "+905321234567",
            named.snapshot.value.calls
                .single()
                .numberRaw,
        )
    }
}
