package dev.rovsti.bridge

/**
 * Pure parts of the Diagnostics "SIM" (S5) and "TalkBack/system" (S14) checks, so a JVM test covers
 * them. One `pass`, `fail` or `unknown` line per check, plus `info` lines for the facts.
 */
object SimProbe {
    /** The S5 report: accounts, the ask flag, and the last placed call's key against the log's. */
    fun lines(accounts: List<SimAccounts.Account>, defaultKey: String?, placedKey: String?, logKey: String?): List<String> {
        val out = ArrayList<String>()
        out.add("info accounts=${accounts.size} default=${if (defaultKey == null) "none" else "set"} ask=${SimAccounts.ask(defaultKey, accounts.size)}")
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
                placedKey == null -> "unknown: key equals the call-log pair (place one call on each SIM, then rerun)"
                logKey == null -> "fail: key equals the call-log pair (the newest log row carries no account columns; log chips are omitted)"
                placedKey == logKey -> "pass: key equals the call-log pair"
                else -> "fail: key equals the call-log pair (placed and logged keys differ; log chips are omitted)"
            },
        )
        out.add(
            if (accounts.any { it.key == logKey }) "pass: the newest log row matches a listed account"
            else "unknown: the newest log row matches no listed account (chip would stay empty)",
        )
        return out
    }

    /** The S14 vibration part. [felt] is unknown until the owner reports it in the spike doc. */
    fun vibrateLines(hasVibrator: Boolean, vibrateConnect: Boolean, vibrateEnd: Boolean, touchExploration: Boolean, vibrated: Boolean): List<String> = listOf(
        "info vibrator=$hasVibrator vibrate_connect=$vibrateConnect vibrate_end=$vibrateEnd talkback_touch_exploration=$touchExploration",
        when {
            !hasVibrator -> "fail: vibrator present"
            vibrated -> "unknown: vibration felt with the screen off (hold the phone to your ear for the 5 s countdown, then note the result in docs/spikes/s14-system.md)"
            else -> "fail: vibration request was accepted"
        },
    )
}
