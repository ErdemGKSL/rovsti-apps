package dev.rovsti.core.log

import android.content.Context
import dev.rovsti.core.Outcome
import dev.rovsti.core.model.core.LogsDrainAnswer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DevModeTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun prefs() = context.getSharedPreferences(DevMode.PREFS, Context.MODE_PRIVATE)

    @Test
    fun thePrefsFileNameIsPinned() {
        // Renaming it would switch developer mode off for everyone who turned it on.
        assertEquals("rovsti_dev", DevMode.PREFS)
    }

    @Test
    fun stateIsPersisted() {
        val mode = DevMode(prefs())
        assertFalse(mode.enabled.value)
        assertTrue(mode.toggle())
        assertTrue(DevMode(prefs()).enabled.value)
        mode.set(false)
        assertFalse(DevMode(prefs()).enabled.value)
    }

    @Test
    fun onWritesTheFileAndOffDeletesIt() {
        val dir = File(context.cacheDir, "devmode-logs").apply { deleteRecursively() }
        val scope = TestScope(StandardTestDispatcher())
        val file = LogFile(dir)
        val pump = RustLogPump({ Outcome.Ok(LogsDrainAnswer(emptyList(), 0, it.afterSeq)) }, scope)
        val mode = DevMode(prefs()).apply { set(false) }
        val log = DevLog(mode, file, pump, scope)
        log.start()
        scope.runCurrent()
        mode.set(true)
        scope.runCurrent()
        RovstiLog.i("test", "kept on disk")
        file.flush()
        assertTrue(
            file
                .paths()
                .first()
                .readText()
                .contains("kept on disk"),
        )
        mode.set(false)
        scope.runCurrent()
        RovstiLog.i("test", "memory only")
        file.flush()
        assertFalse(file.paths().any { it.exists() })
        assertTrue(RovstiLog.ring.snapshot().any { it.text == "memory only" })
        pump.stop()
    }
}
