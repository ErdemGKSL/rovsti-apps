package dev.rovsti.contacts.settings

import dev.rovsti.contacts.R
import dev.rovsti.contacts.people.PeopleSyncStatus
import dev.rovsti.core.model.people.SyncWire
import dev.rovsti.design.text.UiText

/**
 * The "WhatsApp Names and Photos" rows for a [PeopleSyncStatus] (people sync v2, section 9): the status line, the
 * progress bar, and which buttons show and take taps. Pure: [nowMs] decides whether a limit's ETA has passed, [ago]
 * words a past time ("5 min. ago"). Null while WhatsApp is not linked (the rows are hidden).
 */
fun peopleSyncUi(
    status: PeopleSyncStatus,
    nowMs: Long,
    ago: (Long) -> String,
): PeopleSyncUi? =
    when (status) {
        PeopleSyncStatus.NotLinked -> {
            null
        }

        PeopleSyncStatus.Connecting -> {
            PeopleSyncUi(UiText.Res(R.string.settings_people_connecting))
        }

        is PeopleSyncStatus.Idle -> {
            val last = status.lastMs
            PeopleSyncUi(
                if (last == null) {
                    UiText.Res(R.string.settings_people_never)
                } else {
                    UiText.Res(R.string.settings_people_synced, listOf(ago(last)))
                },
            )
        }

        is PeopleSyncStatus.Running -> {
            PeopleSyncUi(
                last = running(status.stage, status.done, status.total),
                running = true,
                progress = fraction(status.done, status.total),
            )
        }

        is PeopleSyncStatus.RateLimited -> {
            val waiting = status.untilMs > nowMs
            PeopleSyncUi(
                last =
                    if (waiting) {
                        UiText.Res(R.string.settings_people_limited, listOf(minutes(status.untilMs - nowMs)))
                    } else {
                        UiText.Res(R.string.settings_people_limited_ready)
                    },
                canContinue = true,
                continueEnabled = !waiting,
                syncEnabled = !waiting,
                confirmRestart = true,
            )
        }

        is PeopleSyncStatus.Interrupted -> {
            val at = listOf(status.done, status.total)
            PeopleSyncUi(
                last =
                    when (status.reason) {
                        SyncWire.NETWORK -> UiText.Res(R.string.settings_people_paused_network, at)
                        SyncWire.WA_DISCONNECTED -> UiText.Res(R.string.settings_people_paused_whatsapp, at)
                        SyncWire.CANCELLED -> UiText.Res(R.string.settings_people_stopped, at)
                        SyncWire.SLICE_BUDGET -> UiText.Res(R.string.settings_people_continues)
                        else -> UiText.Res(R.string.settings_people_interrupted, at + ago(status.atMs))
                    },
                canContinue = true,
                confirmRestart = true,
            )
        }

        is PeopleSyncStatus.Failed -> {
            PeopleSyncUi(
                last =
                    when (status.reason) {
                        SyncWire.AUTH -> UiText.Res(R.string.settings_people_failed_auth)
                        SyncWire.GATEWAY -> UiText.Res(R.string.settings_people_failed_gateway)
                        SyncWire.CORE -> UiText.Res(R.string.settings_people_failed_storage)
                        else -> UiText.Res(R.string.settings_people_failed_other, listOf(ago(status.atMs)))
                    },
                canContinue = true,
                confirmRestart = true,
            )
        }
    }

private fun running(
    stage: String,
    done: Int,
    total: Int,
): UiText =
    UiText.Res(
        when (stage) {
            SyncWire.STAGE_PROFILES -> R.string.settings_people_running_profiles
            SyncWire.STAGE_PICTURES -> R.string.settings_people_running_pictures
            else -> R.string.settings_people_running_lookup
        },
        listOf(done, total),
    )

private fun fraction(
    done: Int,
    total: Int,
): Float = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)

/** "12 min", rounded up, at least 1. */
private fun minutes(ms: Long): UiText {
    val n = ((ms + MINUTE_MS - 1) / MINUTE_MS).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return UiText.Plural(R.plurals.settings_people_minutes, n)
}

private const val MINUTE_MS = 60_000L
