package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamsUiState
import com.nuvio.app.features.trakt.TraktScrobbleRepository
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.features.watchprogress.WatchProgressPlaybackSession
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.buildPlaybackVideoId
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PlaybackProgressPersistIntervalMs = 60_000L

@Composable
fun PlayerScreen(
    title: String,
    sourceUrl: String,
    sourceAudioUrl: String? = null,
    providerName: String,
    streamTitle: String,
    streamSubtitle: String?,
    pauseDescription: String? = null,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    logo: String? = null,
    poster: String? = null,
    background: String? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeTitle: String? = null,
    episodeThumbnail: String? = null,
    contentType: String? = null,
    videoId: String? = null,
    parentMetaId: String,
    parentMetaType: String,
    providerAddonId: String? = null,
    initialPositionMs: Long = 0L,
) {
    LockPlayerToLandscape()
    EnterImmersivePlayerMode()
    val playerSettingsUiState by remember {
        PlayerSettingsRepository.ensureLoaded()
        PlayerSettingsRepository.uiState
    }.collectAsStateWithLifecycle()

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val horizontalSafePadding = playerHorizontalSafePadding()
        val metrics = remember(maxWidth) { PlayerLayoutMetrics.fromWidth(maxWidth) }
        val scope = rememberCoroutineScope()
        var controlsVisible by rememberSaveable { mutableStateOf(true) }
        // Active playback state (mutable to support source/episode switching)
        var activeSourceUrl by rememberSaveable { mutableStateOf(sourceUrl) }
        var activeSourceAudioUrl by rememberSaveable { mutableStateOf(sourceAudioUrl) }
        var activeStreamTitle by rememberSaveable { mutableStateOf(streamTitle) }
        var activeStreamSubtitle by rememberSaveable { mutableStateOf(streamSubtitle) }
        var activeProviderName by rememberSaveable { mutableStateOf(providerName) }
        var activeProviderAddonId by rememberSaveable { mutableStateOf(providerAddonId) }
        var activeSeasonNumber by rememberSaveable { mutableStateOf(seasonNumber) }
        var activeEpisodeNumber by rememberSaveable { mutableStateOf(episodeNumber) }
        var activeEpisodeTitle by rememberSaveable { mutableStateOf(episodeTitle) }
        var activeEpisodeThumbnail by rememberSaveable { mutableStateOf(episodeThumbnail) }
        var activeVideoId by rememberSaveable { mutableStateOf(videoId) }
        var activeInitialPositionMs by rememberSaveable { mutableStateOf(initialPositionMs) }
        var shouldPlay by rememberSaveable(activeSourceUrl) { mutableStateOf(true) }
        var resizeMode by rememberSaveable { mutableStateOf(PlayerResizeMode.Fit) }
        var layoutSize by remember { mutableStateOf(IntSize.Zero) }
        var playbackSnapshot by remember { mutableStateOf(PlayerPlaybackSnapshot()) }
        var playerController by remember { mutableStateOf<PlayerEngineController?>(null) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var scrubbingPositionMs by remember { mutableStateOf<Long?>(null) }
        var pausedOverlayVisible by remember { mutableStateOf(false) }
        var gestureMessage by remember { mutableStateOf<String?>(null) }
        var gestureMessageJob by remember { mutableStateOf<Job?>(null) }
        var initialLoadCompleted by remember(activeSourceUrl) { mutableStateOf(false) }
        var initialSeekApplied by remember(activeSourceUrl, activeInitialPositionMs) {
            mutableStateOf(activeInitialPositionMs <= 0L)
        }
        var lastProgressPersistEpochMs by remember(activeSourceUrl) { mutableStateOf(0L) }
        var previousIsPlaying by remember(activeSourceUrl) { mutableStateOf(false) }
        var hasRequestedScrobbleStartForCurrentItem by remember(
            activeSourceUrl,
            activeVideoId,
            activeSeasonNumber,
            activeEpisodeNumber,
        ) { mutableStateOf(false) }
        var hasSentCompletionScrobbleForCurrentItem by remember(
            activeSourceUrl,
            activeVideoId,
            activeSeasonNumber,
            activeEpisodeNumber,
        ) { mutableStateOf(false) }
        val backdropArtwork = background ?: poster
        val displayedPositionMs = scrubbingPositionMs ?: playbackSnapshot.positionMs
        val isEpisode = activeSeasonNumber != null && activeEpisodeNumber != null

        // Sources & Episodes Panel state
        var showSourcesPanel by remember { mutableStateOf(false) }
        var showEpisodesPanel by remember { mutableStateOf(false) }
        var episodeStreamsPanelState by remember { mutableStateOf(EpisodeStreamsPanelState()) }
        val sourceStreamsState by PlayerStreamsRepository.sourceState.collectAsStateWithLifecycle()
        val episodeStreamsRepoState by PlayerStreamsRepository.episodeStreamsState.collectAsStateWithLifecycle()
        val metaUiState by MetaDetailsRepository.uiState.collectAsStateWithLifecycle()
        val allEpisodes = remember(metaUiState.meta?.videos) {
            metaUiState.meta?.videos ?: emptyList()
        }
        val isSeries = parentMetaType == "series"
        val playbackSession = remember(
            contentType,
            parentMetaId,
            parentMetaType,
            activeVideoId,
            title,
            logo,
            poster,
            background,
            activeSeasonNumber,
            activeEpisodeNumber,
            activeEpisodeTitle,
            activeEpisodeThumbnail,
            activeProviderName,
            activeProviderAddonId,
            activeStreamTitle,
            activeStreamSubtitle,
            pauseDescription,
            activeSourceUrl,
            activeSourceAudioUrl,
        ) {
            WatchProgressPlaybackSession(
                contentType = contentType ?: parentMetaType,
                parentMetaId = parentMetaId,
                parentMetaType = parentMetaType,
                videoId = buildPlaybackVideoId(
                    parentMetaId = parentMetaId,
                    seasonNumber = activeSeasonNumber,
                    episodeNumber = activeEpisodeNumber,
                    fallbackVideoId = activeVideoId,
                ),
                title = title,
                logo = logo,
                poster = poster,
                background = background,
                seasonNumber = activeSeasonNumber,
                episodeNumber = activeEpisodeNumber,
                episodeTitle = activeEpisodeTitle,
                episodeThumbnail = activeEpisodeThumbnail,
                providerName = activeProviderName,
                providerAddonId = activeProviderAddonId,
                lastStreamTitle = activeStreamTitle,
                lastStreamSubtitle = activeStreamSubtitle,
                pauseDescription = pauseDescription,
                lastSourceUrl = activeSourceUrl,
            )
        }

        fun currentPlaybackProgressPercent(snapshot: PlayerPlaybackSnapshot = playbackSnapshot): Float {
            val duration = snapshot.durationMs.takeIf { it > 0L } ?: return 0f
            return ((snapshot.positionMs.toFloat() / duration.toFloat()) * 100f)
                .coerceIn(0f, 100f)
        }

        fun currentTraktScrobbleItem() = TraktScrobbleRepository.buildItem(
            contentType = contentType ?: parentMetaType,
            parentMetaId = parentMetaId,
            title = title,
            seasonNumber = activeSeasonNumber,
            episodeNumber = activeEpisodeNumber,
            episodeTitle = activeEpisodeTitle,
        )

        fun emitTraktScrobbleStart() {
            val item = currentTraktScrobbleItem() ?: return
            if (hasRequestedScrobbleStartForCurrentItem) return
            hasRequestedScrobbleStartForCurrentItem = true

            scope.launch {
                TraktScrobbleRepository.scrobbleStart(
                    item = item,
                    progressPercent = currentPlaybackProgressPercent(),
                )
            }
        }

        fun emitTraktScrobbleStop(progressPercent: Float? = null) {
            val item = currentTraktScrobbleItem() ?: return
            val provided = progressPercent
            if (!hasRequestedScrobbleStartForCurrentItem && (provided ?: 0f) < 80f) return

            val percent = provided ?: currentPlaybackProgressPercent()
            scope.launch {
                TraktScrobbleRepository.scrobbleStop(
                    item = item,
                    progressPercent = percent,
                )
            }
            hasRequestedScrobbleStartForCurrentItem = false
        }

        fun emitStopScrobbleForCurrentProgress() {
            val progressPercent = currentPlaybackProgressPercent()
            if (progressPercent >= 1f && progressPercent < 80f) {
                emitTraktScrobbleStop(progressPercent)
                hasSentCompletionScrobbleForCurrentItem = false
                return
            }

            if (progressPercent >= 80f && !hasSentCompletionScrobbleForCurrentItem) {
                hasSentCompletionScrobbleForCurrentItem = true
                emitTraktScrobbleStop(progressPercent)
            }
        }

        fun flushWatchProgress() {
            emitStopScrobbleForCurrentProgress()
            WatchProgressRepository.flushPlaybackProgress(
                session = playbackSession,
                snapshot = playbackSnapshot,
            )
        }

        val onBackWithProgress = remember(onBack, playbackSession, playbackSnapshot) {
            {
                flushWatchProgress()
                onBack()
            }
        }

        var showAudioModal by remember { mutableStateOf(false) }
        var showSubtitleModal by remember { mutableStateOf(false) }
        var audioTracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
        var subtitleTracks by remember { mutableStateOf<List<SubtitleTrack>>(emptyList()) }
        var selectedAudioIndex by remember { mutableStateOf(-1) }
        var selectedSubtitleIndex by remember { mutableStateOf(-1) }
        var selectedAddonSubtitleId by remember { mutableStateOf<String?>(null) }
        var useCustomSubtitles by remember { mutableStateOf(false) }
        var preferredAudioSelectionApplied by rememberSaveable(sourceUrl) { mutableStateOf(false) }
        var preferredSubtitleSelectionApplied by rememberSaveable(sourceUrl) { mutableStateOf(false) }
        var activeSubtitleTab by remember { mutableStateOf(SubtitleTab.BuiltIn) }
        val subtitleStyle = playerSettingsUiState.subtitleStyle
        val addonSubtitles by SubtitleRepository.addonSubtitles.collectAsStateWithLifecycle()
        val isLoadingAddonSubtitles by SubtitleRepository.isLoading.collectAsStateWithLifecycle()

        fun refreshTracks() {
            val ctrl = playerController ?: return
            audioTracks = ctrl.getAudioTracks()
            subtitleTracks = ctrl.getSubtitleTracks()
            val selectedAudio = audioTracks.firstOrNull { it.isSelected }
            if (selectedAudio != null) selectedAudioIndex = selectedAudio.index
            val selectedSub = subtitleTracks.firstOrNull { it.isSelected }
            println("NuvioPlayer refreshTracks: useCustom=$useCustomSubtitles selectedAddonId=$selectedAddonSubtitleId selectedSubIdx=$selectedSubtitleIndex")
            println("NuvioPlayer refreshTracks: found ${subtitleTracks.size} subtitle tracks, selectedTrack=${selectedSub?.index}")
            if (selectedSub != null && !useCustomSubtitles) selectedSubtitleIndex = selectedSub.index

            if (!preferredAudioSelectionApplied) {
                val preferredAudioTargets = resolvePreferredAudioLanguageTargets(
                    preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
                    secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
                    deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
                )
                if (preferredAudioTargets.isEmpty()) {
                    preferredAudioSelectionApplied = true
                } else if (audioTracks.isNotEmpty()) {
                    val preferredAudioIndex = findPreferredTrackIndex(
                        tracks = audioTracks,
                        targets = preferredAudioTargets,
                        language = { track -> track.language },
                    )
                    if (preferredAudioIndex >= 0 && preferredAudioIndex != selectedAudioIndex) {
                        playerController?.selectAudioTrack(preferredAudioIndex)
                        selectedAudioIndex = preferredAudioIndex
                    }
                    preferredAudioSelectionApplied = true
                }
            }

            if (!preferredSubtitleSelectionApplied) {
                val preferredSubtitleTargets = resolvePreferredSubtitleLanguageTargets(
                    preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
                    secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
                    deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
                )

                if (preferredSubtitleTargets.isEmpty()) {
                    if (selectedSubtitleIndex != -1 || subtitleTracks.any { it.isSelected }) {
                        playerController?.selectSubtitleTrack(-1)
                    }
                    selectedSubtitleIndex = -1
                    selectedAddonSubtitleId = null
                    useCustomSubtitles = false
                    preferredSubtitleSelectionApplied = true
                } else if (subtitleTracks.isNotEmpty()) {
                    val preferredSubtitleIndex = findPreferredSubtitleTrackIndex(
                        tracks = subtitleTracks,
                        targets = preferredSubtitleTargets,
                    )
                    if (preferredSubtitleIndex >= 0 && preferredSubtitleIndex != selectedSubtitleIndex) {
                        playerController?.selectSubtitleTrack(preferredSubtitleIndex)
                        selectedSubtitleIndex = preferredSubtitleIndex
                        selectedAddonSubtitleId = null
                        useCustomSubtitles = false
                    } else if (
                        preferredSubtitleIndex < 0 &&
                        normalizeLanguageCode(playerSettingsUiState.preferredSubtitleLanguage) == SubtitleLanguageOption.FORCED
                    ) {
                        if (selectedSubtitleIndex != -1 || subtitleTracks.any { it.isSelected }) {
                            playerController?.selectSubtitleTrack(-1)
                        }
                        selectedSubtitleIndex = -1
                        selectedAddonSubtitleId = null
                        useCustomSubtitles = false
                    }
                    preferredSubtitleSelectionApplied = true
                }
            }

            println("NuvioPlayer refreshTracks: final selectedSubtitleIndex=$selectedSubtitleIndex")
        }

        fun showGestureMessage(message: String) {
            gestureMessageJob?.cancel()
            gestureMessage = message
            gestureMessageJob = scope.launch {
                delay(900)
                gestureMessage = null
            }
        }

        fun togglePlayback() {
            if (playbackSnapshot.isPlaying) {
                shouldPlay = false
                playerController?.pause()
            } else {
                if (playbackSnapshot.isEnded) {
                    playerController?.seekTo(0L)
                }
                shouldPlay = true
                playerController?.play()
            }
            controlsVisible = true
        }

        fun seekBy(offsetMs: Long) {
            playerController?.seekBy(offsetMs)
            controlsVisible = true
            val seconds = offsetMs / 1000L
            if (seconds != 0L) {
                showGestureMessage(if (seconds > 0) "+${seconds}s" else "${seconds}s")
            }
        }

        fun cycleResizeMode() {
            resizeMode = resizeMode.next()
            showGestureMessage(resizeMode.label)
            controlsVisible = true
        }

        fun cyclePlaybackSpeed() {
            val speeds = listOf(1f, 1.25f, 1.5f, 2f)
            val current = playbackSnapshot.playbackSpeed
            val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
            playerController?.setPlaybackSpeed(next)
            showGestureMessage("${next}x")
            controlsVisible = true
        }

        fun switchToSource(stream: StreamItem) {
            val url = stream.directPlaybackUrl ?: return
            if (url == activeSourceUrl) return
            flushWatchProgress()
            // Cache for reuse-last-link
            if (playerSettingsUiState.streamReuseLastLinkEnabled && activeVideoId != null) {
                val cacheKey = StreamLinkCacheRepository.contentKey(
                    contentType ?: parentMetaType,
                    activeVideoId!!,
                )
                StreamLinkCacheRepository.save(
                    contentKey = cacheKey,
                    url = url,
                    streamName = stream.streamLabel,
                    addonName = stream.addonName,
                    addonId = stream.addonId,
                    filename = stream.behaviorHints.filename,
                    videoSize = stream.behaviorHints.videoSize,
                )
            }
            activeSourceUrl = url
            activeSourceAudioUrl = null
            activeStreamTitle = stream.streamLabel
            activeStreamSubtitle = stream.streamSubtitle
            activeProviderName = stream.addonName
            activeProviderAddonId = stream.addonId
            activeInitialPositionMs = 0L
            showSourcesPanel = false
            controlsVisible = true
        }

        fun switchToEpisodeStream(stream: StreamItem, episode: MetaVideo) {
            val url = stream.directPlaybackUrl ?: return
            flushWatchProgress()
            // Cache for reuse-last-link
            if (playerSettingsUiState.streamReuseLastLinkEnabled) {
                val epVideoId = episode.id
                val cacheKey = StreamLinkCacheRepository.contentKey(
                    contentType ?: parentMetaType,
                    epVideoId,
                )
                StreamLinkCacheRepository.save(
                    contentKey = cacheKey,
                    url = url,
                    streamName = stream.streamLabel,
                    addonName = stream.addonName,
                    addonId = stream.addonId,
                    filename = stream.behaviorHints.filename,
                    videoSize = stream.behaviorHints.videoSize,
                )
            }
            activeSourceUrl = url
            activeSourceAudioUrl = null
            activeStreamTitle = stream.streamLabel
            activeStreamSubtitle = stream.streamSubtitle
            activeProviderName = stream.addonName
            activeProviderAddonId = stream.addonId
            activeSeasonNumber = episode.season
            activeEpisodeNumber = episode.episode
            activeEpisodeTitle = episode.title
            activeEpisodeThumbnail = episode.thumbnail
            activeVideoId = episode.id
            activeInitialPositionMs = 0L
            showEpisodesPanel = false
            episodeStreamsPanelState = EpisodeStreamsPanelState()
            PlayerStreamsRepository.clearEpisodeStreams()
            controlsVisible = true
        }

        fun openSourcesPanel() {
            val type = contentType ?: parentMetaType
            val vid = activeVideoId ?: return
            PlayerStreamsRepository.loadSources(type, vid)
            showSourcesPanel = true
            showEpisodesPanel = false
            controlsVisible = false
        }

        fun openEpisodesPanel() {
            // Ensure meta is loaded for episodes
            if (allEpisodes.isEmpty()) {
                MetaDetailsRepository.load(parentMetaType, parentMetaId)
            }
            showEpisodesPanel = true
            showSourcesPanel = false
            controlsVisible = false
        }

        LaunchedEffect(activeSourceUrl, activeSourceAudioUrl) {
            errorMessage = null
            scrubbingPositionMs = null
            initialLoadCompleted = false
            lastProgressPersistEpochMs = 0L
            previousIsPlaying = false
            preferredAudioSelectionApplied = false
            preferredSubtitleSelectionApplied = false
            SubtitleRepository.clear()
            WatchProgressRepository.ensureLoaded()
        }

        LaunchedEffect(playerController, subtitleStyle) {
            playerController?.applySubtitleStyle(subtitleStyle)
        }

        LaunchedEffect(playbackSnapshot.isLoading, playerController) {
            if (!playbackSnapshot.isLoading && playerController != null) {
                refreshTracks()
            }
        }

        LaunchedEffect(
            playerController,
            playbackSnapshot.isLoading,
            preferredAudioSelectionApplied,
            preferredSubtitleSelectionApplied,
        ) {
            if (playerController == null || playbackSnapshot.isLoading) {
                return@LaunchedEffect
            }
            if (preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
                return@LaunchedEffect
            }

            repeat(10) {
                refreshTracks()
                if (preferredAudioSelectionApplied && preferredSubtitleSelectionApplied) {
                    return@LaunchedEffect
                }
                delay(300)
            }
        }

        LaunchedEffect(playerController, playbackSnapshot.isLoading, activeInitialPositionMs, initialSeekApplied) {
            val controller = playerController ?: return@LaunchedEffect
            if (initialSeekApplied || playbackSnapshot.isLoading || activeInitialPositionMs <= 0L) {
                return@LaunchedEffect
            }
            controller.seekTo(activeInitialPositionMs)
            initialSeekApplied = true
        }

        LaunchedEffect(controlsVisible, playbackSnapshot.isPlaying, playbackSnapshot.isLoading, errorMessage) {
            if (!controlsVisible || !playbackSnapshot.isPlaying || playbackSnapshot.isLoading || errorMessage != null) {
                return@LaunchedEffect
            }
            delay(3500)
            controlsVisible = false
        }

        LaunchedEffect(playbackSnapshot.isPlaying, playbackSnapshot.isLoading, playbackSnapshot.durationMs, errorMessage) {
            pausedOverlayVisible = false
            if (playbackSnapshot.isPlaying || playbackSnapshot.isLoading || playbackSnapshot.durationMs <= 0L || errorMessage != null) {
                return@LaunchedEffect
            }
            delay(5000)
            pausedOverlayVisible = true
        }

        LaunchedEffect(playbackSnapshot.positionMs, playbackSnapshot.isPlaying, playbackSnapshot.isEnded, playbackSnapshot.durationMs) {
            if (playbackSnapshot.isEnded) {
                hasSentCompletionScrobbleForCurrentItem = false
                flushWatchProgress()
                previousIsPlaying = false
                return@LaunchedEffect
            }

            if (previousIsPlaying && !playbackSnapshot.isPlaying) {
                flushWatchProgress()
            }

            if (!previousIsPlaying && playbackSnapshot.isPlaying) {
                emitTraktScrobbleStart()
            }

            previousIsPlaying = playbackSnapshot.isPlaying

            if (!playbackSnapshot.isPlaying) {
                return@LaunchedEffect
            }

            val now = WatchProgressClock.nowEpochMs()
            if (now - lastProgressPersistEpochMs < PlaybackProgressPersistIntervalMs) {
                return@LaunchedEffect
            }
            lastProgressPersistEpochMs = now
            WatchProgressRepository.upsertPlaybackProgress(
                session = playbackSession,
                snapshot = playbackSnapshot,
            )
        }

        DisposableEffect(playbackSession.videoId, activeSourceUrl, activeSourceAudioUrl) {
            onDispose {
                flushWatchProgress()
            }
        }

        DisposableEffect(Unit) {
            onDispose {
                PlayerStreamsRepository.clearAll()
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { layoutSize = it }
                .pointerInput(controlsVisible, playerController) {
                    detectTapGestures(
                        onTap = { offset ->
                            val centerStart = layoutSize.width * 0.4f
                            val centerEnd = layoutSize.width * 0.6f
                            if (controlsVisible && offset.x in centerStart..centerEnd) {
                                controlsVisible = false
                            } else {
                                controlsVisible = !controlsVisible
                            }
                        },
                        onDoubleTap = { offset ->
                            when {
                                offset.x < layoutSize.width * 0.4f -> seekBy(-10_000L)
                                offset.x > layoutSize.width * 0.6f -> seekBy(10_000L)
                                else -> controlsVisible = !controlsVisible
                            }
                        },
                    )
                },
        ) {
            PlatformPlayerSurface(
                sourceUrl = activeSourceUrl,
                sourceAudioUrl = activeSourceAudioUrl,
                modifier = Modifier.fillMaxSize(),
                playWhenReady = shouldPlay,
                resizeMode = resizeMode,
                onControllerReady = { controller ->
                    playerController = controller
                },
                onSnapshot = { snapshot ->
                    playbackSnapshot = snapshot
                    if (!snapshot.isLoading) {
                        initialLoadCompleted = true
                    }
                    if (snapshot.isEnded) {
                        shouldPlay = false
                        controlsVisible = true
                    }
                },
                onError = { message ->
                    errorMessage = message
                    if (message != null) {
                        controlsVisible = true
                    }
                },
            )

            if (pausedOverlayVisible && !controlsVisible) {
                PauseMetadataOverlay(
                    title = title,
                    logo = logo,
                    isEpisode = isEpisode,
                    seasonNumber = activeSeasonNumber,
                    episodeNumber = activeEpisodeNumber,
                    episodeTitle = activeEpisodeTitle,
                    pauseDescription = pauseDescription ?: activeStreamSubtitle,
                    providerName = activeProviderName,
                    metrics = metrics,
                    horizontalSafePadding = horizontalSafePadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                PlayerControlsShell(
                    title = title,
                    streamTitle = activeStreamTitle,
                    providerName = activeProviderName,
                    seasonNumber = activeSeasonNumber,
                    episodeNumber = activeEpisodeNumber,
                    episodeTitle = activeEpisodeTitle,
                    playbackSnapshot = playbackSnapshot,
                    displayedPositionMs = displayedPositionMs,
                    metrics = metrics,
                    resizeMode = resizeMode,
                    onBack = onBackWithProgress,
                    onTogglePlayback = ::togglePlayback,
                    onSeekBack = { seekBy(-10_000L) },
                    onSeekForward = { seekBy(10_000L) },
                    onResizeModeClick = ::cycleResizeMode,
                    onSpeedClick = ::cyclePlaybackSpeed,
                    onSubtitleClick = {
                        refreshTracks()
                        showSubtitleModal = true
                    },
                    onAudioClick = {
                        refreshTracks()
                        showAudioModal = true
                    },
                    onSourcesClick = if (activeVideoId != null) {{ openSourcesPanel() }} else null,
                    onEpisodesClick = if (isSeries) {{ openEpisodesPanel() }} else null,
                    onScrubChange = { positionMs -> scrubbingPositionMs = positionMs },
                    onScrubFinished = { positionMs ->
                        scrubbingPositionMs = null
                        playerController?.seekTo(positionMs)
                    },
                    horizontalSafePadding = horizontalSafePadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AnimatedVisibility(
                visible = playerSettingsUiState.showLoadingOverlay && !initialLoadCompleted && errorMessage == null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                OpeningOverlay(
                    artwork = backdropArtwork,
                    logo = logo,
                    title = title,
                    onBack = onBackWithProgress,
                    horizontalSafePadding = horizontalSafePadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AnimatedVisibility(
                visible = gestureMessage != null,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    GestureFeedbackPill(
                        message = gestureMessage.orEmpty(),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.safeContent.only(WindowInsetsSides.Top))
                            .padding(horizontal = horizontalSafePadding)
                            .padding(top = 40.dp),
                    )
                }
            }

            if (errorMessage != null) {
                ErrorModal(
                    message = errorMessage.orEmpty(),
                    onDismiss = onBackWithProgress,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            AudioTrackModal(
                visible = showAudioModal,
                audioTracks = audioTracks,
                selectedIndex = selectedAudioIndex,
                onTrackSelected = { index ->
                    selectedAudioIndex = index
                    playerController?.selectAudioTrack(index)
                    scope.launch {
                        delay(200)
                        showAudioModal = false
                    }
                },
                onDismiss = { showAudioModal = false },
            )

            SubtitleModal(
                visible = showSubtitleModal,
                activeTab = activeSubtitleTab,
                subtitleTracks = subtitleTracks,
                selectedSubtitleIndex = selectedSubtitleIndex,
                addonSubtitles = addonSubtitles,
                selectedAddonSubtitleId = selectedAddonSubtitleId,
                isLoadingAddonSubtitles = isLoadingAddonSubtitles,
                subtitleStyle = subtitleStyle,
                onTabSelected = { activeSubtitleTab = it },
                onBuiltInTrackSelected = { index ->
                    println("NuvioPlayer onBuiltInTrackSelected: index=$index wasCustom=$useCustomSubtitles")
                    val wasCustom = useCustomSubtitles
                    selectedSubtitleIndex = index
                    selectedAddonSubtitleId = null
                    useCustomSubtitles = false
                    if (wasCustom) {
                        playerController?.clearExternalSubtitleAndSelect(index)
                    } else {
                        playerController?.selectSubtitleTrack(index)
                    }
                },
                onAddonSubtitleSelected = { addon ->
                    println("NuvioPlayer onAddonSubtitleSelected: id=${addon.id} url=${addon.url} lang=${addon.language}")
                    selectedAddonSubtitleId = addon.id
                    selectedSubtitleIndex = -1
                    useCustomSubtitles = true
                    println("NuvioPlayer onAddonSubtitleSelected: set useCustomSubtitles=true, calling setSubtitleUri")
                    playerController?.setSubtitleUri(addon.url)
                },
                onFetchAddonSubtitles = {
                    if (contentType != null && activeVideoId != null) {
                        SubtitleRepository.fetchAddonSubtitles(contentType, activeVideoId!!)
                    }
                },
                onStyleChanged = PlayerSettingsRepository::setSubtitleStyle,
                onDismiss = { showSubtitleModal = false },
            )

            // Sources Panel
            PlayerSourcesPanel(
                visible = showSourcesPanel,
                streamsUiState = sourceStreamsState,
                currentStreamUrl = activeSourceUrl,
                currentStreamName = activeStreamTitle,
                onFilterSelected = { PlayerStreamsRepository.selectSourceFilter(it) },
                onStreamSelected = ::switchToSource,
                onReload = {
                    val type = contentType ?: parentMetaType
                    val vid = activeVideoId ?: return@PlayerSourcesPanel
                    PlayerStreamsRepository.loadSources(type, vid, forceRefresh = true)
                },
                onDismiss = {
                    showSourcesPanel = false
                    controlsVisible = true
                },
            )

            // Episodes Panel
            if (isSeries) {
                PlayerEpisodesPanel(
                    visible = showEpisodesPanel,
                    episodes = allEpisodes,
                    currentSeason = activeSeasonNumber,
                    currentEpisode = activeEpisodeNumber,
                    episodeStreamsState = episodeStreamsPanelState.copy(
                        streamsUiState = episodeStreamsRepoState,
                    ),
                    onSeasonSelected = { /* season tab change handled internally */ },
                    onEpisodeSelected = { episode ->
                        val type = contentType ?: parentMetaType
                        PlayerStreamsRepository.loadEpisodeStreams(type, episode.id)
                        episodeStreamsPanelState = EpisodeStreamsPanelState(
                            showStreams = true,
                            selectedEpisode = episode,
                        )
                    },
                    onEpisodeStreamFilterSelected = {
                        PlayerStreamsRepository.selectEpisodeStreamsFilter(it)
                    },
                    onEpisodeStreamSelected = ::switchToEpisodeStream,
                    onBackToEpisodes = {
                        episodeStreamsPanelState = EpisodeStreamsPanelState()
                        PlayerStreamsRepository.clearEpisodeStreams()
                    },
                    onReloadEpisodeStreams = {
                        val episode = episodeStreamsPanelState.selectedEpisode ?: return@PlayerEpisodesPanel
                        val type = contentType ?: parentMetaType
                        PlayerStreamsRepository.loadEpisodeStreams(type, episode.id, forceRefresh = true)
                    },
                    onDismiss = {
                        showEpisodesPanel = false
                        episodeStreamsPanelState = EpisodeStreamsPanelState()
                        PlayerStreamsRepository.clearEpisodeStreams()
                        controlsVisible = true
                    },
                )
            }
        }
    }
}

private fun <T> findPreferredTrackIndex(
    tracks: List<T>,
    targets: List<String>,
    language: (T) -> String?,
): Int {
    if (targets.isEmpty()) return -1
    for (target in targets) {
        val matchIndex = tracks.indexOfFirst { track ->
            languageMatchesPreference(
                trackLanguage = language(track),
                targetLanguage = target,
            )
        }
        if (matchIndex >= 0) {
            return matchIndex
        }
    }
    return -1
}

private fun findPreferredSubtitleTrackIndex(
    tracks: List<SubtitleTrack>,
    targets: List<String>,
): Int {
    if (targets.isEmpty()) return -1

    for ((targetPosition, target) in targets.withIndex()) {
        val normalizedTarget = normalizeLanguageCode(target) ?: continue
        if (normalizedTarget == SubtitleLanguageOption.FORCED) {
            val forcedIndex = tracks.indexOfFirst { it.isForced }
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }

        val matchIndex = tracks.indexOfFirst { track ->
            languageMatchesPreference(
                trackLanguage = track.language,
                targetLanguage = normalizedTarget,
            )
        }
        if (matchIndex >= 0) return matchIndex
    }

    return -1
}
