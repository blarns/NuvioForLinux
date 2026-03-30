package com.nuvio.app.features.details

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonManifest
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.httpGetText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object MetaDetailsRepository {
    private val log = Logger.withTag("MetaDetailsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(MetaDetailsUiState())
    val uiState: StateFlow<MetaDetailsUiState> = _uiState.asStateFlow()
    private var activeRequestKey: String? = null
    private val cachedMetaByRequestKey = mutableMapOf<String, MetaDetails>()

    fun load(type: String, id: String) {
        log.d { "load() called — type=$type id=$id" }
        val requestKey = "$type:$id"
        val currentState = _uiState.value

        cachedMetaByRequestKey[requestKey]?.let { cachedMeta ->
            _uiState.value = MetaDetailsUiState(meta = cachedMeta)
            activeRequestKey = requestKey
            return
        }

        if (currentState.meta?.type == type && currentState.meta.id == id && !currentState.isLoading) {
            log.d { "Skipping reload for cached meta — type=$type id=$id" }
            activeRequestKey = requestKey
            return
        }

        if (currentState.isLoading && activeRequestKey == requestKey) {
            log.d { "Request already in flight — type=$type id=$id" }
            return
        }

        activeRequestKey = requestKey
        _uiState.value = MetaDetailsUiState(isLoading = true)

        scope.launch {
            val manifests = AddonRepository.uiState.value.addons
                .mapNotNull { it.manifest }
                .filter { manifest ->
                    manifest.resources.any { resource ->
                        resource.name == "meta" &&
                            resource.types.contains(type) &&
                            (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { id.startsWith(it) })
                    }
                }

            if (manifests.isEmpty()) {
                log.w { "No addon provides meta for type=$type id=$id" }
                _uiState.value = MetaDetailsUiState(
                    errorMessage = "No addon provides meta for this content.",
                )
                activeRequestKey = null
                return@launch
            }

            for (manifest in manifests) {
                val result = tryFetchMeta(manifest, type, id)
                if (result != null) {
                    cachedMetaByRequestKey[requestKey] = result
                    _uiState.value = MetaDetailsUiState(meta = result)
                    activeRequestKey = requestKey
                    return@launch
                }
            }

            _uiState.value = MetaDetailsUiState(
                errorMessage = "Could not load details from any addon.",
            )
            activeRequestKey = null
        }
    }

    fun clear() {
        activeRequestKey = null
        cachedMetaByRequestKey.clear()
        _uiState.value = MetaDetailsUiState()
    }

    suspend fun fetch(type: String, id: String): MetaDetails? {
        val requestKey = "$type:$id"
        cachedMetaByRequestKey[requestKey]?.let { return it }

        val manifests = AddonRepository.uiState.value.addons
            .mapNotNull { it.manifest }
            .filter { manifest ->
                manifest.resources.any { resource ->
                    resource.name == "meta" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() || resource.idPrefixes.any { id.startsWith(it) })
                }
            }

        for (manifest in manifests) {
            val result = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                tryFetchMeta(manifest, type, id)
            }
            if (result != null) {
                cachedMetaByRequestKey[requestKey] = result
                return result
            }
        }

        return null
    }

    private const val FETCH_TIMEOUT_MS = 5_000L

    private suspend fun tryFetchMeta(
        manifest: AddonManifest,
        type: String,
        id: String,
    ): MetaDetails? {
        return try {
            val baseUrl = manifest.transportUrl
                .substringBefore("?")
                .removeSuffix("/manifest.json")
            val url = "$baseUrl/meta/$type/$id.json"
            log.d { "Fetching meta from: $url" }
            val payload = httpGetText(url)
            log.d { "Raw payload length=${payload.length}, first 500 chars: ${payload.take(500)}" }
            val result = MetaDetailsParser.parse(payload)
            log.d { "Parsed meta: type=${result.type}, name=${result.name}, videos=${result.videos.size}" }
            if (result.videos.isNotEmpty()) {
                val first = result.videos.first()
                log.d { "First video: id=${first.id} title=${first.title} s=${first.season} e=${first.episode} embeddedStreams=${first.streams.size}" }
            }
            result
        } catch (e: Throwable) {
            log.e(e) { "Failed to fetch/parse meta from ${manifest.transportUrl}" }
            null
        }
    }

   
    fun findEmbeddedStreams(videoId: String): List<com.nuvio.app.features.streams.StreamItem> {
        val meta = _uiState.value.meta ?: return emptyList()
        val videosWithStreams = meta.videos.filter { it.streams.isNotEmpty() }
        if (videosWithStreams.isEmpty()) return emptyList()

        val directMatch = videosWithStreams.firstOrNull { it.id == videoId }
        if (directMatch != null) return directMatch.streams

        val parts = videoId.split(":")
        if (parts.size >= 3) {
            val season = parts[parts.size - 2].toIntOrNull()
            val episode = parts[parts.size - 1].toIntOrNull()
            if (season != null && episode != null) {
                val episodeMatch = videosWithStreams.firstOrNull { it.season == season && it.episode == episode }
                if (episodeMatch != null) return episodeMatch.streams
            }
        }

        val prefixMatch = videosWithStreams.firstOrNull { it.id.startsWith("$videoId:") }
        if (prefixMatch != null) return prefixMatch.streams

        if (videoId == meta.id && videosWithStreams.size == 1) {
            return videosWithStreams.first().streams
        }

        if (videoId == meta.id && videosWithStreams.isNotEmpty()) {
            return videosWithStreams.flatMap { it.streams }
        }

        return emptyList()
    }
}
