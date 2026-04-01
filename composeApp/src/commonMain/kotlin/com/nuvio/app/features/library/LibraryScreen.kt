package com.nuvio.app.features.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioScreenHeader
import com.nuvio.app.core.ui.NuvioStatusModal
import com.nuvio.app.core.ui.NuvioViewAllPillSize
import com.nuvio.app.core.ui.NuvioShelfSection
import com.nuvio.app.features.home.components.HomeEmptyStateCard
import com.nuvio.app.features.home.components.HomePosterCard

@Composable
fun LibraryScreen(
    modifier: Modifier = Modifier,
    onPosterClick: ((LibraryItem) -> Unit)? = null,
) {
    val uiState by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    var pendingRemovalItem by remember { mutableStateOf<LibraryItem?>(null) }
    val isTraktSource = uiState.sourceMode == LibrarySourceMode.TRAKT

    NuvioScreen(
        modifier = modifier,
        horizontalPadding = 0.dp,
    ) {
        stickyHeader {
            NuvioScreenHeader(
                title = if (isTraktSource) "Trakt Library" else "Library",
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        when {
            !uiState.isLoaded -> {
                item {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
            }

            uiState.sections.isEmpty() -> {
                item {
                    HomeEmptyStateCard(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        title = if (isTraktSource) "Your Trakt library is empty" else "Your library is empty",
                        message = if (isTraktSource) {
                            "Connect Trakt and save titles to your watchlist or personal lists."
                        } else {
                            "Saved titles will appear here after you tap Save on a details screen."
                        },
                    )
                }
            }

            else -> {
                librarySections(
                    sections = uiState.sections,
                    onPosterClick = onPosterClick,
                    onPosterLongClick = { item ->
                        if (!isTraktSource) {
                            pendingRemovalItem = item
                        }
                    },
                )
            }
        }
    }

    NuvioStatusModal(
        title = "Remove from Library?",
        message = pendingRemovalItem?.let { "Remove ${it.name} from your library?" }.orEmpty(),
        isVisible = pendingRemovalItem != null,
        confirmText = "Remove",
        dismissText = "Cancel",
        onConfirm = {
            pendingRemovalItem?.id?.let(LibraryRepository::remove)
            pendingRemovalItem = null
        },
        onDismiss = { pendingRemovalItem = null },
    )
}

private fun LazyListScope.librarySections(
    sections: List<LibrarySection>,
    onPosterClick: ((LibraryItem) -> Unit)?,
    onPosterLongClick: (LibraryItem) -> Unit,
) {
    items(
        items = sections,
        key = { section -> section.type },
    ) { section ->
        NuvioShelfSection(
            title = section.displayTitle,
            entries = section.items,
            headerHorizontalPadding = 16.dp,
            rowContentPadding = PaddingValues(horizontal = 16.dp),
            viewAllPillSize = NuvioViewAllPillSize.Compact,
            key = { item -> "${item.type}:${item.id}" },
        ) { item ->
            HomePosterCard(
                item = item.toMetaPreview(),
                onClick = onPosterClick?.let { { it(item) } },
                onLongClick = { onPosterLongClick(item) },
            )
        }
    }
}
