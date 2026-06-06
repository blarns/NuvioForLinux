package com.nuvio.app.features.debrid

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncInt
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncInt
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.desktop.DesktopPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val NODE = "debridSettings"
private const val enabledKey = "debrid_enabled"
private const val cloudLibraryEnabledKey = "debrid_cloud_library_enabled"
private const val preferredResolverProviderIdKey = "debrid_preferred_resolver_provider_id"
private const val torboxApiKeyKey = "debrid_torbox_api_key"
private const val realDebridApiKeyKey = "debrid_real_debrid_api_key"
private const val instantPlaybackPreparationLimitKey = "debrid_instant_playback_preparation_limit"
private const val streamMaxResultsKey = "debrid_stream_max_results"
private const val streamSortModeKey = "debrid_stream_sort_mode"
private const val streamMinimumQualityKey = "debrid_stream_minimum_quality"
private const val streamDolbyVisionFilterKey = "debrid_stream_dolby_vision_filter"
private const val streamHdrFilterKey = "debrid_stream_hdr_filter"
private const val streamCodecFilterKey = "debrid_stream_codec_filter"
private const val streamPreferencesKey = "debrid_stream_preferences"
private const val streamNameTemplateKey = "debrid_stream_name_template"
private const val streamDescriptionTemplateKey = "debrid_stream_description_template"
private const val streamBadgeRulesKey = "debrid_stream_badge_rules"

private fun loadBool(key: String): Boolean? = DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of(key))
private fun saveBool(key: String, value: Boolean) = DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of(key), value)
private fun loadStr(key: String): String? = DesktopPrefs.getString(NODE, ProfileScopedKey.of(key))
private fun saveStr(key: String, value: String) = DesktopPrefs.putString(NODE, ProfileScopedKey.of(key), value)
private fun loadIntVal(key: String): Int? = DesktopPrefs.getInt(NODE, ProfileScopedKey.of(key))
private fun saveIntVal(key: String, value: Int) = DesktopPrefs.putInt(NODE, ProfileScopedKey.of(key), value)

private fun providerApiKeyKey(providerId: String): String {
    val normalized = DebridProviders.byId(providerId)?.id
        ?: providerId.trim().lowercase().replace(Regex("[^a-z0-9_]+"), "_")
    return when (normalized) {
        DebridProviders.TORBOX_ID -> torboxApiKeyKey
        DebridProviders.REAL_DEBRID_ID -> realDebridApiKeyKey
        else -> "debrid_${normalized}_api_key"
    }
}

private fun syncKeys(): List<String> =
    listOf(
        enabledKey,
        cloudLibraryEnabledKey,
        preferredResolverProviderIdKey,
        instantPlaybackPreparationLimitKey,
        streamMaxResultsKey,
        streamSortModeKey,
        streamMinimumQualityKey,
        streamDolbyVisionFilterKey,
        streamHdrFilterKey,
        streamCodecFilterKey,
        streamPreferencesKey,
        streamNameTemplateKey,
        streamDescriptionTemplateKey,
    ) + DebridProviders.all().map { providerApiKeyKey(it.id) }

internal actual object DebridSettingsStorage {
    actual fun loadEnabled(): Boolean? = loadBool(enabledKey)
    actual fun saveEnabled(enabled: Boolean) = saveBool(enabledKey, enabled)
    actual fun loadCloudLibraryEnabled(): Boolean? = loadBool(cloudLibraryEnabledKey)
    actual fun saveCloudLibraryEnabled(enabled: Boolean) = saveBool(cloudLibraryEnabledKey, enabled)
    actual fun loadPreferredResolverProviderId(): String? = loadStr(preferredResolverProviderIdKey)
    actual fun savePreferredResolverProviderId(providerId: String) = saveStr(preferredResolverProviderIdKey, providerId)
    actual fun loadProviderApiKey(providerId: String): String? = loadStr(providerApiKeyKey(providerId))
    actual fun saveProviderApiKey(providerId: String, apiKey: String) = saveStr(providerApiKeyKey(providerId), apiKey)
    actual fun loadTorboxApiKey(): String? = loadProviderApiKey(DebridProviders.TORBOX_ID)
    actual fun saveTorboxApiKey(apiKey: String) = saveProviderApiKey(DebridProviders.TORBOX_ID, apiKey)
    actual fun loadRealDebridApiKey(): String? = loadProviderApiKey(DebridProviders.REAL_DEBRID_ID)
    actual fun saveRealDebridApiKey(apiKey: String) = saveProviderApiKey(DebridProviders.REAL_DEBRID_ID, apiKey)
    actual fun loadInstantPlaybackPreparationLimit(): Int? = loadIntVal(instantPlaybackPreparationLimitKey)
    actual fun saveInstantPlaybackPreparationLimit(limit: Int) = saveIntVal(instantPlaybackPreparationLimitKey, limit)
    actual fun loadStreamMaxResults(): Int? = loadIntVal(streamMaxResultsKey)
    actual fun saveStreamMaxResults(maxResults: Int) = saveIntVal(streamMaxResultsKey, maxResults)
    actual fun loadStreamSortMode(): String? = loadStr(streamSortModeKey)
    actual fun saveStreamSortMode(mode: String) = saveStr(streamSortModeKey, mode)
    actual fun loadStreamMinimumQuality(): String? = loadStr(streamMinimumQualityKey)
    actual fun saveStreamMinimumQuality(quality: String) = saveStr(streamMinimumQualityKey, quality)
    actual fun loadStreamDolbyVisionFilter(): String? = loadStr(streamDolbyVisionFilterKey)
    actual fun saveStreamDolbyVisionFilter(filter: String) = saveStr(streamDolbyVisionFilterKey, filter)
    actual fun loadStreamHdrFilter(): String? = loadStr(streamHdrFilterKey)
    actual fun saveStreamHdrFilter(filter: String) = saveStr(streamHdrFilterKey, filter)
    actual fun loadStreamCodecFilter(): String? = loadStr(streamCodecFilterKey)
    actual fun saveStreamCodecFilter(filter: String) = saveStr(streamCodecFilterKey, filter)
    actual fun loadStreamPreferences(): String? = loadStr(streamPreferencesKey)
    actual fun saveStreamPreferences(preferences: String) = saveStr(streamPreferencesKey, preferences)
    actual fun loadStreamNameTemplate(): String? = loadStr(streamNameTemplateKey)
    actual fun saveStreamNameTemplate(template: String) = saveStr(streamNameTemplateKey, template)
    actual fun loadStreamDescriptionTemplate(): String? = loadStr(streamDescriptionTemplateKey)
    actual fun saveStreamDescriptionTemplate(template: String) = saveStr(streamDescriptionTemplateKey, template)
    actual fun loadStreamBadgeRules(): String? = loadStr(streamBadgeRulesKey)
    actual fun saveStreamBadgeRules(rules: String) = saveStr(streamBadgeRulesKey, rules)

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
        loadCloudLibraryEnabled()?.let { put(cloudLibraryEnabledKey, encodeSyncBoolean(it)) }
        loadPreferredResolverProviderId()?.let { put(preferredResolverProviderIdKey, encodeSyncString(it)) }
        DebridProviders.all().forEach { provider ->
            loadProviderApiKey(provider.id)?.let {
                put(providerApiKeyKey(provider.id), encodeSyncString(it))
            }
        }
        loadInstantPlaybackPreparationLimit()?.let { put(instantPlaybackPreparationLimitKey, encodeSyncInt(it)) }
        loadStreamMaxResults()?.let { put(streamMaxResultsKey, encodeSyncInt(it)) }
        loadStreamSortMode()?.let { put(streamSortModeKey, encodeSyncString(it)) }
        loadStreamMinimumQuality()?.let { put(streamMinimumQualityKey, encodeSyncString(it)) }
        loadStreamDolbyVisionFilter()?.let { put(streamDolbyVisionFilterKey, encodeSyncString(it)) }
        loadStreamHdrFilter()?.let { put(streamHdrFilterKey, encodeSyncString(it)) }
        loadStreamCodecFilter()?.let { put(streamCodecFilterKey, encodeSyncString(it)) }
        loadStreamPreferences()?.let { put(streamPreferencesKey, encodeSyncString(it)) }
        loadStreamNameTemplate()?.let { put(streamNameTemplateKey, encodeSyncString(it)) }
        loadStreamDescriptionTemplate()?.let { put(streamDescriptionTemplateKey, encodeSyncString(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys().forEach { DesktopPrefs.node(NODE).remove(ProfileScopedKey.of(it)) }
        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncBoolean(cloudLibraryEnabledKey)?.let(::saveCloudLibraryEnabled)
        payload.decodeSyncString(preferredResolverProviderIdKey)?.let(::savePreferredResolverProviderId)
        DebridProviders.all().forEach { provider ->
            payload.decodeSyncString(providerApiKeyKey(provider.id))?.let { apiKey ->
                saveProviderApiKey(provider.id, apiKey)
            }
        }
        payload.decodeSyncInt(instantPlaybackPreparationLimitKey)?.let(::saveInstantPlaybackPreparationLimit)
        payload.decodeSyncInt(streamMaxResultsKey)?.let(::saveStreamMaxResults)
        payload.decodeSyncString(streamSortModeKey)?.let(::saveStreamSortMode)
        payload.decodeSyncString(streamMinimumQualityKey)?.let(::saveStreamMinimumQuality)
        payload.decodeSyncString(streamDolbyVisionFilterKey)?.let(::saveStreamDolbyVisionFilter)
        payload.decodeSyncString(streamHdrFilterKey)?.let(::saveStreamHdrFilter)
        payload.decodeSyncString(streamCodecFilterKey)?.let(::saveStreamCodecFilter)
        payload.decodeSyncString(streamPreferencesKey)?.let(::saveStreamPreferences)
        payload.decodeSyncString(streamNameTemplateKey)?.let(::saveStreamNameTemplate)
        payload.decodeSyncString(streamDescriptionTemplateKey)?.let(::saveStreamDescriptionTemplate)
    }
}
