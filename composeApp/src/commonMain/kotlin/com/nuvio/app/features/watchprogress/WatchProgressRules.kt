package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.watching.domain.DefaultContinueWatchingLimit
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.WatchingProgressRecord
import com.nuvio.app.features.watching.domain.continueWatchingProgressEntries
import com.nuvio.app.features.watching.domain.isProgressComplete
import com.nuvio.app.features.watching.domain.resumeProgressForSeries
import com.nuvio.app.features.watching.domain.shouldStoreProgress
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val ContinueWatchingLimit = DefaultContinueWatchingLimit

@Serializable
private data class StoredWatchProgressPayload(
    val entries: List<WatchProgressEntry> = emptyList(),
)

internal object WatchProgressCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun decodeEntries(payload: String): List<WatchProgressEntry> =
        runCatching {
            json.decodeFromString<StoredWatchProgressPayload>(payload).entries
        }.getOrDefault(emptyList())

    fun encodeEntries(entries: Collection<WatchProgressEntry>): String =
        json.encodeToString(
            StoredWatchProgressPayload(
                entries = entries.toList().sortedByDescending { it.lastUpdatedEpochMs },
            ),
        )
}

internal fun shouldStoreWatchProgress(
    positionMs: Long,
    durationMs: Long,
): Boolean = shouldStoreProgress(positionMs = positionMs, durationMs = durationMs)

internal fun isWatchProgressComplete(
    positionMs: Long,
    durationMs: Long,
    isEnded: Boolean,
): Boolean = isProgressComplete(
    positionMs = positionMs,
    durationMs = durationMs,
    isEnded = isEnded,
)

internal fun List<WatchProgressEntry>.resumeEntryForSeries(metaId: String): WatchProgressEntry? =
    firstOrNull { entry -> entry.parentMetaId == metaId }?.let { seed ->
        resumeProgressForSeries(
            content = WatchingContentRef(type = seed.parentMetaType, id = metaId),
            progressRecords = map(WatchProgressEntry::toDomainProgressRecord),
        )?.let { record ->
            firstOrNull { entry -> entry.videoId == record.videoId }
        }
    }

internal fun List<WatchProgressEntry>.continueWatchingEntries(
    limit: Int = ContinueWatchingLimit,
): List<WatchProgressEntry> {
    val domainEntries = continueWatchingProgressEntries(
        progressRecords = map(WatchProgressEntry::toDomainProgressRecord),
        limit = limit,
    )
    val ids = domainEntries.map { record -> record.videoId }.toSet()
    return filter { entry -> entry.videoId in ids }
        .sortedByDescending { it.lastUpdatedEpochMs }
}

private fun WatchProgressEntry.toDomainProgressRecord(): WatchingProgressRecord =
    WatchingProgressRecord(
        content = WatchingContentRef(
            type = parentMetaType,
            id = parentMetaId,
        ),
        videoId = videoId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        lastPositionMs = lastPositionMs,
        isCompleted = isCompleted,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
    )
