package com.nuvio.app.features.watched

import com.nuvio.app.desktop.DesktopPrefs

 actual object WatchedStorage {
    actual fun loadPayload(profileId: Int): String? =
        DesktopPrefs.getString("watchedStorage", "payload_$profileId")
    actual fun savePayload(profileId: Int, payload: String) =
        DesktopPrefs.putString("watchedStorage", "payload_$profileId", payload)
}
