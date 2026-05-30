package com.nuvio.app.features.search

import com.nuvio.app.desktop.DesktopPrefs

internal actual object SearchHistoryStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("searchHistory", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("searchHistory", "payload", payload)
}
