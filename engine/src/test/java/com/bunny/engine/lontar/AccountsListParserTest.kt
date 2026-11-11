package com.bunny.engine.lontar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccountsListParserTest {

    // Shape distilled from a live-verified accounts_list reply:
    // section list -> item section -> accountItem with simpleText
    // identity fields and the widest photo winning.
    private val fixture = """
        {"contents":[{"accountSectionListRenderer":{"contents":[
          {"accountItemSectionRenderer":{"contents":[
            {"accountItem":{
              "accountName":{"simpleText":"Budi Santoso"},
              "channelHandle":{"simpleText":"@budi"},
              "accountByline":{"simpleText":"budi@example.com"},
              "accountPhoto":{"thumbnails":[
                {"url":"https://img.example/s88.jpg","width":88},
                {"url":"https://img.example/s216.jpg","width":216}
              ]},
              "isSelected":true
            }},
            {"accountItem":{
              "accountName":{"simpleText":"Akun Kedua"},
              "isSelected":false
            }}
          ]}}
        ]}}]}
    """.trimIndent()

    @Test
    fun parse_selectedAccountWins_withWidestPhoto() {
        val account = InnertubeParser.parseAccountItem(JSONObject(fixture))
        assertEquals("Budi Santoso", account?.name)
        assertEquals("@budi", account?.handle)
        assertEquals("budi@example.com", account?.email)
        assertEquals("https://img.example/s216.jpg", account?.avatarUrl)
    }

    @Test
    fun parse_noAccountItem_returnsNull() {
        val account = InnertubeParser.parseAccountItem(
            JSONObject("""{"contents":[{"sectionListRenderer":{}}]}"""),
        )
        assertNull(account)
    }
}
