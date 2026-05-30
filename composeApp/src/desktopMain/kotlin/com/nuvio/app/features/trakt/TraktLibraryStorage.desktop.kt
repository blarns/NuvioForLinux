package com.nuvio.app.features.trakt

import com.nuvio.app.desktop.DesktopPrefs

internal actual object TraktLibraryStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("traktLibrary", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("traktLibrary", "payload", payload)
}
