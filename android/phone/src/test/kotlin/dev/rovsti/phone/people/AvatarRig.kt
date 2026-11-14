package dev.rovsti.phone.people

import dev.rovsti.core.model.people.PersonRow
import dev.rovsti.people.AvatarResolver
import dev.rovsti.people.PeopleIndex

/** A people cache with a WhatsApp picture for each of [numbers]: what the Phone avatar tests resolve against. */
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
