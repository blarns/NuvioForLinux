package com.nuvio.app.features.home

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.nextReleasedEpisodeAfter
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeHeroReservedSpace
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomeSkeletonHero
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.isEpisode
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import com.nuvio.app.features.watchprogress.toUpNextContinueWatchingItem

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    onFirstCatalogRendered: (() -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        AddonRepository.initialize()
        ContinueWatchingPreferencesRepository.ensureLoaded()
        WatchedRepository.ensureLoaded()
        WatchProgressRepository.ensureLoaded()
    }

    val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val homeUiState by HomeRepository.uiState.collectAsStateWithLifecycle()
    val homeSettingsUiState by HomeCatalogSettingsRepository.uiState.collectAsStateWithLifecycle()
    val continueWatchingPreferences by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()
    val watchedUiState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    val watchProgressUiState by WatchProgressRepository.uiState.collectAsStateWithLifecycle()

    val latestResumableBySeries = remember(watchProgressUiState.entries) {
        watchProgressUiState.entries
            .filter { !it.isCompleted && it.isEpisode }
            .groupBy { it.parentMetaId }
            .mapValues { (_, entries) -> entries.maxByOrNull { it.lastUpdatedEpochMs } }
    }
    val latestCompletedBySeries = remember(watchProgressUiState.entries, watchedUiState.items) {
        val progressCandidates = watchProgressUiState.entries
            .filter { it.isCompleted && it.isEpisode }
            .map { entry ->
                CompletedSeriesCandidate(
                    parentMetaId = entry.parentMetaId,
                    parentMetaType = entry.parentMetaType,
                    seasonNumber = entry.seasonNumber ?: return@map null,
                    episodeNumber = entry.episodeNumber ?: return@map null,
                    markedAtEpochMs = entry.lastUpdatedEpochMs,
                )
            }
            .filterNotNull()

        val watchedCandidates = watchedUiState.items
            .filter { it.isEpisode }
            .map { item ->
                CompletedSeriesCandidate(
                    parentMetaId = item.id,
                    parentMetaType = item.type,
                    seasonNumber = item.season ?: return@map null,
                    episodeNumber = item.episode ?: return@map null,
                    markedAtEpochMs = item.markedAtEpochMs,
                )
            }
            .filterNotNull()

        (progressCandidates + watchedCandidates)
            .groupBy { it.parentMetaId }
            .mapNotNull { (parentMetaId, entries) ->
                entries.maxByOrNull { it.markedAtEpochMs }?.let { parentMetaId to it }
            }
            .toMap()
    }
    val completedSeriesCandidates = remember(latestCompletedBySeries, latestResumableBySeries) {
        latestCompletedBySeries.values.filter { completed ->
            val latestResume = latestResumableBySeries[completed.parentMetaId]
            latestResume == null || latestResume.lastUpdatedEpochMs <= completed.markedAtEpochMs
        }
    }
    val visibleContinueWatchingEntries = remember(
        watchProgressUiState.continueWatchingEntries,
        latestCompletedBySeries,
    ) {
        watchProgressUiState.continueWatchingEntries.filter { entry ->
            if (!entry.isEpisode) {
                true
            } else {
                val latestCompleted = latestCompletedBySeries[entry.parentMetaId]
                latestCompleted == null || entry.lastUpdatedEpochMs > latestCompleted.markedAtEpochMs
            }
        }
    }
    var nextUpItemsBySeries by remember { mutableStateOf<Map<String, Pair<Long, ContinueWatchingItem>>>(emptyMap()) }
    val continueWatchingItems = remember(
        visibleContinueWatchingEntries,
        nextUpItemsBySeries,
    ) {
        buildList {
            addAll(
                visibleContinueWatchingEntries.map { entry ->
                    entry.lastUpdatedEpochMs to entry.toContinueWatchingItem()
                },
            )
            addAll(nextUpItemsBySeries.values)
        }
            .sortedByDescending { it.first }
            .map { it.second }
    }
    val allManifestsSettled = addonsUiState.addons.isNotEmpty() &&
        addonsUiState.addons.none { it.isRefreshing }

    val metaProviderKey = remember(addonsUiState.addons, allManifestsSettled) {
        if (!allManifestsSettled) return@remember emptyList<String>()
        addonsUiState.addons
            .mapNotNull { addon ->
                addon.manifest?.takeIf { manifest ->
                    manifest.resources.any { resource -> resource.name == "meta" }
                }?.transportUrl
            }
            .sorted()
    }

    val catalogRefreshKey = remember(addonsUiState.addons, allManifestsSettled) {
        if (!allManifestsSettled) return@remember emptyList<String>()
        addonsUiState.addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            buildString {
                append(manifest.transportUrl)
                append(':')
                append(manifest.catalogs.joinToString(separator = ",") { catalog ->
                    "${catalog.type}:${catalog.id}:${catalog.extra.count { it.isRequired }}"
                })
            }
        }
    }

    LaunchedEffect(catalogRefreshKey) {
        if (catalogRefreshKey.isEmpty()) return@LaunchedEffect
        HomeCatalogSettingsRepository.syncCatalogs(addonsUiState.addons)
        HomeRepository.refresh(addonsUiState.addons)
    }

    LaunchedEffect(completedSeriesCandidates, metaProviderKey) {
        if (completedSeriesCandidates.isEmpty()) {
            nextUpItemsBySeries = emptyMap()
            return@LaunchedEffect
        }

        if (metaProviderKey.isEmpty()) return@LaunchedEffect

        val todayIsoDate = CurrentDateProvider.todayIsoDate()
        val resolvedItems = mutableMapOf<String, Pair<Long, ContinueWatchingItem>>()
        completedSeriesCandidates.forEach { completedEntry ->
            val meta = MetaDetailsRepository.fetch(
                type = completedEntry.parentMetaType,
                id = completedEntry.parentMetaId,
            ) ?: return@forEach
            val nextEpisode = meta.nextReleasedEpisodeAfter(
                seasonNumber = completedEntry.seasonNumber,
                episodeNumber = completedEntry.episodeNumber,
                todayIsoDate = todayIsoDate,
            ) ?: return@forEach
            resolvedItems[completedEntry.parentMetaId] =
                completedEntry.markedAtEpochMs to completedEntry.toContinueWatchingSeed(meta).toUpNextContinueWatchingItem(nextEpisode)
        }
        nextUpItemsBySeries = resolvedItems
    }

    val hasActiveAddons = addonsUiState.addons.any { it.manifest != null }
    val showHeroSlot = homeSettingsUiState.heroEnabled
    val isResolvingHeroSources = addonsUiState.addons.any { it.isRefreshing } || homeUiState.isLoading
    val showHeroSkeleton = showHeroSlot &&
        homeUiState.heroItems.isEmpty() &&
        isResolvingHeroSources
    var firstCatalogReported by remember { mutableStateOf(false) }

    LaunchedEffect(homeUiState.sections.firstOrNull()?.key, onFirstCatalogRendered) {
        if (firstCatalogReported || homeUiState.sections.isEmpty()) return@LaunchedEffect
        firstCatalogReported = true
        onFirstCatalogRendered?.invoke()
    }

    NuvioScreen(
        modifier = modifier,
        horizontalPadding = 0.dp,
        topPadding = if (showHeroSlot) 0.dp else null,
    ) {
        if (showHeroSlot) {
            item {
                when {
                    showHeroSkeleton -> HomeSkeletonHero(
                        modifier = Modifier,
                    )

                    homeUiState.heroItems.isNotEmpty() -> HomeHeroSection(
                        items = homeUiState.heroItems,
                        modifier = Modifier,
                        onItemClick = onPosterClick,
                    )

                    else -> HomeHeroReservedSpace(modifier = Modifier)
                }
            }
        }

        when {
            addonsUiState.addons.none { it.manifest != null } -> {
                if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                    item {
                        HomeContinueWatchingSection(
                            items = continueWatchingItems,
                            style = continueWatchingPreferences.style,
                            modifier = Modifier.padding(bottom = 12.dp),
                            onItemClick = onContinueWatchingClick,
                            onItemLongPress = onContinueWatchingLongPress,
                        )
                    }
                }
                item {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = "No active addons",
                        message = "Install and validate at least one addon before loading catalog rows on Home.",
                    )
                }
            }

            homeUiState.isLoading && homeUiState.sections.isEmpty() -> {
                if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                    item {
                        HomeContinueWatchingSection(
                            items = continueWatchingItems,
                            style = continueWatchingPreferences.style,
                            modifier = Modifier.padding(bottom = 12.dp),
                            onItemClick = onContinueWatchingClick,
                            onItemLongPress = onContinueWatchingLongPress,
                        )
                    }
                }
                items(3) {
                    HomeSkeletonRow(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }

            homeUiState.sections.isEmpty() && homeUiState.heroItems.isEmpty() &&
                (!continueWatchingPreferences.isVisible || continueWatchingItems.isEmpty()) -> {
                item {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = "No home rows available",
                        message = homeUiState.errorMessage
                            ?: "Installed addons do not currently expose board-compatible catalogs without required extras.",
                    )
                }
            }

            else -> {
                if (continueWatchingPreferences.isVisible && continueWatchingItems.isNotEmpty()) {
                    item {
                        HomeContinueWatchingSection(
                            items = continueWatchingItems,
                            style = continueWatchingPreferences.style,
                            modifier = Modifier.padding(bottom = 12.dp),
                            onItemClick = onContinueWatchingClick,
                            onItemLongPress = onContinueWatchingLongPress,
                        )
                    }
                }
                items(
                    count = homeUiState.sections.size,
                    key = { index -> homeUiState.sections[index].key },
                ) { index ->
                    val section = homeUiState.sections[index]
                    HomeCatalogRowSection(
                        section = section,
                        entries = section.items.take(HOME_CATALOG_PREVIEW_LIMIT),
                        modifier = Modifier.padding(bottom = 12.dp),
                        onViewAllClick = if (section.canOpenCatalog(HOME_CATALOG_PREVIEW_LIMIT)) {
                            onCatalogClick?.let { { it(section) } }
                        } else {
                            null
                        },
                        watchedKeys = watchedUiState.watchedKeys,
                        onPosterClick = onPosterClick,
                        onPosterLongClick = onPosterLongClick,
                    )
                }
            }
        }
    }
}

private const val HOME_CATALOG_PREVIEW_LIMIT = 18

private data class CompletedSeriesCandidate(
    val parentMetaId: String,
    val parentMetaType: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val markedAtEpochMs: Long,
)

private fun CompletedSeriesCandidate.toContinueWatchingSeed(meta: com.nuvio.app.features.details.MetaDetails) =
    com.nuvio.app.features.watchprogress.WatchProgressEntry(
        contentType = parentMetaType,
        parentMetaId = parentMetaId,
        parentMetaType = parentMetaType,
        videoId = "${parentMetaId}:${seasonNumber}:${episodeNumber}",
        title = meta.name,
        logo = meta.logo,
        poster = meta.poster,
        background = meta.background,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        lastPositionMs = 0L,
        durationMs = 0L,
        lastUpdatedEpochMs = markedAtEpochMs,
        isCompleted = true,
    )
