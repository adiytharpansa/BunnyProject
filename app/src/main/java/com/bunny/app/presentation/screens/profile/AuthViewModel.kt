package com.bunny.app.presentation.screens.profile

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.AuthRepository
import com.bunny.app.data.DesktopAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The profile's account bridge after the 2026-11-12 unification:
 * sign-in itself lives in [DesktopSignInViewModel] (the one door);
 * this bridge owns the session's quieter duties — keeping a fresh
 * token available for the personal layer, and the one sign-out
 * that wipes everything and revokes the grant at Google.
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val desktopAuth: DesktopAuth,
) : ViewModel() {

    val state: StateFlow<AuthRepository.State> = auth.state

    /**
     * A fresh token for the personal layer, without any UI: the
     * desktop grant refreshes silently while it lives. False means
     * the grant is gone — the data screens then ask the user to
     * sign in again from Profile (the one door).
     */
    fun ensureToken(activity: Activity, onResult: (Boolean) -> Unit) {
        if (auth.dataToken != null) {
            onResult(true)
            return
        }
        if (auth.desktopRefreshToken() == null) {
            onResult(false)
            return
        }
        viewModelScope.launch {
            onResult(desktopAuth.ensureFresh(null))
        }
    }

    fun signOut() {
        // The grant is revoked at Google too, not just forgotten
        // locally — captured before the wipe, revoked best-effort.
        val desktopRefresh = auth.desktopRefreshToken()
        auth.signOut()
        desktopAuth.forget()
        if (desktopRefresh != null) {
            viewModelScope.launch { desktopAuth.revoke(desktopRefresh) }
        }
    }
}
