package dev.rosty.messages.schedule

import android.content.res.Resources
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import dev.rosty.core.Outcome
import dev.rosty.core.model.messages.QuickTimesRequest
import dev.rosty.design.overlay.OverlayController
import dev.rosty.design.overlay.showToast
import dev.rosty.design.overlay.showUndo
import dev.rosty.design.time.LocalRostyClock
import dev.rosty.design.time.RostyClock
import dev.rosty.messages.MessagesContainer
import dev.rosty.messages.MessagesRoute
import dev.rosty.messages.R
import dev.rosty.messages.schedule.theme.ScheduleMetrics
import dev.rosty.platform.system.permissions.SettingsKind
import dev.rosty.platform.system.permissions.startFirst
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

/** [ScheduleWords] from the app's resources. */
fun scheduleWords(res: Resources): ScheduleWords =
    ScheduleWords(
        title = res.getString(R.string.schedule_title),
        quick = { label ->
            when (label) {
                "In 1 hour" -> res.getString(R.string.schedule_in_hour)
                "This evening" -> res.getString(R.string.schedule_this_evening)
                "Tomorrow evening" -> res.getString(R.string.schedule_tomorrow_evening)
                "Tomorrow morning" -> res.getString(R.string.schedule_tomorrow_morning)
                else -> label
            }
        },
        day = res.getString(R.string.schedule_day),
        time = res.getString(R.string.schedule_time),
        earlierDay = res.getString(R.string.schedule_day_earlier),
        laterDay = res.getString(R.string.schedule_day_later),
        earlierTime = res.getString(R.string.schedule_time_earlier),
        laterTime = res.getString(R.string.schedule_time_later),
        confirm = { res.getString(R.string.schedule_confirm, it) },
        tooSoon = res.getString(R.string.schedule_too_soon),
        lateNote = res.getString(R.string.schedule_late_note),
        stripTitle = res.getString(R.string.schedule_strip_title),
        cancel = res.getString(R.string.inbox_cancel),
        cancelFor = { res.getString(R.string.schedule_cancel_for, it) },
        waiting = { time, text -> res.getString(R.string.schedule_strip_waiting, time, text) },
        notSent = { res.getString(R.string.schedule_strip_not_sent, it) },
        more = { res.getQuantityString(R.plurals.schedule_strip_more, it, it) },
    )

/** The schedule labels in the phone's zone, language and 24-hour setting. */
@Composable
fun rememberScheduleLabels(): ScheduleLabels {
    val context = LocalContext.current
    val res = LocalResources.current
    return remember(res) {
        ScheduleLabels(
            ZoneId.systemDefault(),
            res.configuration.locales[0],
            android.text.format.DateFormat
                .is24HourFormat(context),
            ScheduleDayWords(res.getString(R.string.conversation_today), res.getString(R.string.schedule_tomorrow)),
        )
    }
}

/**
 * A long press on the Send button of `ComposerField` (the pill this modifier sits on): [onLongPress] runs after the
 * system's long-press time while the finger rests on the button, and that gesture then sends nothing. It only watches
 * the button's corner on the first pass, so typing, the caret and a normal tap on Send are left alone. Nothing happens
 * while [armed] answers false (an empty composer has no Send button).
 */
fun Modifier.sendLongPress(
    rtl: Boolean,
    armed: () -> Boolean,
    onLongPress: () -> Unit,
): Modifier =
    pointerInput(rtl) {
        val reach = (ScheduleMetrics.SendButton + ScheduleMetrics.SendInset + ScheduleMetrics.SendSlop).toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val atEnd = if (rtl) down.position.x <= reach else down.position.x >= size.width - reach
            if (!atEnd || down.position.y < size.height - reach || !armed()) return@awaitEachGesture
            val held =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation(PointerEventPass.Initial)
                    Unit
                } == null
            if (!held) return@awaitEachGesture
            onLongPress()
            // The rest of this gesture belongs to the long press: the button below must not see a tap.
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }

/** What a conversation page needs for scheduled send: the strip to draw and what its taps do. */
@Stable
class ScheduleHost internal constructor(
    val words: ScheduleWords,
    private val stripState: State<ScheduledStripUi>,
    private val onAsk: (String) -> Unit,
    private val onCancel: (StripKey) -> Unit,
    private val onExpand: () -> Unit,
) {
    val strip: ScheduledStripUi get() = stripState.value

    /** A long press on Send with [text] in the composer: open "Send later". */
    fun ask(text: String) = onAsk(text)

    fun cancel(key: StripKey) = onCancel(key)

    fun expand() = onExpand()
}

/** The recipients of an SMS route: the ones it names, else those of its thread. */
@Composable
fun rememberSmsRecipients(
    container: MessagesContainer,
    route: MessagesRoute.SmsThread,
): State<List<String>> =
    produceState(route.addresses, container, route) {
        if (route.addresses.isEmpty() && route.threadId > 0) {
            value =
                container.sms
                    .threads()
                    .first()
                    .firstOrNull { it.threadId == route.threadId }
                    ?.addresses
                    .orEmpty()
        }
    }

/** Scheduled send of an SMS page: one recipient, on the SIM chosen for the thread; a group cannot be scheduled. */
@Composable
fun rememberSmsScheduleHost(
    container: MessagesContainer,
    overlays: OverlayController,
    route: MessagesRoute.SmsThread,
    composer: TextFieldState,
): ScheduleHost {
    val recipients by rememberSmsRecipients(container, route)
    val to = recipients.singleOrNull()
    val dest: () -> ScheduleDest? = {
        to?.let {
            ScheduleDest.Sms(
                it,
                container.store.prefs.value
                    ?.simChoices
                    ?.get(route.threadId),
            )
        }
    }
    return rememberScheduleHost(container, overlays, to?.let { ScheduleDest.Sms(it, null) }, dest, composer)
}

/** Scheduled send of a WhatsApp page: the gateway keeps the text and sends it. */
@Composable
fun rememberWaScheduleHost(
    container: MessagesContainer,
    overlays: OverlayController,
    jid: String,
    composer: TextFieldState,
): ScheduleHost {
    val dest = remember(jid) { ScheduleDest.WhatsApp(jid) }
    // The gateway's list is read when the chat opens (GPUI `load_remote_scheduled`).
    LaunchedEffect(container, jid) { container.schedule.refreshRemote() }
    return rememberScheduleHost(container, overlays, dest, { dest }, composer)
}

/**
 * The glue of one conversation page (E6.10): the strip of [stripDest] from the app's [ScheduleCenter], the sheet a
 * long press on Send opens, and Cancel. [dest] is asked when the sheet opens (null: this conversation cannot schedule).
 */
@Composable
private fun rememberScheduleHost(
    container: MessagesContainer,
    overlays: OverlayController,
    stripDest: ScheduleDest?,
    dest: () -> ScheduleDest?,
    composer: TextFieldState,
): ScheduleHost {
    val context = LocalContext.current
    val res = LocalResources.current
    val clock = LocalRostyClock.current
    val scope = rememberCoroutineScope()
    val words = remember(res) { scheduleWords(res) }
    val labels = rememberScheduleLabels()
    val all by container.schedule.state.collectAsState()
    val expanded = remember { mutableStateOf(false) }
    val items =
        remember(all, stripDest) { stripDest?.let { ScheduleRules.stripItems(it, all.local, all.remote) }.orEmpty() }
    val strip =
        rememberUpdatedState(
            remember(items, expanded.value, words, labels) {
                ScheduledStripUi.of(items, expanded.value, clock.nowMillis(), words, labels)
            },
        )
    val latestDest by rememberUpdatedState(dest)
    val latestItems by rememberUpdatedState(items)
    val largeText = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE
    return remember(container, overlays, words, labels, composer, scope, largeText) {
        val flow = ScheduleFlow(container, overlays, scope, clock, res, words, labels, composer, largeText)
        ScheduleHost(
            words = words,
            stripState = strip,
            onAsk = { text -> flow.ask(text, latestDest()) { context.startFirst(it) } },
            onCancel = { key -> flow.cancel(key, latestItems.firstOrNull { it.key == key }?.body) },
            onExpand = { expanded.value = true },
        )
    }
}

/** What the sheet and the strip do with a choice; the toasts are the GPUI app's pills. */
private class ScheduleFlow(
    private val container: MessagesContainer,
    private val overlays: OverlayController,
    private val scope: CoroutineScope,
    private val clock: RostyClock,
    private val res: Resources,
    private val words: ScheduleWords,
    private val labels: ScheduleLabels,
    private val composer: TextFieldState,
    private val largeText: Boolean,
) {
    fun ask(
        text: String,
        dest: ScheduleDest?,
        openSettings: (List<android.content.Intent>) -> Unit,
    ) {
        val body = text.trim()
        when {
            body.isEmpty() -> overlays.showToast(res.getString(R.string.schedule_type_first))
            dest == null -> overlays.showToast(res.getString(R.string.schedule_one_to_one))
            else -> scope.launch { open(body, dest, openSettings) }
        }
    }

    private suspend fun open(
        body: String,
        dest: ScheduleDest,
        openSettings: (List<android.content.Intent>) -> Unit,
    ) {
        val now = clock.nowMillis()
        val offset =
            ZoneId
                .systemDefault()
                .rules
                .getOffset(Instant.ofEpochMilli(now))
                .totalSeconds
        // Without the native library there are no quick times; the steppers still schedule.
        val quick =
            (container.ops.quickTimes(QuickTimesRequest(now, offset)) as? Outcome.Ok)
                ?.value
                ?.times
                .orEmpty()
                .map { QuickPick(it.label, it.atMs) }
        val ui =
            ScheduleSheetUi(
                body = body,
                quick = quick.toImmutableList(),
                lateNote = dest is ScheduleDest.Sms && !container.specialAccess.exactAlarms(),
                tall = largeText,
            )
        val state = ScheduleSheetState(labels.initialPick(now))
        var entry: dev.rosty.design.overlay.OverlayEntry? = null
        entry =
            overlays.presentSchedule(
                ui,
                state,
                words,
                labels,
                onPick = { at ->
                    state.busy = true
                    scope.launch {
                        val outcome = container.schedule.schedule(dest, body, at)
                        state.busy = false
                        when (outcome) {
                            is ScheduleOutcome.Scheduled -> {
                                entry?.let(overlays::dismiss)
                                // Only what was scheduled leaves the composer: the user may have typed on.
                                if (composer.text.toString().trim() == body) composer.setTextAndPlaceCursorAtEnd("")
                                val time = labels.whenLabel(outcome.atMs, clock.nowMillis())
                                overlays.showToast(res.getString(R.string.schedule_done, time))
                            }

                            ScheduleOutcome.TooSoon -> {
                                state.error = words.tooSoon
                            }

                            ScheduleOutcome.NotLinked -> {
                                state.error = res.getString(R.string.wa_thread_not_linked)
                            }

                            ScheduleOutcome.Failed -> {
                                state.error = res.getString(R.string.schedule_failed)
                            }
                        }
                    }
                },
                onLateNote = { openSettings(container.specialAccess.settingsChain(SettingsKind.ExactAlarms)) },
            )
    }

    fun cancel(
        key: StripKey,
        body: String?,
    ) {
        scope.launch {
            if (container.schedule.cancel(key)) {
                // The text comes back to be edited, but never over what is being typed.
                body?.let { ScheduleRules.textAfterCancel(composer.text.toString(), it) }?.let {
                    composer.setTextAndPlaceCursorAtEnd(it)
                }
                overlays.showToast(res.getString(R.string.schedule_cancelled))
            } else {
                overlays.showToast(res.getString(R.string.schedule_cancel_failed))
            }
        }
    }
}

/**
 * The app-wide notices of scheduled send (fired settle): a scheduled SMS that could not be sent offers to reopen it in
 * its conversation; a WhatsApp text the gateway could not send is named by its chat.
 */
@Composable
fun ScheduleNotices(
    container: MessagesContainer,
    overlays: OverlayController,
    onReopen: (MessagesRoute) -> Unit,
) {
    val res = LocalResources.current
    val reopen by rememberUpdatedState(onReopen)
    LaunchedEffect(container, overlays, res) {
        container.schedule.notices.collect { notice ->
            when (notice) {
                is ScheduleNotice.SmsFailed -> {
                    val text =
                        notice.to?.let { res.getString(R.string.schedule_failed_to, it) }
                            ?: res.getQuantityString(R.plurals.schedule_failed_many, notice.count, notice.count)
                    val again = notice.reopen
                    if (again == null) {
                        overlays.showToast(text)
                    } else {
                        overlays.showUndo(
                            text,
                            { reopen(MessagesRoute.SmsThread(0, listOf(again.first), again.second)) },
                            res.getString(R.string.schedule_reopen),
                        )
                    }
                }

                is ScheduleNotice.WhatsAppFailed -> {
                    val chat =
                        withTimeoutOrNull(CHAT_LOOKUP_MS) { container.waChats.chats.first() }
                            ?.firstOrNull { it.jid == notice.chat }
                    val name = container.names.chatTitle(notice.chat, chat)
                    overlays.showToast(res.getString(R.string.schedule_wa_failed, name))
                }
            }
        }
    }
}

private const val LARGE_TEXT_SCALE = 1.3f
private const val CHAT_LOOKUP_MS = 2_000L
