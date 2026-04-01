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
import com.nuvio.app.features.details.sortedPlayableEpisodes
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeContinueWatchingSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeHeroReservedSpace
import com.nuvio.app.features.home.components.HomeHeroSection
import com.nuvio.app.features.home.components.HomeSkeletonHero
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressEntry
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.toContinueWatchingItem
import com.nuvio.app.features.watchprogress.toUpNextContinueWatchingItem
import com.nuvio.app.features.watching.application.WatchingState
import com.nuvio.app.features.watching.domain.WatchingContentRef
import com.nuvio.app.features.watching.domain.buildPlaybackVideoId
import com.nuvio.app.features.watching.domain.isReleasedBy

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
    val isTraktAuthenticated by remember {
        TraktAuthRepository.ensureLoaded()
        TraktAuthRepository.isAuthenticated
    }.collectAsStateWithLifecycle()

    val effectiveWatchProgressEntries = remember(watchProgressUiState.entries, isTraktAuthenticated) {
        if (!isTraktAuthenticated) {
            watchProgressUiState.entries
        } else {
            val cutoffMs = WatchProgressClock.nowEpochMs() - (TRAKT_CONTINUE_WATCHING_DAYS_CAP_DEFAULT.toLong() * 24L * 60L * 60L * 1000L)
            watchProgressUiState.entries.filter { entry -> entry.lastUpdatedEpochMs >= cutoffMs }
        }
    }

    val effectiveWatchedItems = remember(watchedUiState.items, isTraktAuthenticated) {
        if (isTraktAuthenticated) emptyList() else watchedUiState.items
    }

    val latestCompletedBySeries = remember(effectiveWatchProgressEntries, effectiveWatchedItems, continueWatchingPreferences.upNextFromFurthestEpisode) {
        WatchingState.latestCompletedBySeries(
            progressEntries = effectiveWatchProgressEntries,
            watchedItems = effectiveWatchedItems,
            preferFurthestEpisode = continueWatchingPreferences.upNextFromFurthestEpisode,
        )
    }
    val completedSeriesCandidates = remember(latestCompletedBySeries) {
        latestCompletedBySeries.map { (content, completed) ->
            CompletedSeriesCandidate(
                content = content,
                seasonNumber = completed.seasonNumber,
                episodeNumber = completed.episodeNumber,
                markedAtEpochMs = completed.markedAtEpochMs,
            )
        }
    }
    val visibleContinueWatchingEntries = remember(
        effectiveWatchProgressEntries,
        latestCompletedBySeries,
    ) {
        WatchingState.visibleContinueWatchingEntries(
            progressEntries = effectiveWatchProgressEntries,
            latestCompletedBySeries = latestCompletedBySeries,
        )
    }
    var nextUpItemsBySeries by remember { mutableStateOf<Map<String, Pair<Long, ContinueWatchingItem>>>(emptyMap()) }
    val continueWatchingItems = remember(
        visibleContinueWatchingEntries,
        nextUpItemsBySeries,
    ) {
        buildHomeContinueWatchingItems(
            visibleEntries = visibleContinueWatchingEntries,
            nextUpItemsBySeries = nextUpItemsBySeries,
        )
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
                type = completedEntry.content.type,
                id = completedEntry.content.id,
            ) ?: return@forEach
            val nextEpisode = meta.nextReleasedEpisodeAfter(
                seasonNumber = completedEntry.seasonNumber,
                episodeNumber = completedEntry.episodeNumber,
                todayIsoDate = todayIsoDate,
                showUnairedNextUp = isTraktAuthenticated,
            ) ?: return@forEach
            resolvedItems[completedEntry.content.id] =
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
private const val TRAKT_CONTINUE_WATCHING_DAYS_CAP_DEFAULT = 60

internal fun buildHomeContinueWatchingItems(
    visibleEntries: List<WatchProgressEntry>,
    nextUpItemsBySeries: Map<String, Pair<Long, ContinueWatchingItem>>,
): List<ContinueWatchingItem> {
    return buildList {
        addAll(
            visibleEntries.map { entry ->
                HomeContinueWatchingCandidate(
                    lastUpdatedEpochMs = entry.lastUpdatedEpochMs,
                    item = entry.toContinueWatchingItem(),
                    isProgressEntry = true,
                )
            },
        )
        addAll(
            nextUpItemsBySeries.values.map { (lastUpdatedEpochMs, item) ->
                HomeContinueWatchingCandidate(
                    lastUpdatedEpochMs = lastUpdatedEpochMs,
                    item = item,
                    isProgressEntry = false,
                )
            },
        )
    }
        .sortedWith(
            compareByDescending<HomeContinueWatchingCandidate> { it.lastUpdatedEpochMs }
                .thenByDescending { it.isProgressEntry },
        )
        .distinctBy { it.item.videoId }
        .map(HomeContinueWatchingCandidate::item)
}

private data class CompletedSeriesCandidate(
    val content: WatchingContentRef,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val markedAtEpochMs: Long,
)

private data class HomeContinueWatchingCandidate(
    val lastUpdatedEpochMs: Long,
    val item: ContinueWatchingItem,
    val isProgressEntry: Boolean,
)

private fun CompletedSeriesCandidate.toContinueWatchingSeed(meta: com.nuvio.app.features.details.MetaDetails) =
    WatchProgressEntry(
        contentType = content.type,
        parentMetaId = content.id,
        parentMetaType = content.type,
        videoId = "${content.id}:${seasonNumber}:${episodeNumber}",
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

private fun com.nuvio.app.features.details.MetaDetails.nextReleasedEpisodeAfter(
    seasonNumber: Int?,
    episodeNumber: Int?,
    todayIsoDate: String,
    showUnairedNextUp: Boolean,
): com.nuvio.app.features.details.MetaVideo? {
    val content = WatchingContentRef(type = type, id = id)
    val watchedVideoId = buildPlaybackVideoId(
        content = content,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
    )

    val ordered = sortedPlayableEpisodes()
        .dropWhile { episode ->
            buildPlaybackVideoId(
                content = content,
                seasonNumber = episode.season,
                episodeNumber = episode.episode,
                fallbackVideoId = episode.id,
            ) != watchedVideoId
        }
        .drop(1)
        .filter { episode -> (episode.season ?: 0) > 0 }

    if (showUnairedNextUp) {
        return ordered.firstOrNull()
    }

    return ordered.firstOrNull { episode ->
        isReleasedBy(todayIsoDate = todayIsoDate, releasedDate = episode.released)
    }
}
