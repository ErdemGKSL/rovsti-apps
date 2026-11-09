package dev.rovsti.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A `PendingIntent` armed by the GPUI build (a reminder, a scheduled SMS, the trip alarm or watchdog, a notification
 * action) survives the update and fires into the Kotlin build. It finds its receiver only if the intent action and the
 * extra keys are the same strings. This test reads both source trees as text and compares the string constants, so a
 * rename in `:platform` fails here before it silently drops somebody's reminder.
 *
 * It looks at every `const val ... = "literal"` whose name starts with ACTION or EXTRA, plus the four reply-action
 * constants that were renamed in the port (`ACTION_REPLY` is `ReplyActions.REPLY`), in the files listed below.
 */
class PendingIntentContractTest {
    private val gpui = File("../../crates/rovsti_android/kotlin/dev/rovsti/bridge")
    private val port = File("src/main/kotlin/dev/rovsti/platform")

    /** GPUI source -> the `:platform` files that now own its constants. */
    private val contract: Map<String, List<String>> =
        mapOf(
            "MessageReminders.kt" to listOf("notify/MessageReminders.kt"),
            "CallReminders.kt" to listOf("notify/CallReminders.kt"),
            "BirthdayReminders.kt" to listOf("notify/BirthdayReminders.kt", "contacts/Shortcuts.kt"),
            "ReplyReceiver.kt" to listOf("notify/ReplyActions.kt"),
            "ScheduledSms.kt" to listOf("sms/ScheduledSms.kt"),
            "TripAlarm.kt" to listOf("location/TripAlarm.kt"),
            "TripService.kt" to listOf("location/BridgeTripComponents.kt"),
        )

    private val constant = Regex("""const val\s+(\w+)\s*=\s*"([^"]*)"""")

    private fun literals(file: File): Map<String, String> =
        constant.findAll(file.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private fun gpuiContract(file: String): Map<String, String> =
        literals(File(gpui, file)).filterKeys { it.startsWith("ACTION") || it.startsWith("EXTRA") }

    @Test fun theGpuiSourcesAreThere() {
        assertTrue("run from android/platform: ${gpui.absolutePath}", gpui.isDirectory)
        for (name in contract.keys) assertTrue(name, File(gpui, name).isFile)
    }

    @Test fun everyGpuiActionAndExtraStringSurvivesInPlatform() {
        val missing = mutableListOf<String>()
        for ((source, ports) in contract) {
            val have = ports.flatMap { literals(File(port, it)).values }.toSet()
            for ((name, value) in gpuiContract(source)) {
                if (value !in have) missing += "$source $name = \"$value\" not found in $ports"
            }
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun constantsThatKeptTheirNameKeepTheirValue() {
        val changed = mutableListOf<String>()
        for ((source, ports) in contract) {
            val ported = ports.flatMap { literals(File(port, it)).entries }.associate { it.key to it.value }
            for ((name, value) in gpuiContract(source)) {
                val now = ported[name] ?: continue
                if (now != value) changed += "$source $name: \"$value\" became \"$now\""
            }
        }
        assertEquals(emptyList<String>(), changed)
    }

    @Test fun theContractIsNotEmpty() {
        // 4 FIRE actions (3 reminders, scheduled SMS), 4 reply actions, 4 trip alarm actions, 2 trip service actions.
        val actions = contract.keys.flatMap { gpuiContract(it).filterKeys { n -> n.startsWith("ACTION") }.values }
        assertEquals(14, actions.size)
        assertTrue(actions.all { it.startsWith("dev.rovsti.") })
    }
}
