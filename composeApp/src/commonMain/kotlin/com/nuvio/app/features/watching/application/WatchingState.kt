package com.nuvio.app.features.watching.application

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.watchedItemKey
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watching.domain.WatchingCompletedEpisode
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.WatchingProgressRecord
import com.nuvio.app.features.watching.domain.WatchingWatchedRecord
import com.nuvio.app.features.watching.domain.continueWatchingProgressEntries
import com.nuvio.app.features.watching.domain.latestCompletedSeriesEpisode

object WatchingState {
    fun isPosterWatched(
        watchedKeys: Set<String>,
        item: MetaPreview,
    ): Boolean = watchedKeys.contains(watchedItemKey(item.type, item.id))

    fun isEpisodeWatched(
        watchedKeys: Set<String>,
        metaType: String,
        metaId: String,
        episode: MetaVideo,
    ): Boolean = watchedKeys.contains(
        watchedItemKey(
            type = metaType,
            id = metaId,
            season = episode.season,
            episode = episode.episode,
        ),
    )

    fun areEpisodesWatched(
        watchedKeys: Set<String>,
        metaType: String,
        metaId: String,
        episodes: Collection<MetaVideo>,
    ): Boolean = episodes.isNotEmpty() && episodes.all { episode ->
        isEpisodeWatched(
            watchedKeys = watchedKeys,
            metaType = metaType,
            metaId = metaId,
            episode = episode,
        )
    }

    fun latestCompletedBySeries(
        progressEntries: List<WatchProgressEntry>,
        watchedItems: List<WatchedItem>,
    ): Map<WatchingContentRef, WatchingCompletedEpisode> {
        val contentRefs = buildSet {
            progressEntries.forEach { entry ->
                add(WatchingContentRef(type = entry.parentMetaType, id = entry.parentMetaId))
            }
            watchedItems.forEach { item ->
                add(WatchingContentRef(type = item.type, id = item.id))
            }
        }
        val progressRecords = progressEntries.map(WatchProgressEntry::toDomainProgressRecord)
        val watchedRecords = watchedItems.map(WatchedItem::toDomainWatchedRecord)
        return contentRefs.mapNotNull { content ->
            latestCompletedSeriesEpisode(
                content = content,
                progressRecords = progressRecords,
                watchedRecords = watchedRecords,
            )?.let { completed -> content to completed }
        }.toMap()
    }

    fun visibleContinueWatchingEntries(
        progressEntries: List<WatchProgressEntry>,
        latestCompletedBySeries: Map<WatchingContentRef, WatchingCompletedEpisode>,
    ): List<WatchProgressEntry> {
        val visibleIds = continueWatchingProgressEntries(
            progressRecords = progressEntries.map(WatchProgressEntry::toDomainProgressRecord),
        )
            .filter { record ->
                val latestCompleted = latestCompletedBySeries[record.content]
                latestCompleted == null || record.lastUpdatedEpochMs > latestCompleted.markedAtEpochMs
            }
            .mapTo(linkedSetOf()) { record -> record.videoId }

        return progressEntries
            .filter { entry -> entry.videoId in visibleIds }
            .sortedByDescending { entry -> entry.lastUpdatedEpochMs }
    }
}

private fun WatchProgressEntry.toDomainProgressRecord(): WatchingProgressRecord =
    WatchingProgressRecord(
        content = WatchingContentRef(type = parentMetaType, id = parentMetaId),
        videoId = videoId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        lastPositionMs = lastPositionMs,
        isCompleted = isCompleted,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
    )

private fun WatchedItem.toDomainWatchedRecord(): WatchingWatchedRecord =
    WatchingWatchedRecord(
        content = WatchingContentRef(type = type, id = id),
        seasonNumber = season,
        episodeNumber = episode,
        markedAtEpochMs = markedAtEpochMs,
    )
