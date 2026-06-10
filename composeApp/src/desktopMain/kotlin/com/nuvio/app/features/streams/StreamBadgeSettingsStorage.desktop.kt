package com.nuvio.app.features.streams

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.desktop.DesktopPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal actual object StreamBadgeSettingsStorage {
    private const val NODE = "streamBadges"
    private const val streamBadgeRulesKey = "stream_badge_rules"
    private const val showFileSizeBadgesKey = "show_file_size_badges"
    private const val streamBadgePlacementKey = "stream_badge_placement"

    // Pre-df15c4d2 the fork stored badge rules on the debrid settings node; the repository
    // migrates from here on first load (same as Android's legacy SharedPreferences file).
    private const val legacyDebridNode = "debridSettings"
    private const val legacyDebridStreamBadgeRulesKey = "debrid_stream_badge_rules"

    private val syncKeys = listOf(streamBadgeRulesKey, showFileSizeBadgesKey, streamBadgePlacementKey)

    actual fun loadStreamBadgeRules(): String? =
        DesktopPrefs.getString(NODE, ProfileScopedKey.of(streamBadgeRulesKey))

    actual fun saveStreamBadgeRules(rules: String) {
        DesktopPrefs.putString(NODE, ProfileScopedKey.of(streamBadgeRulesKey), rules)
    }

    actual fun loadShowFileSizeBadges(): Boolean? =
        DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of(showFileSizeBadgesKey))

    actual fun saveShowFileSizeBadges(enabled: Boolean) {
        DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of(showFileSizeBadgesKey), enabled)
    }

    actual fun loadStreamBadgePlacement(): String? =
        DesktopPrefs.getString(NODE, ProfileScopedKey.of(streamBadgePlacementKey))

    actual fun saveStreamBadgePlacement(placement: String) {
        DesktopPrefs.putString(NODE, ProfileScopedKey.of(streamBadgePlacementKey), placement)
    }

    actual fun loadLegacyDebridStreamBadgeRules(): String? =
        DesktopPrefs.getString(legacyDebridNode, ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))

    actual fun clearLegacyDebridStreamBadgeRules() {
        DesktopPrefs.remove(legacyDebridNode, ProfileScopedKey.of(legacyDebridStreamBadgeRulesKey))
    }

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadStreamBadgeRules()?.let { put(streamBadgeRulesKey, encodeSyncString(it)) }
        loadShowFileSizeBadges()?.let { put(showFileSizeBadgesKey, encodeSyncBoolean(it)) }
        loadStreamBadgePlacement()?.let { put(streamBadgePlacementKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { DesktopPrefs.remove(NODE, ProfileScopedKey.of(it)) }
        payload.decodeSyncString(streamBadgeRulesKey)?.let(::saveStreamBadgeRules)
        payload.decodeSyncBoolean(showFileSizeBadgesKey)?.let(::saveShowFileSizeBadges)
        payload.decodeSyncString(streamBadgePlacementKey)?.let(::saveStreamBadgePlacement)
    }
}
