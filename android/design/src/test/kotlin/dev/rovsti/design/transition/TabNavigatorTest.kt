package dev.rovsti.design.transition

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private sealed interface R {
    @Serializable data object A : R

    @Serializable data object B : R

    @Serializable data class Detail(
        val id: Int,
    ) : R
}

@OptIn(ExperimentalCoroutinesApi::class)
class TabNavigatorTest {
    private val tabA = Tab("a")
    private val tabB = Tab("b")
    private val roots = mapOf(tabA to R.A, tabB to R.B)

    private fun navigator() = TabNavigator(listOf(tabA, tabB), roots)

    @Test
    fun stacksAreIndependentPerTab() {
        val nav = navigator()
        nav.push(R.Detail(1))
        nav.push(R.Detail(2))
        nav.select(tabB)
        assertEquals(R.B, nav.current)
        nav.push(R.Detail(9))
        nav.select(tabA)
        assertEquals(listOf(R.A, R.Detail(1), R.Detail(2)), nav.stack(tabA))
        assertEquals(listOf(R.B, R.Detail(9)), nav.stack(tabB))
        assertEquals(R.Detail(2), nav.current)
    }

    @Test
    fun switchingBackRestoresTheStack() {
        val nav = navigator()
        nav.push(R.Detail(1))
        nav.select(tabB)
        nav.select(tabA)
        assertEquals(listOf(R.A, R.Detail(1)), nav.stack(tabA))
    }

    @Test
    fun reselectAtDepthPopsToRootWithoutScrollEvent() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = navigator()
            val events = mutableListOf<ScrollToTop>()
            val job = launch { nav.scrollToTop.toList(events) }
            repeat(2) { nav.push(R.Detail(it)) }
            assertEquals(3, nav.stack(tabA).size)
            nav.select(tabA)
            assertEquals(listOf(R.A), nav.stack(tabA))
            assertTrue("a reselect that popped counts as a pop", nav.lastNavWasPop)
            assertTrue(events.isEmpty())
            job.cancel()
        }

    @Test
    fun reselectAtRootEmitsExactlyOneScrollToTop() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = navigator()
            val events = mutableListOf<ScrollToTop>()
            val job = launch { nav.scrollToTop.toList(events) }
            nav.select(tabA)
            assertEquals(listOf(ScrollToTop(tabA)), events)
            job.cancel()
        }

    @Test
    fun scrollToTopIsNotSticky() =
        runTest(UnconfinedTestDispatcher()) {
            val nav = navigator()
            nav.select(tabA)
            val late = mutableListOf<ScrollToTop>()
            val job = launch { nav.scrollToTop.toList(late) }
            assertTrue("a collector that starts later sees nothing", late.isEmpty())
            job.cancel()
        }

    @Test
    fun popAtRootIsFalseAndChangesNothing() {
        val nav = navigator()
        assertFalse(nav.pop())
        nav.push(R.Detail(1))
        assertTrue(nav.pop())
        assertFalse(nav.pop())
        assertEquals(listOf(R.A), nav.stack(tabA))
    }

    @Test
    fun lastNavWasPopFollowsTheLastChange() {
        val nav = navigator()
        assertFalse(nav.lastNavWasPop)
        nav.push(R.Detail(1))
        assertFalse(nav.lastNavWasPop)
        nav.pop()
        assertTrue(nav.lastNavWasPop)
        nav.select(tabB)
        assertFalse("a tab switch is not a pop", nav.lastNavWasPop)
    }

    @Test
    fun replaceTopKeepsTheDepth() {
        val nav = navigator()
        nav.push(R.Detail(1))
        nav.replaceTop(R.Detail(5))
        assertEquals(listOf(R.A, R.Detail(5)), nav.stack(tabA))
    }

    @Test
    fun resetToBuildsADeepLinkStackAndSelectsTheTab() {
        val nav = navigator()
        nav.resetTo(tabB, listOf(R.Detail(3), R.Detail(4)))
        assertEquals(tabB, nav.currentTab)
        assertEquals(listOf(R.B, R.Detail(3), R.Detail(4)), nav.stack(tabB))
        assertEquals(listOf(R.A), nav.stack(tabA))
    }

    @Test
    fun keysAreUniquePerTabAndDepth() {
        val nav = navigator()
        nav.push(R.Detail(1))
        nav.push(R.Detail(1))
        val keys = nav.keys()
        assertEquals(3, keys.toSet().size)
        assertEquals(listOf(0, 1, 2), keys.map { it.depth })
    }

    @Test
    fun saverRoundTrips() {
        val nav = navigator()
        nav.push(R.Detail(1))
        nav.select(tabB)
        nav.push(R.Detail(2))
        val saver = TabNavigator.saver(listOf(tabA, tabB), roots, R.serializer())
        val saved = with(saver) { SaveScopeStub.save(nav) }!!
        val restored = saver.restore(saved)!!
        assertEquals(tabB, restored.currentTab)
        assertEquals(nav.stack(tabA), restored.stack(tabA))
        assertEquals(nav.stack(tabB), restored.stack(tabB))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rootsMustCoverAllTabs() {
        TabNavigator(listOf(tabA, tabB), mapOf(tabA to R.A))
    }
}

private object SaveScopeStub : androidx.compose.runtime.saveable.SaverScope {
    override fun canBeSaved(value: Any) = true
}
