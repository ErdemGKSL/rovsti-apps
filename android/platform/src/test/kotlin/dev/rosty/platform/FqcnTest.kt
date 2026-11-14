package dev.rosty.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifests of the Kotlin apps name these classes (ARCH 7.2): PendingIntents, alarms and pinned shortcuts made by
 * the GPUI build keep working after the update only if the fully qualified names survive. The list grows with each step
 * (E3.2-E3.7) and is complete when every `dev.rosty.bridge.*` component is ported.
 */
class FqcnTest {
    /** The 13 `dev.rosty.bridge` manifest components (`RostyShareProvider` came with R2 E3.8a). E3.4 ports the SMS side (`platform/sms/BridgeComponents.kt`, package `dev.rosty.bridge`). */
    private val ported =
        listOf(
            "dev.rosty.bridge.SmsStatusReceiver",
            "dev.rosty.bridge.MmsFileProvider",
            "dev.rosty.bridge.MmsResultReceiver",
            "dev.rosty.bridge.ScheduledSmsReceiver",
            // E3.6 (platform/notify/NotifyReceivers.kt)
            "dev.rosty.bridge.MessageReminderReceiver",
            "dev.rosty.bridge.CallReminderReceiver",
            "dev.rosty.bridge.BirthdayReminderReceiver",
            "dev.rosty.bridge.ReplyReceiver",
            // E3.7 (platform/location/BridgeTripComponents.kt)
            "dev.rosty.bridge.TripService",
            "dev.rosty.bridge.TripWatchdogReceiver",
            "dev.rosty.bridge.TripAlarmReceiver",
            "dev.rosty.bridge.AlarmActivity",
            // E3.8a (R2): platform/files/RostyShareProvider.kt, with Documents and MediaShare.
            "dev.rosty.bridge.RostyShareProvider",
        )

    @Test fun everyPortedComponentKeepsItsBridgeName() {
        val missing = ported.filter { name -> runCatching { Class.forName(name) }.isFailure }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun allThirteenComponentsArePorted() {
        assertEquals(13, ported.size)
        assertEquals(ported.size, ported.toSet().size)
        val plan = java.io.File("../../docs/kotlin-ui/platform-port.md").readText()
        assertTrue(plan.contains("RostyShareProvider") && plan.contains("platform.files"))
    }

    @Test fun theManifestNamesAreAllInThePortPlan() {
        // The 13 bridge-owned components (plan, "State found"): the port map must list each one.
        val plan = java.io.File("../../docs/kotlin-ui/platform-port.md").readText()
        val names =
            listOf(
                "CallReminderReceiver",
                "RostyShareProvider",
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
