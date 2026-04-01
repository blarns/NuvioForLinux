package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.PlayArrow

internal fun LazyListScope.settingsRootContent(
    isTablet: Boolean,
    onPlaybackClick: () -> Unit,
    onAppearanceClick: () -> Unit,
    onContentDiscoveryClick: () -> Unit,
    onIntegrationsClick: () -> Unit,
    onAccountClick: () -> Unit,
    onSwitchProfileClick: (() -> Unit)? = null,
    showAccountSection: Boolean = true,
    showGeneralSection: Boolean = true,
) {
    if (showAccountSection) {
        item {
            SettingsSection(
                title = "ACCOUNT",
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    if (onSwitchProfileClick != null) {
                        SettingsNavigationRow(
                            title = "Switch Profile",
                            description = "Change to a different profile.",
                            icon = Icons.Rounded.People,
                            isTablet = isTablet,
                            onClick = onSwitchProfileClick,
                        )
                        SettingsGroupDivider(isTablet = isTablet)
                    }
                    SettingsNavigationRow(
                        title = "Account",
                        description = "Manage your account, sign out, or delete.",
                        icon = Icons.Rounded.AccountCircle,
                        isTablet = isTablet,
                        onClick = onAccountClick,
                    )
                }
            }
        }
    }
    if (showGeneralSection) {
        item {
            SettingsSection(
                title = "GENERAL",
                isTablet = isTablet,
            ) {
                SettingsGroup(isTablet = isTablet) {
                    SettingsNavigationRow(
                        title = "Playback",
                        description = "Control player behavior and viewing defaults.",
                        icon = Icons.Rounded.PlayArrow,
                        isTablet = isTablet,
                        onClick = onPlaybackClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = "Appearance",
                        description = "Tune home presentation and visual preferences.",
                        icon = Icons.Rounded.Palette,
                        isTablet = isTablet,
                        onClick = onAppearanceClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = "Content & Discovery",
                        description = "Manage addons and discovery sources.",
                        icon = Icons.Rounded.Extension,
                        isTablet = isTablet,
                        onClick = onContentDiscoveryClick,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = "Integrations",
                        description = "Connect TMDB, Trakt, and MDBList services.",
                        icon = Icons.Rounded.Link,
                        isTablet = isTablet,
                        onClick = onIntegrationsClick,
                    )
                }
            }
        }
    }
}
