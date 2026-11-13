package dev.rovsti.platform.identity

import android.content.Context
import dev.rovsti.platform.contacts.ContactsQuery

/**
 * Who a number is, for the parts of `:platform` that name a caller outside a screen: the call service (`CallRegistry`:
 * the call screens' snapshot and the CallStyle notifications), the missed-call notifications, the call reminders and the
 * WhatsApp push (F39). The app that hosts calls [install]s its live resolver ([CallerIds] over the core's number book,
 * the same one Recents uses), so every surface names a number the same way; without one (a cold process before the app
 * installed it, or an app with no index) Android's `PhoneLookup` answers with the same rules ([ContactsQuery.identity]).
 *
 * [identify] blocks: it is called off the main thread only.
 */
fun interface CallerLookup {
    /** The contact (or WhatsApp name) of [number]; null for a stranger, a withheld number or an unmatched short code. */
    fun identify(number: String): CallerIdentity?

    companion object {
        @Volatile private var installed: CallerLookup? = null

        /** The app's resolver; null takes the `PhoneLookup` fallback back. */
        fun install(lookup: CallerLookup?) {
            installed = lookup
        }

        /** The installed resolver, else `PhoneLookup` in the home region. */
        fun of(context: Context): CallerLookup =
            installed ?: run {
                val app = context.applicationContext ?: context
                CallerLookup { number -> ContactsQuery.identity(app, number, PhoneNumbers.homeRegion(app)) }
            }

        /**
         * Pure: the title a notification gives [number]: the contact's (or the WhatsApp) name of [who], else the number
         * as [format] writes it for reading, else null (a withheld caller: the notification says so in its own words).
         */
        fun titleOf(
            number: String,
            who: CallerIdentity?,
            format: (String) -> String,
        ): String? =
            who?.title?.trim()?.takeIf { it.isNotEmpty() }
                ?: number.trim().takeIf { it.isNotEmpty() && !PhoneNumbers.isWithheld(it) }?.let(format)
    }
}
