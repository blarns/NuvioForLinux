package com.nuvio.app.core.sync

import com.nuvio.app.desktop.DesktopPrefs

internal actual object SyncClientIdentityStorage {
    private const val NODE = "sync"
    private const val CLIENT_ID_KEY = "client_instance_id"

    actual fun loadClientId(): String? = DesktopPrefs.getString(NODE, CLIENT_ID_KEY)

    actual fun saveClientId(clientId: String) {
        DesktopPrefs.putString(NODE, CLIENT_ID_KEY, clientId)
    }
}
