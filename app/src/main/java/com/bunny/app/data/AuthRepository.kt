package com.bunny.app.data

import android.accounts.Account
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import com.bunny.core.backend.ExtractionBackend
import com.bunny.core.model.BearerContextVerdict
import com.bunny.engine.lontar.AuthCookies
import com.bunny.engine.lontar.LontarAuth
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fase 5 — the Google login's home on the app side.
 *
 * The sign-in itself happens in a WebView the user drives; this
 * repository receives the harvested youtube.com cookies, keeps them
 * ONLY as an AES-GCM blob whose key never leaves the Android
 * Keystore, and mirrors them into the engine's [LontarAuth]. Sign-out
 * wipes the blob, the engine's copy, and the WebView's cookie jar.
 * Cookie values are never logged, never put in diagnostics, and
 * never leave the device except as headers on YouTube's own API
 * calls — the same calls the official web client makes.
 */
@Singleton
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lontarAuth: LontarAuth,
    private val backend: ExtractionBackend,
) {

    sealed interface State {
        data object SignedOut : State
        data class SignedIn(val name: String?, val avatarUrl: String?) : State
    }

    private val _state = MutableStateFlow<State>(State.SignedOut)
    val state: StateFlow<State> = _state.asStateFlow()

    val isSignedIn: Boolean get() = _state.value is State.SignedIn

    // The stored pieces, kept in step with the encrypted blob so the
    // cookie session and the Satu Ketuk token never overwrite each
    // other when one of them is refreshed.
    private var cookieHeaderStored: String = ""
    private var bearerTokenStored: String? = null
    private var bearerContextStored: String? = null
    private var bearerEmailStored: String? = null

    /**
     * The live Satu Ketuk token for the Data API layer. Memory only:
     * access tokens are short-lived, and a fresh one is fetched
     * silently through the Authorization API whenever one is needed.
     */
    @Volatile
    var dataToken: String? = null

    // The Data API identity snapshot (the user's own channel).
    private var apiIdStored: String? = null
    private var apiNameStored: String? = null
    private var apiHandleStored: String? = null
    private var apiAvatarStored: String? = null

    // Satu Pintu: the Desktop-client session's refresh token. The
    // access token lives in DesktopAuth's memory; this is the
    // durable half, kept in the same encrypted blob.
    private var desktopRefreshStored: String? = null

    private val _desktopSignedIn = MutableStateFlow(false)
    val desktopSignedIn: StateFlow<Boolean> = _desktopSignedIn.asStateFlow()

    fun desktopRefreshToken(): String? = desktopRefreshStored

    init {
        restore()
    }

    private fun restore() {
        val blob = prefs().getString(KEY_BLOB, null) ?: return
        val plain = decrypt(blob) ?: return
        val stored = runCatching { JSONObject(plain) }.getOrNull() ?: return
        // The Fase 5 cookie session and the Satu Ketuk bearer slot
        // are read only so sign-out can still revoke those grants;
        // neither is applied as a session any more (unification
        // 2026-11-12: one door, no spares).
        cookieHeaderStored = stored.optString("cookie")
        val token = stored.optString("bearerToken").takeIf { it.isNotEmpty() }
        if (token != null) {
            bearerTokenStored = token
            bearerContextStored = stored.optString("bearerContext")
                .takeIf { it.isNotEmpty() }
            bearerEmailStored = stored.optString("bearerEmail")
                .takeIf { it.isNotEmpty() }
        }
        desktopRefreshStored = stored.optString("desktopRefresh")
            .takeIf { it.isNotEmpty() }
        _desktopSignedIn.value = desktopRefreshStored != null
        apiIdStored = stored.optString("apiId").takeIf { it.isNotEmpty() }
        apiNameStored = stored.optString("apiName").takeIf { it.isNotEmpty() }
        apiHandleStored = stored.optString("apiHandle")
            .takeIf { it.isNotEmpty() }
        apiAvatarStored = stored.optString("apiAvatar")
            .takeIf { it.isNotEmpty() }
        if (apiIdStored != null) {
            // The signed-in identity snapshot survives restarts
            // even though the short-lived token itself does not.
            _state.value = State.SignedIn(apiNameStored, apiAvatarStored)
        }
    }

    /**
     * Satu Pintu — the one door (unified 2026-11-12): keep the
     * desktop refresh token and the identity the Data API named;
     * that identity IS the signed-in state.
     */
    fun signInDesktop(
        refreshToken: String?,
        name: String?,
        avatarUrl: String?,
    ) {
        if (refreshToken != null) desktopRefreshStored = refreshToken
        _desktopSignedIn.value = desktopRefreshStored != null
        if (name != null) {
            _state.value = State.SignedIn(name, avatarUrl)
        }
        val main = _state.value as? State.SignedIn
        persistAll(main?.name, main?.avatarUrl)
    }

    /** One-tap wipe: blob, engine session, WebView cookies, GMS grant. */
    fun signOut() {
        val token = bearerTokenStored ?: dataToken
        val email = bearerEmailStored
        lontarAuth.cookies = null
        lontarAuth.bearerToken = null
        lontarAuth.bearerContext = null
        dataToken = null
        cookieHeaderStored = ""
        bearerTokenStored = null
        bearerContextStored = null
        bearerEmailStored = null
        apiIdStored = null
        apiNameStored = null
        apiHandleStored = null
        apiAvatarStored = null
        desktopRefreshStored = null
        _desktopSignedIn.value = false
        _state.value = State.SignedOut
        prefs().edit().remove(KEY_BLOB).apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        // Best-effort: drop the cached token and revoke the grant, so
        // a future sign-in asks consent afresh. Local sign-out above
        // already stands even if Play services refuses to talk.
        if (token != null) {
            try {
                val client = Identity.getAuthorizationClient(context)
                client.clearToken(
                    ClearTokenRequest.builder().setToken(token).build(),
                )
                if (email != null) {
                    client.revokeAccess(
                        RevokeAccessRequest.builder()
                            .setAccount(Account(email, "com.google"))
                            .setScopes(listOf(Scope(YOUTUBE_READONLY_SCOPE)))
                            .build(),
                    )
                }
            } catch (e: Exception) {
                // The grant can also be revoked from the Google
                // account page; nothing here may fail the sign-out.
            }
        }
    }

    // ------------------------------------------------------------ storage

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persistAll(name: String?, avatarUrl: String?) {
        val plain = JSONObject()
            .put("cookie", cookieHeaderStored)
            .put("name", name ?: "")
            .put("avatar", avatarUrl ?: "")
            .put("bearerToken", bearerTokenStored ?: "")
            .put("bearerContext", bearerContextStored ?: "")
            .put("bearerEmail", bearerEmailStored ?: "")
            .put("apiId", apiIdStored ?: "")
            .put("apiName", apiNameStored ?: "")
            .put("apiHandle", apiHandleStored ?: "")
            .put("apiAvatar", apiAvatarStored ?: "")
            .put("desktopRefresh", desktopRefreshStored ?: "")
            .toString()
        val blob = encrypt(plain) ?: return
        prefs().edit().putString(KEY_BLOB, blob).apply()
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(combined, Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    private fun decrypt(blob: String): String? = try {
        val combined = Base64.decode(blob, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, GCM_IV_BYTES)
        val cipherText = combined.copyOfRange(GCM_IV_BYTES, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    companion object {
        /** The only scope Satu Ketuk asks for: read the account's YouTube. */
        const val YOUTUBE_READONLY_SCOPE =
            "https://www.googleapis.com/auth/youtube.readonly"

        const val PREFS_NAME = "bunny_auth_secure"
        const val KEY_BLOB = "auth_blob_v1"
        const val KEY_ALIAS = "bunny_auth_key_v1"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
