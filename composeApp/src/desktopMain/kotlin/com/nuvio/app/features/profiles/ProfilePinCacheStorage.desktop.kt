package com.nuvio.app.features.profiles

import com.nuvio.app.desktop.DesktopPrefs

internal actual object ProfilePinCacheStorage {
    actual fun loadPayload(profileIndex: Int): String? =
        DesktopPrefs.getString("profilePinCache", "payload_$profileIndex")
    actual fun savePayload(profileIndex: Int, payload: String) =
        DesktopPrefs.putString("profilePinCache", "payload_$profileIndex", payload)
    actual fun removePayload(profileIndex: Int) =
        DesktopPrefs.putString("profilePinCache", "payload_$profileIndex", "")
}
