package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object StreamsRepository {
    private val log = Logger.withTag("StreamsRepo")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _uiState = MutableStateFlow(StreamsUiState())
    val uiState: StateFlow<StreamsUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequestKey: String? = null

  
    fun load(type: String, videoId: String, season: Int? = null, episode: Int? = null) {
        load(type = type, videoId = videoId, season = season, episode = episode, forceRefresh = false)
    }

    fun reload(type: String, videoId: String, season: Int? = null, episode: Int? = null) {
        load(type = type, videoId = videoId, season = season, episode = episode, forceRefresh = true)
    }

    private fun load(type: String, videoId: String, season: Int?, episode: Int?, forceRefresh: Boolean) {
        val requestKey = "$type::$videoId::$season::$episode"
        val currentState = _uiState.value
        if (
            !forceRefresh &&
            activeRequestKey == requestKey &&
            (currentState.groups.isNotEmpty() || currentState.emptyStateReason != null || currentState.isAnyLoading)
        ) {
            log.d { "Skipping stream reload for unchanged request type=$type id=$videoId" }
            return
        }

        activeRequestKey = requestKey
        activeJob?.cancel()
        _uiState.value = StreamsUiState()

        val embeddedStreams = MetaDetailsRepository.findEmbeddedStreams(videoId)
        if (embeddedStreams.isNotEmpty()) {
            log.d { "Using ${embeddedStreams.size} embedded streams for type=$type id=$videoId" }
            val group = AddonStreamGroup(
                addonName = embeddedStreams.first().addonName,
                addonId = "embedded",
                streams = embeddedStreams,
                isLoading = false,
            )
            _uiState.value = StreamsUiState(
                groups = listOf(group),
                activeAddonIds = setOf("embedded"),
                isAnyLoading = false,
            )
            return
        }

        val installedAddons = AddonRepository.uiState.value.addons
        PluginRepository.initialize()
        val pluginScrapers = PluginRepository.getEnabledScrapersForType(type)

        if (installedAddons.isEmpty() && pluginScrapers.isEmpty()) {
            _uiState.value = StreamsUiState(
                isAnyLoading = false,
                emptyStateReason = StreamsEmptyStateReason.NoAddonsInstalled,
            )
            return
        }

        val streamAddons = installedAddons
            .mapNotNull { it.manifest }
            .filter { manifest ->
                manifest.resources.any { resource ->
                    resource.name == "stream" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() ||
                            resource.idPrefixes.any { videoId.startsWith(it) })
                }
            }

        log.d { "Found ${streamAddons.size} addons for stream type=$type id=$videoId" }

        if (streamAddons.isEmpty() && pluginScrapers.isEmpty()) {
            _uiState.value = StreamsUiState(
                isAnyLoading = false,
                emptyStateReason = StreamsEmptyStateReason.NoCompatibleAddons,
            )
            return
        }

        // Initialise loading placeholders
        val initialGroups = streamAddons.map { manifest ->
            AddonStreamGroup(
                addonName = manifest.name,
                addonId = manifest.id,
                streams = emptyList(),
                isLoading = true,
            )
        } + pluginScrapers.map { scraper ->
            AddonStreamGroup(
                addonName = scraper.name,
                addonId = "plugin:${scraper.id}",
                streams = emptyList(),
                isLoading = true,
            )
        }
        _uiState.value = StreamsUiState(
            groups = initialGroups,
            activeAddonIds = initialGroups.map { it.addonId }.toSet(),
            isAnyLoading = true,
            emptyStateReason = null,
        )

        activeJob = scope.launch {
            val addonJobs = streamAddons.map { manifest ->
                async {
                    val encodedId = videoId.encodeForPath()
                    val baseUrl = manifest.transportUrl
                        .substringBefore("?")
                        .removeSuffix("/manifest.json")
                    val url = "$baseUrl/stream/$type/$encodedId.json"
                    log.d { "Fetching streams from: $url" }

                    runCatching {
                        val payload = httpGetText(url)
                        StreamParser.parse(
                            payload = payload,
                            addonName = manifest.name,
                            addonId = manifest.id,
                        )
                    }.fold(
                        onSuccess = { streams ->
                            log.d { "Got ${streams.size} streams from ${manifest.name}" }
                            AddonStreamGroup(
                                addonName = manifest.name,
                                addonId = manifest.id,
                                streams = streams,
                                isLoading = false,
                            )
                        },
                        onFailure = { err ->
                            log.w(err) { "Failed to fetch streams from ${manifest.name}" }
                            AddonStreamGroup(
                                addonName = manifest.name,
                                addonId = manifest.id,
                                streams = emptyList(),
                                isLoading = false,
                                error = err.message,
                            )
                        },
                    )
                }
            }

            val pluginJobs = pluginScrapers.map { scraper ->
                async {
                    PluginRepository.executeScraper(
                        scraper = scraper,
                        tmdbId = videoId.toPluginTmdbId(),
                        mediaType = type,
                        season = season,
                        episode = episode,
                    ).fold(
                        onSuccess = { results ->
                            AddonStreamGroup(
                                addonName = scraper.name,
                                addonId = "plugin:${scraper.id}",
                                streams = results.map { it.toStreamItem(scraper) },
                                isLoading = false,
                            )
                        },
                        onFailure = { error ->
                            AddonStreamGroup(
                                addonName = scraper.name,
                                addonId = "plugin:${scraper.id}",
                                streams = emptyList(),
                                isLoading = false,
                                error = error.message,
                            )
                        },
                    )
                }
            }

            val jobs = addonJobs + pluginJobs

            // Collect results as they arrive and update state incrementally
            jobs.forEach { deferred ->
                val result = deferred.await()
                _uiState.update { current ->
                    val updated = current.groups.map { group ->
                        if (group.addonId == result.addonId) result else group
                    }
                    val anyLoading = updated.any { it.isLoading }
                    current.copy(
                        groups = updated,
                        isAnyLoading = anyLoading,
                        emptyStateReason = updated.toEmptyStateReason(anyLoading),
                    )
                }
            }
        }
    }

    fun selectFilter(addonId: String?) {
        _uiState.update { it.copy(selectedFilter = addonId) }
    }

    fun clear() {
        activeJob?.cancel()
        activeRequestKey = null
        _uiState.value = StreamsUiState()
    }

    // Encode id segment so colons and slashes don't break URL path parsing on addons
    private fun String.encodeForPath(): String =
        replace("%", "%25").replace(" ", "%20")
}

private fun List<AddonStreamGroup>.toEmptyStateReason(anyLoading: Boolean): StreamsEmptyStateReason? {
    if (anyLoading || any { it.streams.isNotEmpty() }) {
        return null
    }

    return if (isNotEmpty() && all { !it.error.isNullOrBlank() }) {
        StreamsEmptyStateReason.StreamFetchFailed
    } else {
        StreamsEmptyStateReason.NoStreamsFound
    }
}

private fun String.toPluginTmdbId(): String {
    return when {
        startsWith("tmdb:") -> removePrefix("tmdb:").substringBefore(":").ifBlank { this }
        startsWith("tmdb/") -> removePrefix("tmdb/").substringBefore('/').ifBlank { this }
        else -> this
    }
}

private fun PluginRuntimeResult.toStreamItem(scraper: PluginScraper): StreamItem {
    val subtitleParts = listOfNotNull(
        quality?.takeIf { it.isNotBlank() },
        size?.takeIf { it.isNotBlank() },
        language?.takeIf { it.isNotBlank() },
    )
    return StreamItem(
        name = name ?: title,
        description = subtitleParts.joinToString(" • ").ifBlank { null },
        url = url,
        infoHash = infoHash,
        addonName = scraper.name,
        addonId = "plugin:${scraper.id}",
    )
}
