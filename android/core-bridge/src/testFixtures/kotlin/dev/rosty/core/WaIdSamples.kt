package dev.rosty.core

import dev.rosty.core.model.wa.WaIdKind
import dev.rosty.core.model.wa.WaIdParsed

/**
 * The strings of the unit tests of `rosty_core::wa_identity::parse` (`crates/rosty_core/src/wa_identity/id.rs`),
 * with what they parse to. `WaIdsTest` checks the Kotlin parser against them and `WaIdsNativeTest` asks the real
 * library for the same strings, so the two cannot drift apart.
 */
object WaIdSamples {
    private const val PN = "90555@s.whatsapp.net"

    private fun pn(device: Int? = null) = WaIdParsed(WaIdKind.Pn, "90555", PN, number = "+90555", device = device)

    /** Every id of the Rust table `PARSED`, and the ids of its other tests. */
    val parsed: List<Pair<String, WaIdParsed>> =
        listOf(
            "90555@s.whatsapp.net" to pn(),
            "90555:12@s.whatsapp.net" to pn(12),
            "90555.0:12@s.whatsapp.net" to pn(12),
            "90555_0:12@s.whatsapp.net" to pn(12),
            "123@lid" to WaIdParsed(WaIdKind.Lid, "123", "123@lid"),
            "123:7@lid" to WaIdParsed(WaIdKind.Lid, "123", "123@lid", device = 7),
            "12.34:7@lid" to WaIdParsed(WaIdKind.Lid, "12.34", "12.34@lid", device = 7),
            "123.0:7@lid" to WaIdParsed(WaIdKind.Lid, "123.0", "123.0@lid", device = 7),
            "1-2@g.us" to WaIdParsed(WaIdKind.Group, "1-2", "1-2@g.us"),
            "status@broadcast" to WaIdParsed(WaIdKind.Status, "status", "status@broadcast"),
            "1700@broadcast" to WaIdParsed(WaIdKind.Broadcast, "1700", "1700@broadcast"),
            "x@newsletter" to WaIdParsed(WaIdKind.Newsletter, "x", "x@newsletter"),
            "1@bot" to WaIdParsed(WaIdKind.Bot, "1", "1@bot"),
            "90555@c.us" to pn(),
            "90555:99@hosted" to pn(99),
            "123@hosted.lid" to WaIdParsed(WaIdKind.Lid, "123", "123@lid"),
            "0@s.whatsapp.net" to WaIdParsed(WaIdKind.Other, "0", "0@s.whatsapp.net"),
            "0@c.us" to WaIdParsed(WaIdKind.Other, "0", "0@s.whatsapp.net"),
            "abc@example.org" to WaIdParsed(WaIdKind.Other, "abc", "abc@example.org"),
            "77:3@call" to WaIdParsed(WaIdKind.Other, "77", "77@call", device = 3),
            // `splits_at_the_last_at_sign`, `surrounding_space_is_ignored`, `legacy_dotted_device_on_a_phone_number`.
            "a@b@g.us" to WaIdParsed(WaIdKind.Group, "a@b", "a@b@g.us"),
            " 123:7@lid\n" to WaIdParsed(WaIdKind.Lid, "123", "123@lid", device = 7),
            "90555.7@s.whatsapp.net" to pn(7),
            // A device that is no number, or too large for one, is dropped; the id stays.
            "90555:x@s.whatsapp.net" to pn(),
            "90555:70000@s.whatsapp.net" to pn(),
            "123:x@lid" to WaIdParsed(WaIdKind.Lid, "123", "123@lid"),
            // `agent_is_stripped_only_when_it_is_a_small_number`.
            "120363.1@g.us" to WaIdParsed(WaIdKind.Group, "120363", "120363@g.us"),
            "120363.999@g.us" to WaIdParsed(WaIdKind.Group, "120363.999", "120363.999@g.us"),
            "news.letter@newsletter" to WaIdParsed(WaIdKind.Newsletter, "news.letter", "news.letter@newsletter"),
            "123:99@hosted.lid" to WaIdParsed(WaIdKind.Lid, "123", "123@lid", device = 99),
            // The same digits under the two servers are two ids.
            "905550000001@lid" to WaIdParsed(WaIdKind.Lid, "905550000001", "905550000001@lid"),
            "905550000001@s.whatsapp.net" to
                WaIdParsed(WaIdKind.Pn, "905550000001", "905550000001@s.whatsapp.net", number = "+905550000001"),
        )

    /** `rejects_what_is_not_an_id`. */
    val rejected: List<String> =
        listOf(
            "",
            "   ",
            "90555",
            "s.whatsapp.net",
            "@s.whatsapp.net",
            "90555@",
            "abc@s.whatsapp.net",
            "+90555@s.whatsapp.net",
            "90 555@s.whatsapp.net",
            "90555x:3@s.whatsapp.net",
            "abc@c.us",
            "abc@hosted",
            ":7@lid",
            ".0:3@s.whatsapp.net",
            "90555.999:1@s.whatsapp.net",
        )
}
