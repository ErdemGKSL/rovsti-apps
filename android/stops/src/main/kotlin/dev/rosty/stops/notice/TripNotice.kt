package dev.rosty.stops.notice

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.rosty.core.model.stops.Destination
import dev.rosty.core.model.stops.NoticePrefs
import dev.rosty.core.model.stops.StopsPrefs
import dev.rosty.design.list.InsetGroupedSection
import dev.rosty.design.list.ListRowSwitch
import dev.rosty.stops.R
import dev.rosty.stops.trip.ArrivingSoonHook
import dev.rosty.wa.WaLinkState
import dev.rosty.wa.vault.WaVaultState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Test tags of the arriving-soon parts of the trip sheet. */
object TripNoticeTags {
    const val Toggle = "stops-notice-toggle"
}

/**
 * Whether the arriving-soon message can go out with a trip: a chat was chosen and WhatsApp is linked (AD-ST7).
 * While the link is still being read ([settled] false) a chosen chat counts: it can only have been chosen while
 * WhatsApp was connected, and the service sends through the stored link, not through this screen.
 */
fun noticeAvailable(
    notice: NoticePrefs?,
    link: WaLinkState,
    settled: Boolean,
): Boolean = notice != null && notice.chat.isNotEmpty() && (link is WaLinkState.Connected || !settled)

/**
 * The line of the live card about the message (GPUI `notice_line`): sent wins over failed, failed over planned; a
 * trip without a message has no line.
 */
@StringRes
fun noticeLine(
    planned: Boolean,
    sent: Boolean,
    failed: Boolean,
): Int? =
    when {
        sent -> R.string.notice_line_sent
        failed -> R.string.notice_line_failed
        planned -> R.string.notice_line_planned
        else -> null
    }

/**
 * The arriving-soon message of a trip (AD-ST7): the message of the settings goes with every trip unless the user
 * switches it off for the next one with the row under Start (GPUI's "Text <chat> when arriving" toggle). The
 * message itself is sent by the trip service through the gateway; this only decides whether a trip carries one.
 *
 * Since the vault (`plans/wa-vault.md` 13.3) only a trip started while Stops' WhatsApp vault is open carries the
 * message: [hold] copies the link into the trip's RAM (Rust), where it stays until the trip ends. While the vault is
 * locked the row under Start is hidden and the trip goes without the message.
 */
class TripNotice(
    private val prefs: StateFlow<StopsPrefs>,
    private val load: suspend () -> StopsPrefs,
    private val link: StateFlow<WaLinkState>,
    private val settled: StateFlow<Boolean>,
    private val vault: StateFlow<WaVaultState> = MutableStateFlow(WaVaultState.Unlocked),
    /** Gives the starting trip its copy of the link; false when the vault is locked. */
    private val hold: suspend () -> Boolean = { true },
) : ArrivingSoonHook {
    private val switchedOn = MutableStateFlow(true)

    /** The per-trip switch: on until the user turns it off, and it stays as it was left for the next trip. */
    val on: StateFlow<Boolean> get() = switchedOn

    fun toggle() {
        switchedOn.value = !switchedOn.value
    }

    override suspend fun noticeFor(
        a: Destination,
        b: Destination,
    ): NoticePrefs? {
        val notice = load().notice
        val open = vault.value == WaVaultState.Unlocked
        val wanted =
            notice?.takeIf { switchedOn.value && open && noticeAvailable(it, link.value, settled.value) } ?: return null
        return wanted.takeIf { hold() }
    }

    @Composable
    override fun PlanRow() {
        val held by prefs.collectAsState()
        val linked by link.collectAsState()
        val ready by settled.collectAsState()
        val isOn by switchedOn.collectAsState()
        val open by vault.collectAsState()
        val notice = held.notice
        if (notice == null || open != WaVaultState.Unlocked || !noticeAvailable(notice, linked, ready)) return
        NoticeToggleRow(notice.chatName, isOn, ::toggle)
    }
}

/** "Text Ayşe when arriving" with its switch: off for the next trip, or on again. */
@Composable
fun NoticeToggleRow(
    chatName: String,
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InsetGroupedSection(modifier) {
        row {
            ListRowSwitch(
                title = stringResource(R.string.notice_toggle, chatName),
                checked = on,
                onCheckedChange = { onToggle() },
                modifier = Modifier.testTag(TripNoticeTags.Toggle),
            )
        }
    }
}
