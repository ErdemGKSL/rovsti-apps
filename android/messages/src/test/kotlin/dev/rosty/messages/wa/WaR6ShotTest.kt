package dev.rosty.messages.wa

import android.os.Looper
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import dev.rosty.core.model.wa.GroupAbilities
import dev.rosty.core.model.wa.GroupInfo
import dev.rosty.core.model.wa.GroupNote
import dev.rosty.core.model.wa.GroupNotice
import dev.rosty.core.model.wa.InviteCode
import dev.rosty.core.model.wa.NoticeActor
import dev.rosty.core.model.wa.NoticeTarget
import dev.rosty.core.model.wa.Participant
import dev.rosty.core.model.wa.SystemNote
import dev.rosty.core.model.wa.WaSyncGroupStoreRequest
import dev.rosty.core.model.wa.WaSyncKind
import dev.rosty.core.model.wa.WaSyncMessage
import dev.rosty.core.model.wa.WaSyncRowState
import dev.rosty.design.overlay.Detent
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.OverlayHost
import dev.rosty.design.overlay.SheetEntry
import dev.rosty.design.text.resolve
import dev.rosty.design.transition.TabNavigator
import dev.rosty.messages.MessagesRoots
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.MessagesTabs
import dev.rosty.messages.R
import dev.rosty.messages.WhatsAppTab
import dev.rosty.messages.chromeFor
import dev.rosty.messages.conversation.ConversationActions
import dev.rosty.messages.conversation.ConversationScreen
import dev.rosty.messages.conversation.ConversationUi
import dev.rosty.messages.conversation.DayLabels
import dev.rosty.messages.conversation.rememberToolLabels
import dev.rosty.messages.inbox.ResourceInboxWords
import dev.rosty.messages.messagesTabItems
import dev.rosty.messages.names.PersonNames
import dev.rosty.messages.wa.WaTestKit.Companion.NOW
import dev.rosty.messages.wa.group.GroupInfoActions
import dev.rosty.messages.wa.group.GroupInfoScreen
import dev.rosty.messages.wa.group.GroupInfoUi
import dev.rosty.messages.wa.group.GroupLinkContent
import dev.rosty.messages.wa.group.GroupMemberUi
import dev.rosty.messages.wa.group.GroupPickerContent
import dev.rosty.messages.wa.group.GroupResultsContent
import dev.rosty.messages.wa.group.JoinPreviewContent
import dev.rosty.messages.wa.group.JoinPreviewUi
import dev.rosty.messages.wa.group.PickOption
import dev.rosty.messages.wa.group.ResultRowUi
import dev.rosty.messages.wa.group.SubgroupUi
import dev.rosty.shell.RostyShell
import dev.rosty.testing.ShotTest
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneId

/**
 * Screenshots of E6-WA R6, light and dark: the group info page (an admin's and a member's view, a group I left), the
 * add-members sheet, the results of an add, the invite link sheet, the join preview (and a reset link), the new-group
 * flow, and a thread of an announcement group where I cannot send, with its notice lines.
 */
@Config(sdk = [35])
class WaR6ShotTest : ShotTest() {
    private val options =
        RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = CHANGE_THRESHOLD))
    private val scope: CoroutineScope = MainScope()
    private val res get() = RuntimeEnvironment.getApplication().resources

    @After fun stop() = scope.cancel()

    private fun snap(
        name: String,
        content: @Composable (OverlayController) -> Unit,
        after: (OverlayController) -> Unit = {},
    ) {
        for (dark in listOf(false, true)) {
            val overlays = OverlayController()
            composeFrame(dark = dark) { content(overlays) }
            advance(600)
            compose.runOnUiThread { after(overlays) }
            advance(1_200)
            compose.onRoot().captureRoboImage(
                filePath = "screenshots/$name-${if (dark) "dark" else "light"}.png",
                roborazziOptions = options,
            )
        }
    }

    private fun member(
        jid: String,
        name: String,
        detail: String? = null,
        badge: String? = null,
        role: String = "member",
        me: Boolean = false,
    ) = GroupMemberUi(jid, name, badge, detail, role, me)

    private val members =
        persistentListOf(
            member(ME, "You", badge = "Admin", role = "admin", me = true),
            member(ADA, "Ada Lovelace", "+1 000 000 0081", "Creator", "superadmin"),
            member(BEN, "Ben", "+1 000 000 0082"),
            // Known only by LID: the WhatsApp name, never the digits.
            member(CEM_LID, "Cem"),
            member(DEN, "Deniz Yıldırım-Karaosmanoğlu", "+1 000 000 0084"),
        )

    private val admin =
        GroupInfoUi(
            jid = GROUP,
            subject = "Mahalle Dayanışma",
            description = "Weekly meetings on Saturday at the community centre. Please keep it on topic.",
            created = "Created by Ada Lovelace, 3 May 2026",
            count = "5 members",
            myRole = "Your role: Admin",
            myRoleKey = "admin",
            abilities = GroupAbilities(true, true, true, true, true, true, true, true),
            announce = true,
            approval = true,
            timer = WaWords.WEEK_S,
            members = members,
            total = members.size,
            community = "Community: Mahalle",
            subgroups =
                persistentListOf(
                    SubgroupUi("120363000000000070@g.us", "Duyurular", announcement = true, size = "900 members"),
                    SubgroupUi(GROUP, "Mahalle Dayanışma", announcement = false, size = "5 members"),
                ),
            loaded = true,
        )

    private val memberView =
        admin.copy(
            myRole = "Your role: Member",
            myRoleKey = "member",
            abilities = GroupAbilities(leave = true),
            members =
                members
                    .map { if (it.me) it.copy(badge = null, role = "member") else it }
                    .toImmutableList(),
            subgroups = persistentListOf(),
            community = null,
        )

    @Composable
    private fun Info(
        overlays: OverlayController,
        state: GroupInfoUi,
    ) {
        OverlayHost(overlays) {
            GroupInfoScreen(state, GroupInfoActions(), timerLabel(res, state.timer), rememberTextFieldState())
        }
    }

    @Test fun theGroupInfoPageOfAnAdmin() = snap("wa-r6-info-admin", content = { Info(it, admin) })

    @Test fun theGroupInfoPageOfAMember() = snap("wa-r6-info-member", content = { Info(it, memberView) })

    @Test fun aGroupILeft() =
        snap(
            "wa-r6-info-left",
            content = { Info(it, memberView.copy(left = true, myRole = null, myRoleKey = null, abilities = GroupAbilities())) },
        )

    private val picks =
        persistentListOf(
            PickOption("a", "Ada Lovelace", "Mobile · +1 000 000 0081", ADA, null),
            PickOption("b", "Ben", "Work · +1 000 000 0082", BEN, null),
            PickOption("e", "Ege Demir", "Mobile · +1 000 000 0085", EGE, null),
        )

    private fun OverlayController.picker(subject: String?) =
        present(
            SheetEntry(
                res.getString(if (subject != null) R.string.wa_group_new else R.string.wa_group_add_members),
                persistentListOf(Detent.Large),
            ) {
                val field = remember { TextFieldState() }
                val name = remember { subject?.let { TextFieldState(it) } }
                GroupPickerContent(
                    field = field,
                    options = picks,
                    picked = setOf(BEN, EGE),
                    onToggle = {},
                    button =
                        if (subject != null) {
                            res.getString(R.string.wa_group_create, 2)
                        } else {
                            res.getString(R.string.wa_group_add_button, 2)
                        },
                    enabled = true,
                    onDone = {},
                    subject = name,
                )
            },
        )

    @Test fun theAddMembersSheet() = snap("wa-r6-add-members", content = { Shell(it) }, after = { it.picker(null) })

    @Test fun theNewGroupFlow() = snap("wa-r6-new-group", content = { Shell(it) }, after = { it.picker("Pazar kahvaltısı") })

    @Test fun whoAnAddDidNotReach() =
        snap("wa-r6-add-results", content = { Shell(it) }, after = {
            it.present(
                SheetEntry(res.getString(R.string.wa_group_results_title), persistentListOf(Detent.Medium)) {
                    GroupResultsContent(
                        "Done for 1 person",
                        persistentListOf(
                            ResultRowUi(
                                EGE,
                                "Ege Demir",
                                res.getString(R.string.wa_group_outcome_invite),
                                InviteCode("PrivacyInviteCode01", 1_790_000_000),
                            ),
                            ResultRowUi(BEN, "Ben", res.getString(R.string.wa_group_outcome_already), null),
                            ResultRowUi(DEN, "Deniz", res.getString(R.string.wa_group_outcome_full), null),
                        ),
                        onInvite = {},
                    )
                },
            )
        })

    @Test fun theInviteLinkSheet() =
        snap("wa-r6-invite-link", content = { Shell(it) }, after = {
            it.present(
                SheetEntry(res.getString(R.string.wa_group_link_title), persistentListOf(Detent.Medium)) {
                    GroupLinkContent("https://chat.whatsapp.com/AbCdEfGhIjKlMnOpQrStUv", {}, {}, {})
                },
            )
        })

    @Test fun theJoinPreview() =
        snap("wa-r6-join-preview", content = { Shell(it) }, after = {
            it.present(
                SheetEntry(res.getString(R.string.wa_group_join), persistentListOf(Detent.Medium)) {
                    JoinPreviewContent(
                        JoinPreviewUi(
                            jid = GROUP,
                            subject = "Mahalle Dayanışma",
                            size = "128 members",
                            description = "Weekly meetings on Saturday.",
                            approval = true,
                        ),
                        onJoin = {},
                    )
                },
            )
        })

    @Test fun aResetInviteLink() =
        snap("wa-r6-join-invalid", content = { Shell(it) }, after = {
            it.present(
                SheetEntry(res.getString(R.string.wa_group_join), persistentListOf(Detent.Medium)) {
                    JoinPreviewContent(JoinPreviewUi(invalid = true), onJoin = {})
                },
            )
        })

    /** Rust's notice for each line of the thread (the template `wa.group_notice` answers). */
    private val notices =
        object : WaActionRules by FakeWaActionRules() {
            override suspend fun groupNotice(
                note: GroupNote,
                own: List<String>,
            ): GroupNotice =
                when (note.action) {
                    "add" -> {
                        GroupNotice(
                            "added",
                            NoticeActor("someone", ADA),
                            listOf(NoticeTarget(ME, me = true), NoticeTarget(BEN)),
                            more = 2,
                        )
                    }

                    "promote" -> {
                        GroupNotice("promoted", NoticeActor("someone", ADA), listOf(NoticeTarget(BEN)))
                    }

                    "subject" -> {
                        GroupNotice("subject", NoticeActor("someone", ADA), value = note.value)
                    }

                    "remove" -> {
                        GroupNotice("left", NoticeActor("someone", BEN))
                    }

                    else -> {
                        GroupNotice("announce_on", NoticeActor("someone", ADA))
                    }
                }
        }

    private fun line(
        id: String,
        ago: Int,
        action: String,
        value: String? = null,
    ): WaSyncMessage =
        WaTestKit
            .text(GROUP, id, NOW - ago * MINUTE, "", sender = ADA)
            .copy(
                kind = WaSyncKind.Unsupported,
                text = null,
                system = SystemNote("group", by = ADA, action = action, value = value),
            )

    private fun announcementThread(): ConversationUi {
        val kit = WaTestKit(CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined), graceMs = 0)
        kit.chat(GROUP, name = "Duyurular", group = true)
        listOf(
            line("g1", 50, "add"),
            line("g2", 40, "promote"),
            line("g3", 30, "subject", "Duyurular"),
            line("g4", 20, "remove"),
            line("g5", 10, "announce", "on"),
        ).forEach(kit::message)
        kit.message(WaTestKit.text(GROUP, "m1", NOW - 5 * MINUTE, "Saturday's meeting moves to 11:00.", sender = ADA))
        runBlocking {
            kit.ops.groupStore(
                WaSyncGroupStoreRequest(
                    1,
                    NOW,
                    GroupInfo(
                        GROUP,
                        "Duyurular",
                        listOf(Participant(ADA, true, role = "superadmin"), Participant(ME, false, role = "member")),
                        announce = true,
                    ),
                ),
            )
        }
        kit.offline.start()
        val days =
            DayLabels(
                ZoneId.of("UTC"),
                res.configuration.locales[0],
                true,
                res.getString(R.string.conversation_today),
                res.getString(R.string.inbox_yesterday),
            )
        val book = mapOf("+100000000081" to "Ada Lovelace", "+100000000082" to "Ben")
        val model =
            WaThreadViewModel(
                WaThreadSources(
                    offline = kit.offline,
                    remote = kit.remote,
                    emoji = WaRecentEmoji(null, Dispatchers.Unconfined),
                    drafts = MemoryDrafts(),
                    names = PersonNames({ book[it] }, MutableStateFlow(Unit)),
                    now = { kit.now },
                    rules = notices,
                    ownIds = { listOf(ME) },
                ),
                GROUP,
                ResourceWaWords(res),
                ResourceInboxWords(res),
                days,
            )
        shadowOf(Looper.getMainLooper()).idle()
        return model.state.value
    }

    @Test fun anAnnouncementGroupWithItsNoticeLines() {
        val state = announcementThread()
        snap("wa-r6-announcement-thread", content = { overlays ->
            OverlayHost(overlays) {
                ConversationScreen(
                    state = state,
                    composer = rememberTextFieldState(""),
                    listState = rememberLazyListState(),
                    actions =
                        remember {
                            ConversationActions(onBack = {}, onSend = {}, onLoadOlder = {}, menuFor = { persistentListOf() })
                        },
                    sendLabel = stringResource(R.string.conversation_send),
                    olderLabel = stringResource(R.string.conversation_load_earlier),
                    labels = rememberToolLabels(),
                )
            }
        })
    }

    /** The app shell on the WhatsApp tab: its overlay layer draws the page sheets (as in [WaR5ShotTest]). */
    @Composable
    private fun Shell(overlays: OverlayController) {
        val navigator = remember { TabNavigator(MessagesTabs, MessagesRoots).also { it.select(WhatsAppTab) } }
        val context = LocalContext.current
        RostyShell(
            navigator = navigator,
            tabs =
                messagesTabItems(
                    stringResource(R.string.tab_chats),
                    stringResource(R.string.tab_whatsapp),
                    stringResource(R.string.tab_settings),
                ),
            chromeFor = { r -> chromeFor(r) { it.resolve(context) } },
            overlays = overlays,
            modifier = Modifier.fillMaxSize(),
        ) {
            entry<MessagesRoute.Chats> {}
            entry<MessagesRoute.WhatsApp> {}
            entry<MessagesRoute.Settings> {}
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val GROUP = "120363000000000081@g.us"
        const val ADA = "100000000081@s.whatsapp.net"
        const val BEN = "100000000082@s.whatsapp.net"
        const val CEM_LID = "777000000000083@lid"
        const val DEN = "100000000084@s.whatsapp.net"
        const val EGE = "100000000085@s.whatsapp.net"
        const val ME = "100000000089@s.whatsapp.net"
    }
}
