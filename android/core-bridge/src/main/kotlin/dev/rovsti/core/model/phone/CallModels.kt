@file:OptIn(ExperimentalSerializationApi::class)

package dev.rovsti.core.model.phone

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.EncodeDefault.Mode.NEVER
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// `sim.*`, `setup.*`, `audio.*`, `scene.*`: what the call screens and the setup page show.
// See `crates/rovsti_ffi/src/ops/phone/calls.rs` and `rovsti_core::wire` for the snapshot.

// ---- sim.*

@Serializable
data class PhoneAccountInfo(
    val key: String,
    val label: String = "",
    @SerialName("slot_index") @EncodeDefault(NEVER) val slotIndex: Int? = null,
    @SerialName("color_argb") @EncodeDefault(NEVER) val colorArgb: Int = 0,
    @EncodeDefault(NEVER) val sim: Boolean = false,
    @EncodeDefault(NEVER) val enabled: Boolean = true,
)

/** `phone_accounts` payload. */
@Serializable
data class PhoneAccounts(
    val accounts: List<PhoneAccountInfo> = emptyList(),
    @SerialName("default_key") @EncodeDefault(NEVER) val defaultKey: String? = null,
    @EncodeDefault(NEVER) val ask: Boolean = false,
)

@Serializable
data class SimBuildRequest(
    val accounts: PhoneAccounts,
)

@Serializable
data class SimAccount(
    val key: String,
    val label: String,
    /** The short label on the chip (8 characters at most, "SIM 2" when labels clash). */
    val chip: String,
    val title: String,
    @SerialName("color_argb") val colorArgb: Int,
    val slot: Int?,
    val enabled: Boolean,
)

@Serializable
data class SimBuildAnswer(
    /** The SIM chip and picker only show with two or more SIMs. */
    val visible: Boolean,
    val ask: Boolean,
    @SerialName("default_key") val defaultKey: String?,
    @SerialName("default_label") val defaultLabel: String,
    val accounts: List<SimAccount>,
)

@Serializable
data class SimDotRequest(
    @SerialName("color_argb") val colorArgb: Int,
    @SerialName("fill_argb") val fillArgb: Int,
    @SerialName("min_contrast") @EncodeDefault(NEVER) val minContrast: Double? = null,
)

/** The dot colour moved until it reads against the fill: linear channels and the same as ARGB. */
@Serializable
data class SimDotAnswer(
    val rgb: List<Double>,
    val argb: Int,
)

// ---- setup.*

@Serializable
enum class SetupItem {
    @SerialName("default_phone_app")
    DefaultPhoneApp,

    @SerialName("phone_and_call_log")
    PhoneAndCallLog,

    @SerialName("contacts")
    Contacts,

    @SerialName("notifications")
    Notifications,

    @SerialName("full_screen_notifications")
    FullScreenNotifications,

    @SerialName("autostart")
    Autostart,

    @SerialName("battery_saver")
    BatterySaver,

    @SerialName("show_on_lock_screen")
    ShowOnLockScreen,

    @SerialName("background_popups")
    BackgroundPopups,
}

@Serializable
data class SetupStep(
    val item: SetupItem,
    val key: String,
    val title: String,
    val reason: String,
    val xiaomi: Boolean,
)

@Serializable
data class SetupStepsAnswer(
    val items: List<SetupStep>,
)

/** What a settings page can tell us: granted, denied, or nothing readable (Xiaomi pages). */
@Serializable
enum class Reading {
    @SerialName("granted")
    Granted,

    @SerialName("denied")
    Denied,

    @SerialName("unknown")
    Unknown,
}

/** What Android lets the app read about itself. Absent fields read as false / unknown. */
@Serializable
data class SetupFacts(
    @SerialName("role_held") val roleHeld: Boolean = false,
    @SerialName("phone_and_call_log_granted") val phoneAndCallLogGranted: Boolean = false,
    @SerialName("contacts_granted") val contactsGranted: Boolean = false,
    @SerialName("notifications_granted") val notificationsGranted: Boolean = false,
    @SerialName("full_screen_allowed") val fullScreenAllowed: Boolean = false,
    @SerialName("battery_exempt") val batteryExempt: Boolean = false,
    val autostart: Reading = Reading.Unknown,
    @SerialName("lock_screen") val lockScreen: Reading = Reading.Unknown,
    @SerialName("background_popups") val backgroundPopups: Reading = Reading.Unknown,
)

/** Xiaomi settings the owner confirmed by hand; valid for one app version. */
@Serializable
data class XiaomiConfirmations(
    @SerialName("app_version") val appVersion: String = "",
    val confirmed: Int = 0,
)

@Serializable
data class ChecklistRequest(
    val facts: SetupFacts,
    @EncodeDefault(NEVER) val xiaomi: Boolean = false,
    @EncodeDefault(NEVER) val confirmations: XiaomiConfirmations? = null,
    @SerialName("app_version") @EncodeDefault(NEVER) val appVersion: String = "",
)

@Serializable
enum class ItemState {
    @SerialName("done")
    Done,

    @SerialName("not_done")
    NotDone,

    @SerialName("unverifiable")
    Unverifiable,
}

@Serializable
data class ItemStatus(
    val item: SetupItem,
    val state: ItemState,
)

@Serializable
data class SetupProgress(
    val done: Int,
    val total: Int,
    val complete: Boolean,
    val label: String,
)

@Serializable
data class ChecklistAnswer(
    val items: List<ItemStatus>,
    val progress: SetupProgress,
)

@Serializable
enum class XiaomiAction {
    @SerialName("confirm")
    Confirm,

    @SerialName("unconfirm")
    Unconfirm,

    @SerialName("reconcile")
    Reconcile,

    @SerialName("query")
    Query,
}

@Serializable
data class XiaomiRequest(
    val action: XiaomiAction,
    @EncodeDefault(NEVER) val confirmations: XiaomiConfirmations? = null,
    @EncodeDefault(NEVER) val item: SetupItem? = null,
    @SerialName("app_version") @EncodeDefault(NEVER) val appVersion: String = "",
    /** Items whose settings screen was opened in this session; "Done" may only be tapped for those. */
    @EncodeDefault(NEVER) val opened: List<SetupItem> = emptyList(),
)

@Serializable
data class XiaomiAnswer(
    val confirmations: XiaomiConfirmations,
    val changed: Boolean,
    val confirmed: Boolean,
    @SerialName("can_confirm") val canConfirm: Boolean,
)

@Serializable
data class ReopenedRequest(
    val before: ChecklistRequest,
    val after: ChecklistRequest,
)

@Serializable
data class ReopenedAnswer(
    val reopened: List<SetupItem>,
    val title: String?,
    @SerialName("still_reopened") val stillReopened: List<SetupItem>,
)

// ---- audio.*

@Serializable
enum class EndpointType {
    @SerialName("earpiece")
    Earpiece,

    @SerialName("bluetooth")
    Bluetooth,

    @SerialName("wired_headset")
    WiredHeadset,

    @SerialName("speaker")
    Speaker,

    @SerialName("streaming")
    Streaming,

    @SerialName("unknown")
    Unknown,
}

@Serializable
enum class EndpointHint {
    @SerialName("hearing_aid")
    HearingAid,

    @SerialName("unknown")
    Unknown,
}

/** One audio output of the phone. [id] is a session handle: never persisted. */
@Serializable
data class Endpoint(
    val id: String,
    @SerialName("type") val kind: EndpointType = EndpointType.Unknown,
    @EncodeDefault(NEVER) val name: String? = null,
    @EncodeDefault(NEVER) val selected: Boolean = false,
    @EncodeDefault(NEVER) val hint: EndpointHint? = null,
)

@Serializable
enum class RowKind {
    @SerialName("bluetooth")
    Bluetooth,

    @SerialName("wired_headset")
    WiredHeadset,

    @SerialName("speaker")
    Speaker,

    @SerialName("phone")
    Phone,

    @SerialName("unknown")
    Unknown,
}

/** What a tap on the audio button does. */
@Serializable
enum class RouteDecision {
    @SerialName("toggle")
    Toggle,

    @SerialName("opens_list")
    OpensList,
}

@Serializable
data class AudioRow(
    val id: String,
    val kind: RowKind,
    val label: String,
    val subtitle: String,
    val selected: Boolean,
    val unavailable: Boolean,
    val ghost: Boolean,
    @SerialName("hearing_aid") val hearingAid: Boolean,
    val nameless: Boolean,
)

/** Rows that arrived or vanished; [selection] is the (from, to) ids when the selected route changed. */
@Serializable
data class RouteDiff(
    val added: List<String>,
    val removed: List<String>,
    val selection: List<String?>?,
)

@Serializable
data class AudioRowsRequest(
    val endpoints: List<Endpoint>,
    /** The list the user saw before: vanished devices stay as ghosts. */
    @EncodeDefault(NEVER) val previous: List<Endpoint>? = null,
)

@Serializable
data class AudioRowsAnswer(
    val rows: List<AudioRow>,
    val decision: RouteDecision,
    @SerialName("any_nameless") val anyNameless: Boolean,
    val diff: RouteDiff,
)

@Serializable
data class AudioDiffRequest(
    val previous: List<Endpoint>?,
    val next: List<Endpoint>,
)

@Serializable
data class AudioDiffAnswer(
    val diff: RouteDiff,
    val empty: Boolean,
)

// ---- scene.*

@Serializable
enum class AudioRoute {
    @SerialName("earpiece")
    Earpiece,

    @SerialName("speaker")
    Speaker,

    @SerialName("bluetooth")
    Bluetooth,

    @SerialName("wired_headset")
    WiredHeadset,

    @SerialName("unknown")
    Unknown,
}

@Serializable
data class AudioState(
    @EncodeDefault(NEVER) val muted: Boolean = false,
    @EncodeDefault(NEVER) val route: AudioRoute = AudioRoute.Unknown,
    @EncodeDefault(NEVER) val routes: List<AudioRoute> = emptyList(),
    @EncodeDefault(NEVER) val endpoints: List<Endpoint> = emptyList(),
)

@Serializable
enum class WireCallState {
    @SerialName("dialing")
    Dialing,

    @SerialName("ringing")
    Ringing,

    @SerialName("active")
    Active,

    @SerialName("holding")
    Holding,

    @SerialName("disconnected")
    Disconnected,

    @SerialName("unknown")
    Unknown,
}

@Serializable
enum class DisconnectCause {
    @SerialName("local")
    Local,

    @SerialName("remote")
    Remote,

    @SerialName("missed")
    Missed,

    @SerialName("rejected")
    Rejected,

    @SerialName("canceled")
    Canceled,

    @SerialName("busy")
    Busy,

    @SerialName("out_of_service")
    OutOfService,

    @SerialName("unreachable")
    Unreachable,

    @SerialName("error")
    Error,

    @SerialName("unknown")
    Unknown,
}

@Serializable
enum class PendingOp {
    @SerialName("answer")
    Answer,

    @SerialName("swap")
    Swap,

    @SerialName("merge")
    Merge,

    @SerialName("unknown")
    Unknown,
}

@Serializable
enum class SilencedBy {
    @SerialName("user")
    User,

    @SerialName("volume")
    Volume,

    @SerialName("flip")
    Flip,

    @SerialName("unknown")
    Unknown,
}

@Serializable
data class AccountRef(
    val key: String,
    @EncodeDefault(NEVER) val label: String = "",
)

/** One call inside a [CallSnapshot]. [caps] and [props] are the Telecom bit sets. */
@Serializable
data class WireCall(
    val id: Int,
    @EncodeDefault(NEVER) val state: WireCallState = WireCallState.Unknown,
    @SerialName("number_raw") @EncodeDefault(NEVER) val numberRaw: String = "",
    @EncodeDefault(NEVER) val normalized: String = "",
    @SerialName("display_name") @EncodeDefault(NEVER) val displayName: String? = null,
    @SerialName("contact_id") @EncodeDefault(NEVER) val contactId: Long? = null,
    @SerialName("connect_time_ms") @EncodeDefault(NEVER) val connectTimeMs: Long = 0,
    @SerialName("started_ms") @EncodeDefault(NEVER) val startedMs: Long = 0,
    @SerialName("can_hold") @EncodeDefault(NEVER) val canHold: Boolean = false,
    @SerialName("is_incoming") @EncodeDefault(NEVER) val isIncoming: Boolean = false,
    @SerialName("disconnect_cause") @EncodeDefault(NEVER) val disconnectCause: DisconnectCause? = null,
    @SerialName("parent_id") @EncodeDefault(NEVER) val parentId: Int? = null,
    @EncodeDefault(NEVER) val children: List<Int> = emptyList(),
    @EncodeDefault(NEVER) val caps: Long = 0,
    @EncodeDefault(NEVER) val props: Long = 0,
    @SerialName("account_key") @EncodeDefault(NEVER) val accountKey: String? = null,
    @SerialName("display_name_alt") @EncodeDefault(NEVER) val displayNameAlt: String? = null,
    @SerialName("hold_since_ms") @EncodeDefault(NEVER) val holdSinceMs: Long? = null,
)

/** The whole call state, pushed on every change. Absent fields read as their defaults. */
@Serializable
data class CallSnapshot(
    val v: Int = 1,
    @EncodeDefault(NEVER) val seq: Long = 0,
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long = 0,
    @SerialName("keyguard_locked") @EncodeDefault(NEVER) val keyguardLocked: Boolean = true,
    @EncodeDefault(NEVER) val audio: AudioState = AudioState(),
    @SerialName("foreground_call_id") @EncodeDefault(NEVER) val foregroundCallId: Int? = null,
    @SerialName("waiting_call_id") @EncodeDefault(NEVER) val waitingCallId: Int? = null,
    @SerialName("background_call_id") @EncodeDefault(NEVER) val backgroundCallId: Int? = null,
    @SerialName("ended_call_id") @EncodeDefault(NEVER) val endedCallId: Int? = null,
    @SerialName("can_add_call") @EncodeDefault(NEVER) val canAddCall: Boolean = false,
    @SerialName("can_merge") @EncodeDefault(NEVER) val canMerge: Boolean = false,
    @SerialName("can_swap") @EncodeDefault(NEVER) val canSwap: Boolean = false,
    @EncodeDefault(NEVER) val pending: PendingOp? = null,
    @SerialName("pending_failed") @EncodeDefault(NEVER) val pendingFailed: PendingOp? = null,
    @SerialName("ringer_silenced") @EncodeDefault(NEVER) val ringerSilenced: Boolean = false,
    @SerialName("silenced_by") @EncodeDefault(NEVER) val silencedBy: SilencedBy? = null,
    @SerialName("selecting_account") @EncodeDefault(NEVER) val selectingAccount: Boolean = false,
    @EncodeDefault(NEVER) val accounts: List<AccountRef> = emptyList(),
    @EncodeDefault(NEVER) val calls: List<WireCall> = emptyList(),
)

@Serializable
data class SceneRequest(
    val snapshot: CallSnapshot,
)

/** A call as the screen draws it. */
@Serializable
data class SceneCall(
    val id: Int,
    val state: WireCallState,
    /** Name, formatted number or "Unknown". */
    val label: String,
    /** "Calling...", the timer, "On hold", "Call ended". */
    val headline: String,
    /** Second line of a failed call. */
    val reason: String?,
    /** A ring-out the owner should call back. */
    val missed: Boolean,
)

@Serializable
data class SceneParticipant(
    val id: Int,
    val index: Int,
    val label: String,
    @SerialName("can_split") val canSplit: Boolean,
    @SerialName("can_drop") val canDrop: Boolean,
)

@Serializable
enum class SceneControl {
    @SerialName("mute")
    Mute,

    @SerialName("keypad")
    Keypad,

    @SerialName("audio")
    Audio,

    @SerialName("add_call")
    AddCall,

    @SerialName("merge")
    Merge,

    @SerialName("hold_swap")
    HoldSwap,

    @SerialName("hold_and_answer")
    HoldAndAnswer,
}

@Serializable
enum class DisabledReason {
    @SerialName("emergency")
    Emergency,

    @SerialName("pending")
    Pending,

    @SerialName("incoming")
    Incoming,

    @SerialName("two_calls")
    TwoCalls,

    @SerialName("add_call_carrier")
    AddCallCarrier,

    @SerialName("needs_two_calls")
    NeedsTwoCalls,

    @SerialName("merge_carrier")
    MergeCarrier,

    @SerialName("hold_unsupported")
    HoldUnsupported,

    @SerialName("swap_unsupported")
    SwapUnsupported,

    @SerialName("current_call_cannot_hold")
    CurrentCallCannotHold,

    @SerialName("on_hold")
    OnHold,

    @SerialName("connecting")
    Connecting,

    @SerialName("whats_app_call")
    WhatsAppCall,
}

@Serializable
data class DisabledControl(
    val control: SceneControl,
    val reason: DisabledReason,
    val text: String,
)

@Serializable
enum class HoldSlot {
    @SerialName("hold")
    Hold,

    @SerialName("swap")
    Swap,
}

@Serializable
enum class SceneLayout {
    @SerialName("empty")
    Empty,

    @SerialName("single")
    Single,

    @SerialName("two")
    Two,

    @SerialName("conference")
    Conference,
}

@Serializable
data class SceneAnswer(
    val layout: SceneLayout,
    val primary: SceneCall?,
    val held: SceneCall?,
    val waiting: SceneCall?,
    val ended: SceneCall?,
    @SerialName("hold_slot") val holdSlot: HoldSlot,
    val participants: List<SceneParticipant>,
    /** Conference children the carrier named but the snapshot lacks. */
    val skipped: Int,
    /** "n people" when children exist but none can be drawn. */
    @SerialName("participants_summary") val participantsSummary: String?,
    /** Only the controls that are dimmed, with why. */
    val disabled: List<DisabledControl>,
)

@Serializable
data class SceneDiffRequest(
    val old: CallSnapshot,
    val new: CallSnapshot,
    @EncodeDefault(NEVER) val endpoints: Boolean = false,
)

@Serializable
data class SceneDiff(
    val applied: Boolean,
    val primary: Boolean,
    val held: Boolean,
    val waiting: Boolean,
    val ended: Boolean,
    val endpoints: Boolean,
    val participants: Boolean,
    val controls: Boolean,
    val audio: Boolean,
    val session: Boolean,
)

@Serializable
data class SceneDiffAnswer(
    val diff: SceneDiff,
    val changed: Boolean,
)

@Serializable
data class SceneTextsRequest(
    val caller: String,
    @SerialName("talk_s") @EncodeDefault(NEVER) val talkS: Long = 0,
    @SerialName("now_ms") @EncodeDefault(NEVER) val nowMs: Long = 0,
    @SerialName("connect_time_ms") @EncodeDefault(NEVER) val connectTimeMs: Long = 0,
)

@Serializable
data class SceneTextsAnswer(
    @SerialName("missed_headline") val missedHeadline: String,
    @SerialName("incoming_announcement") val incomingAnnouncement: String,
    @SerialName("after_call") val afterCall: String,
    /** The running timer; empty before the call connects. */
    val elapsed: String,
)
