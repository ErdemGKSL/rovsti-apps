package dev.rosty.wa.share

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.StoredLink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The asking side of the link hand-over with fakes (`plans/wa-vault.md` 13.3): only the person's tap takes a link, only
 * from a sibling that gives one (its vault unlocked), and only into this app's vault setup.
 */
class WaLinkHandoverTest {
    private val messages = "dev.rosty.messages"
    private val phone = "dev.rosty.phone"
    private val contacts = "dev.rosty.contacts"
    private val link = StoredLink("https://rosty.erdemdev.tr", "inst1", "rwk_" + "A".repeat(43))
    private val other = StoredLink("https://rosty.erdemdev.tr", "inst2", "rwk_" + "B".repeat(43))

    private var held: HeldLink = HeldLink.None
    private val marks = WaLinkMarks.InMemory()
    private val answers = mutableMapOf<String, SiblingAnswer>()
    private val offering = mutableSetOf<String>()
    private val asked = mutableListOf<String>()
    private val adopted = mutableListOf<StoredLink>()

    /** What this app's vault answers to a link it is asked to take; a refusal keeps nothing. */
    private var refuse: (StoredLink) -> Boolean = { false }

    private fun handover(siblings: List<String> = listOf(messages, contacts)) =
        WaLinkHandover(
            held = { held },
            siblings = siblings,
            source =
                object : SiblingLinks {
                    override suspend fun offers(packageName: String): Boolean = packageName in offering

                    override suspend fun ask(packageName: String): SiblingAnswer {
                        asked += packageName
                        return answers[packageName] ?: SiblingAnswer.NotInstalled
                    }
                },
            marks = marks,
            adopt = { taken ->
                if (refuse(taken)) {
                    Outcome.Err(RustError.Invalid("bad link"))
                } else {
                    adopted += taken
                    held = HeldLink.Held(null)
                    Outcome.Ok(Unit)
                }
            },
        )

    @Test
    fun anAppWithALinkOrAVaultAsksNobody() =
        runTest {
            answers[messages] = SiblingAnswer.Link(link)
            offering += messages
            for (mine in listOf(HeldLink.Held("inst2"), HeldLink.Held(null), HeldLink.Unreadable)) {
                held = mine
                assertEquals(WaLinkHandover.Result.AlreadyLinked, handover().take())
                assertNull(handover().offer())
            }
            assertTrue(asked.isEmpty())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun aLockedOrUnlinkedSiblingGivesNothing() =
        runTest {
            // A sibling whose vault is locked answers no row (`SiblingAnswer.NoLink`) and offers nothing.
            answers[messages] = SiblingAnswer.NoLink
            answers[contacts] = SiblingAnswer.NoLink
            val share = handover()
            assertNull(share.offer())
            assertEquals(WaLinkHandover.Result.Nothing, share.take())
            assertTrue(adopted.isEmpty())
            assertEquals(HeldLink.None, held)
        }

    @Test
    fun theOfferNamesTheFirstUnlockedSiblingWithoutReadingAKey() =
        runTest {
            offering += contacts
            assertEquals(contacts, handover().offer())
            assertTrue("the link itself was not read", asked.isEmpty())
        }

    @Test
    fun theTapTakesTheLinkIntoThisAppsVault() =
        runTest {
            answers[contacts] = SiblingAnswer.Link(link)
            val share = handover()
            assertEquals(WaLinkHandover.Result.Adopted(contacts), share.take())
            assertEquals(listOf(link), adopted)
            assertEquals(AdoptedLink("inst1", contacts), marks.adopted)
            // The vault is locked or still being set up: the instance is sealed, the note stands.
            assertEquals(contacts, share.adoptedFrom())
            held = HeldLink.Held("inst1")
            assertEquals(contacts, share.adoptedFrom())
            // Asked again: this app has its vault now, nobody is asked.
            asked.clear()
            assertEquals(WaLinkHandover.Result.AlreadyLinked, share.take())
            assertTrue(asked.isEmpty())
        }

    @Test
    fun ofSeveralSiblingsTheFirstValidAnswerWins() =
        runTest {
            answers[messages] = SiblingAnswer.Link(link)
            answers[phone] = SiblingAnswer.Link(other)
            assertEquals(WaLinkHandover.Result.Adopted(messages), handover(listOf(messages, phone, contacts)).take())
            assertEquals(listOf(messages), asked)
            assertEquals(listOf(link), adopted)
        }

    @Test
    fun wrongDataAndUntrustedAppsAreSkipped() =
        runTest {
            answers[messages] = SiblingAnswer.Invalid
            answers[phone] = SiblingAnswer.Untrusted
            // A link that looks like one only at first sight is not handed to the vault.
            answers[contacts] = SiblingAnswer.Link(StoredLink("ftp://rosty.erdemdev.tr", "inst1", "key"))
            assertEquals(WaLinkHandover.Result.Nothing, handover(listOf(messages, phone, contacts)).take())
            assertTrue(adopted.isEmpty())

            for (bad in listOf(
                StoredLink("https://rosty.erdemdev.tr", "", "key"),
                StoredLink("https://rosty.erdemdev.tr", "inst1", ""),
                StoredLink("https://", "inst1", "key"),
                StoredLink("https://rosty.erdemdev.tr", "inst 1", "key"),
                StoredLink("https://rosty.erdemdev.tr", "inst1", "key\n"),
            )) {
                assertFalse("$bad", WaLinkShare.usable(bad))
            }
            assertTrue(WaLinkShare.usable(link))
        }

    @Test
    fun aLinkTheVaultRefusesIsSkippedForTheNextSibling() =
        runTest {
            answers[messages] = SiblingAnswer.Link(link)
            answers[contacts] = SiblingAnswer.Link(other)
            refuse = { it == link }
            assertEquals(WaLinkHandover.Result.Adopted(contacts), handover().take())
            assertEquals(listOf(other), adopted)
            assertEquals(AdoptedLink("inst2", contacts), marks.adopted)
        }

    @Test
    fun unlinkingHereForgetsWhereTheLinkCameFrom() =
        runTest {
            answers[messages] = SiblingAnswer.Link(link)
            val share = handover()
            share.take()
            held = HeldLink.None
            share.onForget()
            assertNull(marks.adopted)
            assertNull(share.adoptedFrom())
        }

    @Test
    fun theNoteOfWhereTheLinkCameFromEndsWithAnotherLink() =
        runTest {
            answers[messages] = SiblingAnswer.Link(link)
            val share = handover()
            share.take()
            // Linked anew in this app: another instance, not the sibling's any more.
            held = HeldLink.Held("inst2")
            assertNull(share.adoptedFrom())
        }

    @Test
    fun siblingsAreEveryRostyAppButTheAppItself() {
        val stops = "dev.rosty.stops"
        assertEquals(listOf(phone, contacts, stops), WaLinkShare.siblingsOf(messages))
        assertEquals(listOf(messages, contacts, stops), WaLinkShare.siblingsOf(phone))
        // Round 7a: Stops takes part too.
        assertEquals(listOf(messages, phone, contacts), WaLinkShare.siblingsOf(stops))
        assertEquals(WaLinkShare.PACKAGES, WaLinkShare.siblingsOf("dev.rosty.other"))
    }

    @Test
    fun theLinkScreenTakesALinkByItselfUntilThePersonRemovesOne() =
        runTest {
            answers[messages] = SiblingAnswer.Link(link)
            val share = handover()
            assertEquals(WaLinkHandover.Result.Adopted(messages), share.takeAutomatically())
            held = HeldLink.None
            share.onForget()
            assertTrue(marks.autoOff)
            assertEquals(
                "after an unlink only the tap takes it",
                WaLinkHandover.Result.Nothing,
                share.takeAutomatically(),
            )
            assertEquals(WaLinkHandover.Result.Adopted(messages), share.take())
        }
}
