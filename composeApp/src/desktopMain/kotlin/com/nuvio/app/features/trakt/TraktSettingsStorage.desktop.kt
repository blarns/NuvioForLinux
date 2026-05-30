package com.nuvio.app.features.trakt

import com.nuvio.app.desktop.DesktopPrefs

internal actual object TraktSettingsStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("traktSettings", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("traktSettings", "payload", payload)
}
