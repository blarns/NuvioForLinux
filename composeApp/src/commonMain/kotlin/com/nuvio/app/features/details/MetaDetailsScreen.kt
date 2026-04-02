package com.nuvio.app.features.details

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioBackButton
import com.nuvio.app.core.ui.nuvioPlatformExtraBottomPadding
import com.nuvio.app.features.details.components.DetailActionButtons
import com.nuvio.app.features.details.components.CommentDetailSheet
import com.nuvio.app.features.details.components.DetailAdditionalInfoSection
import com.nuvio.app.features.details.components.DetailCastSection
import com.nuvio.app.features.details.components.DetailCommentsSection
import com.nuvio.app.features.details.components.DetailFloatingHeader
import com.nuvio.app.features.details.components.DetailHero
import com.nuvio.app.features.details.components.DetailMetaInfo
import com.nuvio.app.features.details.components.DetailPosterRailSection
import com.nuvio.app.features.details.components.DetailProductionSection
import com.nuvio.app.features.details.components.DetailSeriesContent
import com.nuvio.app.features.details.components.DetailTrailersSection
import com.nuvio.app.features.details.components.EpisodeWatchedActionSheet
import com.nuvio.app.features.details.components.TrailerPlayerPopup
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.trakt.TraktAuthRepository
import com.nuvio.app.features.trakt.TraktCommentReview
import com.nuvio.app.features.trakt.TraktCommentsRepository
import com.nuvio.app.features.trakt.TraktCommentsSettings
import com.nuvio.app.features.trakt.TraktConnectionMode
import com.nuvio.app.features.trakt.TraktListTab
import com.nuvio.app.features.trailer.TrailerPlaybackResolver
import com.nuvio.app.features.trailer.TrailerPlaybackSource
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watched.previousReleasedEpisodesBefore
import com.nuvio.app.features.watched.releasedEpisodesForSeason
import com.nuvio.app.features.watchprogress.CurrentDateProvider
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import com.nuvio.app.features.watchprogress.ContinueWatchingPreferencesRepository
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.launch

@Composable
fun MetaDetailsScreen(
    type: String,
    id: String,
    onBack: () -> Unit,
    onPlay: ((type: String, videoId: String, parentMetaId: String, parentMetaType: String, title: String, logo: String?, poster: String?, background: String?, seasonNumber: Int?, episodeNumber: Int?, episodeTitle: String?, episodeThumbnail: String?, pauseDescription: String?, resumePositionMs: Long?) -> Unit)? = null,
    onOpenMeta: ((MetaPreview) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val uiState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
    val traktAuthUiState by remember {
        TraktAuthRepository.ensureLoaded()
        TraktAuthRepository.uiState
    }.collectAsStateWithLifecycle()
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
    val commentsEnabled by remember {
        TraktCommentsSettings.ensureLoaded()
        TraktCommentsSettings.enabled
    }.collectAsStateWithLifecycle()
    var comments by remember(type, id) { mutableStateOf<List<TraktCommentReview>>(emptyList()) }
    var commentsCurrentPage by remember(type, id) { mutableIntStateOf(0) }
    var commentsPageCount by remember(type, id) { mutableIntStateOf(0) }
    var isCommentsLoading by remember(type, id) { mutableStateOf(false) }
    var isCommentsLoadingMore by remember(type, id) { mutableStateOf(false) }
    var commentsError by remember(type, id) { mutableStateOf<String?>(null) }
    var selectedComment by remember(type, id) { mutableStateOf<TraktCommentReview?>(null) }
    val detailsScope = rememberCoroutineScope()
    var showLibraryListPicker by remember(type, id) { mutableStateOf(false) }
    var pickerTabs by remember(type, id) { mutableStateOf<List<TraktListTab>>(emptyList()) }
    var pickerMembership by remember(type, id) { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var pickerPending by remember(type, id) { mutableStateOf(false) }
    var pickerError by remember(type, id) { mutableStateOf<String?>(null) }

    val shouldShowComments = commentsEnabled &&
        traktAuthUiState.mode == TraktConnectionMode.CONNECTED &&
        requestedMeta != null &&
        requestedMeta.type.lowercase().let { it == "movie" || it == "series" || it == "show" || it == "tv" }

    LaunchedEffect(requestedMeta?.id, shouldShowComments) {
        if (!shouldShowComments || requestedMeta == null) {
            comments = emptyList()
            commentsCurrentPage = 0
            commentsPageCount = 0
            commentsError = null
            return@LaunchedEffect
        }
        isCommentsLoading = true
        commentsError = null
        try {
            val result = TraktCommentsRepository.getCommentsPage(requestedMeta, page = 1)
            comments = result.items
            commentsCurrentPage = result.currentPage
            commentsPageCount = result.pageCount
        } catch (e: Exception) {
            commentsError = e.message ?: "Failed to load comments"
        }
        isCommentsLoading = false
    }

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
                val isTraktConnected = traktAuthUiState.mode == TraktConnectionMode.CONNECTED
                val toggleSaved = remember(meta, isTraktConnected) {
                    {
                        val libraryItem = meta.toLibraryItem(savedAtEpochMs = 0L)
                        if (!isTraktConnected) {
                            LibraryRepository.toggleSaved(libraryItem)
                        } else {
                            detailsScope.launch {
                                pickerPending = true
                                pickerError = null
                                runCatching {
                                    LibraryRepository.pullFromServer(com.nuvio.app.features.profiles.ProfileRepository.activeProfileId)
                                    val tabs = LibraryRepository.traktListTabs()
                                    val snapshot = LibraryRepository.getMembershipSnapshot(libraryItem)
                                    pickerTabs = tabs
                                    pickerMembership = tabs.associate { tab ->
                                        tab.key to (snapshot[tab.key] == true)
                                    }
                                    showLibraryListPicker = true
                                }.onFailure { error ->
                                    pickerError = error.message ?: "Failed to load Trakt lists"
                                }
                                pickerPending = false
                            }
                            Unit
                        }
                    }
                }
                val movieProgress = watchProgressUiState.byVideoId[meta.id]
                    ?.takeUnless { it.isCompleted }
                val cwPrefs by ContinueWatchingPreferencesRepository.uiState.collectAsStateWithLifecycle()
                val seriesAction = remember(watchProgressUiState.entries, watchedUiState.items, meta, todayIsoDate, cwPrefs.upNextFromFurthestEpisode) {
                    meta.seriesPrimaryAction(
                        entries = watchProgressUiState.entries,
                        watchedItems = watchedUiState.items,
                        todayIsoDate = todayIsoDate,
                        preferFurthestEpisode = cwPrefs.upNextFromFurthestEpisode,
                    )
                }
                val seriesActionVideo = remember(seriesAction, meta.id, meta.videos) {
                    val action = seriesAction ?: return@remember null
                    meta.videos.firstOrNull { video ->
                        if (action.seasonNumber != null && action.episodeNumber != null) {
                            video.season == action.seasonNumber &&
                                video.episode == action.episodeNumber
                        } else {
                            buildPlaybackVideoId(
                                parentMetaId = meta.id,
                                seasonNumber = video.season,
                                episodeNumber = video.episode,
                                fallbackVideoId = video.id,
                            ) == action.videoId || video.id == action.videoId
                        }
                    }
                }
                val seriesPauseDescription = remember(seriesActionVideo) {
                    seriesActionVideo?.overview
                }
                val seriesStreamVideoId = remember(seriesAction, seriesActionVideo) {
                    val action = seriesAction ?: return@remember null
                    seriesActionVideo?.id?.takeIf { it.isNotBlank() } ?: action.videoId
                }
                val hasEpisodes = meta.videos.any { it.season != null || it.episode != null }
                val hasProductionSection = remember(meta) {
                    meta.productionCompanies.isNotEmpty() || meta.networks.isNotEmpty()
                }
                val hasAdditionalInfoSection = remember(meta) {
                    meta.status != null ||
                        meta.releaseInfo != null ||
                        meta.runtime != null ||
                        meta.ageRating != null ||
                        meta.country != null ||
                        meta.language != null
                }
                val hasCollectionSection = remember(meta) {
                    meta.collectionName != null && meta.collectionItems.isNotEmpty()
                }
                val hasMoreLikeThisSection = remember(meta) {
                    meta.moreLikeThis.isNotEmpty()
                }
                val hasTrailersSection = remember(meta) {
                    meta.trailers.isNotEmpty()
                }
                val trailerScope = rememberCoroutineScope()
                var selectedTrailer by remember(meta.id) { mutableStateOf<MetaTrailer?>(null) }
                var trailerPlaybackSource by remember(meta.id) { mutableStateOf<TrailerPlaybackSource?>(null) }
                var trailerLoading by remember(meta.id) { mutableStateOf(false) }
                var trailerErrorMessage by remember(meta.id) { mutableStateOf<String?>(null) }
                var trailerRequestToken by remember(meta.id) { mutableIntStateOf(0) }
                val resolveTrailer: (MetaTrailer) -> Unit = remember(meta.id) {
                    { trailer ->
                        selectedTrailer = trailer
                        trailerPlaybackSource = null
                        trailerErrorMessage = null
                        trailerLoading = true
                        trailerRequestToken += 1
                        val currentRequestToken = trailerRequestToken
                        trailerScope.launch {
                            val youtubeUrl = trailer.key.takeIf {
                                it.startsWith("http://") || it.startsWith("https://")
                            } ?: "https://www.youtube.com/watch?v=${trailer.key}"
                            val resolvedSource = runCatching {
                                TrailerPlaybackResolver.resolveFromYouTubeUrl(youtubeUrl)
                            }.getOrNull()
                            if (currentRequestToken != trailerRequestToken) {
                                return@launch
                            }
                            trailerPlaybackSource = resolvedSource
                            trailerErrorMessage = if (resolvedSource == null) {
                                "No playable trailer stream found."
                            } else {
                                null
                            }
                            trailerLoading = false
                        }
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

                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val isTablet = maxWidth >= 720.dp
                    val contentHorizontalPadding = if (isTablet) 32.dp else 18.dp
                    val contentMaxWidth = detailTabletContentMaxWidth(maxWidth, isTablet)

                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState),
                        ) {
                            DetailHero(
                                meta = meta,
                                isTablet = isTablet,
                                contentMaxWidth = contentMaxWidth,
                                scrollOffset = scrollState.value,
                                onHeightChanged = { heroHeightPx = it },
                            )

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = contentHorizontalPadding)
                                    .widthIn(max = if (isTablet) contentMaxWidth else Dp.Unspecified),
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                DetailActionButtons(
                                    playLabel = playButtonLabel,
                                    saveLabel = if (isSaved) "Saved" else "Save",
                                    isSaved = isSaved,
                                    isTablet = isTablet,
                                    onPlayClick = {
                                        when {
                                            (meta.type == "series" || hasEpisodes) && seriesAction != null -> {
                                                onPlay?.invoke(
                                                    meta.type,
                                                    seriesStreamVideoId ?: seriesAction.videoId,
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

                                if (hasEpisodes && hasProductionSection) {
                                    DetailProductionSection(meta = meta)
                                }

                                DetailCastSection(cast = meta.cast)

                                if (shouldShowComments && (isCommentsLoading || comments.isNotEmpty() || !commentsError.isNullOrBlank())) {
                                    DetailCommentsSection(
                                        comments = comments,
                                        isLoading = isCommentsLoading,
                                        isLoadingMore = isCommentsLoadingMore,
                                        canLoadMore = commentsCurrentPage < commentsPageCount,
                                        error = commentsError,
                                        onRetry = {
                                            detailsScope.launch {
                                                isCommentsLoading = true
                                                commentsError = null
                                                try {
                                                    val result = TraktCommentsRepository.getCommentsPage(meta, page = 1, forceRefresh = true)
                                                    comments = result.items
                                                    commentsCurrentPage = result.currentPage
                                                    commentsPageCount = result.pageCount
                                                } catch (e: Exception) {
                                                    commentsError = e.message ?: "Failed to load comments"
                                                }
                                                isCommentsLoading = false
                                            }
                                        },
                                        onLoadMore = {
                                            detailsScope.launch {
                                                isCommentsLoadingMore = true
                                                try {
                                                    val nextPage = commentsCurrentPage + 1
                                                    val result = TraktCommentsRepository.getCommentsPage(meta, page = nextPage)
                                                    val existingIds = comments.map { it.id }.toSet()
                                                    val newComments = result.items.filter { it.id !in existingIds }
                                                    comments = comments + newComments
                                                    commentsCurrentPage = result.currentPage
                                                    commentsPageCount = result.pageCount
                                                } catch (_: Exception) { }
                                                isCommentsLoadingMore = false
                                            }
                                        },
                                        onCommentClick = { review -> selectedComment = review },
                                    )
                                }

                                if (hasTrailersSection) {
                                    DetailTrailersSection(
                                        trailers = meta.trailers,
                                        onTrailerClick = resolveTrailer,
                                    )
                                }

                                if (!hasEpisodes && hasProductionSection) {
                                    DetailProductionSection(meta = meta)
                                }

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
                                        val streamVideoId = video.id.takeIf { it.isNotBlank() } ?: playbackVideoId
                                        val savedProgress = watchProgressUiState.byVideoId[playbackVideoId]
                                            ?.takeUnless { it.isCompleted }
                                        onPlay?.invoke(
                                            meta.type,
                                            streamVideoId,
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

                                if (hasEpisodes && hasAdditionalInfoSection) {
                                    DetailAdditionalInfoSection(meta = meta)
                                }

                                if (!hasEpisodes && hasAdditionalInfoSection) {
                                    DetailAdditionalInfoSection(meta = meta)
                                }

                                if (!hasEpisodes && hasCollectionSection) {
                                    DetailPosterRailSection(
                                        title = meta.collectionName.orEmpty(),
                                        items = meta.collectionItems,
                                        watchedKeys = watchedUiState.watchedKeys,
                                        onPosterClick = onOpenMeta,
                                    )
                                }

                                if (hasMoreLikeThisSection) {
                                    DetailPosterRailSection(
                                        title = "More Like This",
                                        items = meta.moreLikeThis,
                                        watchedKeys = watchedUiState.watchedKeys,
                                        onPosterClick = onOpenMeta,
                                    )
                                }

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
                            val isSelectedEpisodeWatched = remember(meta, selectedEpisode, watchedUiState.watchedKeys) {
                                WatchingState.isEpisodeWatched(
                                    watchedKeys = watchedUiState.watchedKeys,
                                    metaType = meta.type,
                                    metaId = meta.id,
                                    episode = selectedEpisode,
                                )
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
                                WatchingState.areEpisodesWatched(
                                    watchedKeys = watchedUiState.watchedKeys,
                                    metaType = meta.type,
                                    metaId = meta.id,
                                    episodes = previousEpisodes,
                                )
                            }
                            val isSeasonWatched = remember(seasonEpisodes, watchedUiState.watchedKeys) {
                                WatchingState.areEpisodesWatched(
                                    watchedKeys = watchedUiState.watchedKeys,
                                    metaType = meta.type,
                                    metaId = meta.id,
                                    episodes = seasonEpisodes,
                                )
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
                                    WatchingActions.toggleEpisodeWatched(
                                        meta = meta,
                                        episode = selectedEpisode,
                                        isCurrentlyWatched = isSelectedEpisodeWatched,
                                    )
                                },
                                onTogglePreviousWatched = {
                                    WatchingActions.togglePreviousEpisodesWatched(
                                        meta = meta,
                                        episodes = previousEpisodes,
                                        areCurrentlyWatched = arePreviousEpisodesWatched,
                                    )
                                },
                                onToggleSeasonWatched = {
                                    WatchingActions.toggleSeasonWatched(
                                        meta = meta,
                                        episodes = seasonEpisodes,
                                        areCurrentlyWatched = isSeasonWatched,
                                    )
                                },
                            )
                        }

                        TrailerPlayerPopup(
                            visible = selectedTrailer != null,
                            trailerTitle = selectedTrailer?.displayName ?: selectedTrailer?.name.orEmpty(),
                            trailerType = selectedTrailer?.type.orEmpty(),
                            contentTitle = meta.name,
                            playbackSource = trailerPlaybackSource,
                            isLoading = trailerLoading,
                            errorMessage = trailerErrorMessage,
                            onDismiss = {
                                trailerRequestToken += 1
                                trailerLoading = false
                                trailerPlaybackSource = null
                                trailerErrorMessage = null
                                selectedTrailer = null
                            },
                            onRetry = selectedTrailer?.let { trailer ->
                                { resolveTrailer(trailer) }
                            },
                        )

                        TraktListPickerDialog(
                            visible = showLibraryListPicker,
                            title = meta.name,
                            tabs = pickerTabs,
                            membership = pickerMembership,
                            isPending = pickerPending,
                            errorMessage = pickerError,
                            onToggle = { listKey ->
                                pickerMembership = pickerMembership.toMutableMap().apply {
                                    this[listKey] = !(this[listKey] == true)
                                }
                            },
                            onDismiss = {
                                if (!pickerPending) {
                                    showLibraryListPicker = false
                                }
                            },
                            onSave = {
                                detailsScope.launch {
                                    pickerPending = true
                                    pickerError = null
                                    runCatching {
                                        LibraryRepository.applyMembershipChanges(
                                            item = meta.toLibraryItem(savedAtEpochMs = 0L),
                                            desiredMembership = pickerMembership,
                                        )
                                    }.onSuccess {
                                        showLibraryListPicker = false
                                    }.onFailure { error ->
                                        pickerError = error.message ?: "Failed to update Trakt lists"
                                    }
                                    pickerPending = false
                                }
                            },
                        )

                        selectedComment?.let { comment ->
                            val commentIndex = comments.indexOfFirst { it.id == comment.id }.coerceAtLeast(0)
                            CommentDetailSheet(
                                comment = comment,
                                currentIndex = commentIndex,
                                totalCount = comments.size,
                                canGoBack = commentIndex > 0,
                                canGoForward = commentIndex < comments.size - 1,
                                onPrevious = {
                                    if (commentIndex > 0) {
                                        selectedComment = comments[commentIndex - 1]
                                    }
                                },
                                onNext = {
                                    val nextIndex = commentIndex + 1
                                    if (nextIndex < comments.size) {
                                        selectedComment = comments[nextIndex]
                                    }
                                    if (nextIndex >= comments.size - 3 && commentsCurrentPage < commentsPageCount) {
                                        detailsScope.launch {
                                            isCommentsLoadingMore = true
                                            try {
                                                val nextPage = commentsCurrentPage + 1
                                                val result = TraktCommentsRepository.getCommentsPage(meta, page = nextPage)
                                                val existingIds = comments.map { it.id }.toSet()
                                                val newComments = result.items.filter { it.id !in existingIds }
                                                comments = comments + newComments
                                                commentsCurrentPage = result.currentPage
                                                commentsPageCount = result.pageCount
                                            } catch (_: Exception) { }
                                            isCommentsLoadingMore = false
                                        }
                                    }
                                },
                                onDismiss = { selectedComment = null },
                            )
                        }
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

private fun detailTabletContentMaxWidth(maxWidth: Dp, isTablet: Boolean): Dp =
    if (!isTablet) {
        maxWidth
    } else {
        (maxWidth * 0.6f).coerceIn(520.dp, 680.dp)
    }

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun TraktListPickerDialog(
    visible: Boolean,
    title: String,
    tabs: List<TraktListTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    errorMessage: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Choose where to save this title on Trakt",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (!errorMessage.isNullOrBlank()) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items = tabs, key = { it.key }) { tab ->
                        val selected = membership[tab.key] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                )
                                .clickable(enabled = !isPending) { onToggle(tab.key) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tab.title,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (selected) {
                                androidx.compose.material3.Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                ) {
                    Button(
                        onClick = onDismiss,
                        enabled = !isPending,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onSave,
                        enabled = !isPending,
                    ) {
                        if (isPending) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
