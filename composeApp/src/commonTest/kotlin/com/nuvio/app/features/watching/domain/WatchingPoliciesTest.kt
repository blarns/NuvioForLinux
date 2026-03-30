package com.nuvio.app.features.watching.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchingPoliciesTest {
    private val show = WatchingContentRef(type = "series", id = "show")

    @Test
    fun hasWatchedAllMainSeasonEpisodes_ignores_specials() {
        val episodes = listOf(
            WatchingReleasedEpisode(videoId = "special", seasonNumber = 0, episodeNumber = 1, releasedDate = "2026-03-01"),
            WatchingReleasedEpisode(videoId = "ep1", seasonNumber = 1, episodeNumber = 1, releasedDate = "2026-03-08"),
            WatchingReleasedEpisode(videoId = "ep2", seasonNumber = 1, episodeNumber = 2, releasedDate = "2026-03-15"),
        )

        val result = hasWatchedAllMainSeasonEpisodes(
            episodes = episodes,
            todayIsoDate = "2026-03-30",
            isEpisodeWatched = { episode -> episode.seasonNumber == 1 },
        )

        assertTrue(result)
    }

    @Test
    fun latestCompletedSeriesEpisode_prefers_newer_manual_watch_marker() {
        val latestCompleted = latestCompletedSeriesEpisode(
            content = show,
            progressRecords = listOf(
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:2",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    lastUpdatedEpochMs = 100L,
                    isCompleted = true,
                ),
            ),
            watchedRecords = listOf(
                WatchingWatchedRecord(
                    content = show,
                    seasonNumber = 1,
                    episodeNumber = 3,
                    markedAtEpochMs = 200L,
                ),
            ),
        )

        assertNotNull(latestCompleted)
        assertEquals(1, latestCompleted.seasonNumber)
        assertEquals(3, latestCompleted.episodeNumber)
        assertEquals(200L, latestCompleted.markedAtEpochMs)
    }

    @Test
    fun latestCompletedSeriesEpisode_prefers_newer_progress_marker() {
        val latestCompleted = latestCompletedSeriesEpisode(
            content = show,
            progressRecords = listOf(
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:3",
                    seasonNumber = 1,
                    episodeNumber = 3,
                    lastUpdatedEpochMs = 300L,
                    isCompleted = true,
                ),
            ),
            watchedRecords = listOf(
                WatchingWatchedRecord(
                    content = show,
                    seasonNumber = 1,
                    episodeNumber = 2,
                    markedAtEpochMs = 200L,
                ),
            ),
        )

        assertNotNull(latestCompleted)
        assertEquals(1, latestCompleted.seasonNumber)
        assertEquals(3, latestCompleted.episodeNumber)
        assertEquals(300L, latestCompleted.markedAtEpochMs)
    }
}
