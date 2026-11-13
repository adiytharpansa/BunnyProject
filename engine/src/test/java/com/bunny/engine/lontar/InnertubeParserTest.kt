package com.bunny.engine.lontar

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnertubeParserTest {

    private val feedJson = """
        {
          "contents": {
            "twoColumnBrowseResultsRenderer": {
              "tabs": [
                {
                  "tabRenderer": {
                    "content": {
                      "richGridRenderer": {
                        "contents": [
                          {
                            "richItemRenderer": {
                              "content": {
                                "videoRenderer": {
                                  "videoId": "aqz-KE-bpKQ",
                                  "title": {
                                    "runs": [
                                      {
                                        "text": "Big Buck Bunny 4K"
                                      }
                                    ]
                                  },
                                  "longBylineText": {
                                    "runs": [
                                      {
                                        "text": "Blender Foundation",
                                        "navigationEndpoint": {
                                          "browseEndpoint": {
                                            "browseId": "UCtC9L7g"
                                          }
                                        }
                                      }
                                    ]
                                  },
                                  "thumbnail": {
                                    "thumbnails": [
                                      {
                                        "url": "https://i.ytimg.com/small.jpg",
                                        "width": 120
                                      },
                                      {
                                        "url": "https://i.ytimg.com/big.jpg",
                                        "width": 480
                                      }
                                    ]
                                  },
                                  "channelThumbnailSupportedRenderers": {
                                    "channelThumbnailWithLinkRenderer": {
                                      "thumbnail": {
                                        "thumbnails": [
                                          {
                                            "url": "https://yt3.ggpht.com/avatar-small.jpg",
                                            "width": 40
                                          },
                                          {
                                            "url": "https://yt3.ggpht.com/avatar-big.jpg",
                                            "width": 176
                                          }
                                        ]
                                      }
                                    }
                                  },
                                  "lengthText": {
                                    "simpleText": "10:35"
                                  },
                                  "viewCountText": {
                                    "simpleText": "28,123,456 views"
                                  },
                                  "publishedTimeText": {
                                    "simpleText": "9 years ago"
                                  }
                                }
                              }
                            }
                          },
                          {
                            "richItemRenderer": {
                              "content": {
                                "videoRenderer": {
                                  "videoId": "live42",
                                  "title": {
                                    "simpleText": "Siaran Langsung Pagi"
                                  },
                                  "shortBylineText": {
                                    "runs": [
                                      {
                                        "text": "Bunny TV"
                                      }
                                    ]
                                  },
                                  "thumbnail": {
                                    "thumbnails": [
                                      {
                                        "url": "https://i.ytimg.com/live.jpg"
                                      }
                                    ]
                                  },
                                  "viewCountText": {
                                    "runs": [
                                      {
                                        "text": "1.3K watching"
                                      }
                                    ]
                                  },
                                  "badges": [
                                    {
                                      "metadataBadgeRenderer": {
                                        "label": "LIVE",
                                        "icon": {
                                          "iconType": "LIVE"
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            }
                          },
                          {
                            "continuationItemRenderer": {
                              "continuationEndpoint": {
                                "continuationCommand": {
                                  "token": "TOK_NEXT"
                                }
                              }
                            }
                          }
                        ]
                      }
                    }
                  }
                }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun parseFeedPage_collectsVideosWhereverNested() {
        val page = InnertubeParser.parseFeedPage(JSONObject(feedJson))
        assertEquals(2, page.items.size)
        assertEquals("TOK_NEXT", page.nextPage)

        val first = page.items[0]
        assertEquals("aqz-KE-bpKQ", first.id)
        assertEquals("Big Buck Bunny 4K", first.title)
        assertEquals("Blender Foundation", first.channelName)
        assertEquals("UCtC9L7g", first.channelId)
        assertEquals("https://i.ytimg.com/big.jpg", first.thumbnailUrl)
        assertEquals("https://yt3.ggpht.com/avatar-big.jpg", first.channelAvatarUrl)
        assertEquals(635L, first.durationSeconds)
        assertEquals(28_123_456L, first.viewCount)
        assertEquals("9 years ago", first.uploadedText)
        assertTrue(!first.isLive)
    }

    @Test
    fun parseFeedPage_detectsLiveWithoutDuration() {
        val page = InnertubeParser.parseFeedPage(JSONObject(feedJson))
        val live = page.items[1]
        assertTrue(live.isLive)
        assertNull(live.channelAvatarUrl)
        assertNull(live.durationSeconds)
        assertEquals(1_300L, live.viewCount)
        assertEquals("Bunny TV", live.channelName)
    }

    @Test
    fun parseChannelInfo_readsMetadataAndHeaderCounts() {
        // Shape verified live 2026-11-11 (Liputan6 browse): identity in
        // metadata.channelMetadataRenderer, published counts as localized
        // text.content inside the page header's metadataParts.
        val json = """
        {
          "metadata": {
            "channelMetadataRenderer": {
              "title": "Liputan6",
              "description": "Official account Liputan6.",
              "avatar": {
                "thumbnails": [
                  { "url": "https://yt3.example/avatar.jpg", "width": 176 }
                ]
              }
            }
          },
          "header": {
            "pageHeaderRenderer": {
              "content": {
                "pageHeaderViewModel": {
                  "metadata": {
                    "contentMetadataViewModel": {
                      "metadataRows": [
                        {
                          "metadataParts": [
                            { "text": { "content": "@liputan6_news" } }
                          ]
                        },
                        {
                          "metadataParts": [
                            { "text": { "content": "2,86 jt subscriber" } },
                            { "text": { "content": "158 rb video" } }
                          ]
                        }
                      ]
                    }
                  }
                }
              }
            }
          }
        }
        """.trimIndent()
        val info = InnertubeParser.parseChannelInfo(
            JSONObject(json),
            "UC1QpnGo11epfexw1bIiTHTg",
        )
        assertEquals("Liputan6", info?.name)
        assertEquals("https://yt3.example/avatar.jpg", info?.avatarUrl)
        assertEquals("Official account Liputan6.", info?.description)
        assertEquals("2,86 jt subscriber", info?.subscriberText)
        assertEquals("158 rb video", info?.videoCountText)
        assertNull(InnertubeParser.parseChannelInfo(JSONObject("{}"), "UCx"))
    }

    @Test
    fun comments_watchPageYieldsTokenAndOwnerAvatar() {
        // Shape verified live 2026-11-11: the comments token sits in the
        // "comment-item-section" of the watch page; the owner's photo in
        // videoSecondaryInfoRenderer.owner.videoOwnerRenderer.
        val json = """
        {
          "contents": {
            "twoColumnWatchNextResults": {
              "results": {
                "results": {
                  "contents": [
                    {
                      "itemSectionRenderer": {
                        "sectionIdentifier": "comment-item-section",
                        "contents": [
                          {
                            "continuationItemRenderer": {
                              "continuationEndpoint": {
                                "continuationCommand": {
                                  "token": "COMMENTS_TOKEN"
                                }
                              }
                            }
                          }
                        ]
                      }
                    },
                    {
                      "videoSecondaryInfoRenderer": {
                        "owner": {
                          "videoOwnerRenderer": {
                            "thumbnail": {
                              "thumbnails": [
                                { "url": "https://yt3.example/owner48.jpg" },
                                { "url": "https://yt3.example/owner176.jpg" }
                              ]
                            }
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
          }
        }
        """.trimIndent()
        val watch = JSONObject(json)
        assertEquals("COMMENTS_TOKEN", InnertubeParser.findCommentsToken(watch))
        assertEquals(
            "https://yt3.example/owner176.jpg",
            InnertubeParser.parseOwnerAvatarUrl(watch),
        )
    }

    @Test
    fun parseCommentsPage_joinsThreadsWithEntityPayloads() {
        // Shape verified live 2026-11-11: threads carry only keys; the
        // bodies live in commentEntityPayload mutations joined by key.
        // The response carries TWO reload containers — the first holds
        // only the comments header; reading just it was the device bug
        // "komentar tidak tersedia" (regression guard).
        val json = """
        {
          "onResponseReceivedEndpoints": [
            {
              "reloadContinuationItemsCommand": {
                "continuationItems": [
                  { "commentsHeaderRenderer": { "title": { "runs": [] } } }
                ]
              }
            },
            {
              "reloadContinuationItemsCommand": {
                "continuationItems": [
                  {
                    "commentThreadRenderer": {
                      "commentViewModel": {
                        "commentViewModel": {
                          "commentKey": "KEY1",
                          "commentId": "UgK1"
                        }
                      }
                    }
                  },
                  {
                    "continuationItemRenderer": {
                      "continuationEndpoint": {
                        "continuationCommand": { "token": "PAGE2" }
                      }
                    }
                  }
                ]
              }
            }
          ],
          "frameworkUpdates": {
            "entityBatchUpdate": {
              "mutations": [
                {
                  "payload": {
                    "commentEntityPayload": {
                      "key": "KEY1",
                      "properties": {
                        "commentId": "UgK1",
                        "content": { "content": "Komentar pertama" },
                        "publishedTime": "6 bulan yang lalu"
                      },
                      "author": {
                        "displayName": "@penonton",
                        "avatarThumbnailUrl": "https://yt3.example/a88.jpg",
                        "channelId": "UCpenonton"
                      },
                      "toolbar": {
                        "likeCountNotliked": "12",
                        "replyCount": "2"
                      }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
        val page = InnertubeParser.parseCommentsPage(JSONObject(json))
        assertEquals(1, page.items.size)
        val c = page.items[0]
        assertEquals("UgK1", c.id)
        assertEquals("@penonton", c.authorName)
        assertEquals("https://yt3.example/a88.jpg", c.authorAvatarUrl)
        assertEquals("UCpenonton", c.authorChannelId)
        assertEquals("6 bulan yang lalu", c.publishedText)
        assertEquals("Komentar pertama", c.text)
        assertEquals("12", c.likeCountText)
        assertEquals(2, c.replyCount)
        assertEquals("PAGE2", page.nextPage)
    }

    @Test
    fun parseCommentsPage_threadCarriesRepliesContinuation() {
        // Shape verified live 2026-11-11: the thread's replies door is
        // a continuation token inside commentRepliesRenderer.
        val json = """
        {
          "onResponseReceivedEndpoints": [
            {
              "reloadContinuationItemsCommand": {
                "continuationItems": [
                  {
                    "commentThreadRenderer": {
                      "commentViewModel": {
                        "commentViewModel": {
                          "commentKey": "KEY1",
                          "commentId": "UgK1"
                        }
                      },
                      "replies": {
                        "commentRepliesRenderer": {
                          "contents": [
                            {
                              "continuationItemRenderer": {
                                "continuationEndpoint": {
                                  "continuationCommand": { "token": "REPLIES1" }
                                }
                              }
                            }
                          ]
                        }
                      }
                    }
                  }
                ]
              }
            }
          ],
          "frameworkUpdates": {
            "entityBatchUpdate": {
              "mutations": [
                {
                  "payload": {
                    "commentEntityPayload": {
                      "key": "KEY1",
                      "properties": {
                        "commentId": "UgK1",
                        "content": { "content": "Komentar induk" },
                        "publishedTime": "1 hari yang lalu"
                      },
                      "author": { "displayName": "@induk" },
                      "toolbar": { "replyCount": "3" }
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
        val page = InnertubeParser.parseCommentsPage(JSONObject(json))
        assertEquals(1, page.items.size)
        assertEquals("REPLIES1", page.items[0].repliesContinuation)
        assertEquals(3, page.items[0].replyCount)
    }

    @Test
    fun parseRepliesPage_readsBareViewModelsAndNextToken() {
        // Shape verified live 2026-11-11: replies arrive as BARE
        // commentViewModel items in one append container (no thread
        // wrapper), joined to payloads by key, with a trailing
        // continuation item for the next replies page.
        val json = """
        {
          "onResponseReceivedEndpoints": [
            {
              "appendContinuationItemsAction": {
                "continuationItems": [
                  { "commentViewModel": { "commentKey": "RKEY1", "commentId": "UgR1" } },
                  { "commentViewModel": { "commentKey": "RKEY2", "commentId": "UgR2" } },
                  {
                    "continuationItemRenderer": {
                      "continuationEndpoint": {
                        "continuationCommand": { "token": "REPLIES_PAGE2" }
                      }
                    }
                  }
                ]
              }
            }
          ],
          "frameworkUpdates": {
            "entityBatchUpdate": {
              "mutations": [
                {
                  "payload": {
                    "commentEntityPayload": {
                      "key": "RKEY1",
                      "properties": {
                        "commentId": "UgR1",
                        "content": { "content": "Balasan pertama" },
                        "publishedTime": "2 jam yang lalu"
                      },
                      "author": {
                        "displayName": "@pembalas",
                        "avatarThumbnailUrl": "https://yt3.example/b88.jpg"
                      },
                      "toolbar": { "likeCountNotliked": "5" }
                    }
                  }
                },
                {
                  "payload": {
                    "commentEntityPayload": {
                      "key": "RKEY2",
                      "properties": {
                        "commentId": "UgR2",
                        "content": { "content": "Balasan kedua" },
                        "publishedTime": "1 jam yang lalu"
                      },
                      "author": { "displayName": "@lain" },
                      "toolbar": {}
                    }
                  }
                }
              ]
            }
          }
        }
        """.trimIndent()
        val page = InnertubeParser.parseRepliesPage(JSONObject(json))
        assertEquals(2, page.items.size)
        assertEquals("Balasan pertama", page.items[0].text)
        assertEquals("@pembalas", page.items[0].authorName)
        assertEquals("https://yt3.example/b88.jpg", page.items[0].authorAvatarUrl)
        assertEquals("5", page.items[0].likeCountText)
        assertEquals("Balasan kedua", page.items[1].text)
        assertEquals("REPLIES_PAGE2", page.nextPage)
    }

    @Test
    fun parseDownloadFormats_readsAdaptiveAndProgressiveSkipsUrlLess() {
        val json = """
        {
          "streamingData": {
            "adaptiveFormats": [
              { "itag": 140, "url": "https://rr.example/audio",
                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                "bitrate": 128000, "contentLength": "900000" },
              { "itag": 136, "url": "https://rr.example/v720",
                "mimeType": "video/mp4; codecs=\"avc1\"",
                "height": 720, "bitrate": 2000000, "contentLength": "20000000" },
              { "itag": 137,
                "mimeType": "video/mp4; codecs=\"avc1\"",
                "height": 1080, "bitrate": 4000000 }
            ],
            "formats": [
              { "itag": 18, "url": "https://rr.example/prog",
                "mimeType": "video/mp4; codecs=\"avc1, mp4a\"",
                "height": 360, "bitrate": 600000 }
            ]
          }
        }
        """.trimIndent()
        val formats = InnertubeParser.parseDownloadFormats(JSONObject(json))
        // The URL-less adaptive entry (cipher-gated) is skipped.
        assertEquals(3, formats.size)
        val audio = formats.first { it.isAudioOnly }
        assertEquals(140, audio.itag)
        assertEquals("audio/mp4", audio.mimeType)
        assertEquals(900_000L, audio.contentLength)
        val v720 = formats.first { it.height == 720 }
        assertEquals(false, v720.isProgressive)
        val prog = formats.first { it.itag == 18 }
        assertEquals(true, prog.isProgressive)
    }

    @Test
    fun parseCount_handlesSuffixesAndLocales() {
        assertEquals(1_200_000L, InnertubeParser.parseCount("1.2M views"))
        assertEquals(321_000L, InnertubeParser.parseCount("321K watching"))
        assertEquals(1_200L, InnertubeParser.parseCount("1,2 rb x ditonton"))
        assertEquals(3_500_000L, InnertubeParser.parseCount("3,5 jt x ditonton"))
        assertEquals(131_200L, InnertubeParser.parseCount("131,2 rb x ditonton"))
        assertEquals(950L, InnertubeParser.parseCount("950 views"))
        assertEquals(0L, InnertubeParser.parseCount("No views"))
        assertNull(InnertubeParser.parseCount(""))
    }

    @Test
    fun parseDuration_handlesHms() {
        assertEquals(754L, InnertubeParser.parseDuration("12:34"))
        assertEquals(3_723L, InnertubeParser.parseDuration("1:02:03"))
        assertNull(InnertubeParser.parseDuration("abc"))
    }

    private val playerJson = """
        {"playabilityStatus":{"status":"OK"},
         "videoDetails":{"videoId":"aqz-KE-bpKQ","title":"Big Buck Bunny",
           "shortDescription":"Kelinci besar.","author":"Blender Foundation",
           "channelId":"UCtC9L7g","viewCount":"28123456","lengthSeconds":"635",
           "thumbnail":{"thumbnails":[
             {"url":"https://img.example/small.jpg","width":120,"height":90},
             {"url":"https://img.example/large.jpg","width":1280,"height":720}]}},
         "microformat":{"playerMicroformatRenderer":{"publishDate":"2015-01-01"}},
         "streamingData":{"expiresInSeconds":"21540",
           "formats":[
             {"itag":18,"url":"https://rr.example/low","bitrate":500000},
             {"itag":22,"url":"https://rr.example/high","bitrate":2000000}],
           "adaptiveFormats":[
             {"itag":137,"signatureCipher":"s=abc&sp=sig&url=https://rr.example/v"}],
           "hlsManifestUrl":"https://manifest.example/hls.m3u8"}}
    """.trimIndent()

    @Test
    fun parseVideoDetails_mapsPlayerFields() {
        val details = InnertubeParser.parseVideoDetails(JSONObject(playerJson))
        assertEquals("aqz-KE-bpKQ", details.id)
        assertEquals("Blender Foundation", details.channelName)
        assertEquals(28_123_456L, details.viewCount)
        assertEquals("2015-01-01", details.uploadedText)
        assertEquals("https://img.example/large.jpg", details.thumbnailUrl)
    }

    @Test
    fun parsePlaybackStreams_picksBestDirectProgressive_andHls() {
        val streams = InnertubeParser.parsePlaybackStreams(JSONObject(playerJson), nowEpochMs = 1_000)
        assertEquals("https://rr.example/high", streams.progressiveUrl)
        assertEquals("https://manifest.example/hls.m3u8", streams.hlsUrl)
        assertEquals(1_000L + 21_540_000L, streams.expiresAtEpochMs)
    }

    @Test
    fun parsePlaybackStreams_vrResponse_buildsDashFromAdaptiveFormats() {
        // Real ANDROID_VR player response (trimmed): direct URLs everywhere,
        // no server DASH/HLS — the manifest must be generated by Lontar.
        val text = javaClass.classLoader!!.getResourceAsStream("vr_player_response.json")!!
            .bufferedReader().readText()
        val streams = InnertubeParser.parsePlaybackStreams(JSONObject(text), nowEpochMs = 1_000)
        assertTrue(streams.progressiveUrl!!.contains("itag=18"))
        assertEquals(1_000L + 21_540_000L, streams.expiresAtEpochMs)
        val dash = streams.dashManifestContent
        assertNotNull(dash)
        dash!!
        assertTrue(dash.contains("mediaPresentationDuration=\"PT213S\""))
        for (itag in listOf("137", "248", "136", "140", "251")) {
            assertTrue("representation $itag", dash.contains("<Representation id=\"$itag\""))
        }
    }

    @Test
    fun parsePlaybackStreams_unplayable_throws() {
        val blocked = JSONObject(
            """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}}""",
        )
        try {
            InnertubeParser.parsePlaybackStreams(blocked, nowEpochMs = 0)
            throw AssertionError("expected LontarException")
        } catch (e: LontarException) {
            assertTrue(e.message!!.contains("Sign in"))
        }
    }

    @Test
    fun parseFloorStreams_picksHighestBitrateProgressive_withDeclaredSize() {
        val player = JSONObject(
            """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"isLive":false},
             "streamingData":{"expiresInSeconds":"21540",
               "formats":[
                 {"itag":18,"url":"https://rr.example/itag18","bitrate":512000,"contentLength":"36700000"},
                 {"itag":59,"url":"https://rr.example/itag59","bitrate":900000,"contentLength":"64000000"}],
               "adaptiveFormats":[{"itag":137,"url":"https://rr.example/itag137","bitrate":4000000}]}}
            """.trimIndent(),
        )
        val floor = InnertubeParser.parseFloorStreams(player, nowEpochMs = 1_000)
        assertNotNull(floor)
        floor!!
        // The adaptive giant is not the floor: only the muxed file is.
        assertEquals("https://rr.example/itag59", floor.progressiveUrl)
        assertEquals(64_000_000L, floor.estimatedSizeBytes)
        assertEquals(null, floor.dashManifestContent)
        assertEquals(null, floor.hlsUrl)
        assertEquals(1_000L + 21_540_000L, floor.expiresAtEpochMs)
    }

    @Test
    fun parseFloorStreams_refusedLiveOrFileless_yieldsNull() {
        val refused = JSONObject(
            """{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"},
                "streamingData":{"formats":[{"itag":18,"url":"https://rr.example/x","bitrate":1}]}}""",
        )
        assertEquals(null, InnertubeParser.parseFloorStreams(refused, nowEpochMs = 0))
        val live = JSONObject(
            """{"playabilityStatus":{"status":"OK"},"videoDetails":{"isLive":true},
                "streamingData":{"formats":[{"itag":18,"url":"https://rr.example/x","bitrate":1}]}}""",
        )
        assertEquals(null, InnertubeParser.parseFloorStreams(live, nowEpochMs = 0))
        val adaptiveOnly = JSONObject(
            """{"playabilityStatus":{"status":"OK"},
                "streamingData":{"adaptiveFormats":[{"itag":137,"url":"https://rr.example/v","bitrate":9}]}}""",
        )
        assertEquals(null, InnertubeParser.parseFloorStreams(adaptiveOnly, nowEpochMs = 0))
    }

    @Test
    fun session_fromHomepageHtml_extractsMarkers() {
        val html = """<script>ytcfg.set({"INNERTUBE_API_KEY":"AIzaTEST",
            "INNERTUBE_CONTEXT":{"client":{"visitorData":"CgtTEST123"}}});</script>"""
        val session = InnertubeSession.fromHomepageHtml(html)
        assertEquals("AIzaTEST", session?.apiKey)
        assertEquals("CgtTEST123", session?.visitorData)
        assertNull(InnertubeSession.fromHomepageHtml("<html>nothing</html>"))
    }

    @Test
    fun parseFeedPage_playlistVideoRenderer_uploadsRow() {
        val json = JSONObject(
            """
            {"contents":[{"playlistVideoRenderer":{
              "videoId":"dQw4w9WgXcQ",
              "title":{"runs":[{"text":"Video Terbaru Kanal"}]},
              "shortBylineText":{"runs":[{"text":"Kanal Uji","navigationEndpoint":{"browseEndpoint":{"browseId":"UCuji123"}}}]},
              "thumbnail":{"thumbnails":[{"url":"https://i.ytimg.com/t.jpg","width":480}]},
              "lengthText":{"simpleText":"12:05"},
              "videoInfo":{"runs":[{"text":"1,2 rb x ditonton"},{"text":" • "},{"text":"3 hari yang lalu"}]}
            }}]}
            """.trimIndent(),
        )
        val page = InnertubeParser.parseFeedPage(json)
        assertEquals(1, page.items.size)
        val item = page.items.single()
        assertEquals("dQw4w9WgXcQ", item.id)
        assertEquals("Video Terbaru Kanal", item.title)
        assertEquals("UCuji123", item.channelId)
        assertEquals("Kanal Uji", item.channelName)
        assertEquals(725L, item.durationSeconds)
        assertEquals(1200L, item.viewCount)
        assertEquals("3 hari yang lalu", item.uploadedText)
    }

    @Test
    fun parsePlaybackStreams_estimatesSizeFromAdaptiveFormats() {
        val json = JSONObject(
            """
            {"playabilityStatus":{"status":"OK"},
             "videoDetails":{"videoId":"x","lengthSeconds":"600"},
             "streamingData":{
               "expiresInSeconds":"21540",
               "adaptiveFormats":[
                 {"mimeType":"video/mp4; codecs=\"avc1\"","bitrate":2500000,"height":720,"url":"https://v720","initRange":{"start":"0","end":"740"},"indexRange":{"start":"741","end":"3000"}},
                 {"mimeType":"video/mp4; codecs=\"avc1\"","bitrate":800000,"height":480,"url":"https://v480","initRange":{"start":"0","end":"740"},"indexRange":{"start":"741","end":"3000"}},
                 {"mimeType":"video/mp4; codecs=\"avc1\"","bitrate":300000,"height":240,"url":"https://v240","initRange":{"start":"0","end":"740"},"indexRange":{"start":"741","end":"3000"}},
                 {"mimeType":"audio/mp4; codecs=\"mp4a\"","bitrate":128000,"url":"https://a128","initRange":{"start":"0","end":"740"},"indexRange":{"start":"741","end":"3000"}},
                 {"mimeType":"audio/mp4; codecs=\"mp4a\"","bitrate":48000,"url":"https://a48","initRange":{"start":"0","end":"740"},"indexRange":{"start":"741","end":"3000"}}
               ]}}
            """.trimIndent(),
        )
        val streams = InnertubeParser.parsePlaybackStreams(json, nowEpochMs = 0)
        // 480p video (800 kbps) + richest audio (128 kbps), 600 s -> bytes.
        assertEquals((800_000L + 128_000L) * 600L / 8L, streams.estimatedSizeBytes)
        assertNotNull(streams.dashManifestContent)
    }

    @Test
    fun parseRelatedVideos_lockupsFromWatchNext_keepServerOrder() {
        // Fixture mirrors the real anonymous WEB `next` response
        // (probed 2026-11-11): related rows are lockupViewModel objects
        // in secondaryResults; dot durations; the full counts live in
        // the metadata parts' accessibility labels.
        val json = JSONObject(
            """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "secondaryResults": {
                    "secondaryResults": {
                      "results": [
                        {
                          "lockupViewModel": {
                            "contentId": "PDE0HcAjATk",
                            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": {
                                  "content": "Top Hits Spotify Indonesia 2026"
                                },
                                "image": {
                                  "decoratedAvatarViewModel": {
                                    "avatar": {
                                      "avatarViewModel": {
                                        "image": {
                                          "sources": [
                                            {
                                              "url": "https://yt3.ggpht.com/avatar.jpg",
                                              "width": 68
                                            }
                                          ]
                                        }
                                      }
                                    }
                                  }
                                },
                                "rendererContext": {
                                  "commandContext": {
                                    "onTap": {
                                      "innertubeCommand": {
                                        "browseEndpoint": {
                                          "browseId": "UC51FepQ6WpkqdMaIXUthXTg"
                                        }
                                      }
                                    }
                                  }
                                },
                                "metadata": {
                                  "contentMetadataViewModel": {
                                    "metadataRows": [
                                      {
                                        "metadataParts": [
                                          {
                                            "text": {
                                              "content": "Ruang Nada Senja"
                                            }
                                          }
                                        ]
                                      },
                                      {
                                        "metadataParts": [
                                          {
                                            "text": {
                                              "content": "702"
                                            },
                                            "accessibilityLabel": "702 x ditonton"
                                          },
                                          {
                                            "text": {
                                              "content": "6 h lalu"
                                            },
                                            "accessibilityLabel": "6 hari yang lalu"
                                          }
                                        ]
                                      }
                                    ],
                                    "delimiter": " "
                                  }
                                }
                              }
                            },
                            "contentImage": {
                              "thumbnailViewModel": {
                                "image": {
                                  "sources": [
                                    {
                                      "url": "https://i.ytimg.com/vi/PDE0HcAjATk/hqdefault-168.jpg",
                                      "width": 168
                                    },
                                    {
                                      "url": "https://i.ytimg.com/vi/PDE0HcAjATk/hqdefault-336.jpg",
                                      "width": 336
                                    }
                                  ]
                                },
                                "overlays": [
                                  {
                                    "thumbnailBottomOverlayViewModel": {
                                      "badges": [
                                        {
                                          "thumbnailBadgeViewModel": {
                                            "text": "1.23.21"
                                          }
                                        }
                                      ]
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        },
                        {
                          "lockupViewModel": {
                            "contentId": "PLS3mZpWbSjLIiZmCOzhcaCkXkz923KIMA",
                            "contentType": "LOCKUP_CONTENT_TYPE_PLAYLIST",
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": {
                                  "content": "Mix - Lagu Santai"
                                }
                              }
                            }
                          }
                        },
                        {
                          "lockupViewModel": {
                            "contentId": "dQw4w9WgXcQ",
                            "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                            "metadata": {
                              "lockupMetadataViewModel": {
                                "title": {
                                  "content": "Video Kedua"
                                },
                                "metadata": {
                                  "contentMetadataViewModel": {
                                    "metadataRows": [
                                      {
                                        "metadataParts": [
                                          {
                                            "text": {
                                              "content": "Kanal Lain"
                                            }
                                          }
                                        ]
                                      },
                                      {
                                        "metadataParts": [
                                          {
                                            "text": {
                                              "content": "1,2 rb"
                                            },
                                            "accessibilityLabel": "1,2 rb x ditonton"
                                          },
                                          {
                                            "text": {
                                              "content": "2 m lalu"
                                            },
                                            "accessibilityLabel": "2 minggu yang lalu"
                                          }
                                        ]
                                      }
                                    ],
                                    "delimiter": " "
                                  }
                                }
                              }
                            },
                            "contentImage": {
                              "thumbnailViewModel": {
                                "image": {
                                  "sources": [
                                    {
                                      "url": "https://i.ytimg.com/vi/dQw4w9WgXcQ/hq.jpg",
                                      "width": 336
                                    }
                                  ]
                                },
                                "overlays": [
                                  {
                                    "thumbnailBottomOverlayViewModel": {
                                      "badges": [
                                        {
                                          "thumbnailBadgeViewModel": {
                                            "text": "12.34"
                                          }
                                        }
                                      ]
                                    }
                                  }
                                ]
                              }
                            }
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent(),
        )
        val items = InnertubeParser.parseRelatedVideos(json)
        // The playlist lockup is skipped; the server's order survives.
        assertEquals(listOf("PDE0HcAjATk", "dQw4w9WgXcQ"), items.map { it.id })
        val first = items[0]
        assertEquals("Top Hits Spotify Indonesia 2026", first.title)
        assertEquals("UC51FepQ6WpkqdMaIXUthXTg", first.channelId)
        assertEquals("Ruang Nada Senja", first.channelName)
        assertEquals("https://yt3.ggpht.com/avatar.jpg", first.channelAvatarUrl)
        assertEquals(
            "https://i.ytimg.com/vi/PDE0HcAjATk/hqdefault-336.jpg",
            first.thumbnailUrl,
        )
        assertEquals(5001L, first.durationSeconds)
        assertEquals(702L, first.viewCount)
        assertEquals("6 hari yang lalu", first.uploadedText)
        assertEquals(false, first.isLive)
        val second = items[1]
        assertEquals(754L, second.durationSeconds)
        assertEquals(1200L, second.viewCount)
        assertEquals("2 minggu yang lalu", second.uploadedText)
        assertNull(second.channelId)
    }

    @Test
    fun parseCommentsPage_emptyContinuation_yieldsEmptyPage() {
        // Fase 7-A "kosong" shape: the continuation answers with the
        // section's containers but no threads and no entity payloads
        // (comments disabled, or a section that renders nothing).
        // The parser must read this as an empty page — the engine's
        // job is to log it as evidence, not the parser's to invent.
        val json = """
        {
          "onResponseReceivedEndpoints": [
            {
              "reloadContinuationItemsCommand": {
                "continuationItems": [
                  { "commentsHeaderRenderer": { "title": { "runs": [] } } }
                ]
              }
            },
            {
              "reloadContinuationItemsCommand": {
                "continuationItems": []
              }
            }
          ],
          "frameworkUpdates": {
            "entityBatchUpdate": { "mutations": [] }
          }
        }
        """.trimIndent()
        val page = InnertubeParser.parseCommentsPage(JSONObject(json))
        assertTrue(page.items.isEmpty())
        assertNull(page.nextPage)
    }

    @Test
    fun parseRelatedVideos_missingSecondaryResults_yieldsEmpty() {
        // Fase 7-A "kosong" shape: a watch response without the
        // secondary results column at all parses to an empty list,
        // never to an exception the caller would mistake for data.
        val json = JSONObject(
            """
            {
              "contents": {
                "twoColumnWatchNextResults": {
                  "results": { "results": { "contents": [] } }
                }
              }
            }
            """.trimIndent(),
        )
        assertTrue(InnertubeParser.parseRelatedVideos(json).isEmpty())
    }

    @Test
    fun parseChannelInfo_missingHeader_yieldsNull() {
        // Fase 7-A "urai" shape: a channel browse whose page header
        // carries no channel metadata reads as null, so the engine
        // can log the missing header instead of showing a blank one.
        val json = JSONObject(
            """
            { "responseContext": {}, "contents": {} }
            """.trimIndent(),
        )
        assertNull(InnertubeParser.parseChannelInfo(json, "UCxxxxxxxxxxxxxxxxxxxxxx"))
    }
}
