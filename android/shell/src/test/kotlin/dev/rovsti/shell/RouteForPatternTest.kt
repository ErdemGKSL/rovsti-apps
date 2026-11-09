package dev.rovsti.shell

import android.os.Bundle
import dev.rovsti.design.transition.Tab
import dev.rovsti.design.transition.TabNavigator
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Serializable
private sealed interface SampleRoute {
    @Serializable data object Recents : SampleRoute

    @Serializable data object Contacts : SampleRoute

    @Serializable data class ContactDetail(
        val id: Long,
    ) : SampleRoute

    @Serializable data class InCall(
        val callId: String,
    ) : SampleRoute
}

private val RecentsTab = Tab("recents")
private val ContactsTab = Tab("contacts")

/** The shape an app's `routeFor` takes (ARCH 7.3): extras in, a tab and its routes out, nothing read from anywhere else. */
private fun sampleRouteFor(extras: Bundle?): Pair<Tab, List<SampleRoute>>? {
    extras ?: return null
    // A call being answered beats everything: it must be on screen.
    extras.getString("answer_call_id")?.takeIf { it.isNotBlank() }?.let {
        return RecentsTab to listOf(SampleRoute.InCall(it))
    }
    val contact = extras.getLong("open_contact", -1L)
    if (contact >= 0) return ContactsTab to listOf(SampleRoute.ContactDetail(contact))
    return null
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RouteForPatternTest {
    private val spec =
        NavSpec(
            tabs = listOf(RecentsTab, ContactsTab),
            roots = mapOf(RecentsTab to SampleRoute.Recents, ContactsTab to SampleRoute.Contacts),
            serializer = SampleRoute.serializer(),
        )

    private fun extras(block: Bundle.() -> Unit) = Bundle().apply(block)

    @Test fun noExtrasLeavesTheStacksAlone() {
        assertNull(sampleRouteFor(null))
        assertNull(sampleRouteFor(Bundle()))
    }

    @Test fun openContactResetsTheContactsTab() {
        val nav = spec.create()
        val (tab, routes) = sampleRouteFor(extras { putLong("open_contact", 42L) })!!
        nav.resetTo(tab, routes)
        assertEquals(ContactsTab, nav.currentTab)
        assertEquals(listOf(SampleRoute.Contacts, SampleRoute.ContactDetail(42L)), nav.stack(ContactsTab))
        assertEquals("the other tab is untouched", listOf<SampleRoute>(SampleRoute.Recents), nav.stack(RecentsTab))
    }

    @Test fun anAnsweredCallBeatsAContactLink() {
        val nav = spec.create()
        val both =
            extras {
                putLong("open_contact", 7L)
                putString("answer_call_id", "call-9")
            }
        val (tab, routes) = sampleRouteFor(both)!!
        nav.resetTo(tab, routes)
        assertEquals(RecentsTab, nav.currentTab)
        assertEquals(SampleRoute.InCall("call-9"), nav.current)
    }

    @Test fun aSecondIntentReplacesTheFirstDeepLinkInsteadOfStackingOnIt() {
        val nav = spec.create()
        nav.resetTo(ContactsTab, listOf(SampleRoute.ContactDetail(1L)))
        val (tab, routes) = sampleRouteFor(extras { putLong("open_contact", 2L) })!!
        nav.resetTo(tab, routes)
        assertEquals(listOf(SampleRoute.Contacts, SampleRoute.ContactDetail(2L)), nav.stack(ContactsTab))
    }

    @Test fun malformedExtrasAreIgnored() {
        assertNull(sampleRouteFor(extras { putString("open_contact", "not a long") }))
        assertNull(sampleRouteFor(extras { putString("answer_call_id", "  ") }))
    }

    @Test fun theSavedNavigatorComesBackWithItsStacks() {
        val nav = spec.create()
        nav.resetTo(ContactsTab, listOf(SampleRoute.ContactDetail(5L)))
        val text =
            with(spec.saver) {
                androidx.compose.runtime.saveable
                    .SaverScope { true }
                    .save(nav)
            }!!
        val back: TabNavigator<SampleRoute> = spec.saver.restore(text)!!
        assertEquals(ContactsTab, back.currentTab)
        assertEquals(nav.stack(ContactsTab), back.stack(ContactsTab))
    }
}
