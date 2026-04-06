package com.nuvio.app.features.collection

import co.touchlab.kermit.Logger
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.home.HomeCatalogSection
import com.nuvio.app.features.home.MetaPreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class FolderTab(
    val label: String,
    val typeLabel: String = "",
    val items: List<MetaPreview> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isAllTab: Boolean = false,
)

data class FolderDetailUiState(
    val folder: CollectionFolder? = null,
    val collectionTitle: String = "",
    val viewMode: FolderViewMode = FolderViewMode.TABBED_GRID,
    val tabs: List<FolderTab> = emptyList(),
    val selectedTabIndex: Int = 0,
    val isLoading: Boolean = true,
    val showAllTab: Boolean = true,
)

object FolderDetailRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val log = Logger.withTag("FolderDetailRepository")

    private val _uiState = MutableStateFlow(FolderDetailUiState())
    val uiState: StateFlow<FolderDetailUiState> = _uiState.asStateFlow()

    private var loadJobs = mutableListOf<Job>()

    fun initialize(collectionId: String, folderId: String) {
        clear()

        val collection = CollectionRepository.getCollection(collectionId)
        if (collection == null) {
            _uiState.value = FolderDetailUiState(isLoading = false)
            return
        }

        val folder = collection.folders.find { it.id == folderId }
        if (folder == null) {
            _uiState.value = FolderDetailUiState(isLoading = false)
            return
        }

        val showAll = collection.showAllTab && folder.catalogSources.size > 1
        val addons = AddonRepository.uiState.value.addons

        val tabs = buildList {
            if (showAll) {
                add(FolderTab(label = "All", isAllTab = true, isLoading = true))
            }
            folder.catalogSources.forEach { source ->
                val addon = addons.find { it.manifest?.id == source.addonId }
                val catalog = addon?.manifest?.catalogs?.find {
                    it.id == source.catalogId && it.type == source.type
                }
                val label = catalog?.name ?: source.catalogId
                val typeLabel = source.type.replaceFirstChar {
                    if (it.isLowerCase()) it.titlecase() else it.toString()
                }
                add(
                    FolderTab(
                        label = "$label ($typeLabel)",
                        typeLabel = typeLabel,
                        isLoading = true,
                    ),
                )
            }
        }

        _uiState.value = FolderDetailUiState(
            folder = folder,
            collectionTitle = collection.title,
            viewMode = collection.folderViewMode,
            tabs = tabs,
            selectedTabIndex = 0,
            isLoading = true,
            showAllTab = showAll,
        )

        // Load catalog data for each source
        folder.catalogSources.forEachIndexed { sourceIndex, source ->
            val tabIndex = if (showAll) sourceIndex + 1 else sourceIndex
            val addon = addons.find { it.manifest?.id == source.addonId }
            if (addon == null) {
                updateTab(tabIndex) { it.copy(isLoading = false, error = "Addon not found: ${source.addonId}") }
                return@forEachIndexed
            }

            val job = scope.launch {
                runCatching {
                    val page = fetchCatalogPage(
                        manifestUrl = addon.manifestUrl,
                        type = source.type,
                        catalogId = source.catalogId,
                    )
                    updateTab(tabIndex) { it.copy(items = page.items, isLoading = false) }
                    rebuildAllTab()
                }.onFailure { e ->
                    log.e(e) { "Failed to load catalog ${source.catalogId} from ${source.addonId}" }
                    updateTab(tabIndex) { it.copy(isLoading = false, error = e.message) }
                    rebuildAllTab()
                }
            }
            loadJobs.add(job)
        }

        // If no sources, mark as done
        if (folder.catalogSources.isEmpty()) {
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTabIndex = index)
    }

    fun clear() {
        loadJobs.forEach { it.cancel() }
        loadJobs.clear()
        _uiState.value = FolderDetailUiState()
    }

    private fun updateTab(index: Int, transform: (FolderTab) -> FolderTab) {
        val current = _uiState.value
        val updatedTabs = current.tabs.toMutableList()
        if (index !in updatedTabs.indices) return
        updatedTabs[index] = transform(updatedTabs[index])

        val allDone = updatedTabs.none { !it.isAllTab && it.isLoading }
        _uiState.value = current.copy(
            tabs = updatedTabs,
            isLoading = !allDone,
        )
    }

    private fun rebuildAllTab() {
        val current = _uiState.value
        if (!current.showAllTab) return
        val sourceTabs = current.tabs.filter { !it.isAllTab }
        if (sourceTabs.any { it.isLoading }) return

        // Round-robin merge
        val merged = mutableListOf<MetaPreview>()
        val iterators = sourceTabs.map { it.items.iterator() }
        var hasMore = true
        while (hasMore) {
            hasMore = false
            for (iterator in iterators) {
                if (iterator.hasNext()) {
                    merged.add(iterator.next())
                    hasMore = true
                }
            }
        }

        val updatedTabs = current.tabs.toMutableList()
        val allTabIndex = updatedTabs.indexOfFirst { it.isAllTab }
        if (allTabIndex >= 0) {
            updatedTabs[allTabIndex] = updatedTabs[allTabIndex].copy(
                items = merged,
                isLoading = false,
            )
        }
        _uiState.value = current.copy(tabs = updatedTabs)
    }

    fun getCatalogSectionsForRows(): List<HomeCatalogSection> {
        val current = _uiState.value
        val folder = current.folder ?: return emptyList()

        return current.tabs.filter { !it.isAllTab && it.items.isNotEmpty() }.map { tab ->
            HomeCatalogSection(
                key = "folder_${folder.id}_${tab.label}",
                title = tab.label,
                subtitle = tab.typeLabel,
                addonName = "",
                type = "",
                manifestUrl = "",
                catalogId = "",
                items = tab.items,
            )
        }
    }
}
