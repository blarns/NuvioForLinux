package com.nuvio.app.features.watchprogress

import com.nuvio.app.desktop.DesktopPrefs

internal actual object ContinueWatchingEnrichmentStorage {
    actual fun loadPayload(key: String): String? =
        DesktopPrefs.getString("cwEnrichment", key)
    actual fun savePayload(key: String, payload: String) =
        DesktopPrefs.putString("cwEnrichment", key, payload)
    actual fun removePayload(key: String) =
        DesktopPrefs.remove("cwEnrichment", key)
}
