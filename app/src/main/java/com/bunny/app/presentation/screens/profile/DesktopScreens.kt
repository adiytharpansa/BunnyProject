package com.bunny.app.presentation.screens.profile

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.AuthRepository
import com.bunny.app.data.DesktopAuth
import com.bunny.app.data.DesktopClientConfig
import com.bunny.app.data.DesktopRedirect
import com.bunny.app.data.YouTubeDataApi
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.BearerContextVerdict
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Satu Pintu, Langkah 1: the probe. One button opens Google's page
 * in the system browser sheet (Desktop client, PKCE); the token it
 * mints is put on two benches — InnerTube's three Bearer contexts
 * and the official Data API — and the verdicts are shown verbatim
 * in Profile. Nothing about the older doors changes in this step;
 * the verdicts decide Langkah 2's unified shape.
 */
@HiltViewModel
class DesktopProbeViewModel @Inject constructor(
    private val desktopAuth: DesktopAuth,
    private val auth: AuthRepository,
    private val backend: ExtractionBackend,
    private val dataApi: YouTubeDataApi,
) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Waiting : State
        data object Probing : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _verdicts = MutableStateFlow<List<BearerContextVerdict>>(emptyList())
    val verdicts: StateFlow<List<BearerContextVerdict>> = _verdicts.asStateFlow()

    /** "DITERIMA — <channel>" / "DITOLAK" once the Data API bench ran. */
    private val _dataVerdict = MutableStateFlow<String?>(null)
    val dataVerdict: StateFlow<String?> = _dataVerdict.asStateFlow()

    val configured: Boolean get() = DesktopClientConfig.isConfigured

    val desktopSignedIn: StateFlow<Boolean> = auth.desktopSignedIn

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
                "Build ini belum membawa klien Desktop.",
            )
            return
        }
        if (_state.value is State.Waiting || _state.value is State.Probing) {
            return
        }
        _state.value = State.Waiting
        desktopAuth.begin(activity)
    }

    private fun prove(code: String) {
        _state.value = State.Probing
        viewModelScope.launch {
            try {
                val granted = desktopAuth.exchange(code)
                desktopAuth.adopt(granted.accessToken, granted.expiresInSec)
                val token = granted.accessToken
                // Bench 1: InnerTube's doors (the empty table cell).
                val contexts = try {
                    backend.probeBearerContexts(token)
                } catch (e: Exception) {
                    emptyList()
                }
                _verdicts.value = contexts
                val winner = contexts.firstOrNull { it.accepted }
                if (winner != null) {
                    auth.signInBearer(winner, token)
                }
                // Bench 2: the official Data API.
                val mine = try {
                    dataApi.mine(token)
                } catch (e: Exception) {
                    null
                }
                if (mine != null) {
                    _dataVerdict.value = "DITERIMA — ${mine.title}"
                    auth.signInDesktop(
                        refreshToken = granted.refreshToken,
                        name = mine.title,
                        avatarUrl = mine.avatarUrl,
                    )
                } else {
                    _dataVerdict.value = "DITOLAK"
                }
                _state.value = State.Done
            } catch (e: Exception) {
                _state.value = State.Failed(
                    e.message ?: "Penukaran kode gagal.",
                )
            }
        }
    }
}
