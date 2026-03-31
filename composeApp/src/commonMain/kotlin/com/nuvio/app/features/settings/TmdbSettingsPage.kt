package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.tmdb.TmdbSettings
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.tmdb.normalizeLanguage

internal fun LazyListScope.tmdbSettingsContent(
    isTablet: Boolean,
    settings: TmdbSettings,
) {
    item {
        SettingsSection(
            title = "TMDB",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Enable TMDB enrichment",
                    description = "Use TMDB to enrich addon metadata on the details screen when a TMDB or IMDb ID is available.",
                    checked = settings.enabled,
                    isTablet = isTablet,
                    onCheckedChange = TmdbSettingsRepository::setEnabled,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = "LOCALIZATION",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                TmdbLanguageRow(
                    isTablet = isTablet,
                    value = settings.language,
                    enabled = settings.enabled,
                    onLanguageCommitted = TmdbSettingsRepository::setLanguage,
                )
            }
        }
    }

    item {
        SettingsSection(
            title = "MODULES",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "Trailers",
                    description = "Fetch and show TMDB trailer videos section on detail pages.",
                    checked = settings.useTrailers,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseTrailers,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "Artwork",
                    description = "Replace backdrop, poster, and logo with TMDB artwork.",
                    checked = settings.useArtwork,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseArtwork,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "Basic info",
                    description = "Use TMDB title, synopsis, genres, and rating.",
                    checked = settings.useBasicInfo,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseBasicInfo,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "Details",
                    description = "Use TMDB release info, runtime, age rating, status, country, and language.",
                    checked = settings.useDetails,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseDetails,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "Credits",
                    description = "Use TMDB creators, directors, writers, and cast photos.",
                    checked = settings.useCredits,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseCredits,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "Production companies",
                    description = "Use TMDB production company metadata on the details screen.",
                    checked = settings.useProductions,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseProductions,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "Networks",
                    description = "Use TMDB network metadata for TV titles.",
                    checked = settings.useNetworks,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseNetworks,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "Episodes",
                    description = "Use TMDB episode titles, thumbnails, descriptions, and runtimes for series.",
                    checked = settings.useEpisodes,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseEpisodes,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "Season posters",
                    description = "Use TMDB season posters in the metadata screen season selector for series.",
                    checked = settings.useSeasonPosters,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseSeasonPosters,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "More like this",
                    description = "Show TMDB recommendations at the bottom of detail pages.",
                    checked = settings.useMoreLikeThis,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseMoreLikeThis,
                )
                SettingsGroupDivider(isTablet = isTablet)
                TmdbToggleRow(
                    isTablet = isTablet,
                    title = "Collections",
                    description = "Show franchise and collection rails for movies when TMDB provides them.",
                    checked = settings.useCollections,
                    enabled = settings.enabled,
                    onCheckedChange = TmdbSettingsRepository::setUseCollections,
                )
            }
        }
    }
}

@Composable
private fun TmdbLanguageRow(
    isTablet: Boolean,
    value: String,
    enabled: Boolean,
    onLanguageCommitted: (String) -> Unit,
) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    val verticalPadding = if (isTablet) 16.dp else 14.dp
    var draft by rememberSaveable(value) { mutableStateOf(value) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Preferred language",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Set the TMDB language code used for localized metadata, for example `en`, `en-US`, or `pt-BR`.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedTextField(
            value = draft,
            onValueChange = {
                draft = it
                if (enabled) {
                    onLanguageCommitted(normalizeLanguage(it))
                }
            },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Language code") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                disabledContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
    }
}

@Composable
private fun TmdbToggleRow(
    isTablet: Boolean,
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingsSwitchRow(
        title = title,
        description = description,
        checked = checked,
        enabled = enabled,
        isTablet = isTablet,
        onCheckedChange = onCheckedChange,
    )
}
