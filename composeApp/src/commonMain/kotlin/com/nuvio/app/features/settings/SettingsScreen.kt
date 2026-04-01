package com.nuvio.app.features.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.PlatformBackHandler
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.features.mdblist.MdbListSettings
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.TmdbSettingsRepository

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onSwitchProfile: (() -> Unit)? = null,
    onHomescreenClick: () -> Unit = {},
    onContinueWatchingClick: () -> Unit = {},
    onAddonsClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val playerSettingsUiState by remember {
            PlayerSettingsRepository.ensureLoaded()
            PlayerSettingsRepository.uiState
        }.collectAsStateWithLifecycle()

        val selectedTheme by remember {
            ThemeSettingsRepository.ensureLoaded()
            ThemeSettingsRepository.selectedTheme
        }.collectAsStateWithLifecycle()
        val amoledEnabled by remember { ThemeSettingsRepository.amoledEnabled }.collectAsStateWithLifecycle()
        val tmdbSettings by remember {
            TmdbSettingsRepository.ensureLoaded()
            TmdbSettingsRepository.uiState
        }.collectAsStateWithLifecycle()
        val mdbListSettings by remember {
            MdbListSettingsRepository.ensureLoaded()
            MdbListSettingsRepository.uiState
        }.collectAsStateWithLifecycle()

        var currentPage by rememberSaveable { mutableStateOf(SettingsPage.Root.name) }
        val page = remember(currentPage) { SettingsPage.valueOf(currentPage) }
        val previousPage = page.previousPage()

        PlatformBackHandler(
            enabled = previousPage != null,
            onBack = { previousPage?.let { currentPage = it.name } },
        )

        if (maxWidth >= 768.dp) {
            TabletSettingsScreen(
                page = page,
                onPageChange = { currentPage = it.name },
                showLoadingOverlay = playerSettingsUiState.showLoadingOverlay,
                preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
                preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
                secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
                streamReuseLastLinkEnabled = playerSettingsUiState.streamReuseLastLinkEnabled,
                streamReuseLastLinkCacheHours = playerSettingsUiState.streamReuseLastLinkCacheHours,
                decoderPriority = playerSettingsUiState.decoderPriority,
                mapDV7ToHevc = playerSettingsUiState.mapDV7ToHevc,
                tunnelingEnabled = playerSettingsUiState.tunnelingEnabled,
                selectedTheme = selectedTheme,
                onThemeSelected = ThemeSettingsRepository::setTheme,
                amoledEnabled = amoledEnabled,
                onAmoledToggle = ThemeSettingsRepository::setAmoled,
                tmdbSettings = tmdbSettings,
                mdbListSettings = mdbListSettings,
                onSwitchProfile = onSwitchProfile,
                onHomescreenClick = onHomescreenClick,
                onContinueWatchingClick = onContinueWatchingClick,
                onAddonsClick = onAddonsClick,
                onAccountClick = onAccountClick,
            )
        } else {
            MobileSettingsScreen(
                page = page,
                onPageChange = { currentPage = it.name },
                showLoadingOverlay = playerSettingsUiState.showLoadingOverlay,
                preferredAudioLanguage = playerSettingsUiState.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettingsUiState.secondaryPreferredAudioLanguage,
                preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
                secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
                streamReuseLastLinkEnabled = playerSettingsUiState.streamReuseLastLinkEnabled,
                streamReuseLastLinkCacheHours = playerSettingsUiState.streamReuseLastLinkCacheHours,
                decoderPriority = playerSettingsUiState.decoderPriority,
                mapDV7ToHevc = playerSettingsUiState.mapDV7ToHevc,
                tunnelingEnabled = playerSettingsUiState.tunnelingEnabled,
                selectedTheme = selectedTheme,
                onThemeSelected = ThemeSettingsRepository::setTheme,
                amoledEnabled = amoledEnabled,
                onAmoledToggle = ThemeSettingsRepository::setAmoled,
                tmdbSettings = tmdbSettings,
                mdbListSettings = mdbListSettings,
                onSwitchProfile = onSwitchProfile,
                onHomescreenClick = onHomescreenClick,
                onContinueWatchingClick = onContinueWatchingClick,
                onAddonsClick = onAddonsClick,
                onAccountClick = onAccountClick,
            )
        }
    }
}

@Composable
private fun MobileSettingsScreen(
    page: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
    showLoadingOverlay: Boolean,
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    streamReuseLastLinkEnabled: Boolean,
    streamReuseLastLinkCacheHours: Int,
    decoderPriority: Int,
    mapDV7ToHevc: Boolean,
    tunnelingEnabled: Boolean,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    tmdbSettings: TmdbSettings,
    mdbListSettings: MdbListSettings,
    onSwitchProfile: (() -> Unit)? = null,
    onHomescreenClick: () -> Unit = {},
    onContinueWatchingClick: () -> Unit = {},
    onAddonsClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
) {
    NuvioScreen {
        stickyHeader {
            val previousPage = page.previousPage()
            NuvioScreenHeader(
                title = page.title,
                onBack = previousPage?.let { { onPageChange(it) } },
            )
        }

        when (page) {
            SettingsPage.Root -> settingsRootContent(
                isTablet = false,
                onPlaybackClick = { onPageChange(SettingsPage.Playback) },
                onAppearanceClick = { onPageChange(SettingsPage.Appearance) },
                onContentDiscoveryClick = { onPageChange(SettingsPage.ContentDiscovery) },
                onAccountClick = onAccountClick,
                onSwitchProfileClick = onSwitchProfile,
            )
            SettingsPage.Playback -> playbackSettingsContent(
                isTablet = false,
                showLoadingOverlay = showLoadingOverlay,
                preferredAudioLanguage = preferredAudioLanguage,
                secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
                preferredSubtitleLanguage = preferredSubtitleLanguage,
                secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
                streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
                streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
                decoderPriority = decoderPriority,
                mapDV7ToHevc = mapDV7ToHevc,
                tunnelingEnabled = tunnelingEnabled,
            )
            SettingsPage.Appearance -> appearanceSettingsContent(
                isTablet = false,
                selectedTheme = selectedTheme,
                onThemeSelected = onThemeSelected,
                amoledEnabled = amoledEnabled,
                onAmoledToggle = onAmoledToggle,
                onContinueWatchingClick = onContinueWatchingClick,
            )
            SettingsPage.ContentDiscovery -> contentDiscoveryContent(
                isTablet = false,
                onAddonsClick = onAddonsClick,
                onHomescreenClick = onHomescreenClick,
                onTmdbClick = { onPageChange(SettingsPage.TmdbEnrichment) },
                onMdbListClick = { onPageChange(SettingsPage.MdbListRatings) },
            )
            SettingsPage.TmdbEnrichment -> tmdbSettingsContent(
                isTablet = false,
                settings = tmdbSettings,
            )
            SettingsPage.MdbListRatings -> mdbListSettingsContent(
                isTablet = false,
                settings = mdbListSettings,
            )
        }
    }
}

@Composable
private fun TabletSettingsScreen(
    page: SettingsPage,
    onPageChange: (SettingsPage) -> Unit,
    showLoadingOverlay: Boolean,
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    streamReuseLastLinkEnabled: Boolean,
    streamReuseLastLinkCacheHours: Int,
    decoderPriority: Int,
    mapDV7ToHevc: Boolean,
    tunnelingEnabled: Boolean,
    selectedTheme: AppTheme,
    onThemeSelected: (AppTheme) -> Unit,
    amoledEnabled: Boolean,
    onAmoledToggle: (Boolean) -> Unit,
    tmdbSettings: TmdbSettings,
    mdbListSettings: MdbListSettings,
    onSwitchProfile: (() -> Unit)? = null,
    onHomescreenClick: () -> Unit = {},
    onContinueWatchingClick: () -> Unit = {},
    onAddonsClick: () -> Unit = {},
    onAccountClick: () -> Unit = {},
) {
    var selectedCategory by rememberSaveable { mutableStateOf(SettingsCategory.General.name) }
    val activeCategory = SettingsCategory.valueOf(selectedCategory)
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topOffset = max(statusBarPadding + 24.dp, 48.dp) + 64.dp

    Row(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .width(280.dp)
                .fillMaxSize(),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topOffset),
            ) {
                Text(
                    text = "Settings",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 20.dp),
                    style = MaterialTheme.typography.displayLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Spacer(modifier = Modifier.height(10.dp))
                SettingsSidebarItem(
                    label = activeCategory.label,
                    icon = activeCategory.icon,
                    selected = true,
                    onClick = { selectedCategory = activeCategory.name },
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 40.dp,
                top = topOffset,
                end = 40.dp,
                bottom = 40.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                val previousPage = page.previousPage()
                TabletPageHeader(
                    title = if (page == SettingsPage.Root) activeCategory.label else page.title,
                    showBack = previousPage != null,
                    onBack = { previousPage?.let(onPageChange) },
                )
            }
            when (page) {
                SettingsPage.Root -> settingsRootContent(
                    isTablet = true,
                    onPlaybackClick = { onPageChange(SettingsPage.Playback) },
                    onAppearanceClick = { onPageChange(SettingsPage.Appearance) },
                    onContentDiscoveryClick = { onPageChange(SettingsPage.ContentDiscovery) },
                    onAccountClick = onAccountClick,
                    onSwitchProfileClick = onSwitchProfile,
                )
                SettingsPage.Playback -> playbackSettingsContent(
                    isTablet = true,
                    showLoadingOverlay = showLoadingOverlay,
                    preferredAudioLanguage = preferredAudioLanguage,
                    secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
                    preferredSubtitleLanguage = preferredSubtitleLanguage,
                    secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
                    streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
                    streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
                    decoderPriority = decoderPriority,
                    mapDV7ToHevc = mapDV7ToHevc,
                    tunnelingEnabled = tunnelingEnabled,
                )
                SettingsPage.Appearance -> appearanceSettingsContent(
                    isTablet = true,
                    selectedTheme = selectedTheme,
                    onThemeSelected = onThemeSelected,
                    amoledEnabled = amoledEnabled,
                    onAmoledToggle = onAmoledToggle,
                    onContinueWatchingClick = onContinueWatchingClick,
                )
                SettingsPage.ContentDiscovery -> contentDiscoveryContent(
                    isTablet = true,
                    onAddonsClick = onAddonsClick,
                    onHomescreenClick = onHomescreenClick,
                    onTmdbClick = { onPageChange(SettingsPage.TmdbEnrichment) },
                    onMdbListClick = { onPageChange(SettingsPage.MdbListRatings) },
                )
                SettingsPage.TmdbEnrichment -> tmdbSettingsContent(
                    isTablet = true,
                    settings = tmdbSettings,
                )
                SettingsPage.MdbListRatings -> mdbListSettingsContent(
                    isTablet = true,
                    settings = mdbListSettings,
                )
            }
        }
    }
}
