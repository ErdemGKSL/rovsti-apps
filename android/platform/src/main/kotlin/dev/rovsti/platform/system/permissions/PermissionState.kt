package dev.rovsti.platform.system.permissions

/**
 * What the UI knows about one permission group (ARCH 7.5). A Kotlin twin of `rovsti_app_core::permission::Access` with
 * the two extra facts a settings row needs: whether the dialog was ever shown, and whether a grant was lost while the
 * process ran.
 */
sealed interface PermissionState {
    /** The dialog was never shown. */
    data object NeverAsked : PermissionState

    data object Granted : PermissionState

    /** Denied, and Android will still show the dialog. */
    data object DeniedOnce : PermissionState

    /** Denied with "don't ask again" (or the dialog was shown and Android no longer offers it). */
    data object DeniedForGood : PermissionState

    /** Granted earlier in this process and not any more: the user revoked it in Settings. */
    data object RevokedWhileRunning : PermissionState

    /** The `permissions_changed` wire word, the same strings the Rust `Access` serialises to. */
    val wire: String
        get() =
            when (this) {
                Granted -> "granted"
                DeniedForGood -> "permanently_denied"
                else -> "denied"
            }
}

/** What a settings row offers next. */
enum class NextAction { Ask, OpenSettings, None }

/**
 * Pure decision. [granted] is every permission of the group held; [askedBefore] the `asked_<group>` flag;
 * [shouldShowRationale] Android's answer for any missing permission; [wasGrantedThisProcess] whether an earlier refresh
 * of this process saw the group granted.
 */
fun decide(
    granted: Boolean,
    askedBefore: Boolean,
    shouldShowRationale: Boolean,
    wasGrantedThisProcess: Boolean,
): PermissionState =
    when {
        granted -> PermissionState.Granted
        wasGrantedThisProcess -> PermissionState.RevokedWhileRunning
        shouldShowRationale -> PermissionState.DeniedOnce
        askedBefore -> PermissionState.DeniedForGood
        else -> PermissionState.NeverAsked
    }

/** The row's next action: ask while Android still shows the dialog, otherwise send the user to Settings. */
fun nextAction(state: PermissionState): NextAction =
    when (state) {
        PermissionState.NeverAsked, PermissionState.DeniedOnce -> NextAction.Ask
        PermissionState.Granted -> NextAction.None
        PermissionState.DeniedForGood, PermissionState.RevokedWhileRunning -> NextAction.OpenSettings
    }
