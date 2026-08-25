package com.nuvio.app.features.plugins

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginRepositoryRefreshDueTest {

    @Test
    fun `a repository never fetched is due`() {
        assertTrue(isPluginRepositoryRefreshDue(lastUpdatedEpochMs = 0L, nowEpochMs = 1_000L))
    }

    @Test
    fun `a repository fetched just now is not due`() {
        val now = 100_000_000L
        assertFalse(isPluginRepositoryRefreshDue(lastUpdatedEpochMs = now - 1_000L, nowEpochMs = now))
    }

    @Test
    fun `a repository becomes due exactly at the interval`() {
        val now = 100_000_000L
        assertTrue(
            isPluginRepositoryRefreshDue(
                lastUpdatedEpochMs = now - PLUGIN_REPOSITORY_REFRESH_INTERVAL_MS,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun `a repository just inside the interval is not due`() {
        val now = 100_000_000L
        assertFalse(
            isPluginRepositoryRefreshDue(
                lastUpdatedEpochMs = now - (PLUGIN_REPOSITORY_REFRESH_INTERVAL_MS - 1L),
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun `a future timestamp is treated as due rather than trusted`() {
        // A clock that jumped backwards would otherwise pin the repository as fresh
        // indefinitely; refetching is the recoverable direction.
        val now = 100_000_000L
        assertTrue(
            isPluginRepositoryRefreshDue(
                lastUpdatedEpochMs = now + PLUGIN_REPOSITORY_REFRESH_INTERVAL_MS,
                nowEpochMs = now,
            ),
        )
    }

    @Test
    fun `the interval is six hours`() {
        assertTrue(PLUGIN_REPOSITORY_REFRESH_INTERVAL_MS == 6L * 60L * 60L * 1_000L)
    }
}
