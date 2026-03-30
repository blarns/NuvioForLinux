package com.nuvio.app.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseDateDisplayTest {
    @Test
    fun formatsIsoDate() {
        assertEquals("2025 February 1", formatReleaseDateForDisplay("2025-02-01"))
    }

    @Test
    fun stripsTimePortion() {
        assertEquals("2024 January 15", formatReleaseDateForDisplay("2024-01-15T12:30:00Z"))
    }

    @Test
    fun leavesYearOnlyUnchanged() {
        assertEquals("2024", formatReleaseDateForDisplay("2024"))
    }

    @Test
    fun leavesNonIsoUnchanged() {
        assertEquals("TBA", formatReleaseDateForDisplay("TBA"))
    }
}
