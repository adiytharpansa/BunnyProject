package com.bunny.app.playback

import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wall signature and the one-shot recovery gate (Arus Fase A3),
 * pinned against the device evidence of 2026-11-11: a music video
 * cut with an HTTP refusal ~60 s into playback, every classic source
 * from the same response rejected within seconds.
 */
@UnstableApi
class PlaybackWallPolicyTest {

    private val httpRefusal = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
    private val networkLoss = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

    @Test
    fun `wall is an http refusal after playback started`() {
        assertTrue(PlaybackWallPolicy.isWallSignature(false, httpRefusal, 61_000L))
    }

    @Test
    fun `load time refusal at position zero is not the wall`() {
        assertFalse(PlaybackWallPolicy.isWallSignature(false, httpRefusal, 0L))
    }

    @Test
    fun `network loss mid stream is not the wall`() {
        assertFalse(PlaybackWallPolicy.isWallSignature(false, networkLoss, 61_000L))
    }

    @Test
    fun `arus chains never match the wall`() {
        assertFalse(PlaybackWallPolicy.isWallSignature(true, httpRefusal, 61_000L))
    }

    @Test
    fun `auto recovery runs once per video`() {
        assertTrue(PlaybackWallPolicy.shouldAutoRecover(false, null, "abc"))
        assertFalse(PlaybackWallPolicy.shouldAutoRecover(false, "abc", "abc"))
        assertTrue(PlaybackWallPolicy.shouldAutoRecover(false, "abc", "xyz"))
    }

    @Test
    fun `auto recovery never runs for live or without a video`() {
        assertFalse(PlaybackWallPolicy.shouldAutoRecover(true, null, "abc"))
        assertFalse(PlaybackWallPolicy.shouldAutoRecover(false, null, null))
    }

    @Test
    fun `arus opens vod videos while its session is healthy`() {
        assertTrue(
            PlaybackWallPolicy.primaryPath(isLive = false, arusSessionBroken = false) ==
                PlaybackWallPolicy.PrimaryPath.ARUS_FIRST,
        )
    }

    @Test
    fun `classic opens live streams and broken arus sessions`() {
        assertTrue(
            PlaybackWallPolicy.primaryPath(isLive = true, arusSessionBroken = false) ==
                PlaybackWallPolicy.PrimaryPath.CLASSIC_ONLY,
        )
        assertTrue(
            PlaybackWallPolicy.primaryPath(isLive = false, arusSessionBroken = true) ==
                PlaybackWallPolicy.PrimaryPath.CLASSIC_ONLY,
        )
    }

    @Test
    fun `classic rescue runs once per video`() {
        assertTrue(PlaybackWallPolicy.shouldRescueWithClassic(null, "abc"))
        assertFalse(PlaybackWallPolicy.shouldRescueWithClassic("abc", "abc"))
        assertTrue(PlaybackWallPolicy.shouldRescueWithClassic("abc", "xyz"))
        assertFalse(PlaybackWallPolicy.shouldRescueWithClassic(null, null))
    }
}
