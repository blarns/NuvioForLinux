package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.MovieFilter
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune

internal fun LazyListScope.contentDiscoveryContent(
    isTablet: Boolean,
    onAddonsClick: () -> Unit,
    onHomescreenClick: () -> Unit,
    onTmdbClick: () -> Unit,
    onMdbListClick: () -> Unit,
) {
    item {
        SettingsSection(
            title = "SOURCES",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Addons",
                    description = "Install, remove, refresh, and sort your content sources.",
                    icon = Icons.Rounded.Extension,
                    isTablet = isTablet,
                    onClick = onAddonsClick,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = "ENRICHMENT",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "TMDB Enrichment",
                    description = "Enhance detail pages with TMDB artwork, credits, episode metadata, and more.",
                    icon = Icons.Rounded.MovieFilter,
                    isTablet = isTablet,
                    onClick = onTmdbClick,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "MDBList Ratings",
                    description = "Add IMDb, Rotten Tomatoes, Metacritic, and other external ratings to details pages.",
                    icon = Icons.Rounded.Star,
                    isTablet = isTablet,
                    onClick = onMdbListClick,
                )
            }
        }
    }
    item {
        SettingsSection(
            title = "HOME",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Homescreen",
                    description = "Control which catalogs appear on Home and in what order.",
                    icon = Icons.Rounded.Tune,
                    isTablet = isTablet,
                    onClick = onHomescreenClick,
                )
            }
        }
    }
}
