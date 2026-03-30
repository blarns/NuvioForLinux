package com.nuvio.app.features.details

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.nuvioPlatformExtraBottomPadding
import com.nuvio.app.features.details.components.DetailActionButtons
import com.nuvio.app.features.details.components.DetailCastSection
import com.nuvio.app.features.details.components.DetailFloatingHeader
import com.nuvio.app.features.details.components.DetailHero
import com.nuvio.app.features.details.components.DetailMetaInfo
import com.nuvio.app.features.details.components.DetailSeriesContent
import com.nuvio.app.features.details.components.EpisodeWatchedActionSheet
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.episodePlaybackId
import com.nuvio.app.features.watched.previousReleasedEpisodesBefore
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watched.toEpisodeWatchedItem
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId

@Composable
fun MetaDetailsScreen(
    type: String,
    id: String,
    onBack: () -> Unit,
    onPlay: ((type: String, videoId: String, parentMetaId: String, parentMetaType: String, title: String, logo: String?, poster: String?, background: String?, seasonNumber: Int?, episodeNumber: Int?, episodeTitle: String?, episodeThumbnail: String?, pauseDescription: String?, resumePositionMs: Long?) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
    val libraryUiState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    val watchProgressUiState by remember {
        WatchProgressRepository.ensureLoaded()
        WatchProgressRepository.uiState
    }.collectAsStateWithLifecycle()
    val screenAlpha = remember(type, id) { Animatable(0f) }
    val requestedMeta = uiState.meta?.takeIf { it.type == type && it.id == id }
    val needsFreshLoad = requestedMeta == null && !uiState.isLoading
    var selectedEpisodeForActions by remember(type, id) { mutableStateOf<MetaVideo?>(null) }

    LaunchedEffect(type, id, needsFreshLoad) {
        if (!needsFreshLoad) {
            screenAlpha.snapTo(1f)
            return@LaunchedEffect
        }
        screenAlpha.snapTo(0f)
        screenAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 220),
        )
        MetaDetailsRepository.load(type, id)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .alpha(screenAlpha.value)
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            uiState.isLoading || (uiState.meta != null && requestedMeta == null) -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            uiState.errorMessage != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Failed to load",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = uiState.errorMessage.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            requestedMeta != null -> {
                val meta = requestedMeta
                val todayIsoDate = CurrentDateProvider.todayIsoDate()
                val isSaved = remember(libraryUiState.items, meta.id) {
                    libraryUiState.items.any { it.id == meta.id }
                }
                val toggleSaved = remember(meta) {
                    {
                        LibraryRepository.toggleSaved(
                            meta.toLibraryItem(savedAtEpochMs = 0L),
                        )
                    }
                }
                val movieProgress = watchProgressUiState.byVideoId[meta.id]
                    ?.takeUnless { it.isCompleted }
                val seriesAction = remember(watchProgressUiState.entries, watchedUiState.items, meta, todayIsoDate) {
                    meta.seriesPrimaryAction(
                        entries = watchProgressUiState.entries,
                        watchedItems = watchedUiState.items,
                        todayIsoDate = todayIsoDate,
                    )
                }
                val seriesPauseDescription = remember(seriesAction, meta.id, meta.videos) {
                    val action = seriesAction ?: return@remember null
                    meta.videos.firstOrNull { video ->
                        buildPlaybackVideoId(
                            parentMetaId = meta.id,
                            seasonNumber = video.season,
                            episodeNumber = video.episode,
                            fallbackVideoId = video.id,
                        ) == action.videoId
                    }?.overview
                }
                val hasEpisodes = meta.videos.any { it.season != null || it.episode != null }
                val syncSeriesWatchedState = remember(meta, todayIsoDate) {
                    {
                        WatchedRepository.reconcileSeriesWatchedState(
                            meta = meta,
                            todayIsoDate = todayIsoDate,
                            isEpisodeCompleted = { episode ->
                                WatchProgressRepository.progressForVideo(meta.episodePlaybackId(episode))?.isCompleted == true
                            },
                        )
                    }
                }
                val playButtonLabel = remember(movieProgress, seriesAction, meta.type, hasEpisodes) {
                    when {
                        (meta.type == "series" || hasEpisodes) && seriesAction != null ->
                            seriesAction.label
                        meta.type != "series" && !hasEpisodes && movieProgress != null ->
                            "Resume"
                        else -> "Play"
                    }
                }
                val scrollState = rememberScrollState()
                val density = LocalDensity.current
                val safeAreaTopPx = with(density) {
                    WindowInsets.statusBars
                        .asPaddingValues()
                        .calculateTopPadding()
                        .toPx()
                }
                var heroHeightPx by remember(meta.id) { mutableIntStateOf(0) }
                val thresholdPx = (heroHeightPx - safeAreaTopPx).coerceAtLeast(0f)
                val headerTarget = if (heroHeightPx > 0 && scrollState.value > thresholdPx) 1f else 0f
                val headerProgress by animateFloatAsState(
                    targetValue = headerTarget,
                    animationSpec = tween(
                        durationMillis = if (headerTarget > 0f) 150 else 100,
                        easing = LinearOutSlowInEasing,
                    ),
                    label = "detail_floating_header_progress",
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState),
                    ) {
                        DetailHero(
                            meta = meta,
                            scrollOffset = scrollState.value,
                            onHeightChanged = { heroHeightPx = it },
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            DetailActionButtons(
                                playLabel = playButtonLabel,
                                saveLabel = if (isSaved) "Saved" else "Save",
                                isSaved = isSaved,
                                onPlayClick = {
                                    when {
                                        (meta.type == "series" || hasEpisodes) && seriesAction != null -> {
                                            onPlay?.invoke(
                                                meta.type,
                                                seriesAction.videoId,
                                                meta.id,
                                                meta.type,
                                                meta.name,
                                                meta.logo,
                                                meta.poster,
                                                meta.background,
                                                seriesAction.seasonNumber,
                                                seriesAction.episodeNumber,
                                                seriesAction.episodeTitle,
                                                seriesAction.episodeThumbnail,
                                                seriesPauseDescription,
                                                seriesAction.resumePositionMs,
                                            )
                                        }

                                        else -> {
                                            onPlay?.invoke(
                                                meta.type,
                                                meta.id,
                                                meta.id,
                                                meta.type,
                                                meta.name,
                                                meta.logo,
                                                meta.poster,
                                                meta.background,
                                                null,
                                                null,
                                                null,
                                                null,
                                                meta.description,
                                                movieProgress?.lastPositionMs,
                                            )
                                        }
                                    }
                                },
                                onSaveClick = toggleSaved,
                            )

                            DetailMetaInfo(meta = meta)

                            DetailCastSection(cast = meta.cast)

                            DetailSeriesContent(
                                meta = meta,
                                progressByVideoId = watchProgressUiState.byVideoId,
                                watchedKeys = watchedUiState.watchedKeys,
                                onEpisodeClick = { video ->
                                    val season = video.season
                                    val episode = video.episode
                                    val playbackVideoId = buildPlaybackVideoId(
                                        parentMetaId = meta.id,
                                        seasonNumber = season,
                                        episodeNumber = episode,
                                        fallbackVideoId = video.id,
                                    )
                                    val savedProgress = watchProgressUiState.byVideoId[playbackVideoId]
                                        ?.takeUnless { it.isCompleted }
                                    onPlay?.invoke(
                                        meta.type,
                                        playbackVideoId,
                                        meta.id,
                                        meta.type,
                                        meta.name,
                                        meta.logo,
                                        meta.poster,
                                        meta.background,
                                        season,
                                        episode,
                                        video.title,
                                        video.thumbnail,
                                        video.overview,
                                        savedProgress?.lastPositionMs,
                                    )
                                },
                                onEpisodeLongPress = { video ->
                                    selectedEpisodeForActions = video
                                },
                            )

                            Spacer(modifier = Modifier.height(32.dp + nuvioPlatformExtraBottomPadding))
                        }
                    }

                    if (headerProgress <= 0.05f) {
                        NuvioBackButton(
                            onClick = onBack,
                            modifier = Modifier.padding(
                                start = 12.dp,
                                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                            ),
                            containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colorScheme.onBackground,
                        )
                    }

                    DetailFloatingHeader(
                        meta = meta,
                        isSaved = isSaved,
                        progress = headerProgress,
                        onBack = onBack,
                        onToggleSaved = toggleSaved,
                    )

                    selectedEpisodeForActions?.let { selectedEpisode ->
                        val isEpisodeWatched: (com.nuvio.app.features.details.MetaVideo) -> Boolean = remember(meta, watchedUiState.watchedKeys) {
                            { episode ->
                                watchedUiState.watchedKeys.contains(
                                    com.nuvio.app.features.watched.watchedItemKey(
                                        type = meta.type,
                                        id = meta.id,
                                        season = episode.season,
                                        episode = episode.episode,
                                    ),
                                )
                            }
                        }
                        val selectedEpisodeWatchedKey = remember(meta, selectedEpisode) {
                            com.nuvio.app.features.watched.watchedItemKey(
                                type = meta.type,
                                id = meta.id,
                                season = selectedEpisode.season,
                                episode = selectedEpisode.episode,
                            )
                        }
                        val isSelectedEpisodeWatched = remember(watchedUiState.watchedKeys, selectedEpisodeWatchedKey) {
                            watchedUiState.watchedKeys.contains(selectedEpisodeWatchedKey)
                        }
                        val previousEpisodes = remember(meta, selectedEpisode, todayIsoDate) {
                            meta.previousReleasedEpisodesBefore(
                                target = selectedEpisode,
                                todayIsoDate = todayIsoDate,
                            )
                        }
                        val seasonEpisodes = remember(meta, selectedEpisode, todayIsoDate) {
                            meta.releasedEpisodesForSeason(
                                seasonNumber = selectedEpisode.season,
                                todayIsoDate = todayIsoDate,
                            )
                        }
                        val arePreviousEpisodesWatched = remember(previousEpisodes, watchedUiState.watchedKeys) {
                            previousEpisodes.isNotEmpty() && previousEpisodes.all(isEpisodeWatched)
                        }
                        val isSeasonWatched = remember(seasonEpisodes, watchedUiState.watchedKeys) {
                            seasonEpisodes.isNotEmpty() && seasonEpisodes.all(isEpisodeWatched)
                        }
                        EpisodeWatchedActionSheet(
                            episode = selectedEpisode,
                            seasonLabel = selectedEpisode.season?.let { "Season $it" } ?: "Specials",
                            isEpisodeWatched = isSelectedEpisodeWatched,
                            canMarkPreviousEpisodes = previousEpisodes.isNotEmpty(),
                            arePreviousEpisodesWatched = arePreviousEpisodesWatched,
                            isSeasonWatched = isSeasonWatched,
                            onDismiss = { selectedEpisodeForActions = null },
                            onToggleWatched = {
                                val watchedItem = meta.toEpisodeWatchedItem(selectedEpisode)
                                if (isSelectedEpisodeWatched) {
                                    WatchedRepository.unmarkWatched(watchedItem)
                                } else {
                                    WatchedRepository.markWatched(watchedItem)
                                    WatchProgressRepository.clearProgress(meta.episodePlaybackId(selectedEpisode))
                                }
                                syncSeriesWatchedState()
                            },
                            onTogglePreviousWatched = {
                                val watchedItems = previousEpisodes.map(meta::toEpisodeWatchedItem)
                                if (arePreviousEpisodesWatched) {
                                    WatchedRepository.unmarkWatched(watchedItems)
                                } else {
                                    WatchedRepository.markWatched(watchedItems)
                                    WatchProgressRepository.clearProgress(
                                        previousEpisodes.map(meta::episodePlaybackId),
                                    )
                                }
                                syncSeriesWatchedState()
                            },
                            onToggleSeasonWatched = {
                                val watchedItems = seasonEpisodes.map(meta::toEpisodeWatchedItem)
                                if (isSeasonWatched) {
                                    WatchedRepository.unmarkWatched(watchedItems)
                                } else {
                                    WatchedRepository.markWatched(watchedItems)
                                    WatchProgressRepository.clearProgress(
                                        seasonEpisodes.map(meta::episodePlaybackId),
                                    )
                                }
                                syncSeriesWatchedState()
                            },
                        )
                    }
                }
            }
        }

        if (requestedMeta == null) {
            NuvioBackButton(
                onClick = onBack,
                modifier = Modifier.padding(
                    start = 12.dp,
                    top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 8.dp,
                ),
                containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
