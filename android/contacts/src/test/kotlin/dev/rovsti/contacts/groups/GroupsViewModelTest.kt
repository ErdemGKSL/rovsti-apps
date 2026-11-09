package dev.rovsti.contacts.groups

import dev.rovsti.contacts.favorites.FavRig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E4.7: the Groups tab and the group page on the real Rust core: names checked by `groups_title`, subtitles from
 * `groups_labels`, the members to add and remove from `groups_change`, the sentence from `groups_summary`, and the
 * recipients and the file name from `groups_recipients`.
 */
class GroupsViewModelTest {
    private var rig: FavRig? = null
    private var groups: GroupsViewModel? = null

    @After fun stop() {
        rig?.stop()
    }

    private fun rig() = FavRig().also { rig = it }

    private fun FavRig.groupsModel() = GroupsViewModel(book, repo, host.ops).also { groups = it }

    private fun FavRig.page(id: Long) =
        GroupPageViewModel(id, book, repo, host.ops, store, scope, clock = {
            dev.rovsti.contacts.favorites.NOW
        })

    private fun FavRig.groupState(
        predicate: (GroupsUiState) -> Boolean = { true },
        model: GroupsViewModel,
    ) = wait { model.state.first { it.phase == GroupsPhase.Ready && predicate(it) } }

    private fun FavRig.ready(
        model: GroupPageViewModel,
        predicate: (GroupPageUi) -> Boolean = { true },
    ): GroupPageUi =
        wait { model.state.first { it is GroupPageState.Ready && predicate(it.page) } as GroupPageState.Ready }.page

    // ---- the tab

    @Test fun theTabListsEveryGroupWithItsCount() {
        val r = rig()
        val state = r.groupState(model = r.groupsModel())
        assertEquals(listOf("Family", "Work", "Empty"), state.groups.map { it.title })
        assertEquals("3 contacts", state.groups[0].subtitle)
        assertEquals("2 contacts", state.groups[1].subtitle)
        assertTrue(
            state.groups[2].subtitle,
            state.groups[2].subtitle.startsWith("0") || state.groups[2].subtitle.startsWith("No"),
        )
    }

    @Test fun aNewGroupIsCreatedAndOpened() {
        val r = rig()
        val model = r.groupsModel()
        r.groupState(model = model)
        model.onAction(GroupsAction.Create("  Friends "))
        val toast = r.wait { model.effects.first() } as GroupsEffect.Toast
        assertTrue(toast.text, toast.text.startsWith("Group created"))
        val open = r.wait { model.effects.first() } as GroupsEffect.OpenGroup
        val state = r.groupState({ s -> s.groups.any { it.title == "Friends" } }, model)
        assertEquals(open.id, state.groups.first { it.title == "Friends" }.id)
    }

    @Test fun aTakenOrEmptyNameComesBackWithTheCoresWords() {
        val r = rig()
        val model = r.groupsModel()
        r.groupState(model = model)
        model.onAction(GroupsAction.Create("family"))
        val taken = r.wait { model.effects.first() } as GroupsEffect.AskName
        assertEquals("family", taken.initial)
        assertNotNull(taken.error)
        model.onAction(GroupsAction.Create("   "))
        val empty = r.wait { model.effects.first() } as GroupsEffect.AskName
        assertNotNull(empty.error)
        assertEquals("nothing was made", 3, r.groupState(model = model).groups.size)
    }

    @Test fun plusAsksForAName() {
        val r = rig()
        val model = r.groupsModel()
        model.askNewName()
        assertEquals(GroupsEffect.AskName("", null), r.wait { model.effects.first() })
    }

    // ---- the page

    @Test fun theMembersAreTheGroupsPeopleByName() {
        val r = rig()
        val page = r.ready(r.page(10))
        assertEquals("Family", page.title)
        assertEquals(listOf("Ayşe Yılmaz", "Elif Kaya", "Özgür Şahin"), page.members.map { it.name })
        assertEquals("3 contacts", page.subtitle)
        assertTrue(page.canMessage)
        assertTrue("two members have an e-mail", page.canEmail)
        assertEquals(40, page.everyone.size)
        assertNull("one account: no join hint", page.joinHint)
    }

    @Test fun anEmptyGroupHasNoMembersAndNoMessageActions() {
        val r = rig()
        val page = r.ready(r.page(12))
        assertTrue(page.isEmpty)
        assertTrue(!page.canMessage && !page.canEmail)
    }

    @Test fun renameChecksTheNameThenSavesIt() {
        val r = rig()
        val model = r.page(10)
        r.ready(model)
        model.askRename()
        assertEquals(GroupPageEffect.AskName("Family", null), r.wait { model.effects.first() })
        model.onAction(GroupPageAction.Rename("Work"))
        val taken = r.wait { model.effects.first() } as GroupPageEffect.AskName
        assertEquals("Work", taken.initial)
        assertNotNull(taken.error)
        model.onAction(GroupPageAction.Rename("Relatives"))
        assertEquals(GroupPageEffect.Toast("Group renamed"), r.wait { model.effects.first() })
        assertEquals("Relatives", r.ready(model) { it.title == "Relatives" }.title)
    }

    @Test fun addMembersAddsAndRemovesTheDifference() {
        val r = rig()
        val model = r.page(10)
        r.ready(model)
        model.onAction(GroupPageAction.SetMembers(setOf(1L, 3L, 2L, 4L)))
        val toast = r.wait { model.effects.first() } as GroupPageEffect.Toast
        assertTrue(toast.text, toast.text.isNotBlank())
        val page = r.ready(model) { it.members.size == 4 }
        assertEquals(setOf(1L, 2L, 3L, 4L), page.memberIds)
        // Nothing changed: no write, no toast.
        model.onAction(GroupPageAction.SetMembers(setOf(1L, 2L, 3L, 4L)))
        assertNull(r.wait { kotlinx.coroutines.withTimeoutOrNull(400) { model.effects.firstOrNull() } })
    }

    @Test fun removingAMemberTakesOnlyThatPersonOut() {
        val r = rig()
        val model = r.page(10)
        r.ready(model)
        model.onAction(GroupPageAction.RemoveMember(3))
        assertTrue((r.wait { model.effects.first() } as GroupPageEffect.Toast).text.isNotBlank())
        val page = r.ready(model) { it.members.size == 2 }
        assertEquals(setOf(1L, 5L), page.memberIds)
        assertEquals(
            "the contact itself stays",
            40,
            r.wait {
                r.fake
                    .all()
                    .first()
                    .size
            },
        )
    }

    @Test fun messageGroupJoinsTheMembersNumbers() {
        val r = rig()
        val model = r.page(10)
        r.ready(model)
        model.onAction(GroupPageAction.MessageGroup)
        val sms = r.wait { model.effects.first() } as GroupPageEffect.Sms
        assertTrue(sms.address, sms.address.contains(";"))
        assertEquals(3, sms.address.split(";").size)
    }

    @Test fun emailGroupSaysWhoWasLeftOut() {
        val r = rig()
        val model = r.page(10)
        r.ready(model)
        model.onAction(GroupPageAction.EmailGroup)
        val mail = r.wait { model.effects.first() } as GroupPageEffect.Email
        assertEquals(
            "ayse@example.org,elif@example.org",
            mail.address
                .split(",")
                .sorted()
                .joinToString(","),
        )
        val notice = r.wait { model.effects.first() } as GroupPageEffect.Toast
        assertTrue(notice.text, notice.text.contains("1"))
    }

    @Test fun emailGroupOfPeopleWithoutAddressesSaysSo() {
        val r = rig()
        val model = r.page(11)
        r.ready(model)
        model.onAction(GroupPageAction.EmailGroup)
        val toast = r.wait { model.effects.first() } as GroupPageEffect.Toast
        assertTrue(toast.text, toast.text.contains("email", ignoreCase = true))
    }

    @Test fun exportGroupWritesTheMembersUnderTheGroupName() {
        val r = rig()
        val model = r.page(10)
        r.ready(model)
        model.onAction(GroupPageAction.ExportGroup)
        val file = r.wait { model.effects.first() } as GroupPageEffect.SaveVcf
        assertEquals("Family.vcf", file.fileName)
        assertEquals(3, file.count)
        assertEquals(3, Regex("BEGIN:VCARD").findAll(file.text).count())
        assertTrue(file.text.contains("Ayşe") || file.text.contains("Ay"))
    }

    @Test fun exportOfAnEmptyGroupSaysNothingToShare() {
        val r = rig()
        val model = r.page(12)
        r.ready(model)
        model.onAction(GroupPageAction.ExportGroup)
        assertEquals(GroupPageEffect.Toast("Nothing to share."), r.wait { model.effects.first() })
    }

    @Test fun deletingTheGroupKeepsItsPeopleAndClosesThePage() {
        val r = rig()
        val model = r.page(10)
        r.ready(model)
        model.onAction(GroupPageAction.ConfirmDelete)
        assertEquals(GroupPageEffect.Toast("Group deleted"), r.wait { model.effects.first() })
        r.wait { model.state.first { it is GroupPageState.Gone } }
        assertEquals(
            40,
            r.wait {
                r.fake
                    .all()
                    .first()
                    .size
            },
        )
        assertEquals(
            listOf("Work", "Empty"),
            r.wait {
                r.fake
                    .groups()
                    .first()
                    .map { it.title }
            },
        )
    }
}
