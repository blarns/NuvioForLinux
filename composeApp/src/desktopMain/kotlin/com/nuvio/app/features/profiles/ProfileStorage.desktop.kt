package com.nuvio.app.features.profiles

import com.nuvio.app.desktop.DesktopPrefs

internal actual object ProfileStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("profileStorage", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("profileStorage", "payload", payload)
}
