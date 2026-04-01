package com.nuvio.app.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class SettingsCategory(
    val label: String,
    val icon: ImageVector,
) {
    General("General", Icons.Rounded.Settings),
}

internal enum class SettingsPage(
    val title: String,
) {
    Root("Settings"),
    Playback("Playback"),
    Appearance("Appearance"),
    ContentDiscovery("Content & Discovery"),
    TmdbEnrichment("TMDB Enrichment"),
    MdbListRatings("MDBList Ratings"),
}

internal fun SettingsPage.previousPage(): SettingsPage? =
    when (this) {
        SettingsPage.Root -> null
        SettingsPage.Playback -> SettingsPage.Root
        SettingsPage.Appearance -> SettingsPage.Root
        SettingsPage.ContentDiscovery -> SettingsPage.Root
        SettingsPage.TmdbEnrichment -> SettingsPage.ContentDiscovery
        SettingsPage.MdbListRatings -> SettingsPage.ContentDiscovery
    }
