package dev.rosty.platform.audio

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The microphone seam: nothing records without the permission; the fake behaves as the recorder does. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VoiceRecorderTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun withoutThePermissionNothingRecords() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val recorder = AndroidVoiceRecorder(app)
        assertFalse(recorder.start(File(folder.root, "a.ogg"), 60_000) {})
        assertEquals(0, recorder.amplitude())
        assertFalse(recorder.stop())
    }

    @Test fun theFakeRecordsReadsAndIsInterrupted() {
        val fake = FakeVoiceRecorder()
        val stops = mutableListOf<RecorderStop>()
        val file = File(folder.root, "take.ogg")
        assertTrue(fake.start(file, 1_000) { stops += it })
        assertTrue(file.length() > 0)
        fake.level = 1_234
        assertEquals(1_234, fake.amplitude())
        fake.interrupt(RecorderStop.FocusLost)
        assertEquals(listOf(RecorderStop.FocusLost), stops)
        assertTrue(fake.stop())
        assertEquals(0, fake.amplitude())
        assertFalse(fake.stop())
    }
}
