package com.nuvio.app.features.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.nuvioPlatformExtraBottomPadding
import com.nuvio.app.core.ui.nuvioPlatformExtraTopPadding
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.home.components.HomeCatalogRowSection

@Composable
fun FolderDetailScreen(
    onBack: () -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val uiState by FolderDetailRepository.uiState.collectAsState()
    val folder = uiState.folder

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NuvioScreenHeader(
            title = folder?.title ?: uiState.collectionTitle,
            onBack = onBack,
        )

        if (folder == null && !uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Folder not found",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        when (uiState.viewMode) {
            FolderViewMode.TABBED_GRID -> TabbedGridContent(
                uiState = uiState,
                onTabSelected = { FolderDetailRepository.selectTab(it) },
                onPosterClick = onPosterClick,
            )
            FolderViewMode.ROWS -> RowsContent(
                uiState = uiState,
                onPosterClick = onPosterClick,
            )
            FolderViewMode.FOLLOW_LAYOUT -> RowsContent(
                uiState = uiState,
                onPosterClick = onPosterClick,
            )
        }
    }
}

@Composable
private fun TabbedGridContent(
    uiState: FolderDetailUiState,
    onTabSelected: (Int) -> Unit,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val folder = uiState.folder ?: return

    Column(modifier = Modifier.fillMaxSize()) {
        // Folder header with cover + tabs
        FolderHeader(folder = folder)

        if (uiState.tabs.size > 1) {
            ScrollableTabRow(
                selectedTabIndex = uiState.selectedTabIndex,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                divider = {},
            ) {
                uiState.tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == uiState.selectedTabIndex,
                        onClick = { onTabSelected(index) },
                        text = {
                            Text(
                                text = tab.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Grid content for selected tab
        val selectedTab = uiState.tabs.getOrNull(uiState.selectedTabIndex)
        if (selectedTab == null) return

        when {
            selectedTab.isLoading -> LoadingIndicator()
            selectedTab.error != null -> ErrorMessage(selectedTab.error)
            selectedTab.items.isEmpty() -> EmptyMessage()
            else -> {
                val posterShape = folder.posterShape
                val nuvioShape = posterShape.toNuvioPosterShape()
                val columns = when (nuvioShape) {
                    NuvioPosterShape.Poster -> 3
                    NuvioPosterShape.Square -> 3
                    NuvioPosterShape.Landscape -> 2
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        bottom = 18.dp + nuvioPlatformExtraBottomPadding,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    items(
                        items = selectedTab.items,
                        key = { it.id },
                    ) { item ->
                        NuvioPosterCard(
                            title = item.name,
                            imageUrl = item.poster,
                            shape = nuvioShape,
                            detailLine = item.releaseInfo,
                            onClick = { onPosterClick(item) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RowsContent(
    uiState: FolderDetailUiState,
    onPosterClick: (MetaPreview) -> Unit,
) {
    val sections = FolderDetailRepository.getCatalogSectionsForRows()

    if (uiState.isLoading && sections.isEmpty()) {
        LoadingIndicator()
        return
    }

    if (sections.isEmpty() && !uiState.isLoading) {
        EmptyMessage()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            bottom = 18.dp + nuvioPlatformExtraBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(
            items = sections,
            key = { it.key },
        ) { section ->
            HomeCatalogRowSection(
                section = section,
                entries = section.items.take(18),
                onPosterClick = { onPosterClick(it) },
            )
        }
    }
}

@Composable
private fun FolderHeader(folder: CollectionFolder) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Cover image or emoji
        when {
            !folder.coverImageUrl.isNullOrBlank() -> {
                AsyncImage(
                    model = folder.coverImageUrl,
                    contentDescription = folder.title,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentScale = ContentScale.Crop,
                )
            }
            !folder.coverEmoji.isNullOrBlank() -> {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = folder.coverEmoji,
                        style = MaterialTheme.typography.headlineMedium,
                    )
                }
            }
        }

        Column {
            Text(
                text = folder.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${folder.catalogSources.size} source${if (folder.catalogSources.size != 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp,
        )
    }
}

@Composable
private fun ErrorMessage(error: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyMessage() {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No items found",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun PosterShape.toNuvioPosterShape(): NuvioPosterShape =
    when (this) {
        PosterShape.Poster -> NuvioPosterShape.Poster
        PosterShape.Square -> NuvioPosterShape.Square
        PosterShape.Landscape -> NuvioPosterShape.Landscape
    }
