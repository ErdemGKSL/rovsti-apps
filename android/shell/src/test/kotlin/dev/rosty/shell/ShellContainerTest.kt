package dev.rosty.shell

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import dev.rosty.core.FakeRustCore
import dev.rosty.core.RustCore
import dev.rosty.platform.system.FakeSpecialAccess
import dev.rosty.platform.system.permissions.AppPermissions
import dev.rosty.platform.system.permissions.SpecialAccess
import dev.rosty.wa.FakeWaRepository
import dev.rosty.wa.WaRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A container whose seams count what they build; no native library is touched. */
class CountingContainer(
    context: Context,
) : ShellContainer(context, AppPermissions.contacts) {
    var rustInits = 0
    var waInits = 0
    var loaderInits = 0
    val fakeRust = FakeRustCore()
    val fakeWa = FakeWaRepository()

    override fun createRust(): RustCore {
        rustInits++
        return fakeRust
    }

    override fun createWa(): WaRepository {
        waInits++
        return fakeWa
    }

    override fun createSpecialAccess(): SpecialAccess = FakeSpecialAccess()

    override fun createImageLoader(): ImageLoader {
        loaderInits++
        return super.createImageLoader()
    }
}

/** A container that keeps the dev log for its first use, as the apps' test containers do. */
class QuietContainer(
    context: Context,
) : ShellContainer(context, AppPermissions.contacts) {
    val devLogs =
        java.util.concurrent.atomic
            .AtomicInteger()

    override fun createRust(): RustCore = FakeRustCore()

    override fun createSpecialAccess(): SpecialAccess = FakeSpecialAccess()

    override fun startsDevLogAtCreate(): Boolean = false

    override fun createDevLog(): dev.rosty.core.log.DevLog {
        devLogs.incrementAndGet()
        return super.createDevLog()
    }
}

class TestApp :
    Application(),
    HasContainer<CountingContainer> {
    override val container: CountingContainer by lazy { CountingContainer(this) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class ShellContainerTest {
    private val app get() = RuntimeEnvironment.getApplication() as TestApp

    @Test fun buildingTheContainerStartsNothing() {
        val container = app.container
        assertEquals(0, container.rustInits)
        assertEquals(0, container.waInits)
        assertEquals(0, container.loaderInits)
    }

    @Test fun aContainerThatOptsOutStartsNoDevLogOnAPoolThread() {
        val container = QuietContainer(app)
        // Nothing was launched: whatever runs later on the pool cannot build the log behind the test's back.
        container.scope.coroutineContext[kotlinx.coroutines.Job]!!
            .children
            .toList()
            .let { assertEquals(emptyList<kotlinx.coroutines.Job>(), it) }
        assertEquals(0, container.devLogs.get())
        container.devLog
        assertEquals(1, container.devLogs.get())
    }

    @Test fun rustStartsOnFirstUseAndOnlyOnce() {
        val container = app.container
        assertSame(container.fakeRust, container.rust)
        assertSame(container.fakeRust, container.rust)
        assertEquals(1, container.rustInits)
        assertEquals("nothing else woke up", 0, container.waInits)
    }

    @Test fun touchingPermissionsOrHapticsDoesNotStartRustOrWhatsApp() {
        val container = app.container
        container.permissions
        container.haptics
        container.clipboard
        container.files
        container.specialAccess
        assertEquals(0, container.rustInits)
        assertEquals(0, container.waInits)
    }

    @Test fun whatsAppIsBuiltOncePerProcess() {
        val container = app.container
        assertSame(container.wa, container.wa)
        assertEquals(1, container.waInits)
    }

    @Test fun theImageLoaderIsBuiltOnFirstUseAndTrimmingBeforeThenDoesNothing() {
        val container = app.container
        container.trimMemory(80)
        assertEquals("trimming must not build the loader", 0, container.loaderInits)
        assertTrue(container.imageLoader.memoryCache != null)
        assertEquals(1, container.loaderInits)
    }

    @Test fun anyContextFindsTheContainerThroughTheApplication() {
        val container = RuntimeEnvironment.getApplication().shellContainer()
        assertSame(app.container, container)
    }
}
