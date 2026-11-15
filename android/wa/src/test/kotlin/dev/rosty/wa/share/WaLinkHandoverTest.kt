package dev.rosty.wa.share

import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.StoredLink
import dev.rosty.wa.FakeWaGateway
import dev.rosty.wa.WaLinkStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The asking side of the link hand-over with fakes: who is asked, whose answer is taken, what unlinking leaves. */
class WaLinkHandoverTest {
    private val messages = "dev.rosty.messages"
    private val phone = "dev.rosty.phone"
    private val contacts = "dev.rosty.contacts"
    private val link = StoredLink("https://wa.example.com", "inst1", "rwk_" + "A".repeat(43))
    private val other = StoredLink("https://wa.example.com", "inst2", "rwk_" + "B".repeat(43))

    private val gw = FakeWaGateway()
    private val store = WaLinkStore(gw.ops)
    private val marks = WaLinkMarks.InMemory()
    private val answers = mutableMapOf<String, SiblingAnswer>()
    private val asked = mutableListOf<String>()
    private val adopted = mutableListOf<StoredLink>()

    /** What the store answers to a link it is asked to keep; a refusal writes nothing. */
    private var refuse: (StoredLink) -> Boolean = { false }

    private fun handover(siblings: List<String> = listOf(messages, contacts)) =
        WaLinkHandover(
            store = store,
            siblings = siblings,
            source = { pkg ->
                asked += pkg
                answers[pkg] ?: SiblingAnswer.NotInstalled
            },
            marks = marks,
            adopt = { taken ->
                if (refuse(taken)) {
                    Outcome.Err(RustError.Invalid("bad link"))
                } else {
                    adopted += taken
                    gw.link = taken
                    Outcome.Ok(Unit)
                }
            },
        )

    @Test
    fun anAppWithALinkAsksNobody() =
        runTest {
            gw.link = other
            answers[messages] = SiblingAnswer.Link(link)
            assertEquals(WaLinkHandover.Result.AlreadyLinked, handover().adoptIfUnlinked())
            assertTrue(asked.isEmpty())
            assertEquals(other, gw.link)
        }

    @Test
    fun noSiblingInstalledLeavesTheAppUnlinked() =
        runTest {
            assertEquals(WaLinkHandover.Result.Nothing, handover().adoptIfUnlinked())
            assertEquals(listOf(messages, contacts), asked)
            assertNull(gw.link)
            assertNull(marks.adopted)
        }

    @Test
    fun aSiblingWithoutALinkGivesNothing() =
        runTest {
            answers[messages] = SiblingAnswer.NoLink
            answers[contacts] = SiblingAnswer.NoLink
            val share = handover()
            assertEquals(WaLinkHandover.Result.Nothing, share.adoptIfUnlinked())
            assertNull(share.offer())
            assertTrue(adopted.isEmpty())
        }

    @Test
    fun theLinkOfASiblingIsAdoptedAsIfItHadBeenPasted() =
        runTest {
            answers[contacts] = SiblingAnswer.Link(link)
            val share = handover()
            assertEquals(WaLinkHandover.Result.Adopted(contacts), share.adoptIfUnlinked())
            assertEquals(listOf(link), adopted)
            assertEquals(AdoptedLink("inst1", contacts), marks.adopted)
            assertEquals(contacts, share.adoptedFrom())
            // Asked again (the link screen opens): this app has a link now, nobody is asked.
            asked.clear()
            assertEquals(WaLinkHandover.Result.AlreadyLinked, share.adoptIfUnlinked())
            assertTrue(asked.isEmpty())
        }

    @Test
    fun ofSeveralSiblingsTheFirstValidAnswerWins() =
        runTest {
            answers[messages] = SiblingAnswer.Link(link)
            answers[phone] = SiblingAnswer.Link(other)
            assertEquals(
                WaLinkHandover.Result.Adopted(messages),
                handover(listOf(messages, phone, contacts)).adoptIfUnlinked(),
            )
            assertEquals(listOf(messages), asked)
            assertEquals(link, gw.link)
        }

    @Test
    fun wrongDataAndUntrustedAppsAreSkipped() =
        runTest {
            answers[messages] = SiblingAnswer.Invalid
            answers[phone] = SiblingAnswer.Untrusted
            // A link that looks like one only at first sight is not handed to the store.
            answers[contacts] = SiblingAnswer.Link(StoredLink("ftp://wa.example.com", "inst1", "key"))
            val share = handover(listOf(messages, phone, contacts))
            assertEquals(WaLinkHandover.Result.Nothing, share.adoptIfUnlinked())
            assertTrue(adopted.isEmpty())
            assertNull(gw.link)

            for (bad in listOf(
                StoredLink("https://wa.example.com", "", "key"),
                StoredLink("https://wa.example.com", "inst1", ""),
                StoredLink("https://", "inst1", "key"),
                StoredLink("https://wa.example.com", "inst 1", "key"),
                StoredLink("https://wa.example.com", "inst1", "key\n"),
            )) {
                assertFalse("$bad", WaLinkShare.usable(bad))
            }
            assertTrue(WaLinkShare.usable(link))
            assertTrue(WaLinkShare.usable(StoredLink("http://192.168.1.5:8080", "inst1", "key")))
        }

    @Test
    fun aLinkTheStoreRefusesIsSkippedForTheNextSibling() =
        runTest {
            answers[messages] = SiblingAnswer.Link(link)
            answers[contacts] = SiblingAnswer.Link(other)
            refuse = { it == link }
            assertEquals(WaLinkHandover.Result.Adopted(contacts), handover().adoptIfUnlinked())
            assertEquals(other, gw.link)
            assertEquals(AdoptedLink("inst2", contacts), marks.adopted)
        }

    @Test
    fun aDamagedLinkFileIsNotWrittenOver() =
        runTest {
            gw.core.on("wa.link_read") { Outcome.Err(RustError.Invalid("damaged")) }
            answers[messages] = SiblingAnswer.Link(link)
            assertEquals(WaLinkHandover.Result.AlreadyLinked, handover().adoptIfUnlinked())
            assertTrue(asked.isEmpty())
        }

    @Test
    fun unlinkingHereKeepsTheSiblingsLinkAndDoesNotTakeItBackUnasked() =
        runTest {
            answers[messages] = SiblingAnswer.Link(link)
            val share = handover()
            share.adoptIfUnlinked()

            // The user removes the link in this app: the sibling still answers with its own.
            gw.link = null
            share.onForget(link)
            assertNull(marks.adopted)
            assertNull(share.adoptedFrom())
            assertEquals(WaLinkHandover.Result.Declined(messages), share.adoptIfUnlinked())
            assertNull(gw.link)
            // The link screen can offer it, and the user's own tap takes it.
            assertEquals(messages, share.offer())
            assertEquals(WaLinkHandover.Result.Adopted(messages), share.adoptIfUnlinked(explicit = true))
            assertEquals(link, gw.link)
            assertNull(marks.declined)
        }

    @Test
    fun aSiblingWithAnotherLinkIsAdoptedAfterAnUnlink() =
        runTest {
            val share = handover()
            share.onForget(link)
            // The sibling was linked again since (a new key, another instance): that is a new link, not the removed one.
            answers[messages] = SiblingAnswer.Link(link.copy(key = "rwk_" + "C".repeat(43)))
            assertEquals(WaLinkHandover.Result.Adopted(messages), share.adoptIfUnlinked())
        }

    @Test
    fun theNoteOfWhereTheLinkCameFromEndsWithAnotherLink() =
        runTest {
            answers[messages] = SiblingAnswer.Link(link)
            val share = handover()
            share.adoptIfUnlinked()
            assertEquals(messages, share.adoptedFrom())
            // Linked anew by QR in this app: another instance, not the sibling's any more.
            gw.link = other
            assertNull(share.adoptedFrom())
        }

    @Test
    fun theFingerprintTellsLinksApartWithoutHoldingTheKey() {
        val mark = WaLinkHandover.fingerprint(link)
        assertEquals(mark, WaLinkHandover.fingerprint(link.copy(gatewayUrl = "https://other.example.com")))
        assertNotEquals(mark, WaLinkHandover.fingerprint(other))
        assertNotEquals(mark, WaLinkHandover.fingerprint(link.copy(key = link.key + "x")))
        assertFalse(link.key in mark)
        assertEquals(64, mark.length)
    }

    @Test
    fun siblingsAreEveryRostyAppButTheAppItself() {
        assertEquals(listOf(phone, contacts), WaLinkShare.siblingsOf(messages))
        assertEquals(listOf(messages, contacts), WaLinkShare.siblingsOf(phone))
        assertEquals(WaLinkShare.PACKAGES, WaLinkShare.siblingsOf("dev.rosty.stops"))
    }
}
