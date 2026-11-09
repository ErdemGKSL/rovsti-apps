package dev.rovsti.shell

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import dev.rovsti.core.FakeRustCore
import dev.rovsti.core.RustCore
import dev.rovsti.platform.system.FakeSpecialAccess
import dev.rovsti.platform.system.permissions.AppPermissions
import dev.rovsti.platform.system.permissions.SpecialAccess
import dev.rovsti.wa.FakeWaRepository
import dev.rovsti.wa.WaRepository
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
