package dev.rovsti.platform.telephony

import dev.rovsti.core.model.phone.PhoneAccountInfo

/**
 * Pure part of the Diagnostics "SIM" (S5) check, so a JVM test covers it. One `pass`, `fail` or `unknown` line per
 * check, plus `info` lines for the facts. The S14 vibration part went with the GPUI spikes.
 *
 * Ported from crates/rovsti_android/kotlin/dev/rovsti/bridge/SimProbe.kt
 */
object SimProbe {
    /** The S5 report: accounts, the ask flag, and the last placed call's key against the log's. */
    fun lines(
        accounts: List<PhoneAccountInfo>,
        defaultKey: String?,
        placedKey: String?,
        logKey: String?,
    ): List<String> {
        val out = ArrayList<String>()
        val ask = SimAccounts.ask(defaultKey, accounts.size)
        out.add("info accounts=${accounts.size} default=${if (defaultKey == null) "none" else "set"} ask=$ask")
        for (a in accounts) {
            out.add(
                "info slot=${a.slotIndex ?: "?"} sim=${a.sim} enabled=${a.enabled} label=${a.label} " +
                    "default=${a.key == defaultKey} color=${"#%08X".format(a.colorArgb)} keylen=${a.key.length}",
            )
        }
        if (accounts.size < 2) {
            out.add("unknown: key equals the call-log pair (n/a with one SIM; the SIM UI stays hidden)")
            return out
        }
        out.add(
            when {
                placedKey == null -> {
                    "unknown: key equals the call-log pair (place one call on each SIM, then rerun)"
                }

                logKey == null -> {
                    "fail: key equals the call-log pair (the newest log row carries no account columns; log chips are omitted)"
                }

                placedKey == logKey -> {
                    "pass: key equals the call-log pair"
                }

                else -> {
                    "fail: key equals the call-log pair (placed and logged keys differ; log chips are omitted)"
                }
            },
        )
        out.add(
            if (accounts.any { it.key == logKey }) {
                "pass: the newest log row matches a listed account"
            } else {
                "unknown: the newest log row matches no listed account (chip would stay empty)"
            },
        )
        return out
    }
}
