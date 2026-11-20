package dev.rosty.messages.wa.group

import android.content.res.Resources
import dev.rosty.core.model.wa.GroupMember
import dev.rosty.core.model.wa.GroupNotice
import dev.rosty.core.model.wa.ResultView
import dev.rosty.messages.R

/**
 * The words of groups (E6-WA R6): a notice line from `wa.group_notice`'s template, a role, an outcome of a bulk
 * action. Rust picks the template and who it names; this only puts the names in the phone's language.
 */
class GroupWords(
    private val res: Resources,
) {
    /** "Ada", "Ada and Ben", "Ada, Ben, Cem and 2 others"; "you" for me. */
    fun people(
        names: List<String>,
        more: Int,
    ): String {
        val all = if (more > 0) names + res.getQuantityString(R.plurals.wa_gn_others, more, more) else names
        return when (all.size) {
            0 -> ""
            1 -> all[0]
            else -> res.getString(R.string.wa_gn_and, all.dropLast(1).joinToString(", "), all.last())
        }
    }

    val you: String get() = res.getString(R.string.wa_gn_you)

    /**
     * The text of [notice]; [actor] is the name of whoever did it ([GroupNotice.actor] `someone`), [targets] the names
     * of the people it names in order (me as [you]).
     */
    fun notice(
        notice: GroupNotice,
        actor: String?,
        targets: List<String>,
    ): String {
        val me = notice.actor.isMe
        val by = actor?.takeIf { notice.actor.type == "someone" }
        val whom = people(targets, notice.more)
        val onlyMe = notice.targets.size == 1 && notice.targets[0].me && notice.more == 0
        val value = notice.value.orEmpty()

        fun three(
            mine: Int,
            theirs: Int,
            nobody: Int,
        ): String =
            when {
                me -> res.getString(mine)
                by != null -> res.getString(theirs, by)
                else -> res.getString(nobody)
            }
        return when (notice.template) {
            "added" ->
                when {
                    me -> res.getString(R.string.wa_gn_added_you, whom)
                    by != null && onlyMe -> res.getString(R.string.wa_gn_added_me_by, by)
                    by != null -> res.getString(R.string.wa_gn_added_by, by, whom)
                    else -> res.getString(R.string.wa_gn_added, whom)
                }

            "removed" ->
                when {
                    me -> res.getString(R.string.wa_gn_removed_you, whom)
                    by != null && onlyMe -> res.getString(R.string.wa_gn_removed_me_by, by)
                    by != null -> res.getString(R.string.wa_gn_removed_by, by, whom)
                    else -> res.getString(R.string.wa_gn_removed, whom)
                }

            "left" -> if (me) res.getString(R.string.wa_gn_left_you) else res.getString(R.string.wa_gn_left, by ?: whom)

            "joined" -> if (me) res.getString(R.string.wa_gn_joined_you) else res.getString(R.string.wa_gn_joined, by ?: whom)

            "promoted" -> if (onlyMe) res.getString(R.string.wa_gn_promoted_me) else res.getString(R.string.wa_gn_promoted, whom)

            "demoted" -> if (onlyMe) res.getString(R.string.wa_gn_demoted_me) else res.getString(R.string.wa_gn_demoted, whom)

            "number_changed" -> res.getString(R.string.wa_gn_number, whom.ifEmpty { by.orEmpty() })

            "subject" ->
                when {
                    me -> res.getString(R.string.wa_gn_subject_you, value)
                    by != null -> res.getString(R.string.wa_gn_subject_by, by, value)
                    else -> res.getString(R.string.wa_gn_subject, value)
                }

            "description" ->
                three(R.string.wa_gn_description_you, R.string.wa_gn_description_by, R.string.wa_gn_description)

            "description_removed" ->
                three(
                    R.string.wa_gn_description_removed_you,
                    R.string.wa_gn_description_removed_by,
                    R.string.wa_gn_description_removed,
                )

            "picture" -> three(R.string.wa_gn_picture_you, R.string.wa_gn_picture_by, R.string.wa_gn_picture)

            "picture_removed" ->
                three(R.string.wa_gn_picture_removed_you, R.string.wa_gn_picture_removed_by, R.string.wa_gn_picture_removed)

            "announce_on" -> three(R.string.wa_gn_announce_on_you, R.string.wa_gn_announce_on_by, R.string.wa_gn_announce_on)

            "announce_off" ->
                three(R.string.wa_gn_announce_off_you, R.string.wa_gn_announce_off_by, R.string.wa_gn_announce_off)

            "locked_on" -> three(R.string.wa_gn_locked_on_you, R.string.wa_gn_locked_on_by, R.string.wa_gn_locked_on)

            "locked_off" -> three(R.string.wa_gn_locked_off_you, R.string.wa_gn_locked_off_by, R.string.wa_gn_locked_off)

            "approval_on" -> three(R.string.wa_gn_approval_on_you, R.string.wa_gn_approval_on_by, R.string.wa_gn_approval_on)

            "approval_off" ->
                three(R.string.wa_gn_approval_off_you, R.string.wa_gn_approval_off_by, R.string.wa_gn_approval_off)

            "link_reset" -> three(R.string.wa_gn_link_reset_you, R.string.wa_gn_link_reset_by, R.string.wa_gn_link_reset)

            "created" -> three(R.string.wa_gn_created_you, R.string.wa_gn_created_by, R.string.wa_gn_created)

            "deleted" -> res.getString(R.string.wa_gn_deleted)

            else -> res.getString(R.string.wa_gn_other)
        }
    }

    /** "Creator", "Admin", "Member". */
    fun role(role: String?): String =
        res.getString(
            when (role) {
                GroupMember.SUPER_ADMIN -> R.string.wa_group_role_superadmin
                GroupMember.ADMIN -> R.string.wa_group_role_admin
                else -> R.string.wa_group_role_member
            },
        )

    /** What happened to one person of a bulk action. */
    fun outcome(view: ResultView): String =
        when (view.outcome) {
            "invite_instead" -> res.getString(R.string.wa_group_outcome_invite)
            "privacy" -> res.getString(R.string.wa_group_outcome_privacy)
            "not_on_whatsapp" -> res.getString(R.string.wa_group_outcome_not_on_whatsapp)
            "already" -> res.getString(R.string.wa_group_outcome_already)
            "full" -> res.getString(R.string.wa_group_outcome_full)
            "left_recently" -> res.getString(R.string.wa_group_outcome_left_recently)
            "not_allowed" -> res.getString(R.string.wa_group_outcome_not_allowed)
            else ->
                view.code?.let { res.getString(R.string.wa_group_outcome_failed, it) }
                    ?: res.getString(R.string.wa_group_outcome_failed_plain)
        }

    fun memberCount(count: Int): String = res.getQuantityString(R.plurals.wa_group_member_count, count, count)

    fun doneCount(count: Int): String = res.getQuantityString(R.plurals.wa_group_done_count, count, count)

    fun createdBy(
        name: String,
        day: String,
    ): String = res.getString(R.string.wa_group_created_by, name, day)

    fun createdOn(day: String): String = res.getString(R.string.wa_group_created_on, day)

    fun myRole(role: String?): String = res.getString(R.string.wa_group_my_role, role(role))

    fun community(name: String): String = res.getString(R.string.wa_group_community, name)

    fun string(id: Int): String = res.getString(id)
}
