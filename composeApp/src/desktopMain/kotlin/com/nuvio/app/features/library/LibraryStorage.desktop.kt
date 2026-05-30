package com.nuvio.app.features.library

import com.nuvio.app.desktop.DesktopPrefs

internal actual object LibraryStorage {
    actual fun loadPayload(profileId: Int): String? =
        DesktopPrefs.getString("libraryStorage", "payload_$profileId")
    actual fun savePayload(profileId: Int, payload: String) =
        DesktopPrefs.putString("libraryStorage", "payload_$profileId", payload)
}
