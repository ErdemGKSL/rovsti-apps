package dev.rovsti.testing

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * A merged `AndroidManifest.xml` read as plain data, for the manifest tests of every app module (ARCH 7.2). It is a
 * JVM-only parser (no Android classes), so the tests run without Robolectric. The app build hands the file to the test
 * as a system property (the merged manifest of the debug variant); see `:contacts` `build.gradle.kts`.
 */
class MergedManifest private constructor(
    private val root: Element,
) {
    /** One `<intent-filter>` or `<intent>`: its actions, categories and `<data>` attributes. */
    data class IntentSpec(
        val actions: Set<String>,
        val categories: Set<String>,
        val mimeTypes: Set<String>,
        val schemes: Set<String>,
    )

    /** An `<activity>`, `<service>`, `<receiver>` or `<provider>` with the attributes the tests care about. */
    data class Component(
        val kind: String,
        val name: String,
        val attributes: Map<String, String>,
        val filters: List<IntentSpec>,
    ) {
        val exported: Boolean? get() = attributes["exported"]?.toBooleanStrictOrNull()
        val launchMode: String? get() = attributes["launchMode"]
        val authorities: String? get() = attributes["authorities"]
    }

    /** `android:name` of each `<uses-permission>` (also `uses-permission-sdk-23`). */
    val usesPermissions: Set<String> by lazy {
        children(root, "uses-permission", "uses-permission-sdk-23").mapNotNull { it.android("name") }.toSet()
    }

    /** `<permission>` declarations: name to protection level. */
    val declaredPermissions: Map<String, String> by lazy {
        children(root, "permission")
            .mapNotNull { e ->
                e.android("name")?.let {
                    it to
                        e.android("protectionLevel").orEmpty()
                }
            }.toMap()
    }

    /** Attributes of `<application>` without the namespace prefix (`allowBackup`, `name`, ...). */
    val application: Map<String, String> by lazy { attributesOf(applicationElement) }

    private val applicationElement: Element by lazy { children(root, "application").single() }

    val components: List<Component> by lazy {
        children(applicationElement, "activity", "activity-alias", "service", "receiver", "provider").map { e ->
            Component(
                kind = e.tagName,
                name = e.android("name").orEmpty(),
                attributes = attributesOf(e),
                filters = children(e, "intent-filter").map(::intentSpec),
            )
        }
    }

    /** The `<intent>` entries of `<queries>`. */
    val queries: List<IntentSpec> by lazy {
        children(root, "queries").flatMap { q -> children(q, "intent").map(::intentSpec) }
    }

    fun component(name: String): Component = components.single { it.name == name }

    private fun intentSpec(e: Element): IntentSpec {
        val data = children(e, "data")
        return IntentSpec(
            actions = children(e, "action").mapNotNull { it.android("name") }.toSet(),
            categories = children(e, "category").mapNotNull { it.android("name") }.toSet(),
            mimeTypes = data.mapNotNull { it.android("mimeType") }.toSet(),
            schemes = data.mapNotNull { it.android("scheme") }.toSet(),
        )
    }

    private fun attributesOf(e: Element): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        val attrs = e.attributes
        for (i in 0 until attrs.length) {
            val node = attrs.item(i)
            if (node.namespaceURI == ANDROID_NS) map[node.localName] = node.nodeValue
        }
        return map
    }

    private fun children(
        parent: Element,
        vararg tags: String,
    ): List<Element> {
        val out = ArrayList<Element>()
        var node: Node? = parent.firstChild
        while (node != null) {
            if (node is Element && node.tagName in tags) out += node
            node = node.nextSibling
        }
        return out
    }

    private fun Element.android(name: String): String? = getAttributeNS(ANDROID_NS, name).takeIf { it.isNotEmpty() }

    companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

        fun parse(file: File): MergedManifest {
            require(file.isFile) { "Merged manifest not found: $file (run processDebugManifest)" }
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(file)
            return MergedManifest(doc.documentElement)
        }
    }
}
