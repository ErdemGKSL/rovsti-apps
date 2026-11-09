package dev.rovsti.contacts.me

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.rovsti.contacts.ContactsContainer
import dev.rovsti.contacts.findComponentActivity

/** The My card view model, owned by the activity (the list row it feeds outlives the pages pushed over the list). */
@Composable
fun rememberMeViewModel(container: ContactsContainer): MeViewModel {
    val context = LocalContext.current
    val owner = remember(context) { context.findComponentActivity() }
    return remember(owner, container) {
        val factory =
            viewModelFactory {
                initializer { MeViewModel(container.book, container.store, container.ops) }
            }
        ViewModelProvider(owner, factory)[MeViewModel::class.java]
    }
}
