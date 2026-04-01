package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

private const val BASE_URL = "https://api.trakt.tv"
private const val APP_VERSION = "nuvio-compose"

internal sealed interface TraktScrobbleItem {
    val itemKey: String

    data class Movie(
        val title: String?,
        val year: Int?,
        val ids: TraktExternalIds,
    ) : TraktScrobbleItem {
        override val itemKey: String =
            "movie:${ids.imdb ?: ids.tmdb ?: ids.trakt ?: title.orEmpty()}:${year ?: 0}"
    }

    data class Episode(
        val showTitle: String?,
        val showYear: Int?,
        val showIds: TraktExternalIds,
        val season: Int,
        val number: Int,
        val episodeTitle: String?,
    ) : TraktScrobbleItem {
        override val itemKey: String =
            "episode:${showIds.imdb ?: showIds.tmdb ?: showIds.trakt ?: showTitle.orEmpty()}:$season:$number"
    }
}

internal object TraktScrobbleRepository {
    private data class ScrobbleStamp(
        val action: String,
        val itemKey: String,
        val progress: Float,
        val timestampMs: Long,
    )

    private val log = Logger.withTag("TraktScrobble")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var lastScrobbleStamp: ScrobbleStamp? = null
    private val minSendIntervalMs = 8_000L
    private val progressWindow = 1.5f

    suspend fun scrobbleStart(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "start", item = item, progressPercent = progressPercent)
    }

    suspend fun scrobbleStop(item: TraktScrobbleItem, progressPercent: Float) {
        sendScrobble(action = "stop", item = item, progressPercent = progressPercent)
    }

    fun buildItem(
        contentType: String,
        parentMetaId: String,
        title: String?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        episodeTitle: String?,
        releaseInfo: String? = null,
    ): TraktScrobbleItem? {
        val normalizedType = contentType.trim().lowercase()
        val ids = parseTraktContentIds(parentMetaId)
        val parsedYear = extractTraktYear(releaseInfo)

        return if (
            normalizedType in listOf("series", "tv", "show", "tvshow") &&
            seasonNumber != null &&
            episodeNumber != null
        ) {
            TraktScrobbleItem.Episode(
                showTitle = title,
                showYear = parsedYear,
                showIds = ids,
                season = seasonNumber,
                number = episodeNumber,
                episodeTitle = episodeTitle,
            )
        } else {
            TraktScrobbleItem.Movie(
                title = title,
                year = parsedYear,
                ids = ids,
            )
        }
    }

    private suspend fun sendScrobble(
        action: String,
        item: TraktScrobbleItem,
        progressPercent: Float,
    ) {
        val headers = TraktAuthRepository.authorizedHeaders() ?: return
        val clampedProgress = progressPercent.coerceIn(0f, 100f)
        if (shouldSkip(action, item.itemKey, clampedProgress)) return

        val requestBody = json.encodeToString(buildRequestBody(item, clampedProgress))
        val result = runCatching {
            httpPostJsonWithHeaders(
                url = "$BASE_URL/scrobble/$action",
                body = requestBody,
                headers = headers,
            )
            true
        }.recoverCatching { error ->
            if (error is CancellationException) throw error
            val isConflict = error.message?.contains("HTTP 409") == true
            if (isConflict) {
                true
            } else {
                throw error
            }
        }

        val wasSent = result.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed Trakt scrobble $action: ${error.message}" }
        }.getOrDefault(false)

        if (!wasSent) return

        lastScrobbleStamp = ScrobbleStamp(
            action = action,
            itemKey = item.itemKey,
            progress = clampedProgress,
            timestampMs = TraktPlatformClock.nowEpochMs(),
        )

        if (action == "stop") {
            runCatching { TraktProgressRepository.refreshNow() }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                    log.w { "Failed to refresh Trakt progress after stop: ${error.message}" }
                }
        }
    }

    private fun buildRequestBody(
        item: TraktScrobbleItem,
        clampedProgress: Float,
    ): TraktScrobbleRequest {
        return when (item) {
            is TraktScrobbleItem.Movie -> TraktScrobbleRequest(
                movie = TraktMovieBody(
                    title = item.title,
                    year = item.year,
                    ids = TraktIdsBody(
                        trakt = item.ids.trakt,
                        imdb = item.ids.imdb,
                        tmdb = item.ids.tmdb,
                    ),
                ),
                progress = clampedProgress,
                appVersion = APP_VERSION,
            )

            is TraktScrobbleItem.Episode -> TraktScrobbleRequest(
                show = TraktShowBody(
                    title = item.showTitle,
                    year = item.showYear,
                    ids = TraktIdsBody(
                        trakt = item.showIds.trakt,
                        imdb = item.showIds.imdb,
                        tmdb = item.showIds.tmdb,
                    ),
                ),
                episode = TraktEpisodeBody(
                    title = item.episodeTitle,
                    season = item.season,
                    number = item.number,
                ),
                progress = clampedProgress,
                appVersion = APP_VERSION,
            )
        }
    }

    private fun shouldSkip(action: String, itemKey: String, progress: Float): Boolean {
        val last = lastScrobbleStamp ?: return false
        val now = TraktPlatformClock.nowEpochMs()
        val isSameWindow = now - last.timestampMs < minSendIntervalMs
        val isSameAction = last.action == action
        val isSameItem = last.itemKey == itemKey
        val isNearProgress = abs(last.progress - progress) <= progressWindow
        return isSameWindow && isSameAction && isSameItem && isNearProgress
    }
}

@Serializable
private data class TraktScrobbleRequest(
    @SerialName("movie") val movie: TraktMovieBody? = null,
    @SerialName("show") val show: TraktShowBody? = null,
    @SerialName("episode") val episode: TraktEpisodeBody? = null,
    @SerialName("progress") val progress: Float,
    @SerialName("app_version") val appVersion: String? = null,
)

@Serializable
private data class TraktMovieBody(
    @SerialName("title") val title: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("ids") val ids: TraktIdsBody? = null,
)

@Serializable
private data class TraktShowBody(
    @SerialName("title") val title: String? = null,
    @SerialName("year") val year: Int? = null,
    @SerialName("ids") val ids: TraktIdsBody? = null,
)

@Serializable
private data class TraktEpisodeBody(
    @SerialName("title") val title: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("number") val number: Int? = null,
)

@Serializable
private data class TraktIdsBody(
    @SerialName("trakt") val trakt: Int? = null,
    @SerialName("imdb") val imdb: String? = null,
    @SerialName("tmdb") val tmdb: Int? = null,
)
