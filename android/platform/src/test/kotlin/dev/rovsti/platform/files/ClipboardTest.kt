package dev.rovsti.platform.files

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(ClipboardManager::class.java)
    private val repo = SystemClipboardRepository(context)

    @Test fun theSensitiveExtraIsSetWhenAsked() {
        assertTrue(repo.copy("123456", sensitive = true))
        val clip = manager.primaryClip!!
        assertEquals("123456", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE))
    }

    @Test fun aNormalCopyHasNoSensitiveExtra() {
        assertTrue(repo.copy("hello"))
        assertFalse(
            manager.primaryClip!!
                .description.extras
                ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) ?: false,
        )
    }

    @Test fun primaryTextReadsBack() {
        repo.copy("abc")
        assertEquals("abc", repo.primaryText())
        manager.clearPrimaryClip()
        assertNull(repo.primaryText())
    }

    @Test fun changesEmitOnACopy() =
        runTest(UnconfinedTestDispatcher()) {
            var seen = 0
            val job = launch { repo.changes().first().also { seen++ } }
            repo.copy("x")
            job.join()
            assertEquals(1, seen)
        }

    @Test fun theFakeBehavesLikeTheRealOne() {
        val fake = FakeClipboardRepository()
        assertTrue(fake.copy("a", true))
        assertEquals("a", fake.primaryText())
        assertEquals(listOf("a" to true), fake.copies)
    }
}
