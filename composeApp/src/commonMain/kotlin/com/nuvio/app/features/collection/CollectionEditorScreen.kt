package com.nuvio.app.features.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.home.PosterShape

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CollectionEditorScreen(
    collectionId: String?,
    onBack: () -> Unit,
) {
    val state by CollectionEditorRepository.uiState.collectAsState()

    LaunchedEffect(collectionId) {
        CollectionEditorRepository.initialize(collectionId)
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    if (state.showFolderEditor && state.editingFolder != null) {
        FolderEditorSheet(
            state = state,
            onDismiss = { CollectionEditorRepository.cancelFolderEdit() },
        )
    }

    if (state.showCatalogPicker) {
        CatalogPickerSheet(
            availableCatalogs = state.availableCatalogs,
            selectedSources = state.editingFolder?.catalogSources.orEmpty(),
            onToggle = { CollectionEditorRepository.toggleCatalogSource(it) },
            onDismiss = { CollectionEditorRepository.hideCatalogPicker() },
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            top = 10.dp + statusBarTop,
            end = 16.dp,
            bottom = 18.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                    Text(
                        text = if (state.isNew) "New Collection" else "Edit Collection",
                        style = MaterialTheme.typography.displayLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
            }
        }

        // Title
        item {
            OutlinedTextField(
                value = state.title,
                onValueChange = { CollectionEditorRepository.setTitle(it) },
                label = { Text("Collection Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        // Backdrop URL
        item {
            OutlinedTextField(
                value = state.backdropImageUrl,
                onValueChange = { CollectionEditorRepository.setBackdropImageUrl(it) },
                label = { Text("Backdrop Image URL (optional)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        // Pin to Top
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { CollectionEditorRepository.setPinToTop(!state.pinToTop) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Pin to Top",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Display this collection above regular catalog rows.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.pinToTop,
                    onCheckedChange = { CollectionEditorRepository.setPinToTop(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        // View Mode
        item {
            Column {
                Text(
                    text = "View Mode",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FolderViewMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.viewMode == mode,
                            onClick = { CollectionEditorRepository.setViewMode(mode) },
                            label = {
                                Text(
                                    when (mode) {
                                        FolderViewMode.TABBED_GRID -> "Tabbed Grid"
                                        FolderViewMode.ROWS -> "Rows"
                                        FolderViewMode.FOLLOW_LAYOUT -> "Follow Layout"
                                    }
                                )
                            },
                            leadingIcon = if (state.viewMode == mode) {
                                {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }

        // Show All Tab
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { CollectionEditorRepository.setShowAllTab(!state.showAllTab) }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Show \"All\" Tab",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Combine all folder catalogs into a single tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.showAllTab,
                    onCheckedChange = { CollectionEditorRepository.setShowAllTab(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        // Folders Section Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "FOLDERS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = { CollectionEditorRepository.addFolder() }) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Folder")
                }
            }
        }

        // Folder Items
        itemsIndexed(
            items = state.folders,
            key = { _, folder -> folder.id },
        ) { index, folder ->
            FolderListItem(
                folder = folder,
                index = index,
                totalCount = state.folders.size,
                onEdit = { CollectionEditorRepository.editFolder(folder.id) },
                onDelete = { CollectionEditorRepository.removeFolder(folder.id) },
                onMoveUp = { CollectionEditorRepository.moveFolderUp(index) },
                onMoveDown = { CollectionEditorRepository.moveFolderDown(index) },
            )
        }

        if (state.folders.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No folders yet. Add one to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Save button
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (CollectionEditorRepository.save()) {
                        onBack()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                enabled = state.title.isNotBlank(),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (state.isNew) "Create Collection" else "Save Changes")
            }
        }
    }
}

@Composable
private fun FolderListItem(
    folder: CollectionFolder,
    index: Int,
    totalCount: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Folder cover preview
                if (folder.coverEmoji != null) {
                    Surface(
                        modifier = Modifier.size(40.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = folder.coverEmoji, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = folder.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${folder.catalogSources.size} source${if (folder.catalogSources.size != 1) "s" else ""} · ${folder.posterShape.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(
                    onClick = onMoveUp,
                    enabled = index > 0,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowUpward,
                        contentDescription = "Move up",
                        modifier = Modifier.size(16.dp).alpha(if (index > 0) 1f else 0.3f),
                    )
                }
                IconButton(
                    onClick = onMoveDown,
                    enabled = index < totalCount - 1,
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = "Move down",
                        modifier = Modifier.size(16.dp).alpha(if (index < totalCount - 1) 1f else 0.3f),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FolderEditorSheet(
    state: CollectionEditorUiState,
    onDismiss: () -> Unit,
) {
    val folder = state.editingFolder ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    text = "Edit Folder",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            item {
                OutlinedTextField(
                    value = folder.title,
                    onValueChange = { CollectionEditorRepository.updateFolderTitle(it) },
                    label = { Text("Folder Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }

            // Cover (emoji or image url)
            item {
                Column {
                    Text(
                        text = "Cover",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = folder.coverEmoji == null && folder.coverImageUrl == null,
                            onClick = { CollectionEditorRepository.clearFolderCover() },
                            label = { Text("None") },
                        )
                        FilterChip(
                            selected = folder.coverEmoji != null,
                            onClick = {
                                if (folder.coverEmoji == null) {
                                    CollectionEditorRepository.updateFolderCoverEmoji("📁")
                                }
                            },
                            label = { Text("Emoji") },
                        )
                        FilterChip(
                            selected = folder.coverImageUrl != null,
                            onClick = {
                                if (folder.coverImageUrl == null) {
                                    CollectionEditorRepository.updateFolderCoverImage("")
                                }
                            },
                            label = { Text("Image") },
                        )
                    }
                    if (folder.coverEmoji != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = folder.coverEmoji,
                            onValueChange = { CollectionEditorRepository.updateFolderCoverEmoji(it) },
                            label = { Text("Emoji") },
                            modifier = Modifier.width(100.dp),
                            singleLine = true,
                        )
                    }
                    if (folder.coverImageUrl != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = folder.coverImageUrl,
                            onValueChange = { CollectionEditorRepository.updateFolderCoverImage(it) },
                            label = { Text("Image URL") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                }
            }

            // Tile Shape
            item {
                Column {
                    Text(
                        text = "Tile Shape",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PosterShape.entries.forEach { shape ->
                            FilterChip(
                                selected = folder.posterShape == shape,
                                onClick = { CollectionEditorRepository.updateFolderTileShape(shape) },
                                label = { Text(shape.name) },
                                leadingIcon = if (folder.posterShape == shape) {
                                    {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                } else null,
                            )
                        }
                    }
                }
            }

            // Hide Title
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { CollectionEditorRepository.updateFolderHideTitle(!folder.hideTitle) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Hide Title",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Switch(
                        checked = folder.hideTitle,
                        onCheckedChange = { CollectionEditorRepository.updateFolderHideTitle(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }

            // Catalog Sources
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "CATALOG SOURCES",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = { CollectionEditorRepository.showCatalogPicker() }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }

            itemsIndexed(folder.catalogSources) { index, source ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${source.catalogId} (${source.type})",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = source.addonId,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconButton(
                            onClick = { CollectionEditorRepository.removeCatalogSource(index) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Remove",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (folder.catalogSources.isEmpty()) {
                item {
                    Text(
                        text = "No catalog sources. Tap \"Add\" to select from installed addons.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            // Save / Cancel
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = { CollectionEditorRepository.cancelFolderEdit() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = { CollectionEditorRepository.saveFolderEdit() },
                        modifier = Modifier.weight(1f),
                        enabled = folder.title.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Save Folder")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogPickerSheet(
    availableCatalogs: List<AvailableCatalog>,
    selectedSources: List<CollectionCatalogSource>,
    onToggle: (AvailableCatalog) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Select Catalog Sources",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = onDismiss) {
                        Text("Done")
                    }
                }
            }

            val grouped = availableCatalogs.groupBy { it.addonName }
            grouped.forEach { (addonName, catalogs) ->
                item {
                    Text(
                        text = addonName,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                catalogs.forEach { catalog ->
                    val isSelected = selectedSources.any {
                        it.addonId == catalog.addonId && it.type == catalog.type && it.catalogId == catalog.catalogId
                    }
                    item(key = "${catalog.addonId}:${catalog.type}:${catalog.catalogId}") {
                        val bgColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        }
                        val borderColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(bgColor)
                                .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                                .clickable { onToggle(catalog) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = catalog.catalogName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = catalog.type.replaceFirstChar {
                                        if (it.isLowerCase()) it.titlecase() else it.toString()
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = "Selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
