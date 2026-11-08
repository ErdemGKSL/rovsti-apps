package dev.rovsti.testing

import android.view.View
import android.widget.Magnifier
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric cannot create the platform magnifier's surface, so `Magnifier.dismiss()` throws while a long-press
 * selection is active and the Compose test crashes. This shadow keeps the real class out of the way: the magnifier
 * never draws in a JVM test (a screenshot could not show it anyway).
 */
@Implements(Magnifier::class)
class ShadowNoopMagnifier {
    @Implementation
    @Suppress("unused", "ktlint:standard:function-naming")
    protected fun __constructor__(view: View) = Unit

    @Implementation
    protected fun show(
        sourceCenterX: Float,
        sourceCenterY: Float,
    ) = Unit

    @Implementation
    protected fun show(
        sourceCenterX: Float,
        sourceCenterY: Float,
        magnifierCenterX: Float,
        magnifierCenterY: Float,
    ) = Unit

    @Implementation
    protected fun update() = Unit

    @Implementation
    protected fun dismiss() = Unit

    @Implementation
    protected fun getWidth(): Int = 100

    @Implementation
    protected fun getHeight(): Int = 48

    @Implementation
    protected fun getSourceWidth(): Int = 100

    @Implementation
    protected fun getSourceHeight(): Int = 48
}
