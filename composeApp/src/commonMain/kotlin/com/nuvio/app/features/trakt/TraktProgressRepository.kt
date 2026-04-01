package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://api.trakt.tv"
private const val PLAYBACK_SYNTHETIC_DURATION_MS = 100_000L
private const val HISTORY_LIMIT = 250
private const val METADATA_FETCH_TIMEOUT_MS = 3_500L
private const val METADATA_FETCH_CONCURRENCY = 5
private const val METADATA_HYDRATION_LIMIT = 30

data class TraktProgressUiState(
    val entries: List<WatchProgressEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

object TraktProgressRepository {
    private val log = Logger.withTag("TraktProgress")
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _uiState = MutableStateFlow(TraktProgressUiState())
    val uiState: StateFlow<TraktProgressUiState> = _uiState.asStateFlow()

    private var hasLoaded = false
    private var refreshRequestId: Long = 0L

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
    }

    fun onProfileChanged() {
        invalidateInFlightRefreshes()
        hasLoaded = false
        _uiState.value = TraktProgressUiState()
        ensureLoaded()
    }

    fun clearLocalState() {
        invalidateInFlightRefreshes()
        hasLoaded = false
        _uiState.value = TraktProgressUiState()
    }

    fun refreshAsync() {
        scope.launch {
            refreshNow()
        }
    }

    suspend fun refreshNow() {
        ensureLoaded()
        val requestId = nextRefreshRequestId()
        val headers = TraktAuthRepository.authorizedHeaders()
        if (headers == null) {
            _uiState.value = TraktProgressUiState()
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        val rawEntries = runCatching {
            fetchSnapshotEntries(headers)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to refresh Trakt progress: ${error.message}" }
        }.getOrNull()

        if (rawEntries == null) {
            _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to load Trakt progress")
            return
        }

        _uiState.value = TraktProgressUiState(
            entries = rawEntries,
            isLoading = false,
            errorMessage = null,
        )

        if (rawEntries.isNotEmpty()) {
            scope.launch {
                val hydrated = runCatching {
                    hydrateEntriesFromAddonMeta(rawEntries)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    log.w { "Failed to hydrate Trakt metadata: ${error.message}" }
                }.getOrNull() ?: return@launch

                if (!isLatestRefreshRequest(requestId)) return@launch

                val merged = mergeEntriesPreferRichMetadata(
                    current = _uiState.value.entries,
                    hydrated = hydrated,
                )
                _uiState.value = _uiState.value.copy(
                    entries = merged.sortedByDescending { it.lastUpdatedEpochMs },
                    isLoading = false,
                    errorMessage = null,
                )
            }
        }
    }

    fun applyOptimisticProgress(entry: WatchProgressEntry) {
        if (!TraktAuthRepository.isAuthenticated.value) return
        val current = _uiState.value.entries.associateBy { it.videoId }.toMutableMap()
        val existing = current[entry.videoId]
        if (existing == null || entry.lastUpdatedEpochMs >= existing.lastUpdatedEpochMs) {
            current[entry.videoId] = entry
        }
        _uiState.value = _uiState.value.copy(entries = current.values.sortedByDescending { it.lastUpdatedEpochMs })
    }

    fun applyOptimisticRemoval(videoId: String) {
        if (!TraktAuthRepository.isAuthenticated.value) return
        if (videoId.isBlank()) return
        val filtered = _uiState.value.entries.filterNot { it.videoId == videoId }
        _uiState.value = _uiState.value.copy(entries = filtered)
    }

    private suspend fun fetchSnapshotEntries(headers: Map<String, String>): List<WatchProgressEntry> = withContext(Dispatchers.Default) {
        val payloads = coroutineScope {
            val moviesPayload = async {
                httpGetTextWithHeaders(
                    url = "$BASE_URL/sync/playback/movies",
                    headers = headers,
                )
            }
            val episodesPayload = async {
                httpGetTextWithHeaders(
                    url = "$BASE_URL/sync/playback/episodes",
                    headers = headers,
                )
            }
            val historyPayload = async {
                httpGetTextWithHeaders(
                    url = "$BASE_URL/sync/history/episodes?limit=$HISTORY_LIMIT",
                    headers = headers,
                )
            }
            val movieHistoryPayload = async {
                httpGetTextWithHeaders(
                    url = "$BASE_URL/sync/history/movies?limit=$HISTORY_LIMIT",
                    headers = headers,
                )
            }

            awaitAll(moviesPayload, episodesPayload, historyPayload, movieHistoryPayload)
        }

        val moviesPayload = payloads[0]
        val episodesPayload = payloads[1]
        val historyPayload = payloads[2]
        val movieHistoryPayload = payloads[3]

        val moviePlayback = json.decodeFromString<List<TraktPlaybackItem>>(moviesPayload)
        val episodePlayback = json.decodeFromString<List<TraktPlaybackItem>>(episodesPayload)
        val episodeHistory = json.decodeFromString<List<TraktHistoryEpisodeItem>>(historyPayload)
        val movieHistory = json.decodeFromString<List<TraktHistoryMovieItem>>(movieHistoryPayload)

        val inProgressMovies = moviePlayback.mapIndexedNotNull { index, item ->
            mapPlaybackMovie(item = item, fallbackIndex = index)
        }
        val inProgressEpisodes = episodePlayback.mapIndexedNotNull { index, item ->
            mapPlaybackEpisode(item = item, fallbackIndex = index)
        }

        val completedEpisodes = episodeHistory
            .mapIndexedNotNull { index, item -> mapHistoryEpisode(item = item, fallbackIndex = index) }
            .distinctBy { entry -> entry.videoId }
        val completedMovies = movieHistory
            .mapIndexedNotNull { index, item -> mapHistoryMovie(item = item, fallbackIndex = index) }
            .distinctBy { entry -> entry.videoId }

        val mergedByVideoId = linkedMapOf<String, WatchProgressEntry>()
        (inProgressMovies + inProgressEpisodes + completedEpisodes + completedMovies).forEach { entry ->
            val existing = mergedByVideoId[entry.videoId]
            if (existing == null || entry.lastUpdatedEpochMs > existing.lastUpdatedEpochMs) {
                mergedByVideoId[entry.videoId] = entry
            }
        }

        mergedByVideoId.values
            .toList()
            .sortedByDescending { it.lastUpdatedEpochMs }
    }

    private fun mergeEntriesPreferRichMetadata(
        current: List<WatchProgressEntry>,
        hydrated: List<WatchProgressEntry>,
    ): List<WatchProgressEntry> {
        val merged = current.associateBy { it.videoId }.toMutableMap()
        hydrated.forEach { candidate ->
            val existing = merged[candidate.videoId]
            if (existing == null || shouldReplaceEntry(existing = existing, candidate = candidate)) {
                merged[candidate.videoId] = candidate
            }
        }
        return merged.values.toList()
    }

    private fun shouldReplaceEntry(existing: WatchProgressEntry, candidate: WatchProgressEntry): Boolean {
        if (candidate.lastUpdatedEpochMs != existing.lastUpdatedEpochMs) {
            return candidate.lastUpdatedEpochMs > existing.lastUpdatedEpochMs
        }
        return metadataScore(candidate) > metadataScore(existing)
    }

    private fun metadataScore(entry: WatchProgressEntry): Int {
        var score = 0
        if (!entry.logo.isNullOrBlank()) score += 1
        if (!entry.poster.isNullOrBlank()) score += 1
        if (!entry.background.isNullOrBlank()) score += 1
        if (!entry.episodeTitle.isNullOrBlank()) score += 1
        if (!entry.episodeThumbnail.isNullOrBlank()) score += 1
        if (!entry.pauseDescription.isNullOrBlank()) score += 1
        return score
    }

    private fun nextRefreshRequestId(): Long {
        refreshRequestId += 1L
        return refreshRequestId
    }

    private fun invalidateInFlightRefreshes() {
        refreshRequestId += 1L
    }

    private fun isLatestRefreshRequest(requestId: Long): Boolean = refreshRequestId == requestId

    private suspend fun hydrateEntriesFromAddonMeta(
        entries: List<WatchProgressEntry>,
    ): List<WatchProgressEntry> = coroutineScope {
        if (entries.isEmpty()) return@coroutineScope entries

        val uniqueContent = entries
            .map { entry -> entry.parentMetaType to entry.parentMetaId }
            .distinct()
            .take(METADATA_HYDRATION_LIMIT)

        val semaphore = Semaphore(METADATA_FETCH_CONCURRENCY)
        val metadataByContent = uniqueContent
            .map { (metaType, metaId) ->
                async {
                    semaphore.withPermit {
                        val normalizedType = when (metaType.lowercase()) {
                            "movie", "film" -> "movie"
                            else -> "series"
                        }
                        val meta = withTimeoutOrNull(METADATA_FETCH_TIMEOUT_MS) {
                            MetaDetailsRepository.fetch(type = normalizedType, id = metaId)
                        }
                        (metaType to metaId) to meta
                    }
                }
            }
            .awaitAll()
            .toMap()

        entries.map { entry ->
            val meta = metadataByContent[entry.parentMetaType to entry.parentMetaId] ?: return@map entry
            val episode = if (entry.seasonNumber != null && entry.episodeNumber != null) {
                meta.videos.firstOrNull { video ->
                    video.season == entry.seasonNumber && video.episode == entry.episodeNumber
                }
            } else {
                null
            }

            entry.copy(
                title = entry.title.takeIf { it.isNotBlank() } ?: meta.name,
                logo = entry.logo ?: meta.logo,
                poster = entry.poster ?: meta.poster,
                background = entry.background ?: meta.background,
                episodeTitle = entry.episodeTitle ?: episode?.title,
                episodeThumbnail = entry.episodeThumbnail ?: episode?.thumbnail,
                pauseDescription = entry.pauseDescription
                    ?: episode?.overview
                    ?: meta.description,
            )
        }
    }

    private fun mapPlaybackMovie(item: TraktPlaybackItem, fallbackIndex: Int): WatchProgressEntry? {
        val movie = item.movie ?: return null
        val parentMetaId = normalizeTraktContentId(movie.ids, fallback = movie.title)
        if (parentMetaId.isBlank()) return null

        val progressPercent = normalizeTraktProgressPercent(item.progress) ?: return null
        if (progressPercent <= 0f) return null
        val progressFraction = progressPercent / 100f
        val positionMs = (PLAYBACK_SYNTHETIC_DURATION_MS * progressFraction).toLong()

        return WatchProgressEntry(
            contentType = "movie",
            parentMetaId = parentMetaId,
            parentMetaType = "movie",
            videoId = parentMetaId,
            title = movie.title ?: parentMetaId,
            lastPositionMs = positionMs,
            durationMs = PLAYBACK_SYNTHETIC_DURATION_MS,
            lastUpdatedEpochMs = rankedTimestamp(item.pausedAt, fallbackIndex),
            isCompleted = false,
            progressPercent = progressPercent,
        )
    }

    private fun mapPlaybackEpisode(item: TraktPlaybackItem, fallbackIndex: Int): WatchProgressEntry? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val parentMetaId = normalizeTraktContentId(show.ids, fallback = show.title)
        if (parentMetaId.isBlank()) return null

        val progressPercent = normalizeTraktProgressPercent(item.progress) ?: return null
        if (progressPercent <= 0f) return null
        val progressFraction = progressPercent / 100f
        val positionMs = (PLAYBACK_SYNTHETIC_DURATION_MS * progressFraction).toLong()

        return WatchProgressEntry(
            contentType = "series",
            parentMetaId = parentMetaId,
            parentMetaType = "series",
            videoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = season,
                episodeNumber = number,
                fallbackVideoId = episode.ids?.trakt?.let { "trakt:$it" },
            ),
            title = show.title ?: parentMetaId,
            seasonNumber = season,
            episodeNumber = number,
            episodeTitle = episode.title,
            lastPositionMs = positionMs,
            durationMs = PLAYBACK_SYNTHETIC_DURATION_MS,
            lastUpdatedEpochMs = rankedTimestamp(item.pausedAt, fallbackIndex),
            isCompleted = false,
            progressPercent = progressPercent,
        )
    }

    private fun mapHistoryEpisode(item: TraktHistoryEpisodeItem, fallbackIndex: Int): WatchProgressEntry? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val parentMetaId = normalizeTraktContentId(show.ids, fallback = show.title)
        if (parentMetaId.isBlank()) return null

        return WatchProgressEntry(
            contentType = "series",
            parentMetaId = parentMetaId,
            parentMetaType = "series",
            videoId = buildPlaybackVideoId(
                parentMetaId = parentMetaId,
                seasonNumber = season,
                episodeNumber = number,
                fallbackVideoId = episode.ids?.trakt?.let { "trakt:$it" },
            ),
            title = show.title ?: parentMetaId,
            seasonNumber = season,
            episodeNumber = number,
            episodeTitle = episode.title,
            lastPositionMs = 1L,
            durationMs = 1L,
            lastUpdatedEpochMs = rankedTimestamp(item.watchedAt, fallbackIndex),
            isCompleted = true,
            progressPercent = 100f,
        )
    }

    private fun mapHistoryMovie(item: TraktHistoryMovieItem, fallbackIndex: Int): WatchProgressEntry? {
        val movie = item.movie ?: return null
        val parentMetaId = normalizeTraktContentId(movie.ids, fallback = movie.title)
        if (parentMetaId.isBlank()) return null

        return WatchProgressEntry(
            contentType = "movie",
            parentMetaId = parentMetaId,
            parentMetaType = "movie",
            videoId = parentMetaId,
            title = movie.title ?: parentMetaId,
            lastPositionMs = 1L,
            durationMs = 1L,
            lastUpdatedEpochMs = rankedTimestamp(item.watchedAt, fallbackIndex),
            isCompleted = true,
            progressPercent = 100f,
        )
    }

    private fun normalizeTraktProgressPercent(rawProgress: Float?): Float? {
        val value = rawProgress ?: return null
        if (!value.isFinite()) return null
        val normalized = when {
            value <= 1f -> value * 100f
            else -> value
        }
        return normalized.coerceIn(0f, 100f)
    }

    private fun rankedTimestamp(isoDate: String?, fallbackIndex: Int): Long {
        val compactDigits = isoDate
            ?.filter(Char::isDigit)
            ?.take(14)
            ?.takeIf { it.length >= 8 }
            ?.padEnd(14, '0')
            ?.toLongOrNull()
        if (compactDigits != null) return compactDigits

        return TraktPlatformClock.nowEpochMs() - (fallbackIndex * 1_000L)
    }
}

@Serializable
private data class TraktPlaybackItem(
    @SerialName("id") val id: Long? = null,
    @SerialName("progress") val progress: Float? = null,
    @SerialName("paused_at") val pausedAt: String? = null,
    @SerialName("movie") val movie: TraktMedia? = null,
    @SerialName("show") val show: TraktMedia? = null,
    @SerialName("episode") val episode: TraktEpisode? = null,
)

@Serializable
private data class TraktHistoryEpisodeItem(
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("show") val show: TraktMedia? = null,
    @SerialName("episode") val episode: TraktEpisode? = null,
)

@Serializable
private data class TraktHistoryMovieItem(
    @SerialName("watched_at") val watchedAt: String? = null,
    @SerialName("movie") val movie: TraktMedia? = null,
)

@Serializable
private data class TraktMedia(
    @SerialName("title") val title: String? = null,
    @SerialName("ids") val ids: TraktExternalIds? = null,
)

@Serializable
private data class TraktEpisode(
    @SerialName("title") val title: String? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("number") val number: Int? = null,
    @SerialName("ids") val ids: TraktExternalIds? = null,
)
