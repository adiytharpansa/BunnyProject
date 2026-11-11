package com.bunny.engine.lontar

import java.security.MessageDigest

/**
 * Fase 5 login state for the metadata plane, owned by the app layer:
 * the app harvests the cookies from its sign-in WebView, stores them
 * encrypted, and hands them here; Lontar only ever reads them to sign
 * its WEB-persona requests. The player-persona chain stays anonymous
 * by design. Cookie values are NEVER logged anywhere in this module.
 */
class LontarAuth {

    @Volatile
    var cookies: AuthCookies? = null

    val isSignedIn: Boolean get() = cookies != null
}

/**
 * The cookies of one signed-in session. [cookieHeader] is the full
 * "name=value; name=value" string for the Cookie header; [sapisid] is
 * the one value the request signature is built from.
 */
data class AuthCookies(
    val cookieHeader: String,
    val sapisid: String,
) {
    companion object {

        /**
         * Builds the pair from a raw Cookie header harvested in the
         * browser. The signature value is SAPISID; Google also issues
         * it as __Secure-3PAPISID on some flows — either works.
         * Null when the header carries no signature cookie at all
         * (i.e. the browser was not actually signed in).
         */
        fun fromCookieHeader(header: String): AuthCookies? {
            if (header.isBlank()) return null
            val pairs = header.split(';')
                .map { it.trim() }
                .mapNotNull { part ->
                    val eq = part.indexOf('=')
                    if (eq <= 0) null
                    else part.substring(0, eq).trim() to part.substring(eq + 1).trim()
                }
                .toMap()
            val sapisid = pairs["SAPISID"] ?: pairs["__Secure-3PAPISID"]
                ?: return null
            if (!pairs.containsKey("SID") && !pairs.containsKey("__Secure-3PSID")) {
                return null
            }
            return AuthCookies(cookieHeader = header, sapisid = sapisid)
        }
    }
}

/**
 * The SAPISIDHASH request signature YouTube's own web client sends:
 * Authorization: SAPISIDHASH <unixSeconds>_<sha1("<unixSeconds>
 * <SAPISID> <origin>")>. Pure and deterministic for a given instant,
 * so it is unit-tested against a fixed vector.
 */
object SapisidHash {

    const val ORIGIN = "https://www.youtube.com"

    fun authorizationHeader(sapisid: String, unixSeconds: Long): String {
        val digest = MessageDigest.getInstance("SHA-1")
            .digest("$unixSeconds $sapisid $ORIGIN".toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SAPISIDHASH ${unixSeconds}_$hex"
    }
}
