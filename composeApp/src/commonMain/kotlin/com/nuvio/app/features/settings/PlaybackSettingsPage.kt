package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.AudioLanguageOption
import com.nuvio.app.features.player.AvailableLanguageOptions
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.languageLabelForCode
import com.nuvio.app.isIos

internal fun LazyListScope.playbackSettingsContent(
    isTablet: Boolean,
    showLoadingOverlay: Boolean,
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    streamReuseLastLinkEnabled: Boolean,
    streamReuseLastLinkCacheHours: Int,
    decoderPriority: Int,
    mapDV7ToHevc: Boolean,
    tunnelingEnabled: Boolean,
) {
    item {
        PlaybackSettingsSection(
            isTablet = isTablet,
            showLoadingOverlay = showLoadingOverlay,
            preferredAudioLanguage = preferredAudioLanguage,
            secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage,
            preferredSubtitleLanguage = preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage,
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
            streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours,
            decoderPriority = decoderPriority,
            mapDV7ToHevc = mapDV7ToHevc,
            tunnelingEnabled = tunnelingEnabled,
        )
    }
}

@Composable
private fun PlaybackSettingsSection(
    isTablet: Boolean,
    showLoadingOverlay: Boolean,
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    preferredSubtitleLanguage: String,
    secondaryPreferredSubtitleLanguage: String?,
    streamReuseLastLinkEnabled: Boolean,
    streamReuseLastLinkCacheHours: Int,
    decoderPriority: Int,
    mapDV7ToHevc: Boolean,
    tunnelingEnabled: Boolean,
) {
    var showPreferredAudioDialog by remember { mutableStateOf(false) }
    var showSecondaryAudioDialog by remember { mutableStateOf(false) }
    var showPreferredSubtitleDialog by remember { mutableStateOf(false) }
    var showSecondarySubtitleDialog by remember { mutableStateOf(false) }
    var showReuseCacheDurationDialog by remember { mutableStateOf(false) }
    var showDecoderPriorityDialog by remember { mutableStateOf(false) }
    val sectionSpacing = if (isTablet) 18.dp else 12.dp

    Column(
        verticalArrangement = Arrangement.spacedBy(sectionSpacing),
    ) {
        SettingsSection(
            title = "PLAYER",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Show Loading Overlay",
                    description = "Show the opening loading overlay while a stream starts playing.",
                    checked = showLoadingOverlay,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setShowLoadingOverlay,
                )
            }
        }

        SettingsSection(
            title = "SUBTITLE AND AUDIO",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = "Preferred Audio Language",
                    description = when (preferredAudioLanguage) {
                        AudioLanguageOption.DEFAULT -> "Default"
                        AudioLanguageOption.DEVICE -> "Device Language"
                        else -> languageLabelForCode(preferredAudioLanguage)
                    },
                    isTablet = isTablet,
                    onClick = { showPreferredAudioDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Secondary Audio Language",
                    description = languageLabelForCode(secondaryPreferredAudioLanguage),
                    isTablet = isTablet,
                    onClick = { showSecondaryAudioDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Preferred Subtitle Language",
                    description = when (preferredSubtitleLanguage) {
                        SubtitleLanguageOption.NONE -> "None"
                        SubtitleLanguageOption.DEVICE -> "Device Language"
                        SubtitleLanguageOption.FORCED -> "Forced"
                        else -> languageLabelForCode(preferredSubtitleLanguage)
                    },
                    isTablet = isTablet,
                    onClick = { showPreferredSubtitleDialog = true },
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Secondary Subtitle Language",
                    description = languageLabelForCode(secondaryPreferredSubtitleLanguage),
                    isTablet = isTablet,
                    onClick = { showSecondarySubtitleDialog = true },
                )
            }
        }

        SettingsSection(
            title = "STREAM SELECTION",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Reuse Last Link",
                    description = "Auto-play your last working stream for this same movie/episode when cache is still valid.",
                    checked = streamReuseLastLinkEnabled,
                    isTablet = isTablet,
                    onCheckedChange = PlayerSettingsRepository::setStreamReuseLastLinkEnabled,
                )
                if (streamReuseLastLinkEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = "Last Link Cache Duration",
                        description = formatReuseCacheDuration(streamReuseLastLinkCacheHours),
                        isTablet = isTablet,
                        onClick = { showReuseCacheDurationDialog = true },
                    )
                }
            }
        }

        if (!isIos) {
            SettingsSection(
                title = "DECODER",
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = "Decoder Priority",
                        description = when (decoderPriority) {
                            0 -> "Device Only"
                            1 -> "Prefer Device"
                            2 -> "Prefer App (FFmpeg)"
                            else -> "Prefer Device"
                        },
                        isTablet = isTablet,
                        onClick = { showDecoderPriorityDialog = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = "Map DV7 to HEVC",
                        description = "Dolby Vision Profile 7 to HEVC fallback for unsupported devices.",
                        checked = mapDV7ToHevc,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setMapDV7ToHevc,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = "Tunneled Playback",
                        description = "Enable tunneled playback for lower latency audio/video sync.",
                        checked = tunnelingEnabled,
                        isTablet = isTablet,
                        onCheckedChange = PlayerSettingsRepository::setTunnelingEnabled,
                    )
                }
            }
        }
    }

    if (showPreferredAudioDialog) {
        LanguageSelectionDialog(
            title = "Preferred Audio Language",
            options = listOf(
                LanguageSelectionOption(AudioLanguageOption.DEFAULT, "Default"),
                LanguageSelectionOption(AudioLanguageOption.DEVICE, "Device Language"),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, option.label)
            },
            selectedValue = preferredAudioLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setPreferredAudioLanguage(value ?: AudioLanguageOption.DEVICE)
                showPreferredAudioDialog = false
            },
            onDismiss = { showPreferredAudioDialog = false },
        )
    }

    if (showSecondaryAudioDialog) {
        LanguageSelectionDialog(
            title = "Secondary Audio Language",
            options = listOf(
                LanguageSelectionOption(null, "None"),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, option.label)
            },
            selectedValue = secondaryPreferredAudioLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setSecondaryPreferredAudioLanguage(value)
                showSecondaryAudioDialog = false
            },
            onDismiss = { showSecondaryAudioDialog = false },
        )
    }

    if (showPreferredSubtitleDialog) {
        LanguageSelectionDialog(
            title = "Preferred Subtitle Language",
            options = listOf(
                LanguageSelectionOption(SubtitleLanguageOption.NONE, "None"),
                LanguageSelectionOption(SubtitleLanguageOption.DEVICE, "Device Language"),
                LanguageSelectionOption(SubtitleLanguageOption.FORCED, "Forced"),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, option.label)
            },
            selectedValue = preferredSubtitleLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setPreferredSubtitleLanguage(value ?: SubtitleLanguageOption.NONE)
                showPreferredSubtitleDialog = false
            },
            onDismiss = { showPreferredSubtitleDialog = false },
        )
    }

    if (showSecondarySubtitleDialog) {
        LanguageSelectionDialog(
            title = "Secondary Subtitle Language",
            options = listOf(
                LanguageSelectionOption(null, "None"),
                LanguageSelectionOption(SubtitleLanguageOption.FORCED, "Forced"),
            ) + AvailableLanguageOptions.map { option ->
                LanguageSelectionOption(option.code, option.label)
            },
            selectedValue = secondaryPreferredSubtitleLanguage,
            onSelect = { value ->
                PlayerSettingsRepository.setSecondaryPreferredSubtitleLanguage(value)
                showSecondarySubtitleDialog = false
            },
            onDismiss = { showSecondarySubtitleDialog = false },
        )
    }

    if (showReuseCacheDurationDialog) {
        ReuseCacheDurationDialog(
            selectedHours = streamReuseLastLinkCacheHours,
            onDurationSelected = { hours ->
                PlayerSettingsRepository.setStreamReuseLastLinkCacheHours(hours)
                showReuseCacheDurationDialog = false
            },
            onDismiss = { showReuseCacheDurationDialog = false },
        )
    }

    if (showDecoderPriorityDialog) {
        DecoderPriorityDialog(
            selectedPriority = decoderPriority,
            onPrioritySelected = { priority ->
                PlayerSettingsRepository.setDecoderPriority(priority)
                showDecoderPriorityDialog = false
            },
            onDismiss = { showDecoderPriorityDialog = false },
        )
    }
}

private fun formatReuseCacheDuration(hours: Int): String = when {
    hours < 24 -> "$hours hour${if (hours != 1) "s" else ""}"
    hours % 24 == 0 -> {
        val days = hours / 24
        "$days day${if (days != 1) "s" else ""}"
    }
    else -> "$hours hours"
}

private data class LanguageSelectionOption(
    val value: String?,
    val label: String,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun LanguageSelectionDialog(
    title: String,
    options: List<LanguageSelectionOption>,
    selectedValue: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(options) { option ->
                        val isSelected = option.value == selectedValue
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option.value) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Tap outside to close",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReuseCacheDurationDialog(
    selectedHours: Int,
    onDurationSelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(1, 6, 12, 24, 48, 72, 168)

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Last Link Cache Duration",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { hours ->
                        val isSelected = hours == selectedHours
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDurationSelected(hours) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = formatReuseCacheDuration(hours),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Tap outside to close",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DecoderPriorityDialog(
    selectedPriority: Int,
    onPrioritySelected: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = listOf(
        0 to "Device Only",
        1 to "Prefer Device",
        2 to "Prefer App (FFmpeg)",
    )

    BasicAlertDialog(
        onDismissRequest = onDismiss,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Decoder Priority",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    options.forEach { (priority, label) ->
                        val isSelected = priority == selectedPriority
                        val containerColor = if (isSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPrioritySelected(priority) },
                            shape = RoundedCornerShape(12.dp),
                            color = containerColor,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                )
                                Box(
                                    modifier = Modifier.size(24.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Tap outside to close",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
