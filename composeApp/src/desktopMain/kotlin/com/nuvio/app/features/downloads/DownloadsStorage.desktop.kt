package com.nuvio.app.features.downloads

import com.nuvio.app.desktop.DesktopPrefs

internal actual object DownloadsStorage {
    actual fun loadPayload(): String? =
        DesktopPrefs.getString("downloadsStorage", "payload")
    actual fun savePayload(payload: String) =
        DesktopPrefs.putString("downloadsStorage", "payload", payload)
}
