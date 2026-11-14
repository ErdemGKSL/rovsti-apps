package dev.rovsti.contacts.people

import androidx.compose.runtime.Immutable
import dev.rovsti.core.model.contacts.ContactRow
import dev.rovsti.people.ui.AvatarFace

/**
 * One person as a Favorites, Frequently contacted, group or picker row: everything the row draws, already decided.
 * [searchText] is what a picker's search field matches (name and numbers); [colorKey] picks the monogram colour.
 */
@Immutable
data class PersonItem(
    val id: Long,
    val name: String,
    val secondary: String? = null,
    val photoUri: String? = null,
    val searchText: String = name,
    val numbers: List<String> = emptyList(),
) {
    /** The picture the resolver starts from (X.1c). */
    val face: AvatarFace get() = AvatarFace(photoUri, numbers)

    val colorKey: String get() = id.toString()
}

/** The row of `contacts.view` as a [PersonItem]; [extra] is searched too (the numbers). */
internal fun ContactRow.toPerson(secondary: String? = subtitle): PersonItem =
    PersonItem(
        id = id,
        name = name,
        secondary = secondary,
        photoUri = photoUri,
        searchText = (listOf(name) + numbers.map { it.raw }).joinToString(" "),
        numbers = numbers.map { it.raw },
    )
