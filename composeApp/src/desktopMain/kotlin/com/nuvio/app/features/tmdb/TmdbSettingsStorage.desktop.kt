package com.nuvio.app.features.tmdb

import kotlinx.serialization.json.JsonObject

internal actual object TmdbSettingsStorage {
    actual fun loadEnabled(): Boolean? = null
    actual fun saveEnabled(enabled: Boolean) {}
    actual fun loadApiKey(): String? = null
    actual fun saveApiKey(apiKey: String) {}
    actual fun loadLanguage(): String? = null
    actual fun saveLanguage(language: String) {}
    actual fun loadUseTrailers(): Boolean? = null
    actual fun saveUseTrailers(enabled: Boolean) {}
    actual fun loadUseArtwork(): Boolean? = null
    actual fun saveUseArtwork(enabled: Boolean) {}
    actual fun loadUseBasicInfo(): Boolean? = null
    actual fun saveUseBasicInfo(enabled: Boolean) {}
    actual fun loadUseDetails(): Boolean? = null
    actual fun saveUseDetails(enabled: Boolean) {}
    actual fun loadUseCredits(): Boolean? = null
    actual fun saveUseCredits(enabled: Boolean) {}
    actual fun loadUseProductions(): Boolean? = null
    actual fun saveUseProductions(enabled: Boolean) {}
    actual fun loadUseNetworks(): Boolean? = null
    actual fun saveUseNetworks(enabled: Boolean) {}
    actual fun loadUseEpisodes(): Boolean? = null
    actual fun saveUseEpisodes(enabled: Boolean) {}
    actual fun loadUseSeasonPosters(): Boolean? = null
    actual fun saveUseSeasonPosters(enabled: Boolean) {}
    actual fun loadUseMoreLikeThis(): Boolean? = null
    actual fun saveUseMoreLikeThis(enabled: Boolean) {}
    actual fun loadUseCollections(): Boolean? = null
    actual fun saveUseCollections(enabled: Boolean) {}
    actual fun exportToSyncPayload(): JsonObject = kotlinx.serialization.json.buildJsonObject {}
    actual fun replaceFromSyncPayload(payload: JsonObject) {}
}
