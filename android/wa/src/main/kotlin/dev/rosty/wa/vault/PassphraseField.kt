package dev.rosty.wa.vault

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import dev.rosty.design.theme.RostyTheme
import dev.rosty.design.theme.Sizes
import dev.rosty.design.theme.Spacing
import dev.rosty.wa.R

/**
 * What is typed into a [PassphraseField]. The text lives in the field's own `Editable` and here only as long as the
 * screen needs it; [clear] empties both after a try.
 */
@Stable
class PassphraseEntry {
    var text by mutableStateOf("")
        internal set

    internal var view: EditText? = null

    fun clear() {
        text = ""
        view?.text?.clear()
    }
}

/** The editor flags of the passphrase field (plan 4): no learning, no suggestions, no full-screen editor. */
internal const val PASSPHRASE_IME_OPTIONS =
    EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI

/** A visible password: shown as typed (it is long and grouped), but treated as a secret by keyboards and autofill. */
internal const val PASSPHRASE_INPUT_TYPE =
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
        InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS

/**
 * The passphrase entry: a platform [EditText], because Compose text fields cannot ask the keyboard not to learn
 * (`IME_FLAG_NO_PERSONALIZED_LEARNING`). Paste from the long-press menu and autofill from a password manager work as
 * in any password field.
 */
@Composable
fun PassphraseField(
    entry: PassphraseEntry,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = RostyTheme.colors
    val hint = stringResource(R.string.vault_passphrase_hint)
    val label = stringResource(R.string.vault_passphrase_label)
    val submit by rememberUpdatedState(onSubmit)
    val textColor = colors.labelPrimary.toArgb()
    val hintColor = colors.labelTertiary.toArgb()
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = Sizes.touch)
            .background(colors.groupedSurface, RoundedCornerShape(Spacing.m))
            .padding(horizontal = Spacing.m)
            .testTag(VaultTags.PassphraseField),
    ) {
        AndroidView(
            factory = { context -> passphraseEditText(context, label) { submit() } },
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touch),
            update = { view ->
                if (entry.view !== view) {
                    entry.view = view
                    view.addTextChangedListener(watcher { entry.text = it })
                }
                view.hint = hint
                view.setTextColor(textColor)
                view.setHintTextColor(hintColor)
                view.isEnabled = enabled
            },
            onRelease = { view -> if (entry.view === view) entry.view = null },
        )
    }
}

private fun passphraseEditText(
    context: Context,
    label: String,
    onDone: () -> Unit,
): EditText =
    EditText(context).apply {
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER_VERTICAL
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, PASSPHRASE_TEXT_SP)
        isSingleLine = true
        inputType = PASSPHRASE_INPUT_TYPE
        imeOptions = PASSPHRASE_IME_OPTIONS
        setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
        contentDescription = label
        setOnEditorActionListener { _, action, _ ->
            (action == EditorInfo.IME_ACTION_DONE).also { if (it) onDone() }
        }
    }

private fun watcher(onText: (String) -> Unit): TextWatcher =
    object : TextWatcher {
        override fun beforeTextChanged(
            s: CharSequence?,
            start: Int,
            count: Int,
            after: Int,
        ) = Unit

        override fun onTextChanged(
            s: CharSequence?,
            start: Int,
            before: Int,
            count: Int,
        ) = Unit

        override fun afterTextChanged(s: Editable?) = onText(s?.toString().orEmpty())
    }

private const val PASSPHRASE_TEXT_SP = 17f
