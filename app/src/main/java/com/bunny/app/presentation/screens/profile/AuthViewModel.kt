package com.bunny.app.presentation.screens.profile

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bunny.app.data.AuthRepository
import com.bunny.app.data.DataApiException
import com.bunny.app.data.YouTubeDataApi
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
 * sheet from [OneTap.NeedsConsent]. The token is then proven against
 * the official Data API (Jalan A): the account's own channel
 * answering is what signs the user in — InnerTube refused this
 * token on every context (device probe 2026-11-11).
 */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val dataApi: YouTubeDataApi,
    private val tvAuth: com.bunny.app.data.TvAuth,
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

    /**
     * Jalan A (keputusan pengguna 2026-11-11): the token's home is
     * the official Data API — InnerTube refused it on every context
     * (device probe: 403/403/403). Acceptance here is proven by the
     * account's own channel answering; that identity signs the user
     * in. The verdict stays on screen as the honest record.
     */
    private fun onAccessToken(token: String) {
        auth.dataToken = token
        _oneTap.value = OneTap.Probing
        viewModelScope.launch {
            try {
                val mine = dataApi.mine(token)
                if (mine != null) {
                    _verdicts.value = listOf(
                        BearerContextVerdict(
                            contextName = "Data API v3",
                            httpStatus = 200,
                            accepted = true,
                            accountName = mine.title,
                            accountHandle = mine.handle,
                            accountEmail = null,
                            avatarUrl = mine.avatarUrl,
                        ),
                    )
                    auth.signInDataApi(mine, token)
                    _oneTap.value = OneTap.Idle
                } else {
                    _verdicts.value = listOf(
                        BearerContextVerdict(
                            contextName = "Data API v3",
                            httpStatus = 200,
                            accepted = false,
                        ),
                    )
                    failOneTap("Akun ini belum mempunyai kanal YouTube.")
                }
            } catch (e: DataApiException) {
                _verdicts.value = listOf(
                    BearerContextVerdict(
                        contextName = "Data API v3",
                        httpStatus = e.httpCode,
                        accepted = false,
                    ),
                )
                failOneTap(
                    "Kantor data resmi menolak kunci ini (HTTP ${e.httpCode}).",
                )
            } catch (e: Exception) {
                _verdicts.value = listOf(
                    BearerContextVerdict(
                        contextName = "Data API v3",
                        httpStatus = null,
                        accepted = false,
                    ),
                )
                failOneTap("Gagal menghubungi kantor data Google.")
            }
        }
    }

    /**
     * A fresh token for the personal layer, without any UI: after
     * the first consent, the Authorization API answers silently.
     * Reports false when a fresh consent would be needed — the data
     * screens then ask the user to sign in again from Profile.
     */
    fun ensureToken(activity: Activity, onResult: (Boolean) -> Unit) {
        if (auth.dataToken != null) {
            onResult(true)
            return
        }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(AuthRepository.YOUTUBE_READONLY_SCOPE)))
            .build()
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                val token = result.accessToken
                if (!result.hasResolution() && token != null) {
                    auth.dataToken = token
                    onResult(true)
                } else {
                    onResult(false)
                }
            }
            .addOnFailureListener { onResult(false) }
    }

    private fun failOneTap(message: String) {
        _oneTap.value = OneTap.Failed(message)
    }

    fun signOut() {
        // The TV grant is revoked at Google too, not just forgotten
        // locally — captured before the wipe, revoked best-effort.
        val tvRefresh = auth.tvRefreshToken()
        auth.signOut()
        if (tvRefresh != null) {
            viewModelScope.launch { tvAuth.revoke(tvRefresh) }
        }
        _verdicts.value = emptyList()
        _oneTap.value = OneTap.Idle
    }
}
