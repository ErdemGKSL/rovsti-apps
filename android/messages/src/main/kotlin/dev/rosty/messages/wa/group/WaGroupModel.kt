package dev.rosty.messages.wa.group

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.RustError
import dev.rosty.core.model.wa.GroupAbilities
import dev.rosty.core.model.wa.GroupActionKind
import dev.rosty.core.model.wa.GroupInfo
import dev.rosty.core.model.wa.GroupMember
import dev.rosty.core.model.wa.InviteCode
import dev.rosty.core.model.wa.ParticipantResult
import dev.rosty.core.model.wa.Subgroup
import dev.rosty.core.model.wa.WaIds
import dev.rosty.core.model.wa.WaSyncGroupPage
import dev.rosty.messages.wa.WaActionRules
import dev.rosty.messages.wa.WaRemote
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** One member row of the info page. */
@Immutable
data class GroupMemberUi(
    val jid: String,
    val name: String,
    /** Their role's word when it is not "member" ("Admin", "Creator"). */
    val badge: String?,
    /** A readable number under the name, when it is not the name itself. */
    val detail: String?,
    val role: String,
    val me: Boolean,
)

/** A community's group on the info page. */
@Immutable
data class SubgroupUi(
    val jid: String,
    val subject: String,
    val announcement: Boolean,
    val size: String?,
)

/** The info page of a group (E6-WA R6). */
@Immutable
data class GroupInfoUi(
    val jid: String,
    val subject: String = "",
    val description: String? = null,
    /** "Created by Ada, 3 May 2026". */
    val created: String? = null,
    /** "12 members". */
    val count: String = "",
    /** "Your role: Admin"; null when I am not a member. */
    val myRole: String? = null,
    /** My role as the store names it (`superadmin`, `admin`, `member`). */
    val myRoleKey: String? = null,
    val abilities: GroupAbilities = GroupAbilities(),
    val lastAdmin: Boolean = false,
    val announce: Boolean = false,
    val locked: Boolean = false,
    val approval: Boolean = false,
    /** I left the group or was removed. */
    val left: Boolean = false,
    /** The disappearing timer in seconds; 0 is off. */
    val timer: Long = 0,
    val members: ImmutableList<GroupMemberUi> = persistentListOf(),
    /** Members matching the filter, all pages. */
    val total: Int = 0,
    val query: String = "",
    /** "Community: Mahalle"; null outside a community. */
    val community: String? = null,
    val subgroups: ImmutableList<SubgroupUi> = persistentListOf(),
    val loaded: Boolean = false,
) {
    val hasMore: Boolean get() = members.size < total
}

/** One person of a results sheet. */
@Immutable
data class ResultRowUi(
    val jid: String,
    val name: String,
    val why: String,
    val invite: InviteCode?,
)

/** What the page shows in an overlay. */
sealed interface GroupEffect {
    data class Toast(
        val text: String,
    ) : GroupEffect

    /** A bulk action did not work for everyone. */
    data class Results(
        val done: String?,
        val rows: ImmutableList<ResultRowUi>,
    ) : GroupEffect

    data class Link(
        val link: String,
    ) : GroupEffect

    /** I left: the page closes or stays read-only. */
    data object Left : GroupEffect
}

/** Where the group page reads, writes and names (seams for tests). */
class GroupSources(
    val remote: WaRemote,
    /** `wa_sync.group_read`; null when no store is open. */
    val read: suspend (jid: String, own: List<String>, query: String?, offset: Int, limit: Int) -> WaSyncGroupPage?,
    /** `wa_sync.group_store`. */
    val store: suspend (GroupInfo) -> Unit,
    /** Hands an answer to the identity map (members named by both ids). */
    val observe: suspend (GroupInfo) -> Unit = {},
    /** The name-order rule for a member: contact, WhatsApp name, readable number, never LID digits. */
    val name: suspend (jid: String, push: String?) -> String,
    val readable: suspend (number: String) -> String = { it },
    val rules: WaActionRules? = null,
    val ownIds: () -> List<String> = { emptyList() },
    /** A day as the phone writes it. */
    val day: (Long) -> String = { it.toString() },
    /** Store changes for these chats (a notice line merged, the left flag). */
    val changes: Flow<Set<String>?> = emptyFlow(),
    /** The chat's disappearing timer. */
    val timer: suspend (jid: String) -> Long = { 0 },
)

/**
 * The group info page (E6-WA R6): reads the stored group at once (offline), asks the gateway for the current one and
 * keeps it, and runs the admin actions. A refusal of WhatsApp (I was demoted or removed meanwhile) reads the group
 * again so the page shows what I may still do.
 */
class WaGroupModel(
    private val sources: GroupSources,
    val jid: String,
    private val words: GroupWords,
) : ViewModel() {
    private val ui = MutableStateFlow(GroupInfoUi(jid))
    val state: StateFlow<GroupInfoUi> = ui.asStateFlow()

    private val effectChannel = Channel<GroupEffect>(Channel.BUFFERED)
    val effects: Flow<GroupEffect> = effectChannel.receiveAsFlow()

    private var pages = 1
    private var latest: GroupInfo? = null

    /** The groups of the community this group belongs to (or is). */
    private var subgroups: List<Subgroup> = emptyList()

    init {
        viewModelScope.launch {
            read()
            refresh()
        }
        viewModelScope.launch {
            sources.changes.collect { chats -> if (chats == null || jid in chats) read() }
        }
    }

    /** Asks the gateway for the group and keeps it; the page reads it again. */
    suspend fun refresh() {
        when (val got = sources.remote.group(jid)) {
            is Outcome.Ok -> {
                latest = got.value
                sources.store(got.value)
                sources.observe(got.value)
                communityOf(got.value)
                read()
            }

            is Outcome.Err -> {
                if (!ui.value.loaded) read()
            }
        }
    }

    private suspend fun communityOf(info: GroupInfo) {
        val parent = info.community?.parent ?: info.jid.takeIf { info.community?.kind == "parent" } ?: return
        val groups = (sources.remote.subgroups(parent) as? Outcome.Ok)?.value.orEmpty()
        subgroups = groups
    }

    /** The filter of the member list. */
    fun search(query: String) {
        pages = 1
        ui.value = ui.value.copy(query = query)
        viewModelScope.launch { read() }
    }

    /** The next page of members. */
    fun more() {
        pages += 1
        viewModelScope.launch { read() }
    }

    /** Reads the stored group (the store keeps it; without one, the last answer). */
    suspend fun read() {
        val own = sources.ownIds()
        val query = ui.value.query.takeIf { it.isNotBlank() }
        val page = sources.read(jid, own, query, 0, PAGE * pages)
        val group = page?.group
        val members =
            page?.members.orEmpty().map { member -> memberUi(member, own) }
        val subject = group?.subject ?: latest?.subject.orEmpty()
        val creator = group?.creator ?: latest?.creator
        val createdMs = group?.createdMs ?: latest?.createdMs
        val created =
            createdMs?.let { ms ->
                val day = sources.day(ms)
                creator?.let { words.createdBy(sources.name(it, null), day) } ?: words.createdOn(day)
            }
        val size = group?.size ?: page?.total ?: latest?.participants?.size ?: 0
        val view = page?.view
        val communityName =
            group?.communityParent?.let { parent ->
                subgroups.firstOrNull { it.jid == parent }?.subject
            }
        ui.value =
            ui.value.copy(
                subject = subject,
                description = group?.description ?: latest?.description,
                created = created,
                count = words.memberCount(size),
                myRole = view?.myRole?.let(words::myRole),
                myRoleKey = view?.myRole,
                abilities = view?.abilities ?: GroupAbilities(),
                lastAdmin = view?.lastAdmin == true,
                announce = group?.settings?.announce ?: latest?.announce ?: false,
                locked = group?.settings?.locked ?: latest?.locked ?: false,
                approval = group?.settings?.approval ?: latest?.approval ?: false,
                left = group?.settings?.left ?: latest?.left ?: false,
                timer = group?.ephemeralS ?: latest?.ephemeralS ?: sources.timer(jid),
                members = members.toImmutableList(),
                total = page?.total ?: members.size,
                community =
                    group?.communityKind?.let {
                        words.community(
                            communityName ?: subgroups.firstOrNull { s -> s.announcement }?.subject ?: subject,
                        )
                    },
                subgroups =
                    subgroups
                        .map { SubgroupUi(it.jid, it.subject, it.announcement, it.size?.let(words::memberCount)) }
                        .toImmutableList(),
                loaded = true,
            )
    }

    private suspend fun memberUi(
        member: GroupMember,
        own: List<String>,
    ): GroupMemberUi {
        val me = own.any { it == member.jid || it == member.alt }
        val name =
            if (me) {
                words.string(
                    dev.rosty.messages.R.string.wa_group_you,
                )
            } else {
                sources.name(member.jid, member.name)
            }
        val number = WaIds.number(member.jid) ?: member.alt?.let(WaIds::number)
        val readable = number?.let { sources.readable(it) }
        return GroupMemberUi(
            jid = member.jid,
            name = name,
            badge = member.role.takeIf { it != GroupMember.MEMBER }?.let(words::role),
            detail = readable?.takeIf { it != name && !me },
            role = member.role,
            me = me,
        )
    }

    // ---- actions

    /** Adds, removes, promotes or demotes [jids] ([type] the action), then shows who it did not work for. */
    fun members(
        type: String,
        jids: List<String>,
    ) {
        if (jids.isEmpty()) return
        viewModelScope.launch { bulk(type, GroupActionKind.members(type, jids)) }
    }

    /** Approves or rejects join requests. */
    fun answerRequests(
        approve: Boolean,
        jids: List<String>,
    ) = members(if (approve) "approve" else "reject", jids)

    private suspend fun bulk(
        kind: String,
        action: GroupActionKind,
    ) {
        when (val done = sources.remote.groupAction(jid, action)) {
            is Outcome.Ok -> {
                report(kind, done.value.results)
                refresh()
            }

            is Outcome.Err -> {
                failed(done.error)
            }
        }
    }

    /** The results sheet when not everyone got in (or out). */
    private suspend fun report(
        kind: String,
        results: List<ParticipantResult>,
    ) {
        val summary = sources.rules?.groupResults(kind, results) ?: return
        if (summary.allDone) return
        val rows =
            summary.problems.map { problem ->
                ResultRowUi(
                    jid = problem.jid,
                    name = sources.name(problem.jid, null),
                    why = words.outcome(problem),
                    invite =
                        results.firstOrNull { it.jid == problem.jid }?.invite?.takeIf {
                            problem.jid in
                                summary.invites
                        },
                )
            }
        effectChannel.send(
            GroupEffect.Results(
                done = summary.done.takeIf { it > 0 }?.let(words::doneCount),
                rows = rows.toImmutableList(),
            ),
        )
    }

    /** Sends WhatsApp's group invite to someone whose privacy refused the add. */
    fun sendInvite(
        to: String,
        invite: InviteCode,
    ) = simple(GroupActionKind.sendInvite(to, invite), words.string(dev.rosty.messages.R.string.wa_group_invite_sent))

    fun setSubject(text: String) = simple(GroupActionKind.subject(text), null)

    fun setDescription(text: String) = simple(GroupActionKind.description(text), null)

    /** A 640 px JPEG as base64; null removes the picture. */
    fun setPicture(jpegB64: String?) = simple(GroupActionKind.picture(jpegB64), null)

    /** `announce`, `locked` or `approval`. */
    fun setSetting(
        type: String,
        on: Boolean,
    ) {
        // Shown at once; the refetch after the answer has the last word.
        ui.value =
            when (type) {
                "announce" -> ui.value.copy(announce = on)
                "locked" -> ui.value.copy(locked = on)
                "approval" -> ui.value.copy(approval = on)
                else -> ui.value
            }
        simple(GroupActionKind.setting(type, on), null)
    }

    /** The invite link, after resetting it when [reset]. */
    fun inviteLink(reset: Boolean) {
        viewModelScope.launch {
            when (val done = sources.remote.groupAction(jid, GroupActionKind.inviteLink(reset))) {
                is Outcome.Ok -> done.value.link?.let { effectChannel.send(GroupEffect.Link(it)) }
                is Outcome.Err -> failed(done.error)
            }
        }
    }

    /** The pending join requests, named. */
    suspend fun requests(): List<ResultRowUi>? =
        when (val got = sources.remote.joinRequests(jid)) {
            is Outcome.Ok -> got.value.map { ResultRowUi(it.jid, sources.name(it.jid, null), "", null) }
            is Outcome.Err -> null.also { failed(got.error) }
        }

    fun leave() {
        viewModelScope.launch {
            when (val done = sources.remote.groupAction(jid, GroupActionKind.LEAVE)) {
                is Outcome.Ok -> {
                    ui.value = ui.value.copy(left = true, abilities = GroupAbilities(), myRole = null, myRoleKey = null)
                    effectChannel.send(GroupEffect.Left)
                    refresh()
                }

                is Outcome.Err -> {
                    failed(done.error)
                }
            }
        }
    }

    private fun simple(
        action: GroupActionKind,
        done: String?,
    ) {
        viewModelScope.launch {
            when (val answer = sources.remote.groupAction(jid, action)) {
                is Outcome.Ok -> {
                    done?.let { effectChannel.send(GroupEffect.Toast(it)) }
                    refresh()
                }

                is Outcome.Err -> {
                    failed(answer.error)
                }
            }
        }
    }

    /** A refusal reads the group again: I may have been demoted or removed meanwhile. */
    private suspend fun failed(error: RustError) {
        val text =
            when {
                error.isConflict() -> dev.rosty.messages.R.string.wa_group_refused
                error.isOldGateway() -> dev.rosty.messages.R.string.wa_group_old_gateway
                else -> dev.rosty.messages.R.string.wa_group_failed
            }
        effectChannel.send(GroupEffect.Toast(words.string(text)))
        if (error.isConflict()) refresh()
    }

    companion object {
        /** Members read per page. */
        const val PAGE = 100
    }
}

/** WhatsApp refused the change: the gateway's `conflict`. */
fun RustError.isConflict(): Boolean = code == "conflict"

/** WhatsApp does not know it: an unknown or reset invite code (the gateway's `not_found`). */
fun RustError.isGatewayNotFound(): Boolean = code == "not_found"

/** A gateway from before the group routes answers them with the router's 404 (no API body). */
fun RustError.isOldGateway(): Boolean = this is RustError.Network && message?.contains("404") == true
