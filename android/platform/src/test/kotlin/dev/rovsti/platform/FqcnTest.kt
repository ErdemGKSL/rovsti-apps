package dev.rovsti.platform

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The manifests of the Kotlin apps name these classes (ARCH 7.2): PendingIntents, alarms and pinned shortcuts made by
 * the GPUI build keep working after the update only if the fully qualified names survive. The list grows with each step
 * (E3.2-E3.7) and is complete when every `dev.rovsti.bridge.*` component is ported.
 */
class FqcnTest {
    /** Components ported so far. E3.4 ports the SMS side (`platform/sms/BridgeComponents.kt`, package `dev.rovsti.bridge`). */
    private val ported =
        listOf(
            "dev.rovsti.bridge.SmsStatusReceiver",
            "dev.rovsti.bridge.MmsFileProvider",
            "dev.rovsti.bridge.MmsResultReceiver",
            "dev.rovsti.bridge.ScheduledSmsReceiver",
        )

    @Test fun everyPortedComponentKeepsItsBridgeName() {
        val missing = ported.filter { name -> runCatching { Class.forName(name) }.isFailure }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun theManifestNamesAreAllInThePortPlan() {
        // The 13 bridge-owned components (plan, "State found"): the port map must list each one.
        val plan = java.io.File("../../docs/kotlin-ui/platform-port.md").readText()
        val names =
            listOf(
                "CallReminderReceiver",
                "RovstiShareProvider",
                "MessageReminderReceiver",
                "MmsFileProvider",
                "MmsResultReceiver",
                "ReplyReceiver",
                "ScheduledSmsReceiver",
                "SmsStatusReceiver",
                "BirthdayReminderReceiver",
                "AlarmActivity",
                "TripAlarmReceiver",
                "TripService",
                "TripWatchdogReceiver",
            )
        assertEquals(emptyList<String>(), names.filterNot { plan.contains(it) })
    }
}
