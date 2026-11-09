package dev.rovsti.platform.notify

import android.app.AlarmManager
import android.content.Context
import dev.rovsti.bridge.ReceiverWork
import org.junit.After
import org.junit.Before
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager

/** Shared setup for the reminder tests: receivers do their work inline and the alarm shadow is reachable. */
abstract class RemindersTestBase {
    protected val context: Context = RuntimeEnvironment.getApplication()
    protected val alarms get() = shadowOf(context.getSystemService(AlarmManager::class.java))

    @Before fun inline() {
        ReceiverWork.executor = { it.run() }
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun restore() {
        ReceiverWork.executor = { Thread(it).start() }
    }
}
