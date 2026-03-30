package com.nuvio.app.features.watching.domain

private const val InProgressStartThresholdFraction = 0.02f
private const val CompletionThresholdFraction = 0.85
private const val InProgressStartThresholdMinMs = 30_000L

fun watchedKey(
    content: WatchingContentRef,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
): String = "${content.type.trim()}:${content.id.trim()}:${seasonNumber ?: -1}:${episodeNumber ?: -1}"

fun shouldStoreProgress(
    positionMs: Long,
    durationMs: Long,
): Boolean {
    val thresholdMs = if (durationMs > 0L) {
        maxOf(
            InProgressStartThresholdMinMs,
            (durationMs * InProgressStartThresholdFraction).toLong(),
        )
    } else {
        1L
    }
    return positionMs >= thresholdMs
}

fun isProgressComplete(
    positionMs: Long,
    durationMs: Long,
    isEnded: Boolean,
): Boolean {
    if (isEnded) return true
    if (durationMs <= 0L) return false

    val watchedFraction = positionMs.toDouble() / durationMs.toDouble()
    return watchedFraction >= CompletionThresholdFraction
}

fun isReleasedBy(
    todayIsoDate: String,
    releasedDate: String?,
): Boolean {
    val isoDate = releasedDate
        ?.substringBefore('T')
        ?.takeIf { it.length == 10 }
        ?: return true
    return isoDate <= todayIsoDate
}

fun releasedEpisodes(
    episodes: List<WatchingReleasedEpisode>,
    todayIsoDate: String,
): List<WatchingReleasedEpisode> = episodes.filter { episode ->
    isReleasedBy(todayIsoDate = todayIsoDate, releasedDate = episode.releasedDate)
}

fun releasedMainSeasonEpisodes(
    episodes: List<WatchingReleasedEpisode>,
    todayIsoDate: String,
): List<WatchingReleasedEpisode> = releasedEpisodes(
    episodes = episodes,
    todayIsoDate = todayIsoDate,
).filter { episode ->
    normalizeSeasonNumber(episode.seasonNumber) > 0
}

fun hasWatchedAllMainSeasonEpisodes(
    episodes: List<WatchingReleasedEpisode>,
    todayIsoDate: String,
    isEpisodeWatched: (WatchingReleasedEpisode) -> Boolean,
): Boolean {
    val mainSeasonEpisodes = releasedMainSeasonEpisodes(
        episodes = episodes,
        todayIsoDate = todayIsoDate,
    )
    return mainSeasonEpisodes.isNotEmpty() && mainSeasonEpisodes.all(isEpisodeWatched)
}

fun latestCompletedSeriesEpisode(
    content: WatchingContentRef,
    progressRecords: List<WatchingProgressRecord>,
    watchedRecords: List<WatchingWatchedRecord>,
): WatchingCompletedEpisode? {
    val progressMarker = progressRecords
        .asSequence()
        .filter { record ->
            record.content == content &&
                record.isCompleted &&
                record.seasonNumber != null &&
                record.episodeNumber != null
        }
        .mapNotNull { record ->
            val seasonNumber = record.seasonNumber ?: return@mapNotNull null
            val episodeNumber = record.episodeNumber ?: return@mapNotNull null
            WatchingCompletedEpisode(
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                markedAtEpochMs = record.lastUpdatedEpochMs,
            )
        }
        .maxByOrNull { marker -> marker.markedAtEpochMs }

    val watchedMarker = watchedRecords
        .asSequence()
        .filter { record ->
            record.content == content &&
                record.seasonNumber != null &&
                record.episodeNumber != null
        }
        .mapNotNull { record ->
            val seasonNumber = record.seasonNumber ?: return@mapNotNull null
            val episodeNumber = record.episodeNumber ?: return@mapNotNull null
            WatchingCompletedEpisode(
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                markedAtEpochMs = record.markedAtEpochMs,
            )
        }
        .maxByOrNull { marker -> marker.markedAtEpochMs }

    return listOfNotNull(progressMarker, watchedMarker)
        .maxByOrNull { marker -> marker.markedAtEpochMs }
}

fun normalizeSeasonNumber(seasonNumber: Int?): Int = seasonNumber?.coerceAtLeast(0) ?: 0
