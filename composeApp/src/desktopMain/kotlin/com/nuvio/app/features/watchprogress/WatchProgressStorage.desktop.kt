package com.nuvio.app.features.watchprogress

import com.nuvio.app.desktop.DesktopPrefs

internal actual object WatchProgressStorage {
    actual fun loadPayload(profileId: Int): String? =
        DesktopPrefs.getString("watchProgress", "payload_$profileId")
    actual fun savePayload(profileId: Int, payload: String) =
        DesktopPrefs.putString("watchProgress", "payload_$profileId", payload)
}
