package com.orbit.ai.viewmodel

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.orbit.ai.AppContainer
import com.orbit.ai.OrbitApp

/** Creates a ViewModel that receives the app's dependency container. */
@Composable
inline fun <reified VM : ViewModel> orbitViewModel(crossinline make: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as OrbitApp).container
    return viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = make(container) as T
    })
}
