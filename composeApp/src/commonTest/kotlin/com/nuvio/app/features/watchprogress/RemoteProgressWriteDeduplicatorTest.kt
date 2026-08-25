package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteProgressWriteDeduplicatorTest {

    @Test
    fun `sends the first write for an entry`() {
        val dedup = RemoteProgressWriteDeduplicator(windowMs = 5_000L)

        assertTrue(dedup.shouldSend(profileId = 1, entry = entry(), nowEpochMs = 1_000L))
    }

    @Test
    fun `suppresses an identical write inside the window`() {
        val dedup = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        dedup.shouldSend(profileId = 1, entry = entry(), nowEpochMs = 1_000L)

        assertFalse(dedup.shouldSend(profileId = 1, entry = entry(), nowEpochMs = 3_000L))
    }

    @Test
    fun `a differing timestamp alone does not defeat deduplication`() {
        // lastUpdatedEpochMs is stamped from the clock at call time, so two reports of the same
        // terminal event always differ by it. If it counted, the deduplicator would never fire.
        val dedup = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        dedup.shouldSend(profileId = 1, entry = entry(lastUpdatedEpochMs = 1_000L), nowEpochMs = 1_000L)

        assertFalse(
            dedup.shouldSend(profileId = 1, entry = entry(lastUpdatedEpochMs = 2_500L), nowEpochMs = 2_500L),
        )
    }

    @Test
    fun `sends again once the window has passed`() {
        val dedup = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        dedup.shouldSend(profileId = 1, entry = entry(), nowEpochMs = 1_000L)

        assertTrue(dedup.shouldSend(profileId = 1, entry = entry(), nowEpochMs = 6_000L))
    }

    @Test
    fun `real progress inside the window still sends`() {
        val dedup = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        dedup.shouldSend(profileId = 1, entry = entry(lastPositionMs = 10_000L), nowEpochMs = 1_000L)

        assertTrue(
            dedup.shouldSend(profileId = 1, entry = entry(lastPositionMs = 20_000L), nowEpochMs = 2_000L),
        )
    }

    @Test
    fun `profiles are deduplicated independently`() {
        val dedup = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        dedup.shouldSend(profileId = 1, entry = entry(), nowEpochMs = 1_000L)

        assertTrue(dedup.shouldSend(profileId = 2, entry = entry(), nowEpochMs = 1_000L))
    }

    @Test
    fun `a backwards clock forgets rather than suppresses`() {
        val dedup = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        dedup.shouldSend(profileId = 1, entry = entry(), nowEpochMs = 10_000L)

        assertTrue(dedup.shouldSend(profileId = 1, entry = entry(), nowEpochMs = 1_000L))
    }

    @Test
    fun `clear drops the window`() {
        val dedup = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        dedup.shouldSend(profileId = 1, entry = entry(), nowEpochMs = 1_000L)
        dedup.clear()

        assertTrue(dedup.shouldSend(profileId = 1, entry = entry(), nowEpochMs = 1_500L))
    }

    private fun entry(
        videoId: String = "tt1234567:1:5",
        lastPositionMs: Long = 10_000L,
        lastUpdatedEpochMs: Long = 1_000L,
    ) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "tt1234567",
        parentMetaType = "series",
        videoId = videoId,
        title = "Example",
        lastPositionMs = lastPositionMs,
        durationMs = 1_400_000L,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
    )
}
