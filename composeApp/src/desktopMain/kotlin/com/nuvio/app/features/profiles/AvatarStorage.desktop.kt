package com.nuvio.app.features.profiles

import com.nuvio.app.desktop.DesktopPrefs

internal actual object AvatarStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("avatarStorage", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("avatarStorage", "payload", payload)
}
