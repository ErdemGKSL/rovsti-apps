package dev.rovsti.design.glass

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** How much of the glass material a surface gets. Chosen by [glassTier], never by the call site. */
enum class GlassTier {
    /** Saturation, blur, lens and rim light. */
    Liquid,

    /** Saturation, blur and a hairline. No lens, no rim shader. */
    Frost,

    /** No capture: a fill, a hairline and a shadow. */
    Flat,
}

/** Everything [glassTier] looks at, so the decision is a pure function that tests can tabulate. */
@Immutable
data class GlassConditions(
    val sdk: Int,
    val lowRam: Boolean,
    val powerSave: Boolean,
    val reduceTransparency: Boolean,
    val reduceEffects: Boolean,
    val sourceAvailable: Boolean,
    val contentMoving: Boolean,
    val forTests: Boolean,
) {
    companion object {
        /** A modern phone with nothing switched off. */
        val Default =
            GlassConditions(
                sdk = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                lowRam = false,
                powerSave = false,
                reduceTransparency = false,
                reduceEffects = false,
                sourceAvailable = true,
                contentMoving = false,
                forTests = false,
            )
    }
}

/** The one place that decides the tier. */
fun glassTier(c: GlassConditions): GlassTier =
    when {
        c.forTests || c.reduceTransparency || !c.sourceAvailable || c.sdk < Build.VERSION_CODES.S -> {
            GlassTier.Flat
        }

        c.lowRam && c.powerSave -> {
            GlassTier.Flat
        }

        c.sdk < Build.VERSION_CODES.TIRAMISU || c.lowRam || c.powerSave || c.reduceEffects || c.contentMoving -> {
            GlassTier.Frost
        }

        else -> {
            GlassTier.Liquid
        }
    }

/**
 * The ambient part of the conditions (device, power saver, the user's toggles). Per-surface parts
 * (`sourceAvailable`, `contentMoving`) are filled in where a surface resolves its own tier.
 */
val LocalGlassConditions = staticCompositionLocalOf { GlassConditions.Default }

/**
 * Reads the device and listens to the power saver. [reduceTransparency] and [reduceEffects] are the app's own
 * settings: Android has no public system switch for either.
 */
@Composable
fun rememberGlassConditions(
    reduceTransparency: Boolean = false,
    reduceEffects: Boolean = false,
    forTests: Boolean = false,
): GlassConditions {
    val context = LocalContext.current
    var powerSave by remember { mutableStateOf(context.isPowerSave()) }
    DisposableEffect(context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    c: Context,
                    intent: Intent,
                ) {
                    powerSave = c.isPowerSave()
                }
            }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        powerSave = context.isPowerSave()
        onDispose { context.unregisterReceiver(receiver) }
    }
    val lowRam = remember(context) { context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true }
    return remember(powerSave, lowRam, reduceTransparency, reduceEffects, forTests) {
        GlassConditions.Default.copy(
            sdk = Build.VERSION.SDK_INT,
            lowRam = lowRam,
            powerSave = powerSave,
            reduceTransparency = reduceTransparency,
            reduceEffects = reduceEffects,
            forTests = forTests,
        )
    }
}

private fun Context.isPowerSave(): Boolean = getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
