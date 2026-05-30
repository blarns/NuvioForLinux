package com.nuvio.app.features.trakt

import com.nuvio.app.desktop.DesktopPrefs

internal actual object TraktAuthStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("traktAuth", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("traktAuth", "payload", payload)
}
