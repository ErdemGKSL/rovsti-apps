package dev.rovsti.phone.settings

/**
 * What the Data and Backup pages need from Android, kept apart so the screens and the tests never touch an activity:
 * the document the user saves to (SAF `CreateDocument` through `:platform`), the share sheet and the file picker.
 */
interface SettingsFilesHost {
    /** Asks where to save [text] as [fileName]; the result pill follows from the activity result. */
    fun save(
        fileName: String,
        mime: String,
        text: String,
        what: SavedWhat,
    )

    /** The system share sheet with [text]. */
    fun share(text: String)

    /** Opens the file picker for a backup file; the text arrives through the page's `Picked` action. */
    fun pickBackup()
}

/** What an export was, so the result pill can name it (U41). */
enum class SavedWhat { History, Notes, Contacts, Backup }

/** A file the user can save: the name and the text, as the core made them. */
data class ExportFile(
    val fileName: String,
    val mime: String,
    val text: String,
    val what: SavedWhat,
)

internal const val MIME_CSV = "text/csv"
internal const val MIME_VCARD = "text/x-vcard"
internal const val MIME_JSON = "application/json"
