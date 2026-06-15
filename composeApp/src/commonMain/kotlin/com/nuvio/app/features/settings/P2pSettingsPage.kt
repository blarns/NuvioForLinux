package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.p2p.P2pSettingsRepository

internal fun LazyListScope.p2pSettingsContent(
    isTablet: Boolean,
    p2pEnabled: Boolean,
    enableUpload: Boolean,
    hideTorrentStats: Boolean,
) {
    item {
        P2pWarningCard(isTablet = isTablet)
    }
    item {
        SettingsSection(
            title = "Peer-to-peer streaming",
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = "Enable P2P streaming",
                    description = "Stream torrent/magnet results directly over BitTorrent using the bundled " +
                        "TorrServer engine. Off by default; a debrid service is the recommended alternative.",
                    checked = p2pEnabled,
                    isTablet = isTablet,
                    onCheckedChange = P2pSettingsRepository::setP2pEnabled,
                )
                if (p2pEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = "Upload while streaming",
                        description = "Seed pieces back to the swarm while watching. Turning this off reduces " +
                            "(but does not eliminate) your visibility to other peers.",
                        checked = enableUpload,
                        isTablet = isTablet,
                        onCheckedChange = P2pSettingsRepository::setEnableUpload,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSwitchRow(
                        title = "Hide torrent stats overlay",
                        description = "Hide the peer / seed / speed counters during P2P playback.",
                        checked = hideTorrentStats,
                        isTablet = isTablet,
                        onCheckedChange = P2pSettingsRepository::setHideTorrentStats,
                    )
                }
            }
        }
    }
}

@Composable
private fun P2pWarningCard(isTablet: Boolean) {
    val horizontalPadding = if (isTablet) 20.dp else 16.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = horizontalPadding),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
    ) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(
                imageVector = Icons.Rounded.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Experimental — exposes your IP address",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "P2P connects you directly to other peers, so your real IP address is visible to " +
                        "everyone in the swarm. Use a VPN if you enable this. A debrid service avoids P2P " +
                        "entirely and is the recommended way to stream.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.9f),
                )
            }
        }
    }
}
