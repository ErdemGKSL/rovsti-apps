package dev.rosty.messages.wa.group

import dev.rosty.core.FakeWaSyncOps
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.BulkSummary
import dev.rosty.core.model.wa.GroupActionDone
import dev.rosty.core.model.wa.GroupActionKind
import dev.rosty.core.model.wa.GroupInfo
import dev.rosty.core.model.wa.InviteCode
import dev.rosty.core.model.wa.JoinRequest
import dev.rosty.core.model.wa.Participant
import dev.rosty.core.model.wa.ParticipantResult
import dev.rosty.core.model.wa.ResultView
import dev.rosty.core.model.wa.WaSyncGroupReadRequest
import dev.rosty.core.model.wa.WaSyncGroupStoreRequest
import dev.rosty.messages.wa.FakeWaActionRules
import dev.rosty.messages.wa.FakeWaRemote
import dev.rosty.messages.wa.WaActionRules
import dev.rosty.messages.wa.WaRemote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The group info page's model (E6-WA R6): it reads the stored group at once, keeps the gateway's answer, shows who a
 * bulk add did not reach (an invite for a privacy refusal), reads again after WhatsApp refuses (demoted meanwhile),
 * turns read-only when I am removed while it is open, and leaves.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WaGroupModelTest {
    private val res get() = RuntimeEnvironment.getApplication().resources
    private val ops = FakeWaSyncOps()
    private val changes = MutableSharedFlow<Set<String>?>(extraBufferCapacity = 8)
    private val calls = mutableListOf<GroupActionKind>()

    /** What the gateway answers for the group, and for an action. */
    private var info = family(meAdmin = true)
    private var actionAnswer: (GroupActionKind) -> Outcome<GroupActionDone> = { Outcome.Ok(GroupActionDone()) }

    private val remote: WaRemote =
        object : WaRemote by FakeWaRemote() {
            override suspend fun group(jid: String): Outcome<GroupInfo> = Outcome.Ok(info)

            override suspend fun groupAction(
                group: String,
                action: GroupActionKind,
            ): Outcome<GroupActionDone> {
                calls += action
                return actionAnswer(action)
            }

            override suspend fun joinRequests(jid: String) = Outcome.Ok(listOf(JoinRequest(CEM_LID)))
        }

    private val rules: WaActionRules =
        object : WaActionRules by FakeWaActionRules() {
            override suspend fun groupResults(
                kind: String,
                results: List<ParticipantResult>,
            ): BulkSummary {
                val problems =
                    results.filter { it.error != null }.map {
                        ResultView(it.jid, if (it.invite != null) "invite_instead" else "already")
                    }
                return BulkSummary(
                    results.count { it.error == null },
                    problems,
                    results.filter { it.invite != null }.map { it.jid },
                )
            }
        }

    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun reset() = Dispatchers.resetMain()

    private fun model(): WaGroupModel =
        WaGroupModel(
            GroupSources(
                remote = remote,
                read = { jid, own, query, offset, limit ->
                    (ops.groupRead(WaSyncGroupReadRequest(1, jid, own, query, offset, limit)) as Outcome.Ok).value
                },
                store = { ops.groupStore(WaSyncGroupStoreRequest(1, 1_790_000_000_000, it)) },
                name = { jid, push -> push ?: names[jid] ?: "WhatsApp user" },
                rules = rules,
                ownIds = { listOf(ME, ME_LID) },
                day = { "3 May 2026" },
                changes = changes,
            ),
            GROUP,
            GroupWords(res),
        )

    @Test fun theStoredGroupShowsAtOnceThenTheGatewaysAnswerIsKept() {
        // A group stored earlier (offline) with me as a member only.
        runBlocking { ops.groupStore(WaSyncGroupStoreRequest(1, 1, family(meAdmin = false))) }
        info = family(meAdmin = true)
        val model = model()
        val state = model.state.value
        assertEquals("Aile", state.subject)
        assertEquals("Your role: Admin", state.myRole)
        assertTrue(state.abilities.manage && state.abilities.inviteLink)
        assertEquals("Created by Ada, 3 May 2026", state.created)
        assertEquals("4 members", state.count)
        // Me first, then the admins, then by name; a member known only by LID is named, never shown as digits.
        assertEquals(listOf(ME, ADA, BEN, CEM_LID), state.members.map { it.jid })
        assertEquals("You", state.members[0].name)
        assertEquals("Creator", state.members[1].badge)
        assertEquals("Cem", state.members[3].name)
        assertNull(state.members[3].detail)
    }

    @Test fun anAddThatPrivacyRefusedOffersAnInvite() =
        runBlocking {
            val model = model()
            val invite = InviteCode("PrivacyInviteCode01", 1_790_000_000)
            actionAnswer = {
                Outcome.Ok(
                    GroupActionDone(
                        listOf(
                            ParticipantResult(DEN),
                            ParticipantResult(EGE, "403", invite),
                            ParticipantResult(BEN, "409"),
                        ),
                    ),
                )
            }
            model.members("add", listOf(DEN, EGE, BEN))
            val results = withTimeout(5_000) { model.effects.first() } as GroupEffect.Results
            assertEquals("Done for 1 person", results.done)
            assertEquals(listOf(EGE, BEN), results.rows.map { it.jid })
            assertEquals(invite, results.rows[0].invite)
            assertTrue(results.rows[0].why.contains("invite"))
            assertEquals("Already in the group", results.rows[1].why)
            model.sendInvite(EGE, invite)
            assertEquals(GroupActionKind.sendInvite(EGE, invite), calls.last())
        }

    @Test fun aRefusalReadsTheGroupAgainAndHidesWhatIMayNoLongerDo() =
        runBlocking {
            val model = model()
            assertTrue(model.state.value.abilities.settings)
            // Demoted on another phone a moment ago: WhatsApp refuses, and the next answer has me as a member.
            info = family(meAdmin = false)
            actionAnswer = {
                Outcome.Err(
                    RustError.Invalid("refused", buildJsonObject { put("code", JsonPrimitive("conflict")) }),
                )
            }
            model.setSetting("announce", true)
            val toast = withTimeout(5_000) { model.effects.first() } as GroupEffect.Toast
            assertEquals("WhatsApp refused this. You may no longer be an admin.", toast.text)
            assertFalse(model.state.value.abilities.settings)
            assertEquals("Your role: Member", model.state.value.myRole)
        }

    @Test fun removedWhileThePageIsOpenTurnsItReadOnly() =
        runBlocking {
            val model = model()
            assertTrue(model.state.value.abilities.leave)
            // The removal reached the store: the gateway answers that I left.
            ops.groupStore(WaSyncGroupStoreRequest(1, 2, GroupInfo(GROUP, "", emptyList(), left = true)))
            changes.emit(setOf(GROUP))
            val state = withTimeout(5_000) { model.state.first { it.left } }
            assertTrue(state.left)
            assertFalse(state.abilities.send || state.abilities.leave || state.abilities.manage)
            assertNull(state.myRole)
            // What was known stays.
            assertEquals("Aile", state.subject)
        }

    @Test fun leavingTheLastAdminSeatWarnsAndLeaves() =
        runBlocking {
            info = family(meAdmin = true, adaAdmin = false)
            val model = model()
            assertTrue(model.state.value.lastAdmin)
            // WhatsApp no longer shows me the group once I left.
            info = info.copy(participants = emptyList(), left = true)
            model.leave()
            assertEquals(GroupEffect.Left, withTimeout(5_000) { model.effects.first() })
            assertEquals(GroupActionKind.LEAVE, calls.last())
            assertTrue(model.state.value.left)
        }

    @Test fun aLargeGroupReadsInPagesAndFilters() =
        runBlocking {
            info =
                family(meAdmin = true).copy(
                    participants =
                        (0 until 250).map { n ->
                            Participant(
                                "1000000${"%05d".format(n + 100)}@s.whatsapp.net",
                                false,
                                name = "Üye %03d".format(n),
                            )
                        } + Participant(ME, true, role = "admin"),
                    size = 251,
                )
            val model = model()
            assertEquals(WaGroupModel.PAGE, model.state.value.members.size)
            assertTrue(model.state.value.hasMore)
            model.more()
            assertEquals(2 * WaGroupModel.PAGE, model.state.value.members.size)
            model.search("üye 01")
            assertEquals(10, model.state.value.total)
        }

    private fun family(
        meAdmin: Boolean,
        adaAdmin: Boolean = true,
    ) = GroupInfo(
        jid = GROUP,
        subject = "Aile",
        participants =
            listOf(
                Participant(ADA, adaAdmin, name = "Ada", role = if (adaAdmin) "superadmin" else "member"),
                Participant(BEN, false, name = "Ben", role = "member"),
                Participant(CEM_LID, false, name = "Cem", lid = CEM_LID, role = "member"),
                Participant(ME, meAdmin, lid = ME_LID, pn = ME, role = if (meAdmin) "admin" else "member"),
            ),
        createdMs = 1_746_230_400_000,
        creator = ADA,
        size = 4,
        approval = true,
    )

    private val names = mapOf(ADA to "Ada", BEN to "Ben", DEN to "Deniz", EGE to "Ege")

    private companion object {
        const val GROUP = "120363000000000081@g.us"
        const val ADA = "100000000081@s.whatsapp.net"
        const val BEN = "100000000082@s.whatsapp.net"
        const val CEM_LID = "777000000000083@lid"
        const val DEN = "100000000084@s.whatsapp.net"
        const val EGE = "100000000085@s.whatsapp.net"
        const val ME = "100000000089@s.whatsapp.net"
        const val ME_LID = "777000000000089@lid"
    }
}
