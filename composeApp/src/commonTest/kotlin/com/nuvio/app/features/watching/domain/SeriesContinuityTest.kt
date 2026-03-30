package com.nuvio.app.features.watching.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SeriesContinuityTest {
    private val show = WatchingContentRef(type = "series", id = "show")
    private val episodes = listOf(
        WatchingReleasedEpisode(videoId = "ep1", seasonNumber = 1, episodeNumber = 1, title = "Episode 1", releasedDate = "2026-03-01"),
        WatchingReleasedEpisode(videoId = "ep2", seasonNumber = 1, episodeNumber = 2, title = "Episode 2", releasedDate = "2026-03-08"),
        WatchingReleasedEpisode(videoId = "ep3", seasonNumber = 1, episodeNumber = 3, title = "Episode 3", releasedDate = "2026-03-15"),
    )

    @Test
    fun decideSeriesPrimaryAction_prefers_up_next_when_completed_is_newer_than_resume() {
        val action = decideSeriesPrimaryAction(
            content = show,
            episodes = episodes,
            progressRecords = listOf(
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:2",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    lastUpdatedEpochMs = 100L,
                    lastPositionMs = 1_000L,
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
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Up Next S1E3", action.label)
        assertEquals("show:1:3", action.videoId)
        assertEquals(3, action.episodeNumber)
    }

    @Test
    fun decideSeriesPrimaryAction_prefers_resume_when_resume_is_newer_than_completed() {
        val action = decideSeriesPrimaryAction(
            content = show,
            episodes = episodes,
            progressRecords = listOf(
                WatchingProgressRecord(
                    content = show,
                    videoId = "show:1:2",
                    seasonNumber = 1,
                    episodeNumber = 2,
                    lastUpdatedEpochMs = 300L,
                    lastPositionMs = 1_500L,
                ),
            ),
            watchedRecords = listOf(
                WatchingWatchedRecord(
                    content = show,
                    seasonNumber = 1,
                    episodeNumber = 1,
                    markedAtEpochMs = 200L,
                ),
            ),
            todayIsoDate = "2026-03-30",
        )

        assertNotNull(action)
        assertEquals("Resume S1E2", action.label)
        assertEquals("show:1:2", action.videoId)
        assertEquals(1_500L, action.resumePositionMs)
    }
}
