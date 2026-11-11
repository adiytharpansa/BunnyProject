package com.bunny.engine.lontar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LontarAuthTest {

    @Test
    fun sapisidHash_matchesKnownVector() {
        // sha1("1700000000 abc https://www.youtube.com"), computed
        // independently of this implementation.
        assertEquals(
            "SAPISIDHASH 1700000000_27b236f59d4ec583d7530f2c7055d2f9c6aecf92",
            SapisidHash.authorizationHeader("abc", 1_700_000_000L),
        )
    }

    @Test
    fun authCookies_fromHeader_extractsSapisid() {
        val cookies = AuthCookies.fromCookieHeader(
            "SID=aaa; HSID=bbb; SAPISID=ccc; SSID=ddd",
        )
        assertEquals("ccc", cookies?.sapisid)
    }

    @Test
    fun authCookies_fromHeader_acceptsSecureVariants() {
        val cookies = AuthCookies.fromCookieHeader(
            "__Secure-3PSID=aaa; __Secure-3PAPISID=zzz",
        )
        assertEquals("zzz", cookies?.sapisid)
    }

    @Test
    fun authCookies_fromHeader_rejectsSignedOutHeader() {
        // No SAPISID at all, or a SAPISID with no session id beside it:
        // the browser was not signed in, so there is nothing to store.
        assertNull(AuthCookies.fromCookieHeader("PREF=tz=Asia.Jakarta; VISITOR_INFO1_LIVE=x"))
        assertNull(AuthCookies.fromCookieHeader("SAPISID=ccc"))
        assertNull(AuthCookies.fromCookieHeader(""))
    }
}
