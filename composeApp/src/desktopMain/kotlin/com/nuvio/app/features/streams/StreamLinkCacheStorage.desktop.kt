package com.nuvio.app.features.streams

import com.nuvio.app.desktop.DesktopPrefs

internal actual object StreamLinkCacheStorage {
    actual fun loadEntry(hashedKey: String): String? =
        DesktopPrefs.getString("streamLinkCache", hashedKey)
    actual fun saveEntry(hashedKey: String, payload: String) =
        DesktopPrefs.putString("streamLinkCache", hashedKey, payload)
    actual fun removeEntry(hashedKey: String) =
        DesktopPrefs.putString("streamLinkCache", hashedKey, "")
}
