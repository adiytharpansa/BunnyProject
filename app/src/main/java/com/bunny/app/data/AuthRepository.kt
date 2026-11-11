package com.bunny.app.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import com.bunny.core.backend.ExtractionBackend
import com.bunny.engine.lontar.AuthCookies
import com.bunny.engine.lontar.LontarAuth
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

    init {
        restore()
    }

    private fun restore() {
        val blob = prefs().getString(KEY_BLOB, null) ?: return
        val plain = decrypt(blob) ?: return
        val stored = runCatching { JSONObject(plain) }.getOrNull() ?: return
        val header = stored.optString("cookie")
        val cookies = AuthCookies.fromCookieHeader(header) ?: return
        lontarAuth.cookies = cookies
        _state.value = State.SignedIn(
            name = stored.optString("name").takeIf { it.isNotEmpty() },
            avatarUrl = stored.optString("avatar").takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Takes the cookie header harvested from the sign-in WebView.
     * False means the header was not actually a signed-in session —
     * nothing is stored and the engine stays anonymous.
     */
    suspend fun signIn(cookieHeader: String): Boolean {
        val cookies = AuthCookies.fromCookieHeader(cookieHeader) ?: return false
        lontarAuth.cookies = cookies
        _state.value = State.SignedIn(null, null)
        persist(cookieHeader, null, null)
        // Best-effort identity for the profile header; a failure just
        // leaves the header generic, the session itself is valid.
        val info = try {
            backend.getAccountInfo()
        } catch (e: Exception) {
            null
        }
        if (info != null) {
            _state.value = State.SignedIn(info.name, info.avatarUrl)
            persist(cookieHeader, info.name, info.avatarUrl)
        }
        return true
    }

    /** One-tap wipe: blob, engine session, and WebView cookies. */
    fun signOut() {
        lontarAuth.cookies = null
        _state.value = State.SignedOut
        prefs().edit().remove(KEY_BLOB).apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    // ------------------------------------------------------------ storage

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun persist(cookieHeader: String, name: String?, avatarUrl: String?) {
        val plain = JSONObject()
            .put("cookie", cookieHeader)
            .put("name", name ?: "")
            .put("avatar", avatarUrl ?: "")
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

    private companion object {
        const val PREFS_NAME = "bunny_auth_secure"
        const val KEY_BLOB = "auth_blob_v1"
        const val KEY_ALIAS = "bunny_auth_key_v1"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
