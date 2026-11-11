package com.bunny.app.data

import com.bunny.engine.lontar.LontarAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The publisher project's own "TVs and Limited Input devices"
 * OAuth client (Google Cloud project bunny-pribadi, created
 * 2026-11-11 at the user's direction). The values are NOT source:
 * GitHub's secret scanning (correctly) refuses them in the repo,
 * so CI injects them from repository secrets as BuildConfig
 * fields — the same pattern as the fixed debug keystore. A build
 * without them leaves the TV door honestly unconfigured. The
 * client belongs to the user's own project and can be revoked or
 * rotated in the console at any time.
 */
object TvClientConfig {
    val CLIENT_ID: String = com.bunny.app.BuildConfig.TV_CLIENT_ID
    val CLIENT_SECRET: String = com.bunny.app.BuildConfig.TV_CLIENT_SECRET
    val isConfigured: Boolean
        get() = CLIENT_ID.isNotEmpty() && CLIENT_SECRET.isNotEmpty()
}

/** A device-code offer: the user types [userCode] at [verificationUrl]. */
data class DeviceCode(
    val userCode: String,
    val verificationUrl: String,
    val deviceCode: String,
    val intervalSec: Long,
    val expiresInSec: Long,
)

/** One poll of the token endpoint while the user types the code. */
sealed interface PollResult {
    data class Granted(
        val accessToken: String,
        val refreshToken: String?,
        val expiresInSec: Long,
    ) : PollResult

    data object Pending : PollResult
    data object SlowDown : PollResult
    data object Denied : PollResult
    data object Expired : PollResult
    data class Failed(val message: String) : PollResult
}

/**
 * Layar Besar (Jalan B): the official OAuth device flow (RFC 8628)
 * with the user's own TV client. Tokens born here are the kind
 * InnerTube accepts under TVHTML5 — the pairing the device probe
 * proved matters (Satu Ketuk's Android-born token was refused on
 * every context). The refresh token lives in the encrypted vault
 * via [AuthRepository]; the access token lives in the engine's
 * [LontarAuth] and in memory here, never in logs.
 */
@Singleton
class TvAuth @Inject constructor(
    private val httpClient: OkHttpClient,
    private val authRepository: AuthRepository,
    private val lontarAuth: LontarAuth,
) {

    @Volatile
    private var accessExpiryMillis: Long = 0L

    suspend fun requestCode(): DeviceCode {
        val root = postForm(
            DEVICE_CODE_URL,
            mapOf(
                "client_id" to TvClientConfig.CLIENT_ID,
                "scope" to AuthRepository.YOUTUBE_READONLY_SCOPE,
            ),
        )
        return DeviceCode(
            userCode = root.getString("user_code"),
            verificationUrl = root.optString(
                "verification_url",
                "https://www.google.com/device",
            ),
            deviceCode = root.getString("device_code"),
            intervalSec = root.optLong("interval", 5L).coerceAtLeast(5L),
            expiresInSec = root.optLong("expires_in", 1800L),
        )
    }

    suspend fun poll(deviceCode: String): PollResult {
        val (code, root) = postFormRaw(
            TOKEN_URL,
            mapOf(
                "client_id" to TvClientConfig.CLIENT_ID,
                "client_secret" to TvClientConfig.CLIENT_SECRET,
                "device_code" to deviceCode,
                "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
            ),
        )
        if (code == 200) return granted(root)
        return when (root.optString("error")) {
            "authorization_pending" -> PollResult.Pending
            "slow_down" -> PollResult.SlowDown
            "access_denied" -> PollResult.Denied
            "expired_token" -> PollResult.Expired
            else -> PollResult.Failed(
                root.optString("error").ifEmpty { "HTTP $code" },
            )
        }
    }

    /**
     * Keeps the engine's TV session usable: refreshes the access
     * token when it is missing or near expiry. False means the TV
     * sign-in is gone and a fresh code is needed.
     */
    suspend fun ensureFresh(): Boolean {
        val refreshToken = authRepository.tvRefreshToken() ?: return false
        val now = System.currentTimeMillis()
        if (lontarAuth.bearerToken != null &&
            lontarAuth.bearerContext == "TVHTML5" &&
            now < accessExpiryMillis - 300_000L
        ) {
            return true
        }
        val (code, root) = postFormRaw(
            TOKEN_URL,
            mapOf(
                "client_id" to TvClientConfig.CLIENT_ID,
                "client_secret" to TvClientConfig.CLIENT_SECRET,
                "refresh_token" to refreshToken,
                "grant_type" to "refresh_token",
            ),
        )
        if (code != 200) return false
        val granted = granted(root)
        lontarAuth.bearerToken = granted.accessToken
        lontarAuth.bearerContext = "TVHTML5"
        accessExpiryMillis = now + granted.expiresInSec * 1000L
        return true
    }

    /** Called right after a grant, before the identity proof. */
    fun adoptAccessToken(accessToken: String, expiresInSec: Long) {
        lontarAuth.bearerToken = accessToken
        lontarAuth.bearerContext = "TVHTML5"
        accessExpiryMillis =
            System.currentTimeMillis() + expiresInSec * 1000L
    }

    /** Best-effort remote revocation on sign-out. */
    suspend fun revoke(refreshToken: String) {
        runCatching {
            postFormRaw(REVOKE_URL, mapOf("token" to refreshToken))
        }
    }

    private fun granted(root: JSONObject): PollResult.Granted =
        PollResult.Granted(
            accessToken = root.getString("access_token"),
            refreshToken = root.optString("refresh_token")
                .takeIf { it.isNotEmpty() },
            expiresInSec = root.optLong("expires_in", 3600L),
        )

    private suspend fun postForm(
        url: String,
        fields: Map<String, String>,
    ): JSONObject {
        val (code, root) = postFormRaw(url, fields)
        if (code != 200) {
            throw java.io.IOException("OAuth HTTP $code")
        }
        return root
    }

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

    private companion object {
        const val DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
    }
}
