package com.nuvio.app.features.streams

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.debrid.DirectDebridStreamPreparer
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DebridStreamPresentation
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.plugins.PluginRepository
import com.nuvio.app.features.plugins.pluginContentId
import com.nuvio.app.features.plugins.PluginsUiState
import com.nuvio.app.features.plugins.PluginRepositoryItem
import com.nuvio.app.features.plugins.PluginRuntimeResult
import com.nuvio.app.features.plugins.PluginScraper
import com.nuvio.app.core.concurrency.NuvioBlockingDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.getString
import kotlinx.coroutines.launch

/**
 * Upper bound on how long a single addon or scraper may hold the stream list open. Matches
 * PlayerStreamsRepository's bound — the same sources are being asked the same question.
 */
private const val SOURCE_TIMEOUT_MS = 45_000L

/**
 * Whether a repeat request for the same content can reuse what is already on screen.
 *
 * ⚠ The `isLoadJobActive` term is the whole point of this being a named function. Without it a
 * load whose job died still reported `isAnyLoading = true` forever, this returned true forever,
 * and re-opening that episode returned immediately without starting anything — so the user sat
 * on a spinner that nothing would ever complete and had to restart the app. A loading flag is
 * only a reason to wait if something is actually still loading.
 *
 * Shared by StreamsRepository and PlayerStreamsRepository so the two fan-outs cannot drift.
 */
internal fun shouldSkipStreamReload(
    forceRefresh: Boolean,
    sameRequestKey: Boolean,
    hasSettledGroups: Boolean,
    hasEmptyStateReason: Boolean,
    isAnyLoading: Boolean,
    isLoadJobActive: Boolean,
): Boolean {
    if (forceRefresh || !sameRequestKey) return false
    return hasSettledGroups || hasEmptyStateReason || (isAnyLoading && isLoadJobActive)
}

object StreamsRepository {
    private val log = Logger.withTag("StreamsRepo")

    // ⚠ Not Dispatchers.Default — the same reasoning as PlayerStreamsRepository, which this
    // fan-out is the twin of. Scraper plugins block a thread per in-flight HTTP request, and
    // Default is sized to the CPU count: a handful of scrapers can occupy every thread in it,
    // including the one this fan-in needs to publish results, and the source list then spins
    // forever with no error. NuvioBlockingDispatcher is the elastic pool meant for that work.
    private val scope = CoroutineScope(SupervisorJob() + NuvioBlockingDispatcher)
    private val _uiState = MutableStateFlow(StreamsUiState())
    val uiState: StateFlow<StreamsUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var activeRequestKey: String? = null

    fun requestToken(
        type: String,
        videoId: String,
        season: Int? = null,
        episode: Int? = null,
        manualSelection: Boolean = false,
    ): String =
        "$type::$videoId::$season::$episode::$manualSelection"

    fun load(type: String, videoId: String, parentMetaId: String? = null, season: Int? = null, episode: Int? = null, manualSelection: Boolean = false) {
        load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
            forceRefresh = false,
        )
    }

    fun reload(type: String, videoId: String, parentMetaId: String? = null, season: Int? = null, episode: Int? = null, manualSelection: Boolean = false) {
        load(
            type = type,
            videoId = videoId,
            parentMetaId = parentMetaId,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
            forceRefresh = true,
        )
    }

    private fun load(type: String, videoId: String, parentMetaId: String?, season: Int?, episode: Int?, manualSelection: Boolean, forceRefresh: Boolean) {
        val pluginUiState = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.initialize()
            PluginRepository.uiState.value
        } else {
            PluginsUiState(pluginsEnabled = false)
        }
        val requestToken = requestToken(
            type = type,
            videoId = videoId,
            season = season,
            episode = episode,
            manualSelection = manualSelection,
        )
        val requestKey = "$requestToken::pluginsGrouped=${pluginUiState.groupStreamsByRepository}"
        val currentState = _uiState.value
        if (
            shouldSkipStreamReload(
                forceRefresh = forceRefresh,
                sameRequestKey = activeRequestKey == requestKey,
                hasSettledGroups = currentState.groups.any { !it.isLoading },
                hasEmptyStateReason = currentState.emptyStateReason != null,
                isAnyLoading = currentState.isAnyLoading,
                isLoadJobActive = activeJob?.isActive == true,
            )
        ) {
            log.d { "Skipping stream reload for unchanged request type=$type id=$videoId" }
            return
        }

        activeRequestKey = requestKey
        activeJob?.cancel()
        _uiState.value = StreamsUiState(requestToken = requestToken)

        PlayerSettingsRepository.ensureLoaded()
        val playerSettings = PlayerSettingsRepository.uiState.value
        val debridSettings = DebridSettingsRepository.snapshot()
        val streamBadgeRules = StreamBadgeSettingsRepository.snapshot()
        val autoPlayMode = playerSettings.streamAutoPlayMode
        val isAutoPlayEnabled = !manualSelection && autoPlayMode != StreamAutoPlayMode.MANUAL &&
            !(autoPlayMode == StreamAutoPlayMode.REGEX_MATCH &&
                !StreamAutoPlayPolicy.isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex))

        // Look up persisted binge group when both settings are enabled
        val persistedBingeGroup = if (
            playerSettings.streamAutoPlayPreferBingeGroup &&
            playerSettings.streamAutoPlayReuseBingeGroup
        ) {
            parentMetaId?.let { BingeGroupCacheRepository.get(it) }
        } else null

        // Enable direct auto-play flow if normal auto-play is enabled,
        // OR if we have a persisted binge group in MANUAL mode
        val bingeGroupDirectFlow = !manualSelection &&
            persistedBingeGroup != null &&
            autoPlayMode == StreamAutoPlayMode.MANUAL
        val isDirectAutoPlayFlow = isAutoPlayEnabled || bingeGroupDirectFlow

        if (isDirectAutoPlayFlow) {
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                isDirectAutoPlayFlow = true,
                showDirectAutoPlayOverlay = true,
            )
        }

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
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                groups = listOf(presentedGroup),
                activeAddonIds = setOf("embedded"),
                isAnyLoading = false,
            )
            return
        }

        val installedAddons = AddonRepository.uiState.value.addons.enabledAddons()
        val pluginScrapers = if (AppFeaturePolicy.pluginsEnabled) {
            PluginRepository.getEnabledScrapersForType(type)
        } else {
            emptyList()
        }
        val pluginProviderGroups = pluginScrapers.toPluginProviderGroups(
            repositories = pluginUiState.repositories,
            groupByRepository = pluginUiState.groupStreamsByRepository,
        )

        if (installedAddons.isEmpty() && pluginProviderGroups.isEmpty()) {
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                isAnyLoading = false,
                emptyStateReason = StreamsEmptyStateReason.NoAddonsInstalled,
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

                InstalledStreamAddonTarget(
                    addonName = addon.displayTitle.ifBlank { manifest.name },
                    addonId = addon.streamAddonInstanceId(manifest.id),
                    manifest = manifest,
                )
            }

        log.d { "Found ${streamAddons.size} addons for stream type=$type id=$videoId" }

        if (streamAddons.isEmpty() && pluginProviderGroups.isEmpty()) {
            _uiState.value = StreamsUiState(
                requestToken = requestToken,
                isAnyLoading = false,
                emptyStateReason = StreamsEmptyStateReason.NoCompatibleAddons,
            )
            return
        }

        // Initialise loading placeholders
        val installedAddonOrder = streamAddons.map { it.addonName }
        val warmedAddonGroups = AddonStreamWarmupRepository
            .cachedGroups(type = type, videoId = videoId, season = season, episode = episode)
            .orEmpty()
            .associateBy { it.addonId }
        val warmedAddonIds = warmedAddonGroups.keys
        val initialGroups = StreamAutoPlaySelector.orderAddonStreams(streamAddons.map { addon ->
            warmedAddonGroups[addon.addonId] ?: AddonStreamGroup(
                addonName = addon.addonName,
                addonId = addon.addonId,
                streams = emptyList(),
                isLoading = true,
            )
        } + pluginProviderGroups.map { providerGroup ->
            AddonStreamGroup(
                addonName = providerGroup.addonName,
                addonId = providerGroup.addonId,
                streams = emptyList(),
                isLoading = true,
            )
        }, installedAddonOrder)
        val isInitiallyLoading = initialGroups.any { it.isLoading }
        _uiState.value = StreamsUiState(
            requestToken = requestToken,
            groups = initialGroups,
            activeAddonIds = initialGroups.map { it.addonId }.toSet(),
            isAnyLoading = isInitiallyLoading,
            emptyStateReason = null,
            isDirectAutoPlayFlow = isDirectAutoPlayFlow,
            showDirectAutoPlayOverlay = isDirectAutoPlayFlow,
        )

        activeJob = scope.launch {
            val pendingStreamAddons = streamAddons.filterNot { it.addonId in warmedAddonIds }
            // ⚠ UNLIMITED, not BUFFERED. Completions are posted with trySend (the publisher must
            // not suspend), and trySend FAILS on a full channel — so with more sources than the
            // buffer holds, a completion could be dropped, and the fan-in below waits for a
            // count that can then never arrive. An unbounded channel makes trySend infallible;
            // the queue is at most one small object per source.
            val completions = Channel<StreamLoadCompletion>(capacity = Channel.UNLIMITED)
            val pluginRemainingByAddonId = pluginProviderGroups
                .associate { it.addonId to it.scrapers.size }
                .toMutableMap()
            val pluginFirstErrorByAddonId = mutableMapOf<String, String>()
            val totalTasks = pendingStreamAddons.size +
                pluginProviderGroups.sumOf { it.scrapers.size }

            val installedAddonNames = installedAddonOrder.toSet()
            val installedAddonIds = streamAddons.map { it.addonId }.toSet()
            val debridAvailabilityJobs = mutableListOf<Job>()
            var autoSelectTriggered = false
            var timeoutElapsed = false
            fun publishCompletion(completion: StreamLoadCompletion) {
                if (completions.trySend(completion).isFailure) {
                    log.d { "Ignoring late stream load completion after channel close" }
                }
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

            fun publishAddonGroup(group: AddonStreamGroup) {
                _uiState.update { current ->
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
                        emptyStateReason = updated.toEmptyStateReason(anyLoading),
                    )
                }
            }

            fun publishAddonGroupAfterCacheCheck(group: AddonStreamGroup) {
                if (group.addonId !in installedAddonIds || group.streams.isEmpty()) {
                    publishAddonGroup(presentStreamGroup(group))
                    return
                }

                val eligibleGroupIds = setOf(group.addonId)
                val shouldWaitForCacheCheck = LocalDebridAvailabilityService.hasPendingCacheCheck(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                )
                if (!shouldWaitForCacheCheck) {
                    publishAddonGroup(presentStreamGroup(group))
                    return
                }

                val checkingGroup = LocalDebridAvailabilityService.markChecking(
                    groups = listOf(group),
                    eligibleGroupIds = eligibleGroupIds,
                ).firstOrNull() ?: group

                // ⚠ Launched on the load's own job (not the supervisorScope below), so an
                // uncaught throw here would cancel the whole load and strand every group at
                // isLoading = true. The debrid cache check is an enhancement — it must never be
                // able to take the stream list down with it.
                val availabilityJob = launch {
                    val availabilityGroup = runCatchingUnlessCancelled {
                        LocalDebridAvailabilityService.annotateCachedAvailability(
                            groups = listOf(checkingGroup),
                            eligibleGroupIds = eligibleGroupIds,
                        ).firstOrNull()
                    }.getOrElse { error ->
                        log.w(error) { "Debrid availability check failed for ${group.addonName}" }
                        null
                    } ?: checkingGroup
                    publishAddonGroup(presentStreamGroup(availabilityGroup))

                    // Early binge-group match right after this addon's availability is resolved
                    if (isDirectAutoPlayFlow && !autoSelectTriggered && persistedBingeGroup != null && !timeoutElapsed) {
                        val allStreams = _uiState.value.groups.flatMap { it.streams }
                        if (allStreams.isNotEmpty()) {
                            val earlyMatch = StreamAutoPlaySelector.selectAutoPlayStream(
                                streams = allStreams,
                                mode = autoPlayMode,
                                regexPattern = playerSettings.streamAutoPlayRegex,
                                source = playerSettings.streamAutoPlaySource,
                                installedAddonNames = installedAddonNames,
                                selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                                selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                                preferredBingeGroup = persistedBingeGroup,
                                preferBingeGroupInSelection = true,
                                bingeGroupOnly = true,
                                debridEnabled = debridSettings.canResolvePlayableLinks,
                                activeResolverProviderId = debridSettings.activeResolverProviderId,
                            )
                            if (earlyMatch != null) {
                                autoSelectTriggered = true
                                _uiState.update { it.copy(autoPlayStream = earlyMatch) }
                            }
                        }
                    }
                }
                debridAvailabilityJobs += availabilityJob
            }

            val timeoutJob = if (isDirectAutoPlayFlow) {
                val timeoutSeconds = playerSettings.streamAutoPlayTimeoutSeconds
                val isUnlimitedTimeout = timeoutSeconds == Int.MAX_VALUE
                // Timeout semantics:
                // - 0 (instant): timeoutElapsed immediately, full select on each response
                // - 1-30 (bounded): wait the configured delay, then full select
                // - unlimited (Int.MAX_VALUE): timeoutElapsed immediately, full select on each response,
                //   with 60s hard fallback to stream picker
                if (timeoutSeconds <= 0 || isUnlimitedTimeout) {
                    timeoutElapsed = true
                    // For unlimited: launch a hard 60s fallback to dismiss overlay
                    if (isUnlimitedTimeout) {
                        launch {
                            delay(60_000L)
                            if (!autoSelectTriggered) {
                                autoSelectTriggered = true
                                val allStreams = _uiState.value.groups.flatMap { it.streams }
                                if (allStreams.isNotEmpty()) {
                                    val selected = StreamAutoPlaySelector.selectAutoPlayStream(
                                        streams = allStreams,
                                        mode = autoPlayMode,
                                        regexPattern = playerSettings.streamAutoPlayRegex,
                                        source = playerSettings.streamAutoPlaySource,
                                        installedAddonNames = installedAddonNames,
                                        selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                                        selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                                        preferredBingeGroup = persistedBingeGroup,
                                        preferBingeGroupInSelection = persistedBingeGroup != null,
                                        bingeGroupOnly = false,
                                        debridEnabled = debridSettings.canResolvePlayableLinks,
                                        activeResolverProviderId = debridSettings.activeResolverProviderId,
                                    )
                                    _uiState.update { it.copy(autoPlayStream = selected) }
                                }
                                if (_uiState.value.autoPlayStream == null) {
                                    _uiState.update {
                                        it.copy(
                                            isDirectAutoPlayFlow = false,
                                            showDirectAutoPlayOverlay = false,
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        null
                    }
                } else {
                    // Bounded timeout (1-30s)
                    launch {
                        delay(timeoutSeconds * 1_000L)
                        timeoutElapsed = true
                        if (!autoSelectTriggered) {
                            val allStreams = _uiState.value.groups.flatMap { it.streams }
                            if (allStreams.isNotEmpty()) {
                                val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
                                    streams = allStreams,
                                    mode = autoPlayMode,
                                    regexPattern = playerSettings.streamAutoPlayRegex,
                                    source = playerSettings.streamAutoPlaySource,
                                    installedAddonNames = installedAddonNames,
                                    selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                                    selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                                    preferredBingeGroup = persistedBingeGroup,
                                    preferBingeGroupInSelection = persistedBingeGroup != null,
                                    bingeGroupOnly = false,
                                    debridEnabled = debridSettings.canResolvePlayableLinks,
                                    activeResolverProviderId = debridSettings.activeResolverProviderId,
                                )
                                if (evaluation.stream != null || !evaluation.hasPendingDebridCandidate) {
                                    autoSelectTriggered = true
                                    _uiState.update {
                                        it.copy(
                                            autoPlayStream = evaluation.stream,
                                            autoPlayCandidates = evaluation.readyStreams,
                                        )
                                    }
                                }
                                if (evaluation.stream == null && !evaluation.hasPendingDebridCandidate) {
                                    _uiState.update {
                                        it.copy(
                                            isDirectAutoPlayFlow = false,
                                            showDirectAutoPlayOverlay = false,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                null
            }

            // ⚠ supervisorScope, not the plain job scope. The fan-in below waits for exactly
            // `totalTasks` completions, so a source that dies without publishing one blocks it
            // forever. Under a plain Job a single throw in ANY producer also cancelled the
            // parent, which killed the fan-in itself and left every group that had not reported
            // yet stuck at isLoading = true — a permanent spinner with no error, recoverable
            // only by restarting the app. PlayerStreamsRepository was fixed this way already;
            // this is the same fan-out and needs the same guarantees.
            supervisorScope {
                pendingStreamAddons.forEach { addon ->
                    launch {
                        val url = buildAddonResourceUrl(
                            manifestUrl = addon.manifest.transportUrl,
                            resource = "stream",
                            type = type,
                            id = videoId,
                        )
                        log.d { "Fetching streams from: $url" }

                        val displayName = addon.addonName
                        val group = runCatchingUnlessCancelled {
                            // Bounded: one unresponsive addon must not hold the list open forever.
                            withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
                                val payload = httpGetText(url)
                                StreamParser.parse(
                                    payload = payload,
                                    addonName = displayName,
                                    addonId = addon.addonId,
                                    addonLogo = addon.manifest.logoUrl,
                                )
                            }
                        }.fold(
                            onSuccess = { streams ->
                                if (streams == null) {
                                    log.w { "Timed out fetching streams from $displayName" }
                                    AddonStreamGroup(
                                        addonName = displayName,
                                        addonId = addon.addonId,
                                        streams = emptyList(),
                                        isLoading = false,
                                        error = "Timed out",
                                    )
                                } else {
                                    log.d { "Got ${streams.size} streams from ${displayName}" }
                                    AddonStreamGroup(
                                        addonName = displayName,
                                        addonId = addon.addonId,
                                        streams = streams,
                                        isLoading = false,
                                    )
                                }
                            },
                            onFailure = { err ->
                                log.w(err) { "Failed to fetch streams from ${displayName}" }
                                AddonStreamGroup(
                                    addonName = displayName,
                                    addonId = addon.addonId,
                                    streams = emptyList(),
                                    isLoading = false,
                                    error = err.message ?: "Failed to load",
                                )
                            },
                        )
                        publishCompletion(StreamLoadCompletion.Addon(group))
                    }
                }

                pluginProviderGroups.forEach { providerGroup ->
                    val includeScraperNameInSubtitle = false
                    providerGroup.scrapers.forEach { scraper ->
                        launch {
                            // ⚠ The whole body is inside runCatchingUnlessCancelled, not just the
                            // scraper call. executeScraper returns a Result, but mapping its results
                            // into StreamItems and resolving the error string are outside that
                            // Result — and a throw there used to escape the producer, so no
                            // completion was ever published and the fan-in below waited forever.
                            val completion = runCatchingUnlessCancelled {
                                withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
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
                                    ).fold(
                                        onSuccess = { results ->
                                            StreamLoadCompletion.PluginScraper(
                                                addonId = providerGroup.addonId,
                                                streams = results.map { result ->
                                                    result.toStreamItem(
                                                        scraper = scraper,
                                                        addonName = providerGroup.addonName,
                                                        addonId = providerGroup.addonId,
                                                        includeScraperNameInSubtitle = includeScraperNameInSubtitle,
                                                    )
                                                },
                                                error = null,
                                            )
                                        },
                                        onFailure = { error ->
                                            StreamLoadCompletion.PluginScraper(
                                                addonId = providerGroup.addonId,
                                                streams = emptyList(),
                                                error = error.message
                                                    ?: getString(Res.string.streams_failed_to_load_scraper, scraper.name),
                                            )
                                        },
                                    )
                                }
                            }.getOrElse { error ->
                                log.w(error) { "Scraper ${scraper.name} failed outside its own error handling" }
                                null
                            } ?: StreamLoadCompletion.PluginScraper(
                                addonId = providerGroup.addonId,
                                streams = emptyList(),
                                error = "Timed out",
                            )
                            publishCompletion(completion)
                        }
                    }
                }

                repeat(totalTasks) {
                    when (val completion = completions.receive()) {
                        is StreamLoadCompletion.Addon -> {
                            val result = completion.group
                            publishAddonGroupAfterCacheCheck(result)
                        }

                        is StreamLoadCompletion.PluginScraper -> {
                            val remaining = (pluginRemainingByAddonId[completion.addonId] ?: 1) - 1
                            pluginRemainingByAddonId[completion.addonId] = remaining.coerceAtLeast(0)
                            if (!completion.error.isNullOrBlank() && pluginFirstErrorByAddonId[completion.addonId].isNullOrBlank()) {
                                pluginFirstErrorByAddonId[completion.addonId] = completion.error
                            }

                            _uiState.update { current ->
                                val updated = StreamAutoPlaySelector.orderAddonStreams(
                                    groups = current.groups.map { group ->
                                        if (group.addonId != completion.addonId) {
                                            group
                                        } else {
                                            val mergedStreams = if (completion.streams.isEmpty()) {
                                                group.streams
                                            } else {
                                                (group.streams + completion.streams).sortedForGroupedDisplay()
                                            }
                                            val stillLoading = remaining > 0
                                            val finalError = if (mergedStreams.isEmpty() && !stillLoading) {
                                                pluginFirstErrorByAddonId[completion.addonId]
                                            } else {
                                                null
                                            }
                                            group.copy(
                                                streams = mergedStreams,
                                                isLoading = stillLoading,
                                                error = finalError,
                                            )
                                        }
                                    },
                                    installedOrder = installedAddonOrder,
                                )
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
            }
            completions.close()

            for (availabilityJob in debridAvailabilityJobs) {
                availabilityJob.join()

                // Early binge-group match after each availability job completes
                if (isDirectAutoPlayFlow && !autoSelectTriggered && persistedBingeGroup != null) {
                    val allStreams = _uiState.value.groups.flatMap { it.streams }
                    if (allStreams.isNotEmpty()) {
                        val earlyMatch = StreamAutoPlaySelector.selectAutoPlayStream(
                            streams = allStreams,
                            mode = autoPlayMode,
                            regexPattern = playerSettings.streamAutoPlayRegex,
                            source = playerSettings.streamAutoPlaySource,
                            installedAddonNames = installedAddonNames,
                            selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                            selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                            preferredBingeGroup = persistedBingeGroup,
                            preferBingeGroupInSelection = true,
                            bingeGroupOnly = !timeoutElapsed,
                            debridEnabled = debridSettings.canResolvePlayableLinks,
                            activeResolverProviderId = debridSettings.activeResolverProviderId,
                        )
                        if (earlyMatch != null) {
                            autoSelectTriggered = true
                            _uiState.update { it.copy(autoPlayStream = earlyMatch) }
                            break
                        }
                    }
                }
            }

            launch {
                DirectDebridStreamPreparer.prepare(
                    streams = _uiState.value.groups
                        .filter { it.addonId in installedAddonIds }
                        .flatMap { it.streams },
                    season = season,
                    episode = episode,
                    playerSettings = playerSettings,
                    installedAddonNames = installedAddonNames,
                ) { original, prepared ->
                    _uiState.update { current ->
                        current.copy(
                            groups = DirectDebridStreamPreparer.replacePreparedStream(
                                groups = current.groups,
                                original = original,
                                prepared = prepared,
                                eligibleGroupIds = installedAddonIds,
                            ),
                        )
                    }

                    // Early binge-group match after each debrid-prepared stream
                    if (isDirectAutoPlayFlow && !autoSelectTriggered && persistedBingeGroup != null) {
                        val allStreams = _uiState.value.groups.flatMap { it.streams }
                        if (allStreams.isNotEmpty()) {
                            val earlyMatch = StreamAutoPlaySelector.selectAutoPlayStream(
                                streams = allStreams,
                                mode = autoPlayMode,
                                regexPattern = playerSettings.streamAutoPlayRegex,
                                source = playerSettings.streamAutoPlaySource,
                                installedAddonNames = installedAddonNames,
                                selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                                selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                                preferredBingeGroup = persistedBingeGroup,
                                preferBingeGroupInSelection = true,
                                bingeGroupOnly = !timeoutElapsed,
                                debridEnabled = debridSettings.canResolvePlayableLinks,
                                activeResolverProviderId = debridSettings.activeResolverProviderId,
                            )
                            if (earlyMatch != null) {
                                autoSelectTriggered = true
                                _uiState.update { it.copy(autoPlayStream = earlyMatch) }
                            }
                        }
                    }
                }

                // Early match / timeout-elapsed auto-select on each addon response
                if (isDirectAutoPlayFlow && !autoSelectTriggered) {
                    val allStreams = _uiState.value.groups.flatMap { it.streams }
                    if (allStreams.isNotEmpty()) {
                        if (timeoutElapsed) {
                            // After timeout: full fallback (bingeGroupOnly = false)
                            val selected = StreamAutoPlaySelector.selectAutoPlayStream(
                                streams = allStreams,
                                mode = autoPlayMode,
                                regexPattern = playerSettings.streamAutoPlayRegex,
                                source = playerSettings.streamAutoPlaySource,
                                installedAddonNames = installedAddonNames,
                                selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                                selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                                preferredBingeGroup = persistedBingeGroup,
                                preferBingeGroupInSelection = persistedBingeGroup != null,
                                bingeGroupOnly = false,
                                debridEnabled = debridSettings.canResolvePlayableLinks,
                                activeResolverProviderId = debridSettings.activeResolverProviderId,
                            )
                            if (selected != null) {
                                autoSelectTriggered = true
                                _uiState.update { it.copy(autoPlayStream = selected) }
                            }
                        } else if (persistedBingeGroup != null) {
                            // Before timeout: try binge-group-only early match
                            val earlyMatch = StreamAutoPlaySelector.selectAutoPlayStream(
                                streams = allStreams,
                                mode = autoPlayMode,
                                regexPattern = playerSettings.streamAutoPlayRegex,
                                source = playerSettings.streamAutoPlaySource,
                                installedAddonNames = installedAddonNames,
                                selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                                selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                                preferredBingeGroup = persistedBingeGroup,
                                preferBingeGroupInSelection = true,
                                bingeGroupOnly = true,
                                debridEnabled = debridSettings.canResolvePlayableLinks,
                                activeResolverProviderId = debridSettings.activeResolverProviderId,
                            )
                            if (earlyMatch != null) {
                                autoSelectTriggered = true
                                _uiState.update { it.copy(autoPlayStream = earlyMatch) }
                            }
                        }
                    }
                }
            }

            // All addons finished — run final auto-select if not yet triggered
            if (isDirectAutoPlayFlow && !autoSelectTriggered) {
                autoSelectTriggered = true
                val allStreams = _uiState.value.groups.flatMap { it.streams }
                val evaluation = StreamAutoPlaySelector.evaluateAutoPlayStream(
                    streams = allStreams,
                    mode = autoPlayMode,
                    regexPattern = playerSettings.streamAutoPlayRegex,
                    source = playerSettings.streamAutoPlaySource,
                    installedAddonNames = installedAddonNames,
                    selectedAddons = playerSettings.streamAutoPlaySelectedAddons,
                    selectedPlugins = playerSettings.streamAutoPlaySelectedPlugins,
                    preferredBingeGroup = persistedBingeGroup,
                    preferBingeGroupInSelection = persistedBingeGroup != null,
                    bingeGroupOnly = false,
                    debridEnabled = debridSettings.canResolvePlayableLinks,
                    activeResolverProviderId = debridSettings.activeResolverProviderId,
                )
                _uiState.update {
                    it.copy(
                        autoPlayStream = evaluation.stream,
                        autoPlayCandidates = evaluation.readyStreams,
                    )
                }
            }
            if (isDirectAutoPlayFlow && _uiState.value.autoPlayStream == null) {
                _uiState.update {
                    it.copy(
                        isDirectAutoPlayFlow = false,
                        showDirectAutoPlayOverlay = false,
                    )
                }
            }
            timeoutJob?.cancel()
        }
    }

    fun selectFilter(addonId: String?) {
        _uiState.update { it.copy(selectedFilter = addonId) }
    }

    fun consumeAutoPlay() {
        activeRequestKey = null
        _uiState.update {
            it.copy(
                autoPlayStream = null,
                autoPlayCandidates = emptyList(),
                isDirectAutoPlayFlow = false,
                showDirectAutoPlayOverlay = false,
            )
        }
    }

    fun skipAutoPlayStream(stream: StreamItem): Boolean {
        var hasNext = false
        _uiState.update { current ->
            val failedIndex = current.autoPlayCandidates.indexOf(stream)
            val remaining = if (failedIndex >= 0) {
                current.autoPlayCandidates.drop(failedIndex + 1)
            } else {
                current.autoPlayCandidates.drop(1)
            }
            hasNext = remaining.isNotEmpty()
            current.copy(
                autoPlayStream = remaining.firstOrNull(),
                autoPlayCandidates = remaining,
                isDirectAutoPlayFlow = remaining.isNotEmpty(),
                showDirectAutoPlayOverlay = remaining.isNotEmpty(),
            )
        }
        return hasNext
    }

    fun cancelLoading() {
        activeJob?.cancel()
        activeJob = null
        _uiState.update { current ->
            if (!current.isAnyLoading && current.groups.none { it.isLoading }) {
                current
            } else {
                val updatedGroups = current.groups.map { group ->
                    if (group.isLoading) group.copy(isLoading = false) else group
                }
                current.copy(
                    groups = updatedGroups,
                    isAnyLoading = false,
                    emptyStateReason = if (updatedGroups.isEmpty()) {
                        current.emptyStateReason
                    } else {
                        updatedGroups.toEmptyStateReason(anyLoading = false)
                    },
                )
            }
        }
    }

    fun clear() {
        activeJob?.cancel()
        activeJob = null
        activeRequestKey = null
        _uiState.value = StreamsUiState()
    }

    fun setOverlayVisible(visible: Boolean, message: String? = null) {
        _uiState.update { it.copy(showDirectAutoPlayOverlay = visible, overlayMessage = message) }
    }
}

private data class InstalledStreamAddonTarget(
    val addonName: String,
    val addonId: String,
    val manifest: com.nuvio.app.features.addons.AddonManifest,
)

private fun com.nuvio.app.features.addons.ManagedAddon.streamAddonInstanceId(manifestId: String): String =
    "addon:$manifestId:$manifestUrl"

private data class PluginProviderGroup(
    val addonId: String,
    val addonName: String,
    val scrapers: List<PluginScraper>,
)

private sealed interface StreamLoadCompletion {
    data class Addon(val group: AddonStreamGroup) : StreamLoadCompletion
    data class PluginScraper(
        val addonId: String,
        val streams: List<StreamItem>,
        val error: String?,
    ) : StreamLoadCompletion
}

private fun List<PluginScraper>.toPluginProviderGroups(
    repositories: List<PluginRepositoryItem>,
    groupByRepository: Boolean,
): List<PluginProviderGroup> {
    if (!groupByRepository) {
        return map { scraper ->
            PluginProviderGroup(
                addonId = "plugin:${scraper.id}",
                addonName = scraper.name,
                scrapers = listOf(scraper),
            )
        }
    }

    val repoNameByUrl = repositories.associate { it.manifestUrl to it.name }
    return groupBy { it.repositoryUrl }
        .map { (repositoryUrl, scrapers) ->
            PluginProviderGroup(
                addonId = "plugin-repo:${repositoryUrl.lowercase()}",
                addonName = repoNameByUrl[repositoryUrl].orEmpty().ifBlank { repositoryUrl.fallbackRepositoryLabel() },
                scrapers = scrapers.sortedBy { it.name.lowercase() },
            )
        }
        .sortedBy { it.addonName.lowercase() }
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

private suspend fun <T> runCatchingUnlessCancelled(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

private fun PluginRuntimeResult.toStreamItem(
    scraper: PluginScraper,
    addonName: String = scraper.name,
    addonId: String = "plugin:${scraper.id}",
    includeScraperNameInSubtitle: Boolean = false,
): StreamItem {
    val subtitleParts = listOfNotNull(
        scraper.name.takeIf { includeScraperNameInSubtitle && it.isNotBlank() },
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
        sourceName = scraper.name,
        addonName = addonName,
        addonId = addonId,
        behaviorHints = if (requestHeaders.isEmpty()) {
            StreamBehaviorHints()
        } else {
            StreamBehaviorHints(
                notWebReady = true,
                proxyHeaders = StreamProxyHeaders(request = requestHeaders),
            )
        },
    )
}

private fun List<StreamItem>.sortedForGroupedDisplay(): List<StreamItem> =
    sortedWith(
        compareBy<StreamItem>(
            { it.sourceName.orEmpty().lowercase() },
            { it.streamLabel.lowercase() },
            { it.streamSubtitle.orEmpty().lowercase() },
        ),
    )

private fun String.fallbackRepositoryLabel(): String {
    val withoutQuery = substringBefore("?")
    val withoutManifest = withoutQuery.removeSuffix("/manifest.json")
    val host = withoutManifest.substringAfter("://", withoutManifest).substringBefore('/')
    return host.ifBlank {
        withoutManifest.substringAfterLast('/').ifBlank { "Plugin repository" }
    }
}
