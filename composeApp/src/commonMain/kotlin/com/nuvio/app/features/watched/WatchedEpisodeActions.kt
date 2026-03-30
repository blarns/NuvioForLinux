package com.nuvio.app.features.watched

import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.isReleasedBy
import com.nuvio.app.features.details.normalizeSeasonNumber
import com.nuvio.app.features.details.sortedPlayableEpisodes
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId

fun MetaDetails.toSeriesWatchedItem(markedAtEpochMs: Long = 0L): WatchedItem =
    WatchedItem(
        id = id,
        type = type,
        name = name,
        poster = poster,
        releaseInfo = releaseInfo,
        markedAtEpochMs = markedAtEpochMs,
    )

fun MetaDetails.toEpisodeWatchedItem(
    video: MetaVideo,
    markedAtEpochMs: Long = 0L,
): WatchedItem =
    WatchedItem(
        id = id,
        type = type,
        name = video.title.ifBlank { name },
        poster = video.thumbnail ?: background ?: poster,
        releaseInfo = releaseInfo,
        season = video.season,
        episode = video.episode,
        markedAtEpochMs = markedAtEpochMs,
    )

fun MetaDetails.releasedPlayableEpisodes(todayIsoDate: String): List<MetaVideo> =
    sortedPlayableEpisodes().filter { episode -> episode.isReleasedBy(todayIsoDate) }

fun MetaDetails.previousReleasedEpisodesBefore(
    target: MetaVideo,
    todayIsoDate: String,
): List<MetaVideo> {
    val targetVideoId = episodePlaybackId(target)
    return releasedPlayableEpisodes(todayIsoDate)
        .takeWhile { episode -> episodePlaybackId(episode) != targetVideoId }
}

fun MetaDetails.releasedEpisodesForSeason(
    seasonNumber: Int?,
    todayIsoDate: String,
): List<MetaVideo> {
    val normalizedSeason = normalizeSeasonNumber(seasonNumber)
    return releasedPlayableEpisodes(todayIsoDate)
        .filter { episode -> normalizeSeasonNumber(episode.season) == normalizedSeason }
}

fun MetaDetails.episodePlaybackId(video: MetaVideo): String =
    buildPlaybackVideoId(
        parentMetaId = id,
        seasonNumber = video.season,
        episodeNumber = video.episode,
        fallbackVideoId = video.id,
    )
