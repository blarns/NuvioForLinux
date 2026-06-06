package com.nuvio.app.features.mdblist

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.desktop.DesktopPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val NODE = "mdblistSettings"
private const val enabledKey = "mdblist_enabled"
private const val apiKeyKey = "mdblist_api_key"
private const val useImdbKey = "mdblist_use_imdb"
private const val useTmdbKey = "mdblist_use_tmdb"
private const val useTomatoesKey = "mdblist_use_tomatoes"
private const val useMetacriticKey = "mdblist_use_metacritic"
private const val useTraktKey = "mdblist_use_trakt"
private const val useLetterboxdKey = "mdblist_use_letterboxd"
private const val useAudienceKey = "mdblist_use_audience"

private val syncKeys = listOf(
    enabledKey, apiKeyKey, useImdbKey, useTmdbKey, useTomatoesKey,
    useMetacriticKey, useTraktKey, useLetterboxdKey, useAudienceKey,
)

private fun loadBool(key: String): Boolean? = DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of(key))
private fun saveBool(key: String, value: Boolean) = DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of(key), value)
private fun loadStr(key: String): String? = DesktopPrefs.getString(NODE, ProfileScopedKey.of(key))
private fun saveStr(key: String, value: String) = DesktopPrefs.putString(NODE, ProfileScopedKey.of(key), value)

internal actual object MdbListSettingsStorage {
    actual fun loadEnabled(): Boolean? = loadBool(enabledKey)
    actual fun saveEnabled(enabled: Boolean) = saveBool(enabledKey, enabled)
    actual fun loadApiKey(): String? = loadStr(apiKeyKey)
    actual fun saveApiKey(apiKey: String) = saveStr(apiKeyKey, apiKey)
    actual fun loadUseImdb(): Boolean? = loadBool(useImdbKey)
    actual fun saveUseImdb(enabled: Boolean) = saveBool(useImdbKey, enabled)
    actual fun loadUseTmdb(): Boolean? = loadBool(useTmdbKey)
    actual fun saveUseTmdb(enabled: Boolean) = saveBool(useTmdbKey, enabled)
    actual fun loadUseTomatoes(): Boolean? = loadBool(useTomatoesKey)
    actual fun saveUseTomatoes(enabled: Boolean) = saveBool(useTomatoesKey, enabled)
    actual fun loadUseMetacritic(): Boolean? = loadBool(useMetacriticKey)
    actual fun saveUseMetacritic(enabled: Boolean) = saveBool(useMetacriticKey, enabled)
    actual fun loadUseTrakt(): Boolean? = loadBool(useTraktKey)
    actual fun saveUseTrakt(enabled: Boolean) = saveBool(useTraktKey, enabled)
    actual fun loadUseLetterboxd(): Boolean? = loadBool(useLetterboxdKey)
    actual fun saveUseLetterboxd(enabled: Boolean) = saveBool(useLetterboxdKey, enabled)
    actual fun loadUseAudience(): Boolean? = loadBool(useAudienceKey)
    actual fun saveUseAudience(enabled: Boolean) = saveBool(useAudienceKey, enabled)

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
        loadApiKey()?.let { put(apiKeyKey, encodeSyncString(it)) }
        loadUseImdb()?.let { put(useImdbKey, encodeSyncBoolean(it)) }
        loadUseTmdb()?.let { put(useTmdbKey, encodeSyncBoolean(it)) }
        loadUseTomatoes()?.let { put(useTomatoesKey, encodeSyncBoolean(it)) }
        loadUseMetacritic()?.let { put(useMetacriticKey, encodeSyncBoolean(it)) }
        loadUseTrakt()?.let { put(useTraktKey, encodeSyncBoolean(it)) }
        loadUseLetterboxd()?.let { put(useLetterboxdKey, encodeSyncBoolean(it)) }
        loadUseAudience()?.let { put(useAudienceKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { DesktopPrefs.node(NODE).remove(ProfileScopedKey.of(it)) }
        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncString(apiKeyKey)?.let(::saveApiKey)
        payload.decodeSyncBoolean(useImdbKey)?.let(::saveUseImdb)
        payload.decodeSyncBoolean(useTmdbKey)?.let(::saveUseTmdb)
        payload.decodeSyncBoolean(useTomatoesKey)?.let(::saveUseTomatoes)
        payload.decodeSyncBoolean(useMetacriticKey)?.let(::saveUseMetacritic)
        payload.decodeSyncBoolean(useTraktKey)?.let(::saveUseTrakt)
        payload.decodeSyncBoolean(useLetterboxdKey)?.let(::saveUseLetterboxd)
        payload.decodeSyncBoolean(useAudienceKey)?.let(::saveUseAudience)
    }
}
