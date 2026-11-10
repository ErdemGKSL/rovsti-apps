package dev.rovsti.phone.keypad

/** The dp sizes of the Keypad for one screen. Pure data: [KeypadLayout.compute] makes it, the screen only places it. */
data class KeypadSizes(
    /** Diameter of a digit key and of the call key. */
    val key: Float,
    val columnGap: Float,
    val rowGap: Float,
    /** Height of the number field row (the largest text it can show, plus room). */
    val fieldHeight: Float,
    /** How many suggestion rows fit between the field and the keys: 0 to 3. */
    val suggestionRows: Int,
    /** Height of the five key rows (4 digit rows and the call row). */
    val keysHeight: Float,
    /** Whether the letters under the digits are drawn (they are too small under 64 dp keys at a large font). */
    val letters: Boolean,
)

/**
 * The Keypad budget (E5.2). The screen is split top to bottom into the field, the "Add to Contacts" line, the suggestions
 * and the keys; the keys are anchored to the bottom (above the tab bar) so typing and suggestions never move them.
 * [compute] takes the room the screen has (the window less the status bar and the tab bar slot) and the font scale.
 */
object KeypadLayout {
    private const val KEY_MAX = 78f
    private const val KEY_MIN = 64f
    private const val ROW_GAP = 12f
    private const val ROW_GAP_TIGHT = 8f
    private const val SIDE = 16f
    private const val COLUMN_GAP_RATIO = 24f / 78f
    private const val FIELD_LINE = 36f * 1.2f
    private const val FIELD_PAD = 12f
    private const val ACTION_LINE = 36f
    private const val SUGGESTION_ROW = 56f
    private const val MAX_SUGGESTION_ROWS = 3
    private const val SUGGESTION_SCALE_CAP = 2f
    private const val FIELD_SCALE_CAP = 1.5f
    private const val MIN_SUGGESTION_GAP = 8f
    private const val LETTERS_FONT_CAP = 1.5f

    fun compute(
        viewportWidth: Float,
        viewportHeight: Float,
        fontScale: Float,
    ): KeypadSizes {
        val field = FIELD_LINE * fontScale.coerceIn(1f, FIELD_SCALE_CAP) + FIELD_PAD
        val fieldHeight = field.coerceAtLeast(56f)
        // The width bound: three keys and two gaps inside the side margins.
        val widthKey = (viewportWidth - 2 * SIDE) / (3f + 2f * COLUMN_GAP_RATIO)
        // One suggestion row and the action line are kept in reserve before the keys take the rest.
        val reserve = fieldHeight + SUGGESTION_ROW + ACTION_LINE + MIN_SUGGESTION_GAP
        var rowGap = ROW_GAP
        var key = minOf(KEY_MAX, widthKey, (viewportHeight - reserve - 4 * rowGap) / 5f)
        if (key < KEY_MIN) {
            // Too tight: tighter gaps first, then the floor (the suggestions give way, never the keys).
            rowGap = ROW_GAP_TIGHT
            key = minOf(KEY_MAX, widthKey, (viewportHeight - reserve - 4 * rowGap) / 5f).coerceAtLeast(KEY_MIN)
            key = minOf(key, widthKey)
        }
        val columnGap = key * COLUMN_GAP_RATIO
        val keysHeight = 5 * key + 4 * rowGap
        val room = viewportHeight - fieldHeight - keysHeight - MIN_SUGGESTION_GAP
        val rows =
            (
                (room - ACTION_LINE) / (
                    SUGGESTION_ROW *
                        fontScale.coerceIn(
                            1f,
                            SUGGESTION_SCALE_CAP,
                        )
                )
            ).toInt().coerceIn(0, MAX_SUGGESTION_ROWS)
        return KeypadSizes(
            key = key,
            columnGap = columnGap,
            rowGap = rowGap,
            fieldHeight = fieldHeight,
            suggestionRows = rows,
            keysHeight = keysHeight,
            letters = key >= KEY_MIN && fontScale <= LETTERS_FONT_CAP,
        )
    }

    /** The widest the three key columns are: what the screen centres. */
    fun gridWidth(sizes: KeypadSizes): Float = 3 * sizes.key + 2 * sizes.columnGap
}
