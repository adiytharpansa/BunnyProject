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

    init {
        restore()
    }

    private fun restore() {
        val blob = prefs().getString(KEY_BLOB, null) ?: return
        val plain = decrypt(blob) ?: return
        val stored = runCatching { JSONObject(plain) }.getOrNull() ?: return
        cookieHeaderStored = stored.optString("cookie")
        val cookies = AuthCookies.fromCookieHeader(cookieHeaderStored)
        if (cookies != null) lontarAuth.cookies = cookies
        val token = stored.optString("bearerToken").takeIf { it.isNotEmpty() }
        if (token != null) {
            bearerTokenStored = token
            bearerContextStored = stored.optString("bearerContext")
                .takeIf { it.isNotEmpty() }
            bearerEmailStored = stored.optString("bearerEmail")
                .takeIf { it.isNotEmpty() }
            lontarAuth.bearerToken = token
            lontarAuth.bearerContext = bearerContextStored
        }
        if (cookies != null || token != null) {
            _state.value = State.SignedIn(
                name = stored.optString("name").takeIf { it.isNotEmpty() },
                avatarUrl = stored.optString("avatar").takeIf { it.isNotEmpty() },
            )
        }
    }

    /**
     * Takes the cookie header harvested from the sign-in WebView.
     * False means the header was not actually a signed-in session —
     * nothing is stored and the engine stays anonymous.
     */
    suspend fun signIn(cookieHeader: String): Boolean {
        val cookies = AuthCookies.fromCookieHeader(cookieHeader) ?: return false
        lontarAuth.cookies = cookies
        cookieHeaderStored = cookieHeader
        _state.value = State.SignedIn(null, null)
        persistAll(null, null)
        // Best-effort identity for the profile header; a failure just
        // leaves the header generic, the session itself is valid.
        val info = try {
            backend.getAccountInfo()
        } catch (e: Exception) {
            null
        }
        if (info != null) {
            _state.value = State.SignedIn(info.name, info.avatarUrl)
            persistAll(info.name, info.avatarUrl)
        }
        return true
    }

    /**
     * Satu Ketuk: the probe accepted [verdict]'s context — keep the
     * token, the winning context, and the identity the door named.
     */
    fun signInBearer(verdict: BearerContextVerdict, accessToken: String) {
        lontarAuth.bearerToken = accessToken
        lontarAuth.bearerContext = verdict.contextName
        bearerTokenStored = accessToken
        bearerContextStored = verdict.contextName
        bearerEmailStored = verdict.accountEmail
        _state.value = State.SignedIn(verdict.accountName, verdict.avatarUrl)
        persistAll(verdict.accountName, verdict.avatarUrl)
    }

    /** One-tap wipe: blob, engine session, WebView cookies, GMS grant. */
    fun signOut() {
        val token = bearerTokenStored
        val email = bearerEmailStored
        lontarAuth.cookies = null
        lontarAuth.bearerToken = null
        lontarAuth.bearerContext = null
        cookieHeaderStored = ""
        bearerTokenStored = null
        bearerContextStored = null
        bearerEmailStored = null
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
