package dev.rosty.contacts.people

import dev.rosty.core.model.people.PersonRow
import dev.rosty.people.AvatarResolver
import dev.rosty.people.PeopleIndex

/** A people cache with a WhatsApp picture for each of [numbers]: what the avatar tests and shots resolve against. */
internal fun waResolver(numbers: Collection<String>): AvatarResolver {
    val index = PeopleIndex()
    index.replace(numbers.map(::waPerson)) { "file:///people/${it.jid}.jpg" }
    return AvatarResolver(index)
}

internal fun waPerson(number: String): PersonRow {
    val digits = number.filter(Char::isDigit)
    return PersonRow(
        jid = "$digits@s.whatsapp.net",
        numbers = listOf(number),
        name = "WA $digits",
        onWhatsapp = true,
        pictureId = "pic-$digits",
        pictureFile = "$digits.jpg",
    )
}
