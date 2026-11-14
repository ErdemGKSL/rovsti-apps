package dev.rovsti.contacts.people

import dev.rovsti.people.ui.AvatarFace
import dev.rovsti.people.ui.ContactAvatar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * X.1c: the contact photo is read in one place. Screens draw [ContactAvatar] from an [AvatarFace]; only state holders
 * (`*State.kt`, `*ViewModel.kt`, entries) and the resolver's own files mention `photoUri`, and nothing but the
 * editor's photo head hands a photo straight to `Avatar(photoModel = ...)`.
 */
class AvatarSourceScanTest {
    private val root = File("src/main/kotlin/dev/rovsti/contacts")

    private fun sources(): List<File> = root.walkTopDown().filter { it.extension == "kt" }.toList()

    @Test fun sourcesAreFound() {
        assertTrue("run from the module directory: ${root.absolutePath}", sources().size > 50)
    }

    @Test fun screensNeverReadThePhotoUri() {
        val offenders =
            sources()
                .filter { it.name.endsWith("Screen.kt") || it.name == "ContactPicker.kt" }
                .filter { it.name != "ContactEditorScreen.kt" }
                .filter { Regex("""\bphotoUri\b""").containsMatchIn(it.readText()) }
                .map { it.name }
        assertEquals("screens go through ContactAvatar(face): $offenders", emptyList<String>(), offenders)
    }

    @Test fun nothingButTheEditorHandsAPhotoToAvatarDirectly() {
        val offenders =
            sources()
                .filter { "photoModel" in it.readText() }
                .map { it.name }
                .filterNot { it in ALLOWED_PHOTO_MODEL || it == "ContactAvatar.kt" }
        assertEquals("use ContactAvatar: $offenders", emptyList<String>(), offenders)
    }

    @Test fun everyAvatarOfAContactWithAPhotoUsesTheResolver() {
        // Avatar( calls that remain: the editor's photo head (the draft's own picture, edited), the intake preview
        // (a picture decoded from the file being imported), the trash and My card rows (no photo is known there).
        val direct =
            sources()
                .filter { Regex("""(?<![A-Za-z])Avatar\(""").containsMatchIn(it.readText()) }
                .map { it.name }
                .filterNot { it == "ContactAvatar.kt" }
                .sorted()
        assertEquals(
            listOf("ContactEditorScreen.kt", "ContactsListScreen.kt", "IntakeScreen.kt", "TrashScreen.kt"),
            direct,
        )
    }

    private companion object {
        val ALLOWED_PHOTO_MODEL = setOf("ContactEditorScreen.kt")
    }
}
