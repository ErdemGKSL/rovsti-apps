package dev.rovsti.platform.system

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import dev.rovsti.platform.system.permissions.SettingsKind
import dev.rovsti.platform.system.permissions.SpecialAccess

/** A [SpecialAccess] the test drives: flip the fields, read what was asked. */
class FakeSpecialAccess : SpecialAccess {
    var exactAlarms = true
    var fullScreenIntent = true
    var ignoresBattery = false
    val heldRoles = mutableSetOf<String>()
    val askedRoles = mutableListOf<String>()

    override fun exactAlarms() = exactAlarms

    override fun fullScreenIntent() = fullScreenIntent

    override fun ignoresBatteryOptimisation() = ignoresBattery

    override fun isRoleHeld(role: String) = role in heldRoles

    override fun requestRoleContract(role: String): ActivityResultContract<Unit, Boolean> =
        object : ActivityResultContract<Unit, Boolean>() {
            override fun createIntent(
                context: Context,
                input: Unit,
            ): Intent {
                askedRoles += role
                return Intent("fake.request.role").putExtra("role", role)
            }

            override fun parseResult(
                resultCode: Int,
                intent: Intent?,
            ) = resultCode == Activity.RESULT_OK && isRoleHeld(role)
        }

    override fun settingsChain(kind: SettingsKind): List<Intent> = listOf(Intent("fake.settings.${kind.name}"))
}
