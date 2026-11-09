package dev.rovsti.platform.contacts

import dev.rovsti.core.model.contacts.ContactDraft
import dev.rovsti.core.model.contacts.DraftField
import dev.rovsti.core.model.contacts.FieldKind
import dev.rovsti.core.model.contacts.Group
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class FakeContactsRepositoryTest {
    private fun signatures(type: Class<*>) =
        type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge }
            .map { "${it.name}/${it.parameterTypes.size}" }
            .toSet()

    @Test
    fun theFakeImplementsEveryMethodOfTheInterface() {
        val wanted = signatures(ContactsRepository::class.java)
        assertTrue("interface has methods", wanted.size >= 20)
        // Every interface method is declared by the fake itself, not left to a default body.
        assertEquals(emptySet<String>(), wanted - signatures(FakeContactsRepository::class.java))
        assertTrue(ContactsRepository::class.java.isAssignableFrom(FakeContactsRepository::class.java))
    }

    @Test
    fun aWriteReEmitsTheReads() =
        runTest {
            val fake = FakeContactsRepository(groups = listOf(Group(id = 1, title = "Family")))
            val draft =
                ContactDraft(
                    given = "Ada",
                    family = "Lovelace",
                    numbers = listOf(DraftField("0151 1", FieldKind.Mobile)),
                )
            val id = (fake.save(draft) as ContactResult.Ok).value
            assertEquals(listOf("Ada Lovelace"), fake.all().first().map { it.displayName })
            assertTrue(fake.favorites().first().isEmpty())
            fake.setStarred(id, true)
            assertEquals(listOf(id), fake.favorites().first().map { it.id })
            val moved = fake.setMembers(1, add = listOf(id, 99), remove = emptyList()) as ContactResult.Ok
            assertEquals(1, moved.value.added)
            assertEquals(1, moved.value.skipped)
            assertEquals(
                listOf(id),
                fake
                    .groups()
                    .first()
                    .single()
                    .members,
            )
            fake.delete(id)
            assertTrue(fake.all().first().isEmpty())
            assertEquals(ContactResult.failed(ContactResult.Reason.NotFound), fake.delete(id))
        }
}
