package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the decision that made a hung stream list unrecoverable.
 *
 * The reported symptom: after finishing an episode, "next episode" spun forever; going back to
 * the home page and picking the show spun forever too; only restarting the app helped. The
 * second half of that — the part a retry should have fixed — was this decision returning "skip"
 * because a dead load had left `isAnyLoading = true` behind it.
 */
class StreamReloadDecisionTest {

    @Test
    fun skipsWhileALoadIsGenuinelyRunning() {
        assertTrue(
            shouldSkipStreamReload(
                forceRefresh = false,
                sameRequestKey = true,
                hasSettledGroups = false,
                hasEmptyStateReason = false,
                isAnyLoading = true,
                isLoadJobActive = true,
            ),
        )
    }

    @Test
    fun retriesWhenTheLoadingFlagOutlivedItsJob() {
        // The regression: same request, still flagged as loading, but nothing is running any
        // more. Skipping here left the user on a spinner no work would ever complete.
        assertFalse(
            shouldSkipStreamReload(
                forceRefresh = false,
                sameRequestKey = true,
                hasSettledGroups = false,
                hasEmptyStateReason = false,
                isAnyLoading = true,
                isLoadJobActive = false,
            ),
        )
    }

    @Test
    fun reusesSettledResults() {
        assertTrue(
            shouldSkipStreamReload(
                forceRefresh = false,
                sameRequestKey = true,
                hasSettledGroups = true,
                hasEmptyStateReason = false,
                isAnyLoading = false,
                isLoadJobActive = false,
            ),
        )
    }

    @Test
    fun reusesAnEmptyResult() {
        // "No compatible addons" is an answer, not a missing one — re-asking every time the
        // screen opens would refetch nothing on a loop.
        assertTrue(
            shouldSkipStreamReload(
                forceRefresh = false,
                sameRequestKey = true,
                hasSettledGroups = false,
                hasEmptyStateReason = true,
                isAnyLoading = false,
                isLoadJobActive = false,
            ),
        )
    }

    @Test
    fun neverSkipsADifferentRequest() {
        // The next episode is a different request key; it must always start its own load, even
        // while the previous episode's is still running.
        assertFalse(
            shouldSkipStreamReload(
                forceRefresh = false,
                sameRequestKey = false,
                hasSettledGroups = true,
                hasEmptyStateReason = true,
                isAnyLoading = true,
                isLoadJobActive = true,
            ),
        )
    }

    @Test
    fun forceRefreshAlwaysReloads() {
        assertFalse(
            shouldSkipStreamReload(
                forceRefresh = true,
                sameRequestKey = true,
                hasSettledGroups = true,
                hasEmptyStateReason = false,
                isAnyLoading = false,
                isLoadJobActive = false,
            ),
        )
    }

    @Test
    fun startsAFreshLoadWhenNothingHasHappenedYet() {
        assertFalse(
            shouldSkipStreamReload(
                forceRefresh = false,
                sameRequestKey = true,
                hasSettledGroups = false,
                hasEmptyStateReason = false,
                isAnyLoading = false,
                isLoadJobActive = false,
            ),
        )
    }
}
