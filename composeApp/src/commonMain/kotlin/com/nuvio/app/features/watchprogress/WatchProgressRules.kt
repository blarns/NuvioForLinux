package com.nuvio.app.features.watchprogress

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal const val ContinueWatchingLimit = 20
private const val InProgressStartThresholdFraction = 0.02f
private const val CompletionThresholdFraction = 0.85

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
): Boolean {
    val thresholdMs = if (durationMs > 0L) {
        (durationMs * InProgressStartThresholdFraction).toLong()
    } else {
        1L
    }
    return positionMs >= thresholdMs
}

internal fun isWatchProgressComplete(
    positionMs: Long,
    durationMs: Long,
    isEnded: Boolean,
): Boolean {
    if (isEnded) return true
    if (durationMs <= 0L) return false

    val watchedFraction = positionMs.toDouble() / durationMs.toDouble()
    return watchedFraction >= CompletionThresholdFraction
}

internal fun List<WatchProgressEntry>.resumeEntryForSeries(metaId: String): WatchProgressEntry? =
    filter { it.parentMetaId == metaId && !it.isCompleted }
        .maxByOrNull { it.lastUpdatedEpochMs }

internal fun List<WatchProgressEntry>.continueWatchingEntries(
    limit: Int = ContinueWatchingLimit,
): List<WatchProgressEntry> {
    val inProgress = filterNot { it.isCompleted }
    val (episodes, nonEpisodes) = inProgress.partition { it.isEpisode }
    val latestPerSeries = episodes
        .sortedByDescending { it.lastUpdatedEpochMs }
        .distinctBy { it.parentMetaId }
    return (nonEpisodes + latestPerSeries)
        .sortedByDescending { it.lastUpdatedEpochMs }
        .take(limit)
}
