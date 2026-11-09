package dev.rovsti.shell.permissions

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.staticCompositionLocalOf
import dev.rovsti.platform.system.permissions.PermissionController
import dev.rovsti.platform.system.permissions.SpecialAccess

/** The app's controller; provided by `RovstiShellActivity`. Tests provide one over a fake. */
val LocalPermissionController =
    staticCompositionLocalOf<PermissionController> { error("No PermissionController: run inside RovstiShellActivity") }

val LocalSpecialAccess =
    staticCompositionLocalOf<SpecialAccess> { error("No SpecialAccess: run inside RovstiShellActivity") }

internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
