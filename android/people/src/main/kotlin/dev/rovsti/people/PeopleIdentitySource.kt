package dev.rovsti.people

import dev.rovsti.platform.identity.CallerIdentity
import dev.rovsti.platform.identity.IdentitySource
import kotlinx.coroutines.flow.StateFlow

/**
 * The people cache as a caller-ID source (X.1d): a number the cache knows from WhatsApp gets its WhatsApp name and
 * picture ([CallerIdentity.waPushName], [CallerIdentity.waPhotoUri]). It sits after the device contacts in
 * `CallerIds`, so a saved contact keeps its own name and photo and only gains what it lacks; a stranger on WhatsApp
 * becomes someone with a name and a picture. Reads memory only ([PeopleIndex]), so it never blocks the ringing screen
 * and never touches Rust, the network or the disk.
 */
class PeopleIdentitySource(
    private val index: PeopleIndex,
) : IdentitySource {
    override val revision: StateFlow<Long> get() = index.version

    /** The index is always readable; before its first load an empty answer means "nobody known yet". */
    override val ready: Boolean = true

    override suspend fun lookup(numbers: List<String>): Map<String, CallerIdentity> {
        val out = HashMap<String, CallerIdentity>()
        for (number in numbers) {
            val person = index.byNumber(number) ?: continue
            if (!person.row.onWhatsapp) continue
            val name =
                person.row.name
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            if (name == null && person.pictureUri == null) continue
            out[number] = CallerIdentity(waPushName = name, waPhotoUri = person.pictureUri)
        }
        return out
    }
}
