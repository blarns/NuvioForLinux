package com.nuvio.app.features.collection

import com.nuvio.app.desktop.DesktopPrefs

internal actual object CollectionMobileSettingsStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("collectionMobileSettings", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("collectionMobileSettings", "payload", payload)
}
