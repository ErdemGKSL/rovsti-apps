package dev.rosty.stops.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.rosty.core.Outcome
import dev.rosty.core.model.core.Empty
import dev.rosty.core.model.phone.DirRequest
import dev.rosty.core.model.stops.AlarmPlan
import dev.rosty.core.model.stops.AlarmPlanRequest
import dev.rosty.core.model.stops.AlarmSound
import dev.rosty.core.model.stops.GtfsImportRequest
import dev.rosty.core.model.stops.GtfsSummaryRequest
import dev.rosty.core.model.stops.PrefsChoicesAnswer
import dev.rosty.core.model.stops.PrefsEdit
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.core.model.stops.TestRing
import dev.rosty.core.model.stops.VibrationPattern
import dev.rosty.core.ops.StopsOps
import dev.rosty.design.text.UiText
import dev.rosty.platform.location.AlarmPick
import dev.rosty.stops.R
import dev.rosty.stops.data.StopsStore
import dev.rosty.stops.home.tileServerHost
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.util.TimeZone

/** One vibration pattern of the picker: its id and the buzz of its preview (Rust's choices). */
@Immutable
data class VibrationChoiceUi(
    val id: VibrationPattern,
    val previewMs: ImmutableList<Long>,
)

/** The alarm settings as the pages draw them; Rust derived every flag. */
@Immutable
data class AlarmUi(
    val sound: Boolean = true,
    val vibrate: Boolean = true,
    val snoozeMin: Int = 5,
    /** The picked sound's own title; null for the system's default alarm. */
    val soundTitle: String? = null,
    val soundUri: String? = null,
    val volumePct: Int = 80,
    val volumeChoices: ImmutableList<Int> = persistentListOf(),
    val vibration: VibrationPattern = VibrationPattern.Standard,
    val vibrationChoices: ImmutableList<VibrationChoiceUi> = persistentListOf(),
    /** `alarm_is_default`: the Reset row only shows when something differs. */
    val isDefault: Boolean = true,
)

/** Settings > Transit data (AD-ST6). */
@Immutable
data class TransitUi(
    val hasFeed: Boolean = false,
    /** "1 234 stops, 87 lines" (the core's text). */
    val summary: String? = null,
    /** "Imported 5 Nov" (the core's text). */
    val imported: String? = null,
    /** A feed is being read. */
    val reading: Boolean = false,
    val error: UiText? = null,
)

@Immutable
data class SettingsUiState(
    val loading: Boolean = true,
    val alarm: AlarmUi = AlarmUi(),
    /** The host of the tile server in use. */
    val tileHost: String = tileServerHost(null),
    val transit: TransitUi = TransitUi(),
)

/** What the page shows once: a toast, or a toast with Undo. */
sealed interface SettingsEffect {
    data class Toast(
        val text: UiText,
    ) : SettingsEffect

    /** [undo] puts the earlier value back. */
    data class Undo(
        val text: UiText,
        val undo: () -> Unit,
    ) : SettingsEffect
}

/**
 * The Settings root, the Alarm page and the transit feed (E7.10). Every rule is Rust's: the edits go through
 * `stops.prefs_edit` ([StopsStore.edit]; a value picked from `stops.prefs_choices` is reached through Rust's own cycle),
 * the alarm plan and test ring through `stops.alarm_plan`, the feed through `stops.gtfs_import` / `gtfs_summary` /
 * `store_gtfs_remove`. Kotlin only words the answers and plays them on the phone ([device]).
 */
class SettingsViewModel(
    private val store: StopsStore,
    private val ops: StopsOps,
    private val device: AlarmDevice,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** The home's hook: the map asks for its transit stops again. */
    private val transitChanged: () -> Unit = {},
) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = mutable.asStateFlow()

    private val effectChannel = Channel<SettingsEffect>(Channel.BUFFERED)
    val effects: Flow<SettingsEffect> = effectChannel.receiveAsFlow()

    private var choices: PrefsChoicesAnswer? = null

    init {
        viewModelScope.launch {
            choices = (ops.prefsChoices(Empty) as? Outcome.Ok)?.value
            describe()
            loadTransit()
            mutable.update { it.copy(loading = false) }
        }
        viewModelScope.launch { store.prefs.collect { prefs -> mutable.update { it.withPrefs(prefs) } } }
    }

    private fun SettingsUiState.withPrefs(prefs: StopsPrefs): SettingsUiState =
        copy(
            tileHost = tileServerHost(prefs.tileUrl),
            alarm =
                alarm.copy(
                    sound = prefs.sound,
                    vibrate = prefs.vibrate,
                    snoozeMin = prefs.snoozeMin,
                    soundTitle = prefs.alarmSound?.title,
                    soundUri = prefs.alarmSound?.uri,
                    volumePct = prefs.volumePct,
                    vibration = prefs.vibration,
                ),
        )

    /** The derived flags and the choice lists. */
    private suspend fun describe() {
        val answer = (store.describe() as? Outcome.Ok)?.value ?: return
        val c = choices
        mutable.update {
            it.withPrefs(answer.prefs).let { s ->
                s.copy(
                    alarm =
                        s.alarm.copy(
                            isDefault = answer.alarmIsDefault,
                            volumeChoices = c?.volumePct.orEmpty().toImmutableList(),
                            vibrationChoices =
                                c
                                    ?.vibration
                                    .orEmpty()
                                    .map { v -> VibrationChoiceUi(v.id, v.previewMs.toImmutableList()) }
                                    .toImmutableList(),
                        ),
                )
            }
        }
    }

    private fun toast(
        @StringRes id: Int,
        vararg args: Any,
    ) {
        effectChannel.trySend(SettingsEffect.Toast(UiText.Res(id, args.toList())))
    }

    private fun edit(
        edit: PrefsEdit,
        maxSteps: Int = 1,
        until: ((StopsPrefs) -> Boolean)? = null,
        then: (changed: Boolean) -> Unit = {},
    ) {
        viewModelScope.launch {
            when (val done = store.edit(edit, maxSteps, until)) {
                is Outcome.Err -> {
                    toast(R.string.settings_save_failed)
                }

                is Outcome.Ok -> {
                    mutable.update { it.copy(alarm = it.alarm.copy(isDefault = done.value.alarmIsDefault)) }
                    then(done.value.changed)
                }
            }
        }
    }

    // ---- alarm ----

    fun toggleSound() = edit(PrefsEdit.ToggleSound) { changed -> if (!changed) toast(R.string.settings_keep_one_on) }

    fun toggleVibrate() =
        edit(PrefsEdit.ToggleVibrate) { changed -> if (!changed) toast(R.string.settings_keep_one_on) }

    /** The next snooze length (Rust's cycle). */
    fun nextSnooze() = edit(PrefsEdit.NextSnooze)

    fun setVolume(pct: Int) {
        val steps = state.value.alarm.volumeChoices.size + 1
        edit(PrefsEdit.NextVolume, steps, { it.volumePct == pct })
    }

    /** Picks a pattern and buzzes its preview. */
    fun setVibration(choice: VibrationChoiceUi) {
        val steps = state.value.alarm.vibrationChoices.size + 1
        edit(PrefsEdit.NextVibration, steps, { it.vibration == choice.id }) {
            if (!device.vibrate(choice.previewMs)) toast(R.string.settings_no_vibrator)
        }
    }

    /** The system picker answered. */
    fun onSoundPicked(pick: AlarmPick) {
        val before = store.prefs.value.alarmSound
        val edit =
            when (pick) {
                AlarmPick.Cancelled -> return
                AlarmPick.Default -> PrefsEdit.ClearSound
                is AlarmPick.Chosen -> PrefsEdit.SetSound(pick.uri, pick.title)
            }
        edit(edit) {
            val name: UiText =
                when {
                    pick !is AlarmPick.Chosen -> UiText.Res(R.string.settings_sound_default)
                    pick.title.isBlank() -> UiText.Res(R.string.settings_sound_unnamed)
                    else -> UiText.Literal(pick.title)
                }
            effectChannel.trySend(
                SettingsEffect.Undo(UiText.Res(R.string.settings_sound_set, listOf(name))) { restoreSound(before) },
            )
        }
    }

    /** No app answered the sound picker. */
    fun pickerMissing() = toast(R.string.settings_picker_missing)

    private fun restoreSound(sound: AlarmSound?) =
        edit(sound?.let { PrefsEdit.SetSound(it.uri, it.title) } ?: PrefsEdit.ClearSound)

    /** Plays the alarm sound for three seconds at the chosen volume (S14). */
    fun testVolume() {
        viewModelScope.launch {
            val plan = (ops.alarmPlan(AlarmPlanRequest(store.prefs.value)) as? Outcome.Ok)?.value ?: return@launch
            val json = Json.encodeToString(AlarmPlan.serializer(), plan.plan)
            if (device.playSound(
                    json,
                )
            ) {
                toast(R.string.settings_test_volume_playing)
            } else {
                toast(R.string.settings_alarm_busy)
            }
        }
    }

    /** Rings once with the sound and vibration as set. */
    fun testAlarm() {
        viewModelScope.launch {
            val plan = (ops.alarmPlan(AlarmPlanRequest(store.prefs.value)) as? Outcome.Ok)?.value ?: return@launch
            if (!device.testRing(Json.encodeToString(TestRing.serializer(), plan.testRing))) {
                toast(R.string.settings_alarm_busy)
            }
        }
    }

    /** S39, after the page's confirmation: only the alarm settings go back to their defaults; Undo restores them. */
    fun resetAlarm() {
        val before = store.prefs.value
        edit(PrefsEdit.AlarmDefaults) { changed ->
            if (changed) {
                effectChannel.trySend(
                    SettingsEffect.Undo(UiText.Res(R.string.settings_alarm_reset_done)) {
                        viewModelScope.launch {
                            store.update { before }
                            describe()
                        }
                    },
                )
            }
        }
    }

    /** The Alarm page went away: no test sound outlives it. */
    fun leaveAlarm() = device.stopSound()

    // ---- transit ----

    private suspend fun loadTransit(skippedRows: Long? = null) {
        val now = nowMs()
        val tz = TimeZone.getDefault().getOffset(now) / MS_PER_S
        when (val summary = ops.gtfsSummary(GtfsSummaryRequest(now, tz, skippedRows))) {
            is Outcome.Err -> {
                Unit
            }

            is Outcome.Ok -> {
                val s = summary.value
                mutable.update {
                    it.copy(transit = it.transit.copy(hasFeed = s.hasFeed, summary = s.summary, imported = s.imported))
                }
            }
        }
    }

    /** The picker could not hand the file over. */
    fun importFailed(tooLarge: Boolean) {
        val why = if (tooLarge) R.string.settings_transit_too_large else R.string.settings_transit_unreadable
        mutable.update { it.copy(transit = it.transit.copy(error = UiText.Res(why))) }
    }

    /** Reads the GTFS zip at [path] (a copy in the cache, deleted afterwards) and saves it as the feed. */
    fun importFeed(path: String) {
        if (state.value.transit.reading) return
        mutable.update { it.copy(transit = it.transit.copy(reading = true, error = null)) }
        viewModelScope.launch {
            val answer = ops.gtfsImport(GtfsImportRequest(path, nowMs(), save = true))
            File(path).delete()
            when (answer) {
                is Outcome.Err -> {
                    val why = answer.error.message.orEmpty()
                    mutable.update {
                        it.copy(
                            transit =
                                it.transit.copy(
                                    reading = false,
                                    error =
                                        if (why.isBlank()) {
                                            UiText.Res(R.string.settings_transit_unreadable)
                                        } else {
                                            UiText.Res(R.string.settings_transit_failed, listOf(why))
                                        },
                                ),
                        )
                    }
                }

                is Outcome.Ok -> {
                    loadTransit(answer.value.report.skippedRows)
                    mutable.update { it.copy(transit = it.transit.copy(reading = false)) }
                    transitChanged()
                    toast(R.string.settings_transit_imported)
                }
            }
        }
    }

    /** After the page's confirmation: deletes the feed. */
    fun removeFeed() {
        viewModelScope.launch {
            when (ops.storeGtfsRemove(DirRequest())) {
                is Outcome.Err -> {
                    toast(R.string.settings_save_failed)
                }

                is Outcome.Ok -> {
                    mutable.update { it.copy(transit = TransitUi()) }
                    loadTransit()
                    transitChanged()
                    toast(R.string.settings_transit_removed)
                }
            }
        }
    }

    private companion object {
        const val MS_PER_S = 1000
    }
}
