package com.bunny.app.presentation.screens.profile

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.AuthRepository
import com.bunny.core.model.BearerContextVerdict
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Bridge between the profile screen and the two sign-in generations:
 * the Fase 5 cookie harvest (kept for the repository's sake) and
 * Satu Ketuk — the system account sheet via Google's Authorization
 * API. One tap asks Play services for a YouTube-read token; when a
 * consent resolution is needed, the screen launches the system's own
 * sheet from [OneTap.NeedsConsent]. A fresh token is then PROBED
 * against the engine's account door (Langkah 1's evidence step)
 * before anything is called "signed in".
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val backend: com.bunny.core.backend.ExtractionBackend,
) : ViewModel() {

    val state: StateFlow<AuthRepository.State> = auth.state

    sealed interface OneTap {
        data object Idle : OneTap
        data object Working : OneTap
        data class NeedsConsent(val intentSender: IntentSender) : OneTap
        data object Probing : OneTap
        data class Failed(val message: String) : OneTap
    }

    private val _oneTap = MutableStateFlow<OneTap>(OneTap.Idle)
    val oneTap: StateFlow<OneTap> = _oneTap.asStateFlow()

    private val _verdicts = MutableStateFlow<List<BearerContextVerdict>>(emptyList())
    val verdicts: StateFlow<List<BearerContextVerdict>> = _verdicts.asStateFlow()

    /** Stores the WebView-harvested session; reports whether it took. */
    fun onCookiesHarvested(cookieHeader: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(auth.signIn(cookieHeader))
        }
    }

    fun beginOneTap(activity: Activity) {
        val current = _oneTap.value
        if (current is OneTap.Working || current is OneTap.Probing) return
        _oneTap.value = OneTap.Working
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(AuthRepository.YOUTUBE_READONLY_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val sender = result.pendingIntent?.intentSender
                    if (sender != null) {
                        _oneTap.value = OneTap.NeedsConsent(sender)
                    } else {
                        failOneTap("Google tidak memberi jalan persetujuan.")
                    }
                } else {
                    val token = result.accessToken
                    if (token != null) {
                        onAccessToken(token)
                    } else {
                        failOneTap("Google tidak memberi kunci.")
                    }
                }
            }
            .addOnFailureListener { e ->
                failOneTap(e.message ?: "Otorisasi Google gagal.")
            }
    }

    /** The screen launched the consent sheet; back to quiet waiting. */
    fun consumeConsent() {
        if (_oneTap.value is OneTap.NeedsConsent) {
            _oneTap.value = OneTap.Working
        }
    }

    fun onConsentResult(activity: Activity, data: Intent?) {
        try {
            val result = Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(data)
            val token = result.accessToken
            if (token != null) {
                onAccessToken(token)
            } else {
                failOneTap("Persetujuan selesai, tetapi tanpa kunci.")
            }
        } catch (e: ApiException) {
            failOneTap("Persetujuan dibatalkan atau gagal.")
        }
    }

    private fun onAccessToken(token: String) {
        _oneTap.value = OneTap.Probing
        viewModelScope.launch {
            val results = try {
                backend.probeBearerContexts(token)
            } catch (e: Exception) {
                emptyList()
            }
            _verdicts.value = results
            val winner = results.firstOrNull { it.accepted }
            if (winner != null) {
                auth.signInBearer(winner, token)
                _oneTap.value = OneTap.Idle
            } else {
                failOneTap(
                    "Kunci dari Google sah, tetapi semua pintu mesin menolaknya.",
                )
            }
        }
    }

    private fun failOneTap(message: String) {
        _oneTap.value = OneTap.Failed(message)
    }

    fun signOut() {
        auth.signOut()
        _verdicts.value = emptyList()
        _oneTap.value = OneTap.Idle
    }
}
