package com.nuvio.app.features.watched

import kotlin.test.Test
import kotlin.test.assertEquals

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
}

