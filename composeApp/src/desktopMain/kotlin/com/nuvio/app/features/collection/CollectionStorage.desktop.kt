package com.nuvio.app.features.collection

import com.nuvio.app.desktop.DesktopPrefs

internal actual object CollectionStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("collectionStorage", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("collectionStorage", "payload", payload)
}
