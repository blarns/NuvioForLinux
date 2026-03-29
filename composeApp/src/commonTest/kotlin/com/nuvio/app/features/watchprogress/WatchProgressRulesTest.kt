package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchProgressRulesTest {

    @Test
    fun `codec round trips entries in descending updated order`() {
        val older = entry(videoId = "movie-1", lastUpdatedEpochMs = 100L)
        val newer = entry(videoId = "movie-2", lastUpdatedEpochMs = 200L)

        val payload = WatchProgressCodec.encodeEntries(listOf(older, newer))
        val decoded = WatchProgressCodec.decodeEntries(payload)

        assertEquals(listOf("movie-2", "movie-1"), decoded.map { it.videoId })
    }

    @Test
    fun `codec ignores corrupt payload`() {
        assertTrue(WatchProgressCodec.decodeEntries("{not json").isEmpty())
    }

    @Test
    fun `save threshold uses max of thirty seconds and two percent`() {
        assertFalse(shouldStoreWatchProgress(positionMs = 29_999L, durationMs = 600_000L))
        assertTrue(shouldStoreWatchProgress(positionMs = 30_000L, durationMs = 600_000L))
        assertFalse(shouldStoreWatchProgress(positionMs = 119_999L, durationMs = 6_000_000L))
        assertTrue(shouldStoreWatchProgress(positionMs = 120_000L, durationMs = 6_000_000L))
    }

    @Test
    fun `completion detects watched threshold remaining time and ended state`() {
        assertTrue(isWatchProgressComplete(positionMs = 920_000L, durationMs = 1_000_000L, isEnded = false))
        assertTrue(isWatchProgressComplete(positionMs = 850_000L, durationMs = 1_000_000L, isEnded = false))
        assertTrue(isWatchProgressComplete(positionMs = 1L, durationMs = 0L, isEnded = true))
        assertFalse(isWatchProgressComplete(positionMs = 200_000L, durationMs = 1_000_000L, isEnded = false))
    }

    @Test
    fun `resume entry for series picks most recent episode`() {
        val older = entry(videoId = "show:1:1", parentMetaId = "show", seasonNumber = 1, episodeNumber = 1, lastUpdatedEpochMs = 10L)
        val newer = entry(videoId = "show:1:2", parentMetaId = "show", seasonNumber = 1, episodeNumber = 2, lastUpdatedEpochMs = 20L)
        val other = entry(videoId = "movie", parentMetaId = "movie", lastUpdatedEpochMs = 30L)

        val result = listOf(older, newer, other).resumeEntryForSeries("show")

        assertEquals("show:1:2", result?.videoId)
    }

    @Test
    fun `resume entry returns null when no series entries exist`() {
        val result = listOf(entry(videoId = "movie", parentMetaId = "movie")).resumeEntryForSeries("show")

        assertNull(result)
    }

    @Test
    fun `continue watching entries are sorted and capped`() {
        val entries = (1..25).map { index ->
            entry(videoId = "video-$index", lastUpdatedEpochMs = index.toLong())
        }

        val result = entries.continueWatchingEntries()

        assertEquals(20, result.size)
        assertEquals("video-25", result.first().videoId)
        assertEquals("video-6", result.last().videoId)
    }

    @Test
    fun `continue watching deduplicates series keeping latest episode per show`() {
        val ep1 = entry(videoId = "show:1:1", parentMetaId = "show", seasonNumber = 1, episodeNumber = 1, lastUpdatedEpochMs = 10L)
        val ep2 = entry(videoId = "show:1:2", parentMetaId = "show", seasonNumber = 1, episodeNumber = 2, lastUpdatedEpochMs = 20L)
        val movie = entry(videoId = "movie-1", parentMetaId = "movie-1", lastUpdatedEpochMs = 15L)

        val result = listOf(ep1, ep2, movie).continueWatchingEntries()

        assertEquals(2, result.size)
        assertEquals("show:1:2", result.first().videoId)
        assertEquals("movie-1", result.last().videoId)
    }

    @Test
    fun `continue watching keeps multiple movies without deduplication`() {
        val m1 = entry(videoId = "movie-a", parentMetaId = "movie-a", lastUpdatedEpochMs = 10L)
        val m2 = entry(videoId = "movie-b", parentMetaId = "movie-b", lastUpdatedEpochMs = 20L)

        val result = listOf(m1, m2).continueWatchingEntries()

        assertEquals(2, result.size)
    }

    @Test
    fun `build playback video id uses season and episode when present`() {
        assertEquals("show:1:2", buildPlaybackVideoId(parentMetaId = "show", seasonNumber = 1, episodeNumber = 2, fallbackVideoId = "fallback"))
        assertEquals("fallback", buildPlaybackVideoId(parentMetaId = "movie", seasonNumber = null, episodeNumber = null, fallbackVideoId = "fallback"))
        assertEquals("movie", buildPlaybackVideoId(parentMetaId = "movie", seasonNumber = null, episodeNumber = null, fallbackVideoId = null))
    }

    private fun entry(
        videoId: String,
        parentMetaId: String = videoId.substringBefore(':'),
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        lastUpdatedEpochMs: Long = 1L,
    ): WatchProgressEntry =
        WatchProgressEntry(
            contentType = if (seasonNumber != null && episodeNumber != null) "series" else "movie",
            parentMetaId = parentMetaId,
            parentMetaType = if (seasonNumber != null && episodeNumber != null) "series" else "movie",
            videoId = videoId,
            title = "Title",
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            lastPositionMs = 120_000L,
            durationMs = 1_000_000L,
            lastUpdatedEpochMs = lastUpdatedEpochMs,
        )
}
