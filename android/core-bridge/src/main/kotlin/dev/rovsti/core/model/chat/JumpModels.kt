@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.chat

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `date_jump.*`, `unread_jump.*` and `voice_player.*` (E2.11b), mirroring
// `crates/rovsti_ffi/fixtures/{date_jump,unread_jump,voice_player}.*`. Stability: `compose_stability.conf`.

@Serializable
enum class PickAction {
    /** Just describe the pick (today when `startMs` is absent). */
    @SerialName("show")
    Show,

    @SerialName("step_day")
    StepDay,

    @SerialName("step_month")
    StepMonth,
}

@Serializable
data class DatePickRequest(
    /** Local midnight of the picked day; today when absent. */
    @SerialName("start_ms") @EncodeDefault(NEVER) val startMs: Long? = null,
    val action: PickAction,
    @EncodeDefault(NEVER) val delta: Int = 0,
    @SerialName("now_ms") val nowMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

@Serializable
data class QuickPick(
    val label: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("pick_label") val pickLabel: String,
)

@Serializable
data class DatePickAnswer(
    /** Local midnight of the day, in epoch milliseconds. */
    @SerialName("start_ms") val startMs: Long,
    /** "Today", "Yesterday", "Mon 9 Nov" or "9 Nov 2025". */
    val label: String,
    @SerialName("is_today") val isToday: Boolean,
    /** Yesterday, 1 week ago, 1 month ago. */
    @SerialName("quick_picks") val quickPicks: List<QuickPick>,
)

@Serializable
data class DateStepRequest(
    /** The loaded timeline, oldest first. */
    @SerialName("at_ms") val atMs: List<Long>,
    @SerialName("has_older") val hasOlder: Boolean,
    @SerialName("pages_loaded") val pagesLoaded: Int,
    @SerialName("start_ms") val startMs: Long,
    /** The picked day's label, for the pills. */
    val label: String,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
)

/** What to do for a chosen day. */
@Serializable
enum class DateStepKind {
    /** Scroll to and glow the message at `index`. */
    @SerialName("reveal")
    Reveal,

    /** Load one more older page, then ask again. */
    @SerialName("load_older")
    LoadOlder,

    /** The page cap was hit before reaching the day. */
    @SerialName("further_back")
    FurtherBack,

    /** Nothing on or after that day: go to the newest message. */
    @SerialName("newest")
    Newest,

    /** The conversation has no messages. */
    @SerialName("empty")
    Empty,
}

@Serializable
data class DateStepAnswer(
    val kind: DateStepKind,
    /** The index to scroll to and glow, for [DateStepKind.Reveal]. */
    val index: Int?,
    /** The pill, when the step needs one. */
    val notice: String?,
    /** For a reveal: the pill when the message is on a later day than the chosen one. */
    @SerialName("later_notice") val laterNotice: String?,
    @SerialName("max_pages") val maxPages: Int,
)

@Serializable
enum class UnreadEvent {
    /** The list scrolled; `atEnd` says whether the newest message is in view. */
    @SerialName("scrolled")
    Scrolled,

    /** `incoming` messages arrived. */
    @SerialName("arrived")
    Arrived,
}

@Serializable
data class UnreadStateRequest(
    @EncodeDefault(NEVER) val away: Boolean = false,
    @EncodeDefault(NEVER) val unseen: Int = 0,
    val event: UnreadEvent,
    @SerialName("at_end") @EncodeDefault(NEVER) val atEnd: Boolean = false,
    @EncodeDefault(NEVER) val incoming: Int = 0,
)

@Serializable
data class UnreadStateAnswer(
    val away: Boolean,
    val unseen: Int,
    /** Whether the jump-to-latest button shows. */
    val visible: Boolean,
    /** "3", "99+" or `null`. */
    val badge: String?,
)

@Serializable
data class UnreadDividerRequest(
    /** Per item, oldest first: is it an incoming message. */
    val incoming: List<Boolean>,
    val unread: Int,
    /** Items shown before and after a reload, for the count of new arrivals. */
    @EncodeDefault(NEVER) val before: Int? = null,
    @EncodeDefault(NEVER) val after: Int? = null,
)

@Serializable
data class UnreadDividerAnswer(
    /** The item index where the unread run begins. */
    val index: Int?,
    /** "1 unread message", "5 unread messages". */
    val label: String,
    /** How many items a reload added (for the "latest" badge). */
    val arrived: Int,
)

@Serializable
data class ChatUnread(
    val key: String,
    val unread: Boolean,
)

@Serializable
data class NextUnreadRequest(
    val order: List<ChatUnread>,
    @EncodeDefault(NEVER) val current: String? = null,
)

@Serializable
data class NextUnreadAnswer(
    /** Index in the request's `order` of the next unread chat after `current`, wrapping. */
    val index: Int?,
    /** Other chats that are unread. */
    val others: Int,
    /** "Next unread", "Next unread · 3" or `null`. */
    val label: String?,
)

/** Playback speed, written with the Rust variant names. */
@Serializable
enum class VoiceSpeed {
    @SerialName("X1")
    X1,

    @SerialName("X1_5")
    X1_5,

    @SerialName("X2")
    X2,
}

@Serializable
data class SpeedsRequest(
    @EncodeDefault(NEVER) val current: VoiceSpeed? = null,
)

@Serializable
data class SpeedOption(
    val speed: VoiceSpeed,
    val factor: Double,
    /** The pill: "1×", "1.5×", "2×". */
    val label: String,
)

@Serializable
data class SpeedsAnswer(
    /** The cycle, in order. */
    val speeds: List<SpeedOption>,
    /** The speed after `current` (1x when absent). */
    val next: SpeedOption,
)

/** The platform player's report. */
@Serializable
data class VoicePlayerState(
    val key: String,
    val playing: Boolean,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    val ended: Boolean,
    val error: String?,
) {
    companion object {
        /** Nothing playing. */
        val Idle = VoicePlayerState("", false, 0, 0, false, null)
    }
}

@Serializable
data class VoiceToggleRequest(
    @EncodeDefault(NEVER) val state: VoicePlayerState = VoicePlayerState.Idle,
    /** The note whose play button was tapped. */
    val key: String,
)

@Serializable
enum class VoiceToggleKind {
    /** The note is playing: pause it. */
    @SerialName("pause")
    Pause,

    /** The note is paused part-way: continue from `fromMs`. */
    @SerialName("resume")
    Resume,

    /** Start from the beginning. */
    @SerialName("start")
    Start,
}

@Serializable
data class VoiceToggleAnswer(
    val plan: VoiceToggleKind,
    @SerialName("from_ms") val fromMs: Long?,
)

@Serializable
data class VoiceViewRequest(
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @EncodeDefault(NEVER) val playing: Boolean = false,
    /** A scrub position (0..1) to turn into a seek target. */
    @EncodeDefault(NEVER) val scrub: Double? = null,
)

@Serializable
data class VoiceViewAnswer(
    /** Fraction played, 0..1. */
    val progress: Double,
    /** "0:12 / 0:42" while playing or paused part-way, else "0:42". */
    @SerialName("time_label") val timeLabel: String,
    @SerialName("seek_ms") val seekMs: Long?,
)
