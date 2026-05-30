package com.nuvio.app.features.plugins

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

actual object PluginRepository {
    actual val uiState: StateFlow<PluginsUiState> = MutableStateFlow(PluginsUiState())

    actual fun initialize() {}
    actual fun onProfileChanged(profileId: Int) {}
    actual fun clearLocalState() {}
    actual suspend fun pullFromServer(profileId: Int) {}
    actual suspend fun addRepository(rawUrl: String): AddPluginRepositoryResult = AddPluginRepositoryResult.Error("Not supported")
    actual fun removeRepository(manifestUrl: String) {}
    actual fun refreshAll() {}
    actual fun refreshRepository(manifestUrl: String, pushAfterRefresh: Boolean) {}
    actual fun toggleScraper(scraperId: String, enabled: Boolean) {}
    actual fun setPluginsEnabled(enabled: Boolean) {}
    actual fun setGroupStreamsByRepository(enabled: Boolean) {}
    actual fun getEnabledScrapersForType(type: String): List<PluginScraper> = emptyList()
    actual suspend fun testScraper(scraperId: String): Result<List<PluginRuntimeResult>> = Result.failure(Exception("Not supported"))
    actual suspend fun executeScraper(
        scraper: PluginScraper,
        tmdbId: String,
        mediaType: String,
        season: Int?,
        episode: Int?,
    ): Result<List<PluginRuntimeResult>> = Result.failure(Exception("Not supported"))
}
