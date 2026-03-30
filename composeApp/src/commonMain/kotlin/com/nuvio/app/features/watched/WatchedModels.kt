package com.nuvio.app.features.watched

import com.nuvio.app.features.home.MetaPreview
import kotlinx.serialization.Serializable

@Serializable
data class WatchedItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val releaseInfo: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val markedAtEpochMs: Long,
)

data class WatchedUiState(
    val items: List<WatchedItem> = emptyList(),
    val watchedKeys: Set<String> = emptySet(),
    val isLoaded: Boolean = false,
)

fun MetaPreview.toWatchedItem(markedAtEpochMs: Long): WatchedItem =
    WatchedItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        releaseInfo = releaseInfo,
        markedAtEpochMs = markedAtEpochMs,
    )

val WatchedItem.isEpisode: Boolean
    get() = season != null && episode != null

fun watchedItemKey(
    type: String,
    id: String,
    season: Int? = null,
    episode: Int? = null,
): String = "${type.trim()}:${id.trim()}:${season ?: -1}:${episode ?: -1}"

