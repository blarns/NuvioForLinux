package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object TraktAuthStorage {
    private val store = DesktopStorage.store("nuvio_trakt_auth")

    private fun key(profileId: Int) = "trakt_auth_p$profileId"

    actual fun loadPayload(profileId: Int): String? = store.getString(key(profileId))

    actual fun savePayload(profileId: Int, payload: String) {
        store.putString(key(profileId), payload)
    }

    actual fun removeProfile(profileId: Int) {
        store.putString(key(profileId), null)
    }
}

internal actual object TraktLibraryStorage {
    private val store = DesktopStorage.store("nuvio_trakt_library")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("trakt_library"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("trakt_library"), payload)
    }
}

internal actual object TraktSettingsStorage {
    private val store = DesktopStorage.store("nuvio_trakt_settings")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of("trakt_settings"))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of("trakt_settings"), payload)
    }

    private fun pendingKey(profileId: Int) = "trakt_pending_watch_progress_source_p$profileId"

    actual fun loadPendingWatchProgressSourcePayload(profileId: Int): String? =
        store.getString(pendingKey(profileId))

    actual fun savePendingWatchProgressSourcePayload(profileId: Int, payload: String) {
        store.putString(pendingKey(profileId), payload)
    }

    actual fun clearPendingWatchProgressSourcePayload(profileId: Int) {
        store.putString(pendingKey(profileId), null)
    }
}
