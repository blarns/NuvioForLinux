package com.nuvio.app.features.watchprogress

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.watching.domain.WatchingContentRef
import kotlinx.serialization.Serializable

@Serializable
enum class ContinueWatchingSectionStyle {
    Wide,
    Poster,
}

@Serializable
data class WatchProgressEntry(
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastUpdatedEpochMs: Long,
    val providerName: String? = null,
    val providerAddonId: String? = null,
    val lastStreamTitle: String? = null,
    val lastStreamSubtitle: String? = null,
    val pauseDescription: String? = null,
    val lastSourceUrl: String? = null,
    val isCompleted: Boolean = false,
    val progressPercent: Float? = null,
) {
    val progressFraction: Float
        get() {
            progressPercent?.let { explicitPercent ->
                return (explicitPercent / 100f).coerceIn(0f, 1f)
            }
            return if (durationMs > 0L) {
                (lastPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
        }

    val isEpisode: Boolean
        get() = seasonNumber != null && episodeNumber != null

    val isResumable: Boolean
        get() = !isCompleted

    fun resolveResumePosition(actualDurationMs: Long): Long {
        if (actualDurationMs <= 0L) return lastPositionMs.coerceAtLeast(0L)
        if (durationMs > 0L && lastPositionMs > 0L) {
            return lastPositionMs.coerceIn(0L, actualDurationMs)
        }
        progressPercent?.let { percent ->
            val fraction = (percent / 100f).coerceIn(0f, 1f)
            return (actualDurationMs * fraction).toLong()
        }
        return lastPositionMs.coerceAtLeast(0L)
    }
}

data class WatchProgressUiState(
    val entries: List<WatchProgressEntry> = emptyList(),
) {
    val byVideoId: Map<String, WatchProgressEntry>
        get() = entries.associateBy { it.videoId }

    val continueWatchingEntries: List<WatchProgressEntry>
        get() = entries.continueWatchingEntries(limit = ContinueWatchingLimit)
}

data class WatchProgressPlaybackSession(
    val contentType: String,
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val providerName: String? = null,
    val providerAddonId: String? = null,
    val lastStreamTitle: String? = null,
    val lastStreamSubtitle: String? = null,
    val pauseDescription: String? = null,
    val lastSourceUrl: String? = null,
)

data class ContinueWatchingItem(
    val parentMetaId: String,
    val parentMetaType: String,
    val videoId: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val pauseDescription: String? = null,
    val resumePositionMs: Long,
    val resumeProgressFraction: Float? = null,
    val durationMs: Long,
    val progressFraction: Float,
)

data class ContinueWatchingPreferencesUiState(
    val isVisible: Boolean = true,
    val style: ContinueWatchingSectionStyle = ContinueWatchingSectionStyle.Wide,
    val upNextFromFurthestEpisode: Boolean = true,
)

internal fun WatchProgressEntry.toContinueWatchingItem(): ContinueWatchingItem {
    val explicitResumeProgressFraction = progressPercent
        ?.takeIf { durationMs <= 0L && it > 0f }
        ?.let { explicitPercent -> (explicitPercent / 100f).coerceIn(0f, 1f) }

    val subtitle = if (seasonNumber != null && episodeNumber != null) {
        buildString {
            append("S")
            append(seasonNumber)
            append("E")
            append(episodeNumber)
            episodeTitle?.takeIf { it.isNotBlank() }?.let {
                append(" • ")
                append(it)
            }
        }
    } else {
        "Movie"
    }

    return ContinueWatchingItem(
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        videoId = videoId,
        title = title,
        subtitle = subtitle,
        imageUrl = episodeThumbnail ?: background ?: poster,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTitle = episodeTitle,
        episodeThumbnail = episodeThumbnail,
        pauseDescription = pauseDescription,
        resumePositionMs = if (explicitResumeProgressFraction != null) 0L else lastPositionMs,
        resumeProgressFraction = explicitResumeProgressFraction,
        durationMs = durationMs,
        progressFraction = progressFraction,
    )
}

internal fun WatchProgressEntry.toUpNextContinueWatchingItem(
    nextEpisode: MetaVideo,
): ContinueWatchingItem {
    val subtitle = buildString {
        append("Up Next")
        if (nextEpisode.season != null && nextEpisode.episode != null) {
            append(" • S")
            append(nextEpisode.season)
            append("E")
            append(nextEpisode.episode)
        }
        nextEpisode.title.takeIf { it.isNotBlank() }?.let {
            append(" • ")
            append(it)
        }
    }

    return ContinueWatchingItem(
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        videoId = buildPlaybackVideoId(
            parentMetaId = parentMetaId,
            seasonNumber = nextEpisode.season,
            episodeNumber = nextEpisode.episode,
            fallbackVideoId = nextEpisode.id,
        ),
        title = title,
        subtitle = subtitle,
        imageUrl = nextEpisode.thumbnail ?: episodeThumbnail ?: background ?: poster,
        logo = logo,
        poster = poster,
        background = background,
        seasonNumber = nextEpisode.season,
        episodeNumber = nextEpisode.episode,
        episodeTitle = nextEpisode.title,
        episodeThumbnail = nextEpisode.thumbnail,
        pauseDescription = nextEpisode.overview,
        resumePositionMs = 0L,
        resumeProgressFraction = null,
        durationMs = 0L,
        progressFraction = 0f,
    )
}

fun buildPlaybackVideoId(
    parentMetaId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    fallbackVideoId: String? = null,
): String = com.nuvio.app.features.watching.domain.buildPlaybackVideoId(
    content = WatchingContentRef(type = "", id = parentMetaId),
    seasonNumber = seasonNumber,
    episodeNumber = episodeNumber,
    fallbackVideoId = fallbackVideoId,
)
