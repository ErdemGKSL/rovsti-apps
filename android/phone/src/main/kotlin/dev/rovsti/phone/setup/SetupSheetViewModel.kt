package dev.rovsti.phone.setup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rovsti.core.getOrNull
import dev.rovsti.core.model.phone.ChecklistAnswer
import dev.rovsti.core.model.phone.ChecklistRequest
import dev.rovsti.core.model.phone.ItemState
import dev.rovsti.core.model.phone.ReopenedRequest
import dev.rovsti.core.model.phone.SetupFacts
import dev.rovsti.core.model.phone.SetupItem
import dev.rovsti.core.model.phone.SetupStep
import dev.rovsti.core.model.phone.XiaomiAction
import dev.rovsti.core.model.phone.XiaomiConfirmations
import dev.rovsti.core.model.phone.XiaomiRequest
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.platform.system.permissions.PermissionGroup
import dev.rovsti.platform.system.permissions.PermissionState
import dev.rovsti.platform.system.permissions.SettingsKind
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** What a button of a setup row does (the label is the screen's: a string per act). */
enum class SetupAct {
    /** Opens this app's App info page (restricted settings, runtime permissions). */
    AppInfo,
    RequestRole,
    DefaultApps,

    /** Asks for the row's permission group; Android still shows its dialog. */
    Allow,

    /** The group was denied for good: only Settings can change it. */
    OpenSettings,
    FullScreen,
    XiaomiAutostart,
    XiaomiBattery,
    XiaomiOther,
    BatteryDialog,
    Confirm,
    Unconfirm,
}

/** The plain-language help under a row's reason (the words are the screen's). */
enum class SetupSteps { Restricted, LockScreen, Popups, Battery, Autostart }

/** Why nothing opened, shown as the line under the checklist. */
enum class SetupNote { CouldNotOpen, }

/** The tail the help text gets on a Xiaomi row. */
enum class StepsTail { None, FellBack, OpenFirst }

@Immutable
data class SetupButtonUi(
    val act: SetupAct,
    val primary: Boolean,
    val group: PermissionGroup? = null,
)

@Immutable
data class SetupRowUi(
    val item: SetupItem,
    val title: String,
    val reason: String,
    val state: ItemState,
    val steps: SetupSteps?,
    val tail: StepsTail,
    val buttons: ImmutableList<SetupButtonUi>,
)

@Immutable
data class SetupUiState(
    val loaded: Boolean = false,
    /** Whether Rovsti Phone is the default phone app now (the notice line shows while it is not). */
    val roleHeld: Boolean = true,
    /** "Something changed: ..." from `setup.reopened` while a done row was lost, else null (the screen says "Finish setup"). */
    val changedTitle: String? = null,
    val complete: Boolean = false,
    val progressLabel: String = "",
    val fraction: Float = 0f,
    val rows: ImmutableList<SetupRowUi> = persistentListOf(),
    val note: SetupNote? = null,
    /** Reminders cannot be exact: the exact-alarm grant is missing (E5.12). Shown as an extra row, not counted. */
    val exactAlarmMissing: Boolean = false,
)

sealed interface SetupAction {
    /** The screen resumed (or opened): read Android again, ask the core what changed. */
    data object Refresh : SetupAction

    data class Press(
        val item: SetupItem,
        val button: SetupButtonUi,
    ) : SetupAction

    /** A settings page was started for [item]; [fellBack] when only App info could be opened. */
    data class SettingsOpened(
        val item: SetupItem,
        val opened: Boolean,
        val fellBack: Boolean,
    ) : SetupAction

    /** The "Exact reminders" row: opens the system page of the exact-alarm grant. */
    data object OpenExactAlarms : SetupAction

    /** The role dialog answered. A refusal offers the Settings route too. */
    data class RoleAnswered(
        val held: Boolean,
    ) : SetupAction
}

/** What the screen carries out (it owns the activity-result launchers and the context). */
sealed interface SetupEffect {
    data object RequestRole : SetupEffect

    data class RequestPermission(
        val group: PermissionGroup,
    ) : SetupEffect

    /** Start the first page of [kind]'s chain that opens, then report `SettingsOpened`. */
    data class OpenSettings(
        val item: SetupItem,
        val kind: SettingsKind,
    ) : SetupEffect

    /** The system dialog that exempts the app from battery optimisation. */
    data object BatteryDialog : SetupEffect

    /** The page where "Alarms and reminders" is granted. */
    data object OpenExactAlarms : SetupEffect
}

/**
 * The Setup sheet (E5.1): the rows are the core's (`setup.steps` words, `setup.checklist` states from the facts Android
 * gives), the Xiaomi confirmations are `setup.xiaomi`, and what changed since the last look is `setup.reopened`. This
 * class only orders the effects and chooses each row's buttons from its state (`apps/phone/src/setup_ui.rs`).
 */
class SetupSheetViewModel(
    private val ops: PhoneOps,
    private val facts: SetupFactsSource,
    private val memory: SetupMemory,
    private val permission: (PermissionGroup) -> PermissionState,
) : ViewModel() {
    private val mutable = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = mutable.asStateFlow()

    private val outbox = Channel<SetupEffect>(Channel.UNLIMITED)
    val effects: Flow<SetupEffect> = outbox.receiveAsFlow()

    private var steps: List<SetupStep> = emptyList()
    private var seen: SetupFacts? = null
    private var reopened: List<SetupItem> = emptyList()
    private var reopenedTitle: String? = null
    private var roleDeclined = false
    private var fellBack: Set<SetupItem> = emptySet()
    private var opened: Set<SetupItem> = emptySet()
    private var note: SetupNote? = null

    init {
        onAction(SetupAction.Refresh)
    }

    fun onAction(action: SetupAction) {
        when (action) {
            SetupAction.Refresh -> {
                viewModelScope.launch { refresh() }
            }

            is SetupAction.Press -> {
                press(action.item, action.button)
            }

            is SetupAction.SettingsOpened -> {
                if (action.fellBack) fellBack = fellBack + action.item
                note = if (action.opened) null else SetupNote.CouldNotOpen
                viewModelScope.launch { refresh() }
            }

            SetupAction.OpenExactAlarms -> {
                outbox.trySend(SetupEffect.OpenExactAlarms)
            }

            is SetupAction.RoleAnswered -> {
                if (!action.held) roleDeclined = true
                viewModelScope.launch { refresh() }
            }
        }
    }

    private fun press(
        item: SetupItem,
        button: SetupButtonUi,
    ) {
        note = null
        when (button.act) {
            SetupAct.AppInfo -> {
                outbox.trySend(SetupEffect.OpenSettings(item, SettingsKind.AppDetails))
            }

            SetupAct.RequestRole -> {
                outbox.trySend(SetupEffect.RequestRole)
            }

            SetupAct.DefaultApps -> {
                outbox.trySend(SetupEffect.OpenSettings(item, SettingsKind.DefaultApps))
            }

            SetupAct.Allow -> {
                button.group?.let { outbox.trySend(SetupEffect.RequestPermission(it)) }
            }

            SetupAct.OpenSettings -> {
                outbox.trySend(SetupEffect.OpenSettings(item, SettingsKind.AppDetails))
            }

            SetupAct.FullScreen -> {
                outbox.trySend(SetupEffect.OpenSettings(item, SettingsKind.FullScreenIntent))
            }

            SetupAct.XiaomiAutostart -> {
                xiaomiOpened(item, SettingsKind.XiaomiAutostart)
            }

            SetupAct.XiaomiBattery -> {
                xiaomiOpened(item, SettingsKind.XiaomiBattery)
            }

            SetupAct.XiaomiOther -> {
                xiaomiOpened(item, SettingsKind.XiaomiPermissions)
            }

            SetupAct.BatteryDialog -> {
                outbox.trySend(SetupEffect.BatteryDialog)
            }

            SetupAct.Confirm, SetupAct.Unconfirm -> {
                viewModelScope.launch { confirm(item, button.act == SetupAct.Confirm) }
            }
        }
    }

    private fun xiaomiOpened(
        item: SetupItem,
        kind: SettingsKind,
    ) {
        // "I did it" may be tapped only once this row's page was opened in this session.
        opened = opened + item
        outbox.trySend(SetupEffect.OpenSettings(item, kind))
    }

    private suspend fun confirm(
        item: SetupItem,
        on: Boolean,
    ) {
        val request =
            XiaomiRequest(
                action = if (on) XiaomiAction.Confirm else XiaomiAction.Unconfirm,
                confirmations = memory.confirmations(),
                item = item,
                appVersion = facts.appVersion,
                opened = opened.toList(),
            )
        val answer = ops.setupXiaomi(request).getOrNull() ?: return
        if (answer.changed) memory.saveConfirmations(answer.confirmations)
        refresh()
    }

    private suspend fun refresh() {
        if (steps.isEmpty()) {
            steps =
                ops
                    .setupSteps()
                    .getOrNull()
                    ?.items
                    .orEmpty()
        }
        // A confirmation made for an older app version is dropped before it is read.
        val stored = memory.confirmations()
        val reconciled =
            ops
                .setupXiaomi(
                    XiaomiRequest(XiaomiAction.Reconcile, confirmations = stored, appVersion = facts.appVersion),
                ).getOrNull()
        val confirmations = reconciled?.confirmations ?: stored
        if (reconciled?.changed == true) memory.saveConfirmations(confirmations)

        val now = facts.facts()
        val request = request(now, confirmations)
        val list = ops.setupChecklist(request).getOrNull() ?: return
        trackReopened(now, confirmations, list)
        seen = now

        val rows =
            list.items.mapNotNull { status ->
                val step = steps.firstOrNull { it.item == status.item } ?: return@mapNotNull null
                row(step, status.state, confirmations)
            }
        mutable.value =
            SetupUiState(
                loaded = true,
                roleHeld = now.roleHeld,
                changedTitle = if (list.progress.complete) null else reopenedTitle,
                complete = list.progress.complete,
                progressLabel = list.progress.label,
                fraction = if (list.progress.total == 0) 1f else list.progress.done.toFloat() / list.progress.total,
                rows = rows.toImmutableList(),
                note = note,
                exactAlarmMissing = !facts.exactAlarmsAllowed(),
            )
    }

    private fun request(
        now: SetupFacts,
        confirmations: XiaomiConfirmations,
    ) = ChecklistRequest(
        facts = now,
        xiaomi = facts.xiaomi,
        confirmations = confirmations.takeIf { facts.xiaomi },
        appVersion = facts.appVersion,
    )

    /** `setup.reopened` between the facts of the last look and now; the lost rows stay named until they are done again. */
    private suspend fun trackReopened(
        now: SetupFacts,
        confirmations: XiaomiConfirmations,
        list: ChecklistAnswer,
    ) {
        val before = seen ?: return
        val answer =
            ops
                .setupReopened(ReopenedRequest(request(before, confirmations), request(now, confirmations)))
                .getOrNull() ?: return
        val states = list.items.associate { it.item to it.state }
        reopened =
            (reopened + answer.reopened).distinct().filter { states[it]?.let { s -> s != ItemState.Done } == true }
        reopenedTitle =
            when {
                reopened.isEmpty() -> null
                answer.reopened.isNotEmpty() -> answer.title
                else -> reopenedTitle
            }
    }

    private suspend fun row(
        step: SetupStep,
        itemState: ItemState,
        confirmations: XiaomiConfirmations,
    ): SetupRowUi {
        val item = step.item
        val done = itemState.done()
        val byHand = step.xiaomi && item != SetupItem.Autostart && done && confirmed(item, confirmations).confirmed
        val canConfirm = step.xiaomi && confirmed(item, confirmations).canConfirm
        val buttons = if (done) doneButtons(item, byHand) else openButtons(item, itemState, canConfirm)
        return SetupRowUi(
            item = item,
            title = step.title,
            reason = step.reason,
            state = itemState,
            steps = if (done) null else stepsOf(item),
            tail = if (done) StepsTail.None else tailOf(item, itemState, canConfirm),
            buttons = buttons.toImmutableList(),
        )
    }

    private suspend fun confirmed(
        item: SetupItem,
        confirmations: XiaomiConfirmations,
    ) = ops
        .setupXiaomi(
            XiaomiRequest(
                XiaomiAction.Query,
                confirmations = confirmations,
                item = item,
                appVersion = facts.appVersion,
                opened = opened.toList(),
            ),
        ).getOrNull()
        ?.let { Confirmed(it.confirmed, it.canConfirm) } ?: Confirmed(false, false)

    private data class Confirmed(
        val confirmed: Boolean,
        val canConfirm: Boolean,
    )

    /** Buttons on a done row: only a Xiaomi row the owner confirmed by hand can be taken back. */
    private fun doneButtons(
        item: SetupItem,
        confirmedByHand: Boolean,
    ): List<SetupButtonUi> =
        if (item.xiaomi() && item != SetupItem.Autostart && confirmedByHand) {
            listOf(SetupButtonUi(SetupAct.Unconfirm, primary = false))
        } else {
            emptyList()
        }

    private fun openButtons(
        item: SetupItem,
        itemState: ItemState,
        canConfirm: Boolean,
    ): List<SetupButtonUi> {
        val buttons =
            when (item) {
                SetupItem.DefaultPhoneApp -> {
                    buildList {
                        add(SetupButtonUi(SetupAct.AppInfo, primary = false))
                        add(SetupButtonUi(SetupAct.RequestRole, primary = true))
                        if (roleDeclined) add(SetupButtonUi(SetupAct.DefaultApps, primary = false))
                    }
                }

                SetupItem.PhoneAndCallLog -> {
                    access(PermissionGroup.PHONE)
                }

                SetupItem.Contacts -> {
                    access(PermissionGroup.CONTACTS)
                }

                SetupItem.Notifications -> {
                    access(PermissionGroup.NOTIFICATIONS)
                }

                SetupItem.FullScreenNotifications -> {
                    listOf(SetupButtonUi(SetupAct.FullScreen, primary = true))
                }

                SetupItem.Autostart -> {
                    listOf(SetupButtonUi(SetupAct.XiaomiAutostart, primary = true))
                }

                SetupItem.BatterySaver -> {
                    listOf(
                        SetupButtonUi(SetupAct.XiaomiBattery, primary = true),
                        SetupButtonUi(SetupAct.BatteryDialog, primary = false),
                    )
                }

                SetupItem.ShowOnLockScreen, SetupItem.BackgroundPopups -> {
                    listOf(SetupButtonUi(SetupAct.XiaomiOther, primary = true))
                }
            }
        // Autostart is readable, so "denied" is final; every other Xiaomi row can be confirmed by hand once its page
        // was opened.
        val confirmable =
            item.xiaomi() &&
                (itemState == ItemState.Unverifiable || (itemState == ItemState.NotDone && item != SetupItem.Autostart))
        return if (confirmable && canConfirm) buttons + SetupButtonUi(SetupAct.Confirm, primary = false) else buttons
    }

    private fun access(group: PermissionGroup): List<SetupButtonUi> {
        val forGood = permission(group).let { it == PermissionState.DeniedForGood }
        return listOf(
            SetupButtonUi(if (forGood) SetupAct.OpenSettings else SetupAct.Allow, primary = true, group = group),
        )
    }

    private fun stepsOf(item: SetupItem): SetupSteps? =
        when (item) {
            SetupItem.DefaultPhoneApp -> SetupSteps.Restricted
            SetupItem.ShowOnLockScreen -> SetupSteps.LockScreen
            SetupItem.BackgroundPopups -> SetupSteps.Popups
            SetupItem.BatterySaver -> SetupSteps.Battery
            SetupItem.Autostart -> SetupSteps.Autostart
            else -> null
        }

    private fun tailOf(
        item: SetupItem,
        itemState: ItemState,
        canConfirm: Boolean,
    ): StepsTail =
        when {
            stepsOf(item) == null -> StepsTail.None
            item in fellBack -> StepsTail.FellBack
            item.xiaomi() && itemState != ItemState.NotDone && !canConfirm -> StepsTail.OpenFirst
            else -> StepsTail.None
        }

    private fun ItemState.done() = this == ItemState.Done

    /** The four Xiaomi rows, as `setup.steps` flags them. */
    private fun SetupItem.xiaomi() = steps.any { it.item == this && it.xiaomi }
}
