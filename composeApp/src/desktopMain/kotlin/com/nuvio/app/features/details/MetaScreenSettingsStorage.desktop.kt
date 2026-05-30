package com.nuvio.app.features.details

import com.nuvio.app.desktop.DesktopPrefs

internal actual object MetaScreenSettingsStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("metaScreenSettings", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("metaScreenSettings", "payload", payload)
}
