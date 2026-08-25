package com.nuvio.app.features.library

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import kotlinx.serialization.Serializable

@Serializable
data class LibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val posterShape: PosterShape = PosterShape.Poster,
    val addonBaseUrl: String? = null,
    val listKeys: Set<String> = emptySet(),
    val traktRank: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val traktId: Int? = null,
    val savedAtEpochMs: Long,
)

data class LibrarySection(
    val type: String,
    val displayTitle: String,
    val items: List<LibraryItem>,
)

enum class LibrarySourceMode {
    LOCAL,
    TRAKT,
}

/** How the Library orders its items. Persisted per profile with the library payload. */
enum class LibrarySortMode {
    DATE_ADDED,
    LAST_WATCHED,
    NAME,
    ;

    companion object {
        val Default: LibrarySortMode = DATE_ADDED

        fun fromStorage(raw: String?): LibrarySortMode =
            entries.firstOrNull { it.name == raw } ?: Default
    }
}

/**
 * Orders library items for display.
 *
 * [lastWatchedAtByContentId] maps a library item's id to the most recent watch-progress update
 * for that title. An item with no progress has never been watched, so under [LAST_WATCHED] it
 * sorts below everything that has been — and ties there fall back to when it was saved, which
 * keeps a never-watched library in its familiar order instead of an arbitrary one.
 *
 * [NAME] compares case-insensitively; without that, lowercase titles sort after every uppercase
 * one, which reads as broken rather than as sorted.
 */
fun sortLibraryItems(
    items: List<LibraryItem>,
    mode: LibrarySortMode,
    lastWatchedAtByContentId: Map<String, Long> = emptyMap(),
): List<LibraryItem> = when (mode) {
    LibrarySortMode.DATE_ADDED -> items.sortedByDescending { it.savedAtEpochMs }
    // lowercase(), not String.CASE_INSENSITIVE_ORDER — that one is JVM-only and this is commonMain.
    LibrarySortMode.NAME -> items.sortedBy { it.name.trim().lowercase() }
    LibrarySortMode.LAST_WATCHED -> items.sortedWith(
        compareByDescending<LibraryItem> { lastWatchedAtByContentId[it.id] ?: 0L }
            .thenByDescending { it.savedAtEpochMs },
    )
}

data class LibraryUiState(
    val sourceMode: LibrarySourceMode = LibrarySourceMode.LOCAL,
    val sortMode: LibrarySortMode = LibrarySortMode.Default,
    val items: List<LibraryItem> = emptyList(),
    val sections: List<LibrarySection> = emptyList(),
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

fun MetaDetails.toLibraryItem(savedAtEpochMs: Long): LibraryItem =
    LibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = background,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        posterShape = PosterShape.Poster,
        imdbId = id.takeIf { it.startsWith("tt") },
        savedAtEpochMs = savedAtEpochMs,
    )

fun MetaPreview.toLibraryItem(savedAtEpochMs: Long): LibraryItem =
    LibraryItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = banner,
        logo = logo,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
        posterShape = posterShape,
        imdbId = id.takeIf { it.startsWith("tt") },
        savedAtEpochMs = savedAtEpochMs,
    )

fun LibraryItem.toMetaPreview(): MetaPreview =
    MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = banner,
        logo = logo,
        posterShape = posterShape,
        description = description,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres,
    )
