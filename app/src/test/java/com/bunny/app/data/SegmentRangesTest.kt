package com.bunny.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The segmented downloader's range plan: consecutive, gapless,
 * non-overlapping ranges that cover the file exactly — the property
 * the assembly step's length check relies on.
 */
class SegmentRangesTest {

    @Test
    fun exactMultipleSplitsEvenly() {
        val ranges = DownloadManager.segmentRanges(10, 5)
        assertEquals(listOf(0L..4L, 5L..9L), ranges)
    }

    @Test
    fun remainderGetsShortTail() {
        val ranges = DownloadManager.segmentRanges(12, 5)
        assertEquals(listOf(0L..4L, 5L..9L, 10L..11L), ranges)
    }

    @Test
    fun smallerThanOneSegmentIsSingleRange() {
        assertEquals(listOf(0L..2L), DownloadManager.segmentRanges(3, 5))
    }

    @Test
    fun emptyForNonPositiveTotal() {
        assertTrue(DownloadManager.segmentRanges(0, 5).isEmpty())
        assertTrue(DownloadManager.segmentRanges(-1, 5).isEmpty())
    }

    @Test
    fun rangesCoverExactlyWithoutGaps() {
        val total = 7_113_138L
        val ranges = DownloadManager.segmentRanges(total, DownloadManager.SEGMENT_BYTES)
        assertEquals(0L, ranges.first().first)
        assertEquals(total - 1, ranges.last().last)
        ranges.zipWithNext().forEach { (a, b) ->
            assertEquals(a.last + 1, b.first)
        }
        assertEquals(total, ranges.sumOf { it.last - it.first + 1 })
    }
}
