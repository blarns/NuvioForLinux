package com.nuvio.app.features.watchprogress

import com.nuvio.app.desktop.DesktopPrefs

internal actual object ContinueWatchingPreferencesStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("cwPrefs", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("cwPrefs", "payload", payload)
}
