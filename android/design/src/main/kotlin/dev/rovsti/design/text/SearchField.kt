// Portions ported from glisse (https://github.com/0kzh/glisse), MIT License, Copyright (c) 2026 Kelvin Zhang
package dev.rovsti.design.text

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import dev.rovsti.design.R

/**
 * A [GlassTextField] for searching (glisse `SearchField`): a magnifier, a "Search" placeholder and a clear button, the
 * keyboard's action key reads Search and runs [onSubmit]. Capitalisation is off, since queries are names and numbers.
 */
@Composable
fun SearchField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    placeholder: String = stringResource(R.string.design_search),
    enabled: Boolean = true,
    onSubmit: () -> Unit = {},
) {
    GlassTextField(
        state = state,
        placeholder = placeholder,
        modifier = modifier,
        search = true,
        enabled = enabled,
        imeAction = ImeAction.Search,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        onSubmit = onSubmit,
    )
}
