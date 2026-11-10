package com.bunny.engine.lontar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchSuggestionsTest {

    @Test
    fun parse_firefoxArray_extractsSuggestions() {
        val parsed = SearchSuggestions.parse(
            """["lagu indon",["lagu indonesia terbaru","lagu indonesia viral","lagu indo galau"],[],{"k":1}]""",
        )
        assertEquals(
            listOf("lagu indonesia terbaru", "lagu indonesia viral", "lagu indo galau"),
            parsed,
        )
    }

    @Test
    fun parse_garbageOrEmpty_yieldsEmpty() {
        assertTrue(SearchSuggestions.parse("bukan json").isEmpty())
        assertTrue(SearchSuggestions.parse("").isEmpty())
        assertTrue(SearchSuggestions.parse("""["q",[]]""").isEmpty())
    }

    @Test
    fun urlFor_encodesQuery() {
        val url = SearchSuggestions.urlFor("berita hari ini")
        assertTrue(url.startsWith("https://suggestqueries.google.com/complete/search"))
        assertTrue(url.contains("ds=yt"))
        assertTrue(url.contains("q=berita+hari+ini"))
    }
}
