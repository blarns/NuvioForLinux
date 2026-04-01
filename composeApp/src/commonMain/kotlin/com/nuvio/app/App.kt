package com.nuvio.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.core.ui.nuvioBottomNavigationBarInsets
import com.nuvio.app.core.ui.NuvioPosterActionSheet
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.auth.AuthScreen
import com.nuvio.app.features.catalog.CatalogRepository
import com.nuvio.app.features.catalog.CatalogScreen
import com.nuvio.app.features.catalog.INTERNAL_LIBRARY_MANIFEST_URL
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaDetailsScreen
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.HomeScreen
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.library.LibrarySection
import com.nuvio.app.features.library.LibrarySourceMode
import com.nuvio.app.features.library.LibraryScreen
import com.nuvio.app.features.library.toLibraryItem
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.PlayerRoute
import com.nuvio.app.features.player.PlayerScreen
import com.nuvio.app.features.profiles.NuvioProfile
import com.nuvio.app.features.profiles.ProfileEditScreen
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.ProfileSelectionScreen
import com.nuvio.app.features.profiles.ProfileSwitcherTab
import com.nuvio.app.features.search.SearchScreen
import com.nuvio.app.features.settings.SettingsScreen
import com.nuvio.app.features.settings.HomescreenSettingsScreen
import com.nuvio.app.features.settings.ContinueWatchingSettingsScreen
import com.nuvio.app.features.settings.AddonsSettingsScreen
import com.nuvio.app.features.settings.AccountSettingsScreen
import com.nuvio.app.features.settings.ThemeSettingsRepository
import com.nuvio.app.features.streams.StreamContext
import com.nuvio.app.features.streams.StreamContextStore
import com.nuvio.app.features.streams.StreamLinkCacheRepository
import com.nuvio.app.features.streams.StreamsRepository
import com.nuvio.app.features.streams.StreamsScreen
import com.nuvio.app.features.tmdb.TmdbService
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.watched.WatchedRepository
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watching.application.WatchingActions
import com.nuvio.app.features.watching.application.WatchingState
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_logo_wordmark
import org.jetbrains.compose.resources.painterResource

@Serializable
object TabsRoute

@Serializable
data class DetailRoute(val type: String, val id: String)

@Serializable
object HomescreenSettingsRoute

@Serializable
object ContinueWatchingSettingsRoute

@Serializable
object AddonsSettingsRoute

@Serializable
object AccountSettingsRoute

@Serializable
data class StreamRoute(
    val type: String,
    val videoId: String,
    val parentMetaId: String? = null,
    val parentMetaType: String? = null,
    val title: String,
    val logo: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val episodeThumbnail: String? = null,
    val streamContextId: Long? = null,
    val resumePositionMs: Long? = null,
)

@Serializable
data class CatalogRoute(
    val title: String,
    val subtitle: String,
    val manifestUrl: String,
    val type: String,
    val catalogId: String,
    val supportsPagination: Boolean = false,
    val genre: String? = null,
)

enum class AppScreenTab {
    Home,
    Search,
    Library,
    Settings,
}

private enum class AppGateScreen {
    Loading,
    Auth,
    ProfileSelection,
    ProfileEdit,
    Main,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .crossfade(true)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
    }
    val selectedTheme by remember {
        ThemeSettingsRepository.ensureLoaded()
        ThemeSettingsRepository.selectedTheme
    }.collectAsStateWithLifecycle()
    val amoledEnabled by remember { ThemeSettingsRepository.amoledEnabled }.collectAsStateWithLifecycle()

    NuvioTheme(appTheme = selectedTheme, amoled = amoledEnabled) {
        LaunchedEffect(Unit) {
            AuthRepository.initialize()
        }

        val authState by AuthRepository.state.collectAsStateWithLifecycle()
        var gateScreen by rememberSaveable { mutableStateOf(AppGateScreen.Loading.name) }
        var editingProfile by remember { mutableStateOf<NuvioProfile?>(null) }
        var isNewProfile by remember { mutableStateOf(false) }

        LaunchedEffect(authState) {
            when (authState) {
                is AuthState.Loading -> gateScreen = AppGateScreen.Loading.name
                is AuthState.Unauthenticated -> {
                    ProfileRepository.clearInMemory()
                    gateScreen = AppGateScreen.Auth.name
                }
                is AuthState.Authenticated -> {
                    val authenticatedState = authState as AuthState.Authenticated
                    ProfileRepository.ensureLoaded(authenticatedState.userId)
                    if (gateScreen == AppGateScreen.Loading.name || gateScreen == AppGateScreen.Auth.name) {
                        gateScreen = AppGateScreen.ProfileSelection.name
                    }
                }
            }
        }

        AnimatedContent(
            targetState = gateScreen,
            label = "app_gate",
            transitionSpec = {
                (fadeIn(tween(400)) + scaleIn(tween(400), initialScale = 0.94f))
                    .togetherWith(fadeOut(tween(250)))
            },
        ) { currentGate ->
            when (currentGate) {
                AppGateScreen.Loading.name -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                AppGateScreen.Auth.name -> {
                    AuthScreen(modifier = Modifier.fillMaxSize())
                }
                AppGateScreen.ProfileSelection.name -> {
                    ProfileSelectionScreen(
                        onProfileSelected = { profile ->
                            ProfileRepository.selectProfile(profile.profileIndex)
                            SyncManager.pullAllForProfile(profile.profileIndex)
                            gateScreen = AppGateScreen.Main.name
                        },
                        onEditProfile = { profile ->
                            editingProfile = profile
                            isNewProfile = false
                            gateScreen = AppGateScreen.ProfileEdit.name
                        },
                        onAddProfile = {
                            editingProfile = null
                            isNewProfile = true
                            gateScreen = AppGateScreen.ProfileEdit.name
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AppGateScreen.ProfileEdit.name -> {
                    ProfileEditScreen(
                        profile = editingProfile,
                        onBack = { gateScreen = AppGateScreen.ProfileSelection.name },
                        onSaved = { gateScreen = AppGateScreen.ProfileSelection.name },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                AppGateScreen.Main.name -> {
                    MainAppContent(
                        onSwitchProfile = {
                            gateScreen = AppGateScreen.ProfileSelection.name
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainAppContent(
    onSwitchProfile: () -> Unit = {},
) {
        val navController = rememberNavController()
        val hapticFeedback = LocalHapticFeedback.current
        val coroutineScope = rememberCoroutineScope()
        var selectedTab by rememberSaveable { mutableStateOf(AppScreenTab.Home) }
        var selectedPosterForActions by remember { mutableStateOf<MetaPreview?>(null) }
        val libraryUiState by remember {
            LibraryRepository.ensureLoaded()
            LibraryRepository.uiState
        }.collectAsStateWithLifecycle()
    val watchedUiState by remember {
        WatchedRepository.ensureLoaded()
        WatchedRepository.uiState
    }.collectAsStateWithLifecycle()
    var initialHomeReady by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(5_000)
        initialHomeReady = true
    }
    var profileSwitchLoading by remember { mutableStateOf(false) }

        val onPlay: (String, String, String, String, String, String?, String?, String?, Int?, Int?, String?, String?, String?, Long?) -> Unit =
            { type, videoId, parentMetaId, parentMetaType, title, logo, poster, background, seasonNumber, episodeNumber, episodeTitle, episodeThumbnail, pauseDescription, resumePositionMs ->
                val streamContextId = pauseDescription
                    ?.takeIf { it.isNotBlank() }
                    ?.let { StreamContextStore.put(StreamContext(pauseDescription = it)) }
                navController.navigate(
                    StreamRoute(
                        type = type,
                        videoId = videoId,
                        parentMetaId = parentMetaId,
                        parentMetaType = parentMetaType,
                        title = title,
                        logo = logo,
                        poster = poster,
                        background = background,
                        seasonNumber = seasonNumber,
                        episodeNumber = episodeNumber,
                        episodeTitle = episodeTitle,
                        episodeThumbnail = episodeThumbnail,
                        streamContextId = streamContextId,
                        resumePositionMs = resumePositionMs,
                    )
                )
            }

        val onCatalogClick: (HomeCatalogSection) -> Unit = { section ->
            navController.navigate(
                CatalogRoute(
                    title = section.title,
                    subtitle = section.subtitle,
                    manifestUrl = section.manifestUrl,
                    type = section.type,
                    catalogId = section.catalogId,
                    supportsPagination = section.supportsPagination,
                ),
            )
        }

        val onLibrarySectionViewAllClick: (LibrarySection) -> Unit = { section ->
            navController.navigate(
                CatalogRoute(
                    title = section.displayTitle,
                    subtitle = if (libraryUiState.sourceMode == LibrarySourceMode.TRAKT) {
                        "Trakt Library"
                    } else {
                        "Library"
                    },
                    manifestUrl = INTERNAL_LIBRARY_MANIFEST_URL,
                    type = section.items.firstOrNull()?.type ?: "movie",
                    catalogId = section.type,
                    supportsPagination = false,
                ),
            )
        }

        val onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
            val streamContextId = item.pauseDescription
                ?.takeIf { it.isNotBlank() }
                ?.let { StreamContextStore.put(StreamContext(pauseDescription = it)) }
            navController.navigate(
                StreamRoute(
                    type = item.parentMetaType,
                    videoId = item.videoId,
                    parentMetaId = item.parentMetaId,
                    parentMetaType = item.parentMetaType,
                    title = item.title,
                    logo = item.logo,
                    poster = item.poster,
                    background = item.background,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    episodeTitle = item.episodeTitle,
                    episodeThumbnail = item.episodeThumbnail,
                    streamContextId = streamContextId,
                    resumePositionMs = item.resumePositionMs,
                ),
            )
        }

        val onContinueWatchingLongPress: (ContinueWatchingItem) -> Unit = { item ->
            navController.navigate(
                DetailRoute(
                    type = item.parentMetaType,
                    id = item.parentMetaId,
                ),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            NavHost(
                navController = navController,
                startDestination = TabsRoute,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable<TabsRoute> {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val isTabletLayout = maxWidth >= 768.dp
                        val onProfileSelected: (NuvioProfile) -> Unit = { profile ->
                            profileSwitchLoading = true
                            selectedTab = AppScreenTab.Home
                            ProfileRepository.selectProfile(profile.profileIndex)
                            com.nuvio.app.core.sync.SyncManager.pullAllForProfile(profile.profileIndex)
                        }

                        Scaffold(
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(if (initialHomeReady) 1f else 0f),
                            containerColor = MaterialTheme.colorScheme.background,
                            contentWindowInsets = WindowInsets(0),
                            bottomBar = {
                                if (!isTabletLayout) {
                                    val navigationItemColors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    NavigationBar(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                        windowInsets = nuvioBottomNavigationBarInsets(),
                                    ) {
                                        NavigationBarItem(
                                            selected = selectedTab == AppScreenTab.Home,
                                            onClick = { selectedTab = AppScreenTab.Home },
                                            icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                                            label = { Text("Home") },
                                            colors = navigationItemColors,
                                        )
                                        NavigationBarItem(
                                            selected = selectedTab == AppScreenTab.Search,
                                            onClick = { selectedTab = AppScreenTab.Search },
                                            icon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                                            label = { Text("Search") },
                                            colors = navigationItemColors,
                                        )
                                        NavigationBarItem(
                                            selected = selectedTab == AppScreenTab.Library,
                                            onClick = { selectedTab = AppScreenTab.Library },
                                            icon = { Icon(Icons.Rounded.VideoLibrary, contentDescription = null) },
                                            label = { Text("Library") },
                                            colors = navigationItemColors,
                                        )
                                        NavigationBarItem(
                                            selected = selectedTab == AppScreenTab.Settings,
                                            onClick = { selectedTab = AppScreenTab.Settings },
                                            icon = {
                                                ProfileSwitcherTab(
                                                    selected = selectedTab == AppScreenTab.Settings,
                                                    onClick = { selectedTab = AppScreenTab.Settings },
                                                    onProfileSelected = onProfileSelected,
                                                )
                                            },
                                            label = { Text("Profile") },
                                            colors = navigationItemColors,
                                        )
                                    }
                                }
                            },
                        ) { innerPadding ->
                            Box(modifier = Modifier.fillMaxSize()) {
                                AppTabHost(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding),
                                    selectedTab = selectedTab,
                                    onCatalogClick = onCatalogClick,
                                    onPosterClick = { meta ->
                                        navController.navigate(DetailRoute(type = meta.type, id = meta.id))
                                    },
                                    onPosterLongClick = { meta ->
                                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                        selectedPosterForActions = meta
                                    },
                                    onLibraryPosterClick = { item ->
                                        navController.navigate(DetailRoute(type = item.type, id = item.id))
                                    },
                                    onLibrarySectionViewAllClick = onLibrarySectionViewAllClick,
                                    onContinueWatchingClick = onContinueWatchingClick,
                                    onContinueWatchingLongPress = onContinueWatchingLongPress,
                                    onSwitchProfile = onSwitchProfile,
                                    onHomescreenSettingsClick = { navController.navigate(HomescreenSettingsRoute) },
                                    onContinueWatchingSettingsClick = { navController.navigate(ContinueWatchingSettingsRoute) },
                                    onAddonsSettingsClick = { navController.navigate(AddonsSettingsRoute) },
                                    onAccountSettingsClick = { navController.navigate(AccountSettingsRoute) },
                                    onInitialHomeContentRendered = { initialHomeReady = true },
                                )

                                if (isTabletLayout) {
                                    TabletFloatingTopBar(
                                        selectedTab = selectedTab,
                                        onTabSelected = { selectedTab = it },
                                        onProfileSelected = onProfileSelected,
                                    )
                                }
                            }
                        }
                    }
                }
                composable<DetailRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<DetailRoute>()
                    MetaDetailsScreen(
                        type = route.type,
                        id = route.id,
                        onBack = {
                            MetaDetailsRepository.clear()
                            navController.popBackStack()
                        },
                        onPlay = onPlay,
                        onOpenMeta = { preview ->
                            coroutineScope.launch {
                                val resolvedId = if (preview.id.startsWith("tmdb:")) {
                                    val tmdbId = preview.id.removePrefix("tmdb:").toIntOrNull()
                                    tmdbId?.let {
                                        TmdbService.tmdbToImdb(
                                            tmdbId = it,
                                            mediaType = preview.type,
                                        )
                                    } ?: preview.id
                                } else {
                                    preview.id
                                }
                                navController.navigate(
                                    DetailRoute(
                                        type = preview.type,
                                        id = resolvedId,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable<StreamRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<StreamRoute>()
                    val pauseDescription = remember(route.streamContextId) {
                        route.streamContextId?.let { contextId ->
                            StreamContextStore.get(contextId)?.pauseDescription
                        }
                    }

                    val playerSettings by remember {
                        PlayerSettingsRepository.ensureLoaded()
                        PlayerSettingsRepository.uiState
                    }.collectAsStateWithLifecycle()

                    // Reuse Last Link: auto-play from cache if enabled (only on first entry)
                    var reuseHandled by rememberSaveable(route.videoId) { mutableStateOf(false) }
                    LaunchedEffect(route.videoId, playerSettings.streamReuseLastLinkEnabled) {
                        if (reuseHandled) return@LaunchedEffect
                        reuseHandled = true
                        if (!playerSettings.streamReuseLastLinkEnabled) return@LaunchedEffect
                        val cacheKey = StreamLinkCacheRepository.contentKey(route.type, route.videoId)
                        val maxAgeMs = playerSettings.streamReuseLastLinkCacheHours * 60L * 60L * 1000L
                        val cached = StreamLinkCacheRepository.getValid(cacheKey, maxAgeMs)
                        if (cached != null) {
                            val launchId = PlayerLaunchStore.put(
                                PlayerLaunch(
                                    title = route.title,
                                    sourceUrl = cached.url,
                                    logo = route.logo,
                                    poster = route.poster,
                                    background = route.background,
                                    seasonNumber = route.seasonNumber,
                                    episodeNumber = route.episodeNumber,
                                    episodeTitle = route.episodeTitle,
                                    episodeThumbnail = route.episodeThumbnail,
                                    streamTitle = cached.streamName,
                                    streamSubtitle = null,
                                    pauseDescription = pauseDescription,
                                    providerName = cached.addonName,
                                    providerAddonId = cached.addonId,
                                    contentType = route.type,
                                    videoId = route.videoId,
                                    parentMetaId = route.parentMetaId ?: route.videoId,
                                    parentMetaType = route.parentMetaType ?: route.type,
                                    initialPositionMs = route.resumePositionMs ?: 0L,
                                )
                            )
                            route.streamContextId?.let(StreamContextStore::remove)
                            navController.navigate(PlayerRoute(launchId = launchId)) {
                                popUpTo<StreamRoute> { inclusive = true }
                            }
                        }
                    }

                    StreamsScreen(
                        type = route.type,
                        videoId = route.videoId,
                        parentMetaId = route.parentMetaId ?: route.videoId,
                        parentMetaType = route.parentMetaType ?: route.type,
                        title = route.title,
                        logo = route.logo,
                        poster = route.poster,
                        background = route.background,
                        seasonNumber = route.seasonNumber,
                        episodeNumber = route.episodeNumber,
                        episodeTitle = route.episodeTitle,
                        episodeThumbnail = route.episodeThumbnail,
                        resumePositionMs = route.resumePositionMs,
                        onStreamSelected = { stream ->
                            val sourceUrl = stream.directPlaybackUrl
                            if (sourceUrl != null) {
                                // Persist for Reuse Last Link
                                if (playerSettings.streamReuseLastLinkEnabled) {
                                    val cacheKey = StreamLinkCacheRepository.contentKey(route.type, route.videoId)
                                    StreamLinkCacheRepository.save(
                                        contentKey = cacheKey,
                                        url = sourceUrl,
                                        streamName = stream.streamLabel,
                                        addonName = stream.addonName,
                                        addonId = stream.addonId,
                                        filename = stream.behaviorHints.filename,
                                        videoSize = stream.behaviorHints.videoSize,
                                    )
                                }
                                val launchId = PlayerLaunchStore.put(
                                    PlayerLaunch(
                                        title = route.title,
                                        sourceUrl = sourceUrl,
                                        logo = route.logo,
                                        poster = route.poster,
                                        background = route.background,
                                        seasonNumber = route.seasonNumber,
                                        episodeNumber = route.episodeNumber,
                                        episodeTitle = route.episodeTitle,
                                        episodeThumbnail = route.episodeThumbnail,
                                        streamTitle = stream.streamLabel,
                                        streamSubtitle = stream.streamSubtitle,
                                        pauseDescription = pauseDescription,
                                        providerName = stream.addonName,
                                        providerAddonId = stream.addonId,
                                        contentType = route.type,
                                        videoId = route.videoId,
                                        parentMetaId = route.parentMetaId ?: route.videoId,
                                        parentMetaType = route.parentMetaType ?: route.type,
                                        initialPositionMs = route.resumePositionMs ?: 0L,
                                    )
                                )
                                route.streamContextId?.let(StreamContextStore::remove)
                                navController.navigate(
                                    PlayerRoute(launchId = launchId)
                                )
                            }
                        },
                        onBack = {
                            route.streamContextId?.let(StreamContextStore::remove)
                            StreamsRepository.clear()
                            navController.popBackStack()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable<PlayerRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<PlayerRoute>()
                    val launch = remember(route.launchId) { PlayerLaunchStore.get(route.launchId) }
                    if (launch == null) {
                        LaunchedEffect(route.launchId) {
                            navController.popBackStack()
                        }
                        Box(modifier = Modifier.fillMaxSize())
                        return@composable
                    }
                    PlayerScreen(
                        title = launch.title,
                        sourceUrl = launch.sourceUrl,
                        sourceAudioUrl = launch.sourceAudioUrl,
                        logo = launch.logo,
                        poster = launch.poster,
                        background = launch.background,
                        seasonNumber = launch.seasonNumber,
                        episodeNumber = launch.episodeNumber,
                        episodeTitle = launch.episodeTitle,
                        episodeThumbnail = launch.episodeThumbnail,
                        streamTitle = launch.streamTitle,
                        streamSubtitle = launch.streamSubtitle,
                        pauseDescription = launch.pauseDescription,
                        providerName = launch.providerName,
                        providerAddonId = launch.providerAddonId,
                        contentType = launch.contentType,
                        videoId = launch.videoId,
                        parentMetaId = launch.parentMetaId,
                        parentMetaType = launch.parentMetaType,
                        initialPositionMs = launch.initialPositionMs,
                        onBack = {
                            PlayerLaunchStore.remove(route.launchId)
                            navController.popBackStack()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable<CatalogRoute> { backStackEntry ->
                    val route = backStackEntry.toRoute<CatalogRoute>()
                    CatalogScreen(
                        title = route.title,
                        subtitle = route.subtitle,
                        manifestUrl = route.manifestUrl,
                        type = route.type,
                        catalogId = route.catalogId,
                        supportsPagination = route.supportsPagination,
                        genre = route.genre,
                        onBack = {
                            CatalogRepository.clear()
                            navController.popBackStack()
                        },
                        onPosterClick = { meta ->
                            navController.navigate(DetailRoute(type = meta.type, id = meta.id))
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                composable<HomescreenSettingsRoute> {
                    HomescreenSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<ContinueWatchingSettingsRoute> {
                    ContinueWatchingSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<AddonsSettingsRoute> {
                    AddonsSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
                composable<AccountSettingsRoute> {
                    AccountSettingsScreen(
                        onBack = { navController.popBackStack() },
                    )
                }
            }

            NuvioPosterActionSheet(
                item = selectedPosterForActions,
                isSaved = selectedPosterForActions?.let { preview ->
                    LibraryRepository.isSaved(preview.id)
                } == true,
                isWatched = selectedPosterForActions?.let { preview ->
                    WatchingState.isPosterWatched(
                        watchedKeys = watchedUiState.watchedKeys,
                        item = preview,
                    )
                } == true,
                onDismiss = { selectedPosterForActions = null },
                onToggleLibrary = {
                    selectedPosterForActions?.let { preview ->
                        LibraryRepository.toggleSaved(preview.toLibraryItem(savedAtEpochMs = 0L))
                    }
                },
                onToggleWatched = {
                    selectedPosterForActions?.let { preview ->
                        coroutineScope.launch {
                            WatchingActions.togglePosterWatched(preview)
                        }
                    }
                },
            )

            androidx.compose.animation.AnimatedVisibility(
                visible = !initialHomeReady || profileSwitchLoading,
                enter = fadeIn(),
                exit = fadeOut(androidx.compose.animation.core.tween(400)),
            ) {
                AppLaunchOverlay(modifier = Modifier.fillMaxSize())
            }

            // Auto-dismiss profile switch overlay
            if (profileSwitchLoading) {
                LaunchedEffect(Unit) {
                    // Brief loading screen while home refreshes for the new profile
                    kotlinx.coroutines.delay(1200)
                    profileSwitchLoading = false
                }
            }
        }
}

@Composable
private fun AppTabHost(
    selectedTab: AppScreenTab,
    modifier: Modifier = Modifier,
    onCatalogClick: ((HomeCatalogSection) -> Unit)? = null,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
    onLibraryPosterClick: ((LibraryItem) -> Unit)? = null,
    onLibrarySectionViewAllClick: ((LibrarySection) -> Unit)? = null,
    onContinueWatchingClick: ((ContinueWatchingItem) -> Unit)? = null,
    onContinueWatchingLongPress: ((ContinueWatchingItem) -> Unit)? = null,
    onSwitchProfile: (() -> Unit)? = null,
    onHomescreenSettingsClick: () -> Unit = {},
    onContinueWatchingSettingsClick: () -> Unit = {},
    onAddonsSettingsClick: () -> Unit = {},
    onAccountSettingsClick: () -> Unit = {},
    onInitialHomeContentRendered: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        keepAliveTab(
            selected = selectedTab == AppScreenTab.Home,
        ) {
            HomeScreen(
                modifier = Modifier.fillMaxSize(),
                onCatalogClick = onCatalogClick,
                onPosterClick = onPosterClick,
                onPosterLongClick = onPosterLongClick,
                onContinueWatchingClick = onContinueWatchingClick,
                onContinueWatchingLongPress = onContinueWatchingLongPress,
                onFirstCatalogRendered = onInitialHomeContentRendered,
            )
        }
        keepAliveTab(
            selected = selectedTab == AppScreenTab.Search,
        ) {
            SearchScreen(
                modifier = Modifier.fillMaxSize(),
                onPosterClick = onPosterClick,
                onPosterLongClick = onPosterLongClick,
            )
        }
        keepAliveTab(
            selected = selectedTab == AppScreenTab.Library,
        ) {
            LibraryScreen(
                modifier = Modifier.fillMaxSize(),
                onPosterClick = onLibraryPosterClick,
                onSectionViewAllClick = onLibrarySectionViewAllClick,
            )
        }
        keepAliveTab(
            selected = selectedTab == AppScreenTab.Settings,
        ) {
            SettingsScreen(
                modifier = Modifier.fillMaxSize(),
                onSwitchProfile = onSwitchProfile,
                onHomescreenClick = onHomescreenSettingsClick,
                onContinueWatchingClick = onContinueWatchingSettingsClick,
                onAddonsClick = onAddonsSettingsClick,
                onAccountClick = onAccountSettingsClick,
            )
        }
    }
}

@Composable
private fun TabletFloatingTopBar(
    selectedTab: AppScreenTab,
    onTabSelected: (AppScreenTab) -> Unit,
    onProfileSelected: (NuvioProfile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusBarPadding + 10.dp, bottom = 8.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shape = RoundedCornerShape(999.dp),
            tonalElevation = 4.dp,
            shadowElevation = 10.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TabletTopPillItem(
                    label = "Home",
                    selected = selectedTab == AppScreenTab.Home,
                    onClick = { onTabSelected(AppScreenTab.Home) },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Home,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                TabletTopPillItem(
                    label = "Search",
                    selected = selectedTab == AppScreenTab.Search,
                    onClick = { onTabSelected(AppScreenTab.Search) },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                TabletTopPillItem(
                    label = "Library",
                    selected = selectedTab == AppScreenTab.Library,
                    onClick = { onTabSelected(AppScreenTab.Library) },
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.VideoLibrary,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                )
                Surface(
                    color = if (selectedTab == AppScreenTab.Settings) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProfileSwitcherTab(
                            selected = selectedTab == AppScreenTab.Settings,
                            onClick = { onTabSelected(AppScreenTab.Settings) },
                            onProfileSelected = onProfileSelected,
                        )
                        Text(
                            text = "Profile",
                            modifier = Modifier.clickable { onTabSelected(AppScreenTab.Settings) },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedTab == AppScreenTab.Settings) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabletTopPillItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    Surface(
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(999.dp),
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun AppLaunchOverlay(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .zIndex(10f),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(Res.drawable.app_logo_wordmark),
                contentDescription = "Nuvio",
                modifier = Modifier
                    .fillMaxWidth(0.48f)
                    .height(44.dp),
                contentScale = ContentScale.Fit,
            )
            Spacer(modifier = Modifier.height(24.dp))
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun BoxScope.keepAliveTab(
    selected: Boolean,
    content: @Composable () -> Unit,
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "tab_content_alpha",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(contentAlpha)
            .zIndex(if (selected) 1f else 0f),
    ) {
        content()
    }
}
