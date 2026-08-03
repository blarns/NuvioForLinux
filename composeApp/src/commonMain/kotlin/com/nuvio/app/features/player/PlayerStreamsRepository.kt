package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.debrid.DirectDebridStreamPreparer
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.features.streams.AddonStreamWarmupRepository
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamAutoPlaySelector
import com.nuvio.app.features.streams.StreamBadgePresentation
import com.nuvio.app.features.streams.StreamBadgeSettingsRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.core.concurrency.NuvioBlockingDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Dedicated stream fetcher for use inside the player (sources & episodes panels).
 * Uses its own state so it doesn't interfere with the main [StreamsRepository].
 */
object PlayerStreamsRepository {
    private val log = Logger.withTag("PlayerStreamsRepo")

    // Not Dispatchers.Default: scraper plugins block a thread per in-flight request, and this
    // fan-out must keep a thread free to publish results while they do.
    private val scope = CoroutineScope(SupervisorJob() + NuvioBlockingDispatcher)

    // source panel
    private val _sourceState = MutableStateFlow(StreamsUiState())
    val sourceState: StateFlow<StreamsUiState> = _sourceState.asStateFlow()
    private var sourceJob: Job? = null
    private var sourceRequestKey: String? = null

    // episode streams panel
    private val _episodeStreamsState = MutableStateFlow(StreamsUiState())
    val episodeStreamsState: StateFlow<StreamsUiState> = _episodeStreamsState.asStateFlow()
    private var episodeStreamsJob: Job? = null
    private var episodeStreamsRequestKey: String? = null

    fun loadSources(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        forceRefresh: Boolean = false,
    ) {
        fetchStreams(
            type = type,
            videoId = videoId,
            season = season,
            episode = episode,
            forceRefresh = forceRefresh,
            stateFlow = _sourceState,
            requestKeyHolder = { sourceRequestKey },
            setRequestKey = { sourceRequestKey = it },
            jobHolder = { sourceJob },
            setJob = { sourceJob = it },
        )
    }

    fun loadEpisodeStreams(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        forceRefresh: Boolean = false,
    ) {
        fetchStreams(
            type = type,
            videoId = videoId,
            season = season,
            episode = episode,
            forceRefresh = forceRefresh,
            stateFlow = _episodeStreamsState,
            requestKeyHolder = { episodeStreamsRequestKey },
            setRequestKey = { episodeStreamsRequestKey = it },
            jobHolder = { episodeStreamsJob },
            setJob = { episodeStreamsJob = it },
        )
    }

    fun selectSourceFilter(addonId: String?) {
        _sourceState.update { it.copy(selectedFilter = addonId) }
    }

    fun selectEpisodeStreamsFilter(addonId: String?) {
        _episodeStreamsState.update { it.copy(selectedFilter = addonId) }
    }

    fun clearEpisodeStreams() {
        episodeStreamsJob?.cancel()
        episodeStreamsRequestKey = null
        _episodeStreamsState.value = StreamsUiState()
    }

    fun clearAll() {
        sourceJob?.cancel()
        sourceRequestKey = null
        _sourceState.value = StreamsUiState()
        clearEpisodeStreams()
    }

    private fun fetchStreams(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        forceRefresh: Boolean,
        stateFlow: MutableStateFlow<StreamsUiState>,
        requestKeyHolder: () -> String?,
        setRequestKey: (String?) -> Unit,
        jobHolder: () -> Job?,
        setJob: (Job) -> Unit,
    ) {
        val requestKey = "$type::$videoId::$season::$episode"
        val current = stateFlow.value
        if (
            !forceRefresh &&
            requestKeyHolder() == requestKey &&
            (current.groups.isNotEmpty() || current.emptyStateReason != null || current.isAnyLoading)
        ) {
            return
        }

        setRequestKey(requestKey)
        jobHolder()?.cancel()
        stateFlow.value = StreamsUiState()

        val streamBadgeRules = StreamBadgeSettingsRepository.snapshot()
        val embeddedStreams = MetaDetailsRepository.findEmbeddedStreams(videoId)
        if (embeddedStreams.isNotEmpty()) {
            log.d { "Using ${embeddedStreams.size} embedded streams for type=$type id=$videoId" }
            val group = AddonStreamGroup(
                addonName = embeddedStreams.first().addonName,
                addonId = "embedded",
                streams = embeddedStreams,
                isLoading = false,
            )
            val presentedGroup = StreamBadgePresentation.apply(
                groups = listOf(group),
                rules = streamBadgeRules,
            ).firstOrNull() ?: group
            stateFlow.value = StreamsUiState(
                groups = listOf(presentedGroup),
                activeAddonIds = setOf("embedded"),
                isAnyLoading = false,
            )
            return
        }

        val installedAddons = AddonRepository.uiState.value.addons.enabledAddons()
        val installedAddonNames = installedAddons.map { it.displayTitle }.toSet()
        PlayerSettingsRepository.ensureLoaded()
        val playerSettings = PlayerSettingsRepository.uiState.value
        val debridSettings = DebridSettingsRepository.snapshot()
        val pluginScrapers = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.initialize()
            PluginRepository.getEnabledScrapersForType(type)
        } else {
            emptyList()
        }

        if (installedAddons.isEmpty() && pluginScrapers.isEmpty()) {
            stateFlow.value = StreamsUiState(
                isAnyLoading = false,
                emptyStateReason = com.nuvio.app.features.streams.StreamsEmptyStateReason.NoAddonsInstalled,
            )
            return
        }

        val streamAddons = installedAddons
            .mapNotNull { addon ->
                val manifest = addon.manifest ?: return@mapNotNull null
                val supportsRequestedStream = manifest.resources.any { resource ->
                    resource.name == "stream" &&
                        resource.types.contains(type) &&
                        (resource.idPrefixes.isEmpty() ||
                            resource.idPrefixes.any { videoId.startsWith(it) })
                }
                if (!supportsRequestedStream) return@mapNotNull null

                PlayerInstalledStreamAddonTarget(
                    addonName = addon.displayTitle.ifBlank { manifest.name },
                    addonId = addon.streamAddonInstanceId(manifest.id),
                    manifest = manifest,
                )
            }

        if (streamAddons.isEmpty() && pluginScrapers.isEmpty()) {
            stateFlow.value = StreamsUiState(
                isAnyLoading = false,
                emptyStateReason = com.nuvio.app.features.streams.StreamsEmptyStateReason.NoCompatibleAddons,
            )
            return
        }

        val installedAddonOrder = streamAddons.map { it.addonName }
        val warmedAddonGroups = if (forceRefresh) {
            emptyMap()
        } else {
            AddonStreamWarmupRepository
                .cachedGroups(type = type, videoId = videoId, season = season, episode = episode)
                .orEmpty()
                .associateBy { it.addonId }
        }
        val warmedAddonIds = warmedAddonGroups.keys
        val initialGroups = StreamAutoPlaySelector.orderAddonStreams(streamAddons.map { addon ->
            warmedAddonGroups[addon.addonId] ?: AddonStreamGroup(
                addonName = addon.addonName,
                addonId = addon.addonId,
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
        }, installedAddonOrder)
        val isInitiallyLoading = initialGroups.any { it.isLoading }
        stateFlow.value = StreamsUiState(
            groups = initialGroups,
            activeAddonIds = initialGroups.map { it.addonId }.toSet(),
            isAnyLoading = isInitiallyLoading,
        )

        val job = scope.launch {
            val pendingStreamAddons = streamAddons.filterNot { it.addonId in warmedAddonIds }
            val installedAddonIds = streamAddons.map { it.addonId }.toSet()
            val debridAvailabilityJobs = mutableListOf<Job>()
            fun emptyStateReason(groups: List<AddonStreamGroup>, anyLoading: Boolean) =
                if (!anyLoading && groups.all { it.streams.isEmpty() }) {
                    if (groups.all { !it.error.isNullOrBlank() }) {
                        com.nuvio.app.features.streams.StreamsEmptyStateReason.StreamFetchFailed
                    } else {
                        com.nuvio.app.features.streams.StreamsEmptyStateReason.NoStreamsFound
                    }
                } else {
                    null
                }

            fun presentStreamGroup(group: AddonStreamGroup): AddonStreamGroup {
                val badgeGroup = StreamBadgePresentation.apply(
                    groups = listOf(group),
                    rules = streamBadgeRules,
                ).firstOrNull() ?: group
                return DebridStreamPresentation.apply(
                    groups = listOf(badgeGroup),
                    settings = debridSettings,
                ).firstOrNull() ?: badgeGroup
            }

            fun publishStreamGroup(group: AddonStreamGroup) {
                stateFlow.update { current ->
                    val updated = StreamAutoPlaySelector.orderAddonStreams(
                        groups = current.groups.map { currentGroup ->
                            if (currentGroup.addonId == group.addonId) group else currentGroup
                        },
                        installedOrder = installedAddonOrder,
                    )
                    val anyLoading = updated.any { it.isLoading }
                    current.copy(
                        groups = updated,
                        isAnyLoading = anyLoading,
                        emptyStateReason = emptyStateReason(updated, anyLoading),
                    )
                }
            }

            fun publishStreamGroupAfterCacheCheck(group: AddonStreamGroup) {
                if (group.addonId !in installedAddonIds || group.streams.isEmpty()) {
                    publishStreamGroup(presentStreamGroup(group))
                    return
                }

                val eligibleGroupIds = setOf(group.addonId)
                val shouldWaitForCacheCheck = LocalDebridAvailabilityService.hasPendingCacheCheck(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                )
                if (!shouldWaitForCacheCheck) {
                    publishStreamGroup(presentStreamGroup(group))
                    return
                }

                val checkingGroup = LocalDebridAvailabilityService.markChecking(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                ).firstOrNull() ?: group

                val availabilityJob = launch {
                    val availabilityGroup = LocalDebridAvailabilityService.annotateCachedAvailability(
                        groups = listOf(checkingGroup),
                        eligibleGroupIds = eligibleGroupIds,
                    ).firstOrNull() ?: checkingGroup
                    publishStreamGroup(presentStreamGroup(availabilityGroup))
                }
                debridAvailabilityJobs += availabilityJob
            }

            // Every source is described uniformly so the fan-in below can always account for
            // exactly one result per source, whatever happens inside the fetch.
            val sources = pendingStreamAddons.map { addon ->
                PlayerStreamSource(addon.addonName, addon.addonId) {
                    val url = buildAddonResourceUrl(
                        manifestUrl = addon.manifest.transportUrl,
                        resource = "stream",
                        type = type,
                        id = videoId,
                    )
                    StreamParser.parse(httpGetText(url), addon.addonName, addon.addonId)
                }
            } + pluginScrapers.map { scraper ->
                PlayerStreamSource(scraper.name, "plugin:${scraper.id}") {
                    PluginRepository.executeScraper(
                        scraper = scraper,
                        tmdbId = pluginContentId(
                            videoId = videoId,
                            season = season,
                            episode = episode,
                        ),
                        mediaType = type,
                        season = season,
                        episode = episode,
                    ).map { results -> results.map { it.toStreamItem(scraper) } }.getOrThrow()
                }
            }

            val completions = Channel<AddonStreamGroup>(capacity = Channel.BUFFERED)
            // supervisorScope: one source failing must not cancel the others or the fan-in loop.
            // Previously a throw anywhere in the fan-out killed the whole job, and every group
            // that had not reported yet stayed isLoading = true forever — a permanent spinner
            // with no error shown.
            supervisorScope {
                sources.forEach { source ->
                    launch {
                        val group = runCatchingUnlessCancelled {
                            withTimeoutOrNull(SOURCE_TIMEOUT_MS) { source.fetch() }
                        }.fold(
                            onSuccess = { streams ->
                                if (streams == null) {
                                    log.w { "Timed out: ${source.addonName}" }
                                    source.toGroup(error = "Timed out")
                                } else {
                                    source.toGroup(streams = streams)
                                }
                            },
                            onFailure = { err ->
                                log.w(err) { "Failed: ${source.addonName}" }
                                source.toGroup(error = err.message ?: "Failed to load")
                            },
                        )
                        completions.send(group)
                    }
                }
                repeat(sources.size) {
                    val result = completions.receive()
                    publishStreamGroupAfterCacheCheck(result)
                }
            }
            for (availabilityJob in debridAvailabilityJobs) {
                availabilityJob.join()
            }
            launch {
                DirectDebridStreamPreparer.prepare(
                    streams = stateFlow.value.groups
                        .filter { it.addonId in installedAddonIds }
                        .flatMap { it.streams },
                    season = season,
                    episode = episode,
                    playerSettings = playerSettings,
                    installedAddonNames = installedAddonNames,
                ) { original, prepared ->
                    stateFlow.update { current ->
                        current.copy(
                            groups = DirectDebridStreamPreparer.replacePreparedStream(
                                groups = current.groups,
                                original = original,
                                prepared = prepared,
                                eligibleGroupIds = installedAddonIds,
                            ),
                        )
                    }
                }
            }
            completions.close()
        }
        setJob(job)
    }
}

/** Upper bound on how long a single addon or scraper may hold up the panel. */
private const val SOURCE_TIMEOUT_MS = 45_000L

private class PlayerStreamSource(
    val addonName: String,
    val addonId: String,
    val fetch: suspend () -> List<StreamItem>,
) {
    fun toGroup(streams: List<StreamItem> = emptyList(), error: String? = null) = AddonStreamGroup(
        addonName = addonName,
        addonId = addonId,
        streams = streams,
        isLoading = false,
        error = error,
    )
}

private suspend fun <T> runCatchingUnlessCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

private data class PlayerInstalledStreamAddonTarget(
    val addonName: String,
    val addonId: String,
    val manifest: com.nuvio.app.features.addons.AddonManifest,
)

private fun com.nuvio.app.features.addons.ManagedAddon.streamAddonInstanceId(manifestId: String): String =
    "addon:$manifestId:$manifestUrl"

private fun PluginRuntimeResult.toStreamItem(scraper: PluginScraper): StreamItem {
    val subtitleParts = listOfNotNull(
        quality?.takeIf { it.isNotBlank() },
        size?.takeIf { it.isNotBlank() },
        language?.takeIf { it.isNotBlank() },
    )
    val requestHeaders = headers
        .orEmpty()
        .mapNotNull { (key, value) ->
            val headerName = key.trim()
            val headerValue = value.trim()
            if (headerName.isBlank() || headerValue.isBlank() || headerName.equals("Range", ignoreCase = true)) {
                null
            } else {
                headerName to headerValue
            }
        }
        .toMap()

    return StreamItem(
        name = name ?: title,
        description = subtitleParts.joinToString(" • ").ifBlank { null },
        url = url,
        infoHash = infoHash,
        addonName = scraper.name,
        addonId = "plugin:${scraper.id}",
        behaviorHints = if (requestHeaders.isEmpty()) {
            com.nuvio.app.features.streams.StreamBehaviorHints()
        } else {
            com.nuvio.app.features.streams.StreamBehaviorHints(
                notWebReady = true,
                proxyHeaders = com.nuvio.app.features.streams.StreamProxyHeaders(request = requestHeaders),
            )
        },
    )
}
