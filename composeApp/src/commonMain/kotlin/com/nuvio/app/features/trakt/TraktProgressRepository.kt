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

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
    }

    fun onProfileChanged() {
        hasLoaded = false
        _uiState.value = TraktProgressUiState()
        ensureLoaded()
    }

    fun clearLocalState() {
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
        val headers = TraktAuthRepository.authorizedHeaders()
        if (headers == null) {
            _uiState.value = TraktProgressUiState()
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        val snapshot = runCatching {
            fetchSnapshot(headers)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to refresh Trakt progress: ${error.message}" }
        }.getOrNull()

        if (snapshot == null) {
            _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to load Trakt progress")
            return
        }

        _uiState.value = snapshot.copy(isLoading = false, errorMessage = null)
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

    private suspend fun fetchSnapshot(headers: Map<String, String>): TraktProgressUiState = withContext(Dispatchers.Default) {
        val moviesPayload = httpGetTextWithHeaders(
            url = "$BASE_URL/sync/playback/movies",
            headers = headers,
        )
        val episodesPayload = httpGetTextWithHeaders(
            url = "$BASE_URL/sync/playback/episodes",
            headers = headers,
        )
        val historyPayload = httpGetTextWithHeaders(
            url = "$BASE_URL/sync/history/episodes?limit=$HISTORY_LIMIT",
            headers = headers,
        )
        val movieHistoryPayload = httpGetTextWithHeaders(
            url = "$BASE_URL/sync/history/movies?limit=$HISTORY_LIMIT",
            headers = headers,
        )

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

        val hydrated = hydrateEntriesFromAddonMeta(mergedByVideoId.values.toList())

        TraktProgressUiState(
            entries = hydrated.sortedByDescending { it.lastUpdatedEpochMs },
        )
    }

    private suspend fun hydrateEntriesFromAddonMeta(
        entries: List<WatchProgressEntry>,
    ): List<WatchProgressEntry> = coroutineScope {
        if (entries.isEmpty()) return@coroutineScope entries

        val uniqueContent = entries
            .map { entry -> entry.parentMetaType to entry.parentMetaId }
            .distinct()

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

        val progressFraction = ((item.progress ?: 0f).coerceIn(0f, 100f) / 100f)
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
        )
    }

    private fun mapPlaybackEpisode(item: TraktPlaybackItem, fallbackIndex: Int): WatchProgressEntry? {
        val show = item.show ?: return null
        val episode = item.episode ?: return null
        val season = episode.season ?: return null
        val number = episode.number ?: return null

        val parentMetaId = normalizeTraktContentId(show.ids, fallback = show.title)
        if (parentMetaId.isBlank()) return null

        val progressFraction = ((item.progress ?: 0f).coerceIn(0f, 100f) / 100f)
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
        )
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
