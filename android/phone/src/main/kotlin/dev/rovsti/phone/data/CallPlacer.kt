package dev.rovsti.phone.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.phone.DialTextRequest
import dev.rovsti.core.model.phone.IsEmergencyRequest
import dev.rovsti.core.model.phone.SimAccount
import dev.rovsti.core.ops.PhoneOps
import dev.rovsti.platform.telephony.SimAccounts
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.StateFlow

/** The system side of placing a cellular call, behind a seam so the decision logic runs on the JVM. */
fun interface CallLauncher {
    /** Places [number] on the account [accountKey] (null: the system picks). */
    fun place(
        number: String,
        accountKey: String?,
    ): Launch

    enum class Launch {
        /** `TelecomManager.placeCall` was called. */
        PLACED,

        /** `CALL_PHONE` is not held: the system dialer was opened on the number instead. */
        DIALER_OPENED,

        FAILED,
    }
}

/** [CallLauncher] on `TelecomManager.placeCall`, with `ACTION_DIAL` when the app may not place calls. */
class AndroidCallLauncher(
    private val context: Context,
) : CallLauncher {
    @SuppressLint("MissingPermission")
    override fun place(
        number: String,
        accountKey: String?,
    ): CallLauncher.Launch {
        val uri = Uri.fromParts("tel", number, null)
        if (context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            val dial = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(dial)
                CallLauncher.Launch.DIALER_OPENED
            } catch (_: RuntimeException) {
                CallLauncher.Launch.FAILED
            }
        }
        val extras = Bundle()
        if (accountKey != null) {
            SimAccounts.handleFor(context, accountKey)?.let {
                extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it)
            }
        }
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return CallLauncher.Launch.FAILED
        return try {
            telecom.placeCall(uri, extras)
            CallLauncher.Launch.PLACED
        } catch (_: SecurityException) {
            CallLauncher.Launch.FAILED
        }
    }
}

/** What [CallPlacer.place] decided. */
sealed interface PlaceResult {
    /** The call went to Telecom. [accountKey] is the account asked for; null when the system picks (one SIM, emergency). */
    data class Placed(
        val number: String,
        val accountKey: String?,
    ) : PlaceResult

    /** Two SIMs and "ask every time": the screen shows the chooser, then calls [CallPlacer.place] with the key. */
    data class NeedsSim(
        val number: String,
        val choices: ImmutableList<SimAccount>,
    ) : PlaceResult

    /** The app may not place calls (no `CALL_PHONE`): the system dialer was opened on [number]. */
    data class HandedToDialer(
        val number: String,
    ) : PlaceResult

    /** The text is not a number the core can dial. */
    data class Invalid(
        val reason: String,
    ) : PlaceResult

    data class Failed(
        val number: String,
    ) : PlaceResult
}

/**
 * Places a cellular call. The decisions are the core's: `dial.validate` (what may be dialed, in dial form) and
 * `dial.is_emergency`. The SIM rule is the GPUI app's (`apps/phone/src/sim_ui.rs` `sim_needs_choice`): an emergency
 * number never asks for a SIM and never gets an account; otherwise a chosen [simKey] wins, "ask every time" (two or
 * more SIMs, no system default) needs the chooser, and any other case places on the system default, which the app never
 * changes.
 */
class CallPlacer(
    private val ops: PhoneOps,
    private val sims: StateFlow<Sims>,
    private val launcher: CallLauncher,
) {
    suspend fun place(
        typed: String,
        simKey: String? = null,
    ): PlaceResult {
        val number =
            when (val valid = ops.validate(DialTextRequest(typed))) {
                is Outcome.Ok -> valid.value.text
                is Outcome.Err -> return PlaceResult.Invalid(valid.error.message.orEmpty())
            }
        val emergency = isEmergency(number)
        val now = sims.value
        val key =
            when {
                emergency -> null
                simKey != null -> simKey
                now.ask -> return PlaceResult.NeedsSim(number, now.chips)
                else -> now.defaultKey
            }
        return when (launcher.place(number, key)) {
            CallLauncher.Launch.PLACED -> PlaceResult.Placed(number, key)
            CallLauncher.Launch.DIALER_OPENED -> PlaceResult.HandedToDialer(number)
            CallLauncher.Launch.FAILED -> PlaceResult.Failed(number)
        }
    }

    /** Whether [typed] is an emergency number (the core's list: 112 and 911 always). */
    suspend fun isEmergency(typed: String): Boolean =
        (ops.isEmergency(IsEmergencyRequest(typed)) as? Outcome.Ok)?.value?.emergency ?: false
}
