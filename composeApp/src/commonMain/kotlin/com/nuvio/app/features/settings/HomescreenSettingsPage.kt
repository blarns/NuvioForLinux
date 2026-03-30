package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.home.HomeCatalogSettingsItem
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.components.HomeEmptyStateCard

internal fun LazyListScope.homescreenSettingsContent(
    isTablet: Boolean,
    heroEnabled: Boolean,
    items: List<HomeCatalogSettingsItem>,
) {
    val selectedHeroSourceCount = items.count { it.heroSourceEnabled }
    val enabledCatalogCount = items.count { it.enabled }
    item {
        HomescreenSummaryCard(
            isTablet = isTablet,
            enabledCatalogCount = enabledCatalogCount,
            totalCatalogCount = items.size,
            selectedHeroSourceCount = selectedHeroSourceCount,
        )
    }
    item {
        SettingsSection(
            title = "HERO",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Show Hero",
                    description = "Display a featured hero carousel at the top of Home. Choose up to 2 source catalogs below.",
                    checked = heroEnabled,
                    isTablet = isTablet,
                    onCheckedChange = HomeCatalogSettingsRepository::setHeroEnabled,
                )
            }
        }
    }
    item {
        if (items.isNotEmpty()) {
            SettingsSection(
                title = "HERO SOURCES",
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    Text(
                        text = "$selectedHeroSourceCount of ${HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT} selected",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (items.isNotEmpty()) {
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                    items.forEachIndexed { index, item ->
                        if (index > 0) {
                            SettingsGroupDivider(isTablet = isTablet)
                        }
                        SettingsSwitchRow(
                            title = item.displayTitle,
                            description = if (!item.heroSourceEnabled &&
                                selectedHeroSourceCount >= HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT
                            ) {
                                "${item.addonName} • Limit reached (max 2)"
                            } else {
                                item.addonName
                            },
                            checked = item.heroSourceEnabled,
                            enabled = item.heroSourceEnabled ||
                                selectedHeroSourceCount < HomeCatalogSettingsRepository.HERO_SOURCE_SELECTION_LIMIT,
                            isTablet = isTablet,
                            onCheckedChange = { HomeCatalogSettingsRepository.setHeroSourceEnabled(item.key, it) },
                        )
                    }
                }
            }
        }
    }
    item {
        if (items.isEmpty()) {
            HomeEmptyStateCard(
                modifier = Modifier.fillMaxWidth(),
                title = "No home catalogs",
                message = "Install an addon with board-compatible catalogs to configure Homescreen rows.",
            )
        } else {
            SettingsSection(
                title = "CATALOGS",
                isTablet = isTablet,
            ) {
                HomescreenCatalogList(
                    isTablet = isTablet,
                    items = items,
                )
            }
        }
    }
}

@Composable
private fun HomescreenSummaryCard(
    isTablet: Boolean,
    enabledCatalogCount: Int,
    totalCatalogCount: Int,
    selectedHeroSourceCount: Int,
) {
    SettingsGroup(isTablet = isTablet) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Keep Home focused",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "$enabledCatalogCount of $totalCatalogCount catalogs visible • $selectedHeroSourceCount hero sources selected",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Open a catalog only when you need to rename or reorder it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HomescreenCatalogList(
    isTablet: Boolean,
    items: List<HomeCatalogSettingsItem>,
) {
    var expandedKey by remember { mutableStateOf<String?>(null) }

    SettingsGroup(isTablet = isTablet) {
        items.forEachIndexed { index, item ->
            if (index > 0) {
                SettingsGroupDivider(isTablet = isTablet)
            }
            HomescreenCatalogRow(
                item = item,
                isTablet = isTablet,
                expanded = expandedKey == item.key,
                canMoveUp = index > 0,
                canMoveDown = index < items.lastIndex,
                onExpandedChange = { shouldExpand ->
                    expandedKey = if (shouldExpand) item.key else null
                },
                onTitleChange = { HomeCatalogSettingsRepository.setCustomTitle(item.key, it) },
                onEnabledChange = { HomeCatalogSettingsRepository.setEnabled(item.key, it) },
                onMoveUp = { HomeCatalogSettingsRepository.moveUp(item.key) },
                onMoveDown = { HomeCatalogSettingsRepository.moveDown(item.key) },
            )
        }
    }
}
