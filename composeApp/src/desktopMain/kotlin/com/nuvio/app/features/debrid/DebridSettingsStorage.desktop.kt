package com.nuvio.app.features.debrid

import kotlinx.serialization.json.JsonObject

internal actual object DebridSettingsStorage {
    actual fun loadEnabled(): Boolean? = null
    actual fun saveEnabled(enabled: Boolean) {}
    actual fun loadCloudLibraryEnabled(): Boolean? = null
    actual fun saveCloudLibraryEnabled(enabled: Boolean) {}
    actual fun loadPreferredResolverProviderId(): String? = null
    actual fun savePreferredResolverProviderId(providerId: String) {}
    actual fun loadProviderApiKey(providerId: String): String? = null
    actual fun saveProviderApiKey(providerId: String, apiKey: String) {}
    actual fun loadTorboxApiKey(): String? = null
    actual fun saveTorboxApiKey(apiKey: String) {}
    actual fun loadRealDebridApiKey(): String? = null
    actual fun saveRealDebridApiKey(apiKey: String) {}
    actual fun loadInstantPlaybackPreparationLimit(): Int? = null
    actual fun saveInstantPlaybackPreparationLimit(limit: Int) {}
    actual fun loadStreamMaxResults(): Int? = null
    actual fun saveStreamMaxResults(maxResults: Int) {}
    actual fun loadStreamSortMode(): String? = null
    actual fun saveStreamSortMode(mode: String) {}
    actual fun loadStreamMinimumQuality(): String? = null
    actual fun saveStreamMinimumQuality(quality: String) {}
    actual fun loadStreamDolbyVisionFilter(): String? = null
    actual fun saveStreamDolbyVisionFilter(filter: String) {}
    actual fun loadStreamHdrFilter(): String? = null
    actual fun saveStreamHdrFilter(filter: String) {}
    actual fun loadStreamCodecFilter(): String? = null
    actual fun saveStreamCodecFilter(filter: String) {}
    actual fun loadStreamPreferences(): String? = null
    actual fun saveStreamPreferences(preferences: String) {}
    actual fun loadStreamNameTemplate(): String? = null
    actual fun saveStreamNameTemplate(template: String) {}
    actual fun loadStreamDescriptionTemplate(): String? = null
    actual fun saveStreamDescriptionTemplate(template: String) {}
    actual fun loadStreamBadgeRules(): String? = null
    actual fun saveStreamBadgeRules(rules: String) {}
    actual fun exportToSyncPayload(): JsonObject = kotlinx.serialization.json.JsonObject(emptyMap<String, kotlinx.serialization.json.JsonElement>())
    actual fun replaceFromSyncPayload(payload: JsonObject) {}
}
