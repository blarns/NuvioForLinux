package com.nuvio.app.features.simkl

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object SimklSyncStorage {
    private val store = DesktopStorage.store("nuvio_simkl_sync")

    actual fun loadPayload(): String? = store.getString(ProfileScopedKey.of("simkl_sync"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("simkl_sync"), payload)
    }

    actual fun removeProfile(profileId: Int) {
        store.putString(ProfileScopedKey.of("simkl_sync", profileId), null)
    }
}
