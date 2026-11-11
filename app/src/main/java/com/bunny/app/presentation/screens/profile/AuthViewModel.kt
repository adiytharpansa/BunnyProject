package com.bunny.app.presentation.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Thin bridge between the profile/login screens and [AuthRepository]. */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
) : ViewModel() {

    val state: StateFlow<AuthRepository.State> = auth.state

    /** Stores the WebView-harvested session; reports whether it took. */
    fun onCookiesHarvested(cookieHeader: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(auth.signIn(cookieHeader))
        }
    }

    fun signOut() {
        auth.signOut()
    }
}
