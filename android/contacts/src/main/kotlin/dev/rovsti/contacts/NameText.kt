package dev.rovsti.contacts

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** A contact that has no name, no company, no number and no address is listed under this word instead of nothing. */
@Composable
internal fun String.orNoName(): String = ifBlank { stringResource(R.string.contacts_no_name) }
