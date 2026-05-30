package com.nuvio.app.features.home

import com.nuvio.app.desktop.DesktopPrefs

internal actual object HomeCatalogSettingsStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("homeCatalogSettings", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("homeCatalogSettings", "payload", payload)
}
