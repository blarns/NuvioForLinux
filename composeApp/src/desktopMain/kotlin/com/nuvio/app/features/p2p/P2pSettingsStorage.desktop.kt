package com.nuvio.app.features.p2p

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.desktop.DesktopPrefs

// Mirrors the Android actual: profile-scoped booleans. P2P playback itself is gated off on
// desktop (AppFeaturePolicy.p2pEnabled = false); the storage exists so shared settings code
// compiles and any synced values round-trip.
internal actual object P2pSettingsStorage {
    private const val NODE = "p2p"

    actual fun loadP2pEnabled(): Boolean? =
        DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of("p2p_enabled"))

    actual fun saveP2pEnabled(enabled: Boolean) {
        DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of("p2p_enabled"), enabled)
    }

    actual fun loadEnableUpload(): Boolean? =
        DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of("enable_upload"))

    actual fun saveEnableUpload(enabled: Boolean) {
        DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of("enable_upload"), enabled)
    }

    actual fun loadHideTorrentStats(): Boolean? =
        DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of("hide_torrent_stats"))

    actual fun saveHideTorrentStats(enabled: Boolean) {
        DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of("hide_torrent_stats"), enabled)
    }
}
