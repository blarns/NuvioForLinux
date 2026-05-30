package com.nuvio.app.core.ui

import com.nuvio.app.desktop.DesktopPrefs

internal actual object PosterCardStyleStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("posterCardStyle", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("posterCardStyle", "payload", payload)
}
