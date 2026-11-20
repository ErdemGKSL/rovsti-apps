package dev.rosty.core.model.wa

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Groups (E6-WA R6): the group routes on the wire (`rosty_wa_proto::group`), the pure ops of `rosty_core::wa_group`
// and the group tables of the device store.

/** A group's place in its community: [kind] `parent`, `announcement`, `general` or `sub`. */
@Serializable
data class CommunityRef(
    val kind: String,
    @EncodeDefault(NEVER) val parent: String? = null,
) {
    val isAnnouncement: Boolean get() = kind == "announcement"
}

/**
 * One change of a group (`POST /v1/groups/action`), tagged by [type]: `add`, `remove`, `promote`, `demote`,
 * `approve`, `reject` (with [participants]), `subject`, `description` (empty deletes), `picture` ([jpegB64]; none
 * removes), `announce`, `locked`, `approval` ([on]), `invite_link` ([reset]), `send_invite` ([to], [code],
 * [expiration]) or `leave`.
 */
@Serializable
data class GroupActionKind(
    val type: String,
    @EncodeDefault(NEVER) val participants: List<String>? = null,
    @EncodeDefault(NEVER) val subject: String? = null,
    @EncodeDefault(NEVER) val description: String? = null,
    @SerialName("jpeg_b64") @EncodeDefault(NEVER) val jpegB64: String? = null,
    @EncodeDefault(NEVER) val on: Boolean? = null,
    @EncodeDefault(NEVER) val reset: Boolean? = null,
    @EncodeDefault(NEVER) val to: String? = null,
    @EncodeDefault(NEVER) val code: String? = null,
    @EncodeDefault(NEVER) val expiration: Long? = null,
) {
    companion object {
        fun members(
            type: String,
            jids: List<String>,
        ) = GroupActionKind(type, participants = jids)

        fun subject(text: String) = GroupActionKind("subject", subject = text)

        fun description(text: String) = GroupActionKind("description", description = text)

        fun picture(jpegB64: String?) = GroupActionKind("picture", jpegB64 = jpegB64)

        fun setting(
            type: String,
            on: Boolean,
        ) = GroupActionKind(type, on = on)

        fun inviteLink(reset: Boolean) = GroupActionKind("invite_link", reset = reset.takeIf { it })

        fun sendInvite(
            to: String,
            invite: InviteCode,
        ) = GroupActionKind("send_invite", to = to, code = invite.code, expiration = invite.expiration)

        val LEAVE = GroupActionKind("leave")
    }
}

/** `wa.group_action`: [action] on [group]. */
@Serializable
data class GroupAction(
    val group: String,
    val action: GroupActionKind,
)

/** A code an add answered for someone whose privacy refuses adds: an invite may be sent to them instead. */
@Serializable
data class InviteCode(
    val code: String,
    val expiration: Long,
)

/** One person's result of a bulk action; no [error] is done. */
@Serializable
data class ParticipantResult(
    val jid: String,
    @EncodeDefault(NEVER) val error: String? = null,
    @EncodeDefault(NEVER) val invite: InviteCode? = null,
)

/** What `wa.group_action` answers: one result per person of a bulk action, or the invite [link]. */
@Serializable
data class GroupActionDone(
    @EncodeDefault(NEVER) val results: List<ParticipantResult> = emptyList(),
    @EncodeDefault(NEVER) val link: String? = null,
)

/** `wa.group_create`. */
@Serializable
data class CreateGroup(
    val subject: String,
    val participants: List<String> = emptyList(),
)

/** What `wa.group_create` answers: the new group, and the people asked for who did not get in. */
@Serializable
data class GroupCreated(
    val jid: String,
    @EncodeDefault(NEVER) val results: List<ParticipantResult> = emptyList(),
)

/** `wa.group_invite_preview` and `wa.group_join`: a `chat.whatsapp.com` link or a bare code. */
@Serializable
data class InviteCodeBody(
    val code: String,
)

/** The group a link leads to, before joining. */
@Serializable
data class InvitePreview(
    val jid: String,
    val subject: String,
    @EncodeDefault(NEVER) val size: Int? = null,
    @EncodeDefault(NEVER) val description: String? = null,
    /** Joining sends a request an admin approves. */
    @EncodeDefault(NEVER) val approval: Boolean = false,
    @EncodeDefault(NEVER) val community: CommunityRef? = null,
)

/** What `wa.group_join` answers; [pending] when an admin must approve. */
@Serializable
data class GroupJoined(
    val jid: String,
    @EncodeDefault(NEVER) val pending: Boolean = false,
)

/** One pending join request. */
@Serializable
data class JoinRequest(
    val jid: String,
    @SerialName("jid_alt") @EncodeDefault(NEVER) val jidAlt: String? = null,
    @SerialName("requested_ms") @EncodeDefault(NEVER) val requestedMs: Long? = null,
)

/** What `wa.group_requests` answers. */
@Serializable
data class JoinRequestsAnswer(
    val requests: List<JoinRequest>,
)

/** One group of a community. */
@Serializable
data class Subgroup(
    val jid: String,
    val subject: String,
    @EncodeDefault(NEVER) val size: Int? = null,
    @EncodeDefault(NEVER) val announcement: Boolean = false,
    @EncodeDefault(NEVER) val general: Boolean = false,
)

/** What `wa.group_subgroups` answers: the announcement group first. */
@Serializable
data class SubgroupsAnswer(
    val groups: List<Subgroup>,
)

// ---- pure: rosty_core::wa_group

/** A group change as the gateway stores it ([SystemNote] of type `group`). */
@Serializable
data class GroupNote(
    val action: String,
    @EncodeDefault(NEVER) val by: String? = null,
    @EncodeDefault(NEVER) val participants: List<String> = emptyList(),
    @EncodeDefault(NEVER) val value: String? = null,
)

/** `wa.group_notice`: [note] with my ids [own]. */
@Serializable
data class GroupNoticeRequest(
    val note: GroupNote,
    val own: List<String> = emptyList(),
)

/** Who did it: [type] `me`, `someone` ([jid]) or `nobody`. */
@Serializable
data class NoticeActor(
    val type: String,
    @EncodeDefault(NEVER) val jid: String? = null,
) {
    val isMe: Boolean get() = type == "me"
}

/** One person a line names; [me] is "you". */
@Serializable
data class NoticeTarget(
    val jid: String,
    @EncodeDefault(NEVER) val me: Boolean = false,
)

/**
 * A notice line to write: [template] (`added`, `removed`, `left`, `joined`, `promoted`, `demoted`,
 * `number_changed`, `subject`, `description`, `description_removed`, `picture`, `picture_removed`, `announce_on`,
 * `announce_off`, `locked_on`, `locked_off`, `approval_on`, `approval_off`, `link_reset`, `created`, `deleted`,
 * `other`), at most three [targets] and [more] others.
 */
@Serializable
data class GroupNotice(
    val template: String,
    val actor: NoticeActor,
    @EncodeDefault(NEVER) val targets: List<NoticeTarget> = emptyList(),
    @EncodeDefault(NEVER) val more: Int = 0,
    @EncodeDefault(NEVER) val value: String? = null,
    @SerialName("about_me") @EncodeDefault(NEVER) val aboutMe: Boolean = false,
)

/** `wa.invite_find`. */
@Serializable
data class InviteFindRequest(
    val text: String,
)

/** An invite link in a text, [start] to [end] in UTF-16 units. */
@Serializable
data class InviteMatch(
    val code: String,
    val start: Int,
    val end: Int,
)

/** What `wa.invite_find` answers: the first link in the text, and the whole text read as a link or a code. */
@Serializable
data class InviteFindAnswer(
    @EncodeDefault(NEVER) val found: InviteMatch? = null,
    @EncodeDefault(NEVER) val code: String? = null,
)

/** `wa.group_results`: [results] of a bulk action of [kind] (`add`, `remove`, `promote`, ... `create`). */
@Serializable
data class GroupResultsRequest(
    val kind: String,
    val results: List<ParticipantResult>,
)

/**
 * One person it did not work for: [outcome] `invite_instead`, `privacy`, `not_on_whatsapp`, `already`, `full`,
 * `left_recently`, `not_allowed` or `failed` (with the server's [code]).
 */
@Serializable
data class ResultView(
    val jid: String,
    val outcome: String,
    @EncodeDefault(NEVER) val code: String? = null,
)

/** What `wa.group_results` answers. */
@Serializable
data class BulkSummary(
    val done: Int,
    val problems: List<ResultView> = emptyList(),
    val invites: List<String> = emptyList(),
) {
    val allDone: Boolean get() = problems.isEmpty()
}

/** `wa.group_can`: whether [me] may [action] (`remove`, `promote`, `demote`) a member whose role is [target]. */
@Serializable
data class GroupCanRequest(
    val me: String,
    val target: String,
    val action: String,
)

@Serializable
data class GroupCanAnswer(
    val allowed: Boolean,
)

// ---- the device store: wa_sync groups (schema v8)

/** `wa_sync.group_store`: the answer of `wa.group` at [nowMs]. */
@Serializable
data class WaSyncGroupStoreRequest(
    val handle: Long,
    @SerialName("now_ms") val nowMs: Long,
    val group: GroupInfo,
)

@Serializable
data class WaSyncGroupStored(
    val handle: Long,
    val members: Int = 0,
)

/** `wa_sync.group_read`: a page of the members of [jid], my ids [own], filtered by [query]. */
@Serializable
data class WaSyncGroupReadRequest(
    val handle: Long,
    val jid: String,
    val own: List<String> = emptyList(),
    @EncodeDefault(NEVER) val query: String? = null,
    @EncodeDefault(NEVER) val offset: Int = 0,
    val limit: Int,
)

/** The settings that decide who may do what; [left]: I left or was removed. */
@Serializable
data class GroupSettings(
    val announce: Boolean = false,
    val locked: Boolean = false,
    val approval: Boolean = false,
    @SerialName("admins_add") val adminsAdd: Boolean = false,
    val left: Boolean = false,
)

/** What the device keeps about a group besides its members. */
@Serializable
data class StoredGroup(
    val jid: String,
    @EncodeDefault(NEVER) val subject: String? = null,
    @EncodeDefault(NEVER) val description: String? = null,
    @SerialName("description_id") @EncodeDefault(NEVER) val descriptionId: String? = null,
    @SerialName("created_ms") @EncodeDefault(NEVER) val createdMs: Long? = null,
    @EncodeDefault(NEVER) val creator: String? = null,
    val settings: GroupSettings = GroupSettings(),
    @SerialName("ephemeral_s") @EncodeDefault(NEVER) val ephemeralS: Long? = null,
    @EncodeDefault(NEVER) val size: Int? = null,
    @SerialName("community_kind") @EncodeDefault(NEVER) val communityKind: String? = null,
    @SerialName("community_parent") @EncodeDefault(NEVER) val communityParent: String? = null,
    @SerialName("fetched_ms") val fetchedMs: Long = 0,
)

/** One member: [role] `superadmin`, `admin` or `member`. */
@Serializable
data class GroupMember(
    val jid: String,
    @EncodeDefault(NEVER) val alt: String? = null,
    val role: String = MEMBER,
    @EncodeDefault(NEVER) val name: String? = null,
) {
    val isAdmin: Boolean get() = role != MEMBER

    companion object {
        const val SUPER_ADMIN = "superadmin"
        const val ADMIN = "admin"
        const val MEMBER = "member"
    }
}

/** What I may do in a group. */
@Serializable
data class GroupAbilities(
    val send: Boolean = false,
    @SerialName("edit_info") val editInfo: Boolean = false,
    val add: Boolean = false,
    val manage: Boolean = false,
    val settings: Boolean = false,
    @SerialName("invite_link") val inviteLink: Boolean = false,
    val requests: Boolean = false,
    val leave: Boolean = false,
)

/** My role (null: not a member) and what follows from it. */
@Serializable
data class GroupView(
    @SerialName("my_role") @EncodeDefault(NEVER) val myRole: String? = null,
    val abilities: GroupAbilities = GroupAbilities(),
    @SerialName("last_admin") val lastAdmin: Boolean = false,
    @SerialName("announce_only") val announceOnly: Boolean = false,
)

/** What `wa_sync.group_read` answers. */
@Serializable
data class WaSyncGroupPage(
    val handle: Long,
    @EncodeDefault(NEVER) val group: StoredGroup? = null,
    val members: List<GroupMember> = emptyList(),
    val total: Int = 0,
    val admins: Int = 0,
    val view: GroupView = GroupView(),
)
