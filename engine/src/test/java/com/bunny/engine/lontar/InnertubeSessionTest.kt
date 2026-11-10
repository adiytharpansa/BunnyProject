package com.bunny.engine.lontar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InnertubeSessionTest {

    private val html = """
        <html><script>var ytcfg = {"INNERTUBE_API_KEY":"AIzaTESTKEY",
        "INNERTUBE_CLIENT_VERSION":"2.20241126.01.00",
        "visitorData":"CgtTESTVISITOR"};</script></html>
    """.trimIndent()

    @Test
    fun fromHomepageHtml_extractsKeyVersionAndVisitorData() {
        val session = InnertubeSession.fromHomepageHtml(html)
        assertEquals("AIzaTESTKEY", session?.apiKey)
        assertEquals("2.20241126.01.00", session?.clientVersion)
        assertEquals("CgtTESTVISITOR", session?.visitorData)
    }

    @Test
    fun fromHomepageHtml_missingMarkers_returnsNull() {
        assertNull(InnertubeSession.fromHomepageHtml("<html>no config here</html>"))
    }
}
