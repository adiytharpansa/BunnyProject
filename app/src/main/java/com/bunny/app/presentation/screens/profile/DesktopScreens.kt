package com.bunny.app.presentation.screens.profile

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.AuthRepository
import com.bunny.app.data.DesktopAuth
import com.bunny.app.data.DesktopClientConfig
import com.bunny.app.data.DesktopRedirect
import com.bunny.app.data.YouTubeDataApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * THE sign-in (Satu Pintu, unified 2026-11-12 on the user's
 * "satukan jadi satu tombol" + "jangan pernah ada cadangan"):
 * one button, one door. Google's own page in the system browser
 * sheet (Desktop client, authorization code + PKCE) mints the
 * token; the account's own channel answering at the official
 * Data API is what signs the user in. There is no second door:
 * the InnerTube probes (Android-born 2026-11-11, Desktop-born
 * 2026-11-12, both 403/403/403) proved the internal doors accept
 * TV-born tokens only, and the TV code door was removed rather
 * than kept as a spare.
 */
@HiltViewModel
class DesktopSignInViewModel @Inject constructor(
    private val desktopAuth: DesktopAuth,
    private val auth: AuthRepository,
    private val dataApi: YouTubeDataApi,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Waiting : State
        data object Exchanging : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val configured: Boolean get() = DesktopClientConfig.isConfigured

    init {
        viewModelScope.launch {
            desktopAuth.redirect.collect { result ->
                if (result == null) return@collect
                desktopAuth.consumeRedirect()
                when (result) {
                    is DesktopRedirect.Failed ->
                        _state.value = State.Failed(result.message)
                    is DesktopRedirect.Code -> prove(result.code)
                }
            }
        }
    }

    fun start(activity: Activity) {
        if (!DesktopClientConfig.isConfigured) {
            _state.value = State.Failed(
                "Build ini belum membawa klien masuk.",
            )
            return
        }
        val current = _state.value
        if (current is State.Waiting || current is State.Exchanging) return
        _state.value = State.Waiting
        desktopAuth.begin(activity)
    }

    private fun prove(code: String) {
        _state.value = State.Exchanging
        viewModelScope.launch {
            try {
                val granted = desktopAuth.exchange(code)
                desktopAuth.adopt(granted.accessToken, granted.expiresInSec)
                val mine = try {
                    dataApi.mine(granted.accessToken)
                } catch (e: Exception) {
                    null
                }
                if (mine != null) {
                    auth.signInDesktop(
                        refreshToken = granted.refreshToken,
                        name = mine.title,
                        avatarUrl = mine.avatarUrl,
                    )
                    _state.value = State.Idle
                } else {
                    _state.value = State.Failed(
                        "Akun ini belum mempunyai kanal YouTube, " +
                            "atau kantor data Google menolak kuncinya.",
                    )
                }
            } catch (e: Exception) {
                _state.value = State.Failed(
                    e.message ?: "Penukaran kode gagal.",
                )
            }
        }
    }
}
