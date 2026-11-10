package dev.rovsti.contacts.groups

import dev.rovsti.contacts.R
import dev.rovsti.contacts.en
import dev.rovsti.contacts.favorites.FavRig
import dev.rovsti.contacts.favorites.FavSample
import dev.rovsti.core.model.contacts.Account
import dev.rovsti.core.model.contacts.Group
import dev.rovsti.design.text.UiText
import dev.rovsti.platform.contacts.ContactResult
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

    /** The Favorites people with the first [inGoogle] of them in [GOOGLE], and the phone, [GOOGLE], [WORK] and [SIM]. */
    private fun accountsRig(inGoogle: Int) =
        FavRig(
            drafts =
                FavSample.drafts().mapIndexed { i, d ->
                    when {
                        i < inGoogle -> d.copy(account = GOOGLE.key)
                        i < inGoogle + 2 -> d.copy(account = WORK.key)
                        else -> d.copy(account = SIM.key)
                    }
                },
            accounts = listOf(Account(), SIM, GOOGLE, WORK),
        ).also { rig = it }

    private fun FavRig.groupsModel() = GroupsViewModel(book, repo, host.ops, store).also { groups = it }

    /** Creates [name] on the tab and answers the new group as the provider holds it. */
    private fun FavRig.create(
        model: GroupsViewModel,
        name: String,
    ): Group {
        groupState(model = model)
        model.onAction(GroupsAction.Create(name))
        val toast = wait { model.effects.first() } as GroupsEffect.Toast
        assertTrue(toast.text.en(), toast.text.en().startsWith("Group created"))
        val open = wait { model.effects.first() } as GroupsEffect.OpenGroup
        return wait { fake.groups().first().first { it.id == open.id } }
    }

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
        assertEquals("3 contacts", state.groups[0].subtitle.en())
        assertEquals("2 contacts", state.groups[1].subtitle.en())
        assertTrue(
            state.groups[2].subtitle.en(),
            state.groups[2]
                .subtitle
                .en()
                .startsWith("0") ||
                state.groups[2]
                    .subtitle
                    .en()
                    .startsWith("No"),
        )
    }

    @Test fun aNewGroupIsCreatedAndOpened() {
        val r = rig()
        val model = r.groupsModel()
        r.groupState(model = model)
        model.onAction(GroupsAction.Create("  Friends "))
        val toast = r.wait { model.effects.first() } as GroupsEffect.Toast
        assertTrue(toast.text.en(), toast.text.en().startsWith("Group created"))
        val open = r.wait { model.effects.first() } as GroupsEffect.OpenGroup
        val state = r.groupState({ s -> s.groups.any { it.title == "Friends" } }, model)
        assertEquals(open.id, state.groups.first { it.title == "Friends" }.id)
    }

    @Test fun aNewGroupOnAPhoneWithoutAccountsIsAPhoneGroup() {
        val r = rig()
        assertEquals("", r.create(r.groupsModel(), "Friends").account)
    }

    @Test fun aNewGroupGoesToTheSaveToAccountLikeANewContact() {
        val r = accountsRig(inGoogle = 30)
        r.wait { r.store.update { it.copy(saveTo = WORK.key) } }
        assertEquals(WORK.key, r.create(r.groupsModel(), "Friends").account)
    }

    @Test fun withoutSaveToANewGroupGoesToTheAccountWithTheMostContactsNeverTheSim() {
        // 3 people in Google, 2 in Work, 35 on the SIM: the SIM holds the most but cannot hold groups.
        val r = accountsRig(inGoogle = 3)
        assertEquals(GOOGLE.key, r.create(r.groupsModel(), "Friends").account)
    }

    @Test fun aSaveToAccountThatIsGoneFallsBackToTheMostContacts() {
        val r = accountsRig(inGoogle = 30)
        r.wait { r.store.update { it.copy(saveTo = "com.google|gone@example.org") } }
        assertEquals(GOOGLE.key, r.create(r.groupsModel(), "Friends").account)
    }

    @Test fun aRefusedNewGroupSaysSoAndAMissingPermissionSaysWhy() {
        val r = rig()
        val model = r.groupsModel()
        r.groupState(model = model)
        r.fake.failWith = ContactResult.Reason.Refused
        model.onAction(GroupsAction.Create("Friends"))
        assertEquals(
            GroupsEffect.Toast(UiText.Res(R.string.contacts_cant_save_group)),
            r.wait { model.effects.first() },
        )
        r.fake.failWith = ContactResult.Reason.Permission
        model.onAction(GroupsAction.Create("Friends"))
        assertEquals(
            GroupsEffect.Toast(UiText.Res(R.string.contacts_cant_change_groups_permission)),
            r.wait { model.effects.first() },
        )
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
        assertEquals("3 contacts", page.subtitle.en())
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
        assertEquals(
            GroupPageEffect.Toast(UiText.Res(R.string.contacts_group_renamed)),
            r.wait { model.effects.first() },
        )
        assertEquals("Relatives", r.ready(model) { it.title == "Relatives" }.title)
    }

    @Test fun aFailedRenameOrMemberChangeSaysWhyWhenThePermissionIsMissing() {
        val r = rig()
        val model = r.page(10)
        r.ready(model)
        r.fake.failWith = ContactResult.Reason.Permission
        val denied = GroupPageEffect.Toast(UiText.Res(R.string.contacts_cant_change_groups_permission))
        model.onAction(GroupPageAction.Rename("Relatives"))
        assertEquals(denied, r.wait { model.effects.first() })
        model.onAction(GroupPageAction.RemoveMember(3))
        assertEquals(denied, r.wait { model.effects.first() })
        r.fake.failWith = ContactResult.Reason.Unknown
        model.onAction(GroupPageAction.Rename("Relatives"))
        assertEquals(
            GroupPageEffect.Toast(UiText.Res(R.string.contacts_cant_save_group)),
            r.wait { model.effects.first() },
        )
        model.onAction(GroupPageAction.RemoveMember(3))
        assertEquals(
            GroupPageEffect.Toast(UiText.Res(R.string.contacts_cant_change_group)),
            r.wait { model.effects.first() },
        )
    }

    @Test fun addMembersAddsAndRemovesTheDifference() {
        val r = rig()
        val model = r.page(10)
        r.ready(model)
        model.onAction(GroupPageAction.SetMembers(setOf(1L, 3L, 2L, 4L)))
        val toast = r.wait { model.effects.first() } as GroupPageEffect.Toast
        assertTrue(toast.text.en(), toast.text.en().isNotBlank())
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
        assertTrue((r.wait { model.effects.first() } as GroupPageEffect.Toast).text.en().isNotBlank())
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
        assertTrue(notice.text.en(), notice.text.en().contains("1"))
    }

    @Test fun emailGroupOfPeopleWithoutAddressesSaysSo() {
        val r = rig()
        val model = r.page(11)
        r.ready(model)
        model.onAction(GroupPageAction.EmailGroup)
        val toast = r.wait { model.effects.first() } as GroupPageEffect.Toast
        assertTrue(toast.text.en(), toast.text.en().contains("email", ignoreCase = true))
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
        assertEquals(
            GroupPageEffect.Toast(UiText.Res(R.string.contacts_nothing_to_share)),
            r.wait { model.effects.first() },
        )
    }

    @Test fun deletingTheGroupKeepsItsPeopleAndClosesThePage() {
        val r = rig()
        val model = r.page(10)
        r.ready(model)
        model.onAction(GroupPageAction.ConfirmDelete)
        assertEquals(
            GroupPageEffect.Toast(UiText.Res(R.string.contacts_group_deleted)),
            r.wait { model.effects.first() },
        )
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

private val GOOGLE = Account("com.google|ayse@example.org", "ayse@example.org", "com.google")
private val WORK = Account("com.google|ayse@work.example", "ayse@work.example", "com.google")
private val SIM = Account("com.android.contacts.sim|SIM1", "SIM1", "com.android.contacts.sim")
