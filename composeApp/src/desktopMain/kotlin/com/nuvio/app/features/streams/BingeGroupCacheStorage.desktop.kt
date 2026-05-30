package com.nuvio.app.features.streams

import com.nuvio.app.desktop.DesktopPrefs

internal actual object BingeGroupCacheStorage {
    actual fun load(hashedKey: String): String? =
        DesktopPrefs.getString("bingeGroupCache", hashedKey)
    actual fun save(hashedKey: String, value: String) =
        DesktopPrefs.putString("bingeGroupCache", hashedKey, value)
    actual fun remove(hashedKey: String) =
        DesktopPrefs.putString("bingeGroupCache", hashedKey, "")
}
