package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.desktop.DesktopPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual object StreamBadgeSettingsStorage {
    private const val NODE = "streamBadges"
    private const val streamBadgeRulesKey = "stream_badge_rules"

    // Pre-df15c4d2 the fork stored badge rules on the debrid settings node; the repository
    // migrates from here on first load (same as Android's legacy SharedPreferences file).
    private const val legacyDebridNode = "debridSettings"
    private const val legacyDebridStreamBadgeRulesKey = "debrid_stream_badge_rules"

    actual fun loadStreamBadgeRules(): String? =
        DesktopPrefs.getString(NODE, ProfileScopedKey.of(streamBadgeRulesKey))

    actual fun saveStreamBadgeRules(rules: String) {
        DesktopPrefs.putString(NODE, ProfileScopedKey.of(streamBadgeRulesKey), rules)
    }

    actual fun loadLegacyDebridStreamBadgeRules(): String? =
        DesktopPrefs.getString(legacyDebridNode, ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))

    actual fun clearLegacyDebridStreamBadgeRules() {
        DesktopPrefs.remove(legacyDebridNode, ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadStreamBadgeRules()?.let { put(streamBadgeRulesKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        DesktopPrefs.remove(NODE, ProfileScopedKey.of(streamBadgeRulesKey))
        payload.decodeSyncString(streamBadgeRulesKey)?.let(::saveStreamBadgeRules)
    }
}
