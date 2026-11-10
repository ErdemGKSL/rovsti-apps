package dev.rovsti.platform.contacts

import dev.rovsti.core.model.contacts.Account
import dev.rovsti.core.model.contacts.ContactDraft
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveAccountTest {
    private val phone = Account()
    private val google = Account("com.google|ada@example.org", "ada@example.org", "com.google")
    private val work = Account("com.google|ada@work.example", "ada@work.example", "com.google")
    private val sim = Account("com.android.contacts.sim|SIM1", "SIM1", "com.android.contacts.sim")
    private val samsungSim = Account("vnd.sec.contact.sim2|primary.sim2.account_name", "SIM2", "vnd.sec.contact.sim2")

    @Test
    fun theSaveToAccountWinsWhileItIsOnThePhone() {
        val accounts = listOf(phone, google, work)
        val counts = mapOf("" to 3, google.key to 200, work.key to 5)
        assertEquals(work.key, SaveAccount.choose(work.key, accounts, counts))
    }

    @Test
    fun aSaveToAccountThatIsGoneFallsBackToTheAccountWithTheMostContacts() {
        val accounts = listOf(phone, google, work)
        val counts = mapOf("" to 3, google.key to 7, work.key to 120)
        assertEquals(work.key, SaveAccount.choose("com.google|removed@example.org", accounts, counts))
    }

    @Test
    fun anUnsetSaveToTakesTheAccountWithTheMostContactsNotThePhone() {
        val accounts = listOf(phone, google)
        assertEquals(google.key, SaveAccount.choose("", accounts, mapOf("" to 400, google.key to 2)))
    }

    @Test
    fun simAccountsAreNeverChosenByCount() {
        val accounts = listOf(phone, sim, google, samsungSim)
        val counts = mapOf("" to 10, sim.key to 900, samsungSim.key to 800, google.key to 4)
        assertEquals(google.key, SaveAccount.choose("", accounts, counts))
        assertEquals("only SIM contacts: the phone", "", SaveAccount.choose("", listOf(phone, sim), counts))
    }

    @Test
    fun theFirstListedAccountWinsATie() {
        val accounts = listOf(phone, work, google)
        assertEquals(work.key, SaveAccount.choose("", accounts, mapOf(google.key to 9, work.key to 9)))
    }

    @Test
    fun anAccountWithoutContactsOrUnreadableCountsLeavesThePhone() {
        assertEquals("", SaveAccount.choose("", listOf(phone, google), mapOf(google.key to 0)))
        assertEquals("", SaveAccount.choose("", listOf(phone, google), emptyMap()))
    }

    @Test
    fun noAccountsAtAllIsThePhone() {
        assertEquals("", SaveAccount.choose("", emptyList(), emptyMap()))
        assertEquals("", SaveAccount.choose(google.key, emptyList(), mapOf(google.key to 3)))
    }

    @Test
    fun simAccountsAreRecognisedByTheirType() {
        assertTrue(SaveAccount.isSim(sim))
        assertTrue(SaveAccount.isSim(samsungSim))
        assertTrue(SaveAccount.isSim(Account("com.android.contacts.usim|x", "x", "")))
        assertFalse(SaveAccount.isSim(google))
        assertFalse(SaveAccount.isSim(Account("com.example.simple|x", "x", "com.example.simple")))
        assertFalse(SaveAccount.isSim(phone))
    }

    @Test
    fun theRepositoryHelperReadsTheAccountsAndTheCounts() =
        runTest {
            val fake =
                FakeContactsRepository(
                    drafts =
                        listOf(
                            ContactDraft(id = 1, given = "A", account = google.key),
                            ContactDraft(id = 2, given = "B", account = google.key),
                            ContactDraft(id = 3, given = "C"),
                        ),
                    accounts = listOf(phone, google),
                )
            assertEquals(google.key, fake.saveAccount(""))
            assertEquals("", FakeContactsRepository().saveAccount(""))
            fake.failWith = ContactResult.Reason.Permission
            assertEquals("unreadable accounts: the phone", "", fake.saveAccount(google.key))
        }
}
