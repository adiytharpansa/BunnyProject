package com.bunny.engine.lontar

import org.json.JSONArray
import org.json.JSONException
import java.net.URLEncoder

/**
 * Search suggestions from Google's suggestqueries service — the same
 * source the official YouTube app ships in its own code (the
 * `https://suggestqueries.google.com` string sits in its DEX, feeding
 * its YouTubeSuggestionProvider; REA dissection 2026-11-10).
 *
 * The `firefox` client variant is used because it answers with a plain
 * JSON array — `["query", ["suggestion", …], …]` — instead of a JSONP
 * wrapper. Suggestions are decoration on the search box: every failure
 * mode here resolves to an empty list, never an error.
 */
object SearchSuggestions {

    fun urlFor(query: String): String =
        "https://suggestqueries.google.com/complete/search" +
            "?client=firefox&ds=yt&hl=id&q=" + URLEncoder.encode(query, "UTF-8")

    /** Parses the suggestqueries JSON array; total — garbage yields empty. */
    fun parse(responseText: String): List<String> {
        val root = try {
            JSONArray(responseText)
        } catch (e: JSONException) {
            return emptyList()
        }
        val suggestions = root.optJSONArray(1) ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until suggestions.length()) {
            suggestions.optString(i).takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out
    }
}
