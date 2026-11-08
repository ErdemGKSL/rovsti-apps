package dev.rovsti.testing

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule

/**
 * Accessibility checks for Compose UI tests (ORIGINAL).
 *
 * `enableAccessibilityChecks()` of ui-test is not public in the pinned BOM (ui-test 1.12.1, see
 * `docs/kotlin-ui/api-notes.md`), so these are our own checks over the merged semantics tree, run by
 * [ShotTest.assertAccessible] and, once [ShotTest.enableAccessibilityChecks] was called, after every `composeFrame`:
 *
 *  1. **Touch target.** Every visible node with a click action has touch bounds of at least [MinTargetDp] on both axes
 *     (the 48 dp rule of Android and the 44 pt rule of iOS, whichever is larger).
 *  2. **Label.** Every such node exposes text, a content description or state description a screen reader can speak
 *     (or is an editable field, which speaks its own hint).
 *  3. **Role.** A node that is selectable or toggleable names a role, so TalkBack does not announce a bare "button".
 *
 * Findings can be silenced one by one with [ShotTest.suppressAccessibility], which demands a written reason.
 */
data class AccessibilityFinding(
    val node: String,
    val problem: String,
) {
    override fun toString() = "$node: $problem"
}

const val MinTargetDp = 48f

fun ComposeContentTestRule.accessibilityFindings(density: Float): List<AccessibilityFinding> {
    val out = mutableListOf<AccessibilityFinding>()
    for (node in onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
        val bounds = node.touchBoundsInRoot
        if (bounds.width <= 0f || bounds.height <= 0f) continue
        if (node.config.getOrNull(SemanticsProperties.InvisibleToUser) != null) continue
        if (node.config.getOrNull(SemanticsProperties.Disabled) != null) continue
        if (node.isHidden()) continue
        val name = node.describe()
        val wDp = bounds.width / density
        val hDp = bounds.height / density
        // An item scrolled half out of a row or list is clipped by its scroller, not too small: judge it by the size
        // it lays out at (a chip at the edge of an overflowing chip row, in either layout direction).
        val laidOutBigEnough =
            node.size.width / density >= MinTargetDp - 0.5f && node.size.height / density >= MinTargetDp - 0.5f
        if (!laidOutBigEnough && (wDp < MinTargetDp - 0.5f || hDp < MinTargetDp - 0.5f)) {
            out +=
                AccessibilityFinding(name, "touch target %.0f x %.0f dp is below %.0f dp".format(wDp, hDp, MinTargetDp))
        }
        if (!node.isSpeakable()) {
            out +=
                AccessibilityFinding(name, "has a click action but nothing a screen reader can say")
        }
        val toggles =
            node.config.getOrNull(SemanticsProperties.ToggleableState) != null ||
                node.config.getOrNull(SemanticsProperties.Selected) != null
        if (toggles && node.config.getOrNull(SemanticsProperties.Role) == null) {
            out += AccessibilityFinding(name, "is selectable or toggleable but names no role")
        }
    }
    return out
}

private fun SemanticsNode.isHidden(): Boolean = config.getOrNull(SemanticsProperties.HideFromAccessibility) != null

private fun SemanticsNode.isSpeakable(): Boolean {
    val c = config
    if (c.getOrNull(SemanticsProperties.EditableText) != null) return true
    if (c.getOrNull(SemanticsProperties.ContentDescription)?.any { it.isNotBlank() } == true) return true
    if (c.getOrNull(SemanticsProperties.Text)?.any { it.text.isNotBlank() } == true) return true
    if (!c.getOrNull(SemanticsProperties.StateDescription).isNullOrBlank()) return true
    return c.getOrNull(SemanticsActions.OnClick)?.label?.isNotBlank() == true
}

private fun SemanticsNode.describe(): String {
    val c = config
    val tag = c.getOrNull(SemanticsProperties.TestTag)
    val text =
        c.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: c.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
    return "node#$id" + (tag?.let { " tag=$it" } ?: "") + (text?.let { " \"$it\"" } ?: "")
}
