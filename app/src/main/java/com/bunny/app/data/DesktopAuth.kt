package com.bunny.app.data

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.browser.customtabs.CustomTabsIntent
import com.bunny.engine.lontar.LontarAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Satu Pintu (2026-11-12): the publisher project's "Desktop app"
 * OAuth client, driven through an authorization-code + PKCE flow
 * in a Chrome Custom Tab. The research question this door answers:
 * InnerTube's acceptance table has an EMPTY cell for desktop-born
 * tokens — nobody public has ever probed one. The token this flow
 * mints is tested against InnerTube's contexts AND the Data API;
 * the verdicts decide the final shape of the sign-in.
 *
 * Client values arrive exactly like the TV client's: repository
 * secrets → CI environment → BuildConfig. Never in source.
 */
object DesktopClientConfig {
    val CLIENT_ID: String = com.bunny.app.BuildConfig.DESKTOP_CLIENT_ID
    val CLIENT_SECRET: String =
        com.bunny.app.BuildConfig.DESKTOP_CLIENT_SECRET
    val isConfigured: Boolean
        get() = CLIENT_ID.isNotEmpty() && CLIENT_SECRET.isNotEmpty()

    /** The reversed-client-id scheme Google prescribes for installed apps. */
    val redirectScheme: String
        get() = "com.googleusercontent.apps." +
            CLIENT_ID.removeSuffix(".apps.googleusercontent.com")

    val redirectUri: String get() = "$redirectScheme:/oauth2redirect"
}

/** What came back through the redirect: a code, or Google's refusal. */
sealed interface DesktopRedirect {
    data class Code(val code: String) : DesktopRedirect
    data class Failed(val message: String) : DesktopRedirect
}

@Singleton
class DesktopAuth @Inject constructor(
    private val httpClient: OkHttpClient,
    private val authRepository: AuthRepository,
    private val lontarAuth: LontarAuth,
) {

    private val _redirect = MutableStateFlow<DesktopRedirect?>(null)
    val redirect: StateFlow<DesktopRedirect?> = _redirect.asStateFlow()

    private var pendingVerifier: String? = null
    private var pendingState: String? = null

    @Volatile
    private var accessExpiryMillis: Long = 0L

    /** The live desktop-born access token (memory only). */
    @Volatile
    var accessToken: String? = null
        private set

    /** Opens Google's own page in the system browser sheet. */
    fun begin(activity: Activity) {
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(24)
        pendingVerifier = verifier
        pendingState = state
        _redirect.value = null
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        val url = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", DesktopClientConfig.CLIENT_ID)
            .appendQueryParameter(
                "redirect_uri",
                DesktopClientConfig.redirectUri,
            )
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter(
                "scope",
                AuthRepository.YOUTUBE_READONLY_SCOPE,
            )
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .appendQueryParameter("state", state)
            .build()
        try {
            CustomTabsIntent.Builder().build().launchUrl(activity, url)
        } catch (e: Exception) {
            // No Custom Tab provider on this device: the plain
            // browser still honours the same redirect contract.
            activity.startActivity(Intent(Intent.ACTION_VIEW, url))
        }
    }

    /** MainActivity forwards every incoming deep link here. */
    fun handleRedirect(uri: Uri): Boolean {
        if (uri.scheme != DesktopClientConfig.redirectScheme) return false
        val expectedState = pendingState
        pendingState = null
        val returnedState = uri.getQueryParameter("state")
        val error = uri.getQueryParameter("error")
        val code = uri.getQueryParameter("code")
        _redirect.value = when {
            expectedState == null || returnedState != expectedState ->
                DesktopRedirect.Failed(
                    "Jawaban Google tidak cocok dengan permintaan " +
                        "(state). Coba lagi.",
                )
            error != null -> DesktopRedirect.Failed("Google: $error")
            code != null -> DesktopRedirect.Code(code)
            else -> DesktopRedirect.Failed("Google tidak mengirim kode.")
        }
        return true
    }

    fun consumeRedirect() {
        _redirect.value = null
    }

    data class Granted(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSec: Long,
    )

    /** Redeems the authorization code. Throws on transport/HTTP failure. */
    suspend fun exchange(code: String): Granted {
        val verifier = pendingVerifier
            ?: throw java.io.IOException("PKCE verifier hilang")
        pendingVerifier = null
        val (httpCode, root) = postFormRaw(
            TOKEN_URL,
            mapOf(
                "client_id" to DesktopClientConfig.CLIENT_ID,
                "client_secret" to DesktopClientConfig.CLIENT_SECRET,
                "code" to code,
                "code_verifier" to verifier,
                "grant_type" to "authorization_code",
                "redirect_uri" to DesktopClientConfig.redirectUri,
            ),
        )
        if (httpCode != 200) {
            throw java.io.IOException(
                "Penukaran kode HTTP $httpCode: " +
                    root.optString("error"),
            )
        }
        return granted(root)
    }

    /**
     * Keeps a desktop-born access token available for the Data API
     * layer and (if the probe adopts it) the engine. False means
     * the grant is gone and the user signs in again.
     */
    suspend fun ensureFresh(contextName: String?): Boolean {
        val refreshToken = authRepository.desktopRefreshToken()
            ?: return false
        val now = System.currentTimeMillis()
        if (accessToken != null &&
            now < accessExpiryMillis - 300_000L
        ) {
            return true
        }
        val (code, root) = try {
            postFormRaw(
                TOKEN_URL,
                mapOf(
                    "client_id" to DesktopClientConfig.CLIENT_ID,
                    "client_secret" to DesktopClientConfig.CLIENT_SECRET,
                    "refresh_token" to refreshToken,
                    "grant_type" to "refresh_token",
                ),
            )
        } catch (e: java.io.IOException) {
            return false
        }
        if (code != 200) return false
        val fresh = granted(root)
        accessToken = fresh.accessToken
        if (contextName != null) {
            lontarAuth.bearerToken = fresh.accessToken
            lontarAuth.bearerContext = contextName
        }
        accessExpiryMillis = now + fresh.expiresInSec * 1000L
        return true
    }

    /** Remembers a freshly granted pair (call right after exchange). */
    fun adopt(token: String, expiresInSec: Long) {
        accessToken = token
        accessExpiryMillis =
            System.currentTimeMillis() + expiresInSec * 1000L
    }

    /** Best-effort remote revocation on sign-out. */
    suspend fun revoke(refreshToken: String) {
        runCatching {
            postFormRaw(REVOKE_URL, mapOf("token" to refreshToken))
        }
    }

    private fun granted(root: JSONObject): Granted = Granted(
        accessToken = root.getString("access_token"),
        refreshToken = root.optString("refresh_token")
            .takeIf { it.isNotEmpty() },
        expiresInSec = root.optLong("expires_in", 3600L),
    )

    private suspend fun postFormRaw(
        url: String,
        fields: Map<String, String>,
    ): Pair<Int, JSONObject> = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
        fields.forEach { (key, value) -> form.add(key, value) }
        val request = Request.Builder().url(url).post(form.build()).build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            response.code to runCatching { JSONObject(text) }
                .getOrDefault(JSONObject())
        }
    }

    private fun randomUrlSafe(length: Int): String {
        val alphabet =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz" +
                "0123456789-._~"
        val random = SecureRandom()
        return buildString(length) {
            repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
        }
    }

    private companion object {
        const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    }
}
