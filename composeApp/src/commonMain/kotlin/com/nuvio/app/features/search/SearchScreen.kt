package com.nuvio.app.features.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioInputField
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.components.HomeCatalogRowSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomeSkeletonRow
import com.nuvio.app.features.watched.WatchedRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@Composable
fun SearchScreen(
    modifier: Modifier = Modifier,
    onPosterClick: ((MetaPreview) -> Unit)? = null,
    onPosterLongClick: ((MetaPreview) -> Unit)? = null,
) {
    LaunchedEffect(Unit) {
        AddonRepository.initialize()
        WatchedRepository.ensureLoaded()
    }

    val addonsUiState by AddonRepository.uiState.collectAsStateWithLifecycle()
    val uiState by SearchRepository.uiState.collectAsStateWithLifecycle()
    val discoverUiState by SearchRepository.discoverUiState.collectAsStateWithLifecycle()
    val watchedUiState by WatchedRepository.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val headerTitle by remember(query, listState) {
        derivedStateOf {
            if (query.isNotBlank()) {
                "Search"
            } else {
                val discoverInFocus = listState.firstVisibleItemIndex > 0
                if (discoverInFocus) "Discover" else "Search"
            }
        }
    }

    val addonRefreshKey = remember(addonsUiState.addons) {
        addonsUiState.addons.mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            buildString {
                append(manifest.transportUrl)
                append(':')
                append(manifest.catalogs.joinToString(separator = ",") { catalog ->
                    val extra = catalog.extra.joinToString(separator = "&") { property ->
                        buildString {
                            append(property.name)
                            append(':')
                            append(property.isRequired)
                            append(':')
                            append(property.options.joinToString(separator = "|"))
                        }
                    }
                    "${catalog.type}:${catalog.id}:$extra"
                })
            }
        }
    }

    LaunchedEffect(addonRefreshKey) {
        SearchRepository.refreshDiscover(addonsUiState.addons)
    }

    LaunchedEffect(query, addonRefreshKey) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) {
            SearchRepository.clear()
        } else {
            delay(350)
            SearchRepository.search(
                query = normalizedQuery,
                addons = addonsUiState.addons,
            )
        }
    }

    LaunchedEffect(listState, query, discoverUiState.canLoadMore, discoverUiState.isLoading) {
        if (query.isNotBlank()) return@LaunchedEffect

        snapshotFlow { listState.layoutInfo }
            .map { layoutInfo ->
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                lastVisible >= layoutInfo.totalItemsCount - 4
            }
            .distinctUntilChanged()
            .filter { it && discoverUiState.canLoadMore && !discoverUiState.isLoading }
            .collect {
                SearchRepository.loadMoreDiscover()
            }
    }

    NuvioScreen(
        horizontalPadding = 0.dp,
        listState = listState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        stickyHeader {
            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                NuvioScreenHeader(
                    title = headerTitle,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(6.dp))
                androidx.compose.foundation.layout.Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    NuvioInputField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search movies, shows...",
                    )
                }
            }
        }

        if (query.isBlank()) {
            discoverContent(
                state = discoverUiState,
                onTypeSelected = SearchRepository::selectDiscoverType,
                onCatalogSelected = SearchRepository::selectDiscoverCatalog,
                onGenreSelected = SearchRepository::selectDiscoverGenre,
                watchedKeys = watchedUiState.watchedKeys,
                onPosterClick = onPosterClick,
                onPosterLongClick = onPosterLongClick,
            )
        } else {
            when {
                uiState.isLoading && uiState.sections.isEmpty() -> {
                    items(2) {
                        HomeSkeletonRow(modifier = Modifier.padding(horizontal = 0.dp))
                    }
                }

                uiState.sections.isEmpty() -> {
                    item {
                        SearchEmptyStateCard(
                            reason = uiState.emptyStateReason,
                            errorMessage = uiState.errorMessage,
                        )
                    }
                }

                else -> {
                    items(
                        items = uiState.sections,
                        key = { section -> section.key },
                    ) { section ->
                        HomeCatalogRowSection(
                            section = section,
                            modifier = Modifier.padding(bottom = 12.dp),
                            watchedKeys = watchedUiState.watchedKeys,
                            onPosterClick = onPosterClick,
                            onPosterLongClick = onPosterLongClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyStateCard(
    reason: SearchEmptyStateReason?,
    errorMessage: String?,
    modifier: Modifier = Modifier,
) {
    val title: String
    val message: String

    when (reason) {
        SearchEmptyStateReason.NoActiveAddons -> {
            title = "No active addons"
            message = "Install and validate at least one addon before searching."
        }

        SearchEmptyStateReason.NoSearchCatalogs -> {
            title = "No searchable catalogs"
            message = "Your installed addons do not expose catalog search."
        }

        SearchEmptyStateReason.RequestFailed -> {
            title = "Search failed"
            message = errorMessage ?: "Installed addons failed to return valid search results."
        }

        SearchEmptyStateReason.NoResults, null -> {
            title = "No results found"
            message = "Installed searchable catalogs did not return any matches for this query."
        }
    }

    HomeEmptyStateCard(
        modifier = modifier,
        title = title,
        message = message,
    )
}
