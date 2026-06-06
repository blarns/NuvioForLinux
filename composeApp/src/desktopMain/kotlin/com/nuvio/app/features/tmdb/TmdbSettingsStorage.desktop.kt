package com.nuvio.app.features.tmdb

import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.core.sync.decodeSyncBoolean
import com.nuvio.app.core.sync.decodeSyncString
import com.nuvio.app.core.sync.encodeSyncBoolean
import com.nuvio.app.core.sync.encodeSyncString
import com.nuvio.app.desktop.DesktopPrefs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val NODE = "tmdbSettings"
private const val enabledKey = "tmdb_enabled"
private const val apiKeyKey = "tmdb_api_key"
private const val languageKey = "tmdb_language"
private const val useTrailersKey = "tmdb_use_trailers"
private const val useArtworkKey = "tmdb_use_artwork"
private const val useBasicInfoKey = "tmdb_use_basic_info"
private const val useDetailsKey = "tmdb_use_details"
private const val useCreditsKey = "tmdb_use_credits"
private const val useProductionsKey = "tmdb_use_productions"
private const val useNetworksKey = "tmdb_use_networks"
private const val useEpisodesKey = "tmdb_use_episodes"
private const val useSeasonPostersKey = "tmdb_use_season_posters"
private const val useMoreLikeThisKey = "tmdb_use_more_like_this"
private const val useCollectionsKey = "tmdb_use_collections"

private val syncKeys = listOf(
    enabledKey, apiKeyKey, languageKey, useTrailersKey, useArtworkKey,
    useBasicInfoKey, useDetailsKey, useCreditsKey, useProductionsKey,
    useNetworksKey, useEpisodesKey, useSeasonPostersKey, useMoreLikeThisKey, useCollectionsKey,
)

private fun loadBool(key: String): Boolean? = DesktopPrefs.getBoolean(NODE, ProfileScopedKey.of(key))
private fun saveBool(key: String, value: Boolean) = DesktopPrefs.putBoolean(NODE, ProfileScopedKey.of(key), value)
private fun loadStr(key: String): String? = DesktopPrefs.getString(NODE, ProfileScopedKey.of(key))
private fun saveStr(key: String, value: String) = DesktopPrefs.putString(NODE, ProfileScopedKey.of(key), value)

internal actual object TmdbSettingsStorage {
    actual fun loadEnabled(): Boolean? = loadBool(enabledKey)
    actual fun saveEnabled(enabled: Boolean) = saveBool(enabledKey, enabled)
    actual fun loadApiKey(): String? = loadStr(apiKeyKey)
    actual fun saveApiKey(apiKey: String) = saveStr(apiKeyKey, apiKey)
    actual fun loadLanguage(): String? = loadStr(languageKey)
    actual fun saveLanguage(language: String) = saveStr(languageKey, language)
    actual fun loadUseTrailers(): Boolean? = loadBool(useTrailersKey)
    actual fun saveUseTrailers(enabled: Boolean) = saveBool(useTrailersKey, enabled)
    actual fun loadUseArtwork(): Boolean? = loadBool(useArtworkKey)
    actual fun saveUseArtwork(enabled: Boolean) = saveBool(useArtworkKey, enabled)
    actual fun loadUseBasicInfo(): Boolean? = loadBool(useBasicInfoKey)
    actual fun saveUseBasicInfo(enabled: Boolean) = saveBool(useBasicInfoKey, enabled)
    actual fun loadUseDetails(): Boolean? = loadBool(useDetailsKey)
    actual fun saveUseDetails(enabled: Boolean) = saveBool(useDetailsKey, enabled)
    actual fun loadUseCredits(): Boolean? = loadBool(useCreditsKey)
    actual fun saveUseCredits(enabled: Boolean) = saveBool(useCreditsKey, enabled)
    actual fun loadUseProductions(): Boolean? = loadBool(useProductionsKey)
    actual fun saveUseProductions(enabled: Boolean) = saveBool(useProductionsKey, enabled)
    actual fun loadUseNetworks(): Boolean? = loadBool(useNetworksKey)
    actual fun saveUseNetworks(enabled: Boolean) = saveBool(useNetworksKey, enabled)
    actual fun loadUseEpisodes(): Boolean? = loadBool(useEpisodesKey)
    actual fun saveUseEpisodes(enabled: Boolean) = saveBool(useEpisodesKey, enabled)
    actual fun loadUseSeasonPosters(): Boolean? = loadBool(useSeasonPostersKey)
    actual fun saveUseSeasonPosters(enabled: Boolean) = saveBool(useSeasonPostersKey, enabled)
    actual fun loadUseMoreLikeThis(): Boolean? = loadBool(useMoreLikeThisKey)
    actual fun saveUseMoreLikeThis(enabled: Boolean) = saveBool(useMoreLikeThisKey, enabled)
    actual fun loadUseCollections(): Boolean? = loadBool(useCollectionsKey)
    actual fun saveUseCollections(enabled: Boolean) = saveBool(useCollectionsKey, enabled)

    actual fun exportToSyncPayload(): JsonObject = buildJsonObject {
        loadEnabled()?.let { put(enabledKey, encodeSyncBoolean(it)) }
        loadApiKey()?.let { put(apiKeyKey, encodeSyncString(it)) }
        loadLanguage()?.let { put(languageKey, encodeSyncString(it)) }
        loadUseTrailers()?.let { put(useTrailersKey, encodeSyncBoolean(it)) }
        loadUseArtwork()?.let { put(useArtworkKey, encodeSyncBoolean(it)) }
        loadUseBasicInfo()?.let { put(useBasicInfoKey, encodeSyncBoolean(it)) }
        loadUseDetails()?.let { put(useDetailsKey, encodeSyncBoolean(it)) }
        loadUseCredits()?.let { put(useCreditsKey, encodeSyncBoolean(it)) }
        loadUseProductions()?.let { put(useProductionsKey, encodeSyncBoolean(it)) }
        loadUseNetworks()?.let { put(useNetworksKey, encodeSyncBoolean(it)) }
        loadUseEpisodes()?.let { put(useEpisodesKey, encodeSyncBoolean(it)) }
        loadUseSeasonPosters()?.let { put(useSeasonPostersKey, encodeSyncBoolean(it)) }
        loadUseMoreLikeThis()?.let { put(useMoreLikeThisKey, encodeSyncBoolean(it)) }
        loadUseCollections()?.let { put(useCollectionsKey, encodeSyncBoolean(it)) }
    }

    actual fun replaceFromSyncPayload(payload: JsonObject) {
        syncKeys.forEach { DesktopPrefs.node(NODE).remove(ProfileScopedKey.of(it)) }
        payload.decodeSyncBoolean(enabledKey)?.let(::saveEnabled)
        payload.decodeSyncString(apiKeyKey)?.let(::saveApiKey)
        payload.decodeSyncString(languageKey)?.let(::saveLanguage)
        payload.decodeSyncBoolean(useTrailersKey)?.let(::saveUseTrailers)
        payload.decodeSyncBoolean(useArtworkKey)?.let(::saveUseArtwork)
        payload.decodeSyncBoolean(useBasicInfoKey)?.let(::saveUseBasicInfo)
        payload.decodeSyncBoolean(useDetailsKey)?.let(::saveUseDetails)
        payload.decodeSyncBoolean(useCreditsKey)?.let(::saveUseCredits)
        payload.decodeSyncBoolean(useProductionsKey)?.let(::saveUseProductions)
        payload.decodeSyncBoolean(useNetworksKey)?.let(::saveUseNetworks)
        payload.decodeSyncBoolean(useEpisodesKey)?.let(::saveUseEpisodes)
        payload.decodeSyncBoolean(useSeasonPostersKey)?.let(::saveUseSeasonPosters)
        payload.decodeSyncBoolean(useMoreLikeThisKey)?.let(::saveUseMoreLikeThis)
        payload.decodeSyncBoolean(useCollectionsKey)?.let(::saveUseCollections)
    }
}
