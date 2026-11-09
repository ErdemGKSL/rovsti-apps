package dev.rovsti.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** E3.1 AC5 (ARCH 7.1): the SharedPreferences names are the ones the GPUI apps wrote; renaming one loses user data. */
class PrefsNamesTest {
    @Test fun allHoldsTheEightNames() {
        assertEquals(
            setOf(
                "rovsti_bridge",
                "rovsti_scheduled_sms",
                "rovsti_birthday_reminders",
                "rovsti_alarm",
                "rovsti_call_reminders",
                "rovsti_message_reminders",
                "rovsti_phone_settings",
                "rovsti_trip",
            ),
            PlatformPrefs.ALL,
        )
    }

    @Test fun everyGetSharedPreferencesCallUsesAConstant() {
        val call = Regex("""getSharedPreferences\(\s*([^,)]*)""")
        val bad = mutableListOf<String>()
        File("src/main").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            call.findAll(file.readText()).forEach { m ->
                val arg = m.groupValues[1].trim()
                val constant = arg.startsWith("PlatformPrefs.") && arg.removePrefix("PlatformPrefs.") in names
                val local = arg == "FILE" || arg == "NAME" // a private const val set from a PlatformPrefs constant
                if (!constant &&
                    !(local && file.readText().contains(Regex("""const val $arg\s*=\s*PlatformPrefs\.""")))
                ) {
                    bad += "${file.name}: $arg"
                }
            }
        }
        assertEquals("these calls use a literal or an unknown name", emptyList<String>(), bad)
    }

    @Test fun constantsAreUniqueAndPrefixed() {
        assertEquals(8, PlatformPrefs.ALL.size)
        assertTrue(PlatformPrefs.ALL.all { it.startsWith("rovsti_") })
    }

    private val names =
        setOf(
            "BRIDGE",
            "SCHEDULED_SMS",
            "BIRTHDAY_REMINDERS",
            "ALARM",
            "CALL_REMINDERS",
            "MESSAGE_REMINDERS",
            "PHONE_SETTINGS",
            "TRIP",
        )
}
