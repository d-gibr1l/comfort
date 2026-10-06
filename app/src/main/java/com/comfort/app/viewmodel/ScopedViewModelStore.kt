package com.comfort.app.viewmodel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/** A ViewModelStoreOwner that lives exactly as long as the calling composable: its ViewModels are
 * cleared when it leaves the composition. Gives a sheet (or one share) a ViewModel per opening,
 * instead of one kept by the activity across openings.
 *
 * It borrows the enclosing owner's (the activity's) default factory and creation extras, which
 * carry the Application — without them an AndroidViewModel created with plain viewModel() and no
 * initializer fails with "Cannot create an instance of ...". */
@Composable
fun rememberSheetViewModelStoreOwner(): ViewModelStoreOwner {
    val parent = LocalViewModelStoreOwner.current as? HasDefaultViewModelProviderFactory
    val store = remember { ViewModelStore() }
    DisposableEffect(store) { onDispose { store.clear() } }
    return remember(store, parent) {
        if (parent == null) {
            object : ViewModelStoreOwner {
                override val viewModelStore = store
            }
        } else {
            object : ViewModelStoreOwner, HasDefaultViewModelProviderFactory {
                override val viewModelStore = store
                override val defaultViewModelProviderFactory: ViewModelProvider.Factory get() = parent.defaultViewModelProviderFactory
                override val defaultViewModelCreationExtras: CreationExtras get() = parent.defaultViewModelCreationExtras
            }
        }
    }
}
