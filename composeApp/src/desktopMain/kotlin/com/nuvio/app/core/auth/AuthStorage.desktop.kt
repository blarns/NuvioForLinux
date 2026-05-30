package com.nuvio.app.core.auth

import com.nuvio.app.desktop.DesktopPrefs

internal actual object AuthStorage {
    actual fun loadAnonymousUserId(): String? =
        DesktopPrefs.getString("auth", "anonymousUserId")?.takeIf { it.isNotEmpty() }
    
    actual fun saveAnonymousUserId(userId: String) =
        DesktopPrefs.putString("auth", "anonymousUserId", userId)
        
    actual fun clearAnonymousUserId() =
        DesktopPrefs.putString("auth", "anonymousUserId", "")
}
