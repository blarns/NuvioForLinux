package com.nuvio.app.features.library

import kotlin.test.Test
import kotlin.test.assertEquals

class LibrarySortTest {

    @Test
    fun `date added orders newest first`() {
        val sorted = sortLibraryItems(items = sample, mode = LibrarySortMode.DATE_ADDED)

        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }

    @Test
    fun `name orders alphabetically ignoring case`() {
        val sorted = sortLibraryItems(items = sample, mode = LibrarySortMode.NAME)

        assertEquals(listOf("Alpha", "beta", "Gamma"), sorted.map { it.name })
    }

    @Test
    fun `last watched puts the most recently watched first`() {
        val sorted = sortLibraryItems(
            items = sample,
            mode = LibrarySortMode.LAST_WATCHED,
            lastWatchedAtByContentId = mapOf("a" to 500L, "c" to 100L),
        )

        assertEquals(listOf("a", "c", "b"), sorted.map { it.id })
    }

    @Test
    fun `last watched sinks never-watched titles below watched ones`() {
        val sorted = sortLibraryItems(
            items = sample,
            mode = LibrarySortMode.LAST_WATCHED,
            lastWatchedAtByContentId = mapOf("b" to 1L),
        )

        assertEquals("b", sorted.first().id)
    }

    @Test
    fun `never-watched titles keep date-added order among themselves`() {
        // Otherwise a library with no progress at all would come back in an arbitrary order the
        // moment the user picks Last Watched, which reads as the setting having broken something.
        val sorted = sortLibraryItems(
            items = sample,
            mode = LibrarySortMode.LAST_WATCHED,
            lastWatchedAtByContentId = emptyMap(),
        )

        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }

    @Test
    fun `an unknown stored value falls back to the default`() {
        assertEquals(LibrarySortMode.Default, LibrarySortMode.fromStorage("SOMETHING_ELSE"))
        assertEquals(LibrarySortMode.Default, LibrarySortMode.fromStorage(null))
    }

    @Test
    fun `a stored value round trips`() {
        assertEquals(
            LibrarySortMode.LAST_WATCHED,
            LibrarySortMode.fromStorage(LibrarySortMode.LAST_WATCHED.name),
        )
    }

    @Test
    fun `sorting never drops or duplicates items`() {
        LibrarySortMode.entries.forEach { mode ->
            val sorted = sortLibraryItems(
                items = sample,
                mode = mode,
                lastWatchedAtByContentId = mapOf("a" to 5L),
            )
            assertEquals(sample.size, sorted.size, "size changed for $mode")
            assertEquals(sample.map { it.id }.toSet(), sorted.map { it.id }.toSet(), "ids changed for $mode")
        }
    }

    private val sample = listOf(
        item(id = "a", name = "Gamma", savedAtEpochMs = 100L),
        item(id = "b", name = "beta", savedAtEpochMs = 200L),
        item(id = "c", name = "Alpha", savedAtEpochMs = 300L),
    )

    private fun item(id: String, name: String, savedAtEpochMs: Long) = LibraryItem(
        id = id,
        type = "series",
        name = name,
        savedAtEpochMs = savedAtEpochMs,
    )
}
