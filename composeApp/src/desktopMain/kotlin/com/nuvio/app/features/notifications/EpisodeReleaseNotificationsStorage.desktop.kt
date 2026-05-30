package com.nuvio.app.features.notifications

import com.nuvio.app.desktop.DesktopPrefs

internal actual object EpisodeReleaseNotificationsStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("episodeNotifications", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("episodeNotifications", "payload", payload)
}
