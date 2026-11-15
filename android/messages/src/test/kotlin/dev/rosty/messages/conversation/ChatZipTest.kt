package dev.rosty.messages.conversation

import dev.rosty.platform.files.FakeFilesRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatZipTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test fun theZipHoldsTheTextAndEveryFileOnceEvenWithTheSameName() {
        val a = File(File(app.cacheDir, "a").apply { mkdirs() }, "pic.jpg").apply { writeText("one") }
        val b = File(File(app.cacheDir, "b").apply { mkdirs() }, "pic.jpg").apply { writeText("two") }
        val missing = File(app.cacheDir, "gone.jpg")
        val shared =
            runBlocking {
                shareChatZip(
                    app,
                    FakeFilesRepository(staged = null),
                    ExportedChat("Chat with Ada.txt", "hi"),
                    listOf(a, b, missing),
                )
            }
        assertFalse("nothing to share to without a staged uri", shared)
        val zip = File(File(app.cacheDir, "exports"), "Chat with Ada.zip")
        ZipFile(zip).use { z ->
            assertEquals(
                listOf("Chat with Ada.txt", "media/pic.jpg", "media/1-pic.jpg"),
                z.entries().toList().map { it.name },
            )
            assertEquals("two", z.getInputStream(z.getEntry("media/1-pic.jpg")).readBytes().decodeToString())
        }
    }
}
