package com.nuvio.app.features.details

import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.resumeEntryForSeries

internal fun MetaDetails.sortedPlayableEpisodes(): List<MetaVideo> =
    videos
        .filter { it.season != null || it.episode != null }
        .sortedWith(metaVideoSeasonEpisodeComparator)

internal fun MetaDetails.firstPlayableEpisode(): MetaVideo? =
    sortedPlayableEpisodes().firstOrNull()

internal fun MetaDetails.firstReleasedPlayableEpisode(todayIsoDate: String): MetaVideo? =
    sortedPlayableEpisodes().firstOrNull { it.isReleasedBy(todayIsoDate) }

internal fun MetaDetails.nextReleasedEpisodeAfter(
    completedEntry: WatchProgressEntry,
    todayIsoDate: String,
): MetaVideo? {
    return nextReleasedEpisodeAfter(
        seasonNumber = completedEntry.seasonNumber,
        episodeNumber = completedEntry.episodeNumber,
        todayIsoDate = todayIsoDate,
    )
}

internal fun MetaDetails.nextReleasedEpisodeAfter(
    seasonNumber: Int?,
    episodeNumber: Int?,
    todayIsoDate: String,
): MetaVideo? {
    val sortedEpisodes = sortedPlayableEpisodes()
    val watchedVideoId = buildPlaybackVideoId(
        parentMetaId = id,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )
    return sortedEpisodes
        .dropWhile { episode ->
            buildPlaybackVideoId(
                parentMetaId = id,
                seasonNumber = episode.season,
                episodeNumber = episode.episode,
                fallbackVideoId = episode.id,
            ) != watchedVideoId
        }
        .drop(1)
        .firstOrNull { it.isReleasedBy(todayIsoDate) }
}

internal data class SeriesPrimaryAction(
    val label: String,
    val videoId: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeTitle: String?,
    val episodeThumbnail: String?,
    val resumePositionMs: Long?,
)

internal fun MetaDetails.seriesPrimaryAction(
    entries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
    todayIsoDate: String,
): SeriesPrimaryAction? {
    val resumeEntry = entries.resumeEntryForSeries(id)
    val latestCompleted = latestCompletedSeriesEpisode(
        parentMetaId = id,
        parentMetaType = type,
        progressEntries = entries,
        watchedItems = watchedItems,
    )

    val shouldPreferResume = resumeEntry != null &&
        (latestCompleted == null || resumeEntry.lastUpdatedEpochMs > latestCompleted.markedAtEpochMs)

    if (shouldPreferResume) {
        return SeriesPrimaryAction(
            label = resumeEntry.resumeLabel(),
            videoId = resumeEntry.videoId,
            seasonNumber = resumeEntry.seasonNumber,
            episodeNumber = resumeEntry.episodeNumber,
            episodeTitle = resumeEntry.episodeTitle,
            episodeThumbnail = resumeEntry.episodeThumbnail,
            resumePositionMs = resumeEntry.lastPositionMs,
        )
    }

    val nextEpisode = if (latestCompleted != null) {
        nextReleasedEpisodeAfter(
            seasonNumber = latestCompleted.seasonNumber,
            episodeNumber = latestCompleted.episodeNumber,
            todayIsoDate = todayIsoDate,
        )
    } else {
        firstReleasedPlayableEpisode(todayIsoDate)
    }

    return nextEpisode?.let { episode ->
        SeriesPrimaryAction(
            label = if (latestCompleted != null) episode.upNextLabel() else episode.playLabel(),
            videoId = buildPlaybackVideoId(
                parentMetaId = id,
                seasonNumber = episode.season,
                episodeNumber = episode.episode,
                fallbackVideoId = episode.id,
            ),
            seasonNumber = episode.season,
            episodeNumber = episode.episode,
            episodeTitle = episode.title,
            episodeThumbnail = episode.thumbnail,
            resumePositionMs = null,
        )
    }
}

internal fun MetaVideo.playLabel(): String =
    if (season != null && episode != null) {
        "Play S${season}E${episode}"
    } else {
        "Play"
    }

internal fun MetaVideo.upNextLabel(): String =
    if (season != null && episode != null) {
        "Up Next S${season}E${episode}"
    } else {
        "Up Next"
    }

internal fun WatchProgressEntry.resumeLabel(): String =
    if (seasonNumber != null && episodeNumber != null) {
        "Resume S${seasonNumber}E${episodeNumber}"
    } else {
        "Resume"
    }

internal fun MetaVideo.isReleasedBy(todayIsoDate: String): Boolean {
    val releaseDate = released
        ?.substringBefore('T')
        ?.takeIf { it.length == 10 }
        ?: return true
    return releaseDate <= todayIsoDate
}

internal data class CompletedSeriesEpisode(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val markedAtEpochMs: Long,
)

internal fun latestCompletedSeriesEpisode(
    parentMetaId: String,
    parentMetaType: String,
    progressEntries: List<WatchProgressEntry>,
    watchedItems: List<WatchedItem>,
): CompletedSeriesEpisode? {
    val progressMarker = progressEntries
        .asSequence()
        .filter { entry ->
            entry.parentMetaId == parentMetaId &&
                entry.isCompleted &&
                entry.seasonNumber != null &&
                entry.episodeNumber != null
        }
        .map { entry ->
            CompletedSeriesEpisode(
                seasonNumber = entry.seasonNumber ?: return@map null,
                episodeNumber = entry.episodeNumber ?: return@map null,
                markedAtEpochMs = entry.lastUpdatedEpochMs,
            )
        }
        .filterNotNull()
        .maxByOrNull { marker -> marker.markedAtEpochMs }

    val watchedMarker = watchedItems
        .asSequence()
        .filter { item ->
            item.id == parentMetaId &&
                item.type == parentMetaType &&
                item.season != null &&
                item.episode != null
        }
        .map { item ->
            CompletedSeriesEpisode(
                seasonNumber = item.season ?: return@map null,
                episodeNumber = item.episode ?: return@map null,
                markedAtEpochMs = item.markedAtEpochMs,
            )
        }
        .filterNotNull()
        .maxByOrNull { marker -> marker.markedAtEpochMs }

    return listOfNotNull(progressMarker, watchedMarker)
        .maxByOrNull { marker -> marker.markedAtEpochMs }
}
