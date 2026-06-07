package com.nuvio.app.features.trakt

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.desktop.DesktopPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val NODE = "traktComments"
private const val enabledKey = "comments_enabled"

internal actual object TraktCommentsStorage {
    actual fun loadEnabled(): Boolean? =
        DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of(enabledKey))

    actual fun saveEnabled(enabled: Boolean) =
        DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of(enabledKey), enabled)

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        DesktopPrefs.node(NODE).remove(ProfileScopedKey.of(enabledKey))
        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
    }
}
