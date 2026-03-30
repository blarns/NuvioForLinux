package com.nuvio.app.features.watched

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WatchedRepositoryTest {
    @Test
    fun watchedItemKey_isTypeAware() {
        assertEquals("movie:tt1:-1:-1", watchedItemKey(type = "movie", id = "tt1"))
    }

    @Test
    fun watchedItemKey_trimsValues() {
        assertEquals("series:abc:-1:-1", watchedItemKey(type = " series ", id = " abc "))
    }

    @Test
    fun watchedItemKey_includes_episode_coordinates() {
        assertEquals(
            "series:show:2:5",
            watchedItemKey(type = "series", id = "show", season = 2, episode = 5),
        )
    }

    @Test
    fun fullyWatchedSeries_ignores_specials() {
        val meta = MetaDetails(
            id = "show",
            type = "series",
            name = "Show",
            videos = listOf(
                MetaVideo(id = "special", title = "Special", season = 0, episode = 1, released = "2026-03-01"),
                MetaVideo(id = "ep1", title = "Episode 1", season = 1, episode = 1, released = "2026-03-08"),
                MetaVideo(id = "ep2", title = "Episode 2", season = 1, episode = 2, released = "2026-03-15"),
            ),
        )

        val result = meta.hasWatchedAllMainSeasonEpisodes(todayIsoDate = "2026-03-30") { episode ->
            episode.season == 1
        }

        assertTrue(result)
    }
}

