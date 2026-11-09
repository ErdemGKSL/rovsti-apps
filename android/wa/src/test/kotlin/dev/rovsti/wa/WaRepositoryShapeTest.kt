package dev.rovsti.wa

import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * [FakeWaRepository] must answer exactly what [WaRepository] declares (name and parameter types), and its extra public
 * members must be test controls with a known name, so the fake cannot drift into a second API.
 */
class WaRepositoryShapeTest {
    private fun signature(m: java.lang.reflect.Method) =
        m.name + m.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }

    private val declared =
        WaRepository::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .map(::signature)
            .toSet()

    private val fakeMethods =
        FakeWaRepository::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }

    @Test fun theFakeImplementsEveryMember() {
        val implemented = fakeMethods.map(::signature).toSet()
        assertEquals(emptySet<String>(), declared - implemented)
    }

    @Test fun theFakeAddsOnlyTestControls() {
        val controls = setOf("setState", "setChats", "setMessages", "emit", "getLog", "getLinked")
        val extra = fakeMethods.filter { signature(it) !in declared }.map { it.name }.toSet()
        assertEquals(emptySet<String>(), extra - controls)
    }

    @Test fun theInterfaceHasTheDocumentedMembers() {
        val names = declared.map { it.substringBefore('(') }.toSet()
        assertEquals(
            setOf("getLinkState", "getEvents", "start", "gateway", "wake", "chats", "messages", "relinkTo", "forget"),
            names,
        )
    }
}
