@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.messages

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Models of `messages.*` (E2.11a): preferences, drafts, permission decisions and conversation tags, mirroring
// `crates/rovsti_ffi/fixtures/messages.*`. Stability: `compose_stability.conf`.

/** The voice note playback speed (written with the Rust variant names). */
@Serializable
enum class VoiceSpeed {
    @SerialName("X1")
    X1,

    @SerialName("X1_5")
    X1_5,

    @SerialName("X2")
    X2,
}

/** The size of the text in message bubbles. */
@Serializable
enum class ChatTextSize {
    @SerialName("small")
    Small,

    @SerialName("default")
    Default,

    @SerialName("large")
    Large,

    @SerialName("larger")
    Larger,
}

/** A message starred on this device (SMS/MMS have no provider star). */
@Serializable
data class LocalStar(
    @SerialName("thread_id") val threadId: String,
    @SerialName("at_ms") val atMs: Long,
    val author: String,
    val preview: String,
)

/** Local pinned / archived / unread marks, keyed by SMS thread id. */
@Serializable
data class SmsMarks(
    val pinned: List<Long> = emptyList(),
    val archived: List<Long> = emptyList(),
    /** Threads marked unread by hand. */
    val unread: List<Long> = emptyList(),
)

/** `messages-prefs.json`: what Rovsti Messages remembers between runs. */
@Serializable
data class MessagesPrefs(
    val spam: SpamRules,
    /** The SIM last used per SMS thread (thread id to subscription id). */
    @SerialName("sim_choices") val simChoices: Map<Long, Int>,
    /** Master switch for message notifications made by the app. */
    val notifications: Boolean,
    /** Offer a "Copy code" chip on passcode messages. */
    @SerialName("otp_copy") val otpCopy: Boolean,
    /** Notification tags (`sms:<id>`, `wa:<jid>`) the user silenced. */
    val muted: List<String>,
    /** The reply chips above the composers. */
    @SerialName("quick_replies") val quickReplies: List<String>,
    @SerialName("voice_speed") val voiceSpeed: VoiceSpeed,
    /** Local stars on SMS/MMS messages by provider id (`sms:12`). */
    @SerialName("sms_stars") val smsStars: Map<String, LocalStar>,
    /** When a timed mute ends (epoch ms), per tag; a muted tag without an entry is muted until unmuted. */
    @SerialName("mute_until") val muteUntil: Map<String, Long>,
    @SerialName("sms_marks") val smsMarks: SmsMarks,
    @SerialName("chat_text_size") val chatTextSize: ChatTextSize,
)

/** A request that names the data directory (tests); the app's own directory is used when absent. */
@Serializable
data class DirRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
)

@Serializable
data class PrefsLoadAnswer(
    val prefs: MessagesPrefs,
    /** Whether the settings already equal the defaults (the Reset row is disabled). */
    @SerialName("is_reset") val isReset: Boolean,
)

@Serializable
data class PrefsSaveRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    val prefs: MessagesPrefs,
)

/** What `mark_spam` changed, so Undo restores it exactly. */
@Serializable
data class SpamMark(
    @SerialName("rule_added") val ruleAdded: Boolean,
    @SerialName("was_allowed") val wasAllowed: Boolean,
)

/**
 * One change to the preferences: [edit] names it (`mute`, `unmute`, `expire_mutes`, `mark_spam`, `unmark_spam`,
 * `allow_sender`, `unallow_sender`, `remove_allowed`, `rekey`, `reset`), the other fields are its arguments.
 */
@Serializable
data class PrefsEditRequest(
    val prefs: MessagesPrefs,
    val edit: String,
    /** A notification tag: `mute`, `unmute`. */
    @EncodeDefault(NEVER) val tag: String = "",
    /** `mute`: when it ends; absent mutes until the user unmutes. */
    @SerialName("until_ms") @EncodeDefault(NEVER) val untilMs: Long? = null,
    /** `expire_mutes`. */
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long = 0,
    /** `mark_spam`, `unmark_spam`, `allow_sender`, `unallow_sender`. */
    @EncodeDefault(NEVER) val sender: String = "",
    /** `unmark_spam`: what `mark_spam` answered. */
    @EncodeDefault(NEVER) val mark: SpamMark? = null,
    /** `remove_allowed`. */
    @EncodeDefault(NEVER) val index: Int = 0,
    /** `rekey`: the tag of the merged chat's old and new thread. */
    @EncodeDefault(NEVER) val from: String = "",
    @EncodeDefault(NEVER) val into: String = "",
)

@Serializable
data class PrefsEditAnswer(
    val prefs: MessagesPrefs,
    /** Whether the edit changed anything. */
    val changed: Boolean,
    /** `mark_spam`: hand it back to `unmark_spam` for Undo. */
    val mark: SpamMark?,
)

// ---- drafts

/** Conversation tag (`sms:7`, `wa:<jid>`) to the unsent text. */
@Serializable
data class DraftsAnswer(
    val drafts: Map<String, String>,
)

@Serializable
data class DraftsSaveRequest(
    @EncodeDefault(NEVER) val dir: String? = null,
    val drafts: Map<String, String>,
)

@Serializable
data class DraftsPutRequest(
    @EncodeDefault(NEVER) val drafts: Map<String, String> = emptyMap(),
    /** The conversation tag. */
    val thread: String,
    /** Blank text removes the draft; a thread not yet created (`sms:-1`) keeps none. */
    val text: String,
)

@Serializable
data class DraftsRekeyRequest(
    @EncodeDefault(NEVER) val drafts: Map<String, String> = emptyMap(),
    val from: String,
    val into: String,
)

// ---- access

/** The state of an Android permission group. */
@Serializable
enum class PermissionState {
    @SerialName("denied")
    Denied,

    @SerialName("granted")
    Granted,

    /** Denied and Android no longer shows the dialog. */
    @SerialName("permanently_denied")
    PermanentlyDenied,
}

/** The state of the thread list. */
@Serializable
enum class ThreadAccess {
    @SerialName("loading")
    Loading,

    @SerialName("ready")
    Ready,

    /** `READ_SMS` is not granted. */
    @SerialName("denied")
    Denied,

    @SerialName("unavailable")
    Unavailable,
}

/** How to ask for a permission. */
@Serializable
enum class Ask {
    /** Show the system dialog. */
    @SerialName("request")
    Request,

    /** Send the user to the App info page. */
    @SerialName("open_app_info")
    OpenAppInfo,
}

@Serializable
data class AccessRequest(
    /** The state of the permission to decide about. */
    @EncodeDefault(NEVER) val state: PermissionState = PermissionState.Denied,
    /** The app's own notifications switch (the banner shows only when it is on). */
    @SerialName("notifications_on") @EncodeDefault(NEVER) val notificationsOn: Boolean = false,
    /** The notifications permission; absent where Android cannot tell. */
    @SerialName("notify_state") @EncodeDefault(NEVER) val notifyState: PermissionState? = null,
    @EncodeDefault(NEVER) val threads: ThreadAccess = ThreadAccess.Loading,
    @SerialName("contacts_denied") @EncodeDefault(NEVER) val contactsDenied: Boolean = false,
)

/** What to pull again when the app returns to the foreground. */
@Serializable
data class ResumePulls(
    val role: Boolean,
    val access: Boolean,
    val threads: Boolean,
    val contacts: Boolean,
)

@Serializable
data class AccessAnswer(
    /** How to ask for `state`; `null` when granted. */
    val ask: Ask?,
    @SerialName("notify_banner") val notifyBanner: String?,
    @SerialName("contacts_banner") val contactsBanner: String,
    val resume: ResumePulls,
)

// ---- tags

/** `messages.tag` request: give a thread to get its tag, or a tag to get its thread. */
@Serializable
data class TagRequest(
    @EncodeDefault(NEVER) val thread: ThreadId? = null,
    @EncodeDefault(NEVER) val tag: String? = null,
)

@Serializable
data class TagAnswer(
    val tag: String?,
    val thread: ThreadId?,
)
