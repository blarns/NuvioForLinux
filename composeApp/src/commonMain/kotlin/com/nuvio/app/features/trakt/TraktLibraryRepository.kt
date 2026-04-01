package com.nuvio.app.features.trakt

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.addons.httpPostJsonWithHeaders
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.tmdb.TmdbService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://api.trakt.tv"
private const val WATCHLIST_KEY = "trakt:watchlist"
private const val PERSONAL_LIST_PREFIX = "trakt:list:"

data class TraktLibraryUiState(
    val listTabs: List<TraktListTab> = emptyList(),
    val entriesByList: Map<String, List<LibraryItem>> = emptyMap(),
    val allItems: List<LibraryItem> = emptyList(),
    val membershipByContent: Map<String, Set<String>> = emptyMap(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

object TraktLibraryRepository {
    private val log = Logger.withTag("TraktLibrary")
    private val json = Json { ignoreUnknownKeys = true }

    private val _uiState = MutableStateFlow(TraktLibraryUiState())
    val uiState: StateFlow<TraktLibraryUiState> = _uiState.asStateFlow()

    private var hasLoaded = false

    fun ensureLoaded() {
        if (hasLoaded) return
        hasLoaded = true
    }

    fun onProfileChanged() {
        hasLoaded = false
        _uiState.value = TraktLibraryUiState()
        ensureLoaded()
    }

    fun clearLocalState() {
        hasLoaded = false
        _uiState.value = TraktLibraryUiState()
    }

    fun currentListTabs(): List<TraktListTab> = _uiState.value.listTabs

    fun isInAnyList(itemId: String, itemType: String): Boolean {
        val key = contentKey(itemId, itemType)
        return _uiState.value.membershipByContent[key].orEmpty().isNotEmpty()
    }

    suspend fun refreshNow() {
        ensureLoaded()
        val headers = TraktAuthRepository.authorizedHeaders()
        if (headers == null) {
            _uiState.value = TraktLibraryUiState()
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        val result = runCatching {
            fetchSnapshot(headers)
        }.onFailure { error ->
            if (error is CancellationException) throw error
            log.w { "Failed to refresh Trakt library: ${error.message}" }
        }.getOrNull()

        if (result == null) {
            _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = "Failed to load Trakt library")
            return
        }

        _uiState.value = result.copy(isLoading = false, errorMessage = null)
    }

    suspend fun getMembershipSnapshot(item: LibraryItem): TraktMembershipSnapshot {
        ensureLoaded()
        if (_uiState.value.listTabs.isEmpty() && TraktAuthRepository.isAuthenticated.value) {
            refreshNow()
        }
        val itemMembership = _uiState.value.membershipByContent[contentKey(item.id, item.type)].orEmpty()
        val map = _uiState.value.listTabs.associate { tab ->
            tab.key to itemMembership.contains(tab.key)
        }
        return TraktMembershipSnapshot(listMembership = map)
    }

    suspend fun toggleWatchlist(item: LibraryItem) {
        ensureLoaded()
        val snapshot = getMembershipSnapshot(item)
        val currentlyInWatchlist = snapshot.listMembership[WATCHLIST_KEY] == true
        val desired = snapshot.listMembership.toMutableMap().apply {
            this[WATCHLIST_KEY] = !currentlyInWatchlist
        }
        applyMembershipChanges(item, TraktMembershipChanges(desiredMembership = desired))
    }

    suspend fun applyMembershipChanges(item: LibraryItem, changes: TraktMembershipChanges) {
        ensureLoaded()
        val headers = TraktAuthRepository.authorizedHeaders() ?: return
        val current = getMembershipSnapshot(item).listMembership
        val desired = changes.desiredMembership
        val keys = (current.keys + desired.keys).distinct()

        for (key in keys) {
            val before = current[key] == true
            val after = desired[key] == true
            if (before == after) continue

            if (key == WATCHLIST_KEY) {
                if (after) {
                    addToWatchlist(headers, item)
                } else {
                    removeFromWatchlist(headers, item)
                }
            } else {
                val listId = key.removePrefix(PERSONAL_LIST_PREFIX)
                if (listId == key || listId.isBlank()) continue
                if (after) {
                    addToPersonalList(headers, listId, item)
                } else {
                    removeFromPersonalList(headers, listId, item)
                }
            }
        }

        refreshNow()
    }

    private suspend fun fetchSnapshot(headers: Map<String, String>): TraktLibraryUiState = withContext(Dispatchers.Default) {
        val watchlistTabs = listOf(
            TraktListTab(
                key = WATCHLIST_KEY,
                title = "Watchlist",
                type = TraktListType.WATCHLIST,
            ),
        )

        val personalLists = fetchPersonalLists(headers)
        val allTabs = watchlistTabs + personalLists

        val entriesByList = linkedMapOf<String, List<LibraryItem>>()
        entriesByList[WATCHLIST_KEY] = fetchWatchlistItems(headers)

        personalLists.forEach { tab ->
            val listId = tab.traktListId?.toString() ?: return@forEach
            entriesByList[tab.key] = fetchPersonalListItems(headers, listId)
        }

        val membershipByContent = mutableMapOf<String, MutableSet<String>>()
        entriesByList.forEach { (listKey, entries) ->
            entries.forEach { entry ->
                membershipByContent
                    .getOrPut(contentKey(entry.id, entry.type)) { mutableSetOf() }
                    .add(listKey)
            }
        }

        val allItems = entriesByList.values
            .flatten()
            .distinctBy { contentKey(it.id, it.type) }
            .sortedByDescending { it.savedAtEpochMs }

        TraktLibraryUiState(
            listTabs = allTabs,
            entriesByList = entriesByList,
            allItems = allItems,
            membershipByContent = membershipByContent.mapValues { it.value.toSet() },
        )
    }

    private suspend fun fetchPersonalLists(headers: Map<String, String>): List<TraktListTab> {
        val payload = httpGetTextWithHeaders(
            url = "$BASE_URL/users/me/lists",
            headers = headers,
        )
        val lists = json.decodeFromString<List<TraktListSummaryDto>>(payload)
        return lists.mapNotNull { list ->
            val traktId = list.ids?.trakt ?: return@mapNotNull null
            TraktListTab(
                key = "$PERSONAL_LIST_PREFIX$traktId",
                title = list.name?.ifBlank { null } ?: "List $traktId",
                type = TraktListType.PERSONAL,
                traktListId = traktId,
                slug = list.ids.slug,
                description = list.description,
            )
        }
    }

    private suspend fun fetchWatchlistItems(headers: Map<String, String>): List<LibraryItem> {
        val moviesPayload = httpGetTextWithHeaders(
            url = "$BASE_URL/sync/watchlist/movies?extended=full,images",
            headers = headers,
        )
        val showsPayload = httpGetTextWithHeaders(
            url = "$BASE_URL/sync/watchlist/shows?extended=full,images",
            headers = headers,
        )
        val movieItems = json.decodeFromString<List<TraktListItemDto>>(moviesPayload)
        val showItems = json.decodeFromString<List<TraktListItemDto>>(showsPayload)
        return (movieItems + showItems)
            .mapNotNull(::mapToLibraryItem)
            .sortedByDescending { it.savedAtEpochMs }
    }

    private suspend fun fetchPersonalListItems(
        headers: Map<String, String>,
        listId: String,
    ): List<LibraryItem> {
        val moviesPayload = httpGetTextWithHeaders(
            url = "$BASE_URL/users/me/lists/$listId/items/movies?extended=full,images",
            headers = headers,
        )
        val showsPayload = httpGetTextWithHeaders(
            url = "$BASE_URL/users/me/lists/$listId/items/shows?extended=full,images",
            headers = headers,
        )

        val movieItems = json.decodeFromString<List<TraktListItemDto>>(moviesPayload)
        val showItems = json.decodeFromString<List<TraktListItemDto>>(showsPayload)
        return (movieItems + showItems)
            .mapNotNull(::mapToLibraryItem)
            .sortedByDescending { it.savedAtEpochMs }
    }

    private suspend fun addToWatchlist(headers: Map<String, String>, item: LibraryItem) {
        val body = buildMutationBody(item) ?: return
        httpPostJsonWithHeaders(
            url = "$BASE_URL/sync/watchlist",
            body = body,
            headers = headers,
        )
    }

    private suspend fun removeFromWatchlist(headers: Map<String, String>, item: LibraryItem) {
        val body = buildMutationBody(item) ?: return
        httpPostJsonWithHeaders(
            url = "$BASE_URL/sync/watchlist/remove",
            body = body,
            headers = headers,
        )
    }

    private suspend fun addToPersonalList(headers: Map<String, String>, listId: String, item: LibraryItem) {
        val body = buildMutationBody(item) ?: return
        httpPostJsonWithHeaders(
            url = "$BASE_URL/users/me/lists/$listId/items",
            body = body,
            headers = headers,
        )
    }

    private suspend fun removeFromPersonalList(headers: Map<String, String>, listId: String, item: LibraryItem) {
        val body = buildMutationBody(item) ?: return
        httpPostJsonWithHeaders(
            url = "$BASE_URL/users/me/lists/$listId/items/remove",
            body = body,
            headers = headers,
        )
    }

    private suspend fun buildMutationBody(item: LibraryItem): String? {
        val type = normalizeType(item.type)
        val ids = resolveIds(item)

        val request = if (type == "movie") {
            TraktListItemsMutationRequestDto(
                movies = listOf(
                    TraktListMovieRequestItemDto(
                        title = item.name,
                        year = extractYear(item.releaseInfo),
                        ids = ids,
                    ),
                ),
            )
        } else {
            TraktListItemsMutationRequestDto(
                shows = listOf(
                    TraktListShowRequestItemDto(
                        title = item.name,
                        year = extractYear(item.releaseInfo),
                        ids = ids,
                    ),
                ),
            )
        }
        return json.encodeToString(request)
    }

    private suspend fun resolveIds(item: LibraryItem): TraktIdsDto? {
        val rawId = item.id.trim()
        val imdb = imdbRegex.find(rawId)?.value
        val tmdbFromId = rawId.removePrefix("tmdb:").toIntOrNull()
        val traktFromId = rawId.removePrefix("trakt:").toIntOrNull()

        val normalizedType = if (normalizeType(item.type) == "movie") "movie" else "tv"
        val resolvedImdb = imdb ?: tmdbFromId?.let { TmdbService.tmdbToImdb(it, normalizedType) }

        if (resolvedImdb.isNullOrBlank() && tmdbFromId == null && traktFromId == null) {
            return null
        }

        return TraktIdsDto(
            imdb = resolvedImdb,
            tmdb = tmdbFromId,
            trakt = traktFromId,
        )
    }

    private fun mapToLibraryItem(item: TraktListItemDto): LibraryItem? {
        val movie = item.movie
        val show = item.show
        val media = movie ?: show ?: return null
        val type = if (movie != null) "movie" else "series"
        val ids = media.ids

        val id = ids?.imdb
            ?: ids?.tmdb?.let { "tmdb:$it" }
            ?: ids?.trakt?.let { "trakt:$it" }
            ?: return null

        val poster = media.images?.poster?.firstOrNull()
        val banner = media.images?.fanart?.firstOrNull() ?: media.images?.banner?.firstOrNull()
        val logo = media.images?.logo?.firstOrNull()

        val savedAt = item.listedAt?.takeIf { it.isNotBlank() }?.hashCode()?.toLong()?.let { kotlin.math.abs(it) }
            ?: TraktPlatformClock.nowEpochMs()

        return LibraryItem(
            id = id,
            type = type,
            name = media.title?.ifBlank { id } ?: id,
            poster = poster,
            banner = banner,
            logo = logo,
            description = media.overview,
            releaseInfo = media.year?.toString(),
            imdbRating = media.rating?.toString(),
            genres = media.genres.orEmpty(),
            savedAtEpochMs = savedAt,
        )
    }

    private fun contentKey(itemId: String, itemType: String): String =
        "${normalizeType(itemType)}:${itemId.trim()}"

    private fun normalizeType(type: String): String {
        val normalized = type.trim().lowercase()
        return when (normalized) {
            "movie", "film" -> "movie"
            "tv", "show", "series", "tvshow" -> "series"
            else -> normalized
        }
    }

    private fun extractYear(releaseInfo: String?): Int? {
        if (releaseInfo.isNullOrBlank()) return null
        val yearText = Regex("(19|20)\\d{2}").find(releaseInfo)?.value ?: return null
        return yearText.toIntOrNull()
    }

    private val imdbRegex = Regex("tt\\d+")
}

@Serializable
private data class TraktListSummaryDto(
    val name: String? = null,
    val description: String? = null,
    val ids: TraktListIdsDto? = null,
)

@Serializable
private data class TraktListIdsDto(
    val trakt: Long? = null,
    val slug: String? = null,
)

@Serializable
private data class TraktListItemDto(
    @SerialName("listed_at") val listedAt: String? = null,
    val movie: TraktMediaDto? = null,
    val show: TraktMediaDto? = null,
)

@Serializable
private data class TraktMediaDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIdsDto? = null,
    val overview: String? = null,
    val rating: Double? = null,
    val genres: List<String>? = null,
    val images: TraktImagesDto? = null,
)

@Serializable
private data class TraktImagesDto(
    val fanart: List<String>? = null,
    val poster: List<String>? = null,
    val logo: List<String>? = null,
    val banner: List<String>? = null,
)

@Serializable
private data class TraktIdsDto(
    val trakt: Int? = null,
    val imdb: String? = null,
    val tmdb: Int? = null,
)

@Serializable
private data class TraktListItemsMutationRequestDto(
    val movies: List<TraktListMovieRequestItemDto>? = null,
    val shows: List<TraktListShowRequestItemDto>? = null,
)

@Serializable
private data class TraktListMovieRequestItemDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIdsDto? = null,
)

@Serializable
private data class TraktListShowRequestItemDto(
    val title: String? = null,
    val year: Int? = null,
    val ids: TraktIdsDto? = null,
)

